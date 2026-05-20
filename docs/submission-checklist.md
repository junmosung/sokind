# 📋 제출물 체크리스트 점검 결과

과제 §6 제출물 체크리스트의 각 항목이 어디에 어떻게 충족되었는지 한눈에 정리.

## 한 줄 요약
**필수 6/6 + 가산점 4 모두 충족.** `./gradlew test` 한 번이면 시나리오 11개 자동 검증.

---

## 필수 항목 (6/6)

| # | 항목 | 상태 | 핵심 산출물 |
|---|---|---|---|
| 1 | README — 실행/환경/의사결정 | ✅ | [`README.md`](../README.md) |
| 2 | API 명세 (OpenAPI) | ✅ | [`openapi.yaml`](../openapi.yaml) + [Swagger UI](http://localhost:8080/swagger.html) |
| 3 | ERD + 핵심 DDL | ✅ | [`docs/db.md`](db.md) + [`V1__init.sql`](../src/main/resources/db/migration/V1__init.sql) |
| 4 | 주요 쿼리 + 인덱스 + 병목 | ✅ | [`docs/queries.md`](queries.md) — 핫패스 3개 |
| 5 | 설계 5종 (재연결/중복/확장/관측/장애) | ✅ | [`docs/design.md`](design.md) + [`docs/event-sourcing.md`](event-sourcing.md) |
| 6 | 이벤트 기반 상태 복원 | ✅ | [`docs/event-sourcing.md`](event-sourcing.md) + [`TimelineService.kt`](../src/main/kotlin/me/victor/demo/domain/timeline/TimelineService.kt) |

---

### ① README — 실행 방법, 환경 구성, 주요 의사결정 요약
[`README.md`](../README.md) (226줄)

| 요구사항 | 위치 |
|---|---|
| 실행 방법 | `## 실행` — Postgres 기동 → 앱 기동 → Swagger UI → 통합 테스트 → 초기화 5단계 |
| 환경 구성 | 사전 요구사항 (Docker + JDK 24), `docker-compose.yml` |
| 주요 의사결정 요약 | `## 주요 의사결정 요약` — STOMP/jOOQ/순서 기준/중복 위치/복원 방식/EDIT·DELETE 머지/Broadcast 결합도/인증 |
| 가정 명시 | `## 가정 (Assumptions)` |
| 부채 명시 | `## 알려진 동작 / 부채` |
| 평가 매핑 | `## 평가 항목 ↔ 결과물 매핑` |

---

### ② API 명세 (OpenAPI)
[`openapi.yaml`](../openapi.yaml) (389줄, OpenAPI 3.1)

- 모든 REST 엔드포인트: `/sessions`, `/sessions/{id}/{join|leave|end|participants|events|timeline}`, `/ws`
- 요청/응답 스키마: Session / Participant / ChatEvent / Timeline / Snapshot 도메인 모델
- 멱등성 계약(`wasInserted`) + 에러 본문(`ApiError`) 정의
- Swagger UI: `http://localhost:8080/swagger.html` — `openapi.yaml`을 SoT로 두고 빌드 시 static으로 복사

---

### ③ ERD + 핵심 DDL
[`docs/db.md`](db.md) (105줄) + [`V1__init.sql`](../src/main/resources/db/migration/V1__init.sql) (88줄)

| 항목 | 위치 |
|---|---|
| ERD | mermaid `erDiagram` — sessions/participants/events/snapshots 관계 + 모든 컬럼 |
| 핵심 DDL | 4개 테이블 + 7개 인덱스 + CHECK 제약 (모두 V1__init.sql) |
| 테이블별 결정 근거 | `## 테이블별 결정 근거` — events SoT / sessions·participants 프로젝션 / snapshots 최적화 |
| 정규화 정책 | `## 정규화 정책` — JSONB + 캐시 컬럼 트레이드오프 |
| ORM 정책 (jOOQ) | `## ORM 정책 (jOOQ)` — 컴파일 타임 안전 + Flyway introspection |

---

### ④ 주요 쿼리 + 인덱스 근거 + 병목 설명
[`docs/queries.md`](queries.md) (127줄) — 핫패스 3개

| 쿼리 | SQL 명시 | 인덱스 동작 | 병목 시나리오 |
|---|---|---|---|
| **쿼리 1** — 시점 t 타임라인 복원 (snapshot+델타) | ✅ 3단계 SQL | `idx_events_session_server_ts` + `idx_snapshots_session_seq_desc` + `idx_events_session_seq` | 메시지 크기 / 캐시 / 인덱스 비대화 / snapshot 누락 (4) |
| **쿼리 2** — 이벤트 append (멱등) | ✅ `INSERT … ON CONFLICT DO NOTHING RETURNING` | `uq_events_session_client_event_id` UNIQUE | hot row / 풀 고갈 / 시퀀스 컨텐션 / 재전송 폭주 (4) |
| **쿼리 3** — 재연결 catch-up | ✅ `WHERE server_seq > ? ORDER BY seq ASC LIMIT 500` | `idx_events_session_seq` range scan | 오래된 클라 / thundering herd / 페이지 중 새 이벤트 (3) |

**보너스**: 실제 트랜잭션 경계 + AFTER_COMMIT fan-out 다이어그램 포함.

---

### ⑤ 설계 문서 (재연결/중복/확장/관측/장애)
[`docs/design.md`](design.md) (173줄) + [`docs/event-sourcing.md`](event-sourcing.md) (102줄)

| 영역 | 위치 |
|---|---|
| **재연결** 정합성 | `design.md §1` + `event-sourcing.md` "재연결 정합성 흐름" |
| **중복 처리** | `event-sourcing.md` "중복 이벤트 처리 (4중 방어)" — DB / Repository / Service / Broadcast |
| **수평 확장** | `design.md §2` — sticky session / Redis·Rabbit relay / partition / replica |
| **관측 가능성** | `design.md §3` — 구조화 로그 / Prometheus 메트릭 7개 / OpenTelemetry trace 경로 / 대시보드 |
| **비동기 처리** (Outbox/DLQ/Idempotency) | `design.md §4` — 현재 구현(`@TransactionalEventListener` + `@Async`) + Outbox 청사진 |
| **장애 시나리오 3종** | `design.md §5` — A) 인스턴스 다운, B) DB 장애 (커넥션 고갈/락 경합/노드 다운), C) 정합성 (중복/부분 실패/broadcast 누락). 각각 감지 → 완화 → 복구 |

---

### ⑥ 이벤트 기반 상태 복원
[`docs/event-sourcing.md`](event-sourcing.md) (설계) + 실제 구현

| 항목 | 위치 |
|---|---|
| 복원 설계 (풀 리플레이 / snapshot+델타) | `event-sourcing.md` "복원 전략" — 단계별 의사 코드 |
| 중복/순서 처리 명시 | `event-sourcing.md` "4중 방어" + "순서 뒤바뀜 처리" (first-DELETE-wins 등) |
| **구현 결과** | [`TimelineService.kt`](../src/main/kotlin/me/victor/demo/domain/timeline/TimelineService.kt) + `GET /sessions/{id}/timeline?at=` |
| 비용 분석 | "O(N % snapshot_interval), interval=50 → 평균 ~25 이벤트만 리플레이" |

---

## 가산점 항목 (4 추가 구현)

| # | 항목 | 위치 |
|---|---|---|
| 1 | **Snapshot 자동 생성** (트리거 + 결정론 검증) | [`SnapshotService.kt`](../src/main/kotlin/me/victor/demo/domain/snapshot/SnapshotService.kt) + [`SnapshotTrigger.kt`](../src/main/kotlin/me/victor/demo/domain/snapshot/SnapshotTrigger.kt) (`@Async + AFTER_COMMIT`) |
| 2 | **Projection 비동기 분리** | [`ChatBroadcaster.kt`](../src/main/kotlin/me/victor/demo/api/ws/ChatBroadcaster.kt) (`@TransactionalEventListener(AFTER_COMMIT)`) — 메인 트랜잭션과 격리 |
| 3 | **헥사고날 아키텍처 청사진** | [`docs/architecture.md`](architecture.md) — 현재 layered + 전면 적용 시 ports & adapters |
| 4 | **Testcontainers 통합 테스트** | [`src/test/kotlin/.../it/`](../src/test/kotlin/me/victor/demo/it/) — 11/11 자동 검증 |

---

## 검증 방법

### 빠른 검증 (5초)
```bash
./gradlew test
```
Postgres 컨테이너 자동 기동 → Flyway 적용 → 11개 시나리오 자동 검증.

### 시나리오 매트릭스
| 테스트 파일 | 검증 내용 | 테스트 수 |
|---|---|---|
| [`ChatIntegrationTest`](../src/test/kotlin/me/victor/demo/it/ChatIntegrationTest.kt) | 세션 라이프사이클, 1:1 정원, ENDED 거절, 멱등성, 순서 결정성, 시점 복원(EDIT/DELETE) | 5 |
| [`SnapshotIntegrationTest`](../src/test/kotlin/me/victor/demo/it/SnapshotIntegrationTest.kt) | interval 도달 시 자동 생성, snapshot+델타 == 풀 리플레이 결정론 | 2 |
| [`WebSocketIntegrationTest`](../src/test/kotlin/me/victor/demo/it/WebSocketIntegrationTest.kt) | STOMP 구독 + REST publish 브로드캐스트, 멱등 hit 미전파, serverSeq 단조성 | 3 |
| `DemoApplicationTests` | 컨텍스트 로드 | 1 |
| **합계** | | **11** |

### 수동 검증 (선택)
```bash
docker compose up -d postgres
./gradlew bootRun
# 다른 터미널에서
curl -X POST http://localhost:8080/sessions
```
자세한 curl 시나리오는 [`README.md`](../README.md) "동작 검증" 섹션 참조.

---

## 비목표 (과제가 명시한 non-goals)

명시적으로 다음은 구현 범위에서 제외:
- 프론트엔드 / 웹 / 모바일 UI
- 운영 배포 자동화 / CI/CD
- 인증·인가의 완결된 구현 (현재는 본문 `userId` 신뢰, README "가정"에 명시)
- 완전한 제품 수준 UX / 관리 도구

## 미구현 (의식적 선택)

| 항목 | 사유 |
|---|---|
| k6 부하 테스트 | 가산점 영역 — 환경 측정값이 환경 의존이라 큰 가치 없음 |
| Outbox 워커 | 청사진만 (`design.md §4`) — 외부 publish 대상이 없는 MVP라 over-engineering |
| 다중 인스턴스 broadcast (Redis/Rabbit relay) | 청사진만 (`design.md §2`) — MVP 단일 인스턴스에선 SimpleBroker로 충분 |
| 헥사고날 전면 적용 | 청사진만 (`docs/architecture.md`) — 도메인 크기 대비 over-engineering, 부분 적용 전략 문서화 |
