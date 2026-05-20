package me.victor.demo.domain.timeline

import me.victor.demo.common.NotFoundException
import me.victor.demo.domain.event.ChatEvent
import me.victor.demo.domain.event.ChatEventType
import me.victor.demo.domain.session.SessionStatus
import me.victor.demo.infra.json.JsonbCodec
import me.victor.demo.infra.persistence.EventRepository
import me.victor.demo.infra.persistence.SessionRepository
import me.victor.demo.infra.persistence.SnapshotRepository
import org.jooq.JSONB
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/** 시점 t의 참여자. JOIN/LEAVE 적용 결과. */
data class ParticipantSnapshot(
    val userId: String,
    /** 최초 참여 시각 (재참여해도 유지). */
    val joinedAt: Instant,
    /** null이면 t 시점에 참여 중. */
    val leftAt: Instant?,
)

/** 메시지 라이프사이클. EDIT/DELETE의 결과. */
enum class MessageStatus { SENT, EDITED, DELETED }

/** 시점 t의 메시지. 같은 messageId의 MESSAGE/EDIT/DELETE를 머지한 결과. */
data class MessageSnapshot(
    val messageId: String,
    /** 최초 MESSAGE의 actor. EDIT 주체와 다를 수 있음. */
    val sender: String,
    /** DELETED면 null로 마스킹. */
    val text: String?,
    val status: MessageStatus,
    /** 최초 MESSAGE의 server_ts. */
    val createdAt: Instant,
    /** 가장 최근 변경(EDIT/DELETE)의 server_ts. SENT면 createdAt과 동일. */
    val lastModifiedAt: Instant,
)

/** 시점 t의 세션 전체 상태. asOfSeq를 클라가 보관하면 그 이후만 incremental sync 가능. */
data class Timeline(
    val sessionId: UUID,
    val status: SessionStatus,
    /** 복원에 사용된 컷오프 server_seq = 다음 incremental sync 시작점. */
    val asOfSeq: Long,
    /** 복원 기준 시각. server_ts<=asOfTime인 이벤트만 적용됨. */
    val asOfTime: Instant,
    val participants: List<ParticipantSnapshot>,
    /** 전송 순서. DELETED 상태도 포함 (text는 null). */
    val messages: List<MessageSnapshot>,
)

/** snapshot 테이블에 저장되는 형태. Timeline에서 복원 컨텍스트만 뺀 것 — 다른 시점의 base로 재사용 가능. */
data class SnapshotState(
    val status: SessionStatus,
    val participants: List<ParticipantSnapshot>,
    val messages: List<MessageSnapshot>,
)

/**
 * 이벤트 기반 시점 복원.
 *   1) cutoffSeq = events.maxSeqAt(at)
 *   2) snapshots에서 base 가져와 (없으면 빈 상태) startSeq 결정
 *   3) startSeq < seq <= cutoffSeq 이벤트만 리플레이
 * 비용: snapshot 있으면 O(N % interval), 없으면 O(N).
 * 순서는 항상 server_seq ASC → 결정론적.
 */
@Service
class TimelineService(
    private val sessions: SessionRepository,
    private val events: EventRepository,
    private val snapshots: SnapshotRepository,
    private val codec: JsonbCodec,
) {

    fun restore(sessionId: UUID, at: Instant?): Timeline {
        sessions.findById(sessionId) ?: throw NotFoundException("session $sessionId not found")
        val asOf = at ?: Instant.now()
        return restoreAtSeq(sessionId, events.maxSeqAt(sessionId, asOf), asOf)
    }

    /** SnapshotService도 같은 진입점 사용 — "조회"와 "snapshot 생성"이 같은 코드 경로. */
    fun restoreAtSeq(sessionId: UUID, cutoffSeq: Long, asOfTime: Instant = Instant.now()): Timeline =
        buildState(sessionId, cutoffSeq).toTimeline(sessionId, cutoffSeq, asOfTime)

    fun snapshotStateAt(sessionId: UUID, cutoffSeq: Long): Pair<SnapshotState, JSONB> {
        val state = buildState(sessionId, cutoffSeq).toSnapshotState()
        return state to codec.toJsonbTyped(state)
    }

    private fun buildState(sessionId: UUID, cutoffSeq: Long): ReplayState {
        val base = snapshots.findLatestAtOrBefore(sessionId, cutoffSeq)
        val state = ReplayState()
        var startSeq = 0L
        if (base != null) {
            state.loadFrom(codec.fromJsonbTyped(base.state, SnapshotState::class.java))
            startSeq = base.upToSeq
        }
        events.findBySession(
            sessionId = sessionId,
            fromSeq = if (startSeq > 0) startSeq else null,
            upToSeq = cutoffSeq,
            limit = 10_000,
        ).forEach(state::apply)
        return state
    }
}

/**
 * 리플레이 도중의 가변 상태. 단일 스레드 사용. 변환 시 immutable 복사본만 외부로 반환.
 *
 * 머지 규칙:
 *   JOIN: 기존 joinedAt 보존 / LEAVE: leftAt 세팅 / DISCONNECT·RECONNECT: 무시
 *   MESSAGE: messageId 기준 first-write-wins (방어적)
 *   MESSAGE_EDIT: DELETED면 무시(삭제 우선), 아니면 text+status 갱신
 *   MESSAGE_DELETE: text=null, status=DELETED / SESSION_END: status=ENDED
 */
private class ReplayState(
    val participants: LinkedHashMap<String, ParticipantSnapshot> = linkedMapOf(),
    val messages: LinkedHashMap<String, MessageSnapshot> = linkedMapOf(),
    var status: SessionStatus = SessionStatus.ACTIVE,
) {

    fun loadFrom(snapshot: SnapshotState) {
        participants.clear()
        messages.clear()
        snapshot.participants.forEach { participants[it.userId] = it }
        snapshot.messages.forEach { messages[it.messageId] = it }
        status = snapshot.status
    }

    fun apply(e: ChatEvent) {
        when (e.type) {
            ChatEventType.JOIN -> {
                val uid = e.actorUserId ?: return
                participants[uid] = ParticipantSnapshot(
                    userId = uid,
                    joinedAt = participants[uid]?.joinedAt ?: e.serverTs,
                    leftAt = null,
                )
            }
            ChatEventType.LEAVE -> {
                val uid = e.actorUserId ?: return
                participants[uid] = participants[uid]?.copy(leftAt = e.serverTs) ?: return
            }
            ChatEventType.DISCONNECT, ChatEventType.RECONNECT -> Unit
            ChatEventType.MESSAGE -> {
                val mid = e.payload["messageId"] as? String ?: return
                messages.putIfAbsent(mid, MessageSnapshot(
                    messageId = mid,
                    sender = e.actorUserId ?: "unknown",
                    text = e.payload["text"] as? String,
                    status = MessageStatus.SENT,
                    createdAt = e.serverTs,
                    lastModifiedAt = e.serverTs,
                ))
            }
            ChatEventType.MESSAGE_EDIT -> {
                val mid = e.payload["messageId"] as? String ?: return
                val existing = messages[mid] ?: return
                if (existing.status == MessageStatus.DELETED) return
                messages[mid] = existing.copy(
                    text = e.payload["text"] as? String,
                    status = MessageStatus.EDITED,
                    lastModifiedAt = e.serverTs,
                )
            }
            ChatEventType.MESSAGE_DELETE -> {
                val mid = e.payload["messageId"] as? String ?: return
                messages[mid] = messages[mid]?.copy(
                    text = null,
                    status = MessageStatus.DELETED,
                    lastModifiedAt = e.serverTs,
                ) ?: return
            }
            ChatEventType.SESSION_END -> status = SessionStatus.ENDED
        }
    }

    fun toTimeline(sessionId: UUID, asOfSeq: Long, asOfTime: Instant) = Timeline(
        sessionId, status, asOfSeq, asOfTime,
        participants.values.toList(), messages.values.toList(),
    )

    fun toSnapshotState() = SnapshotState(status, participants.values.toList(), messages.values.toList())
}
