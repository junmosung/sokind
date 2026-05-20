package me.victor.demo.infra.persistence

import me.victor.demo.domain.participant.Participant
import me.victor.demo.jooq.tables.references.PARTICIPANTS
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.util.UUID

/** participants 테이블 IO. join은 단일 SQL의 ON CONFLICT로 멱등화 — 동시 호출 안전. */
@Repository
class ParticipantRepository(private val dsl: DSLContext) {

    /**
     * 멱등 join. 이미 참여 중이면 no-op, 떠났던 유저면 left_at만 NULL로 되돌림
     * (joined_at은 최초 진입 시각 그대로 보존).
     */
    fun join(sessionId: UUID, userId: String): Int = dsl.insertInto(PARTICIPANTS)
        .set(PARTICIPANTS.SESSION_ID, sessionId)
        .set(PARTICIPANTS.USER_ID, userId)
        .onConflict(PARTICIPANTS.SESSION_ID, PARTICIPANTS.USER_ID)
        .doUpdate()
        .setNull(PARTICIPANTS.LEFT_AT)
        .where(PARTICIPANTS.LEFT_AT.isNotNull)
        .execute()

    fun leave(sessionId: UUID, userId: String): Int = dsl.update(PARTICIPANTS)
        .set(PARTICIPANTS.LEFT_AT, DSL.currentOffsetDateTime())
        .where(PARTICIPANTS.SESSION_ID.eq(sessionId))
        .and(PARTICIPANTS.USER_ID.eq(userId))
        .and(PARTICIPANTS.LEFT_AT.isNull)
        .execute()

    fun findBySession(sessionId: UUID): List<Participant> = dsl.selectFrom(PARTICIPANTS)
        .where(PARTICIPANTS.SESSION_ID.eq(sessionId))
        .orderBy(PARTICIPANTS.JOINED_AT)
        .fetch().map { it.toDomain() }

    fun activeCount(sessionId: UUID): Int = dsl.selectCount().from(PARTICIPANTS)
        .where(PARTICIPANTS.SESSION_ID.eq(sessionId))
        .and(PARTICIPANTS.LEFT_AT.isNull)
        .fetchOne(0, Int::class.java) ?: 0

    fun updateLastSeen(sessionId: UUID, userId: String, seq: Long): Int = dsl.update(PARTICIPANTS)
        .set(PARTICIPANTS.LAST_SEEN_SEQ, DSL.greatest(PARTICIPANTS.LAST_SEEN_SEQ, DSL.value(seq)))
        .where(PARTICIPANTS.SESSION_ID.eq(sessionId))
        .and(PARTICIPANTS.USER_ID.eq(userId))
        .execute()
}

private fun me.victor.demo.jooq.tables.records.ParticipantsRecord.toDomain() = Participant(
    sessionId = sessionId!!,
    userId = userId!!,
    joinedAt = joinedAt!!.toInstant(),
    leftAt = leftAt?.toInstant(),
    lastSeenSeq = lastSeenSeq!!,
)
