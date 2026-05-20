package me.victor.demo.domain.session

import me.victor.demo.common.ConflictException
import me.victor.demo.common.NotFoundException
import me.victor.demo.domain.event.AppendCommand
import me.victor.demo.domain.event.ChatEventType
import me.victor.demo.domain.event.EventService
import me.victor.demo.domain.participant.Participant
import me.victor.demo.infra.persistence.ParticipantRepository
import me.victor.demo.infra.persistence.SessionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 세션 라이프사이클. 모든 상태 변경은 EventService.append를 거쳐 이벤트로 남으므로
 * events만으로 sessions/participants 완전 재구축이 가능하다.
 *
 * REST 경로의 clientEventId는 서버가 UUID로 자동 생성 — HTTP 재시도 dedup이 필요하면
 * 클라가 명시 키를 보내도록 확장 가능 (과제 범위 밖).
 */
@Service
class SessionService(
    private val sessions: SessionRepository,
    private val participants: ParticipantRepository,
    private val eventService: EventService,
) {

    /** create는 이벤트 대상이 아님 — sessions row가 있어야 events FK 성립. */
    fun create(): Session = sessions.create()

    fun get(id: UUID): Session =
        sessions.findById(id) ?: throw NotFoundException("session $id not found")

    fun list(status: SessionStatus?, limit: Int, offset: Int): List<Session> =
        sessions.list(status, limit.coerceIn(1, 200), offset.coerceAtLeast(0))

    fun participants(sessionId: UUID): List<Participant> {
        get(sessionId)
        return participants.findBySession(sessionId)
    }

    @Transactional
    fun join(sessionId: UUID, userId: String) {
        val session = get(sessionId)
        if (session.status == SessionStatus.ENDED) throw ConflictException("session is ENDED")

        // 1:1 정원 — 다른 유저 1명이 이미 있고 신규 유저면 거절.
        val existing = participants.findBySession(sessionId)
        if (existing.none { it.userId == userId } && existing.size >= 2) {
            throw ConflictException("session is full (1:1)")
        }
        emit(sessionId, ChatEventType.JOIN, userId, "join")
    }

    @Transactional
    fun leave(sessionId: UUID, userId: String) {
        get(sessionId)
        emit(sessionId, ChatEventType.LEAVE, userId, "leave")
    }

    @Transactional
    fun end(sessionId: UUID) {
        if (get(sessionId).status == SessionStatus.ENDED) return
        emit(sessionId, ChatEventType.SESSION_END, actor = null, prefix = "end")
    }

    private fun emit(sessionId: UUID, type: ChatEventType, actor: String?, prefix: String) {
        eventService.append(AppendCommand(
            sessionId = sessionId,
            type = type,
            actorUserId = actor,
            clientEventId = "$prefix-${UUID.randomUUID()}",
            clientTs = Instant.now(),
        ))
    }
}
