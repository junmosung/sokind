# 쿼리 최적화 (핫패스)

대화 데이터가 대량 누적될 때(가정: 일 1M 이벤트 / 활성 세션 10K)의 조회 성능 전략.
events 테이블이 가장 빠르게 커지므로 인덱스 설계는 events 중심.

---

## 쿼리 1 — 시점 t 타임라인 복원 (가장 중요)

**의미**: `GET /sessions/{id}/timeline?at=<ts>` → 시점 t 세션 상태 복원.
**구현**: `TimelineService.restore` (`domain/timeline/TimelineService.kt`).

### 현재 (snapshot + 델타) — 이미 구현됨
```sql
-- Step 1: 시간 컷오프 → seq 컷오프 (idx_events_session_server_ts)
SELECT COALESCE(MAX(server_seq), 0)
  FROM events
 WHERE session_id = $1
   AND server_ts <= $2;

-- Step 2: cutoff 이하 가장 최신 snapshot (idx_snapshots_session_seq_desc)
SELECT up_to_seq, state
  FROM snapshots
 WHERE session_id = $1
   AND up_to_seq <= $cutoff
 ORDER BY up_to_seq DESC
 LIMIT 1;

-- Step 3: snapshot 이후 ~ cutoff까지 델타만 리플레이 (idx_events_session_seq)
SELECT server_seq, type, actor_user_id, client_event_id,
       client_ts, server_ts, payload
  FROM events
 WHERE session_id = $1
   AND server_seq >  $snapshot_seq
   AND server_seq <= $cutoff
 ORDER BY server_seq ASC
 LIMIT 10000;
```

### 복잡도
- snapshot 없는 신규 세션: O(N) (풀 리플레이)
- snapshot 있음: O(N % interval). interval=50 (기본) → 평균 ~25개 이벤트만.

### 시나리오별 병목 / 개선
| 시나리오 | 병목 | 개선 |
|---|---|---|
| 메시지가 큼(첨부/긴 텍스트) | heap fetch I/O | 본문 별도 테이블 분리(`event_messages`) — 평균 ≥ 1KB일 때만 유효 |
| 같은 세션 timeline 폭주 | DB 연결 점유 | Redis 30초 TTL 캐시 (key: `timeline:{id}:{at_초}`) |
| 인덱스 비대화 | I/O / 캐시 미스 | 오래된 세션 → `archive_events` partial archival, 콜드 조회 시만 union |
| snapshot 누락 | 폴백 풀 리플레이 비용 | 트리거 외 주기적 워커로 백필 (현재 미구현, 설계만) |

---

## 쿼리 2 — 이벤트 append (멱등)

**구현**: `EventRepository.append` (`infra/persistence/EventRepository.kt`).

```sql
INSERT INTO events (session_id, type, actor_user_id, client_event_id,
                    client_ts, payload)
VALUES ($1, $2, $3, $4, $5, $6::jsonb)
ON CONFLICT (session_id, client_event_id) DO NOTHING
RETURNING server_seq, server_ts, ...;

-- RETURNING 비어있으면 (= 멱등 hit) 같은 행을 SELECT
SELECT ... FROM events
 WHERE session_id = $1 AND client_event_id = $2;
```

### 인덱스 영향
- UNIQUE 검사 1회 (`uq_events_session_client_event_id`).
- 동시에 3개 인덱스(`session_seq`, `session_server_ts`, `session_type_seq`)도 update.

### 트랜잭션 경계 (실제 코드)
```
BEGIN
  events INSERT ... ON CONFLICT DO NOTHING RETURNING ...
  if wasInserted:
    UPDATE sessions SET last_event_seq = GREATEST(...)
    (type에 따라) UPDATE participants ... | UPDATE sessions.status
    publishEvent(EventAppended)        ← 핸들러는 AFTER_COMMIT
COMMIT
→ ChatBroadcaster (WebSocket) + SnapshotTrigger (@Async) 가 실행
```
브로드캐스트와 snapshot 생성은 모두 트랜잭션 **밖** → 락 시간 ↓.

### 시나리오별 병목 / 개선
| 시나리오 | 병목 | 개선 |
|---|---|---|
| 단일 세션에 초당 수백 메시지 | 인덱스 경합, hot row | `events PARTITION BY HASH(session_id)` |
| Hikari 풀 고갈 | 커넥션 부족 | 트랜잭션 짧게 (이미 외부 IO 분리됨) + 풀 사이즈 조정 |
| 시퀀스 컨텐션 | 매우 높은 TPS | `BIGSERIAL` → `CACHE 100` 또는 IDENTITY |
| 중복 재전송 폭주 | UNIQUE 위반 (현재 abort 없음) | BIGSERIAL은 ON CONFLICT 시에도 consume → 결번 생김 (정상, 단조성 유지) |

---

## 쿼리 3 — 재연결 catch-up (incremental sync)

**구현**: `EventRepository.findBySession(fromSeq=last_seen_seq)`.

```sql
SELECT server_seq, type, actor_user_id, client_event_id,
       client_ts, server_ts, payload
  FROM events
 WHERE session_id = $1
   AND server_seq > $last_seen_seq
 ORDER BY server_seq ASC
 LIMIT 500;
```

인덱스 `idx_events_session_seq`로 range scan, ordered.

### 시나리오별 병목 / 개선
| 시나리오 | 병목 | 개선 |
|---|---|---|
| 오래 끊겼다 복귀 (수만 건) | 페이로드 폭증 | LIMIT + cursor(`last_returned_seq`); 임계치 초과 시 full timeline fall back |
| 동시 재연결 폭주 (서버 재기동 직후) | DB 동시성 | 클라 측 지수 백오프 + jitter + health endpoint |
| 페이지네이션 중 새 이벤트 도착 | 일관성 흔들림 | 자연 처리 (server_seq 단조) — cursor가 그 시점의 seq만 넘기면 됨 |

---

## 운영 관점

- `EXPLAIN ANALYZE` 권장 시점: 인덱스 변경 전, 쿼리 수정 후. 현재 인덱스는 ordered range scan + index-only를 노림.
- VACUUM/ANALYZE: events는 append-only라 dead tuple 거의 0. `participants.left_at` 갱신은 발생 → autovacuum 기본 설정 충분.
- Hikari 20/5: 짧은 트랜잭션 + AFTER_COMMIT 분리로 연결당 처리량 높음. 부하 테스트 후 조정.
- Slow query 모니터링: 100ms 초과 로그 → [design.md §3 관측 가능성](./design.md).
