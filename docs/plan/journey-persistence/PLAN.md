# 여정 상태 저장 · 경로 쪼개기 — 구현 계획

- 티어: **L** (신규 도메인 + 테이블 6개 + 동시성 경계 + 경로 compile 규칙)
- 개정일: 2026-07-31 (구현안 리뷰 반영)
- 구현 단위: `journey-schema-domain` → `journey-compiler` → `journey-start-api`
- 상위 맥락: [journey-navigation/PLAN.md](../journey-navigation/PLAN.md) Phase 1
- 선행 의존: `journey-auth-oauth`, `journey-route-snapshot` 머지 완료
- 다중 기기 미지원: [journey-navigation/SCOPE-CHANGES.md](../journey-navigation/SCOPE-CHANGES.md) SC-1

## 1. 한 줄 요약

**진행 중인 여정을 PostgreSQL의 기준 상태로 저장하고, 선택한 경로를 "걷기 / 기다리기 / 타고 가기" 단계와 복수 노선 후보로 쪼갠다.**

## 2. 이번 작업의 완료 상태

이 작업이 끝나면 다음이 가능하다.

- 검색 결과의 `searchSessionId + pathKey`로 여정을 시작한다.
- 앱이 재실행되면 `GET /api/journeys/active`로 최소 스냅샷을 복원한다.
- 동일 사용자의 ACTIVE Journey는 DB와 트랜잭션 양쪽에서 하나만 유지한다.
- 첫 leg는 `ACTIVE`, 나머지는 `PENDING`으로 저장한다.
- 최초 경로 JSON은 불변으로 보존한다.

이번 작업에서 하지 않는 것은 다음과 같다.

- 탑승·하차 이벤트에 따른 상태 전이
- heartbeat API와 lease 만료 evaluator
- ETA·환승 위험 계산
- FCM과 Journey notification outbox
- 재탐색 제안 생성·수락과 `reroute_proposal` 테이블

lease와 세 version 칼럼은 후속 기능의 기준 계약이므로 이번 migration에 미리 포함한다.

## 3. 확정 설계

### 3.1 패키지와 실행 역할

`domain/journey`, `application/journey`, `infrastructure/.../journey`를 신규 경계로 둔다.

패키지 분리는 코드 소유권을 위한 것이고 부하 격리는 런타임 역할이 담당한다.

- 여정 시작·조회 API: `api` 역할
- 이후 due evaluator·알림 계산: `worker` 역할
- PostgreSQL: 두 역할이 공유하는 canonical state

API 전용 controller와 시작 orchestration bean에는 `api` profile을 적용한다. 엔티티와 repository는 이후 Worker도 사용하므로 profile로 제한하지 않는다.

### 3.2 식별자

- `journey_session.id`: 애플리케이션에서 생성한 UUID
- 나머지 테이블 PK: identity bigint
- 외부 deep link와 push에는 UUID인 `journeyId`만 노출

### 3.3 세 version

| 칼럼 | 초기값 | 증가 조건 | 용도 |
|---|---:|---|---|
| `command_version` | 1 | 탑승·하차·leg 전이·종료 | 오래된 사용자 command 차단 |
| `evaluation_version` | 0 | ETA·남은 정류장·환승 상태 갱신 | 평가 snapshot 최신성 |
| `route_revision` | 1 | 재탐색 제안 수락 | 이전 경로 비동기 결과 fencing |

`JOURNEY_STARTED` 자체가 첫 canonical command이므로 `commandVersion=1`로 생성한다.

세 값은 JPA `@Version`에 맡기지 않는다. 후속 command 구현은 다음 조건부 갱신 또는 pessimistic lock을 사용해야 한다.

```sql
UPDATE journey_session
SET command_version = command_version + 1, ...
WHERE id = :journeyId
  AND status = 'ACTIVE'
  AND command_version = :baseCommandVersion
  AND route_revision = :routeRevision;
```

영향 행이 0건이면 최신 snapshot과 함께 `409 VERSION_CONFLICT`를 반환한다.

### 3.4 ACTIVE Journey 동시성

사용자당 ACTIVE Journey는 하나다.

1. DB partial unique index가 최종 불변조건을 보장한다.
2. 시작 트랜잭션은 `users` 행을 `PESSIMISTIC_WRITE`로 잠가 같은 사용자의 시작 요청을 직렬화한다.
3. `replace=false`면 현재 snapshot을 담아 `409 ACTIVE_JOURNEY_EXISTS`를 반환한다.
4. `replace=true`면 기존 Journey를 `CANCELED/REPLACED`로 바꾸고 `flush()`한 뒤 새 Journey를 insert한다.

모든 `DataIntegrityViolationException`을 409로 바꾸면 안 된다. SQLSTATE `23505`이면서 위반 이름이 `uq_journey_active_per_user`인 경우만, rollback 이후 현재 ACTIVE Journey를 다시 조회해 409로 번역한다.

### 3.5 트랜잭션 경계

Redis 조회와 경로 compile은 DB 트랜잭션 밖에서 실행한다.

```text
JourneyStartService (트랜잭션 없음)
  1. Redis route snapshot 조회
  2. snapshot owner 검증
  3. JourneyCompiler 실행
  4. route snapshot을 JsonNode로 직렬화
  5. JourneyStartTxService 호출

JourneyStartTxService (@Transactional)
  6. users 행 FOR UPDATE
  7. 기존 ACTIVE Journey 확인
  8. 필요하면 기존 Journey 취소 + flush
  9. Journey·JourneyRevision(1, ACTIVE)·Leg 저장 + flush
 10. RouteOption 저장 + flush
 11. 단일 후보 WAIT·RIDE의 selectedRouteOption 연결
 12. Transfer 저장
 13. COMMIT
```

`JourneyStartService`와 `JourneyStartTxService`는 별도 Spring bean으로 둔다. 같은 클래스의 self-invocation으로 `@Transactional` proxy를 우회하지 않는다.

Leg가 option을 가리키고 option도 소유 WAIT leg를 가리키므로 저장 순서를 명시한다. identity PK가 필요한 양방향 참조를 cascade 한 번에 저장하려 하지 않는다.

`JourneyStartTxService`가 unique 위반을 던지면 트랜잭션은 이미 rollback된 뒤 바깥 `JourneyStartService`로 돌아온다. 바깥 서비스가 `uq_journey_active_per_user`만 식별해 별도 read transaction으로 현재 ACTIVE snapshot을 조회하고 `ActiveJourneyExistsException`으로 바꾼다.

### 3.6 경로 compile과 불완전 데이터

교통 구간은 기다리기와 탑승으로 나눈다.

| `trafficType` | 생성 leg |
|---|---|
| 1 지하철 | `WAIT_SUBWAY` + `RIDE_SUBWAY` |
| 2 버스 | `WAIT_BUS` + `RIDE_BUS` |
| 3 도보 | 시작·종료는 `WALK`, 교통수단 사이는 `TRANSFER_WALK` |

초기 lifecycle state:

- `legIndex=0`: `ACTIVE`
- 나머지: `PENDING`

정류장·노선 식별자가 없다는 이유로 lifecycle state를 `UNKNOWN`으로 만들지 않는다. 수동 진행은 가능하도록 `ACTIVE/PENDING`을 유지하고 `evidence_quality=UNKNOWN`으로 표시한다. 서버가 현재 leg 자체를 판별할 수 없을 때만 `UNKNOWN`을 사용한다.

### 3.7 복수 노선 후보

`SubPathResult.lanes`에는 하나의 버스 구간에서 탈 수 있는 복수 노선이 들어올 수 있다. 임의로 첫 lane을 `JourneyLeg.routeId`로 고정하지 않는다.

- 모든 lane을 해당 `WAIT_BUS` 또는 `WAIT_SUBWAY`의 `journey_leg_route_option`에 저장한다.
- 후보가 하나면 짝이 되는 WAIT·RIDE leg의 `selectedRouteOptionId`가 같은 option을 가리킨다.
- 후보가 여러 개면 두 leg의 `selectedRouteOptionId`를 비워 둔다.
- 사용자가 노선·차량을 고르는 Phase 2 command가 WAIT·RIDE 선택값을 한 트랜잭션에서 확정한다.
- `laneIndex`, 원천 식별자, 내부 resolve 결과를 함께 보존한다.

정류장 동일성 판별 우선순위:

1. 내부 stop/station ID
2. ARS ID
3. 정규화한 이름 + 좌표 30m 이내
4. 판단 불가

같은 정류장으로 확정되면 환승 연결의 `walkLegIndex`는 null이다.

### 3.8 JSON과 시각

- `routeSnapshot`, `destinationSummary`, event payload는 `JsonNode + @JdbcTypeCode(SqlTypes.JSON)`으로 매핑한다.
- 최초 `routeSnapshot`은 `updatable=false`, 변경 메서드 미제공으로 불변 처리한다.
- 감사 칼럼은 기존 `BaseEntity` 관례의 `timestamp(6)`을 유지한다.
- lease, ETA, 이벤트 발생시각 등 절대시각은 PostgreSQL `timestamptz`, Java `Instant`를 사용한다.
- 현재 시각은 `Instant.now()`를 직접 호출하지 않고 주입한 `Clock`에서 얻는다.

## 4. 구현 단위

### 4.1 PR A — journey-schema-domain

브랜치: `feat/journey-schema-domain`

#### Migration

- [ ] `src/main/resources/db/migration/V35__create_journey.sql`
- [ ] `.gitignore`에 `!src/main/resources/db/migration/V35__create_journey.sql`

V34는 `V34__add_token_generation_to_users.sql`이 이미 사용 중이므로 재사용하지 않는다.

```sql
CREATE TABLE journey_session (
    id                    uuid primary key,
    created_at            timestamp(6) not null,
    updated_at            timestamp(6) not null,
    deleted_at            timestamp(6),
    user_id               bigint       not null references users(id),
    status                varchar(20)  not null
        constraint journey_session_status_check
            check (status in ('ACTIVE', 'COMPLETED', 'CANCELED', 'EXPIRED')),
    command_version       bigint       not null default 1
        constraint journey_command_version_check check (command_version >= 1),
    evaluation_version    bigint       not null default 0
        constraint journey_evaluation_version_check check (evaluation_version >= 0),
    route_revision        integer      not null default 1
        constraint journey_route_revision_check check (route_revision >= 1),
    current_leg_index     integer      not null default 0
        constraint journey_current_leg_index_check check (current_leg_index >= 0),
    started_at            timestamptz  not null,
    last_heartbeat_at     timestamptz  not null,
    guidance_lease_until  timestamptz  not null,
    next_evaluation_at    timestamptz,
    ended_at              timestamptz,
    end_reason            varchar(40)
        constraint journey_end_reason_check
            check (end_reason is null or end_reason in
                   ('COMPLETED', 'USER_CANCELED', 'REPLACED',
                    'CLIENT_LEASE_EXPIRED', 'ADMIN_CANCELED')),
    route_snapshot        jsonb        not null,
    destination_summary   jsonb
);

CREATE UNIQUE INDEX uq_journey_active_per_user
    ON journey_session (user_id)
    WHERE status = 'ACTIVE' AND deleted_at IS NULL;

CREATE INDEX idx_journey_due
    ON journey_session (next_evaluation_at)
    WHERE status = 'ACTIVE' AND deleted_at IS NULL;

CREATE TABLE journey_leg (
    id                    bigint generated by default as identity primary key,
    created_at            timestamp(6) not null,
    updated_at            timestamp(6) not null,
    deleted_at            timestamp(6),
    journey_id            uuid         not null references journey_session(id),
    route_revision        integer      not null check (route_revision >= 1),
    leg_index             integer      not null check (leg_index >= 0),
    type                  varchar(20)  not null
        constraint journey_leg_type_check
            check (type in ('WALK', 'WAIT_BUS', 'RIDE_BUS', 'WAIT_SUBWAY',
                            'RIDE_SUBWAY', 'TRANSFER_WALK')),
    state                 varchar(24)  not null
        constraint journey_leg_state_check
            check (state in ('PENDING', 'ACTIVE', 'CONFIRMATION_REQUIRED',
                             'COMPLETED', 'SKIPPED', 'UNKNOWN')),
    start_stop_id         varchar(64),
    end_stop_id           varchar(64),
    start_name            varchar(255),
    end_name              varchar(255),
    vehicle_id            varchar(64),
    planned_duration_sec  integer check (planned_duration_sec >= 0),
    predicted_end_at      timestamptz,
    remaining_stops       integer check (remaining_stops >= 0),
    evidence_source       varchar(30),
    evidence_observed_at  timestamptz,
    evidence_quality      varchar(20)
);

CREATE UNIQUE INDEX uq_journey_leg_index
    ON journey_leg (journey_id, route_revision, leg_index)
    WHERE deleted_at IS NULL;

CREATE TABLE journey_leg_route_option (
    id                    bigint generated by default as identity primary key,
    created_at            timestamp(6) not null,
    updated_at            timestamp(6) not null,
    deleted_at            timestamp(6),
    journey_leg_id        bigint       not null references journey_leg(id),
    option_index          integer      not null check (option_index >= 0),
    lane_index            integer      not null check (lane_index >= 0),
    route_id              varchar(64),
    source_route_id       varchar(64),
    route_name            varchar(100)
);

CREATE UNIQUE INDEX uq_journey_leg_route_option
    ON journey_leg_route_option (journey_leg_id, option_index)
    WHERE deleted_at IS NULL;

ALTER TABLE journey_leg
    ADD COLUMN selected_route_option_id bigint
        references journey_leg_route_option(id);

CREATE INDEX idx_journey_leg_selected_route_option
    ON journey_leg (selected_route_option_id)
    WHERE selected_route_option_id IS NOT NULL;

CREATE TABLE transfer_connection (
    id                     bigint generated by default as identity primary key,
    created_at             timestamp(6) not null,
    updated_at             timestamp(6) not null,
    deleted_at             timestamp(6),
    journey_id             uuid         not null references journey_session(id),
    route_revision         integer      not null check (route_revision >= 1),
    transfer_index         integer      not null check (transfer_index >= 0),
    from_leg_index         integer      not null check (from_leg_index >= 0),
    walk_leg_index         integer      check (walk_leg_index >= 0),
    to_leg_index           integer      not null check (to_leg_index >= 0),
    status                 varchar(20)  not null
        constraint transfer_connection_status_check
            check (status in ('COMFORTABLE', 'CATCHABLE', 'AT_RISK',
                              'MISSED', 'UNKNOWN')),
    current_leg_arrival_at timestamptz,
    user_ready_at          timestamptz,
    next_bus_arrival_at    timestamptz,
    transfer_slack_seconds integer,
    success_probability    numeric(4, 3)
        check (success_probability between 0 and 1),
    source                 varchar(20),
    confidence             varchar(20),
    calculated_at          timestamptz,
    data_observed_at       timestamptz,
    model_version          varchar(50),
    last_notified_status   varchar(20)
);

CREATE UNIQUE INDEX uq_transfer_connection_index
    ON transfer_connection (journey_id, route_revision, transfer_index)
    WHERE deleted_at IS NULL;

CREATE TABLE journey_event (
    id                        bigint generated by default as identity primary key,
    created_at                timestamp(6) not null,
    updated_at                timestamp(6) not null,
    deleted_at                timestamp(6),
    event_id                  uuid         not null,
    journey_id                uuid         not null references journey_session(id),
    base_command_version      bigint       not null check (base_command_version >= 1),
    route_revision            integer      not null check (route_revision >= 1),
    type                      varchar(40)  not null,
    client_occurred_at        timestamptz,
    server_received_at        timestamptz  not null,
    payload                   jsonb,
    result                    varchar(20)  not null
        constraint journey_event_result_check
            check (result in ('APPLIED', 'DUPLICATE', 'CONFLICT', 'REJECTED')),
    resulting_command_version bigint,
    rejection_reason          varchar(255)
);

CREATE UNIQUE INDEX uq_journey_event_id ON journey_event (event_id);
CREATE INDEX idx_journey_event_journey
    ON journey_event (journey_id, server_received_at);

CREATE TABLE journey_revision (
    id                    bigint generated by default as identity primary key,
    created_at            timestamp(6) not null,
    updated_at            timestamp(6) not null,
    deleted_at            timestamp(6),
    journey_id            uuid         not null references journey_session(id),
    route_revision        integer      not null check (route_revision >= 1),
    status                varchar(20)  not null
        constraint journey_revision_status_check
            check (status in ('ACTIVE', 'SUPERSEDED')),
    reason                varchar(50),
    route_snapshot        jsonb        not null,
    activated_at          timestamptz  not null,
    superseded_at         timestamptz
);

CREATE UNIQUE INDEX uq_journey_revision
    ON journey_revision (journey_id, route_revision)
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX uq_journey_active_revision
    ON journey_revision (journey_id)
    WHERE status = 'ACTIVE' AND deleted_at IS NULL;
```

#### Domain

- [ ] `JourneySession`, `JourneyLeg`, `JourneyLegRouteOption`
- [ ] `TransferConnection`, `JourneyEvent`, `JourneyRevision`
- [ ] 상태 enum과 `JourneyEndReason`
- [ ] repository 6개
- [ ] `UserRepository.findByIdForUpdate(Long)` 추가

최소 조회 계약:

```java
Optional<JourneySession> findByUserIdAndStatusAndDeletedAtIsNull(
        Long userId, JourneyStatus status);
Optional<JourneySession> findByIdAndUserIdAndDeletedAtIsNull(
        UUID journeyId, Long userId);
List<JourneyLeg> findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
        UUID journeyId, int routeRevision);
List<JourneyLegRouteOption> findByJourneyLegIdInAndDeletedAtIsNull(
        Collection<Long> journeyLegIds);
```

최소 snapshot 조회는 leg마다 option을 개별 조회하지 않는다. leg를 한 번, 해당 leg ID들의 option을 한 번 조회해 조립한다.

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select u from User u where u.id = :id")
Optional<User> findByIdForUpdate(@Param("id") Long id);
```

`JourneySession`은 UUID PK 때문에 `BaseEntity`를 상속하지 않고 감사 칼럼을 직접 선언한다.

#### 검증

- [ ] V35가 Testcontainers PostgreSQL에서 적용됨
- [ ] 모든 CHECK·partial unique index 존재
- [ ] `routeSnapshot`이 JSON string이 아니라 JSON object로 round-trip
- [ ] route snapshot 변경 시도가 엔티티 update에 반영되지 않음
- [ ] Flyway migration 파일이 Git 추적 및 bootJar 내부에 포함됨

### 4.2 PR B — journey-compiler

브랜치: `feat/journey-compiler`

#### 구현

- [ ] `JourneyLegDraft`
- [ ] `JourneyRouteOptionDraft`
- [ ] `TransferConnectionDraft`
- [ ] `JourneyPlan`
- [ ] `JourneyCompiler`
- [ ] 정류장 동일성 판별기

입력은 `PathResult`, 출력은 저장소와 무관한 `JourneyPlan`이다.

```java
public record JourneyPlan(
        List<JourneyLegDraft> legs,
        List<TransferConnectionDraft> transfers
) {}
```

Compiler는 entity를 만들거나 repository를 호출하지 않는다.

#### Fixture

- [ ] S1 도보 → 버스
- [ ] S2 버스 → 버스
- [ ] S3 지하철 → 버스
- [ ] S4 버스 → 지하철
- [ ] S6 다중 환승
- [ ] 하나의 버스 subPath에 lane이 2개 이상인 경로
- [ ] 정류장 ID가 없고 이름·좌표만 있는 경로

#### 검증

- [ ] 첫 leg ACTIVE, 나머지 PENDING
- [ ] 버스·지하철은 WAIT + RIDE로 분리
- [ ] 중간 도보만 TRANSFER_WALK
- [ ] 같은 정류장 환승은 walkLegIndex null
- [ ] lane 순서와 route option 순서가 보존됨
- [ ] 식별자 누락은 lifecycle UNKNOWN이 아니라 evidence quality UNKNOWN
- [ ] 입력 DTO는 변경하지 않음

### 4.3 PR C — journey-start-api

브랜치: `feat/journey-start-api`

#### Application

- [ ] `StartJourneyUseCase`
- [ ] `GetActiveJourneyUseCase`
- [ ] non-transactional `JourneyStartService`
- [ ] transactional `JourneyStartTxService`
- [ ] 최소 `JourneyQueryService`
- [ ] 예외와 409 fallback 번역기

시작 시각:

```text
startedAt = clock.instant()
lastHeartbeatAt = startedAt
guidanceLeaseUntil = startedAt + 3분
commandVersion = 1
evaluationVersion = 0
routeRevision = 1
currentLegIndex = 0
```

시작 시 최초 경로를 `JourneySession.routeSnapshot`에 불변 원본으로 저장하고, 같은 JSON으로 `JourneyRevision(routeRevision=1, ACTIVE)`도 생성한다. 이후 현재 경로 조회는 ACTIVE revision을 기준으로 하며 Session 원본은 감사·비교용으로만 남긴다.

#### API

```http
POST /api/journeys?replace=false
Authorization: Bearer <accessToken>
{ "searchSessionId": "...", "pathKey": "0" }

GET /api/journeys/active
Authorization: Bearer <accessToken>
```

| 상황 | 응답 |
|---|---|
| 시작 성공 | `201` + 최소 Journey snapshot |
| ACTIVE Journey 조회 성공 | `200` + 최소 snapshot |
| ACTIVE Journey 없음 | `204` |
| 기존 ACTIVE 존재 | `409 ACTIVE_JOURNEY_EXISTS` + 현재 snapshot |
| snapshot 만료·잘못된 pathKey | `410 SNAPSHOT_EXPIRED` |
| snapshot owner 불일치 | `403 SNAPSHOT_OWNER_MISMATCH` |

최소 snapshot:

- `journeyId`
- `commandVersion`, `evaluationVersion`, `routeRevision`
- `status`, `currentLegIndex`
- `guidanceLeaseUntil`, `serverTime`
- 전체 leg와 route options

사용자 ID는 JWT principal에서만 가져온다.

#### Security

```java
.requestMatchers("/api/journeys/**").authenticated()
```

`SecurityConfig`는 명시한 경로만 보호하므로 이 규칙을 빠뜨리지 않는다.

#### 동시성·통합 검증

- [ ] 동시에 시작 요청 2개 → 하나만 ACTIVE, 나머지는 409
- [ ] `replace=true` → 기존 CANCELED/REPLACED flush 후 신규 ACTIVE
- [ ] `replace=true` 요청 2개 동시 실행 → ACTIVE는 하나
- [ ] 다른 사용자는 서로 블로킹하지 않음
- [ ] unique index 외의 integrity 오류는 ACTIVE 409로 오역하지 않음
- [ ] snapshot 만료·pathKey 오류 → 410
- [ ] owner 불일치 → 403
- [ ] 인증 없이 POST/GET → 401
- [ ] `EntityManager.clear()` 뒤 ACTIVE snapshot 재조회 가능
- [ ] 기존 전체 테스트 회귀 없음

## 5. 배포

SQL을 VPS에서 수동 실행하지 않는다.

1. V35가 Git 추적되고 bootJar 내부에 포함됐는지 확인한다.
2. API JAR를 배포한다.
3. API 시작 시 Flyway가 V35를 한 번 적용한다.
4. `flyway_schema_history`의 version `35`와 success를 확인한다.
5. API health와 Journey security smoke test를 확인한다.

이번 세 PR에는 Worker 실행 로직이 없고 V35는 새 테이블만 추가하는 backward-compatible migration이다. API/Worker 분리가 이미 적용된 VPS라면 최종 배포는 `./deploy.sh api`로 수행하고 Worker 컨테이너는 유지할 수 있다.

## 6. 깨지면 안 되는 불변조건

1. 사용자당 ACTIVE Journey는 최대 하나
2. 시작 상태는 `commandVersion=1`, `evaluationVersion=0`, `routeRevision=1`
3. 첫 leg만 ACTIVE, 나머지는 PENDING
4. 최초 route snapshot은 수정하지 않음
5. ETA 평가가 commandVersion을 올리지 않음
6. 이전 routeRevision의 비동기 결과는 반영하지 않음
7. 정밀한 사용자 GPS 좌표를 DB·로그·event payload에 저장하지 않음

## 7. 후속 Phase 계약

- Phase 2: heartbeat, command state machine, active/detail snapshot 확장
- Phase 3: ETA·환승 평가와 evaluationVersion 증가
- Phase 4: due evaluator와 lease expiration
- Phase 5: FCM·Journey notification outbox
- Phase 6: `reroute_proposal` migration, 부분 재탐색 제안·수락

`journey_revision`은 수락되어 실제 안내에 사용된 경로만 `ACTIVE/SUPERSEDED`로 저장한다. 아직 수락되지 않은 대안은 Phase 6의 `reroute_proposal`이 점수·만료·dedup과 함께 보관하며, 수락 transaction이 새 ACTIVE JourneyRevision을 만든다.
