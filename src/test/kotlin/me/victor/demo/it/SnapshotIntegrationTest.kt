package me.victor.demo.it

import me.victor.demo.api.rest.AppendEventResponse
import me.victor.demo.api.rest.SessionView
import me.victor.demo.domain.timeline.Timeline
import me.victor.demo.jooq.tables.references.SNAPSHOTS
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Snapshot 자동 생성 + 결정론 검증.
 *
 *  - interval=5로 properties override → 컨텍스트 분리(별도 캐시 키).
 *  - @Async + AFTER_COMMIT 트리거라 await으로 비동기 완료를 폴링.
 *  - "snapshot 경로 == 풀 리플레이 경로" 동일성을 hash로 검증.
 */
// SnapshotIntegrationTest는 별도 interval로 동작해야 하므로 별도 컨텍스트.
// IntegrationTestSupport의 어노테이션은 재상속해서 properties만 override.
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["chat.snapshot.interval-events=5"],
)
class SnapshotIntegrationTest : IntegrationTestSupport() {

    @Test
    fun `interval 도달 시 snapshot이 자동 생성된다`() {
        val sessionId = createSession()
        join(sessionId, "alice")  // seq 1
        join(sessionId, "bob")    // seq 2

        // 8개 MESSAGE → 총 10 이벤트. interval=5이므로 snapshot이 1~2개 생겨야 함.
        repeat(8) { i ->
            sendMessage(sessionId, "alice", "cli-$i", "m$i", "msg $i")
        }

        // @Async 작업 완료 대기 (최대 5초)
        await atMost Duration.ofSeconds(5) untilAsserted {
            val count = snapshotCount(sessionId)
            assertTrue(count >= 1, "snapshot이 최소 1개는 생성되어야 함 (실제=$count)")
        }
    }

    @Test
    fun `snapshot 경로와 풀 리플레이 경로의 결과가 동일하다`() {
        val sessionId = createSession()
        join(sessionId, "alice")
        join(sessionId, "bob")
        repeat(8) { i -> sendMessage(sessionId, "alice", "cli-$i", "m$i", "msg $i") }

        // snapshot 생성 대기
        await atMost Duration.ofSeconds(5) untilAsserted {
            assertTrue(snapshotCount(sessionId) >= 1)
        }

        // 1) snapshot이 있는 상태에서 timeline 복원
        val withSnapshot = restoreTimeline(sessionId)

        // 2) snapshot을 모두 지운 뒤 풀 리플레이로 복원
        dsl.deleteFrom(SNAPSHOTS).where(SNAPSHOTS.SESSION_ID.eq(sessionId)).execute()
        val withoutSnapshot = restoreTimeline(sessionId)

        // 두 결과의 도메인 부분이 정확히 같아야 함 (asOfTime은 호출 시각이라 제외).
        assertEquals(withoutSnapshot.asOfSeq, withSnapshot.asOfSeq, "asOfSeq 일치")
        assertEquals(withoutSnapshot.status, withSnapshot.status, "status 일치")
        assertEquals(withoutSnapshot.participants, withSnapshot.participants, "참여자 동일")
        assertEquals(withoutSnapshot.messages, withSnapshot.messages, "메시지 동일")
    }

    // ---------------------- helpers ----------------------

    private fun createSession(): UUID =
        rest.exchange(baseUrl() + "/sessions", HttpMethod.POST, null, SessionView::class.java).body!!.id

    private fun join(sessionId: UUID, userId: String) {
        rest.exchange(
            baseUrl() + "/sessions/$sessionId/join",
            HttpMethod.POST,
            jsonEntity(mapOf("userId" to userId)),
            Void::class.java,
        )
    }

    private fun sendMessage(sessionId: UUID, sender: String, clientEventId: String, messageId: String, text: String) {
        rest.exchange(
            baseUrl() + "/sessions/$sessionId/events",
            HttpMethod.POST,
            jsonEntity(mapOf(
                "clientEventId" to clientEventId,
                "type" to "MESSAGE",
                "actorUserId" to sender,
                "payload" to mapOf("messageId" to messageId, "text" to text),
            )),
            AppendEventResponse::class.java,
        )
    }

    private fun restoreTimeline(sessionId: UUID): Timeline =
        rest.getForEntity(baseUrl() + "/sessions/$sessionId/timeline", Timeline::class.java).body!!

    private fun snapshotCount(sessionId: UUID): Int =
        dsl.selectCount().from(SNAPSHOTS).where(SNAPSHOTS.SESSION_ID.eq(sessionId))
            .fetchOne(0, Int::class.java) ?: 0

    private fun jsonEntity(body: Any): HttpEntity<Any> {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        return HttpEntity(body, headers)
    }
}
