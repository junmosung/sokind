package me.victor.demo.api.rest

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import me.victor.demo.domain.event.AppendCommand
import me.victor.demo.domain.event.ChatEvent
import me.victor.demo.domain.event.ChatEventType
import me.victor.demo.domain.event.EventService
import me.victor.demo.infra.persistence.AppendResult
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * 이벤트 수집 요청. 동일 (sessionId, clientEventId) 재전송은 DB UNIQUE로 collapse.
 * actorUserId는 MESSAGE/EDIT/DELETE 및 JOIN/LEAVE/DISCONNECT/RECONNECT에 필수.
 */
data class AppendEventRequest(
    /** 클라 발급 멱등성 키. 같은 세션 내 unique. */
    @field:NotBlank val clientEventId: String,
    val type: ChatEventType,
    val actorUserId: String?,
    /** 누락 시 서버 수신 시각. 순서 결정엔 미사용. */
    val clientTs: Instant? = null,
    /** 예: MESSAGE → {messageId, text}, MESSAGE_DELETE → {messageId}. */
    val payload: Map<String, Any?> = emptyMap(),
)

/** 이벤트 수집 응답. wasInserted=false면 멱등 hit (projection/broadcast 모두 발생 안 함). */
data class AppendEventResponse(
    val serverSeq: Long,
    val sessionId: UUID,
    val type: ChatEventType,
    val actorUserId: String?,
    val clientEventId: String,
    val clientTs: Instant,
    val serverTs: Instant,
    val payload: Map<String, Any?>,
    val wasInserted: Boolean,
) {
    companion object {
        fun of(r: AppendResult) = AppendEventResponse(
            r.event.serverSeq, r.event.sessionId, r.event.type, r.event.actorUserId,
            r.event.clientEventId, r.event.clientTs, r.event.serverTs, r.event.payload,
            r.wasInserted,
        )
    }
}

/** 이벤트 히스토리 한 항목. ChatEvent에서 sessionId만 제외. */
data class EventView(
    val serverSeq: Long,
    val type: ChatEventType,
    val actorUserId: String?,
    val clientEventId: String,
    val clientTs: Instant,
    val serverTs: Instant,
    val payload: Map<String, Any?>,
) {
    companion object {
        fun of(e: ChatEvent) = EventView(
            e.serverSeq, e.type, e.actorUserId, e.clientEventId, e.clientTs, e.serverTs, e.payload,
        )
    }
}

@RestController
@RequestMapping("/sessions/{sessionId}/events")
class EventController(private val service: EventService) {

    /** 멱등 append. 항상 200 + wasInserted로 신규/hit 구분. */
    @PostMapping
    fun append(
        @PathVariable sessionId: UUID,
        @Valid @RequestBody body: AppendEventRequest,
    ): AppendEventResponse = AppendEventResponse.of(
        service.append(AppendCommand(
            sessionId = sessionId,
            type = body.type,
            actorUserId = body.actorUserId,
            clientEventId = body.clientEventId,
            clientTs = body.clientTs ?: Instant.now(),
            payload = body.payload,
        ))
    )

    /** 디버깅/검증용 히스토리. from은 exclusive(>), to는 inclusive(<=). */
    @GetMapping
    fun history(
        @PathVariable sessionId: UUID,
        @RequestParam(required = false) from: Long?,
        @RequestParam(required = false) to: Long?,
        @RequestParam(defaultValue = "500") limit: Int,
    ): List<EventView> = service.history(sessionId, from, to, limit).map(EventView::of)
}
