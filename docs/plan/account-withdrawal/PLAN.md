# 회원 탈퇴 (account-withdrawal) — 실행 플랜

티어: L. 두 PR 로 분할. fresh main 기준 독립 브랜치 각각.

이 플랜은 컨텍스트 없이 읽어도 구현 가능하도록 쓴다. 설계 근거는
`CONTEXT.md`(용어)와 `docs/plan/account-withdrawal/ADR-deletion-boundary.md`(삭제 경계)에 있다.

---

## 배경 사실 (조사로 확정)

- `users.deleted_at` 컬럼은 V1 baseline 에 이미 존재. **마이그레이션 불필요**.
- 삭제/변경 대상 테이블(물리13/소프트9/손대지않음2) 전부 `deleted_at` 컬럼 보유.
  favorite_route·route_search_log 도 V1 에 deleted_at 있음.
- 이 저장소 관례: 삭제 = `deleted_at` 찍기, 조회 = `deleted_at IS NULL` 명시(자동 필터 없음).
- `users` FK 그래프(2단):
  - 직접(`user_id`): `auth_identity`, `route_search_history`(ON DELETE CASCADE), `user_push_token`,
    `route_alert_schedule`, `route_arrival_alert`, `bus_arrival_alert`, `journey_session`,
    `journey_notification`, `journey_telemetry_event`, `reroute_proposal`, `subway_live_activity_token`
  - `alert_id` 매달림: `route_arrival_alert_notification`(→route_arrival_alert),
    `bus_alert_notification`(→bus_arrival_alert)
  - `journey_id`/`journey_leg_id` 매달림: `journey_leg`, `journey_leg_route_option`,
    `transfer_connection`, `journey_event`, `journey_revision`, `subway_leg_binding`,
    `subway_binding_event`, `subway_ring_push_outbox`
- 인증 세대검증 지점: `JwtAuthFilter.isStillValid` → `TokenGenerationPort.currentGeneration`
  → `UserTokenGenerationAdapter`(`src/main/java/.../infrastructure/adapter/out/auth/UserTokenGenerationAdapter.java`).
  현재 `deleted_at` 안 봄 → 소프트 삭제된 사용자 토큰이 통과함.
- `@AuthenticationPrincipal Long userId` 로 인증 사용자 id 획득 (SessionController/MeController 참고).
- 스키마 IT 패턴 존재: `src/test/java/.../domain/push/UserPushTokenSchemaIT.java`
  (Testcontainers Postgres + Flyway + JdbcTemplate).

## 삭제 경계 (ADR 0001 최종표)

> **codex 리뷰 반영(2026-08-27)**: 누락 테이블 2개 추가, 삭제금지 계약 2개를 소프트에서
> "손대지 않음"으로 분리. 상세는 ADR-deletion-boundary.md.

**물리 삭제 (13 대상)** — 남으면 발송되거나 재가입 막거나 개인정보 남음:
1. `auth_identity` (userId)
2. `user_push_token` (userId)
3. `subway_live_activity_token` (userId)
4. `subway_ring_push_outbox` (journeyId 경유)
5. `route_arrival_alert` (userId) + `route_arrival_alert_notification` (alertId 경유)
6. `bus_arrival_alert` (userId) + `bus_alert_notification` (alertId 경유)
7. `route_alert_schedule` (userId)
8. `route_search_history` (userId; 이미 ON DELETE CASCADE 지만 명시 삭제)
9. `favorite_route` (userId; 집·회사 좌표) ← codex 발견 누락
10. `route_search_log` (userId nullable, V1; 검색이력+response_json) ← codex 발견 누락
11. `TossUserToken` (userKey 경유; derived deleteByUserKey, @Modifying 아님)
12. `users.toss_user_key` → NULL

**소프트 삭제 (9 대상)** — `deleted_at = now`:
`journey_session`, `journey_leg`, `journey_leg_route_option`, `transfer_connection`,
`journey_event`, `journey_revision`, `journey_telemetry_event`, `reroute_proposal`,
`subway_binding_event`

**손대지 않음 (2 대상)** — 마이그레이션이 삭제 금지 명시:
- `journey_notification` (V40:10-13: 지우면 delivery_key 재열림→재발송)
- `subway_leg_binding` (V44:14-16: version/reset_generation 리셋)
- 근거: 둘 다 due 쿼리가 `journey_session` 조인 + `deleted_at IS NULL` → journey 소프트삭제로
  읽기·발송이 이미 막힘. 스키마 테스트에서 별도 버킷(행 존치 허용)으로 다룬다.

**`users`**: 소프트 삭제(`deleted_at`) + `tokenGeneration++` + `toss_user_key=NULL`.

물리 삭제는 자식→부모 역순(codex ① 검증: FK 방향과 일치). 소프트 삭제는 순서 무관.
Live Activity 종료 푸시는 넣지 않음(ADR 결과 참조). 외부 호출 0, 트랜잭션 1개.

---

# PR1 — 소프트 삭제 사용자 인증 차단 가드

독립적으로 옳은 변경. PR2 없이도 유효(소프트 삭제된 users 행을 인증에서 배제).

## 변경 파일

### 1. `UserRepository` — deleted_at 인지 세대조회
`src/main/java/watoo/grd/nextroute/domain/user/repository/UserRepository.java`
- 추가:
```java
@Query("select u.tokenGeneration from User u where u.id = :id and u.deletedAt is null")
Optional<Integer> findTokenGenerationOfActive(@Param("id") Long id);
```
- 이유: 기본 `findById` 는 소프트 삭제 행도 반환. 활성 사용자만 세대값을 준다.

### 2. `UserTokenGenerationAdapter` — 위 메서드로 교체
`src/main/java/watoo/grd/nextroute/infrastructure/adapter/out/auth/UserTokenGenerationAdapter.java`
- `currentGeneration` 을 `findById(...).map(getTokenGeneration)` →
  `findTokenGenerationOfActive(id).map(OptionalInt::of).orElseGet(OptionalInt::empty)` 로 변경.
- 효과: 삭제된 사용자는 `empty` → `JwtAuthFilter.isStillValid` 가 access 거부.
  `SessionService.reissue` 도 같은 방어가 필요 → 아래 3.

### 3. `SessionService.requireUser` — 활성 사용자만
`src/main/java/watoo/grd/nextroute/application/auth/service/SessionService.java`
- `requireUser` 가 `deleted_at IS NULL` 을 보게 한다. 방법: `UserRepository` 에
  `findByIdAndDeletedAtIsNull` 추가 후 `reissue`/`logout`/`me` 경로에서 사용.
- 이유: reissue 는 refresh 토큰으로 들어와 세대검증만 하므로, 삭제 사용자의 refresh 가
  세대만 맞으면 통과할 수 있다. 활성 조건으로 막는다.

### 4. 테스트
`src/test/java/watoo/grd/nextroute/application/auth/SessionServiceTest.java`(기존 있으면 추가):
- 소프트 삭제된 사용자의 reissue → `SessionException`.
`src/test/java/watoo/grd/nextroute/.../UserTokenGenerationAdapter` 계열 IT(또는 기존 IT에 추가):
- 소프트 삭제된 사용자 `currentGeneration` → `empty`.

## PR1 검증
- `./gradlew compileJava compileTestJava`
- `./gradlew test --tests "*SessionServiceTest" --tests "*TokenGeneration*"`

## PR1 작업 순서
- [ ] UserRepository 메서드 2개 추가
- [ ] UserTokenGenerationAdapter 교체
- [ ] SessionService requireUser 활성 조건화
- [ ] 테스트 2개
- [ ] 컴파일 + 테스트

---

# PR2 — 탈퇴 유스케이스 + 엔드포인트 + 스키마 테스트

PR1 병합 후 fresh main 기준. PR1 의 가드가 있어야 탈퇴가 실제로 인증을 끊는다.

## 신규 파일

### 1. UseCase 포트
`src/main/java/watoo/grd/nextroute/application/auth/port/in/WithdrawUseCase.java`
```java
public interface WithdrawUseCase {
    /** 계정 탈퇴. 되돌릴 수 없다. 이미 삭제된 사용자면 조용히 성공(멱등). */
    void withdraw(long userId);
}
```

### 2. Service
`src/main/java/watoo/grd/nextroute/application/auth/service/WithdrawService.java`
- `@Service`, `@Transactional`(단일 트랜잭션).
- 순서:
  1. 존재/멱등 확인: `userRepository.findByIdAndDeletedAtIsNull(userId)` 로 활성 사용자 조회.
     없으면(미존재 또는 이미 탈퇴) 조용히 return. tossUserKey 값만 읽어 둔다.
     **잠금이 필요하면** `findByIdForUpdate` 를 쓰되, 이 엔티티를 dirty-checking 으로 수정하지
     않는다(아래 ⚠️ 참조).
  2. **물리 삭제** (자식→부모 역순):
     - 알림 자식: `route_arrival_alert_notification`, `bus_alert_notification` (alertId in ...)
     - 알림 부모: `route_arrival_alert`, `bus_arrival_alert`, `route_alert_schedule`
     - journey 매달린 물리: `subway_ring_push_outbox` (journeyId in ...)
     - 발송 주소: `subway_live_activity_token`, `user_push_token`
     - 개인 이력: `route_search_history`, `favorite_route`, `route_search_log`
     - `auth_identity`
     - toss: `TossUserToken.deleteByUserKey(tossUserKey)` (tossUserKey != null 일 때만)
  3. **소프트 삭제** (`deleted_at=now`): journey 계열 9개 (userId 또는 journeyId in 서브쿼리).
     `journey_notification`·`subway_leg_binding` 은 제외(손대지 않음).
  4. **`users` 최종 변경도 벌크 update 로** — `UPDATE users SET deleted_at=:now,
     token_generation=token_generation+1, toss_user_key=NULL WHERE id=:userId`.
- 삭제/변경은 각 repository 에 `@Modifying @Query` 벌크 메서드로 수행(엔티티 로딩 회피).
  journeyId 종속 테이블은 `where journey_id in (select id from journey_session where user_id=:userId)`.

⚠️ **codex ② — `clearAutomatically` 함정**:
- 벌크 DML 은 영속성 컨텍스트를 우회한다. `@Modifying(clearAutomatically=true)` 를 쓰면
  같은 트랜잭션의 **영속 `User` 엔티티가 detach** 되어, 마지막에 `user.bump()/markDeleted()` 를
  dirty-checking 으로 하려던 변경이 **flush 안 되고 유실**된다(JourneyNotificationRepository:88-91
  가 같은 위험을 명시).
- **회피**: users 변경을 관리 엔티티 수정이 아니라 4)의 **벌크 update 한 방**으로 처리 →
  detach 여부와 무관. 이러면 벌크 메서드들에 `clearAutomatically=true` 를 일괄로 붙여도 안전하다.
- 테스트는 `JdbcTemplate` 사후검증만 하지 말고, 같은 트랜잭션에서 JPA 재조회가 stale 을 보지
  않는지도 1건 확인(codex ② 후단).

### 3. Controller — DELETE /api/auth/me
`src/main/java/watoo/grd/nextroute/infrastructure/adapter/in/api/auth/WithdrawController.java`
(또는 MeController 확장 — 단 MeController 는 `/api/me`. 계약은 `DELETE /api/auth/me` 이므로
신규 컨트롤러 권장, `@RequestMapping("/api/auth/me")`)
```java
@DeleteMapping
public ResponseEntity<Void> withdraw(@AuthenticationPrincipal Long userId) {
    withdrawUseCase.withdraw(userId);
    return ResponseEntity.noContent().build(); // 204
}
```
- 재호출은 PR1 가드로 자연히 401(access 죽음). 별도 처리 없음.

## 수정 파일

### 4. 각 도메인 repository — 벌크 삭제 메서드 추가
물리 삭제 대상별 `@Modifying @Query("delete ...")` 또는 파생 `deleteBy...`:
- `RouteArrivalAlertRepository`, `RouteArrivalAlertNotificationRepository`,
  `BusArrivalAlertRepository`, `BusAlertNotificationRepository`, `RouteAlertScheduleRepository`,
  `SubwayRingPushOutboxRepository`, `SubwayLiveActivityTokenRepository`, `UserPushTokenRepository`,
  `RouteSearchHistoryRepository`, `FavoriteRouteRepository`, `RouteSearchLogRepository`,
  `AuthIdentityRepository`, `TossUserTokenRepository`(기존 deleteByUserKey 재사용)
소프트 삭제 대상별 `@Modifying @Query("update E e set e.deletedAt=:now where ...")`:
- journey 계열 9개 repository (journey_notification·subway_leg_binding 제외).
- `UserRepository`: users 최종 벌크 update 메서드 추가(deleted_at+generation+toss NULL 한 방).
- 주의: 벌크는 영속성 컨텍스트 우회. users 를 벌크로 처리하므로 `clearAutomatically=true`
  일괄 적용 안전(위 ⚠️ codex ②).

### 5. `UserPushTokenRepository` 주석 정정
`src/main/java/watoo/grd/nextroute/domain/push/repository/UserPushTokenRepository.java:19`
- "탈퇴 시 soft delete 해야 한다" 주석을 "탈퇴 시 물리 삭제한다(발송 주소는 남기지 않음, ADR 0001)"로 정정.

## 테스트

### 6. 스키마 경계 테스트 (핵심 계약)
`src/test/java/watoo/grd/nextroute/application/auth/WithdrawalDeletionBoundaryIT.java`
- Testcontainers Postgres + Flyway (`UserPushTokenSchemaIT` 패턴 복제).
- 한 사용자에 전 기능 데이터 심기 → `withdraw()` → JdbcTemplate + `information_schema` 로:
  - **물리 삭제 대상**: 해당 user 관련 행 count == 0
  - **소프트 삭제 대상**: 해당 user 관련 행 전부 `deleted_at IS NOT NULL`
  - `users.deleted_at IS NOT NULL`, `toss_user_key IS NULL`, `token_generation` 증가
- FK 그래프로 `users`/`journey_session` 참조 테이블을 읽어, 코드가 아는 물리/소프트/손대지않음
  목록에 없는 테이블이 새로 붙으면 실패하게 한다(자식 추가 시 조용한 깨짐 방지).

⚠️ **codex ③ — FK 그래프 쿼리 정정** (기존 `JourneySchemaIT.java:160-164` 패턴 참고):
- `table_constraints`+`key_column_usage` 만으로는 **참조 대상 테이블을 못 얻는다**. 참조 측은
  `referential_constraints` → `constraint_column_usage`(또는 unique_constraint 조인)로 얻어야
  `자식→부모` 간선이 완성된다.
- **순환 FK 존재**: `journey_leg.selected_route_option_id ↔ journey_leg_route_option.journey_leg_id`
  (V35). `WITH RECURSIVE` 를 쓰면 방문 경로 배열로 cycle 가드 필수, 안 쓰면 2단 이하만 커버.
  self-ref FK 처리도 포함.
- composite FK 는 컬럼별 행이 나오므로 constraint 단위로 distinct 처리(간선 중복 방지).
- **`table_schema='public'` 필터를 모든 조인에 적용**(오탐 방지).
- **버킷 3종 검증**: 물리=행 0 / 소프트=`journey_session` 및 계열 9개는 전부 `deleted_at` non-null
  / 손대지않음=`journey_notification`·`subway_leg_binding` 은 검증 제외(행 존치 정상).
- 이 테스트는 신규 테이블뿐 아니라 **현재 미등록 테이블도 즉시 잡는다**(codex 가 favorite_route·
  route_search_log 를 이 방식으로 발견). 최초 실행 시 목록 완전성부터 확인할 것.

### 7. 멱등성 테스트
- 이미 탈퇴한 사용자 `withdraw()` 재호출 → 예외 없이 성공, 상태 불변.

## PR2 검증
- `./gradlew compileJava compileTestJava`
- `./gradlew test --tests "*WithdrawalDeletionBoundaryIT" --tests "*Withdraw*"`
- (수동, 배포 후) `DELETE /api/auth/me` 204 → 같은 토큰 재요청 401.

## PR2 작업 순서
- [ ] WithdrawUseCase 포트
- [ ] 각 repository 벌크 삭제/소프트삭제 메서드
- [ ] UserPushTokenRepository 주석 정정
- [ ] WithdrawService (순서 엄수: 알림자식→알림부모→outbox→토큰→이력→identity→toss→journey소프트→users)
- [ ] WithdrawController (DELETE /api/auth/me, 204)
- [ ] 스키마 경계 IT + 멱등성 테스트
- [ ] 컴파일 + 테스트

---

## 배포
- 마이그레이션 없음. VPS 수동배치 불필요.
- `JOURNEY_SUBWAY_RING_PUSH_ENABLED=true` 는 별개 작업(사용자 예고). 탈퇴는 이 플래그와 무관
  (END 푸시 미포함).

## 후속 (이 플랜 범위 밖, 노트로 이관)
- Apple `/auth/revoke`: authorization code 교환 경로 신설 필요. 앱 심사 제출 전 필수. 별건 PR.
- 토스 연결끊기 콜백의 계정 미삭제 비대칭 정리(현재 토큰만 폐기).
