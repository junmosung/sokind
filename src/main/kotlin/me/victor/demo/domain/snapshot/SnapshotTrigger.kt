package me.victor.demo.domain.snapshot

import me.victor.demo.domain.event.EventAppended
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * EventAppended를 받아 snapshot 정책 평가/실행.
 *
 *  - AFTER_COMMIT + @Async: append 응답 latency에 영향 없음, 메인 트랜잭션과 격리.
 *  - 실패는 로그만 — snapshot이 없어도 풀 리플레이로 정상 복원되므로 best-effort.
 */
@Component
class SnapshotTrigger(private val snapshotService: SnapshotService) {
    private val log = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async
    fun onAppended(evt: EventAppended) {
        try {
            snapshotService.maybeCreateSnapshot(evt.event.sessionId, evt.event.serverSeq)
        } catch (e: Exception) {
            log.warn("snapshot trigger failed: sessionId={} seq={}: {}",
                evt.event.sessionId, evt.event.serverSeq, e.message, e)
        }
    }
}
