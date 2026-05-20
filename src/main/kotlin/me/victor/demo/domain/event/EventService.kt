package me.victor.demo.domain.event

import me.victor.demo.common.BadRequestException
import me.victor.demo.common.ConflictException
import me.victor.demo.common.NotFoundException
import me.victor.demo.domain.session.SessionStatus
import me.victor.demo.infra.persistence.AppendResult
import me.victor.demo.infra.persistence.EventRepository
import me.victor.demo.infra.persistence.ParticipantRepository
import me.victor.demo.infra.persistence.SessionRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * 모든 쓰기의 단일 진입점. REST/WebSocket/내부 호출이 모두 여기를 통과해야
 * 이벤트 append + projection 갱신이 한 트랜잭션에 묶여 부분 실패가 불가능해진다.
 *
 * 타입별 projection:
 *   JOIN → participants.left_at=NULL,  LEAVE → left_at=NOW(),  SESSION_END → status=ENDED.
 *   MESSAGE*는 projection 없음 (조회 시 리플레이/스냅샷에서 산출).
 */
@Service
class EventService(
    private val events: EventRepository,
    private val sessions: SessionRepository,
    private val participants: ParticipantRepository,
    private val publisher: ApplicationEventPublisher,
) {

    @Transactional
    fun append(cmd: AppendCommand): AppendResult {
        val session = sessions.findById(cmd.sessionId)
            ?: throw NotFoundException("session ${cmd.sessionId} not found")
        // ENDED 세션은 이후 이벤트 거절. 단 SESSION_END 자체의 멱등 재전송은 ON CONFLICT가 흡수.
        if (session.status == SessionStatus.ENDED && cmd.type != ChatEventType.SESSION_END) {
            throw ConflictException("session is ENDED")
        }
        validate(cmd)

        val result = events.append(
            cmd.sessionId, cmd.type, cmd.actorUserId,
            cmd.clientEventId, cmd.clientTs, cmd.payload,
        )

        // 멱등 hit이면 projection/publish 모두 스킵 — 중복 반영을 모든 레이어에서 차단.
        if (result.wasInserted) {
            applyProjection(result.event)
            sessions.bumpLastEventSeq(cmd.sessionId, result.event.serverSeq)
            // AFTER_COMMIT 구독자(브로드캐스터/스냅샷 트리거)로 fan-out. rollback된 이벤트는 안 새어나감.
            publisher.publishEvent(EventAppended(result.event))
        }
        return result
    }

    fun history(sessionId: UUID, fromSeq: Long?, toSeq: Long?, limit: Int): List<ChatEvent> {
        sessions.findById(sessionId) ?: throw NotFoundException("session $sessionId not found")
        return events.findBySession(sessionId, upToSeq = toSeq, fromSeq = fromSeq, limit = limit)
    }

    private fun validate(cmd: AppendCommand) {
        if (cmd.clientEventId.isBlank()) throw BadRequestException("clientEventId is required")
        when (cmd.type) {
            ChatEventType.MESSAGE, ChatEventType.MESSAGE_EDIT -> {
                require("messageId" in cmd.payload) { "payload.messageId required" }
                require("text" in cmd.payload) { "payload.text required" }
                require(cmd.actorUserId != null) { "actorUserId required" }
            }
            ChatEventType.MESSAGE_DELETE -> {
                require("messageId" in cmd.payload) { "payload.messageId required" }
                require(cmd.actorUserId != null) { "actorUserId required" }
            }
            ChatEventType.JOIN, ChatEventType.LEAVE,
            ChatEventType.DISCONNECT, ChatEventType.RECONNECT ->
                require(cmd.actorUserId != null) { "actorUserId required" }
            ChatEventType.SESSION_END -> Unit
        }
    }

    private fun require(condition: Boolean, msg: () -> String) {
        if (!condition) throw BadRequestException(msg())
    }

    private fun applyProjection(event: ChatEvent) {
        when (event.type) {
            ChatEventType.JOIN -> participants.join(event.sessionId, event.actorUserId!!)
            ChatEventType.LEAVE -> participants.leave(event.sessionId, event.actorUserId!!)
            ChatEventType.SESSION_END -> sessions.updateStatus(
                event.sessionId, SessionStatus.ENDED,
                OffsetDateTime.ofInstant(event.serverTs, ZoneOffset.UTC),
            )
            else -> Unit
        }
    }
}

/** 신규 이벤트 커밋 후 내부 발행. 멱등 hit은 publish되지 않으므로 구독자는 항상 신규만 받는다. */
data class EventAppended(val event: ChatEvent)

/** EventService.append의 명령. REST/WebSocket이 이 형태로 변환해 단일 진입점을 통과. */
data class AppendCommand(
    val sessionId: UUID,
    val type: ChatEventType,
    /** 시스템 이벤트는 null. */
    val actorUserId: String?,
    /** 클라 발급 멱등성 키. */
    val clientEventId: String,
    /** 클라 보고 시각. 순서 판정엔 미사용. */
    val clientTs: Instant,
    val payload: Map<String, Any?> = emptyMap(),
)
