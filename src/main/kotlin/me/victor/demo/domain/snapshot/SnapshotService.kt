package me.victor.demo.domain.snapshot

import me.victor.demo.domain.timeline.TimelineService
import me.victor.demo.infra.persistence.SnapshotRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Snapshot 생성 정책 (count-based).
 *  - 마지막 snapshot의 upToSeq에서 [intervalEvents] 이상 진행됐을 때만 신규 생성.
 *  - 저장은 ON CONFLICT DO NOTHING으로 멱등 — 동시 워커 안전.
 */
@Service
class SnapshotService(
    private val snapshots: SnapshotRepository,
    private val timeline: TimelineService,
    @Value("\${chat.snapshot.interval-events:50}") private val intervalEvents: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun maybeCreateSnapshot(sessionId: UUID, currentSeq: Long) {
        val lastSeq = snapshots.findLatestAtOrBefore(sessionId, currentSeq)?.upToSeq ?: 0L
        if (currentSeq - lastSeq < intervalEvents) return
        val (_, jsonb) = timeline.snapshotStateAt(sessionId, currentSeq)
        if (snapshots.save(sessionId, currentSeq, jsonb)) {
            log.info("snapshot created: sessionId={} upToSeq={}", sessionId, currentSeq)
        }
    }
}
