package me.victor.demo.api.ws

import me.victor.demo.domain.event.AppendCommand
import me.victor.demo.domain.event.ChatEventType
import me.victor.demo.domain.event.EventService
import org.springframework.messaging.handler.annotation.DestinationVariable
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.messaging.handler.annotation.Payload
import org.springframework.stereotype.Controller
import java.time.Instant
import java.util.UUID

/**
 * STOMP publish 수신. EventService에 위임만 하면 broadcast는 ChatBroadcaster가
 * EventAppended를 받아 처리 → REST와 동일 경로로 구독자에게 전달됨.
 */
@Controller
class ChatStompController(private val eventService: EventService) {

    @MessageMapping("/sessions/{sessionId}/events")
    fun onEvent(
        @DestinationVariable sessionId: UUID,
        @Payload msg: StompAppendMessage,
    ) {
        eventService.append(AppendCommand(
            sessionId = sessionId,
            type = msg.type,
            actorUserId = msg.actorUserId,
            clientEventId = msg.clientEventId,
            clientTs = msg.clientTs ?: Instant.now(),
            payload = msg.payload,
        ))
    }
}

/** STOMP 본문. REST AppendEventRequest와 형태 동일하게 유지. */
data class StompAppendMessage(
    val clientEventId: String,
    val type: ChatEventType,
    val actorUserId: String?,
    val clientTs: Instant? = null,
    val payload: Map<String, Any?> = emptyMap(),
)
