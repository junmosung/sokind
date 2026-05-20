package me.victor.demo.api.ws

import me.victor.demo.domain.event.ChatEvent
import me.victor.demo.domain.event.ChatEventType
import me.victor.demo.domain.event.EventAppended
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Instant
import java.util.UUID

/**
 * EventAppended → /topic/sessions/{id} broadcast.
 *
 *  - AFTER_COMMIT만: rollback된 이벤트는 외부로 새지 않음.
 *  - 멱등 hit은 EventService가 publish 자체를 안 하므로 여기 도달하지 않음.
 *  - ChatEvent 원본 대신 BroadcastPayload로 변환 → 내부 변경이 외부 계약으로 새지 않음.
 */
@Component
class ChatBroadcaster(private val messaging: SimpMessagingTemplate) {

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onAppended(evt: EventAppended) {
        messaging.convertAndSend(
            "/topic/sessions/${evt.event.sessionId}",
            BroadcastPayload.of(evt.event),
        )
    }
}

/** /topic/sessions/{id} 브로드캐스트 외부 계약. */
data class BroadcastPayload(
    val serverSeq: Long,
    val sessionId: UUID,
    val type: ChatEventType,
    val actorUserId: String?,
    val clientEventId: String,
    val clientTs: Instant,
    val serverTs: Instant,
    val payload: Map<String, Any?>,
) {
    companion object {
        fun of(e: ChatEvent) = BroadcastPayload(
            e.serverSeq, e.sessionId, e.type, e.actorUserId,
            e.clientEventId, e.clientTs, e.serverTs, e.payload,
        )
    }
}
