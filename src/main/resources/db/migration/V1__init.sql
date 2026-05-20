-- 1:1 채팅 이벤트 소싱 스키마
--
-- 단일 진실 공급원(SoT): events (append-only). sessions/participants는 빠른 조회용 프로젝션.
-- 순서 SoT: events.server_seq (BIGSERIAL). 중복 SoT: UNIQUE(session_id, client_event_id).

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- sessions: 1:1 대화 컨테이너.
-- last_event_seq는 매번 MAX(events.server_seq) 계산을 피하기 위한 캐시 + catch-up 워터마크.
CREATE TABLE sessions (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    status          VARCHAR(16) NOT NULL,                   -- ACTIVE | INTERRUPTED | ENDED
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ended_at        TIMESTAMPTZ,
    last_event_seq  BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT sessions_status_chk CHECK (status IN ('ACTIVE','INTERRUPTED','ENDED'))
);

-- 세션 목록(status 필터 + 최신순) hot path용.
CREATE INDEX idx_sessions_status_created_at ON sessions (status, created_at DESC);

-- participants: 1:1이라 세션당 최대 2행. PK가 멱등 join의 ON CONFLICT 키.
-- left_at IS NULL = 현재 참여 중. last_seen_seq = 재연결 catch-up 시작점.
CREATE TABLE participants (
    session_id      UUID        NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    user_id         VARCHAR(64) NOT NULL,
    joined_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    left_at         TIMESTAMPTZ,
    last_seen_seq   BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (session_id, user_id)
);

-- "유저가 현재 참여 중인 세션" 조회용 partial index — 활성 행만 인덱싱.
CREATE INDEX idx_participants_user_active
    ON participants (user_id)
    WHERE left_at IS NULL;

-- events: append-only 로그 (SoT).
-- payload는 타입별 형태가 달라 JSONB로. 인덱스는 hot path 컬럼에만.
--   MESSAGE / MESSAGE_EDIT : { messageId, text }
--   MESSAGE_DELETE         : { messageId }
--   JOIN/LEAVE/*           : {}  (actor_user_id로 식별)
CREATE TABLE events (
    server_seq      BIGSERIAL   PRIMARY KEY,                -- 순서 SoT
    session_id      UUID        NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    type            VARCHAR(32) NOT NULL,
    actor_user_id   VARCHAR(64),
    client_event_id VARCHAR(64) NOT NULL,                   -- 클라 멱등성 키
    client_ts       TIMESTAMPTZ NOT NULL,                   -- 표시용 (순서 판정엔 미사용)
    server_ts       TIMESTAMPTZ NOT NULL DEFAULT NOW(),     -- 시간 컷오프용
    payload         JSONB       NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT events_type_chk CHECK (type IN (
        'MESSAGE','MESSAGE_EDIT','MESSAGE_DELETE',
        'JOIN','LEAVE','DISCONNECT','RECONNECT',
        'SESSION_END'
    ))
);

-- 중복 차단 + INSERT ... ON CONFLICT DO NOTHING 키.
CREATE UNIQUE INDEX uq_events_session_client_event_id
    ON events (session_id, client_event_id);

-- 타임라인 리플레이: WHERE session_id=? AND server_seq<=? ORDER BY server_seq.
CREATE INDEX idx_events_session_seq
    ON events (session_id, server_seq);

-- 시간 기반 조회: /timeline?at=, /events?from=&to=.
CREATE INDEX idx_events_session_server_ts
    ON events (session_id, server_ts);

-- 타입별 부분 조회 (예: "최근 메시지 N개"는 type='MESSAGE'만).
CREATE INDEX idx_events_session_type_seq
    ON events (session_id, type, server_seq);

-- snapshots: 리플레이 비용 상한용 머터리얼라이즈드 상태.
-- up_to_seq=N 행은 "seq<=N인 이벤트 적용 결과 상태"를 의미.
-- 복원 = snapshot @ N + (seq > N 이벤트 리플레이).
CREATE TABLE snapshots (
    session_id  UUID        NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
    up_to_seq   BIGINT      NOT NULL,
    state       JSONB       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (session_id, up_to_seq)
);

-- "seq N 이하 중 최신 snapshot" 단건 조회용 DESC 인덱스.
CREATE INDEX idx_snapshots_session_seq_desc
    ON snapshots (session_id, up_to_seq DESC);
