# 설계 문서 — 재연결 · 확장성 · 관측 · 비동기 · 장애 대응

## 1. 재연결 정합성

### 메커니즘
- 모든 이벤트가 `server_seq`로 전역 순서가 결정 → 클라가 마지막 본 seq만 알면 손실 없는 catch-up 가능.
- `sessions.last_event_seq`: 세션 워터마크.
- `participants.last_seen_seq`: 유저별 워터마크 (unread / 마지막 위치 표시).

### 시퀀스
1. WebSocket 끊김 감지 (클라 keepalive 실패).
2. `GET /sessions/{id}/timeline?at=now` → 풀 스냅샷 + `asOfSeq`.
3. WS 재연결 → `/topic/sessions/{id}` 구독.
4. `GET /sessions/{id}/events?from={asOfSeq}` 페이지네이션으로 catch-up.
5. 도중 도착하는 broadcast는 seq 기준 dedup.

### Edge case
- 너무 오래 끊긴 클라 → 페이지 임계치 초과 시 전체 timeline 재요청. 응답에 `hasMore` 힌트.
- 서버 재기동 직후 thundering herd → 클라 지수 백오프 + jitter, 서버는 `/actuator/health/readiness`.

---

## 2. 수평 확장

### 현재 (MVP)
- STOMP SimpleBroker (in-memory) → 한 인스턴스 안에서만 broadcast.
- 단일 Postgres. 일관성은 DB 트랜잭션 + UNIQUE/CHECK으로 보장.

### 다중 인스턴스
| 영역 | 전략 |
|---|---|
| HTTP | 무상태 → L7 LB 라운드로빈. |
| WebSocket | 세션 ID 기반 sticky session (consistent hash) → 같은 세션의 두 참여자가 같은 인스턴스에. SimpleBroker로도 도달. |
| WebSocket 강한 분산 | sticky 없이 → STOMP relay를 **Redis Pub/Sub** 또는 **RabbitMQ STOMP relay**로 교체. `ChatBroadcaster`는 SimpMessagingTemplate만 호출하므로 broker만 갈아끼우면 됨. |
| DB | 단일 → read replica로 timeline 조회 분산. 더 큰 스케일은 `events PARTITION BY HASH(session_id)`. |
| 세션 affinity | 없음. sticky는 broadcast 효율 최적화일 뿐 — 깨져도 정합성 영향 없음 (DB가 SoT). |

### 상태 저장 위치
- 영속: Postgres (events / sessions / participants / snapshots).
- 휘발 / presence: Redis (online 유저, typing) — 설계만, MVP 미구현.

---

## 3. 관측 가능성

### 로그
- 구조화 JSON 로그. 핵심 필드: `trace_id`, `session_id`, `actor_user_id`, `event_type`, `client_event_id`, `was_inserted`, `server_seq`, `duration_ms`.
- 레벨 가이드:
  - INFO — 세션 라이프사이클, snapshot 생성, 멱등 hit 비율 anomaly.
  - WARN — 정원 초과 join, ENDED 세션에 이벤트 시도, slow query, snapshot trigger 실패.
  - ERROR — DB 트랜잭션 실패, broadcast 실패, projection 정합성 어긋남.

### 메트릭 (Prometheus / Micrometer)
| 메트릭 | 타입 | 의미 |
|---|---|---|
| `chat_events_appended_total{type, was_inserted}` | counter | 신규 vs 멱등 hit 비율 — 재전송 폭주 감지 |
| `chat_event_append_duration_seconds` | histogram | append 트랜잭션 latency |
| `chat_timeline_restore_duration_seconds{strategy}` | histogram | "fullReplay" vs "snapshotDelta" 효과 측정 |
| `chat_snapshot_created_total` | counter | snapshot 생성 빈도 (트리거 정책 평가용) |
| `chat_ws_connected_sessions` | gauge | 현재 연결된 WebSocket 수 |
| `chat_broadcast_fanout_total{result}` | counter | broadcast 성공/실패 |
| `db_pool_active`, `db_pool_pending` | gauge | Hikari 상태 |

### 분산 추적 (OpenTelemetry)
HTTP/STOMP 요청 → `EventService.append` → `events INSERT` → `participants UPDATE` →
`ApplicationEventPublisher.publish` → `ChatBroadcaster` + `SnapshotTrigger` (별도 span).

### 대시보드 (예시)
- 세션 수 / 활성 세션 / 분당 메시지
- p50/p95/p99 append latency
- 멱등 hit 비율 (>5%면 재전송 폭주 의심)
- snapshot 생성 빈도 / 평균 델타 크기
- WebSocket 연결 안정성 (재연결 빈도)
- DB pool utilization

---

## 4. 비동기 처리

### 무엇을 비동기로
| 작업 | 동기/비동기 | 근거 |
|---|---|---|
| 이벤트 append | 동기 | 클라 응답에 `wasInserted` 필요 |
| Projection 갱신 (sessions/participants) | 동기, 같은 트랜잭션 | 부분 실패 방지 |
| WebSocket broadcast | **비동기, AFTER_COMMIT** | 외부 IO. 트랜잭션 락 시간 ↓ |
| Snapshot 생성 | **비동기, AFTER_COMMIT + @Async** | 수십 ms 걸림. append 응답 latency에 영향 없게 |
| Push 알림 등 | 비동기, outbox 패턴 | 외부 서비스 SLA 격리 |

### 현재 구현 (Day 2~3)
- `ApplicationEventPublisher.publishEvent(EventAppended)` 발행.
- `ChatBroadcaster.onAppended` — `@TransactionalEventListener(AFTER_COMMIT)`. 동기 (브로드캐스트는 빠름).
- `SnapshotTrigger.onAppended` — `@TransactionalEventListener(AFTER_COMMIT) + @Async`. 별도 스레드.
- 검증: `SnapshotIntegrationTest`에서 await 폴링으로 동작 확인.

### Outbox 패턴 (확장 시 청사진)
```
BEGIN
  INSERT INTO events ...
  INSERT INTO outbox (id, payload, status='PENDING') ...
COMMIT

[워커]
  SELECT FROM outbox WHERE status='PENDING' FOR UPDATE SKIP LOCKED LIMIT 100
  → 외부 publish (Kafka / Webhook / Push)
  → UPDATE status='SENT' on success / retry+1 on fail
```

- 재시도: 지수 백오프 + jitter, max N회.
- DLQ: `outbox_dlq` 테이블 → 알림 → 사람이 조사.
- 멱등성: `idempotency-key` = outbox row UUID. 외부 시스템이 중복 흡수.
- 동시성: `FOR UPDATE SKIP LOCKED`로 워커 간 경합 없이 분산.

---

## 5. 장애 시나리오

### A. 서버 인스턴스 다운

**감지**: LB health check 실패, 메트릭 `up=0`, 클라 keepalive timeout.
**완화**: LB가 자동 제외 → HTTP 다른 인스턴스, WebSocket 클라 재연결.
**복구**: K8s가 새 인스턴스 기동 → Flyway 자동 실행 → LB 재등록 → 클라가 timeline + catch-up.
**손실**: 인-플라이트 HTTP는 클라 재시도 (`clientEventId` 멱등성으로 안전). WebSocket buffer는 catch-up으로 복구.

---

### B. DB 장애

#### B-1. 커넥션 고갈
**감지**: `db_pool_pending` spike, `Connection is not available, request timed out after 3000ms`.
**완화**: Hikari `connectionTimeout: 3000`으로 새 요청 빠르게 5xx → 클라 백오프. 단기 풀 증가 + slow query kill.
**복구**: `pg_stat_statements`로 원인 쿼리 → 인덱스/캐싱 → 풀 사이즈 영구 조정.

#### B-2. 락 경합
**감지**: `pg_stat_activity` `wait_event_type='Lock'` 다수, `chat_event_append_duration_seconds` p99 spike.
**완화**: 트랜잭션 짧게 유지 (이미 broadcast/snapshot 분리). 핫 행 분산은 partition 검토.
**복구**: `EXPLAIN ANALYZE`로 락 원인 → 인덱스/쿼리 재작성.

#### B-3. DB 노드 다운
**감지**: 헬스체크 실패, 인스턴스 일제 connection refused.
**완화**: 모든 인스턴스 fail-fast → 503. Circuit breaker 가능. WebSocket은 유지하되 publish 시 에러.
**복구**: 자동 failover (Patroni / RDS Multi-AZ / Cloud SQL HA) → replica 승격. synchronous replication이면 commit된 데이터는 손실 0.

---

### C. 데이터 정합성

#### C-1. 중복 저장
**감지**: `chat_events_appended_total{was_inserted="false"}` 비율 임계치(예 10%) 초과, 같은 messageId 다수 발견.
**완화**: `client_event_id` UNIQUE가 1차 방어선. messageId 중복은 리플레이 단계 first-write-wins로 흡수.
**복구**: 멱등 hit 비율 상승 원인(클라 재전송 폭주) 분석 → 클라 SDK 백오프.

#### C-2. 부분 실패 (event INSERT 성공, projection 실패)
**감지**: 이론상 불가 (단일 트랜잭션). 발생 시 `sessions.last_event_seq < MAX(events.server_seq)` 또는 participants 누락.
**진단 SQL**:
```sql
SELECT s.id, s.last_event_seq, MAX(e.server_seq) AS actual
  FROM sessions s LEFT JOIN events e ON e.session_id = s.id
 GROUP BY s.id, s.last_event_seq
HAVING s.last_event_seq <> COALESCE(MAX(e.server_seq), 0);
```
**복구**: events에서 projection 재계산하는 admin 워커.

#### C-3. Broadcast 누락
**감지**: 클라 sequence gap, `chat_broadcast_fanout_total{result="failure"}` 증가.
**완화/복구**: 클라가 gap 감지 시 `GET /events?from=last_seen_seq`로 자동 catch-up. broadcast가 best-effort여도 정합성은 결국 수렴.

---

## 6. 한 줄 정리

> **events 테이블에 append만 잘되면 무엇이든 복구 가능.**
> projection / broadcast / snapshot은 전부 보조 장치 — best-effort로 다루고,
> 정합성의 최후 보루는 항상 이벤트 로그 + 결정론적 리플레이.
