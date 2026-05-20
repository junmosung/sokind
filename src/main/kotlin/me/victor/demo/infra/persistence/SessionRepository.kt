package me.victor.demo.infra.persistence

import me.victor.demo.domain.session.Session
import me.victor.demo.domain.session.SessionStatus
import me.victor.demo.jooq.tables.references.SESSIONS
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

/**
 * sessions 테이블 IO (jOOQ).
 *
 * jOOQ 채택 이유: 컬럼/타입 오타가 컴파일 타임에 잡히고, GREATEST 같은 SQL 함수도
 * DSL로 직접 표현 가능. 생성된 SQL이 그대로 노출돼 리뷰가 쉬움.
 */
@Repository
class SessionRepository(private val dsl: DSLContext) {

    fun create(): Session = dsl.insertInto(SESSIONS)
        .set(SESSIONS.STATUS, SessionStatus.ACTIVE.name)
        .returning()
        .fetchSingle()
        .toDomain()

    fun findById(id: UUID): Session? = dsl.selectFrom(SESSIONS)
        .where(SESSIONS.ID.eq(id))
        .fetchOne()?.toDomain()

    fun list(status: SessionStatus?, limit: Int, offset: Int): List<Session> = dsl.selectFrom(SESSIONS)
        .where(status?.let { SESSIONS.STATUS.eq(it.name) } ?: DSL.noCondition())
        .orderBy(SESSIONS.CREATED_AT.desc())
        .limit(limit).offset(offset)
        .fetch().map { it.toDomain() }

    fun updateStatus(id: UUID, status: SessionStatus, endedAt: OffsetDateTime? = null): Int =
        dsl.update(SESSIONS)
            .set(SESSIONS.STATUS, status.name)
            .set(SESSIONS.ENDED_AT, endedAt)
            .where(SESSIONS.ID.eq(id))
            .execute()

    /** GREATEST로 단조 증가 보장 — 동시 INSERT에서 작은 seq가 큰 값을 덮어쓰지 않도록. */
    fun bumpLastEventSeq(id: UUID, seq: Long): Int = dsl.update(SESSIONS)
        .set(SESSIONS.LAST_EVENT_SEQ, DSL.greatest(SESSIONS.LAST_EVENT_SEQ, DSL.value(seq)))
        .where(SESSIONS.ID.eq(id))
        .execute()
}

private fun me.victor.demo.jooq.tables.records.SessionsRecord.toDomain() = Session(
    id = id!!,
    status = SessionStatus.valueOf(status!!),
    createdAt = createdAt!!.toInstant(),
    endedAt = endedAt?.toInstant(),
    lastEventSeq = lastEventSeq!!,
)
