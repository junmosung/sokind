package me.victor.demo.domain.participant

import java.time.Instant
import java.util.UUID

/**
 * 세션 참여자.
 *
 * `participants` 테이블의 한 행. 1:1 세션이므로 한 sessionId당 최대 2개 행이 존재.
 * 같은 user가 leave 후 다시 join하면 같은 행이 left_at NULL로 되돌아간다 (joined_at은 보존).
 */
data class Participant(
    /** 어느 세션의 참여자인가. PK의 일부. */
    val sessionId: UUID,

    /** 참여한 사용자 식별자. 현재는 클라가 본문에 실어 보낸 값을 그대로 신뢰. PK의 일부. */
    val userId: String,

    /** 이 사용자가 세션에 처음 들어온 시각. 재참여해도 갱신되지 않음 (이력성 보존). */
    val joinedAt: Instant,

    /**
     * 마지막으로 세션을 떠난 시각.
     *  - null: 현재 참여 중 (online으로 간주).
     *  - non-null: 떠난 상태. 재참여 시 다시 null로 돌아감.
     */
    val leftAt: Instant?,

    /**
     * 이 사용자가 마지막으로 본 이벤트의 server_seq.
     * 재연결 시 catch-up 시작점, unread 개수 계산에 사용.
     */
    val lastSeenSeq: Long,
) {
    /** leftAt이 null이면 참여 중(=online)으로 간주. */
    val isOnline: Boolean get() = leftAt == null
}
