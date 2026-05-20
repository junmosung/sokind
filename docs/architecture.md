# 아키텍처 노트

## 현재 구조: 단순 레이어드 (Layered)

```
src/main/kotlin/me/victor/demo/
├── DemoApplication.kt
├── common/        ← 예외 + ControllerAdvice
├── domain/        ← 도메인 모델 + Service
│   ├── session/        (Session, SessionStatus, SessionService)
│   ├── participant/    (Participant)
│   ├── event/          (ChatEvent, ChatEventType, EventService, AppendCommand, EventAppended)
│   ├── timeline/       (Timeline, *Snapshot, MessageStatus, SnapshotState, TimelineService)
│   └── snapshot/       (SnapshotService, SnapshotTrigger)
├── api/           ← 외부 어댑터
│   ├── rest/           (3 Controller + 요청/응답 DTO)
│   └── ws/             (STOMP Config / Controller / Broadcaster + DTO)
└── infra/         ← 인프라 어댑터
    ├── persistence/    (jOOQ Repository 4종 + AppendResult, StoredSnapshot)
    └── json/           (JsonbCodec)
```

### 의존 방향

```
        ┌───── api ─────┐
        ▼               ▼
common ◄── domain ◄── infra
              ▲          │
              └──────────┘
              (domain의 모델 타입을
               infra가 import해서 매핑)
```

- **api → domain**: Controller가 도메인 Service 호출.
- **api → common**: 예외 타입.
- **domain → infra**: Service가 Repository를 직접 import (= 순수 헥사고날 아님).
- **infra → domain**: Repository가 domain 모델 타입(Session, ChatEvent 등)을 반환·인자로 사용.
- 컴파일/런타임 사이클 없음 (서로 다른 종류의 import 방향).

### 장점
- 디렉토리 이름만으로 외부/코어/인프라 즉시 파악.
- 인터페이스 추가 비용 0.
- Spring DI 그대로 동작.

### 한계
- domain이 infra의 jOOQ Repository를 직접 알기 때문에 단위 테스트 시 DSLContext 필요 (또는 mock).
- DB 교체 시 도메인 코드 영향 (현실적으론 잘 일어나지 않지만).

---

## 헥사고날 전면 적용 시 (참고)

```
domain/                              ← Spring/jOOQ 의존 0
├── session/{Session, SessionStatus}.kt
├── event/{ChatEvent, ChatEventType}.kt
├── timeline/{Timeline, *Snapshot, SnapshotState}.kt
└── snapshot/StoredSnapshot.kt

application/
├── port/
│   ├── inbound/                    ← use case 인터페이스
│   │   ├── AppendEventUseCase.kt   (operate: AppendCommand → AppendResult)
│   │   ├── RestoreTimelineUseCase.kt
│   │   ├── JoinSessionUseCase.kt
│   │   └── ...
│   └── outbound/                   ← 도메인이 인프라에 요구하는 능력
│       ├── EventStore.kt           (append, findBySession, maxSeqAt)
│       ├── SessionStore.kt
│       ├── ParticipantStore.kt
│       ├── SnapshotStore.kt
│       ├── DomainEventPublisher.kt
│       └── ChatBroadcaster.kt
└── service/                        ← use case 구현
    ├── EventAppendService.kt       implements AppendEventUseCase
    ├── TimelineRestoreService.kt
    └── SnapshotMaintenanceService.kt

adapter/
├── inbound/
│   ├── rest/                       ← controller (use case 호출만)
│   ├── ws/                         ← STOMP controller
│   └── dto/                        ← 요청/응답 DTO + mapper
└── outbound/
    ├── jooq/                       ← outbound port의 jOOQ 구현
    │   ├── JooqEventStore.kt       implements EventStore
    │   └── ...
    ├── stomp/StompChatBroadcaster.kt
    └── spring/SpringDomainEventPublisher.kt

config/                             ← @SpringBootApplication, @EnableAsync, WebSocketConfig
```

### 얻는 것
- 도메인 단위 테스트가 Spring 없이 가능 — `EventAppendService(fakeEventStore, fakePublisher)`.
- 인프라 교체 자유도 — outbound port 구현만 갈아끼우면 됨.
- 의존이 항상 `adapter → application → domain` 일방향.

### 비용 (이번 도메인엔 over-engineering)
- use case마다 인터페이스 + 구현 → 클래스 수 약 2배.
- DTO ↔ 도메인 매퍼 추가.
- use case 5~6개 규모라 비용 대비 효용 모호.

### 부분 적용 전략 (추천)
- 새 use case부터 위 구조로 시작.
- 기존 코드는 layered 유지 → 변경 비용 0.
- 둘이 공존해도 무방 (Spring DI가 양쪽 처리).
