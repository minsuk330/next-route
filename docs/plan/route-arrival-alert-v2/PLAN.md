# Route Arrival Alert v2 — 에이전트 실행 플랜 (L티어)

목표 도착시각 기반 출발 알림 v2. 기존 "적응형 반복 재탐색" 모델 폐기.
새 모델: **baseline search(등록) → first check → DEPARTURE 발송 → final check → optional CHANGE 1회 → DONE**.

전제: PR#52(main 머지) + 2026-07-03 로컬 리팩터(min totalTime, 필드 rename, EARLIER_DEPARTURE 제거)가 base.
V23 마이그레이션은 **prod 미적용 확정**(사용자 확답) → 파일 직접 재작성(신규 버전 금지). untracked convention 유지.

> **⚠️ V23 재작성 전 필수 가드 (codex 치명 지적 #5)**: `ddl-auto: validate` + Flyway 활성이라, 어떤 환경(로컬/CI/스테이징)이든 기존 V23을 **이미 적용한 DB가 있으면 checksum 불일치로 부팅 실패**. 재작성 착수 전:
> 1. 대상 DB들 `SELECT version, checksum, success FROM flyway_schema_history WHERE version='23'` 조회. row 없음 확인 = 안전.
> 2. row 있으면 → 직접 재작성 금지. 해당 DB에서 `route_arrival_alert*` DROP 후 재적용하거나, `flyway repair`. 여의치 않으면 **V24 신규 마이그레이션으로 전환**(ALTER).
> 3. 로컬 개발 DB는 v1 V23 이미 적용됐을 가능성 높음 → clean drop 후 부팅 확인 스텝을 PR-A 완료 기준에 포함.

---

## 0. 확정 설계 결정 (근거 포함)

1. **Evaluator 2모드**
   - `STATIC` (baseline/등록 시): 실시간·모델 예측 **무시**. ODsay `sectionTime` + 도보 실측 `walkTotalTimeSeconds` + **버스 구간 정적 대기 `term/2`** 합산. 등록은 출발 몇 시간 전일 수 있어 그 순간의 실시간 대기는 노이즈 → baseline 오염 방지.
     - 정적 대기 근거: ODsay sectionTime엔 환승 대기 없음 → 대기 0 가정 시 baseline 체계적 과소평가 → 실제 leaveBy가 baseline보다 앞 → **first check 시점이 늦어 출발시각을 이미 지나는 사고**. `BusRoute.term`(배차간격 분, 서울시 API 정적 적재)의 절반 = 평균 대기 기대값. 조회는 기존 `TransferStopResolver.resolveRoute()`가 이미 들고 있는 BusRoute row에 편승(`RouteResolution`에 term 필드 추가) — **추가 쿼리 0, 외부 API 0**. resolver 미해석(경기/인천)·term null → `defaultHeadwayMinutes(10)/2`.
     - **term/2 편향 보정 (codex 우려 #1)**: `term/2`는 균등 배차 가정 평균. headway 변동 크면 실제 기대대기 > term/2 → baseline 낙관 → first check 지연 사고. → `firstCheckAt` 여유를 **term 비례 가변**으로: `safetyMargin = firstCheckBaseMarginMinutes(3) + min(경로 버스구간 max(term) × firstCheckHeadwayFactor(0.5), firstCheckMaxMarginMinutes(20))`. 고배차 노선일수록 first check를 더 일찍 당겨 baseline 오차 흡수. 상한(20분)은 "너무 일러 실시간 미확정 → NO_VEHICLE" 방지.
   - `REALTIME` (first/final check): enricher 결과 소비한 풀 계산. check는 출발 3~18분 전이라 실시간 유효.
1-b. **baseline은 enricher 스킵**: 등록 시 `search()` 풀 호출하면 transfer enricher가 실시간 버스 API fan-out 실행 후 결과 버림. `RouteSearchService`에 transfer 보강 생략 변형(`searchWithoutRealtime()`) 추가 — 폴리라인·도보 보강(walkTotalTimeSeconds는 STATIC에 필요)은 유지, `transferArrivalEnricher.enrich()`만 생략.
2. **Enricher 재사용, 타임라인 재계산 금지**: `TransferArrivalEnricher`가 이미 wave 기반 `estimatedUserArrivalAt`(도보 실측 포함, 2번째 환승은 직전 버스 predictedArrivalAt 기준)으로 `waitSeconds`를 계산해 `SubPathResult.transferArrivals`로 내려줌. evaluator는 합산만:
   - 도보 = `walkTotalTimeSeconds ?: sectionTime×60`
   - 지하철 = `sectionTime×60`
   - 버스 = `min(AVAILABLE/APPROXIMATE lane).waitSeconds + sectionTime×60`
   - `TransferArrival` AVAILABLE은 항상 boardable(사전 필터+clamp) → 음수 waitSeconds 가드 불필요.
3. **후보 선택**: AVAILABLE 중 최소 evaluated duration → 없으면 UNKNOWN 중 최소 → 전부 OUT_OF_SERVICE면 운행불가.
4. **final check 실행 조건 (codex 우려 #4 반영)**: DEPARTURE **발송 완료 후**에만. 생략은 first check가 스냅샷에 기록한 `finalCheckEligibility`로 판정:
   - `ELIGIBLE` — 버스 구간 있고 모든 승차지점 resolve 성공 → final check 실행
   - `NO_BUS` — 버스 구간 없음(지하철·도보 전용) → 생략(정상). 지하철 실시간 소스 없어 재검증 가치 0(§8 한계).
   - `UNRESOLVED_BUS` — 버스 있으나 stopId/routeId/seq resolve 실패 → 생략 + **reliability LOW 마킹**("안정적"과 구분, 재검증 불가 흔적).
   - 추가 생략: `userLeadMinutes ≤ finalCheckLeadMinutes(5)` — final check 시각(rec−5)이 발송 시각(rec−userLead) 이상이면 발송 전 재검증 = 순서붕괴. **경계 `≤`** (userLead=5, finalLead=5 → 동시각 → 생략). userLead=10이면 간격 5분 확보 → 실행.
4-b. **final check는 ODsay 재검색 없음 (타깃 재조회)**: 출발 5분 전 경로 스위칭은 UX상 무의미 → 전체 재탐색 불필요. first check에서 **선택 경로 스냅샷**(고정 소요 합 + 승차 지점 목록 + eligibility)을 alert에 저장, final check는 그 승차 지점들의 버스 도착만 `SearchTimeBusQueryPort`로 재조회(collector 캐시 프라이밍 PR#51 덕에 히트 확률 높음). ODsay 0회, 전 경로 fan-out 없음. 트레이드오프: 대안 경로 발견 불가 — 선택 경로 악화 시 CHANGE로 leaveBy만 당김(수용).
4-c. **스냅샷 invalidation + 과거 clamp (codex 우려 #2)**: final check가 스냅샷을 맹신하지 않도록 재조회 시 유효성 검사:
   - boarding 도착응답에 해당 route 없음(폐지/우회) → `SNAPSHOT_STALE`
   - 서비스윈도 재판정: 예상 승차시각 > 막차 → `OUT_OF_SERVICE`
   - 조회 outcome ≠ OK(BLOCKED/LIMITED/ERROR) → 그 boarding만 term/2 유지 + reliability 하향(계속)
   - `SNAPSHOT_STALE`/`OUT_OF_SERVICE` 임계 초과 → final check 신뢰 불가 → **CHANGE 안 보냄**(혼란 방지), reliability=LOW로 DONE, lastNotified 값 유지(이미 보낸 출발알림이 최선).
   - `finalRecommendedLeaveByAt ≤ now`(소요 급증으로 이미 출발했어야 함) → 표시값 `now`로 clamp + `urgentDeparture=true`(CHANGE payload "지금 바로"). **CHANGE 판정(|final−lastNotified|≥임계)은 clamp 전 원값**으로(당겨진 폭 정확), 표시는 clamp값.
5. **스케줄러 상태머신 (codex 치명 #3 반영)**: `phase` 컬럼(`WAITING_FIRST_CHECK → WAITING_DEPARTURE → WAITING_FINAL_CHECK → COMPLETED`) + 단일 `next_action_at` 스캔 컬럼. checker 스캔: `status=ACTIVE AND next_action_at <= now`.
   - **claim/lock**: alert에 `@Version`(낙관락). checker/dispatcher 동시 실행 가능(스케줄러 풀 3) → row 갱신은 버전 충돌 시 재시도/스킵.
   - **WAITING_DEPARTURE는 checker 스캔에서 제외**: 이 구간 진입 시 `next_action_at = NULL` 세팅(dispatcher 소유). NULL은 `next_action_at <= now`에 안 걸림 → 반복 스캔/교착 없음. dispatcher는 notification outbox(`status=PENDING, scheduled_at<=now`)로 별도 스캔.
   - **발송+전이 원자성**: dispatcher가 DEPARTURE 발송 성공 → **같은 트랜잭션**에서 notification SENT 마킹 + `recordNotified()`(phase 전이, next_action_at=finalCheckAt). 전이 실패 시 롤백 → notification PENDING 유지 → 재클레임. 중복 발송은 `dedup_key` unique로 방어(재시도해도 1건).
   - **dispatcher 실패 시 대체 전이**: 발송 반복 실패(attemptCount 상한) → notification FAILED. alert는 `expiresAt` 도래 시 checker가 EXPIRED 처리(고아 방지).
6. **notification type**: `DEPARTURE`, `CHANGE` 2종만.
7. **userLeadMinutes ∈ {0, 5, 10, 15}** 만 허용 (기존 %5 검증 대체).
8. **서비스윈도(첫차/막차)**: 컬럼 저장 안 함(체크 시점 판정값, 구독 상태 아님). `BusRouteStop`의 정류장별 첫차/막차로 **예상 승차 시각** 기준 판정. `NO_VEHICLE`은 막차/배차대기 구분 불가하므로 근거로 쓰지 않음.
   - STATIC 모드 승차시각 추정: `targetArrivalAt`에서 뒤 구간 소요 **역산** (등록 시점 now 기준은 무의미).
   - REALTIME 모드: 검색 시각 + 앞 구간 누적 (check는 출발 임박이라 유효).
9. **reliability**: `HIGH`(모든 버스 구간 REALTIME/MODEL AVAILABLE, 지하철 근사 없음) / `MEDIUM`(APPROXIMATE 또는 일부 fallback) / `LOW`(fallback 다수·NO_VEHICLE). varchar enum, alert에 최근 값 저장.
10. **horizon 한계 인지**: 실시간=다음 1~2대, 모델=현재 운행 차량만. 범위 밖 버스 구간 → ODsay 시간 유지 + reliability 하향 (후보 제외하지 않음). 2번째 환승은 MODEL 의존↑.
11. **운행불가 처리**: 등록 시 전부 OUT_OF_SERVICE → 422. check 시 → 직전 추천값 유지 + reliability LOW (알림 플로우는 계속).
12. **final check 리드 = 5분** (원안 3분에서 조정: search+evaluate+push 지연 여유).

---

## 1. PR 분리 (per-task-pr: 각각 fresh main 기준 독립 브랜치)

| PR | 브랜치 | 내용 | flag |
|----|--------|------|------|
| PR-A | `feat/route-alert-v2-schema-evaluator` | V23 재작성 + 엔티티 개편 + evaluator 2모드 + 서비스윈도 판정 + 등록 플로우 | 없음(API는 기존과 동일 활성) |
| PR-B | `feat/route-alert-v2-scheduler` | 체크포인트 스케줄러(first/final check, CHANGE 판정, 만료) | `notification.route-arrival.scheduler-enabled` (기본 false) |
| PR-C | `feat/route-alert-v2-dispatch` | 발송 디스패처(outbox claim, Toss push, stale 가드, 발송 후 phase 전이) | `notification.route-arrival.dispatch-enabled` (기본 false) |

의존: A → B → C 순차 머지. B의 DEPARTURE row는 C 전까지 inert(기존 PR1과 동일 패턴).

---

## 2. PR-A: 스키마 + evaluator + 등록 플로우

### 2.1 V23 재작성 — `src/main/resources/db/migration/V23__create_route_arrival_alert.sql` (untracked)

```sql
CREATE TABLE route_arrival_alert (
    id bigint generated by default as identity primary key,
    created_at timestamp(6), updated_at timestamp(6), deleted_at timestamp(6),
    user_id bigint not null references users,
    start_x double precision not null, start_y double precision not null, start_name varchar(255),
    end_x double precision not null, end_y double precision not null, end_name varchar(255),
    target_arrival_at timestamp(6) not null,
    user_lead_minutes integer not null,          -- {0,5,10,15}
    buffer_minutes integer not null,
    -- baseline (등록 시 STATIC evaluator)
    baseline_duration_sec integer,
    baseline_leave_by_at timestamp(6),
    first_check_at timestamp(6),
    -- first check 결과 (REALTIME evaluator)
    recommended_duration_sec integer,
    recommended_leave_by_at timestamp(6),
    departure_notification_at timestamp(6),
    -- 발송 기록 (dispatcher가 기록)
    last_notified_leave_by_at timestamp(6),
    last_notified_at timestamp(6),
    -- 선택 경로 스냅샷 (first check가 기록, final check 타깃 재조회용)
    -- JSON: {"fixedSec":<대기 제외 고정소요합>,"eligibility":"ELIGIBLE|NO_BUS|UNRESOLVED_BUS",
    --        "boardings":[{"stop":"...","route":"...","seq":N,"offsetSec":<출발→해당 정류장 고정소요>}]}
    selected_path_snapshot varchar(2000),
    -- final check
    final_checked_at timestamp(6),
    reliability varchar(255)
        constraint route_arrival_alert_reliability_check
        check (reliability in ('HIGH','MEDIUM','LOW')),
    change_alert_count integer not null default 0,
    -- 상태머신
    phase varchar(255) not null
        constraint route_arrival_alert_phase_check
        check (phase in ('WAITING_FIRST_CHECK','WAITING_DEPARTURE','WAITING_FINAL_CHECK','COMPLETED')),
    next_action_at timestamp(6),
    status varchar(255) not null
        constraint route_arrival_alert_status_check
        check (status in ('ACTIVE','DONE','EXPIRED','CANCELED')),
    expires_at timestamp(6) not null,
    version bigint not null default 0     -- @Version 낙관락 (checker/dispatcher 동시성)
);
CREATE INDEX idx_route_alert_user ON route_arrival_alert (user_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_route_alert_scan ON route_arrival_alert (status, next_action_at) WHERE deleted_at IS NULL;

CREATE TABLE route_arrival_alert_notification (
    id bigint generated by default as identity primary key,
    created_at timestamp(6), updated_at timestamp(6), deleted_at timestamp(6),
    alert_id bigint not null references route_arrival_alert,
    type varchar(255) not null
        constraint route_alert_notification_type_check
        check (type in ('DEPARTURE','CHANGE')),
    dedup_key varchar(255) not null,
    status varchar(255) not null
        constraint route_alert_notification_status_check
        check (status in ('PENDING','PROCESSING','SENT','FAILED','CANCELED')),
    scheduled_at timestamp(6) not null,
    target_leave_by_at timestamp(6) not null,
    payload varchar(1000),
    attempt_count integer not null default 0,
    processing_started_at timestamp(6),
    sent_at timestamp(6),
    last_failure_reason varchar(500)
);
CREATE UNIQUE INDEX uq_route_alert_notification_dedup ON route_arrival_alert_notification (dedup_key);
CREATE INDEX idx_route_alert_notification_status_scheduled
    ON route_arrival_alert_notification (status, scheduled_at) WHERE deleted_at IS NULL;
```

삭제되는 기존 컬럼: `last_total_duration_sec`, `leave_by_at`, `last_checked_at`, `last_notified_leave_by_at`은 유지.

### 2.2 엔티티 — `src/main/java/watoo/grd/nextroute/domain/notification/entity/`

- `RouteArrivalAlert.java` 개편: 2.1 컬럼과 1:1. 삭제: `lastTotalDurationSec`, `leaveByAt`, `lastCheckedAt`. 추가: `baselineDurationSec/baselineLeaveByAt/firstCheckAt/recommendedDurationSec/recommendedLeaveByAt/departureNotificationAt/lastNotifiedAt/selectedPathSnapshot/finalCheckedAt/reliability/phase/nextActionAt`. `@Version private long version` 추가(낙관락).
  - 도메인 메서드: `applyFirstCheck(...)` → phase=WAITING_DEPARTURE, **nextActionAt=NULL**(checker 스캔 제외, dispatcher 소유. departureNotificationAt은 notification row.scheduledAt에만 기록)
  - `recordNotified(leaveByAt, at, finalCheckAt)` → lastNotified* 기록, finalCheckAt null이면 phase=COMPLETED·status=DONE, 아니면 phase=WAITING_FINAL_CHECK·nextActionAt=finalCheckAt. **dispatcher가 발송 성공과 같은 트랜잭션에서 호출**
  - `applyFinalCheck(at, reliability)` / `incrementChangeAlertCount()` → phase=COMPLETED·status=DONE
  - `markExpired()/markCanceled()` 기존 유지 (+nextActionAt=null)
- 신규 `RouteAlertPhase.java` enum: WAITING_FIRST_CHECK/WAITING_DEPARTURE/WAITING_FINAL_CHECK/COMPLETED
- `RouteNotificationType.java`: `DEPART_REMINDER` → `DEPARTURE`, `CHANGE` 추가
- 신규 `RouteAlertReliability.java` enum: HIGH/MEDIUM/LOW
- `RouteArrivalAlertNotification.java`: 변경 없음(javadoc dedupKey 갱신: `DEPARTURE=alertId:DEPARTURE`, `CHANGE=alertId:CHANGE`)

### 2.3 Evaluator — `application/notification/`

- `port/out/RouteTravelTimeEstimator.java` → 확장 (rename 없이 메서드 교체):
```java
public interface RouteTravelTimeEstimator {
    RouteEvaluation evaluate(TravelTimeQuery query, EvaluationMode mode);
    enum EvaluationMode { STATIC, REALTIME }
    record TravelTimeQuery(double startX, double startY, String startName,
                           double endX, double endY, String endName,
                           LocalDateTime targetArrivalAt) {}
    record RouteEvaluation(int durationSec, RouteAlertReliability reliability,
                           SelectedPath selectedPath,
                           Integer maxHeadwayMinutes) {}   // maxHeadway: 선택 경로 버스구간 max(term), firstCheck 여유 산정용(버스 없으면 null)
    record SelectedPath(int fixedSec, List<Boarding> boardings, FinalCheckEligibility eligibility) {}
    enum FinalCheckEligibility { ELIGIBLE, NO_BUS, UNRESOLVED_BUS }   // final check 실행/생략 사유 구분
    record Boarding(String stopId, String routeId, int seq, int offsetSec) {}
    // 경로 자체 없음 → 기존 IllegalStateException 유지(422 매핑 기존 로직 재사용)
    // 전부 OUT_OF_SERVICE → RouteOutOfServiceException (신규, 422 매핑 추가)
}
```
- `service/RouteSearchTravelTimeEstimator.java` 재작성:
  - STATIC → `searchWithoutRealtime()`(신규, transfer enricher 생략), REALTIME → 기존 `search()`
  - path별 `evaluatePath(path, mode)`:
    - 도보(trafficType=3): `walkTotalTimeSeconds ?: sectionTime×60` — `TransferArrivalEnricher.travelSeconds()`(:450) 로직과 동일 규칙
    - 지하철(1): `sectionTime×60`
    - 버스(2): STATIC → `sectionTime×60 + (term ?: defaultHeadwayMinutes)×60/2` (term은 `RouteResolution`에 추가된 필드, resolver 미해석 시 기본값). REALTIME → lane들의 `transferArrivals` 중 status AVAILABLE/ARRIVAL_TIME_APPROXIMATE 최소 `waitSeconds` + `sectionTime×60`; 유효 lane 없으면 STATIC 계산식 fallback + reliability 하향
  - 선택 path의 `SelectedPath` 산출: fixedSec = 대기 제외 전 구간 합, boardings = 버스 구간별 (resolve된 stopId/routeId/targetSeq, offsetSec = 출발→해당 승차 정류장 고정소요 누적)
- `application/route/service/TransferStopResolver.java`: `RouteResolution`에 `term` 필드 추가 (`resolveRoute()`가 이미 조회한 `BusRoute.term` 그대로 노출 — 추가 쿼리 없음, 기존 호출부는 record 필드 추가만 영향)
- `application/route/service/RouteSearchService.java`: `searchWithoutRealtime(request)` 추가 — 기존 `search()`에서 `transferArrivalEnricher.enrich()` 단계만 생략 (폴리라인·도보 보강 유지). `SearchRouteUseCase`에 메서드 추가
  - path별 availability 판정 (신규 `service/RouteServiceWindowChecker.java`):
    - 버스 구간별 예상 승차시각: STATIC=targetArrivalAt에서 역산, REALTIME=now+앞구간 누적
    - `TransferStopResolver`(기존, `application/route/service/`)로 stopId/routeId resolve → `BusRouteStop` 첫차/막차 조회 (`domain/bus/entity/BusRouteStop.java:47-50`, 저장 포맷 구현 시 확인)
    - resolve 실패·데이터 없음 → UNKNOWN. 승차시각이 [첫차,막차] 밖 → OUT_OF_SERVICE. 안이면 AVAILABLE
  - 선택: AVAILABLE 최소 duration → UNKNOWN 최소 → 전부 OUT_OF_SERVICE면 `RouteOutOfServiceException`
  - reliability: 결정 9 규칙
- 기존 `estimateSeconds()` 삭제, 호출부 전환

### 2.4 등록 플로우 — `service/RouteArrivalAlertService.java`

- `create()`:
  - userLead 검증: `Set.of(0,5,10,15).contains(userLead)` 아니면 IllegalArgumentException
  - `evaluate(query, STATIC)` → `baselineDurationSec` (내부적으로 `searchWithoutRealtime` — 등록 시 실시간 fan-out 없음)
  - `baselineLeaveByAt = targetArrivalAt − baselineDurationSec − bufferMinutes`
  - `safetyMargin = firstCheckBaseMarginMinutes(3) + min((evaluation.maxHeadwayMinutes ?: 0)×firstCheckHeadwayFactor(0.5), firstCheckMaxMarginMinutes(20))` (버스 없으면 base만)
  - `firstCheckAt = baselineLeaveByAt − userLeadMinutes − safetyMargin`; 과거면 `now` clamp (기존 remindAt 패턴)
  - `baselineLeaveByAt ≤ now` 면 422 (이미 출발했어야 함)
  - alert 저장: phase=WAITING_FIRST_CHECK, nextActionAt=firstCheckAt, reliability=baseline 결과
  - **DEPARTURE row는 등록 시 만들지 않음** (first check가 생성) — 기존 PR1과 다름
- `cancel()`: 기존 유지 + `nextActionAt=null`
- DTO:
  - `RouteAlertRequest`: 변경 없음 (검증 로직만 서비스에서 교체)
  - `RouteAlertResponse`: `lastTotalDurationSec/leaveByAt` 제거 → `baselineLeaveByAt`, `recommendedLeaveByAt`, `phase`, `reliability` 노출
- `config/RouteArrivalAlertProperties.java` 추가 필드:
```java
private int firstCheckLeadMinutes = 3;    // firstCheckAt = baselineLeaveBy − lead − 이 값
private int finalCheckLeadMinutes = 5;    // final check = recommendedLeaveBy − 이 값
private int changeThresholdMinutes = 5;   // |final − lastNotified| ≥ 이 값 → CHANGE
private int finalCheckLeadMinutes = 5;    // final check = recommendedLeaveBy − 이 값. userLead ≤ 이 값 → 생략
private int defaultHeadwayMinutes = 10;   // term 미확보 버스 구간 STATIC 대기 = 이 값/2
private int firstCheckBaseMarginMinutes = 3;    // firstCheckAt 기본 여유
private double firstCheckHeadwayFactor = 0.5;   // term 비례 여유 계수
private int firstCheckMaxMarginMinutes = 20;    // term 비례 여유 상한
private double snapshotStaleThreshold = 0.5;    // STALE/OUT_OF_SERVICE boarding 비율 초과 시 CHANGE 억제
```
(주: 기존 `minLeadForFinalCheck`는 `finalCheckLeadMinutes`로 통합 — 생략 임계와 리드가 같은 값이라 단일화)

### 2.5 PR-A 작업 순서

- [x] **V23 재작성 전 flyway 가드**: 로컬 psql/docker 미기동 확인 — 라이브 충돌 없음. DB 있는 환경 부팅 시 checksum 가드 재확인 필요(§헤더 ⚠️)
- [x] V23 재작성 (2.1) — version 컬럼 포함
- [x] enum 4종 (Phase 신규, Type 개편 DEPARTURE/CHANGE, Reliability 신규, FinalCheckEligibility=estimator nested)
- [x] `RouteArrivalAlert` 엔티티 개편 + 도메인 메서드 + `@Version`
- [x] `RouteTravelTimeEstimator` 포트 교체(evaluate 2모드) + `RouteOutOfServiceException`(IllegalStateException 상속 → 기존 422 핸들러 재사용)
- [x] `RouteResolution`에 termMinutes 추가 + `SearchRouteUseCase.searchWithoutRealtime()` (+`RouteSearchService` searchInternal 분리)
- [x] `RouteServiceWindowChecker` 신규 (time-of-day 파서, 파싱실패→UNKNOWN permissive, 자정넘김 처리)
- [x] `RouteSearchTravelTimeEstimator` 재작성 (2모드 + term/2 + SelectedPath + maxHeadway + 후보선택)
- [x] `RouteArrivalAlertService.create()` 개편 + Properties(defaultHeadway/firstCheck*)
- [x] DTO 개편 (RouteAlertResponse: baseline/recommended/phase/reliability 노출)
- [x] 테스트 통과 (RouteArrivalAlertServiceTest 9, RouteSearchTravelTimeEstimatorTest 8, RouteServiceWindowCheckerTest 7)
- [ ] **로컬 부팅 스모크: V23 clean 적용 — 로컬 DB/Docker 미기동으로 미실행.** DB 있는 환경서 Hibernate validate 확인 필요(엔티티↔V23 정적 대조는 완료)

## 3. PR-B: 체크포인트 스케줄러

### 3.1 신규 `service/RouteAlertCheckScheduler.java`

- `@Scheduled(fixedDelayString = "${notification.route-arrival.check-interval-ms:30000}")`, `@ConditionalOnProperty(scheduler-enabled)`
- 스캔: `alertRepository.findByStatusAndNextActionAtLessThanEqualAndDeletedAtIsNull(ACTIVE, now)` (repo 메서드 신규, `idx_route_alert_scan` 사용)
- row별 분기:
  - `expiresAt < now` → markExpired + 자식 PENDING 취소 전파(기존 `cancelPendingByAlertId` 재사용)
  - phase=WAITING_FIRST_CHECK → **first check**:
    - `evaluate(query, REALTIME)` → recommendedDurationSec/recommendedLeaveByAt(= target − dur − buffer)/departureNotificationAt(= recommendedLeaveBy − userLead) + `SelectedPath`(eligibility 포함) 스냅샷 JSON 직렬화
    - eligibility 산정: 버스 구간 없음→`NO_BUS`, 버스 있고 전부 resolve 성공→`ELIGIBLE`, resolve 실패 구간 존재→`UNRESOLVED_BUS`(reliability LOW)
    - 운행불가/검색실패 → baseline 값 유지 + reliability LOW로 계속 (스냅샷 boardings 빈/eligibility≠ELIGIBLE → final check 생략)
    - DEPARTURE notification row 생성: dedupKey=`{alertId}:DEPARTURE`, scheduledAt=departureNotificationAt(과거면 now), targetLeaveByAt=recommendedLeaveByAt
    - `applyFirstCheck(...)` → phase=WAITING_DEPARTURE, **nextActionAt=NULL**(dispatcher 소유) — **CHANGE는 first check에서 발행 금지**
  - phase=WAITING_DEPARTURE → **스캔 자체에 안 걸림**(nextActionAt=NULL). dispatcher가 outbox로 소유·전이.
  - phase=WAITING_FINAL_CHECK → **final check (타깃 재조회, ODsay 재검색 금지)**:
    - `selectedPathSnapshot` 역직렬화 → boardings 순회하며 `SearchTimeBusQueryPort.getArrInfoByStop(stop, route, seq)` 재조회 (collector 캐시 히트 기대)
    - **스냅샷 유효성 검사(4-c)**: 응답에 route 없음→`SNAPSHOT_STALE`; 예상 승차시각>막차→`OUT_OF_SERVICE`; outcome≠OK→그 boarding만 term/2 유지+reliability 하향
    - 타임라인 재계산: `departAt = lastNotifiedLeaveByAt` 가정, `arrive_i = departAt + offsetSec_i + Σ(재계산 wait_1..i-1)`, `wait_i` = 도착 API의 `arrive_i` 이후 최초 버스 (enricher `earliestRealtimeArrival` 로직 헬퍼 추출해 재사용)
    - `finalDurationSec = fixedSec + Σwait_i` → `finalRecommendedLeaveByAt = target − finalDurationSec − buffer`
    - **STALE/OUT_OF_SERVICE boarding 비율 > snapshotStaleThreshold(0.5)** → final check 신뢰 불가: CHANGE 억제, reliability=LOW, lastNotified 유지하고 `applyFinalCheck` → DONE
    - **과거 clamp**: `finalRecommendedLeaveByAt ≤ now` → 표시값=now, `urgentDeparture=true`. **CHANGE 판정은 clamp 전 원값**으로
    - `|원값 final − lastNotifiedLeaveByAt| ≥ changeThresholdMinutes && changeAlertCount == 0` → CHANGE row 생성 (dedupKey=`{alertId}:CHANGE`, scheduledAt=now, targetLeaveByAt=clamp값, payload에 urgentDeparture) + `incrementChangeAlertCount()`
    - `applyFinalCheck(now, reliability)` → DONE
- 동시성: 모든 alert 갱신은 `@Version` 낙관락. 충돌(dispatcher와 경합) 시 스킵/다음 스캔 재시도.
- 메트릭: check 성공/실패/운행불가/snapshot-stale 카운터 (기존 메트릭 패턴 확인 후 동일 방식)

### 3.2 PR-B 작업 순서

- [ ] repo 스캔 메서드 + 스케줄러 골격 + flag
- [ ] first check 로직 + DEPARTURE 생성
- [ ] final check 로직 + CHANGE 판정
- [ ] 만료 처리
- [ ] 테스트 (5.PR-B) → `./gradlew test --tests '*RouteAlertCheckScheduler*'`

## 4. PR-C: 발송 디스패처

### 4.1 신규 `service/RouteAlertDispatchScheduler.java`

- flag `dispatch-enabled`, 주기 스캔: `notification (status=PENDING, scheduled_at <= now)` claim → PROCESSING(+processingStartedAt), reclaim: PROCESSING이 임계 초과 시 PENDING 복귀
- stale 가드 (발송 직전):
  - 부모 alert ACTIVE 아님 → CANCELED
  - `targetLeaveByAt != alert.recommendedLeaveByAt`(재계산됨) → 최신값으로 payload 갱신 후 발송
  - `targetArrivalAt < now` → CANCELED
- Toss push 발송: 기존 Toss 클라이언트/푸시 포트 재사용(tossUserKey 신원 — `project-toss-login` 참조. 구현 시 기존 발송 어댑터 유무 확인, 없으면 이 PR에서 포트+어댑터 신설)
- **발송+전이 원자성**: DEPARTURE 발송 성공 시 **같은 트랜잭션**에서 notification SENT + `alert.recordNotified(targetLeaveByAt, now, finalCheckAt)`. 전이 실패→롤백→notification PENDING 유지→재클레임. 중복 발송은 dedup_key unique로 방어.
- `recordNotified`의 finalCheckAt 계산 (final check 생략 판정):
  - `userLead ≤ finalCheckLeadMinutes(5)` **또는** eligibility ∈ {`NO_BUS`,`UNRESOLVED_BUS`} **또는** 스냅샷 null → finalCheckAt=null → DONE
  - else finalCheckAt = `recommendedLeaveByAt − finalCheckLeadMinutes`(과거면 now)
- CHANGE 발송 성공 → sent 마킹만
- 실패 → attemptCount++/FAILED/재시도 정책 (기존 outbox 패턴 확인 후 동일). attemptCount 상한 초과→FAILED 고정, alert는 expiresAt에 checker가 EXPIRED(고아 방지)

### 4.2 PR-C 작업 순서

- [ ] claim/reclaim repo 메서드 + 디스패처 골격 + flag
- [ ] stale 가드
- [ ] Toss 발송 연동
- [ ] 발송 후 phase 전이 (recordNotified)
- [ ] 테스트 (5.PR-C) → `./gradlew test --tests '*RouteAlertDispatch*'`

## 5. 테스트 플랜

**PR-A**
- userLead 0/5/10/15 허용, 그 외 거부
- STATIC: `searchWithoutRealtime` 호출(transfer enricher 미실행) + 버스 구간 `sectionTime + term/2` 검증 (term 있음/null→기본값/2, resolver 미해석→기본값/2 3케이스)
- REALTIME 모드: realtime wait / model wait / fallback(유효 lane 없음→STATIC식+reliability 하향) 3케이스 duration 검증
- 도보 walkTotalTimeSeconds 우선, 없으면 sectionTime 검증
- `SelectedPath` 산출: fixedSec/boardings(offsetSec 누적)/eligibility(ELIGIBLE/NO_BUS/UNRESOLVED_BUS 3케이스) 검증
- firstCheckAt safetyMargin: term 비례 여유(고배차 노선 더 이름) + 상한 20분 clamp 검증
- AVAILABLE/UNKNOWN/OUT_OF_SERVICE 후보 선택 우선순위 검증, 전부 OUT_OF_SERVICE→예외→422
- 서비스윈도: 예상 승차시각이 막차 이후 → OUT_OF_SERVICE (STATIC=역산 앵커 검증 포함)
- create: baselineLeaveByAt/firstCheckAt 계산, firstCheckAt 과거→now clamp, phase/nextActionAt 초기값, DEPARTURE row 미생성

**PR-B**
- first check: DEPARTURE 생성 + CHANGE 미생성 + phase 전이(WAITING_DEPARTURE, **nextActionAt=NULL**) + 스냅샷(eligibility 포함) 저장 검증
- first check 실패 → baseline 유지 + reliability LOW + eligibility≠ELIGIBLE
- final check: **ODsay/SearchRouteUseCase 미호출** 검증(mock verify never) — boardings 도착 API만 조회
- final check 타임라인: wait 재계산 반영 finalRecommendedLeaveByAt 검증, 조회 실패 boarding→term/2 유지
- **스냅샷 STALE/OUT_OF_SERVICE 비율 > 0.5 → CHANGE 억제 + LOW + DONE** 검증
- **과거 clamp**: finalRecommendedLeaveByAt≤now → 표시=now·urgentDeparture, **CHANGE 판정은 원값** 검증
- |원값 diff| ≥ 5분 && changeAlertCount=0 → CHANGE 1회, 이후 DONE / |diff| < 5분 → CHANGE 없이 DONE
- 만료 전파
- **동시성**: `@Version` 충돌 시 스킵/재시도 (checker-dispatcher 경합 시뮬)

**PR-C**
- 발송+전이 **원자성**: 전이 강제 실패 주입 → notification PENDING 유지(SENT 아님) 검증
- final check 생략 4케이스: userLead≤5 / eligibility=NO_BUS / eligibility=UNRESOLVED_BUS / 스냅샷 null → 발송 후 즉시 DONE. userLead>5+ELIGIBLE → WAITING_FINAL_CHECK+nextActionAt 검증
- DEPARTURE 발송 성공 → lastNotifiedLeaveByAt/lastNotifiedAt 저장 검증
- stale 가드 3케이스

## 6. 참조

- enricher 타임라인: `application/route/service/TransferArrivalEnricher.java` (`travelSeconds`:450, `earliestRealtimeArrival`:457, `buildAvailable`, wave 3a~3f)
- `SubPathResult.transferArrivals`, `TransferArrival.waitSeconds`: `application/route/dto/`
- 첫차/막차: `domain/bus/entity/BusRouteStop.java:47-50` (포맷 구현 시 확인)
- resolver: `application/route/service/TransferStopResolver.java`
- 422 매핑: 커밋 a34d729 (경로없음 매핑 위치)
- outbox/알림 패턴: `BusArrivalAlert` 계열 (project-bus-label-v2)
- 배포: V23 untracked convention — VPS 배포 시 수동 반영 (reference-vps-deploy)

## 7. 외부 API 비용 (alert 1건당)

| 자원 | 원안 | 확정안 |
|---|---|---|
| ODsay 검색 | 3회 | **2회** (baseline+first check. final은 재검색 없음) |
| 버스 실시간 fan-out | 2회 풀(전 경로×lane) | **1회 풀**(first check) + final은 선택 경로 승차지점 1~2콜(캐시 히트 기대) |
| baseline 정확도 | 대기 0 가정 | term/2 정적 보정 (외부 호출 0) |

## 8. 미결/구현 중 확인 사항

- `BusRouteStop` 첫차/막차 저장 포맷 (예: "20250101040000" vs "HHmm") — 파싱 전 실데이터 확인
- `BusRoute.term` prod 적재율 (bus_route 테이블 — resolver 동작 중이라 적재 자체는 확인됨, term null 비율만 체크)
- Toss push 발송 어댑터 기존 존재 여부 (PR-C 시점)
- 자정 넘는 막차(예: 25:30 표기) 처리
- `earliestRealtimeArrival` 로직 final check 재사용 방식 (enricher에서 헬퍼 추출 vs 복제)
