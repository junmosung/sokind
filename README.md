# 1:1 Chat (Event-Sourced)

1:1 실시간 채팅 서비스 + 이벤트 소싱 기반 시점 복원.
Spring Boot 4 (Kotlin, JDK 24) + PostgreSQL 16 + jOOQ + STOMP over WebSocket.

## 제출물 체크리스트 (필수 6/6 + 가산점 4)

| # | 항목 | 위치 |
|---|---|---|
| 1 | README — 실행/환경/의사결정 | 이 파일 |
| 2 | API 명세 (OpenAPI) | [`openapi.yaml`](openapi.yaml) + Swagger UI `/swagger.html` |
| 3 | ERD + 핵심 DDL | [`docs/db.md`](docs/db.md) + [`V1__init.sql`](src/main/resources/db/migration/V1__init.sql) |
| 4 | 주요 쿼리 + 인덱스 + 병목 | [`docs/queries.md`](docs/queries.md) — 핫패스 3개 |
| 5 | 설계 (재연결/중복/확장/관측/장애) | [`docs/design.md`](docs/design.md) + [`docs/event-sourcing.md`](docs/event-sourcing.md) |
| 6 | 이벤트 기반 상태 복원 | [`docs/event-sourcing.md`](docs/event-sourcing.md) + [`TimelineService.kt`](src/main/kotlin/me/victor/demo/domain/timeline/TimelineService.kt) |
| + | 가산점 4종 (Snapshot 자동화 / 비동기 Broadcast / 헥사고날 청사진 / Testcontainers 11/11) | [`docs/submission-checklist.md`](docs/submission-checklist.md) |

항목별 상세 매핑·검증 방법은 [**docs/submission-checklist.md**](docs/submission-checklist.md).

## 한 줄 설계 요약
**모든 도메인 변경을 `events` 테이블에 append-only로 기록 → 어느 시점이든 그 로그만으로 상태 재구축이 가능하다.**

- 순서 SoT: `server_seq` (BIGSERIAL).
- 중복 SoT: `(session_id, client_event_id)` UNIQUE + `INSERT … ON CONFLICT DO NOTHING`.
- `sessions` / `participants`는 빠른 조회용 프로젝션. 정합성 깨져도 events에서 재구축 가능.

---

## 빠른 검증 — 권장 (5초)

```bash
./gradlew test
```

Testcontainers가 Postgres를 자동 기동 → Flyway 적용 → 11개 시나리오 자동 검증. Docker만 실행 중이면 OK.

| 테스트 | 검증 내용 | 수 |
|---|---|---|
| `ChatIntegrationTest` | 세션 라이프사이클, 1:1 정원, ENDED 거절, 멱등성, 순서 결정성, 시점 복원(EDIT/DELETE) | 5 |
| `SnapshotIntegrationTest` | interval 도달 시 자동 생성, snapshot+델타 == 풀 리플레이 결정론 | 2 |
| `WebSocketIntegrationTest` | STOMP 구독 + REST publish 브로드캐스트, 멱등 hit 미전파, serverSeq 단조성 | 3 |
| `DemoApplicationTests` | 컨텍스트 로드 | 1 |

---

## End-to-End 수동 검증 — 단계별

### 사전 요구
- Docker (Postgres 16 컨테이너)
- JDK 24 (gradle toolchain이 자동으로 설치)
- `jq` (응답 파싱용)

### Step 1 — Postgres 기동
```bash
docker compose up -d postgres
```
**확인**:
```bash
docker ps --filter name=chat-postgres
# STATUS에 "Up X seconds (healthy)" 가 보여야 함
```

### Step 2 — 앱 빌드 + 기동
```bash
./gradlew bootRun
```
**내부 동작**:
1. `jooqCodegen` — 라이브 Postgres의 스키마를 introspect해서 타입 안전 DSL 코드를 `build/generated-sources/jooq/`에 생성 (커밋되지 않음).
2. `compileKotlin` — 코드 컴파일.
3. Flyway가 `V1__init.sql`을 적용해 4개 테이블 + 7개 인덱스 생성.

**확인** (앱 로그):
```
Migrating schema "public" to version "1 - init"
Started DemoApplicationKt in X seconds
```
이후:
- 앱: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger.html

### Step 3 — REST 시나리오

새 터미널을 열고 진행. `$BASE`는 앱 주소.
```bash
BASE=http://localhost:8080
```

#### 3-1. 세션 생성
```bash
SID=$(curl -sf -X POST $BASE/sessions | jq -r .id)
echo $SID
```
**기대**: `c23d692a-...` 형태의 UUID 1줄.

#### 3-2. 양쪽 참여 (1:1)
```bash
curl -sf -X POST $BASE/sessions/$SID/join \
  -H 'content-type: application/json' -d '{"userId":"alice"}' -w 'HTTP %{http_code}\n'
curl -sf -X POST $BASE/sessions/$SID/join \
  -H 'content-type: application/json' -d '{"userId":"bob"}' -w 'HTTP %{http_code}\n'
```
**기대**: 각각 `HTTP 204`.

3번째 참여자는 거절되어야 함:
```bash
curl -s -X POST $BASE/sessions/$SID/join \
  -H 'content-type: application/json' -d '{"userId":"carol"}' -w '\nHTTP %{http_code}\n'
```
**기대**: `{"error":"conflict","message":"session is full (1:1)"}` + `HTTP 409`.

#### 3-3. 메시지 전송
```bash
curl -sf -X POST $BASE/sessions/$SID/events \
  -H 'content-type: application/json' \
  -d '{"clientEventId":"cli-1","type":"MESSAGE","actorUserId":"alice","payload":{"messageId":"m1","text":"안녕"}}' \
  | jq
```
**기대**:
```json
{
  "serverSeq": 3,
  "wasInserted": true,
  "clientEventId": "cli-1",
  ...
}
```
> `serverSeq=3`인 이유: JOIN 2건이 seq 1, 2를 차지.

#### 3-4. 멱등성 검증 — 같은 키로 재전송
```bash
curl -sf -X POST $BASE/sessions/$SID/events \
  -H 'content-type: application/json' \
  -d '{"clientEventId":"cli-1","type":"MESSAGE","actorUserId":"alice","payload":{"messageId":"m1","text":"안녕"}}' \
  | jq
```
**기대**:
```json
{
  "serverSeq": 3,           ← 같은 serverSeq
  "wasInserted": false,     ← 멱등 hit
  ...
}
```
> **핵심**: 새 행이 만들어지지 않고 같은 위치 정보가 반환됨. DB에 row가 늘지 않음.

#### 3-5. 시점 복원 (현재)
```bash
curl -sf "$BASE/sessions/$SID/timeline" | jq
```
**기대**: 참여자 2명(alice, bob) + 메시지 1건(m1, SENT).

#### 3-6. EDIT/DELETE 후 시점 복원 비교
```bash
T_MID=$(date -u +%Y-%m-%dT%H:%M:%S.999Z)
sleep 1
curl -sf -X POST $BASE/sessions/$SID/events -H 'content-type: application/json' \
  -d '{"clientEventId":"cli-2","type":"MESSAGE_EDIT","actorUserId":"alice","payload":{"messageId":"m1","text":"안녕 (수정됨)"}}' >/dev/null

# 현재 → status=EDITED, text="안녕 (수정됨)"
curl -sf "$BASE/sessions/$SID/timeline" | jq '.messages[0]'

# 과거 시점 t_mid → 수정 적용 전 (status=SENT, text="안녕")
curl -sf "$BASE/sessions/$SID/timeline?at=$T_MID" | jq '.messages[0]'
```
**핵심**: 같은 세션을 두 시점으로 보면 다른 상태가 반환됨 → 이벤트 기반 시점 복원이 동작.

#### 3-7. 이벤트 히스토리 (디버깅)
```bash
curl -sf "$BASE/sessions/$SID/events?from=0" | jq
```
**기대**: server_seq 오름차순 이벤트 목록 (JOIN×2, MESSAGE, MESSAGE_EDIT).

### Step 4 — WebSocket 브로드캐스트 (선택)

`./gradlew test`의 `WebSocketIntegrationTest`가 이미 자동 검증하지만, 수동으로 확인하려면:

```bash
cd /tmp && mkdir -p ws-check && cd ws-check
npm init -y >/dev/null && npm install @stomp/stompjs ws sockjs-client >/dev/null
```

`check.mjs` 작성 (대략):
```javascript
import { Client } from '@stomp/stompjs';
import WebSocket from 'ws';

const SID = '여기에 Step 3-1에서 만든 sessionId';
const client = new Client({
  webSocketFactory: () => new WebSocket('ws://localhost:8080/ws'),
  onConnect: () => {
    client.subscribe(`/topic/sessions/${SID}`, m => console.log('수신:', m.body));
  },
});
client.activate();
```

```bash
node check.mjs &
# 다른 터미널에서 메시지 publish하면 "수신: {...}" 가 출력됨
```

### Step 5 — 정리
```bash
docker compose down
```

### (선택) 스키마/데이터 초기화 (재실행 시)
```bash
docker exec chat-postgres psql -U chat -d chat -c \
  "DROP SCHEMA public CASCADE; CREATE SCHEMA public; GRANT ALL ON SCHEMA public TO chat;"
```

---

## 디렉토리 구조
```
demo/
├── README.md                      ← 이 파일
├── openapi.yaml                   ← API 명세 (OpenAPI 3.1)
├── docker-compose.yml             ← Postgres
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

## 가정 (Assumptions)

- **인증/인가**: REST 본문/STOMP 메시지의 `userId`를 신뢰. 실 운영에선 JWT 등으로 검증.
- **1:1 정원**: 세션 당 최대 2명. 세 번째 다른 userId의 join은 409.
- **`clientEventId` 발급 책임**: 클라이언트. 누락 시 멱등성 보장 X (REST `/events`는 400, `/join|leave|end`는 서버가 UUID로 발급).
- **단일 인스턴스 가정** (MVP). 다중 인스턴스 확장은 [docs/design.md §2](docs/design.md) 참조.
- **시간 신뢰**: 서버 시계가 SoT. NTP 동기화는 운영 기본.

---

## 알려진 동작

- **`BIGSERIAL` 결번**: ON CONFLICT DO NOTHING 시에도 sequence가 consume됨 (Postgres 정상 동작). 단조성은 유지되므로 순서 판정엔 영향 없음.
