package me.victor.demo.infra.persistence

import me.victor.demo.domain.event.ChatEvent
import me.victor.demo.domain.event.ChatEventType
import me.victor.demo.infra.json.JsonbCodec
import me.victor.demo.jooq.tables.records.EventsRecord
import me.victor.demo.jooq.tables.references.EVENTS
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** wasInserted=false면 동일 clientEventId로 이미 저장된 멱등 재전송 (event는 기존 행). */
data class AppendResult(val event: ChatEvent, val wasInserted: Boolean)

/**
 * events 테이블 IO (jOOQ).
 *
 * append는 ON CONFLICT DO NOTHING (DO UPDATE 아님) — append-only 의도를 SQL로 명시.
 * 멱등 hit이면 RETURNING이 비고, 같은 키로 SELECT해서 기존 행 반환.
 */
@Repository
class EventRepository(
    private val dsl: DSLContext,
    private val codec: JsonbCodec,
) {

    fun append(
        sessionId: UUID,
        type: ChatEventType,
        actorUserId: String?,
        clientEventId: String,
        clientTs: Instant,
        payload: Map<String, Any?>,
    ): AppendResult {
        val inserted = dsl.insertInto(EVENTS)
            .set(EVENTS.SESSION_ID, sessionId)
            .set(EVENTS.TYPE, type.name)
            .set(EVENTS.ACTOR_USER_ID, actorUserId)
            .set(EVENTS.CLIENT_EVENT_ID, clientEventId)
            .set(EVENTS.CLIENT_TS, OffsetDateTime.ofInstant(clientTs, ZoneOffset.UTC))
            .set(EVENTS.PAYLOAD, codec.toJsonb(payload))
            .onConflict(EVENTS.SESSION_ID, EVENTS.CLIENT_EVENT_ID)
            .doNothing()
            .returning()
            .fetchOne()

        if (inserted != null) return AppendResult(inserted.toDomain(), wasInserted = true)

        val existing = dsl.selectFrom(EVENTS)
            .where(EVENTS.SESSION_ID.eq(sessionId))
            .and(EVENTS.CLIENT_EVENT_ID.eq(clientEventId))
            .fetchSingle()
        return AppendResult(existing.toDomain(), wasInserted = false)
    }

    /**
     * server_seq ASC 정렬. fromSeq는 exclusive(>), upToSeq/upToTs는 inclusive(<=).
     * fromSeq는 catch-up, upToSeq/upToTs는 시점 복원 컷오프에 사용.
     */
    fun findBySession(
        sessionId: UUID,
        upToSeq: Long? = null,
        upToTs: Instant? = null,
        fromSeq: Long? = null,
        limit: Int = 1000,
    ): List<ChatEvent> {
        var condition = EVENTS.SESSION_ID.eq(sessionId)
        if (fromSeq != null) condition = condition.and(EVENTS.SERVER_SEQ.gt(fromSeq))
        if (upToSeq != null) condition = condition.and(EVENTS.SERVER_SEQ.le(upToSeq))
        if (upToTs != null) {
            condition = condition.and(EVENTS.SERVER_TS.le(OffsetDateTime.ofInstant(upToTs, ZoneOffset.UTC)))
        }
        return dsl.selectFrom(EVENTS)
            .where(condition)
            .orderBy(EVENTS.SERVER_SEQ.asc())
            .limit(limit.coerceIn(1, 5_000))
            .fetch()
            .map { it.toDomain() }
    }

    /** server_ts<=at 중 최대 server_seq. 시점 복원 컷오프. */
    fun maxSeqAt(sessionId: UUID, at: Instant): Long =
        dsl.select(DSL.coalesce(DSL.max(EVENTS.SERVER_SEQ), DSL.value(0L)))
            .from(EVENTS)
            .where(EVENTS.SESSION_ID.eq(sessionId))
            .and(EVENTS.SERVER_TS.le(OffsetDateTime.ofInstant(at, ZoneOffset.UTC)))
            .fetchOne(0, Long::class.java) ?: 0L

    private fun EventsRecord.toDomain() = ChatEvent(
        serverSeq = serverSeq!!,
        sessionId = sessionId!!,
        type = ChatEventType.valueOf(type!!),
        actorUserId = actorUserId,
        clientEventId = clientEventId!!,
        clientTs = clientTs!!.toInstant(),
        serverTs = serverTs!!.toInstant(),
        payload = codec.fromJsonb(payload),
    )
}
