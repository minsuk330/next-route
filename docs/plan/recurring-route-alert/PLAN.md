# 목표 도착 알림 반복(요일) 기능 — 구현 PLAN (L티어)

> 설계 진실원천: `docs/superpowers/specs/2026-07-06-recurring-route-alert-design.md` (gitignore, 로컬 디스크).
> 브랜치: `feat/recurring-route-alert` (fresh main 기준). 단일 PR (스택 금지 — v2 PR#53 선례).
> flag off 기본 → 머지 무영향.

## 아키텍처 요약 (안1)
신규 `RouteAlertSchedule`(구독 규칙) → `RouteAlertScheduleSpawner`(@Scheduled+flag)가
in-flight occurrence `RouteArrivalAlert`(기존 v2 **상태머신 무변경**, `scheduleId` relation만 추가)를 최대 1개 유지.
occurrence마다 새 alert id → 부분 유니크 `(schedule_id, target_arrival_at)`로 중복 방지.

## 티어 근거
L: 스키마 신설 + 모듈 경계 신설(schedule/spawner) + API 계약 교체 + 동시성(@Version/부분유니크). 다PR성 로직(스키마+spawner) 단일 브랜치로 통합.

---

## 작업 순서 (체크박스, 의존성 순)

### Phase 1 — 순수 로직 (TDD)
- [x] `domain/notification/entity/RouteWeekdays.java` (신규): bitmask↔DayOfWeek, `nextOccurrence(now,hhmm,mask,repeat)`.
  - bit0=월…bit6=일 (=`DayOfWeek.getValue()-1`). 코드 MON..SUN.
  - nextOccurrence: 일회성=today@hhmm>now?today:tomorrow. 반복=d 0..7 증가하며 mask포함&date@hhmm>now 최초.
  - 검증: `RouteWeekdaysTest` (요일 경계·주넘김·일회성 today/tomorrow).

### Phase 2 — 엔티티/스키마/repo
- [x] `domain/notification/entity/RouteAlertScheduleStatus.java` (신규 enum): ACTIVE/COMPLETED/CANCELED. **(FAILED 제거 — 이미 쓴 코드 수정 필요)**
- [x] `domain/notification/entity/RouteAlertSchedule.java` (신규): user, 좌표(startX→startx 규칙), arrivalTime(LocalTime), weekdaysMask(int), repeat(boolean, `@Column(name="repeat")`), userLeadMinutes, bufferMinutes, lastSpawnedTargetArrivalAt, **retry metadata 4필드(lastMaterializationFailedAt/lastMaterializationError/materializationRetryCount/nextMaterializationRetryAt)**, status, @Version. 메서드 recordSpawn(성공시 retry리셋)/**recordMaterializationError(msg,now,backoff)**/markCompleted/markCanceled/isActive. **(markFailed 제거)**
- [x] `domain/notification/entity/RouteArrivalAlert.java` (수정): `Long scheduleId` 필드 + 빌더 파라미터 추가. 나머지 무변경.
- [x] `domain/notification/repository/RouteAlertScheduleRepository.java` (신규): findByUserAndDeletedAtIsNull, findByIdAndUserAndDeletedAtIsNull, findByStatusAndDeletedAtIsNull.
- [x] `domain/notification/repository/RouteArrivalAlertRepository.java` (수정): `existsByScheduleIdAndStatusAndDeletedAtIsNull` + **`findActiveOccurrencesByScheduleIds`(getActive batch, Q3)** + 일회성 종결판정용 마지막 occurrence 조회(`findTopByScheduleIdOrderByIdDesc` 등).
- [x] `resources/db/migration/V25__create_route_alert_schedule.sql` (신규): 아래 SQL 개요.

### Phase 3 — 서비스 리팩터 + API 교체
- [ ] `application/notification/dto/RouteAlertRequest.java` (수정): targetArrivalAt 제거 → arrivalTime(LocalTime `@JsonFormat("HH:mm")`) + weekdays(List<String>) + repeat(boolean). (완료됨)
- [ ] `application/notification/dto/RouteAlertResponse.java` → **삭제**, `RouteAlertScheduleResponse.java` (신규): schedule 요약 + nextArrivalAt + in-flight occurrence(id/phase/recommendedLeaveByAt/baselineLeaveByAt, nullable).
- [ ] `application/notification/service/RouteArrivalAlertService.java` (수정):
  - `create()`: schedule 저장(tx1 commit) → **별도 빈** `spawnService.processScheduleSpawn(scheduleId, now)` 호출(tx2, 즉시 materialize 시도) → RouteAlertScheduleResponse 반환. occurrence 실패가 schedule rollback 으로 안 번짐.
  - `getActive()`: schedule 목록(ACTIVE) → nextOccurrence + in-flight occurrence **batch 조회**(Q3, `findActiveOccurrencesByScheduleIds`) 로 응답.
  - `cancel()`: schedule markCanceled + in-flight occurrence markCanceled + notification cancelPendingByAlertId 전파.
  - 검증 추가: repeat=true면 weekdays≥1, userLead {0,5,10,15}, arrivalTime NotNull.
- [ ] `application/notification/port/in/CreateRouteAlertUseCase.java` / `GetRouteAlertUseCase.java` / `CancelRouteAlertUseCase.java` (수정): 반환/파라미터 타입 RouteAlertScheduleResponse 로 교체.
- [ ] `application/notification/config/RouteArrivalAlertProperties.java` (수정): `scheduleSpawnerEnabled=false`, `scheduleSpawnIntervalMs=60000`, `spawnLeadHours=6` 추가.
- [ ] `infrastructure/adapter/in/api/notification/RouteAlertController.java` (수정): 반환 타입 교체. path 유지. cancel `/{id}`=scheduleId.

### Phase 4 — Spawner (flag off)
- [ ] `application/notification/service/RouteAlertScheduleSpawnService.java` (신규, @Service): tx 경계 소유. `processScheduleSpawn(Long scheduleId, LocalDateTime now)` @Transactional:
  - in-flight(`existsByScheduleIdAndStatus ACTIVE`) 있으면 skip.
  - 일회성 종결: 마지막 occurrence status==DONE → `markCompleted()`.
  - `next=nextOccurrence(now)`; `next−now>spawnLeadHours` → skip(대기).
  - window내 `createOccurrence(schedule, next, now)` 결과 분기:
    - **성공** → `recordSpawn(next)`.
    - **too-late(baselineLeaveBy≤now)·RouteOutOfService** → roll-forward: 상태변경 없이 skip(ACTIVE). nextOccurrence 자연 이월(cursor 없음).
    - **유니크 충돌(DataIntegrityViolation)** → tx rollback, spawner 바깥서 idempotent 성공 취급.
    - **시스템오류(no-route IllegalState/기타 예외)** → `recordMaterializationError(msg, now, backoff)`(retry_count++, next_retry_at=now+지수백오프), ACTIVE 유지. spawn 진입 시 `now < next_materialization_retry_at` 면 백오프 skip.
  - `createOccurrence`: STATIC eval→baselineLeaveBy 계산. `≤now` 면 throw 대신 **TOO_LATE outcome 반환**(roll-forward 신호). 정상이면 firstCheckAt→alert 저장(scheduleId 주입)→`recordSpawn`→return.
- [ ] `application/notification/service/RouteAlertScheduleSpawner.java` (신규): thin @Component `@ConditionalOnProperty(prefix="notification.route-arrival", name="schedule-spawner-enabled", havingValue="true")`, `@Scheduled(fixedDelayString="${notification.route-arrival.schedule-spawn-interval-ms:60000}")`. scan: `scheduleRepo.findByStatusAndDeletedAtIsNull(ACTIVE)` 루프 → 각 `spawnService.processScheduleSpawn(id, now)` (별도 빈=tx 경계·@Version 독립). 예외 격리(schedule별 try/catch). 유니크 충돌은 spawnService tx 밖에서 성공 취급.

### Phase 5 — 테스트
- [ ] `RouteWeekdaysTest` (완료). 
- [ ] `RouteArrivalAlertServiceTest` (재작성): create→schedule 저장, window내 즉시 occurrence, 일회성/반복 검증, weekdays≥1, cancel 전파, getActive.
- [ ] `RouteAlertScheduleSpawnerTest` 또는 `processScheduleSpawn` 단위: in-flight skip / window밖 skip / window내 생성+recordSpawn / 유니크충돌 멱등 / 일회성 종결→COMPLETED / 일회성 실패→FAILED / 반복 실패→ACTIVE 유지.

---

## V25 SQL 개요
```sql
CREATE TABLE route_alert_schedule (
  id bigint identity pk, created_at, updated_at, deleted_at,
  user_id bigint not null references users,
  startx double precision not null, starty ... , start_name varchar,   -- 좌표 naming 규칙
  endx, endy, end_name,
  arrival_time time not null, weekdays_mask integer not null,
  repeat boolean not null, user_lead_minutes integer not null, buffer_minutes integer not null,
  last_spawned_target_arrival_at timestamp(6),
  last_materialization_failed_at timestamp(6), last_materialization_error varchar(500),  -- retry metadata(시스템오류만)
  materialization_retry_count integer not null default 0, next_materialization_retry_at timestamp(6),
  status varchar check (ACTIVE|COMPLETED|CANCELED), version bigint not null default 0
);
CREATE INDEX idx_route_alert_schedule_user  ON ... (user_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_route_alert_schedule_status ON ... (status) WHERE deleted_at IS NULL;
ALTER TABLE route_arrival_alert ADD COLUMN schedule_id bigint REFERENCES route_alert_schedule;
CREATE INDEX idx_route_alert_inflight ON route_arrival_alert (schedule_id, status) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uq_route_alert_schedule_target
  ON route_arrival_alert (schedule_id, target_arrival_at)
  WHERE schedule_id IS NOT NULL AND deleted_at IS NULL;
```
- 좌표 컬럼 `startx/starty/endx/endy` — 끝자리 단일 대문자 underscore 없음 (V23→V24 rename 버그 교훈).
- untracked convention: 로컬/VPS 수동 반영. 적용 전 checksum 가드. ([[reference-vps-deploy]])

## Properties 키 (혼동 방지)
- spawner: `notification.route-arrival.schedule-spawner-enabled` (기본 false)
- `schedule-spawn-interval-ms` (60000), `spawn-lead-hours` (6)
- check scheduler `scheduler-enabled` / dispatcher `dispatch-enabled` 와 **별개**.

## 핵심 참조 (기존 코드)
- `RouteArrivalAlertService.java:50-105` — 이식할 create 본문(STATIC eval→baseline→firstCheck).
- `RouteArrivalAlert.java:105-131` — 빌더(scheduleId 추가 지점).
- `V23__create_route_arrival_alert.sql`, `V24__fix_route_arrival_alert_coord_columns.sql` — 좌표 naming 선례.
- `RouteAlertCheckScheduler.java` — thin @ConditionalOnProperty+@Scheduled 패턴(spawner 복제 기준).
- `RouteOutOfServiceException extends IllegalStateException` — 경로실패 catch는 IllegalStateException 하나로 커버.

## 검증법
- 단위: `./gradlew test --tests '*RouteWeekdays*' --tests '*RouteArrivalAlertService*' --tests '*Spawner*'`.
- 로컬 DB 부팅 validate (엔티티↔V25, 좌표 naming 재확인). DB/Docker 없으면 정적대조만.
- flag off 기본 → 머지 무영향. 스테이징: spawner flag on + e2e(반복 2주기 관측).

## codex 논의 결과 (2026-07-07)
**Q1 확정(roll-forward 모델)**: create()·spawn tx 분리로 rollback 차단. materialize 실패 3분류 — too-late/운행불가=roll-forward(ACTIVE, cursor 없는 self-healing 이월, 일회성도 다음날), 시스템오류=retry metadata+ACTIVE, 유니크충돌=idempotent skip. FAILED terminal 제거. 일회성 종결=occurrence DONE 일 때만 COMPLETED(EXPIRED=이월).
**Q2 확정**: `RouteAlertScheduleSpawnService` 별도 @Service 분리(self-invocation 회피). spawner→spawnService→(occurrence factory).
**Q3 확정**: getActive batch 쿼리(`findActiveOccurrencesByScheduleIds`).

**Q4 확정**: spawn-lead-hours **6h 고정**(동적 late-guard 미채택, too-late roll-forward 로 커버).
**Q5 확정**: occurrence DONE→COMPLETED / EXPIRED→일회성도 roll-forward.
**retry 확정**: 4컬럼(last_materialization_failed_at/error/retry_count/next_retry_at) 지수 백오프.

→ **전 논의점 확정. PLAN final. 구현 진입 가능.**
