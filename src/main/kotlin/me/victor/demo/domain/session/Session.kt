package me.victor.demo.domain.session

import java.time.Instant
import java.util.UUID

/**
 * 세션의 라이프사이클 상태.
 *
 *  - [ACTIVE]: 정상 진행 중. 이벤트 수집/조회 가능.
 *  - [INTERRUPTED]: (예약) 비정상 중단. 현재 코드 경로에서는 사용하지 않으나,
 *    "관리자 강제 일시정지" 같은 미래 시나리오를 위해 enum에는 보존.
 *  - [ENDED]: 종료. 이후 비-SESSION_END 이벤트는 409로 거절.
 */
enum class SessionStatus { ACTIVE, INTERRUPTED, ENDED }

/**
 * 세션(1:1 대화 컨테이너) 도메인 모델.
 *
 * `sessions` 테이블의 한 행을 그대로 표현한다.
 * 이벤트 로그에서 재구축 가능한 프로젝션이지만, 빠른 조회를 위해 별도 테이블로 유지.
 */
data class Session(
    /** 세션 고유 식별자. DB가 발급한 UUID v4 (gen_random_uuid). */
    val id: UUID,

    /** 현재 상태. ACTIVE / INTERRUPTED / ENDED 중 하나. */
    val status: SessionStatus,

    /** 세션이 처음 만들어진 시각 (서버 시계 기준). 변경되지 않음. */
    val createdAt: Instant,

    /** 세션이 종료된 시각. status=ENDED가 될 때만 채워짐. 그 외엔 null. */
    val endedAt: Instant?,

    /**
     * 이 세션에 쓰여진 이벤트의 최대 server_seq.
     * 재연결 시 클라이언트가 incremental sync 시작점(= last_event_seq + 1)으로 사용.
     * 매번 MAX(events.server_seq)를 계산하는 비용을 피하기 위한 캐시 컬럼.
     */
    val lastEventSeq: Long,
)
