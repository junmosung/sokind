package me.victor.demo.api.rest

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import me.victor.demo.domain.participant.Participant
import me.victor.demo.domain.session.Session
import me.victor.demo.domain.session.SessionService
import me.victor.demo.domain.session.SessionStatus
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** REST 응답용 세션. 외부 계약과 내부 모델 분리. lastEventSeq는 클라 catch-up 워터마크. */
data class SessionView(
    val id: UUID,
    val status: SessionStatus,
    val createdAt: Instant,
    /** ACTIVE면 null. */
    val endedAt: Instant?,
    val lastEventSeq: Long,
) {
    companion object {
        fun of(s: Session) = SessionView(s.id, s.status, s.createdAt, s.endedAt, s.lastEventSeq)
    }
}

/** REST 응답용 참여자. online은 leftAt==null의 가독성 alias. */
data class ParticipantView(
    val userId: String,
    val joinedAt: Instant,
    /** 참여 중이면 null. */
    val leftAt: Instant?,
    val online: Boolean,
    val lastSeenSeq: Long,
) {
    companion object {
        fun of(p: Participant) = ParticipantView(p.userId, p.joinedAt, p.leftAt, p.isOnline, p.lastSeenSeq)
    }
}

data class JoinRequest(@field:NotBlank val userId: String)
data class LeaveRequest(@field:NotBlank val userId: String)

@RestController
@RequestMapping("/sessions")
class SessionController(private val service: SessionService) {

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(): SessionView = SessionView.of(service.create())

    @GetMapping
    fun list(
        @RequestParam(required = false) status: SessionStatus?,
        @RequestParam(defaultValue = "50") limit: Int,
        @RequestParam(defaultValue = "0") offset: Int,
    ): List<SessionView> = service.list(status, limit, offset).map(SessionView::of)

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): SessionView = SessionView.of(service.get(id))

    @GetMapping("/{id}/participants")
    fun participants(@PathVariable id: UUID): List<ParticipantView> =
        service.participants(id).map(ParticipantView::of)

    @PostMapping("/{id}/join")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun join(@PathVariable id: UUID, @Valid @RequestBody body: JoinRequest) {
        service.join(id, body.userId)
    }

    @PostMapping("/{id}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun leave(@PathVariable id: UUID, @Valid @RequestBody body: LeaveRequest) {
        service.leave(id, body.userId)
    }

    @PostMapping("/{id}/end")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun end(@PathVariable id: UUID) {
        service.end(id)
    }
}
