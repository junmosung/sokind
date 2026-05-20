package me.victor.demo.infra.persistence

import me.victor.demo.jooq.tables.references.SNAPSHOTS
import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * snapshots 한 행. state는 raw JSONB — 호출자가 typed deserialize 책임.
 * Repository를 도메인 타입(SnapshotState)으로부터 분리해 순수 IO로 유지.
 */
data class StoredSnapshot(
    val sessionId: UUID,
    /** "seq<=upToSeq 인 이벤트 적용 결과 상태"라는 약속. */
    val upToSeq: Long,
    val state: JSONB,
    val createdAt: Instant,
)

/**
 * snapshots IO. 생성은 ON CONFLICT DO NOTHING으로 멱등 — 동시 워커 안전.
 * (결정론적 리플레이라 어느 행이 살아남든 내용은 동일.)
 */
@Repository
class SnapshotRepository(private val dsl: DSLContext) {

    /** @return true=신규 INSERT, false=동일 키 이미 존재(멱등 hit). */
    fun save(sessionId: UUID, upToSeq: Long, state: JSONB): Boolean = dsl.insertInto(SNAPSHOTS)
        .set(SNAPSHOTS.SESSION_ID, sessionId)
        .set(SNAPSHOTS.UP_TO_SEQ, upToSeq)
        .set(SNAPSHOTS.STATE, state)
        .onConflict(SNAPSHOTS.SESSION_ID, SNAPSHOTS.UP_TO_SEQ)
        .doNothing()
        .execute() > 0

    /** upToSeq 이하 중 최신 snapshot — idx_snapshots_session_seq_desc로 인덱스 only scan 1회. */
    fun findLatestAtOrBefore(sessionId: UUID, upToSeq: Long): StoredSnapshot? = dsl.selectFrom(SNAPSHOTS)
        .where(SNAPSHOTS.SESSION_ID.eq(sessionId))
        .and(SNAPSHOTS.UP_TO_SEQ.le(upToSeq))
        .orderBy(SNAPSHOTS.UP_TO_SEQ.desc())
        .limit(1)
        .fetchOne()?.let {
            StoredSnapshot(it.sessionId!!, it.upToSeq!!, it.state!!, it.createdAt!!.toInstant())
        }

    fun count(sessionId: UUID): Int = dsl.selectCount().from(SNAPSHOTS)
        .where(SNAPSHOTS.SESSION_ID.eq(sessionId))
        .fetchOne(0, Int::class.java) ?: 0
}
