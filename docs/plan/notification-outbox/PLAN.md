# 알림 발송 정합성 Outbox 보강 — 실행 플랜 (L티어)

> 구현 세션은 이 문서만으로 시작 가능해야 한다. 전제 사실은 전부 검증된 코드 참조 포함.

## 배경 / 목표

알림 발송 = DB 상태 변경 + 토스 API 호출의 dual-write. 토스 API는 멱등키 미지원
(`TossMessengerAdapter.java:44` — body에 templateSetCode+context만) → exactly-once 불가.
**보장 수준 선언: at-least-once + 상태 기반 dedup.** 산출물 = 코드 보강 + crash window 장애주입 IT + 보장 문서.

현황(검증됨):
- 경로 알림 v2: 이미 절반 outbox. `route_arrival_alert_notification`(V23) + `dedup_key` unique
  + 조건부 UPDATE 원자 claim(`RouteArrivalAlertNotificationRepository.claimForProcessing:33`).
  갭 = FOR UPDATE SKIP LOCKED 부재, DEAD 상태 부재(FAILED가 permanent와 재시도 소진 혼용).
- 버스 알림: outbox 미분리. 구독 테이블(`bus_arrival_alert`, V19~21)에 발송 상태 혼재.
  claim이 read-then-write 비원자(`BusAlertStateService.claim:35` findById→상태검사→markProcessing)
  → 다중 인스턴스 시 중복 발송. 발송 멱등키 없음.
- 실패 분류는 정립돼 있음: `TossMessengerException(permanent boolean)`,
  transient 3회 소진 → FAILED, 백오프 60s (`RouteAlertDispatchStateService.markTransientFailure:139`).
- reclaim: 경로 PROCESSING 5분 고착 → PENDING, 버스 3분.
- ShedLock/분산락 전무. @Scheduled fixedDelay 단일 인스턴스 가정.
- 마이그레이션 V26까지. 다음 V27.
- IT 관례: `BusArrivalAlertRepositoryIT` = @DataJpaTest + @AutoConfigureTestDatabase(replace=NONE)
  + @Testcontainers + PostgreSQLContainer("postgres:17") + flyway off
  + 전용 init script `src/test/resources/db/it/bus-alert-it-schema.sql` (V1 baseline postgis 충돌 회피).
- admin API는 `AdminApiKeyFilter`(X-Admin-Key, PR #58)로 보호됨.

## PR 분해

| 순서 | 브랜치 | 내용 | 마이그레이션 |
|---|---|---|---|
| PR-1 | `feat/route-alert-outbox-hardening` | 경로 v2: SKIP LOCKED 배치 claim + DEAD + admin requeue + IT | V27 |
| PR-2 | `feat/bus-alert-outbox` | 버스 outbox 분리(flag, 기본 off) + OutboxRetryPolicy 공통화 + IT | V28 |
| PR-3 | `docs/notification-delivery-guarantees` | 보장 문서 | 없음 |
| PR-4(후속) | — | flag 기본 on + 구 버스 발송 경로 제거 + route 스케줄러 runner retrofit | 안정화 후 |
| PR-5(독립, S) | `chore/notification-scheduler-isolation` | 알림 전용 TaskScheduler 격리 (지연 방지) | 없음 |

각 PR은 fresh main 독립 브랜치. 머지 순서 PR-1 → PR-2 고정(Flyway 번호 + 공통 추상 의존).
PR-3은 독립이나 PR-2 머지 후 권장(테스트 링크 정확성). 플랜 파일(docs/plan/notification-outbox/)은 PR-1에 포함.

---

## PR-1: 경로 v2 보강

### 작업 순서

- [ ] 1. `V27__route_alert_notification_dead_status.sql`
- [ ] 2. `RouteNotificationStatus`에 DEAD 추가 + 엔티티 전이 메서드
- [ ] 3. 리포지토리: claimBatch(SKIP LOCKED) + cancelStaleDispatchable + requeueDead
- [ ] 4. `RouteAlertDispatchStateService` 재편 (claimBatch/loadTask/DEAD 분기)
- [ ] 5. `RouteAlertDispatchScheduler` 루프 재편
- [ ] 6. admin controller (DEAD 조회/requeue)
- [ ] 7. 단위 테스트 갱신 + `route-alert-it-schema.sql` + `RouteAlertNotificationOutboxIT`
- [ ] 8. `./gradlew test` 전체 통과

### 1. V27 (src/main/resources/db/migration/V27__route_alert_notification_dead_status.sql)

```sql
-- DEAD = 재시도 소진(수동 개입 대기, requeue 가능). FAILED 는 permanent 실패 전용으로 의미 축소.
ALTER TABLE route_arrival_alert_notification
    DROP CONSTRAINT route_alert_notification_status_check;
ALTER TABLE route_arrival_alert_notification
    ADD CONSTRAINT route_alert_notification_status_check
    CHECK (status IN ('PENDING','PROCESSING','SENT','FAILED','DEAD','CANCELED'));
CREATE INDEX idx_route_alert_notification_dead
    ON route_arrival_alert_notification (created_at) WHERE status = 'DEAD';
ALTER TABLE route_arrival_alert_notification
    ADD COLUMN requeue_count integer NOT NULL DEFAULT 0,
    ADD COLUMN last_requeued_at timestamp(6);
```
주의: 실제 기존 제약 이름은 V23 DDL에서 확인 후 일치시킬 것.

### 2. 엔티티/enum

- `domain/notification/entity/RouteNotificationStatus.java`: `DEAD` 추가. javadoc에 FAILED/DEAD 의미 구분.
- `domain/notification/entity/RouteArrivalAlertNotification.java`: `markDead(String reason, LocalDateTime now)`,
  `requeue(LocalDateTime now)` (status=PENDING, scheduledAt=now, attemptCount=0, lastFailureReason=null).

### 3. 리포지토리 (domain/notification/repository/RouteArrivalAlertNotificationRepository.java)

기존 `claimForProcessing:33`, `findDispatchable` → 신규 배치 claim으로 대체(사용처 이관 후 제거).

```java
// 반드시 @Transactional 안에서 호출. UPDATE...RETURNING 이라 non-@Modifying
// (@Modifying 은 update-count 경로라 List<Long> 반환과 충돌 — codex 리뷰 확인).
// FOR UPDATE OF 는 alias 로 지정 — outbox 행만 잠금(부모 alert 행 잠그면 checker 낙관락과 경합).
@Query(value = """
    WITH picked AS (
        SELECT o.id FROM route_arrival_alert_notification o
        WHERE o.status = 'PENDING' AND o.scheduled_at <= :now AND o.deleted_at IS NULL
          AND EXISTS (SELECT 1 FROM route_arrival_alert a WHERE a.id = o.alert_id
                      AND a.status = 'ACTIVE' AND a.deleted_at IS NULL)
        ORDER BY o.scheduled_at LIMIT :batchSize
        FOR UPDATE OF o SKIP LOCKED)
    UPDATE route_arrival_alert_notification n
    SET status = 'PROCESSING', processing_started_at = :now,
        attempt_count = n.attempt_count + 1, updated_at = :now
    FROM picked WHERE n.id = picked.id
    RETURNING n.id
    """, nativeQuery = true)
List<Long> claimBatchForProcessing(LocalDateTime now, int batchSize);
```

PG17에서 CTE 내 `FOR UPDATE OF <alias>` + EXISTS 조합 의미론은 **IT 첫 케이스로 로컬 검증**
(scalar 반환/락 범위) — 통과 못 하면 CTE 분해(SELECT FOR UPDATE SKIP LOCKED 후 IN UPDATE, 같은 tx) 폴백.

stale PENDING 정리 (기존 `RouteAlertDispatchStateService.claimForDispatch:58`의 가드 4조건을 SQL로 1:1 이전 —
비-ACTIVE / soft-delete / target 과거 / DEPARTURE인데 phase≠WAITING_DEPARTURE):

```java
@Modifying
@Query(value = """
    UPDATE route_arrival_alert_notification n
    SET status = 'CANCELED', updated_at = :now
    FROM route_arrival_alert a
    WHERE n.alert_id = a.id AND n.status = 'PENDING' AND n.scheduled_at <= :now AND n.deleted_at IS NULL
      AND (a.status <> 'ACTIVE' OR a.deleted_at IS NOT NULL OR a.target_arrival_at < :now
           OR (n.type = 'DEPARTURE' AND a.phase <> 'WAITING_DEPARTURE'))
    """, nativeQuery = true)
int cancelStaleDispatchable(LocalDateTime now);
```

DEAD requeue (**codex 반영: attempt 보존이 기본** — 리셋하면 반복 requeue가 무한 재시도 예산이 됨.
requeue는 "1회 더 시도" 의미. 전체 예산 재부여는 `resetAttempts=true` 명시 옵션):

```java
@Modifying
@Query(value = """
    UPDATE route_arrival_alert_notification n
    SET status = 'PENDING', scheduled_at = :now,
        attempt_count = CASE WHEN :resetAttempts THEN 0 ELSE n.attempt_count - 1 END,
        requeue_count = n.requeue_count + 1, last_requeued_at = :now, updated_at = :now
    FROM route_arrival_alert a
    WHERE n.alert_id = a.id AND n.status = 'DEAD' AND n.id IN (:ids)
      AND n.requeue_count < :maxRequeue
      AND a.status = 'ACTIVE' AND a.deleted_at IS NULL AND a.target_arrival_at > :now
    """, nativeQuery = true)
int requeueDead(List<Long> ids, LocalDateTime now, boolean resetAttempts, int maxRequeue);
```

(`attempt_count - 1`: 다음 claim에서 +1 되어 원 카운트 복원 = 정확히 1회 추가 시도 후 max 초과 시 다시 DEAD.)
V27에 `requeue_count integer not null default 0`, `last_requeued_at timestamp(6)` 컬럼 추가. maxRequeue 기본 3(프로퍼티).

+ `List<RouteArrivalAlertNotification> findByStatusOrderByCreatedAtDesc(RouteNotificationStatus status)` (DEAD 조회).

### 4. RouteAlertDispatchStateService (application/notification/service/RouteAlertDispatchStateService.java)

- 신규 `claimBatch(LocalDateTime now, int batchSize)`: 단일 @Transactional —
  `cancelStaleDispatchable(now)` → `claimBatchForProcessing(now, batchSize)` → `List<Long>` 반환.
  **이 tx 안에서 엔티티 로드 금지(id만).**
- 신규 `loadTask(Long id)`: 별도 tx(readOnly 아님 — CANCELED 전이 가능). notification+alert 로드,
  status==PROCESSING 확인, 부모 비활성이면 `markCanceled` 후 empty 반환.
  기존 `claimForDispatch:58`의 context 빌드 로직 이관.
- `markTransientFailure:139` 수정: `attemptCount >= maxAttempts` → `markFailed("max-retry")` 대신 `markDead(reason, now)`.
  (attempt 증가는 claim에서 하므로 기존 증가 로직 위치 확인 후 중복 증가 제거.)
- `markDepartureSent:105`의 부모 alert 원자 전이(`recordNotified`)와 `isInactive` 이중 가드는 그대로 유지.
- 기존 `claimForDispatch`, `findDispatchable` 사용 코드는 새 경로로 이관 후 제거.

### 5. RouteAlertDispatchScheduler (application/notification/service/RouteAlertDispatchScheduler.java:40)

```
run(): reclaim → ids = stateService.claimBatch(now, batchSize)   // 커밋됨
       for id in ids:
           task = stateService.loadTask(id); if empty → continue
           send(task)                                            // 토스, tx 밖
           markXxx(...)                                          // REQUIRED tx (claim tx는 이미 닫힘)
```
notification 단위 try/catch 격리(기존 `:51-57` 패턴) 유지.

### 6. admin (infrastructure/adapter/in/api/admin/RouteAlertNotificationAdminController.java 신규)

기존 admin 컨트롤러(`BusArrivalLabelAdminController.java:23`) 관례 미러:
- `GET /api/admin/route-alert-notifications/dead` → id/dedupKey/attemptCount/lastFailureReason/createdAt 목록
- `POST /api/admin/route-alert-notifications/dead/requeue` body `{"ids":[...]}` (생략 시 전체 DEAD) →
  `{requeued: n, skipped: [...]}` (부모 비활성은 skip)

### 7. 테스트

단위(`RouteAlertDispatchStateServiceTest` 갱신): transient 소진→DEAD, permanent→FAILED 유지, requeue 리셋.

IT 신규 `src/test/resources/db/it/route-alert-it-schema.sql`:
V23+V24+V26+V27 합산 DDL 복제. 헤더 주석 "복제 원본: V23,V24,V26,V27 — 마이그레이션 추가 시 이 파일 갱신".

IT 신규 `src/test/java/watoo/grd/nextroute/domain/notification/RouteAlertNotificationOutboxIT.java`
(BusArrivalAlertRepositoryIT 패턴: @DataJpaTest+replace=NONE+Testcontainers+flyway off+initScript):
- `concurrentClaim_disjointWinners`: PENDING N행, 스레드 2개(CountDownLatch 동기화, 각자 TransactionTemplate)
  claimBatch 동시 실행 → 반환 id 집합 disjoint, 합집합=전체, attempt_count 전부 1
- `crashAfterClaim_reclaimThenRedeliverOnce`: claim 후 mark 없이 → reclaimStale(5m 경과 시뮬:
  processing_started_at 직접 과거로 UPDATE) → 재claim → attempt=2, 최종 SENT 1행 (유실 0)
- `crashAfterSendBeforeMark_duplicateDelivery`: claim → markSent 미호출 → reclaim → 재claim 성공
  = **중복 발송 실증** (문서 근거용, 주석에 명시)
- `dedupKey_uniqueViolation`: 동일 dedup_key 2회 insert → DataIntegrityViolationException
- `deadRequeue_roundTrip`: DEAD 전이 → requeueDead → PENDING/attempt=0. 부모 비활성이면 requeue 0건
- `cancelStaleDispatchable_fourConditions`: 조건별 4케이스 CANCELED 전이

### 검증

`./gradlew test` 통과 (Testcontainers → 로컬 docker 필요). 기존 `RouteAlertDispatchStateServiceTest`,
`RouteAlertCheckProcessorTest` 등 회귀 없음 확인.

---

## PR-2: 버스 outbox 분리

### 작업 순서

- [ ] 1. `V28__create_bus_alert_notification.sql`
- [ ] 2. 엔티티/enum/리포지토리 (route 미러)
- [ ] 3. `OutboxRetryPolicy` 공통 추상 + route 치환
- [ ] 4. `BusAlertOutboxEnqueueService` (+구독 `handoffToOutbox` 쿼리)
- [ ] 5. `BusAlertOutboxStateService` + `BusAlertOutboxRelayScheduler`
- [ ] 6. `BusAlertDispatchService`/`BusAlertStateService` flag 분기
- [ ] 7. admin controller
- [ ] 8. 테스트 (단위 + `bus-alert-outbox-it-schema.sql` + `BusAlertOutboxIT`)
- [ ] 9. `./gradlew test` 통과

### 핵심 설계 결정 (구현 시 준수)

- **dedup_key = `BUS_ARRIVAL:{alertId}`**: 버스는 one-shot(SENT=구독 종결) → 구독당 outbox 행 최대 1개.
  perUserCycleCap(`BusAlertDispatchService`)은 enqueue 시점 rate cap으로 유지 — dedup 단위 아님.
- **구독 테이블 스키마 무변경, 상태 재해석** (flag on 시): PENDING=활성 폴링 대상,
  PROCESSING=outbox 인계됨(단일 승자), SENT/FAILED=outbox 종결과 원자 전이. EXPIRED/CANCELED 기존 의미.
- **flag on 시 구독 reclaimStale 반드시 skip** (`BusAlertStateService.expireAndReclaim` 분기):
  안 끄면 인계된 구독 PENDING 복귀 → 재enqueue → unique 롤백 무한루프. 재시도 소유권 = outbox reclaim 단독.
  (expirePendingOverdue는 PENDING 대상이라 유지.)
- v2 테이블과 통합 금지(FK 다형성/상태머신 상이/회귀 반경). 공통화는 코드만:
  - `application/notification/outbox/OutboxRetryPolicy.java`: `decide(attemptCount, maxAttempts, permanent)
    → RETRY(backoffUntil) | DEAD | FAILED` 순수 계산. route `RouteAlertDispatchStateService`도 치환(분기 한 줄).
  - `OutboxDispatchRunner`(루프 템플릿)는 diff가 기계적일 때만 이번 PR, 아니면 PR-4 이월.

### V28 (src/main/resources/db/migration/V28__create_bus_alert_notification.sql)

```sql
-- 버스 알림 발송 outbox. 구독(bus_arrival_alert)에서 발송 라이프사이클 분리.
CREATE TABLE bus_alert_notification (
    id                    bigint generated by default as identity primary key,
    created_at            timestamp(6),
    updated_at            timestamp(6),
    deleted_at            timestamp(6),
    alert_id              bigint       not null references bus_arrival_alert,
    dedup_key             varchar(255) not null,
    status                varchar(255) not null
        constraint bus_alert_notification_status_check
            check (status in ('PENDING','PROCESSING','SENT','FAILED','DEAD','CANCELED')),
    scheduled_at          timestamp(6) not null,
    payload               varchar(1000),
    attempt_count         integer      not null default 0,
    requeue_count         integer      not null default 0,
    last_requeued_at      timestamp(6),
    processing_started_at timestamp(6),
    sent_at               timestamp(6),
    last_failure_reason   varchar(500)
);
CREATE UNIQUE INDEX uq_bus_alert_notification_dedup ON bus_alert_notification (dedup_key);
CREATE INDEX idx_bus_alert_notification_status_scheduled
    ON bus_alert_notification (status, scheduled_at) WHERE deleted_at IS NULL;
CREATE INDEX idx_bus_alert_notification_dead
    ON bus_alert_notification (created_at) WHERE status = 'DEAD';
```

payload = enqueue 시점 스냅샷 JSON `{"routeName":..,"stopName":..,"arrivalMin":N}` —
relay가 발송 시 재계산하지 않음(one-shot 계약: "곧 도착" 판정 시점 값).

### enqueue (application/notification/service/BusAlertOutboxEnqueueService.java 신규)

**codex 반영 — 선확인+예외흡수 설계 폐기**: unique 위반이 flush/commit 시점에 터지면 같은 tx의 구독
UPDATE까지 롤백되고 tx가 rollback-only가 돼 "catch 후 skip"이 성립하지 않음. READ COMMITTED에서 선확인은
INSERT와 원자적이지 않음. → **claim+insert를 단일 SQL로 묶고 ON CONFLICT DO NOTHING**:

```java
// BusAlertNotificationRepository. non-@Modifying (RETURNING), @Transactional 안에서 호출.
@Query(value = """
    WITH claimed AS (
        UPDATE bus_arrival_alert a
        SET status = 'PROCESSING', processing_started_at = :now
        WHERE a.id = :alertId AND a.status = 'PENDING' AND a.deleted_at IS NULL
        RETURNING a.id)
    INSERT INTO bus_alert_notification
        (alert_id, dedup_key, status, scheduled_at, payload, attempt_count, created_at, updated_at)
    SELECT c.id, :dedupKey, 'PENDING', :now, :payload, 0, :now, :now FROM claimed
    ON CONFLICT (dedup_key) DO NOTHING
    RETURNING id
    """, nativeQuery = true)
List<Long> enqueue(Long alertId, String dedupKey, String payload, LocalDateTime now);
```

결과 해석 (enqueue 서비스):
- id 반환 = 정상 enqueue
- 빈 결과 + 구독이 PENDING 아니었음 = 다른 승자/이미 인계, skip
- 빈 결과 + conflict (구독은 PROCESSING 전이됨) = outbox 행 기존 존재 → **기존 outbox 상태별 보정**:
  PENDING/PROCESSING이면 정상(이미 인계됨), SENT/FAILED/CANCELED/DEAD면 비정상 조합 — 로그 + 구독 상태를
  outbox 종결 상태에 맞춰 보정(SENT→구독 SENT 등). 같은 tx 내 처리.

호출 지점: `BusAlertDispatchService.processAlert:90` — flag on이면
`stateService.claim(id)`+발송 대신 `enqueueService.enqueue(alert, targetSeconds)` 하고 끝(발송은 relay 소관).
flag off면 기존 경로 그대로. perUserCycleCap 카운트는 enqueue 성공 기준으로 유지.

### relay (infrastructure/adapter/in/scheduler/BusAlertOutboxRelayScheduler.java 신규)

`@ConditionalOnProperty(prefix="notification.bus-arrival", name="outbox-enabled", havingValue="true")`
(기본 off), `@Scheduled(fixedDelayString="${notification.bus-arrival.outbox-relay-interval-ms:15000}")`.
루프 = PR-1 스케줄러와 동일 골격: reclaim → claimBatch(SKIP LOCKED, 부모 가드는
`a.status='PROCESSING'`) → loadTask → 토스 발송(tx 밖) → mark.

`BusAlertOutboxStateService` (신규):
- `loadTask`: **부모 재검증 필수(codex 반영)** — `alert.status==PROCESSING && deleted_at is null && expires_at>now`
  아니면 outbox CANCELED 후 skip. 사용자 취소(`BusAlertService.cancel`은 PROCESSING도 CANCELED 가능)와 경합 대비
- `markSent`: outbox SENT + 구독 `markSent` **같은 tx**. tx 안에서 부모 재검증 반복(이중 가드, route
  `markDepartureSent`의 isInactive 가드 미러) — **CANCELED 구독을 SENT로 되살리는 것 금지**, 그 경우 outbox만 SENT-종결 기록
- `markTransientFailure`: OutboxRetryPolicy → RETRY(releaseToPending, scheduledAt=now+backoff 60s) | DEAD
- `markPermanentFailure`: outbox FAILED + 구독 FAILED 같은 tx (부모 재검증 동일)
- `reclaimStale`: PROCESSING 5분 고착 → PENDING
- `cancelStale`: 부모가 terminal(SENT/FAILED/CANCELED/EXPIRED)/soft-delete/만료인 outbox PENDING → CANCELED (relay 주기 내 실행)
- **만료 스윕(codex 반영)**: outbox DEAD 또는 PROCESSING 소유 구독도 `expires_at < now`면
  outbox CANCELED + 구독 EXPIRED로 닫음 — 방치된 DEAD가 활성 unique 슬롯 점유하는 것 방지
- DEAD requeue: PR-1과 동일 규칙(attempt 보존 기본, requeue_count cap). 구독 expires_at 경과 시 거부 → 만료 스윕과 동일 처리.

active-hours 게이트(05:00~01:00, `BusArrivalAlertScheduler:25` 참조)는 relay에도 동일 적용.

### admin

`BusAlertNotificationAdminController` — PR-1 미러 (`/api/admin/bus-alert-notifications/dead[/requeue]`).

### 테스트

단위: enqueue 결과 3분기 해석(정상/타승자/conflict 보정), OutboxRetryPolicy 3분기, expireAndReclaim flag 분기.
IT `BusAlertOutboxIT` + `db/it/bus-alert-outbox-it-schema.sql`(기존 bus-alert-it-schema.sql + V28, 헤더에 원본 명시):
- 동시 enqueue 2스레드 → CTE claim 단일 승자, outbox 정확히 1행
- ON CONFLICT 경로: 기존 outbox 행 존재 시 enqueue가 빈 결과 + 구독/outbox 상태 보정 확인
- 동시 claim disjoint / claim 후 crash → reclaim → 재발송 1회 / mark 전 crash → 중복 실증
- flag on에서 구독 PROCESSING이 구독 reclaim에 안 걸림 (무한루프 회귀 방지)
- markSent 원자성: tx 실패 시 outbox/구독 둘 다 미전이
- **발송 중 사용자 취소 경합**: loadTask 후 구독 CANCELED → markSent가 구독 안 되살림, outbox 종결만
- **만료 스윕**: DEAD outbox + expires_at 경과 구독 → outbox CANCELED + 구독 EXPIRED

### 검증

`./gradlew test`. 로컬 E2E: compose 기동 + flag on → 구독 생성 → relay 로그/DB 전이 확인.

---

## PR-3: docs/notification-delivery-guarantees.md

1. 보장 선언: at-least-once + outbox dedup(unique dedup_key + 원자 claim). exactly-once 불가 근거 = 토스 멱등키 부재.
2. crash window 표 (각 행에 IT 클래스#메서드 링크):

| Window | 결과 | 복구 | 중복/유실 |
|---|---|---|---|
| enqueue tx 커밋 전 | 행 없음 | 다음 사이클 재판정 | 없음 |
| claim 후 발송 전 | PROCESSING 고착 | reclaim(5m)→PENDING→재발송 | 없음 |
| 발송 후 mark 전 | PROCESSING 고착 | reclaim→재발송 | **중복(제거 불가)** |
| mark 후 | SENT 종결 | — | 없음 |

3. DEAD vs FAILED 의미, admin requeue 운영 절차.
4. 동시성 모델: SKIP LOCKED 다중 인스턴스 claim 안전. 한계 명시: perUserCycleCap 인스턴스-로컬(×N),
   ShedLock 부재, claimTimeout(5m) > 발송 최대소요 가드레일(미준수 시 조기 reclaim → 중복 증가).
5. 버스 flag 롤백/재-on 절차 (**codex 반영 — "고아 outbox 무해" 서술 폐기**):
   구독 PROCESSING이 "구 경로 claim"과 "outbox 인계" 양쪽 의미라 owner 구분 불가 →
   off→재-on 시 잔존 outbox PENDING을 relay가 집어 구 경로 발송과 **이중 발송 가능**.
   절차 강제로 해소 (스키마에 owner 컬럼 추가하는 대안은 기각 — 구독 테이블 무변경 원칙):
   - **off 절차**: flag off 배포 → 구독 PROCESSING drain 대기(구 reclaim 3분 + 1사이클) 후 안정
   - **재-on 절차**: 구 스케줄러 발송 정지 확인 → 이전 epoch의 outbox PENDING/PROCESSING 전부
     CANCELED 처리(admin SQL) → flag on. 이 순서 안 지키면 이중 발송 — 문서에 필수 절차로 명시
   - cancelStale은 부모 terminal 정리용이며 owner 모호성의 해결책이 아님을 명시

---

## PR-5: 알림 스케줄러 격리 (독립 S티어 — outbox와 무의존, 아무 때나)

브랜치: `chore/notification-scheduler-isolation`

**문제**: `spring.task.scheduling.pool.size: 3` (application.yaml:14-17)에 @Scheduled 15개 공유.
외부 API read timeout 30s짜리 collector들(버스 도착/위치·날씨·polyline)이 매분 동시 실행되면
스레드 3개 순간 고갈 → 15s 알림 relay/check가 큐 대기 = 알림 밀림.

**해법 = 자원 격리** (풀 증설만으론 불충분):
- 알림 계열 job(route relay/check/spawner, bus relay, bus 알림 폴러)을 전용 `TaskScheduler` 빈으로 분리
  (`notificationTaskScheduler`, 스레드 2, thread name prefix `noti-sched-`)
- `SchedulingConfigurer`로 job→executor 매핑 or 알림 스케줄러 클래스들이 전용 빈 참조
- 공용 풀 3→5 소폭 증설
- 정합성 영향 없음: 같은 job은 여전히 fixedDelay 비중첩. 병렬 워커 도입 시에도 outbox 원자 claim이
  스레드 수와 무관하게 정합성 보장(설계 문서에 명시, PR-3 §4와 연결)

**검증**: 격리 전후 relay 실행 간격 편차 로그(마지막 실행 timestamp 간격) — 장시간 배치 강제 실행 중
알림 relay 간격이 15s±ε 유지되는지. 이 수치가 "스케줄러 경합 → 격리" 보조 포폴 재료.

## 구현 함정 (전 PR 공통 — 반드시 읽을 것)

1. `UPDATE...RETURNING`은 non-@Modifying native + @Transactional 필수. claim tx에서 엔티티 로드 금지(id만 취급).
2. **claim tx 열린 채 REQUIRES_NEW mark 호출 금지** — 커넥션 2개 동시 점유 → 풀 고갈. claim 커밋 후 mark는 REQUIRED.
3. reclaim은 SKIP LOCKED가 대체하지 않음(락은 tx 커밋 시 해제, crash 소유권은 status+timestamp) — 필수 유지.
4. IT init script는 DDL 복제 방식 — 마이그레이션 추가 시 스크립트 동기 갱신, 헤더에 복제 원본 버전 명시.
5. `FOR UPDATE OF <outbox테이블>` 누락 시 EXISTS 부모 행까지 잠김 → checker 낙관락 경합.
6. V27의 기존 CHECK 제약 이름은 V23 실제 DDL에서 확인(추정 금지).
7. attempt_count 증가 위치가 claim으로 이동 — 기존 mark류의 증가 로직과 중복되지 않게 정리.
8. (codex) PG17에서 CTE 내 `FOR UPDATE OF <alias>` + EXISTS 의미론은 IT 첫 케이스로 검증 — 실패 시 CTE 분해 폴백.
9. (codex) enqueue는 단일 SQL(CTE claim + INSERT ON CONFLICT) — 선확인/예외흡수 패턴 금지(rollback-only 함정).
10. (codex) bus mark류는 전부 부모 구독 재검증 — CANCELED 구독을 SENT로 되살리기 금지.

## 리스크/롤백

- V27/V28 additive → 스키마 롤백 불요. DEAD 행은 구 코드에 미인식(방치=안전).
- 경로 v2: `dispatch-enabled` flag off로 발송 중지 가능. 버스: `outbox-enabled` off 시 구 경로 복귀.
- prod 배포 순서: PR-1(경로 flag 상태 확인) → PR-2 flag off 배포 → 저트래픽 시간 flag on → DEAD/재시도 모니터링.

## L티어 절차

1. ~~플랜 승인~~ (완료)
2. ~~codex 논의~~ (완료 2026-07-09 — 반박 4건 본 문서 반영: enqueue 단일 SQL 전환, flag 재-on 절차 강제,
   DEAD requeue attempt 보존+cap, bus mark 부모 재검증+만료 스윕. 동의 2건: SKIP LOCKED 방향, dedup_key=alertId)
3. 구현은 새 세션에서 이 문서 읽고 PR-1부터. 각 PR 완료 시 /verify + 리뷰
