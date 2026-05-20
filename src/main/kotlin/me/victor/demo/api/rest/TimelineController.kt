package me.victor.demo.api.rest

import me.victor.demo.domain.timeline.Timeline
import me.victor.demo.domain.timeline.TimelineService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/sessions/{id}/timeline")
class TimelineController(private val service: TimelineService) {

    /** at 미지정 시 현재 시각. 응답의 asOfSeq로 이후 GET /events?from=asOfSeq incremental sync. */
    @GetMapping
    fun timeline(
        @PathVariable id: UUID,
        @RequestParam(required = false) at: Instant?,
    ): Timeline = service.restore(id, at)
}
