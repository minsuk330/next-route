# 알림 스케줄 수정(edit) — 구현 PLAN (L티어)

> 설계 진실원천: `docs/superpowers/specs/2026-07-07-schedule-edit-design.md` (gitignore, 로컬).
> 의존: PR #56(RouteAlertSchedule/spawn). 브랜치 = **#56 머지 후 fresh main 기준 `feat/schedule-edit`** 권장(미머지면 feat/recurring-route-alert 위 브랜치). 단일 PR.

## 티어 근거
L: 스키마(V26 status check) + occurrence 상태머신 확장(SUPERSEDED) + dispatcher 경합 guard 변경 + 설계 대안 논쟁(발송후 repeat 분기).

## 목표 흐름 (요약)
`PUT /{scheduleId}` → schedule 필드 갱신(tx1) + occurrence 처리(발송전=SUPERSEDED / repeat=true 발송후=유지 / one-time 발송후=DONE+soft-delete) → spawn 재materialize(tx2) → 응답. dispatcher 는 superseded/soft-delete alert 발송 차단.

---

## 작업 순서 (체크박스)

### Phase 1 — 상태·엔티티·마이그레이션
- [ ] `domain/notification/entity/RouteAlertStatus.java`: `SUPERSEDED` 추가.
- [ ] `domain/notification/entity/RouteArrivalAlert.java`: 메서드 2개 추가
  - `markSuperseded()`: `status=SUPERSEDED; phase=COMPLETED; nextActionAt=null; markDeleted();`
  - `markDoneOnEdit()`: `status=DONE; phase=COMPLETED; nextActionAt=null; markDeleted();`
  - (기존 `markCanceled()`는 soft-delete 안 함 — 구분 유지)
- [ ] `resources/db/migration/V26__add_superseded_to_route_arrival_alert_status.sql` (신규):
  ```sql
  ALTER TABLE route_arrival_alert DROP CONSTRAINT route_arrival_alert_status_check;
  ALTER TABLE route_arrival_alert ADD CONSTRAINT route_arrival_alert_status_check
      CHECK (status IN ('ACTIVE','DONE','EXPIRED','CANCELED','SUPERSEDED'));
  ```
  untracked convention(수동 반영). V25 이후 순서.

### Phase 2 — DTO·포트·예외
- [ ] `application/notification/dto/RouteAlertUpdateRequest.java` (신규): `arrivalTime(LocalTime @JsonFormat "HH:mm" @NotNull)`, `weekdays(List<String>)`, `repeat(boolean)`, `userLeadMinutes(@NotNull @PositiveOrZero Integer)`, `bufferMinutes(@PositiveOrZero Integer, nullable)`. **좌표 없음**. `bufferMinutes==null → 기본값`(create 와 동일 의미, 엔티티 컬럼은 non-null 이므로 서비스서 default 적용).
- [ ] `application/notification/port/in/UpdateRouteAlertUseCase.java` (신규): `RouteAlertScheduleResponse update(long userId, Long scheduleId, RouteAlertUpdateRequest req);`
- [ ] `application/notification/exception/ScheduleNotEditableException.java` (신규): 비-ACTIVE·낙관락 충돌 → 409. IllegalState 재사용 금지(422 매핑됨). 참조: 기존 409 패턴 `FavoriteController.java:56`.

### Phase 3 — 서비스 update
- [ ] `application/notification/service/RouteArrivalAlertService.java`:
  - `implements ... UpdateRouteAlertUseCase` 추가.
  - create 의 검증 로직을 private helper `validate(userLead, arrivalTime, repeat, mask)` 로 추출(create·update 공유).
  - `update(userId, scheduleId, req)`:
    1. 검증(공유 helper)
    2. `[tx1: TransactionTemplate]`
       - `scheduleRepository.findByIdAndUserAndDeletedAtIsNull` (없음→NoSuchElement=404). ACTIVE 아니면 `ScheduleNotEditableException`(409).
       - schedule 필드 갱신: 신규 도메인 메서드 `RouteAlertSchedule.update(arrivalTime, mask, repeat, lead, buffer)` — 필드 갱신 + **retry 백오프 메타 리셋**(nextMaterializationRetryAt/count/error/failedAt = null/0). ★리셋 안 하면 tx2 재spawn이 백오프 게이트에 막혀 조용히 skip(codex#2).
       - occurrence 처리 — **ACTIVE 전체 순회 먼저**(DB가 active 1개 보장 안 함, codex#3):
         `findByScheduleIdAndStatusAndDeletedAtIsNull(id, ACTIVE)` 순회:
           · 발송전(lastNotifiedAt==null) → `markSuperseded()`+`cancelPendingByAlertId`  (repeat 무관)
           · 발송후(lastNotifiedAt!=null): repeat=true→유지 / one-time→`markDoneOnEdit()`+cancel
         one-time 추가: ACTIVE 없고 **최신 non-deleted occurrence 가 이미 DONE**(발송후 종결, schedule ACTIVE 윈도우)면 → `findTopByScheduleIdAndDeletedAtIsNullOrderByIdDesc` 로 찾아 `markDoneOnEdit()`(soft-delete) — "새 1회 보장"(C2).
       - `OptimisticLockingFailureException` → `ScheduleNotEditableException`(409) 로 변환.
    3. `[tx2]` try/catch(RuntimeException) `spawnService.processScheduleSpawn(scheduleId, now)`
    4. `buildResponse(scheduleId, now)`
  - **tx1/tx2 분리 필수**(create 와 동일 TransactionTemplate 패턴).

- [ ] `domain/notification/entity/RouteAlertSchedule.java`: `update(LocalTime arrivalTime, int weekdaysMask, boolean repeat, Integer userLeadMinutes, Integer bufferMinutes)` — 필드 갱신 + **retry 백오프 메타 리셋**(materializationRetryCount=0, nextMaterializationRetryAt/lastMaterializationError/lastMaterializationFailedAt=null). status·lastSpawned 는 불변.

### Phase 4 — 컨트롤러
- [ ] `infrastructure/adapter/in/api/notification/RouteAlertController.java`:
  - `@PutMapping("/{id}")` → `updateRouteAlertUseCase.update(userId, id, req)`.
  - `@ExceptionHandler(ScheduleNotEditableException.class)` → 409 Conflict.

### Phase 5 — dispatcher guard (경합 차단, PR 범위)
- [ ] `domain/notification/repository/RouteArrivalAlertNotificationRepository.java`: `claimForProcessing` WHERE 에 alert 활성 조건 추가(bulk update 는 join 불가 → subquery, enum 완전수식):
  ```
  and exists (select 1 from RouteArrivalAlert a
              where a.id = n.alert.id and a.status = ...RouteAlertStatus.ACTIVE and a.deletedAt is null)
  ```
- [ ] `application/notification/service/RouteAlertDispatchStateService.java`:
  - `markDepartureSent`(:106): 진입 후 `alert` 가 `status!=ACTIVE || deletedAt!=null` 이면 **`n.markCanceled(); return;`**(no-op 금지 — PROCESSING 방치 시 reclaimStale 재시도→반복발송, codex#1). recordNotified 미호출.
  - `markChangeSent`(:119): 동일 inactive guard(무조건 SENT → one-time 발송후 edit ↔ PROCESSING CHANGE race, codex 지적). inactive 면 markCanceled+return.
  - `claimForDispatch`/DispatchTask 구성부(:60,66): 대상 alert inactive(status≠ACTIVE||deleted) 면 해당 notification cancel + skip(매 사이클 재선택 방지, codex#4).

### Phase 6 — 테스트
- [ ] `RouteArrivalAlertServiceTest`: update repeat=true(발송전 supersede·발송후 유지·없음) / one-time(발송전·발송후 DONE+soft-delete·이미DONE) / 검증 / 404 / 409(비-ACTIVE·낙관락).
- [ ] `RouteArrivalAlert` 엔티티: markSuperseded/markDoneOnEdit 필드. `RouteAlertSchedule.update`: 필드 갱신 + retry 메타 리셋.
- [ ] dispatcher guard `RouteAlertDispatchStateServiceTest`: markDepartureSent·markChangeSent alert inactive → notification CANCELED + recordNotified 미호출. (claimForProcessing subquery 는 통합영역)

검증: `./gradlew test --tests '*RouteArrivalAlertService*' --tests '*RouteAlertDispatchStateService*' --tests '*RouteArrivalAlert*'` + 로컬 DB 부팅 validate(enum↔V26).

---

## 핵심 참조 (기존 코드)
- `RouteArrivalAlertService.java:56` create — 검증/tx분리/buildResponse 패턴 공유.
- `RouteArrivalAlert.java:180` markCanceled — soft-delete 안 함(구분 기준).
- `RouteArrivalAlertNotificationRepository.java:38` claimForProcessing — guard 추가 지점.
- `RouteAlertDispatchStateService.java:106` markDepartureSent — inactive no-op 추가 지점.
- `RouteArrivalAlertRepository.java` findTopByScheduleIdAndDeletedAtIsNullOrderByIdDesc / findByScheduleIdAndStatusAndDeletedAtIsNull — 재사용.
- `RouteAlertController.java:49~63` 예외 핸들러 — 409 추가.

## 정합/경합 (구현 시 주의)
- one-time 발송후 DONE 은 **반드시 soft-delete 동반**(유니크 슬롯 해제·종결 오판 방지).
- dispatcher guard 없으면 supersede 직후 PROCESSING 건이 옛 파라미터 발송 → 중복. guard 필수.
- 낙관락: PUT 409 / spawner 다음 tick.

## 검증
- 단위 통과 + 로컬 DB 부팅 validate(RouteAlertStatus enum ↔ V26 check).
- 회귀: create/getActive/cancel/spawn + 정상 dispatch 무영향.
- 스테이징: 수정→재생성 e2e(repeat/one-time × 발송전/후).

## codex 논의 결과 (2026-07-07, 반영 완료)
1. **claimForProcessing subquery** 형태 맞음(bulk join 불가). 성능 영향 작음, 단 alert row 락 안 함 → race 축소만.
2. **markDepartureSent inactive = cancel**(no-op 금지, PROCESSING 방치→reclaimStale 반복발송). `markChangeSent`도 동일 guard 필요.
3. **findTop 만으론 불충분** → ACTIVE 전체 순회 먼저 + findTop 는 already-DONE 2차 조회.
4. **409 = ScheduleNotEditableException 신설**(FavoriteController.java:56 패턴).
+ **update 가 spawn 백오프 메타 리셋 필수**(codex#2, 안 하면 tx2 재spawn skip).
+ **claimForDispatch 구성부에도 inactive guard**(codex#4).

## 잔여 race (accepted, 문서화)
edit 직전 dispatcher 가 PENDING→PROCESSING claim + Toss 발송 성공하면, edit 는 그 건 취소 불가(cancelPending 은 PENDING 만). markDepartureSent guard 가 재시도·phase 부활은 막지만 **이미 나간 푸시는 회수 불가** → "발송후 유지"와 동일 취급으로 수용(하드 보장 필요 시 alert row 락 별도 검토).
