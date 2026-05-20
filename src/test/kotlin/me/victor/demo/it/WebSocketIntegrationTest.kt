package me.victor.demo.it

import me.victor.demo.api.rest.SessionView
import me.victor.demo.api.ws.BroadcastPayload
import org.awaitility.kotlin.atMost
import org.awaitility.kotlin.await
import org.awaitility.kotlin.untilAsserted
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.messaging.converter.MappingJackson2MessageConverter
import org.springframework.messaging.simp.stomp.StompFrameHandler
import org.springframework.messaging.simp.stomp.StompHeaders
import org.springframework.messaging.simp.stomp.StompSession
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.messaging.WebSocketStompClient
import java.lang.reflect.Type
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * STOMP over WebSocket broadcast 검증.
 *
 *  - REST publish 시 /topic/sessions/{id} 구독자가 수신.
 *  - 멱등 hit은 broadcast로 새지 않음 (EventService가 publish 자체를 안 함).
 *  - serverSeq가 단조 증가.
 *
 * 주의: WebSocketStompClient는 어디도 참조하지 않으면 GC되어 연결이 끊긴다.
 * 따라서 [openSessions] 필드로 강한 참조를 유지하고 @AfterEach에서 해제.
 */
class WebSocketIntegrationTest : IntegrationTestSupport() {

    private val openSessions = mutableListOf<Pair<WebSocketStompClient, StompSession>>()

    @AfterEach
    fun closeWebSocketSessions() {
        openSessions.forEach { (_, session) -> runCatching { session.disconnect() } }
        openSessions.clear()
    }

    @Test
    fun `REST publish가 두 STOMP 구독자에게 모두 전달된다`() {
        val sessionId = createSession()
        join(sessionId, "alice")
        join(sessionId, "bob")

        val aliceReceived = subscribe(sessionId)
        val bobReceived = subscribe(sessionId)

        publish(sessionId, clientEventId = "rest-1", messageId = "m1", text = "via REST")

        await atMost Duration.ofSeconds(3) untilAsserted {
            assertEquals(1, aliceReceived.size, "alice가 1건 받아야 함")
            assertEquals(1, bobReceived.size, "bob이 1건 받아야 함")
        }
        assertEquals("rest-1", aliceReceived.first().clientEventId)
        assertEquals("rest-1", bobReceived.first().clientEventId)
    }

    @Test
    fun `멱등 재전송은 broadcast되지 않는다`() {
        val sessionId = createSession()
        join(sessionId, "alice")
        join(sessionId, "bob")

        val received = subscribe(sessionId)

        publish(sessionId, clientEventId = "dup-1", messageId = "m1", text = "원본")
        publish(sessionId, clientEventId = "dup-1", messageId = "m1", text = "원본")

        // 첫 publish는 broadcast되어야 → await으로 수신 확인.
        await atMost Duration.ofSeconds(3) untilAsserted {
            assertEquals(1, received.size, "최초 publish는 도달해야 함")
        }
        // 약간 더 기다려서 중복 publish가 broadcast되지 않음을 확정.
        Thread.sleep(500)
        assertEquals(1, received.size, "멱등 hit은 broadcast로 새지 않아야 함")
    }

    @Test
    fun `broadcast된 이벤트의 serverSeq는 단조 증가`() {
        val sessionId = createSession()
        join(sessionId, "alice")
        join(sessionId, "bob")

        val received = subscribe(sessionId)

        repeat(5) { i ->
            publish(sessionId, clientEventId = "cli-$i", messageId = "m$i", text = "msg $i")
        }

        await atMost Duration.ofSeconds(5) untilAsserted {
            assertEquals(5, received.size)
        }
        val seqs = received.map { it.serverSeq }
        assertEquals(seqs.sorted(), seqs, "serverSeq는 단조 증가")
    }

    // ---------------------- STOMP helpers ----------------------

    private fun subscribe(sessionId: UUID): ConcurrentLinkedQueue<BroadcastPayload> {
        val received = ConcurrentLinkedQueue<BroadcastPayload>()
        val client = WebSocketStompClient(StandardWebSocketClient())
        // Jackson 2의 KotlinModule이 jackson-module-kotlin에서 자동 로드되지 않으면
        // data class 역직렬화에서 silent fail → KotlinModule을 명시 등록한 ObjectMapper 사용.
        val jsonConverter = MappingJackson2MessageConverter().apply {
            objectMapper = com.fasterxml.jackson.databind.ObjectMapper().apply {
                registerModule(com.fasterxml.jackson.module.kotlin.kotlinModule())
                registerModule(com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            }
        }
        client.messageConverter = jsonConverter

        val subscribed = CompletableFuture<Unit>()
        val handler = object : StompSessionHandlerAdapter() {
            override fun handleException(
                session: StompSession,
                command: org.springframework.messaging.simp.stomp.StompCommand?,
                headers: StompHeaders,
                payload: ByteArray,
                exception: Throwable,
            ) {
                exception.printStackTrace()
            }

            override fun afterConnected(session: StompSession, headers: StompHeaders) {
                session.subscribe("/topic/sessions/$sessionId", object : StompFrameHandler {
                    override fun getPayloadType(headers: StompHeaders): Type = BroadcastPayload::class.java
                    override fun handleFrame(headers: StompHeaders, payload: Any?) {
                        if (payload != null) received += (payload as BroadcastPayload)
                    }
                })
                subscribed.complete(Unit)
            }
        }

        val session = client.connectAsync("ws://localhost:$port/ws", handler).get(5, TimeUnit.SECONDS)
        subscribed.get(2, TimeUnit.SECONDS)
        // STOMP SUBSCRIBE는 RECEIPT 없이 즉시 반환 → 서버 broker가 등록을 마칠 시간 확보.
        // 없으면 직후 publish가 구독자 없는 상태에서 broker를 통과해 누락.
        Thread.sleep(300)
        openSessions += client to session
        return received
    }

    // ---------------------- REST helpers ----------------------

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

    private fun publish(sessionId: UUID, clientEventId: String, messageId: String, text: String) {
        val res = rest.exchange(
            baseUrl() + "/sessions/$sessionId/events",
            HttpMethod.POST,
            jsonEntity(mapOf(
                "clientEventId" to clientEventId,
                "type" to "MESSAGE",
                "actorUserId" to "alice",
                "payload" to mapOf("messageId" to messageId, "text" to text),
            )),
            String::class.java,
        )
        assertTrue(res.statusCode.is2xxSuccessful)
    }

    private fun jsonEntity(body: Any): HttpEntity<*> {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        return HttpEntity(body, headers)
    }
}
