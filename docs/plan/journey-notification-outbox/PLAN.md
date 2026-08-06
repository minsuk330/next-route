# journey-notification-outbox — 실행 플랜

- 티어: **L** (codex 논의 후 새 세션에서 구현)
- 브랜치: `feat/journey-notification-outbox` (fresh `origin/main` = 4c64de1 기준)
- 상위: [Phase 5 인덱스](../journey-navigation/PHASE-5.md) §1 ②
- 선행: PR ① `journey-push-token` (머지됨, PR #122, V39)
- 마이그레이션: **V40** `journey_notification`
- 근거: `journey-navigation/PLAN.md` §4.5(178행), `REQUIREMENTS.md` §4.5, `PHASE-5.md` §1 ②·§3,
  `TECHNICAL_DECISIONS.md` §2
- codex 설계 리뷰 반영: 2026-08-06 (§13 에 반영 내역)

> 이 문서는 컨텍스트 없는 세션이 읽고 바로 구현할 수 있도록 쓴다. 사람 검토용 요약은
> [SUMMARY.md](./SUMMARY.md).

---

## 0. 이 PR 이 하는 일

환승 상태가 나빠진 순간에 알림 한 건을 표에 쌓고, 별도 루프가 그것을 꺼내 보내는 구조를 만든다.
실제 발송 코드(FCM)는 PR ③ 이고, 이번에는 **아무것도 안 보내는 가짜 sender** 를 끼운다. 그래도
"위험 감지 → 큐에 쌓임 → 꺼내짐 → 보냄 처리" 전 구간이 돌아 IT 로 증명할 수 있다.

Phase 4 로 서버는 사용자가 화면을 안 봐도 "이 환승 놓칠 것 같다"를 안다. 그런데 그 판단이
`transfer_connection` 행에만 적히고 아무 데도 안 나간다.

바로 안 보내고 표를 거치는 이유: 평가기는 최소 10초마다 다시 계산하고 그 계산은 DB 트랜잭션 안에서
돈다. 그 안에서 FCM 을 부르면 응답을 기다리는 동안 DB 커넥션을 붙잡는다(Phase 3 에서 겪은 문제).
계산은 "이런 일이 생겼다"만 적고 발송은 딴 스레드가 맡는다.

### 용어

| 말 | 뜻 |
|---|---|
| outbox | 보낼 알림을 쌓아 두는 표. 여기서는 `journey_notification` |
| relay | 그 표에서 꺼내 실제로 보내는 루프 |
| claim | 여러 서버가 같은 알림을 두 번 보내지 않게 한 행을 "내가 처리 중"으로 찍고 가져가는 것 |
| 환승 상태 4단계 | 여유 COMFORTABLE(5분 초과) / 탈 수 있음 CATCHABLE(2~5분) / 놓칠 듯 AT_RISK(0~2분) / 놓침 MISSED(0 미만) |
| `logicalCheckpointKey` | `journeyId:routeRevision:scopeType:scopeId:checkpoint`. **사용자에게 두 번 보이지 않게** |
| `deliveryKey` | 위 키 + `:channel`. **한 채널로 두 번 보내지 않게** |

### 범위 밖 (다른 PR)

| 항목 | 담당 |
|---|---|
| FCM 발송 구현·채널 라우터·token `UNREGISTERED` 처리 | ③ |
| N1·N3(정류장 접근·하차 임박) 서버 발송 금지, 체크포인트 종류 확장, 정책 클래스 추출 | ④ |
| lease 만료·`commandVersion`·`evaluationVersion` 발송 직전 재검증 | ⑤ |
| payload 계약·deep link | ⑥ |
| alert-to-action 지표 | ⑦ |

---

## 1. 확정 설계 결정 (재논의 금지 — codex 쟁점은 §11)

| 항목 | 결정 | 이유 |
|---|---|---|
| ②의 범위 | 표·상태기계·enqueue(평가기 연결)·취소·**relay 까지 전부**. sender 는 포트 + no-op | 끝에서 끝까지 도는 슬라이스여야 IT 로 증명된다 |
| 알림 생성 규칙 | 환승 상태가 **나빠질 때만**. 마커는 기존 미사용 컬럼 `transfer_connection.last_notified_status`(V35:144) | ④ 가 이 자리를 정책 클래스로 뽑아 확장 |
| enqueue 위치 | 평가기 쓰기 트랜잭션(`JourneyEvaluationScheduleWriter.finish`) 안, 소유권 확인(CAS) **뒤** | 마커와 outbox 행이 함께 커밋돼야 한다. 순서가 claim 뺏김 안전성을 만든다 |
| ON CONFLICT 대상 | `delivery_key` | 채널마다 행 하나. logical 로 걸면 두 번째 채널이 조용히 사라진다 |
| unique 인덱스 | `delivery_key` **전체 unique**(부분 인덱스 아님) | 이 표의 행은 **절대 삭제하지 않는다**(§7 보관 규칙). 부분 인덱스면 삭제 순간 키가 열려 재발송 |
| 채널 값 | `TOSS` / **`PUSH`** | `TECHNICAL_DECISIONS.md:65` — DB channel 은 `PUSH`, `FCM` 은 **provider** 이고 그것은 `user_push_token.provider`(V39)가 이미 갖는다 |
| 채널 결정 | ② 가 포트 + 항상 `[PUSH]` 기본 구현. ③ 이 `@ConditionalOnProperty` 로 교체 | ③ 이 ② 파일을 안 고친다 |
| 채널 결정 시점 | 평가 트랜잭션 **밖** | ③ 구현이 DB 를 읽어도 평가 트랜잭션을 오염시키지 않는다 |
| 채널 전달 방법 | 별도 `NotificationContext` 를 `finish()` 인자로 | `JourneyEvaluationOutcome` 을 **안 건드린다**. 발송 경로 결정이 계산 DTO 에 섞이지 않게 |
| 마커 대상 엔티티 획득 | 알림 쪽에서 **다시 조회** | 같은 트랜잭션이라 1차 캐시가 같은 인스턴스를 준다(원자성 동일). `TransferAssessmentWriter` 시그니처를 안 바꿔 조회 경로(`assessAndStore`) 위험을 만들지 않는다 |
| relay | 버스 것을 **복제**. 공유 추상화 안 만듦 | 일반화하면 살아 있는 토스 경로 파일을 고치게 된다 |
| `RelayWaker` | 별도 빈 신설 | 싱글턴 공유 시 여정 초당 200건이 버스 루프를 그만큼 깨운다. `extends` 는 타입 중복으로 부팅 실패 |
| 도메인 시각 | `timestamptz` / Java `Instant` | journey 도메인 전체가 그렇다(V35). 섞으면 KST/UTC 어긋남이 조용히 들어온다 |
| payload | `jsonb` | journey 표 관례. ⑥ 이 정할 계약이 구조체 |
| 버전 컬럼 | `command_version`·`evaluation_version` 을 **V40 에 지금 넣는다** | ⑤ 가 발송 직전 재검증에 쓰고 ⑥ 이 payload 에 싣는데(`TECHNICAL_DECISIONS:68`), 두 PR 다 마이그레이션이 없다 |
| 발송 보장 | **최소 한 번(at-least-once)**. 정확히 한 번이 아니다 | 발송이 트랜잭션 밖이라 보낸 직후 죽으면 회수 후 재발송된다. ⑥ 이 payload 에 상태를 안 담아 중복이 무해하다 |

---

## 2. 반드시 지켜야 할 여섯 가지 (안 지키면 조용히 망가짐)

### 2.1 `checkpoint` 값이 상태를 품어야 한다

`TRANSFER_RISK` 하나로 두면 `AT_RISK → MISSED` 승격이 **같은 `deliveryKey`** 를 만들어
`ON CONFLICT` 에 삼켜진다. 가장 중요한 알림이 영영 안 나간다.

값은 상태별로 나눈다: `TRANSFER_RISK_AT_RISK`, `TRANSFER_RISK_MISSED`.
전용 IT 로 못박는다(§9 `JourneyNotificationEnqueueIT`).

### 2.2 `expires_at` 에 최소 유효시간 바닥(180초)

MISSED 는 여유가 음수라는 뜻 = 다음 버스 도착 시각이 **이미 과거**. 그 값을 그대로 만료로 쓰면
삽입 순간 이미 만료라 relay 가 첫 claim 에서 취소한다. 가장 중요한 알림이 사라진다.

```
expiresAt = clamp(nextBusArrivalAt ?: now + defaultTtl, now + minTtl, now + maxTtl)
minTtl = 180s, defaultTtl = 10m, maxTtl = 30m
```

**바닥을 180초로 잡은 이유**(codex 지적). 재시도가 만료 안에서 끝나야 한다. 백오프 60초 ×
최대 3회 = 180초이므로 `minTtl` 이 60초면 만료 바닥에 걸린 행은 재시도를 **한 번도** 못 한다.
설정 검증 규칙: `backoffSeconds * maxAttempts <= minTtlSeconds`.

연계: `markTransientFailure` 는 재시도 예정 시각이 `expiresAt` 을 넘으면 재시도 대신 CANCELED.

### 2.3 낡은 알림은 새 알림이 밀어낸다 (`supersede`)

`AT_RISK` 와 `MISSED` 는 **별개 행**이라 순서가 뒤집힐 수 있다. `AT_RISK` 행이 재시도로 밀리면
나중에 만든 `MISSED` 보다 **뒤에** 나간다 — 사용자가 "놓쳤다" 다음에 "놓칠 것 같다"를 본다.

같은 이유로 상태가 좋아져도(`AT_RISK → CATCHABLE`) 이미 쌓인 `AT_RISK` 가 최대 30분 동안 발송
대상으로 남는다.

**규칙**: 같은 대상(`journeyId`+`routeRevision`+`scopeType`+`scopeId`)의 PENDING 행 중 방금
만든 것이 아닌 것을 CANCELED 로 만든다. 두 자리에서 부른다.

| 언제 | 무엇 |
|---|---|
| 새 알림을 enqueue 한 직후 | 그 대상의 다른 PENDING 을 취소(`exceptDeliveryKey`) |
| 상태가 알릴 값이 아닌데 마커에는 알린 기록이 있을 때 | 그 대상의 PENDING 전부 취소 + 마커 비움 |

PROCESSING 은 못 막는다 — `loadTask` 재확인이 안전망(§5.7).

### 2.4 채널 목록이 비면 마커를 쓰지 않는다

포트 계약은 "null 도 빈 목록도 아님"이지만 방어한다. 비었는데 마커를 쓰면 그 환승은 **영영**
알림이 없다. 코드는 `enqueue 를 한 번이라도 시도했을 때만` 마커를 쓴다.

### 2.5 `relay.enabled=true` + `sender=noop` 은 부팅에서 막는다

진짜 알림 행을 SENT 로 소모해 나중에 진짜 발송을 막는다. `@PostConstruct` 에서 거절한다
(`test` 프로필 제외).

### 2.6 sender 가 던지는 모든 예외를 삼킨다

`NotificationSendException` 이 아닌 예외(NPE, 타임아웃, SDK 내부 오류)를 안 잡으면 행이
PROCESSING 에 남아 회수 → 재시도 → 또 예외를 **영원히** 반복한다. relay 는 `Throwable` 까지
잡아 transient 로 분류한다. ③ 은 sender 타임아웃을 `claimTimeoutMinutes` 보다 짧게 둘 의무가 있다.

---

## 3. 작업 순서 (체크박스, 의존성 명시)

### Step 1 — 마이그레이션
- [ ] `src/main/resources/db/migration/V40__create_journey_notification.sql` (§4)
- [ ] `.gitignore` 27행 뒤 `!src/main/resources/db/migration/V40__create_journey_notification.sql`
- [ ] `src/test/java/.../domain/journey/JourneySchemaIT.java` `@Sql` 배열에 V40 추가
- [ ] 검증: `git check-ignore -v src/main/resources/db/migration/V40__*.sql` 이 **아무것도 출력 안 함**
- [ ] 검증: `./gradlew test --tests '*JourneySchemaIT'`

### Step 2 — 도메인 (의존: Step 1)
- [ ] `domain/journey/entity/JourneyNotificationStatus.java`
- [ ] `domain/journey/entity/JourneyNotificationScope.java`
- [ ] `domain/journey/entity/JourneyCheckpoint.java`
- [ ] `domain/notification/entity/NotificationChannel.java`
- [ ] `domain/journey/entity/JourneyNotification.java`
- [ ] `src/test/java/.../domain/journey/JourneyNotificationSchemaIT.java`
- [ ] 검증: `./gradlew test --tests '*JourneyNotificationSchemaIT'`

### Step 3 — 레포지토리 (의존: Step 2)
- [ ] `domain/journey/repository/JourneyNotificationRepository.java` — `enqueue`,
      `claimBatchForProcessing`, `reclaimStale`, `cancelPendingByJourneyId`,
      `cancelPendingByJourneyIdIn`, **`supersedePendingByScope`**(§2.3)
- [ ] 검증: `./gradlew test --tests '*JourneyNotificationSchemaIT'` (레포지토리 케이스 추가)

### Step 4 — 마커 배선 (의존: Step 2, Step 3 과 병렬 가능)
- [ ] `domain/journey/entity/TransferStatus.java` — `public int severity()` 추가
- [ ] `application/journey/service/TransferRiskPolicy.java` — private `severity()` 를 enum 에 위임
- [ ] `domain/journey/entity/TransferConnection.java` — `markNotified(String)`, `clearNotified()`
- [ ] 검증: `./gradlew test --tests '*TransferRiskPolicyTest' --tests '*Journey*AssessmentIT'`

> `TransferAssessmentWriter` 는 **안 고친다**(codex 리뷰로 바뀐 결정). 관리 중인 엔티티를
> 내보내면 조회 경로(`TransferRiskEvaluator:103` → `assessAndStore`)에서 트랜잭션이 끝난 뒤
> 그 엔티티를 고쳤을 때 마커가 조용히 사라진다. 알림 쪽에서 다시 조회한다(§5.5 성질 5).

### Step 4.5 — 만료 배치 트랜잭션 수정 (**선행 필수**, 의존: 없음)
- [ ] `application/journey/service/JourneyLeaseExpiryService.java` — `sweep()` 이
      `TransactionTemplate` 으로 배치 하나를 감싼다(§6 ⚠)
- [ ] `sweep()` 을 직접 도는 IT 추가
- [ ] 검증: `./gradlew test --tests '*JourneyLeaseExpiry*'`

> **왜 선행인가.** 지금 `sweep():46` 이 같은 클래스의 `expireBatch()` 를 직접 부르는데 Spring
> `@Transactional` 은 프록시 기반이라 자기 호출에는 **안 걸린다**. 즉 운영에서 만료 배치는
> 트랜잭션 밖이다. SQL 한 문장이라 지금까지 우연히 안전했을 뿐, Step 7 에서 취소 쿼리를 더하면
> 두 문장이 따로 커밋돼 "만료됐는데 알림은 살아 있는" 상태가 생긴다. 기존 IT 는 `expireBatch()`
> 를 직접 불러 프록시를 타므로 이 문제를 못 잡는다.

### Step 5 — 포트·no-op 구현 (의존: Step 2)
- [ ] `application/journey/port/out/NotificationSender.java`
- [ ] `application/journey/port/out/NotificationChannelResolver.java`
- [ ] `application/journey/dto/NotificationSendCommand.java`
- [ ] `application/journey/exception/NotificationSendException.java`
- [ ] `infrastructure/adapter/out/notification/NoOpNotificationSender.java`
- [ ] `infrastructure/adapter/out/notification/FixedNotificationChannelResolver.java`
- [ ] `application/journey/config/JourneyNotificationProperties.java`
- [ ] `src/main/resources/application.yaml` — `journey.notification` 블록
- [ ] 검증: `./gradlew compileJava`

### Step 6 — enqueue + 알림 판단 (의존: Step 3·4·5)
- [ ] `application/journey/service/JourneyNotificationKeys.java`
- [ ] `application/journey/service/JourneyNotificationEnqueuer.java`
- [ ] `application/journey/service/TransferRiskNotifier.java`
- [ ] `application/journey/service/JourneyOutboxEnqueuedEvent.java`
- [ ] `application/journey/dto/NotificationContext.java` — **신규**(`JourneyEvaluationOutcome` 은
      안 건드린다)
- [ ] `application/journey/service/JourneyEvaluationService.java` — 채널 결정(**트랜잭션 밖**) →
      `NotificationContext` 조립 → `finish()` 에 전달
- [ ] `application/journey/service/JourneyEvaluationScheduleWriter.java` — 인자 추가 + notifier 호출
- [ ] 검증: `./gradlew test --tests '*TransferRiskNotifierTest' --tests '*JourneyNotificationKeysTest' --tests '*JourneyNotificationEnqueueIT' --tests '*JourneyNotificationLostClaimIT' --tests '*JourneyNotificationSupersedeIT'`

### Step 7 — 여정 종료 시 취소 (의존: Step 3, **Step 4.5**)
- [ ] `application/journey/service/JourneyCommandService.java` (`:137` 뒤)
- [ ] `application/journey/service/JourneyStartTxService.java` (`:120` 뒤)
- [ ] `application/journey/service/JourneyLeaseExpiryService.java` (`expireBatch()` 안,
      `claimExpiredBatch` 결과로 `cancelPendingByJourneyIdIn`)
- [ ] 검증: `./gradlew test --tests '*JourneyNotificationCancelOnEndIT' --tests '*JourneyLeaseExpiry*' --tests '*JourneyCommand*' --tests '*JourneyStart*'`

### Step 8 — relay (의존: Step 3·5)
- [ ] `application/journey/service/JourneyNotificationOutboxStateService.java`
- [ ] `application/journey/service/JourneyNotificationRelayService.java`
- [ ] `application/journey/service/JourneyRelayWaker.java`
- [ ] `application/journey/service/JourneyRelayWakeListener.java`
- [ ] `infrastructure/adapter/in/scheduler/journey/JourneyNotificationRelayLoop.java`
- [ ] 검증: `./gradlew test --tests '*JourneyNotificationRelayIT' --tests '*JourneyNotificationRelayWakeupIT' --tests '*JourneyNotificationAtomicClaimIT'`

### Step 9 — 지표 (의존: Step 6·8)
- [ ] `application/journey/observability/JourneyNotificationMetrics.java`
- [ ] 검증: `./gradlew test --tests '*JourneyNotification*'`

### Step 10 — 마무리
- [ ] 전체 `./gradlew test`
- [ ] 버스 경로 회귀 `./gradlew test --tests 'watoo.grd.nextroute.application.notification.*'`
- [ ] `PHASE-5.md` ② 진행 표시 + 보관(retention) 후속 과제 한 줄
- [ ] 커밋 + PR (플랜 파일 2개 포함)

---

## 4. V40 SQL

`src/main/resources/db/migration/V40__create_journey_notification.sql`

스타일은 `V35__create_journey.sql` 을 따른다(한국어 `--` 헤더로 *왜*, `id bigint generated by
default as identity primary key`, 감사칼럼 `timestamp(6) not null`, 도메인 시각 `timestamptz`,
enum 은 `constraint <table>_<col>_check`, 인덱스 `uq_`/`idx_`).

```sql
-- 여정 알림 발송 outbox. 평가기는 "이 체크포인트가 발생했다"만 적고 발송은 relay 가 한다.
--
-- bus_alert_notification(V28)의 상태 기계를 그대로 따르되 세 가지를 바꾼다.
--   1) 도메인 시각을 timestamptz(Instant) 로 둔다. journey 도메인은 전부 Instant 다(V35).
--      LocalDateTime 을 섞으면 경계마다 변환이 생기고 KST/UTC 어긋남이 조용히 들어온다.
--   2) 멱등 키가 두 겹이다(REQUIREMENTS 4.5). logical_checkpoint_key = "사용자에게 두 번
--      보이지 않게", delivery_key = logical:channel = "채널로 두 번 보내지 않게".
--   3) payload 는 jsonb. journey 표 관례이고 PR6 이 정할 payload 계약이 구조체다.
--
-- delivery_key 의 unique 에 WHERE deleted_at IS NULL 을 붙이지 않는다(관례에서 의도적 이탈).
-- 이 표의 행은 절대 지우지 않는다 - soft delete 도 물리 삭제도 하지 않는다. 삭제하는 순간
-- 키가 다시 열려 같은 알림이 재발송되기 때문이다. deleted_at 컬럼은 BaseEntity 가 주는 것이라
-- 있을 뿐 이 표에서는 쓰지 않는다. 보관 정책이 필요해지면 흔적 표를 먼저 설계해야 한다.

CREATE TABLE journey_notification (
    id                     bigint generated by default as identity primary key,
    created_at             timestamp(6) not null,
    updated_at             timestamp(6) not null,
    deleted_at             timestamp(6),

    journey_id             bigint       not null references journey_session(id),
    -- 수신자. relay 가 journey_session 을 다시 읽지 않게 비정규화한다. enqueue SQL 이
    -- journey_session 에서 그대로 집어 오므로 두 값이 어긋날 수 없다.
    user_id                bigint       not null references users(id),
    route_revision         integer      not null
        constraint journey_notification_route_revision_check check (route_revision >= 1),

    -- 만들 당시의 세 version. PR5 가 발송 직전 재검증에 쓰고 PR6 이 payload 에 싣는데
    -- (TECHNICAL_DECISIONS 2장) 두 PR 다 마이그레이션이 없다. 나중에 ALTER 로 붙이면 이미 쌓인
    -- 행의 기본값을 소급 결정해야 하고 그 결정이 곧 "낡은 알림 판정 규칙"이라 위험하다.
    -- enqueue SQL 이 journey_session 에서 그대로 집어 온다 - 같은 트랜잭션이라 CAS 가 올린
    -- 뒤의 값을 본다.
    command_version        bigint       not null
        constraint journey_notification_command_version_check check (command_version >= 1),
    evaluation_version     bigint       not null
        constraint journey_notification_evaluation_version_check check (evaluation_version >= 0),

    -- 알림 대상. 환승=TRANSFER(scope_id=transfer_index), 하차=LEG(scope_id=leg_index),
    -- 여정 전체=JOURNEY(scope_id=0). PR3·PR4 에는 마이그레이션이 없으므로 값 목록을 지금 다 연다.
    scope_type             varchar(20)  not null
        constraint journey_notification_scope_type_check
            check (scope_type in ('TRANSFER', 'LEG', 'JOURNEY')),
    scope_id               integer      not null
        constraint journey_notification_scope_id_check check (scope_id >= 0),

    -- 체크포인트 코드. CHECK 를 걸지 않는다 — 값 목록을 늘리는 것은 PR4 인데 PR4 에는
    -- 마이그레이션이 없다. 어휘는 Java enum(JourneyCheckpoint) 이 소유한다.
    checkpoint             varchar(40)  not null,

    -- 발송 채널. PR3 이 어댑터를 붙이지만 PR3 에도 마이그레이션이 없으므로 지금 둘 다 넣는다.
    --
    -- 값이 FCM 이 아니라 PUSH 인 이유(TECHNICAL_DECISIONS 2장). FCM 은 채널이 아니라 provider 고,
    -- 그것은 user_push_token.provider(V39)가 이미 갖는다. 채널은 "어느 길로 보내는가"(토스
    -- 메신저냐 네이티브 푸시냐)이고, 그 길 안에서 어느 중계자를 쓰는지는 token 이 안다.
    channel                varchar(20)  not null
        constraint journey_notification_channel_check
            check (channel in ('TOSS', 'PUSH')),

    logical_checkpoint_key varchar(255) not null,
    delivery_key           varchar(255) not null,

    status                 varchar(20)  not null
        constraint journey_notification_status_check
            check (status in ('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'DEAD', 'CANCELED')),

    scheduled_at           timestamptz  not null,
    -- 이 시각을 넘기면 보내지 않고 CANCELED. 늦게 도착한 환승 알림은 사용자를 엉뚱한 때에
    -- 움직인다. PR3 이 이 값으로 FCM TTL 을 만든다.
    expires_at             timestamptz  not null,
    payload                jsonb,

    attempt_count          integer      not null default 0
        constraint journey_notification_attempt_count_check check (attempt_count >= 0),
    requeue_count          integer      not null default 0
        constraint journey_notification_requeue_count_check check (requeue_count >= 0),
    last_requeued_at       timestamptz,
    processing_started_at  timestamptz,
    sent_at                timestamptz,
    last_failure_reason    varchar(500)
);

-- ON CONFLICT 중재자. 부분 인덱스가 아닌 이유는 위 헤더 참고.
CREATE UNIQUE INDEX uq_journey_notification_delivery
    ON journey_notification (delivery_key);

-- 사용자 표시 dedupe 조회(PR4)와 감사. 채널이 둘이면 같은 값이 2행이라 unique 가 아니다.
CREATE INDEX idx_journey_notification_logical
    ON journey_notification (logical_checkpoint_key);

-- relay claim 경로. status='PENDING' 을 술어로 뺀 부분 인덱스다. (status, scheduled_at) 통
-- 인덱스(V28 방식)로 두면 영구 보관되는 SENT 행이 인덱스의 대부분을 차지한다.
CREATE INDEX idx_journey_notification_claim
    ON journey_notification (scheduled_at)
    WHERE status = 'PENDING' AND deleted_at IS NULL;

-- 고착 행 회수 경로. relay 루프가 sweep 마다(1초) 도는 쿼리라 전체 스캔이면 안 된다.
-- 버스 outbox 는 이 인덱스가 없어 매 sweep 전체 스캔이다 — 여기서는 반복하지 않는다.
CREATE INDEX idx_journey_notification_processing
    ON journey_notification (processing_started_at)
    WHERE status = 'PROCESSING';

-- 여정 종료 시 pending 취소. 종료는 사용자 요청 경로(DELETE /api/journeys/{id})에서도
-- 일어나므로 이 UPDATE 가 표 전체를 훑으면 안 된다.
CREATE INDEX idx_journey_notification_journey_live
    ON journey_notification (journey_id)
    WHERE status IN ('PENDING', 'PROCESSING');

-- DEAD 분류(운영 확인). V28 관례.
CREATE INDEX idx_journey_notification_dead
    ON journey_notification (created_at) WHERE status = 'DEAD';
```

**의도적으로 안 넣은 것**: `check (expires_at > scheduled_at)`. `releaseToPending` 이
`scheduled_at` 을 백오프만큼 미루므로 2~3회 재시도 뒤 `expires_at` 을 넘을 수 있고, 그러면 CHECK 가
`REQUIRES_NEW` 트랜잭션 안에서 터져 행이 PROCESSING 에 갇혀 회수 루프를 무한 반복한다. 이
불변조건은 Java 에서 지킨다(§2.2).

---

## 5. 신규 파일 시그니처

### 5.1 도메인

```java
// domain/journey/entity/JourneyNotificationStatus.java
public enum JourneyNotificationStatus {
    PENDING, PROCESSING, SENT,
    /** permanent 실패(종결). 재시도 대상 아님. */
    FAILED,
    /** transient 재시도 소진 → 수동 개입 대기. */
    DEAD,
    CANCELED
}

// domain/journey/entity/JourneyNotificationScope.java
public enum JourneyNotificationScope { TRANSFER, LEG, JOURNEY }

// domain/journey/entity/JourneyCheckpoint.java
/**
 * 체크포인트 어휘. DB CHECK 를 걸지 않고 여기서 소유한다 — PR4 가 값을 늘리는데 PR4 에는
 * 마이그레이션이 없다.
 *
 * <p>상태를 이름에 품는 것이 필수다. TRANSFER_RISK 하나로 두면 AT_RISK→MISSED 승격이 같은
 * deliveryKey 를 만들어 ON CONFLICT 에 삼켜진다.
 */
public enum JourneyCheckpoint {
    TRANSFER_RISK_AT_RISK, TRANSFER_RISK_MISSED;
    public static JourneyCheckpoint ofTransferStatus(TransferStatus status) { ... }
}

// domain/notification/entity/NotificationChannel.java
/** PR3 의 FCM 어댑터가 journey 패키지에 의존하지 않도록 notification 도메인에 둔다. */
public enum NotificationChannel { TOSS, PUSH }
// FCM 은 채널이 아니라 provider 다(TECHNICAL_DECISIONS 2장). 그것은 user_push_token.provider(V39).
```

`domain/journey/entity/JourneyNotification.java` — `BaseEntity` 상속, `@Builder` 수기 생성자,
setter 없음. 상태 전이 메서드는 `BusAlertNotification.java:76-100` 을 그대로 따른다:

```java
public void markCanceled(String reason)                       // reason 을 lastFailureReason 에
public void markSent(Instant now)
public void markFailed(String reason)                         // permanent
public void markDead(String reason)
public void releaseToPending(String reason, Instant nextAttemptAt)
```

`attemptCount` 는 claim SQL 이 올린다 — 이 메서드들은 안 건드린다(버스와 동일).
`payload` 는 `@JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") JsonNode`
(`JourneySession.routeSnapshot` 과 같은 방식).

### 5.2 레포지토리

`domain/journey/repository/JourneyNotificationRepository.java`

```java
/**
 * enqueue. 여정 재확인 CTE + INSERT 를 한 문장으로 묶는다.
 *
 * <p>CTE 가 여정이 아직 ACTIVE 이고 route_revision 이 계산 시점과 같은지 확인한다. 여정이
 * 끝났거나 경로가 바뀌었으면 CTE 가 비어 0행 삽입 — 예외 없이.
 *
 * <p>ON CONFLICT 대상은 delivery_key 다. logical_checkpoint_key 로 걸면 두 번째 채널이 조용히
 * 사라진다 — 키가 두 겹인 이유가 그것이다.
 *
 * <p>:now·:expiresAt 은 Instant(timestamptz), :auditNow 는 LocalDateTime(BaseEntity 감사 칼럼).
 * 한 문장에 시각 파라미터가 둘인 것은 보기 나쁘지만 맞다. 이름을 헷갈리지 말 것.
 *
 * <p>세 version 은 파라미터로 받지 않고 CTE 가 journey_session 에서 그대로 집어 온다. 같은
 * 트랜잭션 안이라 CAS(finishEvaluation)가 evaluation_version 을 올린 <b>뒤</b>의 값을 본다 —
 * Java 로 옮기려면 native UPDATE 가 올린 값을 다시 읽어야 해서 쿼리가 하나 더 는다.
 *
 * <p>{@code @Modifying} 이 아니다(RETURNING). 반드시 {@code @Transactional} 안에서 부른다.
 *
 * @return 생성된 행 id(1건). 중복이거나 여정이 끝났으면 0건
 */
@Query(value = """
        WITH src AS (
            SELECT s.id AS journey_id, s.user_id,
                   s.command_version, s.evaluation_version
            FROM journey_session s
            WHERE s.id = :journeyId
              AND s.route_revision = :routeRevision
              AND s.status = 'ACTIVE'
              AND s.deleted_at IS NULL)
        INSERT INTO journey_notification
            (journey_id, user_id, route_revision, command_version, evaluation_version,
             scope_type, scope_id, checkpoint, channel,
             logical_checkpoint_key, delivery_key, status, scheduled_at, expires_at, payload,
             attempt_count, requeue_count, created_at, updated_at)
        SELECT src.journey_id, src.user_id, :routeRevision,
               src.command_version, src.evaluation_version,
               :scopeType, :scopeId, :checkpoint, :channel,
               :logicalKey, :deliveryKey, 'PENDING', :now, :expiresAt,
               cast(:payload as jsonb), 0, 0, :auditNow, :auditNow
        FROM src
        ON CONFLICT (delivery_key) DO NOTHING
        RETURNING id
        """, nativeQuery = true)
List<Long> enqueue(@Param("journeyId") Long journeyId, @Param("routeRevision") int routeRevision,
                   @Param("scopeType") String scopeType, @Param("scopeId") int scopeId,
                   @Param("checkpoint") String checkpoint, @Param("channel") String channel,
                   @Param("logicalKey") String logicalKey, @Param("deliveryKey") String deliveryKey,
                   @Param("now") Instant now, @Param("expiresAt") Instant expiresAt,
                   @Param("payload") String payload, @Param("auditNow") LocalDateTime auditNow);

/**
 * 같은 대상의 낡은 PENDING 을 밀어낸다(§2.3).
 *
 * <p>AT_RISK 와 MISSED 는 별개 행이라 재시도로 순서가 뒤집힐 수 있다. 상태가 좋아졌을 때
 * 쌓여 있던 알림이 30분 동안 살아 있는 것도 같은 문제다.
 *
 * <p>{@code exceptDeliveryKey} 가 null 이면 그 대상의 PENDING 을 전부 취소한다(회복 경로).
 */
@Modifying(flushAutomatically = true, clearAutomatically = false)
@Query("""
        update JourneyNotification n
           set n.status = watoo...JourneyNotificationStatus.CANCELED,
               n.lastFailureReason = 'superseded'
         where n.journeyId = :journeyId and n.routeRevision = :routeRevision
           and n.scopeType = :scopeType and n.scopeId = :scopeId
           and n.status = watoo...JourneyNotificationStatus.PENDING
           and (:exceptDeliveryKey is null or n.deliveryKey <> :exceptDeliveryKey)
        """)
int supersedePendingByScope(@Param("journeyId") Long journeyId,
                            @Param("routeRevision") int routeRevision,
                            @Param("scopeType") JourneyNotificationScope scopeType,
                            @Param("scopeId") int scopeId,
                            @Param("exceptDeliveryKey") String exceptDeliveryKey);

/** relay 배치 claim. BusAlertNotificationRepository#claimBatchForProcessing 과 같은 모양. */
@Query(value = """
        WITH picked AS (
            SELECT o.id FROM journey_notification o
            WHERE o.status = 'PENDING' AND o.scheduled_at <= :now AND o.deleted_at IS NULL
            ORDER BY o.scheduled_at
            LIMIT :batchSize
            FOR UPDATE OF o SKIP LOCKED)
        UPDATE journey_notification n
        SET status = 'PROCESSING', processing_started_at = :now,
            attempt_count = n.attempt_count + 1, updated_at = :auditNow
        FROM picked
        WHERE n.id = picked.id
        RETURNING n.id
        """, nativeQuery = true)
List<Long> claimBatchForProcessing(@Param("now") Instant now, @Param("batchSize") int batchSize,
                                   @Param("auditNow") LocalDateTime auditNow);

/** 고착 PROCESSING 회수 → PENDING(scheduledAt=now). crash 소유권 회수. */
@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query("""
        update JourneyNotification n
           set n.status = watoo...JourneyNotificationStatus.PENDING, n.scheduledAt = :now
         where n.status = watoo...JourneyNotificationStatus.PROCESSING
           and n.processingStartedAt < :staleBefore
        """)
int reclaimStale(@Param("staleBefore") Instant staleBefore, @Param("now") Instant now);

/**
 * 여정 종료 시 미발송 취소.
 *
 * <p>PENDING 만 건드린다. PROCESSING 은 이미 발송 중이라 REQUIRES_NEW 로 도는
 * markSent/markTransientFailure 와 충돌한다 — 그쪽은 relay 의 발송 직전 재확인이 잡는다.
 *
 * <p>clearAutomatically = false 다(기존 cancelPendingByAlertId 와 다름). JourneyCommandService 는
 * 취소 뒤에도 여정 엔티티를 만진다(bumpCommandVersion, 스냅샷 생성). 영속성 컨텍스트를 비우면
 * 그 변경이 조용히 사라진다. 이 트랜잭션들이 JourneyNotification 을 읽은 적은 없어 비울 이유도
 * 없다.
 */
@Modifying(flushAutomatically = true, clearAutomatically = false)
@Query("""
        update JourneyNotification n
           set n.status = watoo...JourneyNotificationStatus.CANCELED,
               n.lastFailureReason = 'journey-ended'
         where n.journeyId = :journeyId
           and n.status = watoo...JourneyNotificationStatus.PENDING
        """)
int cancelPendingByJourneyId(@Param("journeyId") Long journeyId);

@Modifying(flushAutomatically = true, clearAutomatically = false)
@Query("... where n.journeyId in :journeyIds and n.status = ...PENDING")
int cancelPendingByJourneyIdIn(@Param("journeyIds") Collection<Long> journeyIds);
```

### 5.3 포트·DTO·예외

```java
// application/journey/port/out/NotificationSender.java
/** 채널을 모르는 발송 포트. PR2 는 no-op, PR3 이 FCM·토스 구현을 붙인다. */
public interface NotificationSender {
    /** 실패 시 {@link NotificationSendException} 을 던진다(permanent 여부 포함). */
    void send(NotificationSendCommand command);
}

// application/journey/port/out/NotificationChannelResolver.java
/**
 * 이 사용자에게 어느 길로 보낼지. PR2 기본 구현은 항상 PUSH 하나.
 * PR3 이 auth_identity.provider 를 읽는 구현으로 교체한다(@ConditionalOnProperty).
 *
 * <p>계약: null 도 빈 목록도 반환하지 않는다. 절대 예외를 던지지 않는다 — 알림 실패가 여정을
 * 실패시키면 안 된다. 부르는 쪽도 빈 목록을 방어한다(마커를 앞당기면 영영 알림이 없다).
 */
public interface NotificationChannelResolver {
    List<NotificationChannel> resolve(long userId);
}

// application/journey/dto/NotificationContext.java
/**
 * 평가 트랜잭션 밖에서 미리 정해 finish() 에 넘기는 발송 정보.
 *
 * <p>JourneyEvaluationOutcome 에 넣지 않는다 — 그쪽은 "무엇을 계산했나"이고 이쪽은 "어디로
 * 보내나"다. 섞으면 계산 DTO 가 알림 관심사를 알게 된다.
 */
public record NotificationContext(long userId, int currentLegIndex,
                                  List<NotificationChannel> channels) {}

// application/journey/dto/NotificationSendCommand.java
public record NotificationSendCommand(long notificationId, long userId,
                                      NotificationChannel channel, JourneyCheckpoint checkpoint,
                                      JsonNode payload, Instant expiresAt) {}

// application/journey/exception/NotificationSendException.java
/** TossMessengerException 과 같은 모양. permanent=true 면 재시도 없이 FAILED. */
@Getter
public class NotificationSendException extends RuntimeException {
    private final boolean permanent;
}
```

**`NotificationSender` 계약에서 못박을 것 — 사용자당 기기가 여럿이다** (codex 지적).

PR ① 이 사용자당 여러 push token 을 허용했는데 outbox 행은 채널당 하나다. 토큰 3개 중 1개만
실패하면 그 행이 재시도되면서 이미 성공한 2개에 또 간다.

② 는 sender 가 no-op 이라 실제로 겪지 않지만 계약을 지금 정한다.

- **행 하나 = "이 사용자에게 이 채널로 한 번 알린다"** 이지 "토큰 하나에 보낸다"가 아니다
- `SENT` 의 뜻 = **살아 있는 token 전부에 대해 provider 에 넘겼다**
- **토큰별 영구 실패는 outbox 가 재시도하지 않는다.** ③ 이 `UNREGISTERED` 인 token 을
  `enabled=false` 로 끄고(PR ① 이 자리를 만들어 뒀다) 그 행은 `SENT` 로 끝낸다
- **토큰별 일시 실패는 행 전체가 재시도한다.** 이미 성공한 기기에 중복이 갈 수 있다 — 수용한다.
  ⑥ 이 payload 에 상태를 안 담고 ③ 이 collapse key 를 쓰므로 중복의 해가 작다
- 토큰별 상태 표는 만들지 않는다. 필요해지면 ③ 이 판단한다

### 5.4 키 조립

`application/journey/service/JourneyNotificationKeys.java` — 두 키 형식이 존재하는 **유일한** 자리.

```java
public static String logical(long journeyId, int routeRevision,
                             JourneyNotificationScope scope, int scopeId,
                             JourneyCheckpoint checkpoint) {
    return journeyId + ":" + routeRevision + ":" + scope.name() + ":" + scopeId + ":" + checkpoint.name();
}

public static String delivery(String logicalKey, NotificationChannel channel) {
    return logicalKey + ":" + channel.name();
}
```

### 5.5 알림 판단 (`TransferRiskNotifier`)

`JourneyEvaluationScheduleWriter.finish` 안에서, `assessmentWriter.write(...)` 뒤 ·
`storeBoardingPredictions` 앞에 호출된다. 같은 트랜잭션.

```java
private static final Set<TransferStatus> NOTIFIABLE = EnumSet.of(AT_RISK, MISSED);

void notifyWorsened(JourneyEvaluationOutcome outcome, NotificationContext ctx) {
    if (!properties.isEnqueueEnabled()) return;
    if (ctx.channels().isEmpty()) return;          // §2.4 — 마커를 앞당기면 영영 알림 없음

    // 마커를 쓸 엔티티. 같은 트랜잭션이라 1차 캐시가 assessmentWriter 가 방금 만진 것과
    // 같은 인스턴스를 준다(§1 표). TransferAssessmentWriter 시그니처는 안 바꾼다.
    Map<Integer, TransferConnection> byIndex = transferRepository
            .findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByTransferIndex(
                    outcome.journeyId(), outcome.routeRevision())
            .stream().collect(toMap(TransferConnection::getTransferIndex, identity()));

    for (TransferRiskAssessment a : outcome.batch().assessments()) {
        TransferConnection tc = byIndex.get(a.transferIndex());
        if (tc == null) continue;
        if (!a.isUpcoming(ctx.currentLegIndex())) continue;       // 지나온 환승 제외

        if (!NOTIFIABLE.contains(a.status())) {
            // 회복 경로(§2.3). 알렸던 기록이 있는데 지금은 알릴 상태가 아니면, 쌓여 있는
            // 알림을 밀어내고 마커를 비운다 — 안 하면 옛 AT_RISK 가 30분 동안 살아 있고
            // 다시 나빠졌을 때 재알림도 막힌다.
            if (tc.getLastNotifiedStatus() != null) {
                notificationRepository.supersedePendingByScope(
                        outcome.journeyId(), outcome.routeRevision(), TRANSFER, a.transferIndex(), null);
                tc.clearNotified();
            }
            continue;
        }

        int prev = severityOf(tc.getLastNotifiedStatus());        // 없으면 -1
        if (a.status().severity() <= prev) continue;              // 나빠질 때만

        String lastDeliveryKey = null;
        for (NotificationChannel ch : ctx.channels()) {
            lastDeliveryKey = enqueuer.enqueue(outcome, a, ch);   // 0행이어도 정상
        }
        // 승격(AT_RISK→MISSED)으로 생긴 옛 행을 밀어낸다(§2.3).
        // 채널이 여럿이면 마지막 키만 남기는 게 아니라 이번 checkpoint 의 키 전부를 남겨야
        // 하므로 구현은 exceptDeliveryKeys(Collection) 로 받는다.
        notificationRepository.supersedePendingByScope(
                outcome.journeyId(), outcome.routeRevision(), TRANSFER, a.transferIndex(),
                enqueuedKeys);

        tc.markNotified(a.status().name());
    }
}
```

**여기서 지켜야 할 성질 5개:**

1. **UNKNOWN 은 마커에 절대 안 써진다.** 마커는 enqueue 할 때만 쓰고 UNKNOWN 은 알릴 값이
   아니기 때문. 안 그러면 `AT_RISK → UNKNOWN → AT_RISK` 가 재알림이 된다.
   ⚠ 기존 `severity()` 표는 UNKNOWN(2) 이 CATCHABLE(1) **위**다. 숫자만 비교하면 "모르겠다"가
   알림이 된다 — `NOTIFIABLE` 필터가 숫자 비교 **앞**에 와야 하는 이유.
   ⚠ 회복 경로도 UNKNOWN 에서는 마커를 비우지 **않아야** 한다. 잠깐 모르는 것과 실제로 좋아진
   것은 다르다. 구현은 `NOTIFIABLE` 도 UNKNOWN 도 아닌 값(COMFORTABLE·CATCHABLE)에서만 비운다.
2. **INSERT 가 0행이어도 마커는 쓴다.** 충돌은 "사용자가 이미 봤다"는 뜻이고, 마커를 안 쓰면
   10초마다 영원히 헛삽입한다.
3. **`finish()` 의 CAS 뒤여야 한다.** claim 을 뺏긴 워커는 `finish()` 가 false 로 빠져 여기 못
   온다. 순서를 바꾸면 진 워커가 마커를 앞당겨 찍어 이긴 워커의 알림을 영구히 막는다.
   Javadoc 에 못박고 `JourneyNotificationLostClaimIT` 로 고정한다.
4. **채널 목록이 비면 아무것도 안 한다**(§2.4).
5. **마커 대상 엔티티는 다시 조회한다.** `TransferAssessmentWriter.write` 의 시그니처를 바꾸지
   않는 이유: 그 메서드에는 호출자가 둘이고(`JourneyEvaluationScheduleWriter:61` 과 조회 경로
   `TransferRiskEvaluator:103`), 조회 경로는 반환 시점에 트랜잭션이 끝난다. 관리 중인 엔티티를
   내보내면 나중에 누가 그것을 고쳤을 때 `lastNotifiedStatus` 가 조용히 사라진다(codex 지적).

### 5.6 enqueue (`JourneyNotificationEnqueuer`)

- 키 조립·payload 직렬화·`expiresAt` 계산은 **레포지토리 호출 전**에 한다(트랜잭션 안에서 예외를
  안 내려고). 직렬화 실패는 `null` payload + 경고 로그 —
  `BusAlertOutboxEnqueueService.writePayload` 와 같은 방식
- `expiresAt` = §2.2 의 clamp
- 반환 0행/1행 둘 다 정상. **`try/catch` 로 감싸지 않는다** — 이미 rollback-only 가 된
  트랜잭션을 삼키면 시끄러운 실패가 조용한 실패로 바뀔 뿐
- wake 이벤트는 `finish()` 당 **최대 1회** 발행(행마다 아님). Semaphore 가 합치긴 하지만 초당
  200 평가에서 이벤트 멀티캐스터 시간을 낭비한다

### 5.7 relay 4종

`BusAlertOutboxStateService`·`BusAlertOutboxRelayService`·`RelayWaker`·`RelayWakeListener`·
`BusAlertRelayLoop` 를 그대로 베낀다. **버스 쪽 파일은 한 줄도 안 고친다.**

`JourneyNotificationOutboxStateService` — 모든 트랜잭션의 주인:

| 메서드 | 트랜잭션 | 내용 |
|---|---|---|
| `reclaim(now)` | `@Transactional` | `reclaimStale(now - claimTimeout, now)` |
| `claimBatch(now, size)` | `@Transactional` | `claimBatchForProcessing` |
| `loadTask(id)` | `@Transactional` | **발송 직전 재확인** (아래) |
| `markSent(id)` | `REQUIRES_NEW` | |
| `markTransientFailure(id, reason)` | `REQUIRES_NEW` | |
| `markPermanentFailure(id, reason)` | `REQUIRES_NEW` | |

`loadTask` 재확인 항목 (하나라도 어긋나면 `markCanceled` 후 빈 Optional):
- 행이 아직 `PROCESSING` 인가
- `journey_session` 이 아직 `ACTIVE` 이고 soft delete 안 됐나
- `journey_session.route_revision == n.routeRevision` — **"재탐색 후 이전 revision 발송 0"이
  여기서 지켜진다.** ⑤ 로 미루지 말 것. 재탐색 기능이 아직 없어 항상 1 이지만 미리 막는다
- `expiresAt > now`

`markTransientFailure` 규칙:
```java
if (n.getStatus() != PROCESSING) return;                     // 취소된 행 되살리기 금지
Instant nextAt = now.plusSeconds(backoffSeconds);            // 고정 간격(버스와 동일)
if (n.getAttemptCount() >= maxAttempts) n.markDead("max-retry: " + reason);
else if (!nextAt.isBefore(n.getExpiresAt())) n.markCanceled("expired-before-retry");
else n.releaseToPending(reason, nextAt);
```

⚠ 버스 템플릿에는 `getStatus() != PROCESSING` 가드가 **없다**(그쪽은 `loadTask` 가 취소하면 빈
Optional 을 반환해 mark 가 뒤따르지 않아 우연히 안전). 그 우연을 물려받지 말 것.

`JourneyNotificationRelayService` — `@Transactional` 을 **안** 붙인다.
`drainUntilEmpty()` → `relayOnce()` (reclaim → claimBatch → 워커 풀 팬아웃 → `process(id)`),
스레드 이름 `journey-relay-N`, 데몬, `@PreDestroy shutdownNow`.

발송 결과 분류 — **`NotificationSendException` 이 아닌 것도 전부 잡는다**(§2.6):

```java
try {
    sender.send(command);
    stateService.markSent(id);
} catch (NotificationSendException e) {
    if (e.isPermanent()) stateService.markPermanentFailure(id, e.getMessage());
    else stateService.markTransientFailure(id, e.getMessage());
} catch (Throwable t) {
    // 예상 못 한 예외(NPE·SDK 내부 오류·타임아웃)를 안 잡으면 행이 PROCESSING 에 남아
    // 회수 → 재시도 → 또 예외를 영원히 반복한다. transient 로 보고 재시도 예산을 태운다.
    stateService.markTransientFailure(id, "unexpected: " + t);
}
```

`JourneyRelayWaker` — 버스 `RelayWaker` 20줄 복제. **공유·상속 금지**(§1 표 참고).

`JourneyNotificationRelayLoop` — `SmartLifecycle`, `@Profile("worker")` +
`@ConditionalOnProperty(prefix="journey.notification.relay", name="enabled", havingValue="true")`.

### 5.8 설정

`application/journey/config/JourneyNotificationProperties.java`, prefix `journey.notification`.
`@PostConstruct validate()` 로 부팅에서 막는다(`JourneyEvaluatorProperties.validate()` 관례).

```yaml
journey:
  notification:
    enqueue-enabled: true          # 평가기는 돌되 알림만 끄는 kill switch
    sender: noop                   # PR3 이 fcm 추가
    channel-resolver: fixed        # PR3 이 identity 추가
    min-ttl-seconds: 180           # MISSED 가 즉시 만료되지 않게 + 재시도 3회가 들어갈 바닥
    default-ttl-seconds: 600
    max-ttl-seconds: 1800
    max-attempts: 3
    backoff-seconds: 60
    claim-timeout-minutes: 3
    relay:
      enabled: false               # PR3 전까지 꺼 둔다
      sweep-interval-ms: 1000
      batch-size: 200
      workers: 4
      max-batches: 1000
      drain-max-millis: 5000
```

`@PostConstruct validate()` 가 부팅에서 막는 것:

| 규칙 | 이유 |
|---|---|
| `minTtl > 0`, `maxTtl >= defaultTtl >= minTtl` | 순서 뒤집힘 |
| **`backoffSeconds * maxAttempts <= minTtlSeconds`** | 만료 바닥에 걸린 행이 재시도를 한 번도 못 하면 안 된다. 60×3=180 ≤ 180 ✓. `minTtl=60` 이던 원안은 이 규칙에 걸린다(codex 지적) |
| **`relay.enabled=true` 이면 `sender != noop`** (`test` 프로필 제외) | no-op 이 진짜 행을 SENT 로 소모해 나중에 진짜 발송을 막는다(§2.5) |
| `sender` 가 아는 값인지 | 오타로 조용히 no-op 이 뜨는 것 방지 |

### 5.9 스위치 4개 — 기본은 전부 꺼짐 조합

| 스위치 | 기본 | 끄면 |
|---|---|---|
| `journey.navigation.evaluator.enabled` (기존) | false | 평가기 빈 자체가 안 뜬다 → 알림 0. 배선 수준 보장 |
| `journey.notification.enqueue-enabled` | true | 평가기는 돌되 안 쌓는다 |
| `journey.notification.relay.enabled` | false | relay 루프 빈이 안 뜬다. 쌓이기만 함 |
| `journey.notification.sender` | `noop` | 아무것도 안 보낸다 |

**취소 갈고리에는 플래그를 안 건다.** 평가기를 껐다 켜는 사이 PENDING 이 남으면 죽은 여정의
알림이 나중에 나간다.

### 5.10 지표

`application/journey/observability/JourneyNotificationMetrics.java`
(`JourneyEvaluationMetrics` 관례). 버스 outbox 는 Micrometer 계측이 0 이지만 ② 가 한동안 혼자
운영되므로 최소한만 넣는다.

`enqueued{checkpoint}` · `claimed` · `sent{channel}` · `failed{channel,kind}` ·
`canceled{reason}`. **journeyId·userId·token 은 태그 금지**(카디널리티 + 개인정보).

---

## 6. 수정 파일 12개

| 경로 | 변경 |
|---|---|
| `.gitignore` (27행 뒤) | `!src/main/resources/db/migration/V40__create_journey_notification.sql` |
| `src/main/resources/application.yaml` | §5.8 블록 |
| `application/journey/service/JourneyEvaluationService.java` | `applyGrace` 뒤 · `finish()` 앞에서 `channelResolver.resolve(userId)` 호출(**트랜잭션 밖**) → `NotificationContext` 를 만들어 `finish()` 에 넘김. 알릴 값이 하나도 없으면 호출 생략 |
| `application/journey/service/JourneyEvaluationScheduleWriter.java` | `finish(claim, outcome, nextAt, notificationContext)` 로 인자 하나 추가. CAS 뒤·boarding 앞에서 `notifier.notifyWorsened(outcome, ctx)` |
| `domain/journey/entity/TransferConnection.java` | `+markNotified(String)`, `+clearNotified()`. `lastNotifiedStatus` 의 **유일한** writer |
| `domain/journey/entity/TransferStatus.java` | `+public int severity()` |
| `application/journey/service/TransferRiskPolicy.java` | private `severity()` → enum 위임 |
| `application/journey/service/JourneyCommandService.java` | `:137` `journey.end(...)` 뒤 `cancelPendingByJourneyId` |
| `application/journey/service/JourneyStartTxService.java` | `:120` `saveAndFlush(existing)` 뒤 `cancelPendingByJourneyId` |
| `application/journey/service/JourneyLeaseExpiryService.java` | **`sweep()` 을 `TransactionTemplate` 으로 감싼다**(아래 ⚠) + `expireBatch()` 안에서 `cancelPendingByJourneyIdIn(expired)` |
| `src/test/java/.../domain/journey/JourneySchemaIT.java` | `@Sql` 배열에 V40 |

**안 고치는 것 2개**(codex 리뷰로 바뀐 결정):
- `JourneyEvaluationOutcome` — `channels`·`userId`·`currentLegIndex` 를 넣지 않고 별도
  `NotificationContext` 로 넘긴다. 발송 경로 결정이 계산 DTO 에 섞이지 않는다
- `TransferAssessmentWriter` — 시그니처 그대로. 관리 중인 엔티티를 내보내면 조회 경로
  (`TransferRiskEvaluator:103` → `assessAndStore`)의 호출자가 반환 뒤에 그것을 고쳤을 때
  `lastNotifiedStatus` 가 조용히 사라진다. 알림 쪽에서 다시 조회한다(§5.5 성질 5)

### 여정 종료 5경로 → 코드 3곳

`JourneySession.end()` 의 Java 호출 자리는 저장소 전체에 **2곳**뿐이고, 여기에 raw SQL 배치를
더하면 3곳이 5경로를 전부 덮는다.

| 경로 | 자리 |
|---|---|
| 사용자 취소(DELETE) / 완료 / 앱이 보고한 lease 만료 | `JourneyCommandService:136-137` (셋이 여기로 모임) |
| 새 여정으로 대체(`replace=true`) | `JourneyStartTxService:114-120` |
| 서버 lease 만료 배치 | `JourneyLeaseExpiryService.expireBatch():67` |

### ⚠ 만료 배치는 지금 트랜잭션 없이 돈다 — 먼저 고쳐야 한다

codex 리뷰가 잡은 것이고 **확인했다**. `JourneyLeaseExpiryService.sweep():46` 이 같은 클래스의
`expireBatch()` 를 그냥 부른다. Spring 의 `@Transactional` 은 프록시로 동작하므로 자기 클래스
안에서 직접 부르면 **적용되지 않는다**. 즉 운영에서 `expireBatch()` 는 트랜잭션 밖이다.

지금까지 안 터진 이유: `claimExpiredBatch` 가 SQL **한 문장**이라 자동 커밋으로 원자적이다.
기존 IT(`JourneyLeaseExpiryIT`)는 `expireBatch()` 를 **직접** 불러서 프록시를 타므로 이 문제를
못 잡았다.

여기에 취소 쿼리를 더하면 문장이 둘이 되고, 각각 따로 커밋된다 → **만료는 됐는데 알림은 안
취소된 상태**가 생긴다. 그래서 이 PR 이 먼저 고친다.

**고치는 방법**: `JourneyLeaseExpiryService` 에 `TransactionTemplate` 을 주입하고 `sweep()` 이
배치 한 번을 그 안에서 돌린다. `expireBatch()` 의 `@Transactional` 은 직접 호출하는 기존 IT 를
위해 남긴다(중첩되면 참여할 뿐 해롭지 않다).

```java
public int sweep() {
    int total = 0;
    for (int i = 0; i < expiryProperties.getMaxSweepsPerTick(); i++) {
        // 자기 호출은 프록시를 안 타 @Transactional 이 안 걸린다. 배치와 알림 취소가
        // 따로 커밋되면 "만료됐는데 알림은 살아 있는" 상태가 생기므로 여기서 명시적으로 연다.
        int expired = transactionTemplate.execute(status -> expireBatch()).size();
        ...
    }
}
```

**검증**: 기존 IT 로는 못 잡으므로 `sweep()` 을 직접 도는 IT 를 새로 만든다
(§9 `JourneyNotificationCancelOnEndIT` 에 포함).

만료 배치의 취소를 `claimExpiredBatch` CTE 에 합치지 않는 이유(위 수정 뒤에도 유지): 트랜잭션이
확보되면 두 문장으로도 원자적이고, 합치면 journey 레포지토리가 notification 표에 쓰게 되며, 부하
시험을 거친 쿼리(`idx_journey_due`, 정렬 단계 없음)의 실행 계획을 흔든다. 200개짜리 `IN` 은
`idx_journey_notification_journey_live` 로 싸다.

---

## 7. 나머지 위험과 그 처리

| 위험 | 처리 |
|---|---|
| claim 뺏김에 알림 유실 | `finish()` 가 CAS 실패 시 false 로 빠지므로 진 워커는 마커도 알림도 안 건드린다. **순서가 이 성질을 만든다** |
| grace 정책 간섭 | `applyGrace` 는 `hasChanged`·`finish` **앞**에서 돌고 outcome 의 판정은 grace 적용 후 값이다. grace 는 `fresh.isUnknown()` 일 때만 발동하므로 더 나쁜 상태를 만들어 낼 수 없다 |
| 경로 재탐색 | 3중 — 키 두 개가 `routeRevision` 을 품고, enqueue CTE 가 확인하고, `loadTask` 가 다시 확인 |
| 10초 재계산 | 마커(쿼리 0회) + `ON CONFLICT`(안전망). 둘 다 필요 |
| 발송 중 여정 종료 | 취소는 PENDING 만. PROCESSING 은 `loadTask` 가 잡는다. 네트워크 호출 뒤 창은 못 막음 — ⑥ 이 payload 에 상태를 안 담아 앱이 다시 조회하면 수렴 |
| 회수 후 재취소 | `reclaimStale` 이 고착 PROCESSING 을 PENDING 으로 되돌리면 취소 sweep 이 이미 지난 뒤다. 그 행은 다시 claim 되고 `loadTask` 가 CANCELED 로 끝낸다 — `loadTask` 재확인이 필수인 이유 |
| `attempt_count` 소모 | claim SQL 이 올리므로 `loadTask` 가 취소한 행도 시도를 하나 쓴다. 워커가 3번 죽으면 DEAD. 버스와 동일 — 수용하고 설정 Javadoc 에 적는다 |
| 보관 | **이 표의 행은 지우지 않는다** — soft delete 도 물리 삭제도. 삭제하면 `delivery_key` 가 열려 재발송된다. 지금 규모에선 문제없음. 정리가 필요해지면 흔적(tombstone) 표를 먼저 설계해야 한다 — Phase 6 이후 과제로 `PHASE-5.md` 에 기록 |
| `Instant`/`LocalDateTime` 혼용 | `TransferConnection` 이 이미 그렇다(감사=LocalDateTime, 도메인=Instant). SQL 파라미터 이름을 `now`(Instant) / `auditNow`(LocalDateTime) 로 고정해 뒤바뀜 방지 |
| **중복 발송(최소 한 번)** | 발송이 트랜잭션 밖이라 provider 에 넘긴 직후 죽으면 회수 후 다시 간다. **막을 수 없다** — 정확히 한 번을 만들려면 provider 응답과 DB 커밋을 원자적으로 묶어야 하는데 불가능하다. ⑥ 이 payload 에 상태를 안 담고 ③ 이 collapse key 를 써서 해를 줄인다. 계약으로 명시하고 IT 로 창을 재현한다 |
| **기기 여럿** | 행 하나 = "사용자 × 채널" 이지 "토큰 하나"가 아니다. 토큰별 일시 실패 시 이미 성공한 기기에 중복이 간다 — 수용(§5.3 계약) |

---

## 8. `journey.navigation.evaluator.enabled=false` (현재 기본값)일 때

생산자가 완전히 죽는다. `JourneyDueEvaluatorScheduler` 는 `@Profile("worker")` +
`matchIfMissing` 없는 `@ConditionalOnProperty` 라 **빈 자체가 안 뜬다**. → claim 없음 → 평가 없음
→ enqueue 없음. `finish()` 의 운영 호출자는 하나뿐이라 배선 수준에서 보장된다.

계속 도는 것: V40 표(비어 있음), 취소 갈고리(빈 표에 인덱스 UPDATE 한 번 — 의도적으로 안 막음).

---

## 9. 테스트

### 준비물
- `src/test/java/.../testsupport/AbstractJourneyOutboxPostgresIT.java` —
  `AbstractBusAlertOutboxPostgresIT` 와 같은 모양(static 블록 postgres:17 +
  `@DynamicPropertySource`). init script 는 기존 `db/it/journey-it-schema.sql`(users)
- 스키마는 **실제 V35~V40 파일을 `@Sql(BEFORE_TEST_CLASS)` 로 실행**. DDL 복제본을 안 만들어
  마이그레이션과 어긋날 수 없다. `bus-alert-outbox-it-schema.sql` 을 확장하지 **않는다** —
  두 outbox 는 분리 유지
- `src/test/java/.../CountingNotificationSender.java` — `CountingTossMessengerPort` 와 같은 모양.
  호출 수, 채널별 수, 시나리오화된 transient/permanent 실패
- 하네스: `@DataJpaTest` + `@AutoConfigureTestDatabase(NONE)` + `@Testcontainers` +
  `@Transactional(propagation = NOT_SUPPORTED)` + `@Import({서비스들, Xxx.Config.class})` +
  고정 KST `Clock`

### 클래스별 검증 항목

| 클래스 | 검증 |
|---|---|
| `domain/journey/JourneyNotificationSchemaIT` | unique 가 **부분 인덱스가 아님**(soft delete 뒤에도 같은 deliveryKey 재삽입 실패) · 채널 2개/logical 1개 · CHECK 거절(scope_type·channel·버전 음수) · `checkpoint` 에 CHECK **없음** · `Instant` timestamptz 왕복 · jsonb 가 문자열 아닌 객체로 왕복 · 인덱스 6개 존재와 술어 |
| `application/journey/notification/JourneyNotificationEnqueueIT` | `COMFORTABLE→CATCHABLE` 0행 · `CATCHABLE→AT_RISK` 1행 + 마커 · AT_RISK 로 10초씩 6틱 → 여전히 1행(INSERT 시도 자체가 0회) · **`AT_RISK→MISSED` 2행, deliveryKey 다름** · `→UNKNOWN` 0행 + 마커 그대로 · 채널 2개 → 2행/logical 1개 · `routeRevision` 불일치 0행 · 비 ACTIVE 여정 0행 · 마커와 행이 함께 커밋(강제 롤백 후 둘 다 없음) · **행에 기록된 `command_version`·`evaluation_version` 이 CAS 로 올린 뒤 값과 같음** · **채널 목록이 비면 0행 + 마커 그대로**(§2.4) |
| `application/journey/notification/JourneyNotificationSupersedeIT` | **§2.3 전용.** `AT_RISK` 발송 대기 중 `MISSED` 가 생기면 앞 것이 CANCELED · 상태가 `CATCHABLE` 로 회복되면 쌓인 `AT_RISK` 가 CANCELED 되고 마커가 비워짐 · **`UNKNOWN` 에서는 마커를 안 비움** · PROCESSING 인 행은 안 건드림 |
| `application/journey/notification/JourneyNotificationLostClaimIT` | 낡은 `leaseUntil` 로 `finish()` false → 0행 **+** 마커 그대로 |
| `application/journey/notification/JourneyNotificationAtomicClaimIT` | `BusAlertAtomicClaimIT` 복제. 4스레드 × K행 → 각 행 정확히 한 번, 합 K |
| `application/journey/notification/JourneyNotificationRelayIT` | SENT · transient → PENDING(`scheduled_at = now+backoff`) · 소진 → DEAD · permanent → FAILED(재시도 없음) · 만료 → CANCELED(FAILED 아님) · 여정 종료 → CANCELED, sender 미호출 · `route_revision` 변경 → CANCELED, sender 미호출 · **MISSED(과거 도착시각)도 실제 발송됨**(만료 바닥) · `markTransientFailure` 가 비 PROCESSING 행에 no-op · **sender 가 `NotificationSendException` 이 아닌 예외(NPE)를 던져도 transient 로 분류돼 결국 DEAD 로 끝난다**(§2.6 — 무한 반복 가드) |
| `application/journey/notification/JourneyNotificationAtLeastOnceIT` | **발송 보장 계약을 문서 아닌 코드로 고정.** sender 가 성공한 직후 mark 전에 죽는 상황을 재현(mark 를 건너뛴다) → 회수 후 재claim → **같은 알림이 두 번 발송된다**. 이것이 버그가 아니라 계약임을 명시하는 테스트 |
| `application/journey/notification/JourneyNotificationCancelOnEndIT` | 3경로 전부: PENDING→CANCELED, PROCESSING 무손상, SENT 무손상. 명령 경로 취소 뒤 `commandVersion` 정상 증가 + 스냅샷 정상(`clearAutomatically=false` 회귀 가드). **만료 경로는 `expireBatch()` 가 아니라 `sweep()` 을 부른다** — 자기 호출 트랜잭션 문제를 잡는 유일한 방법(§6 ⚠). 추가로 취소 쿼리를 일부러 실패시켰을 때 만료도 함께 롤백되는지 확인 |
| `application/journey/notification/JourneyNotificationRelayWakeupIT` | 여정 enqueue 커밋이 `JourneyRelayWaker` 를 깨우고, **공유 `RelayWaker` 빈의 permit 은 그대로**(교차 wake 가드) |
| `TransferRiskNotifierTest` (단위) | 상태 순위표(UNKNOWN=2 가 CATCHABLE 위) · `NOTIFIABLE` 필터가 숫자 비교 앞 · UNKNOWN 은 마커에 안 써지고 비워지지도 않음 · INSERT 0행이어도 마커 씀 · `isUpcoming` 필터 · `enqueue-enabled=false` kill switch · 빈 채널 목록 방어 |
| `JourneyNotificationKeysTest` (단위) | 두 키 문자열 정확 값 · `deliveryKey.startsWith(logicalKey + ":")` · 최대 입력에서 255자 이내 |
| `JourneyNotificationPropertiesTest` (단위) | `minTtl <= 0` · `maxTtl < minTtl` · **`backoff × maxAttempts > minTtl`**(원안 기본값이 여기 걸린다) · **`relay.enabled=true` + `sender=noop`** 거절 |
| `JourneySchemaIT` (수정) | `@Sql` 에 V40 추가 — 드리프트 감지 |

**② 테스트에 넣지 않는 것**(⑤ 담당): lease 만료·`commandVersion`·`evaluationVersion` 발송 직전
재검증. ② 는 ACTIVE·`routeRevision`·`expiresAt` 세 가지만 확인한다.

### 실행
```
./gradlew test                                                    # 전체
./gradlew test --tests 'watoo.grd.nextroute.application.notification.*'   # 버스 회귀
```
Docker 필요(Testcontainers postgres:17).

---

## 10. 참고 파일 (기존 코드)

| 용도 | 경로 |
|---|---|
| outbox 스키마 원본 | `src/main/resources/db/migration/V28__create_bus_alert_notification.sql` |
| 마이그레이션 스타일 | `src/main/resources/db/migration/V35__create_journey.sql` |
| enqueue / claim / reclaim SQL 원본 | `domain/notification/repository/BusAlertNotificationRepository.java:20-71` |
| 상태 전이 메서드 | `domain/notification/entity/BusAlertNotification.java:76-100` |
| 트랜잭션 경계 원본 | `application/notification/service/BusAlertOutboxStateService.java:42-113` |
| relay 루프 원본 | `application/notification/service/BusAlertOutboxRelayService.java:50-154` |
| wake 배선 원본 | `application/notification/service/{RelayWaker,RelayWakeListener,OutboxEnqueuedEvent}.java`, `infrastructure/adapter/in/scheduler/notifination/BusAlertRelayLoop.java` |
| 발송 실패 분류 원본 | `application/notification/exception/TossMessengerException.java`, `infrastructure/adapter/out/api/toss/TossMessengerAdapter.java` |
| 평가기 쓰기 경로 | `application/journey/service/JourneyEvaluationScheduleWriter.java:49-63` |
| 평가기 흐름 | `application/journey/service/JourneyEvaluationService.java:80-100` |
| 판정 저장 | `application/journey/service/TransferAssessmentWriter.java:45-74` |
| 마커 컬럼 | `domain/journey/entity/TransferConnection.java:92-94`, `V35__create_journey.sql:144` |
| 상태 순위 | `application/journey/service/TransferRiskPolicy.java:83-91` |
| 종료 SQL | `domain/journey/repository/JourneySessionRepository.java:112-134` |
| 취소 쿼리 선례 | `domain/notification/repository/RouteArrivalAlertNotificationRepository.java:64-71` |
| 지표 관례 | `application/journey/observability/JourneyEvaluationMetrics.java` |
| 스키마 IT 템플릿 | `src/test/java/.../domain/journey/JourneySchemaIT.java:69-103` |
| outbox IT 베이스 | `src/test/java/.../testsupport/AbstractBusAlertOutboxPostgresIT.java` |
| 동시성 IT 원본 | `src/test/java/.../application/notification/service/BusAlertAtomicClaimIT.java` |
| 가짜 sender 원본 | `src/test/java/.../application/notification/service/CountingTossMessengerPort.java` |

---

## 11. 이 PR 에서 하지 않는 것

| 항목 | 담당 | 이 PR 이 준비해 둔 것 |
|---|---|---|
| FCM 발송 구현·채널 라우터 | ③ | `NotificationSender`·`NotificationChannelResolver` 포트, `PUSH` 채널 값 |
| N1·N3 서버 발송 금지, 체크포인트 확장, 정책 클래스 추출 | ④ | `JourneyCheckpoint` enum(DB CHECK 없음), `JourneyNotificationScope.LEG` |
| lease·`commandVersion`·`evaluationVersion` 발송 직전 재검증 | ⑤ | **V40 에 두 version 컬럼**(⑤ 에 마이그레이션이 없다) |
| payload 계약·deep link | ⑥ | `payload jsonb`, 세 version 컬럼 |
| alert-to-action 지표 | ⑦ | 최소 지표(§5.10) |
| 보관(retention) | Phase 6 이후 | 삭제 금지 규칙을 §7 에 못박음. 정리하려면 흔적 표를 먼저 설계해야 함 |
| 토큰별 발송 상태 표 | ③ 이 판단 | 행 하나 = "사용자 × 채널" 계약을 §5.3 에 명시 |

---

## 12. codex 설계 리뷰 반영 내역 (2026-08-06)

**막는 문제 2개 — 둘 다 코드로 확인함**

| # | 지적 | 반영 |
|---|---|---|
| 1 | ⑤ 가 필요한 `commandVersion`·`evaluationVersion` 을 V40 이 저장하지 않는데 ⑤ 에는 마이그레이션이 없다 | V40 에 두 컬럼 추가. enqueue CTE 가 `journey_session` 에서 집어 온다(같은 트랜잭션이라 CAS 뒤 값) |
| 2 | `JourneyLeaseExpiryService.sweep()` 이 자기 클래스의 `expireBatch()` 를 직접 불러 `@Transactional` 이 안 걸린다 → 취소 쿼리를 더하면 원자성이 깨진다 | **Step 4.5 신설**. `TransactionTemplate` 으로 감싸고 `sweep()` 을 직접 도는 IT 추가. 기존 코드의 버그이기도 하다 |

**쟁점 8개에 대한 답과 반영**

| # | 쟁점 | codex | 반영 |
|---|---|---|---|
| 1 | `timestamptz`/`Instant` | 동의 | 유지 |
| 2 | `payload jsonb` | 동의 | 유지. `null`·비객체 규칙은 §5.6(직렬화 실패 시 `null`) |
| 3 | `delivery_key` 전체 unique | 설명 부족 — 보관 정책과 모순 | **§7 의 "보관 후 삭제"를 철회**. 이 표의 행은 지우지 않는다. 정리가 필요하면 흔적 표 선설계 |
| 4 | `clearAutomatically=false` | 동의 | 유지 |
| 5 | 만료 취소를 별도 쿼리로 | 반대(위 막는 문제 2) | 별도 쿼리는 유지하되 **트랜잭션을 먼저 확보**(Step 4.5) |
| 6 | `RelayWaker` 복제 | 동의 | 유지 |
| 7 | 관리 중인 엔티티 반환 | 설명 부족 — `assessAndStore` 호출자는 반환 뒤 트랜잭션이 끝난다 | **철회**. `TransferAssessmentWriter` 를 안 고치고 알림 쪽에서 다시 조회 |
| 8 | `JourneyEvaluationOutcome` 에 `channels` | 반대 — 거짓 이지선다 | **철회**. `NotificationContext` 를 별도 인자로 |

**내가 놓쳤던 것 — 전부 반영**

| 지적 | 반영 자리 |
|---|---|
| 사용자당 기기 여럿 vs 채널 단위 성공 상태 | §5.3 `NotificationSender` 계약(행 = 사용자×채널, 토큰별 영구실패는 token 비활성화, 일시실패 중복은 수용) |
| `AT_RISK` 재시도가 `MISSED` 보다 뒤에 나갈 수 있음 | §2.3 `supersedePendingByScope` |
| 상태가 회복돼도 쌓인 알림이 30분 남음 | §2.3 회복 경로 + 마커 비움 |
| `delivery_key` 는 enqueue 만 막고 발송 중복은 못 막음 | §1 "최소 한 번" 계약, §7 위험표, `JourneyNotificationAtLeastOnceIT` |
| `backoff × maxAttempts` 와 `minTtl` 모순 | `minTtl` 60→**180초**, 검증 규칙 방향 수정 |
| 채널 이름이 문서마다 다름(`PUSH`/`FCM`) | **`FCM` → `PUSH`**. `TECHNICAL_DECISIONS:65` 가 맞다 — FCM 은 provider 이고 `user_push_token.provider`(V39)가 갖는다 |
| 빈 채널 목록이 마커만 앞당김 | §2.4 |
| `relay.enabled=true` + `sender=noop` | §2.5 부팅 거절 |
| 예상 못 한 예외가 영원히 재시도 | §2.6 `catch (Throwable)` |

---

## 13. 진행 방식

1. ~~이 PLAN.md 로 codex 논의~~ → **완료**(2026-08-06, §12)
2. **새 세션**에서 이 파일을 읽고 §3 순서대로 구현. **Step 4.5 를 Step 7 보다 먼저** 할 것
3. `/verify` + 리뷰 → PR (플랜 파일 2개 포함)
