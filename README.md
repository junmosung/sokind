# 1:1 Chat (Event-Sourced)

1:1 실시간 채팅 서비스 + 이벤트 소싱 기반 시점 복원.
Spring Boot 4 (Kotlin, JDK 24) + PostgreSQL 16 + jOOQ + STOMP over WebSocket.

> **📋 평가자용 한눈에 보기**: [**docs/submission-checklist.md**](docs/submission-checklist.md) — 과제 §6 제출물 체크리스트 항목별 위치/검증 매핑 (필수 6/6 + 가산점 4).

## 제출물 체크리스트 (한눈에)

| # | 항목 | 위치 |
|---|---|---|
| 1 | README — 실행/환경/의사결정 | 이 파일 |
| 2 | API 명세 (OpenAPI) | [`openapi.yaml`](openapi.yaml) + Swagger UI `/swagger.html` |
| 3 | ERD + 핵심 DDL | [`docs/db.md`](docs/db.md) + [`V1__init.sql`](src/main/resources/db/migration/V1__init.sql) |
| 4 | 주요 쿼리 + 인덱스 + 병목 | [`docs/queries.md`](docs/queries.md) — 핫패스 3개 |
| 5 | 설계 (재연결/중복/확장/관측/장애) | [`docs/design.md`](docs/design.md) + [`docs/event-sourcing.md`](docs/event-sourcing.md) |
| 6 | 이벤트 기반 상태 복원 | [`docs/event-sourcing.md`](docs/event-sourcing.md) + [`TimelineService.kt`](src/main/kotlin/me/victor/demo/domain/timeline/TimelineService.kt) |
| + | 가산점 4종 (Snapshot 자동화/비동기 Projection/헥사고날 청사진/Testcontainers 11/11) | [`docs/submission-checklist.md`](docs/submission-checklist.md#가산점-항목-4-추가-구현) |

자세한 점검 결과 + 검증 방법은 [**docs/submission-checklist.md**](docs/submission-checklist.md).

## 한 줄 설계 요약
**모든 도메인 변경을 `events` 테이블에 append-only로 기록 → 어느 시점이든 그 로그만으로 상태 재구축이 가능하다.**

- 순서 SoT: `server_seq` (BIGSERIAL).
- 중복 SoT: `(session_id, client_event_id)` UNIQUE + `INSERT … ON CONFLICT DO NOTHING`.
- `sessions` / `participants`는 빠른 조회용 프로젝션. 정합성 깨져도 events에서 재구축 가능.

---

## 실행

### 사전 요구
- Docker (Postgres 16 컨테이너 기동)
- JDK 24 (gradle toolchain이 자동으로 받음)

### 1. Postgres 기동 (**필수: jOOQ 코드 생성도 이 DB를 introspect함**)
```bash
docker compose up -d postgres
```

### 2. 앱 기동
```bash
./gradlew bootRun
```
- 빌드 단계: jOOQ가 라이브 DB의 스키마를 introspect해서 타입 안전한 DSL 코드를 `build/generated-sources/jooq/`에 생성 (커밋하지 않음).
- 첫 기동: Flyway가 `V1__init.sql`을 실행해 스키마를 만든다.
- `http://localhost:8080`에서 동작.

### 3. Swagger UI
```
http://localhost:8080/swagger.html
```
루트의 `openapi.yaml`을 SoT로 두고, 빌드 시 `static/`으로 복사되어 `/openapi.yaml`로 서빙된다.

### 4. 통합 테스트 (`./gradlew test`)
Testcontainers가 Postgres 컨테이너를 자동 기동 → Flyway 적용 → 시나리오 검증까지 한 번에.
docker만 실행 중이면 `docker compose up`도 필요 없음.

```bash
./gradlew test
```

| 테스트 파일 | 검증 내용 | 테스트 수 |
|---|---|---|
| `ChatIntegrationTest` | 세션 라이프사이클, 1:1 정원, ENDED 거절, 멱등성, 순서 결정성, 시점 복원(EDIT/DELETE) | 5 |
| `SnapshotIntegrationTest` | interval 도달 시 자동 생성, snapshot+델타 == 풀 리플레이 결정론 | 2 |
| `WebSocketIntegrationTest` | STOMP 구독 + REST publish 브로드캐스트, 멱등 hit 미전파, serverSeq 단조성 | 3 |
| `DemoApplicationTests` | 컨텍스트 로드 | 1 |
| **합계** | | **11** |

### 5. (선택) 스키마/데이터 초기화
```bash
docker exec chat-postgres psql -U chat -d chat -c \
  "DROP SCHEMA public CASCADE; CREATE SCHEMA public; GRANT ALL ON SCHEMA public TO chat;"
```

---

## 동작 검증

### REST 시나리오 (curl)
```bash
BASE=http://localhost:8080

# 세션 생성
SID=$(curl -sf -X POST $BASE/sessions | jq -r .id)

# 참여
curl -sf -X POST $BASE/sessions/$SID/join -H 'content-type: application/json' -d '{"userId":"alice"}'
curl -sf -X POST $BASE/sessions/$SID/join -H 'content-type: application/json' -d '{"userId":"bob"}'

# 메시지 보내기 (멱등 키 cli-1)
curl -sf -X POST $BASE/sessions/$SID/events -H 'content-type: application/json' -d '
{"clientEventId":"cli-1","type":"MESSAGE","actorUserId":"alice","payload":{"messageId":"m1","text":"안녕"}}'

# 같은 cli-1 재전송 → wasInserted=false, 같은 serverSeq 반환
curl -sf -X POST $BASE/sessions/$SID/events -H 'content-type: application/json' -d '
{"clientEventId":"cli-1","type":"MESSAGE","actorUserId":"alice","payload":{"messageId":"m1","text":"안녕"}}'

# 현재 시점 상태 복원
curl -sf "$BASE/sessions/$SID/timeline"

# 특정 시점 복원
curl -sf "$BASE/sessions/$SID/timeline?at=2026-05-20T02:30:00Z"

# 이벤트 로그 (디버깅)
curl -sf "$BASE/sessions/$SID/events?from=0&limit=100"
```

### WebSocket(STOMP) 시나리오
```bash
cd /tmp && mkdir -p ws-check && cd ws-check
npm init -y && npm install @stomp/stompjs ws sockjs-client
# 이후 docs/event-sourcing.md의 검증 절차 또는 첨부 check.mjs 사용
```
- 구독: `/topic/sessions/{sessionId}`
- 발행: `/app/sessions/{sessionId}/events` (본문은 REST의 AppendEventRequest와 동일)

자동화된 E2E 검증 스크립트 예시는 [`/tmp/ws-check/check.mjs`](#) 참조.

---

## 디렉토리 구조
```
demo/
├── README.md                      ← 이 파일
├── openapi.yaml                   ← API 명세 (OpenAPI 3.1)
├── docker-compose.yml             ← Postgres + Redis
├── build.gradle.kts               ← 의존성 + Boot 4 모듈 분리 이슈 주석
├── docs/
│   ├── db.md                      ← ERD + DDL 설계 근거 + 인덱스 트레이드오프
│   ├── queries.md                 ← 핫패스 쿼리 3개 + 인덱스 동작 + 병목 분석
│   ├── event-sourcing.md          ← 복원 전략, 중복/순서 처리, 재연결 흐름
│   └── design.md                  ← 재연결 / 수평 확장 / 관측 / 비동기 / 장애 시나리오
└── src/main/
    ├── kotlin/me/victor/demo/
    │   ├── DemoApplication.kt         ← @SpringBootApplication, @EnableAsync
    │   ├── common/                    ← RestControllerAdvice + 예외 타입 (도메인/인프라 공용)
    │   ├── domain/                    ← 도메인 코어 — 모델 + 비즈니스 로직 (Service)
    │   │   ├── session/               (Session, SessionStatus, SessionService)
    │   │   ├── participant/           (Participant)
    │   │   ├── event/                 (ChatEvent, ChatEventType, EventService, AppendCommand, EventAppended)
    │   │   ├── timeline/              (Timeline, ParticipantSnapshot, MessageSnapshot, MessageStatus, SnapshotState, TimelineService)
    │   │   └── snapshot/              (SnapshotService, SnapshotTrigger)
    │   ├── api/                       ← 외부 노출 어댑터 (REST + WebSocket)
    │   │   ├── rest/                  (SessionController, EventController, TimelineController + 요청/응답 DTO)
    │   │   └── ws/                    (WebSocketConfig, ChatStompController, ChatBroadcaster + STOMP DTO)
    │   └── infra/                     ← 인프라 어댑터 (DB, JSON)
    │       ├── persistence/           (jOOQ Repository 4종 + AppendResult, StoredSnapshot)
    │       └── json/                  (JsonbCodec)
    └── resources/
        ├── application.yaml
        ├── static/swagger.html        ← Swagger UI (CDN 기반, /swagger.html)
        └── db/migration/V1__init.sql  ← 핵심 DDL (모든 설계 결정 주석에 박힘)

build/generated-sources/jooq/main/     ← jOOQ codegen 산출물 (커밋 안 함)
└── me/victor/demo/jooq/                 ← Tables, Records, Keys, Indexes
```

### 레이어 의존 방향
```
api (REST/WS) ───┐
                 ├──→ domain (서비스 + 도메인 모델)  ───→ infra (Repository, JsonbCodec)
infra ───────────┘                                           │
                                                             ↓
                                                          jOOQ generated
```
- **domain은 api/infra를 모르지 않음** (실용 layered: Service가 Repository를 직접 import).
  헥사고날 풀 적용 시 outbound port 인터페이스로 한 단계 더 분리 가능 ([docs/architecture.md](docs/architecture.md) 참조).
- **api는 domain만 의존** — Controller가 Repository를 직접 호출하지 않음.
- **infra는 domain의 모델 타입을 import**해서 매핑 — 의존 방향은 infra → domain (역방향 의존 0).

---

## 주요 의사결정 요약

| 결정 | 선택 | 근거 |
|---|---|---|
| 통신 프로토콜 | STOMP over WebSocket | Spring 친화적, pub/sub broadcast가 1:1·N:N 양쪽 쉬움. 단일 인스턴스는 SimpleBroker, 다중은 Redis/Rabbit relay로 교체. |
| ORM | **jOOQ** (DSL + Flyway 스키마 SoT) | 컴파일 타임 타입 안전 + SQL 그대로 노출. `INSERT … ON CONFLICT DO NOTHING RETURNING`, `GREATEST`, JSONB 같은 Postgres 고유 기능을 그대로 표현 가능. 라이브 DB introspection으로 코드 생성 → 스키마 ↔ 코드 drift 0. |
| 순서 결정 기준 | DB BIGSERIAL `server_seq` | 클라 시계/네트워크 지연 무관하게 결정적. 리플레이가 항상 같은 상태 산출. |
| 중복 방지 위치 | DB UNIQUE + ON CONFLICT | 4중 방어 (DB → Repository → Service → Broadcaster). 멱등 hit이면 projection/broadcast 모두 스킵. |
| 시점 복원 | 풀 이벤트 리플레이 (MVP) | 평균 세션 크기에서 충분. Snapshot+델타는 Day 3 가산점으로 분리 설계. |
| 메시지 EDIT/DELETE | 인메모리 머지 (리플레이 시 적용) | 별도 테이블 없이 events만으로 결정적 결과. DELETE 이후 EDIT은 무시. |
| Broadcast 결합도 | `ApplicationEventPublisher` + AFTER_COMMIT | rollback된 이벤트가 외부로 안 샘. snapshot 워커 / 메트릭 등 future fan-out도 같은 경로. |
| 인증 | 본문의 `userId` 신뢰 (과제 non-goal) | 실 운영에선 JWT 클레임에서 추출하고 STOMP CONNECT 헤더 검증. README에 가정으로 명시. |

자세한 트레이드오프는 [docs/db.md](docs/db.md), [docs/event-sourcing.md](docs/event-sourcing.md) 참조.

---

## 평가 항목 ↔ 결과물 매핑

| 평가 항목 | 위치 |
|---|---|
| 실시간 메시지 송수신 | `api/ws/ChatStompController.kt`, `api/ws/ChatBroadcaster.kt` |
| join/leave + presence | `domain/session/SessionService.kt`, `infra/persistence/ParticipantRepository.kt` |
| 이벤트 수집 API | `api/rest/EventController.kt` (POST/GET) |
| 중복 이벤트 방지 | `infra/persistence/EventRepository.kt` (ON CONFLICT), `domain/event/EventService.kt` (wasInserted 분기) |
| 순서 뒤바뀜 처리 | `server_seq` 기반 정렬 (모든 리플레이/조회) |
| 시점 상태 복원 | `domain/timeline/TimelineService.kt` + `GET /sessions/{id}/timeline?at=` |
| Snapshot 자동 생성 | `domain/snapshot/SnapshotService.kt`, `SnapshotTrigger.kt` (@Async + AFTER_COMMIT) |
| DB 설계 (ERD/DDL/인덱스 근거) | [docs/db.md](docs/db.md) + `src/main/resources/db/migration/V1__init.sql` |
| REST API 스펙 | [openapi.yaml](openapi.yaml) |
| 쿼리 최적화 / 병목 분석 | [docs/queries.md](docs/queries.md) |
| 재연결 정합성 | [docs/design.md](docs/design.md) §1 + [docs/event-sourcing.md](docs/event-sourcing.md) |
| 수평 확장 전략 | [docs/design.md](docs/design.md) §2 |
| 관측 가능성 설계 | [docs/design.md](docs/design.md) §3 |
| 비동기 처리 (Outbox/DLQ/Idempotency) | [docs/design.md](docs/design.md) §4 |
| 장애 시나리오 3종 | [docs/design.md](docs/design.md) §5 |
| 이벤트 기반 상태 복원 설계 | [docs/event-sourcing.md](docs/event-sourcing.md) |

---

## 가정 (Assumptions)

- **인증/인가**: REST 본문/STOMP 메시지의 `userId`를 신뢰. 실 운영에선 JWT 등으로 검증.
- **1:1 정원**: 세션 당 최대 2명. 세 번째 다른 userId의 join은 409.
- **`clientEventId` 발급 책임**: 클라이언트. 누락 시 멱등성 보장 X (REST `/events`는 400, `/join|leave|end`는 서버가 UUID로 발급).
- **단일 인스턴스 가정** (MVP). 다중 인스턴스 확장은 [docs/design.md §2](docs/design.md) 참조.
- **시간 신뢰**: 서버 시계가 SoT. NTP 동기화는 운영 기본.

---

## 알려진 동작 / 부채

- **`BIGSERIAL` 결번**: `INSERT … ON CONFLICT DO NOTHING` 시에도 sequence가 consume되어 `server_seq`에 빈 번호가 생길 수 있음 (Postgres 정상 동작). 단조 증가는 유지되므로 순서 판정엔 영향 없음.
- **Snapshot 자동 생성 미구현**: 인프라/스키마(`snapshots` 테이블 + 인덱스)는 준비됨. 워커 구현은 가산점 영역. [docs/event-sourcing.md](docs/event-sourcing.md) "Snapshot + 델타" 참조.
- **Projection 비동기 파이프라인 미구현**: 현재는 동기. 분리 설계는 [docs/design.md §4](docs/design.md) "Outbox 패턴" 참조.
- **다중 인스턴스 broadcast**: 현재 SimpleBroker(in-memory). Redis/Rabbit relay로 교체 설계만.
- **부하 테스트 결과 없음**: 인덱스/풀 사이즈는 합리적 기본값. 실측 후 조정 예정.

---

## 추가 구현 항목 (가산점)

- ✅ **Snapshot 자동 생성** (`SnapshotService` + `SnapshotTrigger` @Async). interval은 `chat.snapshot.interval-events` (기본 50).
- ✅ **Projection 비동기 분리** — Broadcast는 `@TransactionalEventListener(AFTER_COMMIT)`로 트랜잭션 외 처리.
- ✅ **헥사고날 청사진 + 부분 적용 전략** — [docs/architecture.md](docs/architecture.md)
- ✅ **Testcontainers 통합 테스트** — `./gradlew test` 한 번에 Postgres 자동 기동 + 11개 시나리오 검증.
- (예정) k6 부하 테스트 시나리오
