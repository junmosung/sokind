package me.victor.demo.domain.event

import java.time.Instant
import java.util.UUID

/**
 * 이벤트 타입.
 *
 * 채팅 도메인에서 발생할 수 있는 모든 상태 변화를 enum으로 명시.
 * 신규 타입 추가 시 DB의 CHECK 제약([V1__init.sql])도 같이 갱신해야 함.
 *
 *  - [MESSAGE]: 새 메시지 전송. payload에 messageId + text.
 *  - [MESSAGE_EDIT]: 기존 메시지 수정. payload에 messageId + 새 text.
 *  - [MESSAGE_DELETE]: 메시지 삭제. payload에 messageId.
 *  - [JOIN]: 사용자 세션 참여. actor_user_id가 누구인지 식별.
 *  - [LEAVE]: 사용자 세션 나가기.
 *  - [DISCONNECT]: presence 신호 — 네트워크 단절 등. 프로젝션 변경 없음(향후 Redis presence).
 *  - [RECONNECT]: presence 신호 — 재연결 성공.
 *  - [SESSION_END]: 세션 종료. 이 이후 비-SESSION_END 이벤트는 거절됨.
 */
enum class ChatEventType {
    MESSAGE, MESSAGE_EDIT, MESSAGE_DELETE,
    JOIN, LEAVE, DISCONNECT, RECONNECT,
    SESSION_END,
}

/**
 * Append-only 이벤트 레코드. `events` 테이블의 한 행과 1:1 매핑.
 *
 * 이 레코드는 **불변(immutable)**. 한 번 저장된 이벤트는 절대 변경/삭제되지 않으며,
 * 모든 상태 변경은 새로운 이벤트로만 표현된다 (이벤트 소싱 원칙).
 */
data class ChatEvent(
    /**
     * 전역 단조 증가 시퀀스. DB가 BIGSERIAL로 부여.
     * **모든 순서 판정의 SoT** — 리플레이/타임라인 정렬에 사용.
     * 클라이언트 시계나 네트워크 지연과 무관하게 결정론적인 순서를 보장한다.
     *
     * 참고: ON CONFLICT DO NOTHING 시에도 sequence는 consume되므로 결번이 생길 수 있음
     * (Postgres BIGSERIAL의 정상 동작). 단조성은 유지된다.
     */
    val serverSeq: Long,

    /** 이 이벤트가 속한 세션. */
    val sessionId: UUID,

    /** 이벤트 종류. payload 구조와 프로젝션 반영 방식이 이 값에 따라 분기. */
    val type: ChatEventType,

    /**
     * 이 이벤트를 발생시킨 사용자.
     * MESSAGE / MESSAGE_EDIT / MESSAGE_DELETE / JOIN / LEAVE / DISCONNECT / RECONNECT는 필수.
     * SESSION_END 같은 시스템 이벤트는 null 허용.
     */
    val actorUserId: String?,

    /**
     * 클라이언트가 발급한 멱등성 키.
     * **(sessionId, clientEventId)**는 events 테이블의 UNIQUE 키 → 동일 키 재전송은
     * `INSERT … ON CONFLICT DO NOTHING`으로 collapse된다.
     * 클라는 같은 논리적 작업의 재시도에 반드시 같은 키를 사용해야 한다.
     */
    val clientEventId: String,

    /**
     * 클라이언트가 보고한 시각.
     * **순서 판정에는 사용하지 않음** (악의적/오작동 클라의 시계 왜곡 방지).
     * UI에서 "보낸 시각" 표시, 오프라인 작성 메시지의 원래 작성 시각 보존 등 용도.
     */
    val clientTs: Instant,

    /**
     * 서버가 INSERT한 시각. server_ts <= t 조건의 시점 복원에 사용.
     * 같은 server_ts에 여러 이벤트가 있을 수 있으므로 server_seq가 tie-breaker.
     */
    val serverTs: Instant,

    /**
     * 타입별로 형태가 다른 추가 데이터.
     *  - MESSAGE / MESSAGE_EDIT: `{ "messageId": "...", "text": "..." }`
     *  - MESSAGE_DELETE:        `{ "messageId": "..." }`
     *  - JOIN/LEAVE/DISCONNECT/RECONNECT/SESSION_END: `{}` (누가 했는지는 actorUserId)
     *
     * JSONB로 저장되어 스키마 진화에 자유롭다. application validation으로 형태 검증.
     */
    val payload: Map<String, Any?>,
)
