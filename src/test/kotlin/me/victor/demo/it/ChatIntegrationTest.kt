package me.victor.demo.it

import me.victor.demo.api.rest.AppendEventResponse
import me.victor.demo.api.rest.EventView
import me.victor.demo.api.rest.SessionView
import me.victor.demo.domain.event.ChatEventType
import me.victor.demo.domain.session.SessionStatus
import me.victor.demo.domain.timeline.MessageStatus
import me.victor.demo.domain.timeline.Timeline
import org.junit.jupiter.api.Test
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * REST 시나리오 풀 커버리지.
 *
 * 검증 영역:
 *  - 세션 라이프사이클 (생성/join/leave/end)
 *  - 1:1 정원 제한 (3번째 join → 409)
 *  - ENDED 세션의 이벤트 거절
 *  - 이벤트 멱등성 (same clientEventId → wasInserted=false + 같은 serverSeq)
 *  - 순서 결정성 (server_seq 오름차순)
 *  - 시점 복원 (현재 + 과거 t + MESSAGE_EDIT/DELETE 머지)
 */
class ChatIntegrationTest : IntegrationTestSupport() {

    @Test
    fun `세션 라이프사이클 - 생성, 1대1 join, leave, end`() {
        val sessionId = createSession()
        join(sessionId, "alice")
        join(sessionId, "bob")

        // 3번째 다른 user → 409
        val third = postJson("/sessions/$sessionId/join", mapOf("userId" to "carol"))
        assertEquals(HttpStatus.CONFLICT, third.statusCode)

        // 같은 user 재 join → 204 (멱등)
        val rejoin = postJson("/sessions/$sessionId/join", mapOf("userId" to "alice"))
        assertEquals(HttpStatus.NO_CONTENT, rejoin.statusCode)

        // leave + end
        assertEquals(HttpStatus.NO_CONTENT, postJson("/sessions/$sessionId/leave", mapOf("userId" to "alice")).statusCode)
        assertEquals(HttpStatus.NO_CONTENT, postJson("/sessions/$sessionId/end", null).statusCode)

        // ENDED 세션에 join → 409
        val afterEnd = postJson("/sessions/$sessionId/join", mapOf("userId" to "dave"))
        assertEquals(HttpStatus.CONFLICT, afterEnd.statusCode)

        // 세션 상태 ENDED
        val sess = getJson("/sessions/$sessionId", SessionView::class.java).body!!
        assertEquals(SessionStatus.ENDED, sess.status)
        assertNotNull(sess.endedAt)
    }

    @Test
    fun `이벤트 멱등성 - 같은 clientEventId 재전송은 동일 serverSeq 반환`() {
        val sessionId = createSession()
        join(sessionId, "alice")

        val req = mapOf(
            "clientEventId" to "msg-1",
            "type" to "MESSAGE",
            "actorUserId" to "alice",
            "payload" to mapOf("messageId" to "m1", "text" to "hi"),
        )
        val first = postJson("/sessions/$sessionId/events", req, AppendEventResponse::class.java).body!!
        val second = postJson("/sessions/$sessionId/events", req, AppendEventResponse::class.java).body!!

        assertTrue(first.wasInserted, "첫 호출은 신규 INSERT")
        assertEquals(false, second.wasInserted, "두 번째는 멱등 hit")
        assertEquals(first.serverSeq, second.serverSeq, "같은 serverSeq를 반환해야 위치 정보 일관")
        assertEquals(first.serverTs, second.serverTs, "최초 INSERT의 serverTs가 유지되어야 함")
    }

    @Test
    fun `순서 결정성 - server_seq는 단조 증가, 히스토리도 server_seq ASC`() {
        val sessionId = createSession()
        join(sessionId, "alice")
        join(sessionId, "bob")

        val seqs = (1..5).map { i ->
            postJson(
                "/sessions/$sessionId/events",
                mapOf(
                    "clientEventId" to "cli-$i",
                    "type" to "MESSAGE",
                    "actorUserId" to "alice",
                    "payload" to mapOf("messageId" to "m$i", "text" to "msg $i"),
                ),
                AppendEventResponse::class.java,
            ).body!!.serverSeq
        }
        assertEquals(seqs.sorted(), seqs, "server_seq는 INSERT 순서대로 단조 증가")

        val history = getList<EventView>("/sessions/$sessionId/events").body!!
        assertEquals(history.map { it.serverSeq }.sorted(), history.map { it.serverSeq }, "히스토리는 server_seq ASC")
    }

    @Test
    fun `시점 복원 - 현재 + 과거 t + MESSAGE_EDIT, MESSAGE_DELETE 머지`() {
        val sessionId = createSession()
        join(sessionId, "alice")
        join(sessionId, "bob")

        sendMessage(sessionId, "alice", "cli-1", "m1", "원본 메시지")
        sendMessage(sessionId, "bob", "cli-2", "m2", "두 번째")

        val tMid = Instant.now().plusMillis(50)  // 두 메시지 직후 ~ EDIT/DELETE 이전
        Thread.sleep(120)  // server_ts <= tMid 컷오프가 EDIT/DELETE보다 앞서도록

        // EDIT m1, DELETE m2
        postJson(
            "/sessions/$sessionId/events",
            mapOf(
                "clientEventId" to "cli-3",
                "type" to "MESSAGE_EDIT",
                "actorUserId" to "alice",
                "payload" to mapOf("messageId" to "m1", "text" to "수정됨"),
            ),
            Any::class.java,
        )
        postJson(
            "/sessions/$sessionId/events",
            mapOf(
                "clientEventId" to "cli-4",
                "type" to "MESSAGE_DELETE",
                "actorUserId" to "bob",
                "payload" to mapOf("messageId" to "m2"),
            ),
            Any::class.java,
        )

        // 현재 시점: EDIT/DELETE 반영
        val now = getJson("/sessions/$sessionId/timeline", Timeline::class.java).body!!
        val m1 = now.messages.first { it.messageId == "m1" }
        val m2 = now.messages.first { it.messageId == "m2" }
        assertEquals(MessageStatus.EDITED, m1.status)
        assertEquals("수정됨", m1.text)
        assertEquals(MessageStatus.DELETED, m2.status)
        assertNull(m2.text, "DELETED 메시지의 text는 null로 마스킹")
        assertEquals(2, now.participants.size)

        // 과거 t: EDIT/DELETE 이전 상태
        val past = getJson("/sessions/$sessionId/timeline?at=$tMid", Timeline::class.java).body!!
        val pastM1 = past.messages.first { it.messageId == "m1" }
        val pastM2 = past.messages.first { it.messageId == "m2" }
        assertEquals(MessageStatus.SENT, pastM1.status)
        assertEquals("원본 메시지", pastM1.text)
        assertEquals(MessageStatus.SENT, pastM2.status)
        assertEquals("두 번째", pastM2.text)
    }

    @Test
    fun `세션 목록 필터 - status로 필터 가능`() {
        val active = createSession()
        val ended = createSession()
        postJson("/sessions/$ended/end", null)

        val activeList = getList<SessionView>("/sessions?status=ACTIVE").body!!
        val endedList = getList<SessionView>("/sessions?status=ENDED").body!!

        assertTrue(activeList.any { it.id == active })
        assertTrue(endedList.any { it.id == ended })
        assertTrue(activeList.none { it.status == SessionStatus.ENDED })
    }

    // ----------------------------------------------------------------------
    // helpers
    // ----------------------------------------------------------------------

    private fun createSession(): UUID =
        postJson("/sessions", null, SessionView::class.java).body!!.id

    private fun join(sessionId: UUID, userId: String) {
        val r = postJson("/sessions/$sessionId/join", mapOf("userId" to userId))
        assertEquals(HttpStatus.NO_CONTENT, r.statusCode)
    }

    private fun sendMessage(sessionId: UUID, sender: String, clientEventId: String, messageId: String, text: String) {
        val r = postJson(
            "/sessions/$sessionId/events",
            mapOf(
                "clientEventId" to clientEventId,
                "type" to ChatEventType.MESSAGE.name,
                "actorUserId" to sender,
                "payload" to mapOf("messageId" to messageId, "text" to text),
            ),
            AppendEventResponse::class.java,
        )
        assertEquals(HttpStatus.OK, r.statusCode)
    }

    private fun postJson(path: String, body: Any?) =
        rest.exchange(baseUrl() + path, HttpMethod.POST, jsonEntity(body), String::class.java)

    private fun <T : Any> postJson(path: String, body: Any?, type: Class<T>) =
        rest.exchange(baseUrl() + path, HttpMethod.POST, jsonEntity(body), type)

    private fun <T : Any> getJson(path: String, type: Class<T>) =
        rest.getForEntity(baseUrl() + path, type)

    private inline fun <reified T> getList(path: String) =
        rest.exchange(
            baseUrl() + path, HttpMethod.GET, null,
            object : ParameterizedTypeReference<List<T>>() {},
        )

    private fun jsonEntity(body: Any?): HttpEntity<*> {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        return HttpEntity(body, headers)
    }
}
