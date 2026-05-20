# 이벤트 기반 상태 복원

## 한 줄 요약
**모든 도메인 변경을 `events`에 append하고, 어느 시점이든 그 로그만으로 상태를 재구축할 수 있게 한다.**

## 기본 원칙

| 원칙 | 적용 |
|---|---|
| events는 단일 진실 공급원(SoT) | sessions/participants는 빠른 조회용 프로젝션. 어긋나면 events로 재계산. |
| 순서는 `server_seq`가 결정 | DB가 BIGSERIAL로 부여. 클라 시계/네트워크 무관 결정적. |
| 시간 매핑은 `server_ts` | `at=<ts>` 복원에 사용. 같은 ts 내 tie-break는 seq. |
| 중복은 `(session_id, client_event_id)` UNIQUE | `INSERT … ON CONFLICT DO NOTHING`. 멱등 hit은 projection/broadcast 모두 스킵. |
| `client_ts`는 표시용 | 오프라인 메시지의 원본 시각 보존. 순서 결정에 절대 미사용. |

---

## 복원 전략 (현재 구현: snapshot + 델타)

`TimelineService.restore(sessionId, at)`:

```
1. cutoffSeq = MAX(server_seq) WHERE server_ts <= at
2. base     = snapshots WHERE up_to_seq <= cutoffSeq ORDER BY up_to_seq DESC LIMIT 1
3. state    = base ? deserialize(base.state) : empty
4. delta    = events WHERE server_seq > base.upToSeq AND <= cutoffSeq ORDER BY server_seq ASC
5. delta.forEach { state.apply(it) }
6. → Timeline(asOfSeq=cutoffSeq, asOfTime=at, ...)
```

복잡도: O(N % snapshot_interval). interval=50 기본 → 평균 ~25개 이벤트만 리플레이.

### Snapshot 자동 생성 (`SnapshotService` + `SnapshotTrigger`)
- 트리거: `EventAppended`를 `@TransactionalEventListener(AFTER_COMMIT) + @Async`로 받음.
- 정책: count-based. 마지막 snapshot의 `upToSeq`에서 `chat.snapshot.interval-events` 이상 진행됐을 때만 생성.
- 멱등성: `SnapshotRepository.save`가 ON CONFLICT DO NOTHING → 두 워커가 같은 (sessionId, upToSeq)에 동시 진입해도 안전. 내용이 결정론적이라 어느 행이 살아남든 동일.
- 실패는 best-effort: snapshot이 영영 안 생겨도 풀 리플레이로 정상 복원되므로 로그만 남기고 삼킴.
- 검증: `SnapshotIntegrationTest`에서 "snapshot+델타 == 풀 리플레이" hash 일치 보장.

---

## 중복 이벤트 처리 (4중 방어)

| 레이어 | 차단 방법 |
|---|---|
| DB | `UNIQUE (session_id, client_event_id)` |
| Repository | `INSERT … ON CONFLICT DO NOTHING RETURNING …` (멱등 hit이면 RETURNING 비고, 같은 키로 SELECT) |
| Service | `wasInserted=false`면 projection 재적용/도메인 이벤트 발행 모두 스킵 |
| Broadcast | 멱등 hit은 publishEvent 자체가 없어 broadcaster 도달 안 함 |

### 응답 계약
```json
POST /sessions/{id}/events
→ 200 OK
{ "serverSeq": 42, "clientEventId": "cli-1", "wasInserted": false, ... }
```
`wasInserted=false` + 동일 `serverSeq/serverTs` → 클라가 위치 정보를 일관되게 사용 가능.

### 멱등성 키 발급 책임
- 클라이언트가 UUID/ULID/단조 카운터 등으로 발급. 같은 작업의 재시도는 같은 키.
- REST `/join|leave|end`는 서버가 `join-{uuid}` 자동 생성 (HTTP 재시도 dedup이 필요하면 클라 명시 키로 확장 가능).

---

## 순서 뒤바뀜 처리

### 시나리오
- 약전파 모바일 클라가 메시지 3개를 거의 동시에 보냄 → 1번이 가장 늦게 도착.
- 두 클라 동시 메시지 → 서버 도착 순서가 사용자 의도와 다름.

### 규칙
1. 수신 순서 = `server_seq` 부여 순서가 모든 판단의 기준.
2. `client_ts`는 UI 표시용. 정렬 키로 사용 ❌.
3. 리플레이는 항상 `server_seq ASC` → 같은 이벤트 집합은 항상 같은 상태(결정론).

### MESSAGE_EDIT/DELETE 충돌
- `EDIT` 다음 `DELETE` → DELETED.
- `DELETE` 다음 `EDIT` → 여전히 DELETED (코드에서 명시 무시, first-DELETE-wins).
- 같은 messageId에 MESSAGE 두 번 → first-write-wins 방어 (정상 경로엔 `client_event_id` UNIQUE로 이미 차단).

---

## 재연결 정합성 흐름

```
[클라]                                     [서버]
  | --- (WS 끊김) ---                       |
  |                                          |
  |--- GET /sessions/{id}/timeline?at=now → |
  |          ← Timeline { asOfSeq=N, participants, messages }
  |                                          |
  |--- WS 재연결 → /topic/sessions/{id} 구독 |
  |                                          |
  |--- GET /events?from=N&limit=500       → |
  |          ← [seq=N+1...M]                 |
  |                                          |
  | (지금부터 broadcast 수신)                 |
```

- `asOfSeq` = 다음 incremental sync 시작점 워터마크.
- catch-up 페이지 도중 도착하는 broadcast는 seq 기준 dedup → 자연 처리.
- 다중 인스턴스 정합성은 [design.md §1, §2](./design.md).
