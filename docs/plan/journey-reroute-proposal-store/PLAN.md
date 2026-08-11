# journey-reroute-proposal-store — 구현 계획 (Phase 6 ②, L티어)

- 상위 인덱스: [PHASE-6.md](../journey-navigation/PHASE-6.md) §② · [PLAN.md](../journey-navigation/PLAN.md) §6.6
- 요구사항: [REQUIREMENTS.md](../journey-navigation/REQUIREMENTS.md) §4.8 (R7·R8)
- 확정 기술 결정: [TECHNICAL_DECISIONS.md](../journey-navigation/TECHNICAL_DECISIONS.md) §4 · §6.3
- 범위 변경: [SCOPE-CHANGES.md](../journey-navigation/SCOPE-CHANGES.md) SC-2 · SC-10
- 브랜치: `feat/journey-reroute-proposal-store` (fresh `main` 기준 독립 브랜치)
- 작성일: 2026-08-11

## 0. 이 PR 의 범위

**재탐색 제안을 담아 두는 표와 그 수명, 그리고 뒤 PR 넷이 따를 후보 계약을 만든다.**

| 넣는다 | 안 넣는다 |
|---|---|
| V42 `reroute_proposal` 표·엔티티·저장소 | 후보 생성 로직 (③ routeOption, ④ 다른 경로) |
| canonical 후보 계약(`apply` + `display`)과 `candidateId` | 수락·revision 교체 (⑤) |
| `GET .../reroute-proposals/active`, `POST .../reroute-proposals` | 제안 알림·체크포인트 (⑥) |
| lazy 만료 전이 + 배치 sweep·supersede·부분 unique | 트리거 판정 (①) — 포트로 받기만 한다 |
| 여정 종료 시 제안 정리(기존 두 경로에 한 줄씩) | |
| 후보 공급자 포트(`RerouteCandidateProvider`)와 적격성 포트 | 위 두 포트의 실제 구현 |
| consumer contract test | |

**이 PR 만 머지하면 기능은 아무것도 안 보인다.** 적격성 포트의 기본 구현이 항상 거절이고
후보 공급자가 하나도 없어서, `POST` 는 `409 REROUTE_NOT_ELIGIBLE`, `GET` 은 `proposal: null` 이다.
그것이 의도다 — 표와 계약을 먼저 얼려 ③④⑤가 같은 것을 보게 한다.

## 1. 핵심 설계 결정

### 1.1 후보는 두 겹이다 — `apply` 와 `display`

화면 요약만 저장하면 `REMAINING_PATH` 를 수락했을 때 **새 leg·routeOption·geometry 를 만들 재료가
없다.** 그래서 후보마다 적용 재료(`apply`)와 화면 요약(`display`)을 같이 저장한다.

`apply` 는 종류마다 다르다.

- `ROUTE_OPTION` — 어떤 `WAIT_*` leg 의 어떤 후보를 고를지. `waitLegIndex`·`routeOptionIndex`·
  `routeId`·`directionKey`
- `REMAINING_PATH` — 남은 구간의 **경로 원본 그대로**(`PathResult` JSON) + 어느 leg 부터 갈아끼우는지
  (`fromLegIndex`). ⑤가 이 값을 `JourneyCompiler.compile(PathResult)` 에 그대로 먹인다

`display` 는 앱이 그리는 값이고 `apply` 에서 파생된다(R6): 목적지 도착 예정 시각·총 소요·환승 횟수·
도보 시간·개선폭·데이터 신선도.

**기각한 대안** — "화면 DTO 만 저장하고 수락 시 다시 검색한다". 수락은 사용자가 이미 고른 뒤라
그 시점에 다른 결과가 나오면 **고른 것과 다른 경로**로 여정이 바뀐다.

**`apply` 는 `apply` 로만 남고 API 밖으로 안 나간다**(§1.8).

### 1.1.1 다형 `apply` 의 이름은 계약이다

`apply` 가 두 종류라 JSON 에 종류를 적는 칸(discriminator)이 필요하다. Jackson 기본 동작에
맡기면 클래스 이름이 그대로 JSON 에 박혀 **리팩터링 한 번이 저장된 행을 못 읽게 만든다.**

`@JsonTypeInfo(use = NAME, include = PROPERTY, property = "type")` +
`@JsonSubTypes({@Type(value = RouteOptionApply.class, name = "ROUTE_OPTION"),
@Type(value = RemainingPathApply.class, name = "REMAINING_PATH")})` 로 이름을 **손으로 고정**한다.
값은 `RerouteProposalKind` 및 V42 `kind` check 제약과 **같은 문자열**이다 — 세 곳이 다른 이름을
쓰면 어느 것이 진짜인지 아무도 모른다.

모르는 `type` 은 역직렬화에서 거절한다. 조용히 null 로 만들면 ⑤가 빈 후보를 적용하려 든다.

### 1.2 `candidateId` 는 값이지 순번이 아니다

제안 하나에 후보가 최대 3개인데 수락 경로는 `.../{proposalId}/accept` 하나다. 후보를 가리킬 값이
없으면 서버가 무엇을 적용할지 모른다. 저장 시점에 후보마다 UUID 를 부여해 응답에 싣고, ⑤의 수락
요청 body 가 그 값을 받는다.

**기각한 대안** — 배열 순번. 정렬 기준이 바뀌거나 후보가 하나 걸러지면 **다른 경로가 적용된다.**

### 1.3 만료는 지나갈 때 넘기고(lazy), 아무도 안 지나가면 배치가 쓸어 담는다

`expires_at` 이 지나도 상태는 저절로 안 바뀐다. 부분 unique(`WHERE status='ACTIVE'`)만 믿으면
**만료된 `ACTIVE` 행이 자리를 잡고 새 제안 삽입을 영영 막는다.**

**첫째, lazy 전이.** 진입점 셋이 지나갈 때 함께 넘긴다.

1. `GET active` — 만료된 행을 `ACTIVE` 로 취급하지 않고 전이시킨 뒤 없다고 답한다
2. `POST` 생성 — **같은 트랜잭션에서** "만료 전이 → supersede → insert"
3. 수락(⑤) — 재검증 전에 같은 전이를 먼저 돌린다 (⑤가 이 서비스의 메서드를 부른다)

**둘째, 배치 sweep.** 초안은 lazy 만으로 끝냈는데, 그 판단은 **"활성 제안이 있는 여정은 사용자가
화면을 보고 있다"는 가정**에 기대고 있었다. 그 가정이 깨지는 경우가 실제로 있다 — 앱이 죽거나
백그라운드로 들어가면 `GET`·`POST`·수락 **어느 것도 오지 않는다.** 그러면 TTL 이 지난 행이
`ACTIVE` 인 채로 남고, 사용자가 돌아왔을 때 그 자리 때문에 새 제안이 안 만들어진다.

그래서 lazy 는 **즉시성** 목적으로 유지하되 **bounded scheduled sweep** 을 함께 둔다. 새로
발명하지 않고 `JourneyLeaseExpiryService.sweep()`/`JourneyLeaseExpiryScheduler` 의 모양을 그대로
따른다 — batch size × maxSweepsPerTick 으로 한 tick 의 일을 묶고, 트랜잭션은 SQL 한 문장만 감싼다.

**셋째, 여정이 끝나면 그 여정의 제안도 접는다.** 끝난 여정의 제안이 살아 있으면 수락 API 가
"여정은 끝났는데 제안은 유효한" 상태를 본다. 정리 자리는 이미 있다 — 끝나는 길이 모이는 두 곳에
`cancelPendingByJourneyId` 옆에 한 줄을 더한다(§2.12).

### 1.4 만료돼도 행을 지우지 않는다

지우면 늦게 온 수락 요청이 "그런 제안 없음(404)"인지 "만료됨(409)"인지 구별이 안 된다. 사용자는
왜 안 되는지 모르고, 운영은 제안이 몇 번 늦었는지 못 센다.

### 1.5 개정본 컬럼은 하나다

초안은 `route_revision` 과 `base_route_revision` 을 따로 뒀는데 **두 값이 언제나 같다** — 제안은
만들어진 그 개정본을 기준으로만 유효하다. 둘로 두면 어긋난 상태가 생길 수 있고, 어느 쪽이 진짜인지
읽는 사람이 헷갈린다. `route_revision` 하나만 두고 `base_command_version` 을 함께 저장한다.

### 1.6 외부 호출은 트랜잭션 밖

후보 생성(③④)은 실시간 버스 API·ML·ODsay 를 부른다. TECHNICAL_DECISIONS §6.3 그대로
"트랜잭션 밖에서 후보 평가 → 저장만 트랜잭션"이다. 기존 `JourneyStartService`/`JourneyStartTxService`
분리와 같은 모양을 따른다.

### 1.7 `apply` 는 API 응답에 싣지 않는다

후보 하나에 `PathResult` 원본이 통째로 들어가고 후보는 최대 3개다. 그대로 응답에 실으면 **수십 KB
JSON 이 재탐색 화면을 열 때마다 이동통신망으로 나간다** — 지하철에서 초조한 사용자에게 가장 나쁜
크기다. 게다가 앱은 그 값을 쓰지 않는다. 앱이 필요한 것은 "무엇을 고를지"(`candidateId`)와
"어떻게 보일지"(`display`)뿐이다.

그래서 읽기 모델을 둘로 나눈다.

| 모델 | 내용 | 쓰는 곳 |
|---|---|---|
| `RerouteProposalView` | `apply` 포함 전체 | 서버 내부. ⑤ 수락 경로 |
| `RerouteProposalResponse` | `candidateId` + `display` 만 | API 응답(`GET`·`POST`) |

**저장은 여전히 inline 이다.** `PathResult` 를 Redis 참조로 두는 대안은 기각한다 —
`RedisRouteSearchSnapshotAdapter` 는 TTL 만료 시 조용히 empty 를 돌려주는 best-effort 캐시라,
사용자가 수락을 누르는 순간 근거가 사라져 있을 수 있다. `JourneySession`·`JourneyRevision` 이
이미 경로 원본을 jsonb 로 통째 저장하는 선례이기도 하다.

### 1.8 ①③④가 없어도 이 PR 은 안전하다

- `RerouteEligibilityPolicy` 기본 구현은 **항상 거절**(`disabled`). ①이 실제 구현을 올리면 교체된다
- `RerouteCandidateProvider` 구현이 하나도 없으면 후보 0개 → 저장 0행 → `proposal: null`

안전한 방향이 기본값이다. 반대로 두면 ①이 늦어지는 동안 아무 제한 없이 외부 검색이 돈다.

## 2. 변경 파일

### 2.1 신규 — 마이그레이션

**`src/main/resources/db/migration/V42__create_reroute_proposal.sql`**

```sql
-- 아직 사용자가 수락하지 않은 재탐색 대안. 실제 안내에 쓰인 경로는 journey_revision 이 갖는다.
--
-- 왜 따로 두나. 제안은 점수·만료·중복 제거가 필요해 성격이 다르고, 여기 행이 journey_revision 에
-- 섞이면 "지금 안내 중인 경로"를 묻는 질문이 매번 status 필터를 달고 다녀야 한다.

CREATE TABLE reroute_proposal (
    id                    bigint generated by default as identity primary key,
    created_at            timestamp(6) not null,
    updated_at            timestamp(6) not null,
    deleted_at            timestamp(6),

    journey_id            bigint      not null references journey_session(id),
    -- 소유권을 여기서도 들고 있는다. 조회마다 journey_session 을 조인하지 않기 위해서다.
    user_id               bigint      not null references users(id),

    -- 이 제안이 유효한 개정본. 수락 재검증의 기준이기도 하다(둘로 나누지 않는다).
    route_revision        integer     not null,
    transfer_index        integer     not null,

    -- ROUTE_OPTION | REMAINING_PATH. 하나로 뭉치면 ③과 ④ 중 무엇이 일하는지 운영에서 못 가른다.
    kind                  varchar(20) not null,
    status                varchar(20) not null,

    -- 낙관적 락 기준. 제안을 만든 뒤 사용자가 누르기까지 최대 2분이 있다.
    base_command_version  bigint      not null,

    -- 후보 목록. 후보마다 candidateId + apply(적용 재료) + display(화면 요약)를 갖는다.
    -- display 만 저장하면 REMAINING_PATH 수락 시 새 leg 를 만들 재료가 없다.
    candidates            jsonb       not null,

    -- 후보 평가에 쓴 실시간 스냅샷 시각. 신선도 표시와 사후 검증의 기준.
    snapshot_observed_at  timestamptz not null,
    expires_at            timestamptz not null,

    -- 만료·수락·교체가 언제 왜 일어났는지. 뭉뚱그리면 제안이 늦은 건지 밀린 건지 못 가른다.
    resolved_at           timestamptz,
    resolved_reason       varchar(40),

    constraint reroute_proposal_kind_check
        check (kind in ('ROUTE_OPTION', 'REMAINING_PATH')),
    constraint reroute_proposal_status_check
        check (status in ('ACTIVE', 'ACCEPTED', 'EXPIRED', 'SUPERSEDED'))
);

-- 환승 하나에 살아 있는 제안은 하나다. 부분 인덱스인 것이 중요하다 - 전체 unique 로 걸면
-- 지난 제안이 자리를 잡아 같은 환승에서 두 번째 제안을 영영 못 만든다.
-- (journey_notification.delivery_key 를 전체 unique 로 건 탓에 그 표를 못 지우게 된 전례가 있다)
CREATE UNIQUE INDEX uq_reroute_proposal_active
    ON reroute_proposal (journey_id, transfer_index)
    WHERE status = 'ACTIVE' AND deleted_at IS NULL;

CREATE INDEX ix_reroute_proposal_journey
    ON reroute_proposal (journey_id, created_at DESC);
-- lazy 만료가 이 인덱스로 돈다.
CREATE INDEX ix_reroute_proposal_expiry
    ON reroute_proposal (expires_at)
    WHERE status = 'ACTIVE';

COMMENT ON TABLE reroute_proposal IS
    '수락 전 재탐색 대안. 안내에 쓰인 경로는 journey_revision 이 갖는다';
COMMENT ON COLUMN reroute_proposal.candidates IS
    '후보 목록. candidateId + apply(적용 재료) + display(화면 요약)';
COMMENT ON COLUMN reroute_proposal.route_revision IS
    '제안이 유효한 개정본. 수락 재검증 기준이며 base_route_revision 을 따로 두지 않는다';
```

### 2.2 수정 — 마이그레이션 whitelist

**`.gitignore`** — 29행(`!...V41...`) 다음에 추가:

```
!src/main/resources/db/migration/V42__create_reroute_proposal.sql
```

migration 은 파일별 whitelist 다. 빠뜨리면 로컬에서만 돌고 배포에 안 올라간다.

### 2.3 수정 — 테스트 초기화

**`src/test/resources/db/it/journey-reset.sql`** — `DROP TABLE IF EXISTS journey_telemetry_event
CASCADE;` **위**에 추가:

```sql
DROP TABLE IF EXISTS reroute_proposal CASCADE;
```

FK 방향상 `journey_session` 보다 먼저 지워야 한다.

### 2.4 신규 — 도메인

| 파일 | 내용 |
|---|---|
| `domain/journey/entity/RerouteProposal.java` | 엔티티. `BaseEntity` 상속, `candidates` 는 `@JdbcTypeCode(SqlTypes.JSON) JsonNode`. 상태 전이 메서드 `expire(Instant)`·`supersede(Instant)`·`accept(Instant)` — 각각 `resolvedAt`·`resolvedReason` 을 함께 채운다 |
| `domain/journey/entity/RerouteProposalKind.java` | `ROUTE_OPTION`, `REMAINING_PATH`. 값 목록은 V42 의 check 제약이 소유한다(`JourneyRevisionStatus` 주석 관례를 따른다) |
| `domain/journey/entity/RerouteProposalStatus.java` | `ACTIVE`, `ACCEPTED`, `EXPIRED`, `SUPERSEDED` |
| `domain/journey/repository/RerouteProposalRepository.java` | 아래 §2.5 |

### 2.5 신규 — 저장소 쿼리

**`domain/journey/repository/RerouteProposalRepository.java`**

```java
public interface RerouteProposalRepository extends JpaRepository<RerouteProposal, Long> {

    /** 살아 있는 제안 하나. 만료 전이를 먼저 돌린 뒤에 부른다. */
    Optional<RerouteProposal> findByJourneyIdAndTransferIndexAndStatusAndDeletedAtIsNull(
            Long journeyId, int transferIndex, RerouteProposalStatus status);

    /**
     * 만료·개정본 어긋남을 한 문장으로 정리한다.
     *
     * <p>둘을 함께 넘기는 이유 — 수락으로 개정본이 오르면 이전 개정본의 제안은 만료 시각과
     * 무관하게 무의미하다. 남겨 두면 부분 unique 가 새 제안 삽입을 막는다.
     */
    @Modifying(clearAutomatically = false)
    @Query(value = """
            UPDATE reroute_proposal
               SET status = CASE WHEN route_revision <> :currentRevision
                                 THEN 'SUPERSEDED' ELSE 'EXPIRED' END,
                   resolved_at = :now,
                   resolved_reason = CASE WHEN route_revision <> :currentRevision
                                          THEN 'route-changed' ELSE 'ttl' END,
                   updated_at = :auditNow
             WHERE journey_id = :journeyId
               AND status = 'ACTIVE'
               AND deleted_at IS NULL
               AND (expires_at <= :now OR route_revision <> :currentRevision)
            """, nativeQuery = true)
    int resolveStale(@Param("journeyId") Long journeyId,
                     @Param("currentRevision") int currentRevision,
                     @Param("now") Instant now,
                     @Param("auditNow") LocalDateTime auditNow);

    /** 같은 환승의 살아 있는 제안을 새 제안에 자리를 내주게 한다. */
    @Modifying(clearAutomatically = false)
    @Query(value = """
            UPDATE reroute_proposal
               SET status = 'SUPERSEDED', resolved_at = :now,
                   resolved_reason = 'replaced', updated_at = :auditNow
             WHERE journey_id = :journeyId AND transfer_index = :transferIndex
               AND status = 'ACTIVE' AND deleted_at IS NULL
            """, nativeQuery = true)
    int supersedeActive(@Param("journeyId") Long journeyId,
                        @Param("transferIndex") int transferIndex,
                        @Param("now") Instant now,
                        @Param("auditNow") LocalDateTime auditNow);
}
```

**시각 파라미터 두 종류를 조심할 것** — `now`·`expiresAt` 은 도메인 시각(`Instant`, timestamptz),
`auditNow` 는 `BaseEntity` 감사 칼럼(`LocalDateTime`, `timestamp(6)`)이다.
`JourneyNotificationRepository` 주석이 같은 함정을 적어 뒀다.

### 2.6 신규 — 후보 계약 (이 PR 의 진짜 산출물)

**`application/journey/dto/RerouteCandidate.java`**

```java
public record RerouteCandidate(
        String candidateId,                 // 저장 시점 UUID. 순번 금지
        RerouteProposalKind kind,
        RerouteCandidateApply apply,        // 적용 재료
        RerouteCandidateDisplay display) {  // 화면 요약
}
```

**`application/journey/dto/RerouteCandidateApply.java`** — sealed 로 두 종류를 닫는다.
종류가 늘어나려면 어차피 V42 `kind` check·`RerouteCandidateProvider.kind()`·⑤ 수락 로직을 함께
고쳐야 하는 호환성 변경이므로, **sealed 가 그 변경을 컴파일 시점에 드러내는 편이 낫다.**

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = RerouteCandidateApply.RouteOptionApply.class,
                           name = "ROUTE_OPTION"),
        @JsonSubTypes.Type(value = RerouteCandidateApply.RemainingPathApply.class,
                           name = "REMAINING_PATH")
})
public sealed interface RerouteCandidateApply {

    /** 같은 승·하차 구간에서 다른 노선을 고른다. leg 구조는 그대로다. */
    record RouteOptionApply(int waitLegIndex, int routeOptionIndex,
                            String routeId, String directionKey) implements RerouteCandidateApply {}

    /**
     * 남은 구간을 다른 경로로 갈아끼운다.
     *
     * @param fromLegIndex 이 leg 부터 교체한다. 그 앞은 완료·진행 구간이라 보존된다
     * @param path         경로 원본. ⑤가 {@code JourneyCompiler.compile(path)} 로 leg 를 만든다
     */
    record RemainingPathApply(int fromLegIndex, PathResult path) implements RerouteCandidateApply {}
}
```

**`application/journey/dto/RerouteCandidateDisplay.java`**

```java
public record RerouteCandidateDisplay(
        Instant destinationEtaAt,
        int totalDurationSec,
        int etaGainSec,          // 기존 경로 대비 개선폭. 같은 스냅샷으로 잰 값이어야 한다
        int transferCount,
        int walkingSec,
        String source,           // REALTIME | ML | STATIC
        String confidence,
        Instant dataObservedAt,
        List<String> legSummaries) {
}
```

**JSON 표현** — `candidates` 컬럼은 `{"version":1,"candidates":[...]}` 로 감싼다. 배열을 바로 넣으면
나중에 목록 밖 메타(평가 근거 등)를 붙일 자리가 없다. `version` 은 계약이 바뀔 때 ⑤가 옛 행을
가려낼 수 있게 한다.

**`application/journey/dto/RerouteProposalView.java`** — **서버 내부** 읽기 모델(⑤가 소비):
`proposalId`·`journeyId`·`routeRevision`·`transferIndex`·`kind`·`baseCommandVersion`·
`snapshotObservedAt`·`expiresAt`·`candidates`(= `apply` 포함).

**`application/journey/dto/RerouteCandidateSummary.java`** — API 로 나가는 후보:
`candidateId`·`kind`·`display` **만**. `apply` 는 없다(§1.7).
`RerouteProposalView` 에서 파생시키는 정적 팩터리를 이 자리에 둔다 — 컨트롤러에서 필드를
골라 담게 하면 나중에 `apply` 가 실수로 새어 나간다.

### 2.7 신규 — 포트 (③④①이 채운다)

**`application/journey/port/out/RerouteCandidateProvider.java`**

```java
/**
 * 재탐색 후보를 만드는 쪽. 사다리의 칸마다 구현이 하나다.
 *
 * <p>구현체는 외부 API 를 부르므로 <b>트랜잭션 밖에서</b> 호출된다.
 */
public interface RerouteCandidateProvider {

    RerouteProposalKind kind();

    /** 낮을수록 먼저 본다. ROUTE_OPTION=0, REMAINING_PATH=1. 순서를 뒤집으면 요금이 폭발한다. */
    int priority();

    /** 후보가 없으면 빈 목록. 예외를 던지지 않는다 - 재탐색 실패가 여정을 흔들면 안 된다. */
    List<RerouteCandidate> findCandidates(RerouteContext context);
}
```

**`application/journey/dto/RerouteContext.java`**

```java
public record RerouteContext(Long journeyId, Long userId, int routeRevision,
                             long commandVersion, int transferIndex, Instant now) {
}
```

**`application/journey/port/out/RerouteEligibilityPolicy.java`**

```java
/**
 * 지금 재탐색을 평가해도 되는지. ①이 구현한다.
 *
 * @return 거절 사유. 비어 있으면 허용
 */
public interface RerouteEligibilityPolicy {
    Optional<String> denialReason(Long journeyId, int transferIndex, Instant now);
}
```

**기본 구현** — `application/journey/config/JourneyRerouteConfig.java` 에
`@Bean @ConditionalOnMissingBean(RerouteEligibilityPolicy.class)` 로 **항상 `Optional.of("disabled")`**
를 돌려주는 구현을 등록한다. ①이 실 구현을 올리면 자동으로 대체된다.

### 2.8 신규 — 서비스

**`application/journey/service/RerouteProposalService.java`** (트랜잭션 없음)

```java
/** 조회·요청 진입점. 외부 호출은 여기서, 저장은 TxService 에서. */
public class RerouteProposalService {

    /** 소유권 확인 + lazy 만료 전이 후 살아 있는 제안. 없으면 empty. */
    public Optional<RerouteProposalView> findActive(Long userId, Long journeyId);

    /**
     * 제안 생성 요청.
     *
     * <p>순서: 여정 소유권·ACTIVE 확인 → 적격성(①) → priority 순 provider 순회(첫 비어 있지
     * 않은 kind 채택) → 최대 3개 절단 → 저장(TxService).
     *
     * <p>적격하지 않으면 {@code REROUTE_NOT_ELIGIBLE}(409). provider 가 전부 빈손이면 예외가
     * 아니라 empty 다 - "찾아봤지만 나은 것이 없다"는 정상 결과다.
     */
    public Optional<RerouteProposalView> request(Long userId, Long journeyId,
                                                 int transferIndex, Instant now);
}
```

**`application/journey/service/RerouteProposalTxService.java`** (`@Transactional`)

```java
/**
 * 저장만 한다. 한 트랜잭션 안에서 "만료 전이 → supersede → insert" 를 끝낸다.
 *
 * <p>셋을 나누면 그 사이에 다른 요청이 들어와 부분 unique 위반이 난다.
 */
public RerouteProposalView store(RerouteContext context, RerouteProposalKind kind,
                                 List<RerouteCandidate> candidates,
                                 Instant snapshotObservedAt, Instant expiresAt);
```

`candidateId` 는 여기서 부여한다(`UUID.randomUUID()`). provider 가 만든 후보에 ID 가 이미 있으면
덮어쓴다 — ID 의 유일성은 저장소가 책임진다.

**`RerouteProposalStaleResolver`** 를 따로 두지 않는다. 만료 전이는 `RerouteProposalTxService` 의
public 메서드 `resolveStale(journeyId, currentRevision, now)` 로 노출해 ⑤가 재사용한다.

**`application/journey/service/RerouteProposalExpiryService.java`** (배치 sweep, §1.3)

`JourneyLeaseExpiryService` 를 그대로 본뜬다 — 다른 모양으로 만들면 두 만료 경로를 각각 배워야 한다.

```java
/**
 * 아무도 안 지나가는 제안을 접는다.
 *
 * <p>lazy 전이(조회·생성·수락)가 1차이고 이것은 그물이다. 앱이 죽거나 백그라운드로 들어가면
 * 세 진입점이 모두 오지 않아 TTL 이 지난 행이 ACTIVE 로 남는다.
 *
 * <p>{@code sweep()} 은 batch 를 반복하되 한 tick 의 상한을 지킨다. 트랜잭션은 SQL 한 문장만
 * 감싼다 - 길게 잡으면 만료 하나가 커넥션 풀을 마르게 한다.
 */
public int sweep();          // 접은 제안 수
```

저장소에 배치 쿼리 하나를 더한다.

```java
/** 만료된 ACTIVE 를 batch 크기만큼 접는다. 두 인스턴스가 같은 행을 잡지 않는다. */
@Modifying(clearAutomatically = false)
@Query(value = """
        UPDATE reroute_proposal
           SET status = 'EXPIRED', resolved_at = :now,
               resolved_reason = 'ttl-sweep', updated_at = :auditNow
         WHERE id IN (SELECT id FROM reroute_proposal
                       WHERE status = 'ACTIVE' AND deleted_at IS NULL
                         AND expires_at <= :now
                       ORDER BY expires_at
                       LIMIT :batchSize
                       FOR UPDATE SKIP LOCKED)
        """, nativeQuery = true)
int expireDueBatch(@Param("now") Instant now, @Param("batchSize") int batchSize,
                   @Param("auditNow") LocalDateTime auditNow);
```

**`infrastructure/adapter/in/scheduler/journey/RerouteProposalExpiryScheduler.java`**

`JourneyLeaseExpiryScheduler` 골격(`@Scheduled(fixedDelayString = ...)`, **예외를 삼킨다**).
주기는 `journey.reroute.expiry.interval-ms`, 기본 `60000` — 제안 TTL 이 120초라 그 절반이면
자리를 오래 막지 않는다. lease 만료(30초)보다 느슨해도 되는 이유는 여기서 늦는 대가가
"새 제안이 조금 늦게 생긴다"뿐이기 때문이다.

### 2.9 신규 — 설정

**`application/journey/config/JourneyRerouteProperties.java`** (`@ConfigurationProperties("journey.reroute")`)

| 키 | 기본값 | 근거 |
|---|---|---|
| `enabled` | `false` | 기능 플래그. `false` 면 `GET` 은 항상 empty, `POST` 는 `disabled` 로 거절 |
| `proposal-ttl` | `PT2M` | TECHNICAL_DECISIONS §4 (제안 TTL 2분) |
| `max-candidates` | `3` | 같은 곳 (표시 후보 최대 3개) |
| `expiry.interval-ms` | `60000` | sweep 주기. TTL 의 절반 |
| `expiry.batch-size` | `200` | `JourneyExpiryProperties` 와 같은 크기 |
| `expiry.max-sweeps-per-tick` | `5` | 한 tick 이 무한정 돌지 않게 하는 상한 |

`application.yml` 의 journey 블록에 기본값을 함께 적는다. cooldown·최소 개선폭은 ①③ 소유라 여기
넣지 않는다.

### 2.10 신규 — API

**`infrastructure/adapter/in/api/journey/JourneyRerouteProposalController.java`**

```java
@RestController
@RequestMapping("/api/journeys")
@Tag(name = "[회원] 여정")
public class JourneyRerouteProposalController {

    /** 살아 있는 제안. 없으면 body 의 proposal 이 null 이다(204 가 아니다 - 앱 분기를 하나로). */
    @GetMapping("/{journeyId}/reroute-proposals/active")
    public RerouteProposalResponse active(@AuthenticationPrincipal Long userId,
                                          @PathVariable Long journeyId);

    /**
     * 사용자가 "다시 찾아줘"를 누른 경로. 명시 요청도 ①의 window·cooldown 을 통과해야 한다 -
     * 버튼 연타가 외부 검색 연타가 되면 안 된다.
     */
    @PostMapping("/{journeyId}/reroute-proposals")
    public RerouteProposalResponse request(@AuthenticationPrincipal Long userId,
                                           @PathVariable Long journeyId,
                                           @Valid @RequestBody CreateRerouteProposalRequest request);
}
```

- 사용자 ID 는 **JWT 에서만** 온다. 본문에 있어도 쓰지 않는다(`JourneyTelemetryController` 주석과 같은 이유)
- `CreateRerouteProposalRequest(int transferIndex)`
- `RerouteProposalResponse(ProposalBody proposal)` — 없으면 `proposal: null`.
  `ProposalBody` 는 `proposalId`·`routeRevision`·`transferIndex`·`kind`·`expiresAt`·
  `snapshotObservedAt`·`List<RerouteCandidateSummary> candidates`.
  **`apply` 는 어느 필드에도 없다**(§1.7) — `RerouteProposalView` 를 그대로 응답에 쓰지 않는 이유다
- 신규 DTO 위치: `infrastructure/adapter/in/api/journey/dto/`

**`global/error/code/JourneyErrorCode.java`** 에 한 줄 추가:

```java
REROUTE_NOT_ELIGIBLE("REROUTE_NOT_ELIGIBLE", "지금은 재탐색할 수 있는 상태가 아닙니다.", HttpStatus.CONFLICT),
```

`REROUTE_PROPOSAL_NOT_FOUND` 등은 **넣지 않는다** — 쓰는 곳이 ⑤에 생기므로 지금 만들면 죽은 코드다.

### 2.11 신규 — 계측

**`application/journey/observability/JourneyRerouteMetrics.java`** (`JourneyTelemetryMetrics` 모양)

| 이름 | 태그 |
|---|---|
| `journey.reroute.proposal.created` | `kind` |
| `journey.reroute.proposal.resolved` | `reason`(`ttl`·`ttl-sweep`·`route-changed`·`replaced`·`journey-ended`) |
| `journey.reroute.request.rejected` | `reason`(`disabled`·①이 내려 준 사유) |
| `journey.reroute.request.empty` | — (적격했지만 후보 없음) |

`ttl`(lazy)과 `ttl-sweep`(배치)을 가르는 것이 중요하다. **`ttl-sweep` 이 대부분이면 사용자가
제안을 보지 못하고 있다는 뜻**이라, 그때는 발송(⑥)이나 앱 폴링 쪽을 봐야 한다.

### 2.12 수정 — 여정이 끝날 때 제안도 접는다

끝나는 길은 둘이고, 둘 다 이미 대기 알림을 취소하고 있다. 그 옆에 한 줄씩 더한다.

| 파일 | 자리 |
|---|---|
| `application/journey/service/JourneyCommandService.java` | `notificationRepository.cancelPendingByJourneyId(journey.getId())` (145행 부근) 다음 |
| `application/journey/service/JourneyLeaseExpiryService.java` | `notificationRepository.cancelPendingByJourneyIdIn(expired)` (113행 부근) 다음 |

저장소에 대응 쿼리 둘을 더한다(`resolved_reason = 'journey-ended'`). `expireBatch()` 는 이미
"만료 SQL 과 알림 취소가 한 트랜잭션"이라는 원자성을 지키고 있으므로 **그 트랜잭션 안에** 들어간다
— 밖으로 빼면 "여정은 끝났는데 제안은 살아 있는" 창이 생긴다.

`cancelPendingByJourneyId` 처럼 **스위치를 걸지 않는다.** 기능 플래그가 꺼진 동안 쌓인 행이라도
여정이 끝나면 접혀야 한다.

## 3. 작업 순서

- [ ] **1. 브랜치** — `git switch -c feat/journey-reroute-proposal-store` (fresh `main` 기준.
      전환 전 커밋 안 된 변경은 무조건 stash)
- [ ] **2. V42 SQL** 작성 → `.gitignore` `!` 라인 추가 → `journey-reset.sql` 에 DROP 추가
- [ ] **3. 도메인** — `RerouteProposalKind`·`RerouteProposalStatus`·`RerouteProposal`·`Repository`
- [ ] **4. 스키마 IT** — `JourneyRerouteProposalSchemaIT` (§4.1). **여기서 먼저 빨갛게 만든 뒤 통과시킨다**
- [ ] **5. 후보 계약** — `RerouteCandidate`·`RerouteCandidateApply`(`@JsonTypeInfo` 포함)·
      `RerouteCandidateDisplay`·`RerouteCandidateSummary`·`RerouteProposalView`·`RerouteContext`
- [ ] **6. contract test** — `RerouteCandidateContractTest` (§4.2). 계약을 코드로 못 박는 자리다
- [ ] **7. 포트** — `RerouteCandidateProvider`·`RerouteEligibilityPolicy` + 기본 거절 빈
- [ ] **8. 설정** — `JourneyRerouteProperties` + `application.yml` 기본값
- [ ] **9. 서비스** — `RerouteProposalTxService`(저장·만료 전이) → `RerouteProposalService`(조회·요청)
- [ ] **10. 저장 IT** — `RerouteProposalStoreIT` (§4.3)
- [ ] **11. 서비스 단위 테스트** — `RerouteProposalServiceTest` (§4.4)
- [ ] **12. 배치 만료** — `RerouteProposalExpiryService`·`RerouteProposalExpiryScheduler` +
      `expireDueBatch` 쿼리 → `RerouteProposalExpiryIT` (§4.6)
- [ ] **13. 여정 종료 정리** — `JourneyCommandService`·`JourneyLeaseExpiryService` 한 줄씩 (§2.12)
      → 두 경로 회귀 테스트 확인
- [ ] **14. 계측** — `JourneyRerouteMetrics` 를 서비스·sweep 에 연결
- [ ] **15. API** — 컨트롤러·요청/응답 DTO·에러 코드. `apply` 가 응답에 없는지 확인
- [ ] **16. 컨트롤러 테스트** — `JourneyRerouteProposalControllerTest` (§4.5)
- [ ] **17. 전체 검증** — §5
- [ ] **18. 플랜 문서 커밋** — 이 디렉터리(`docs/plan/journey-reroute-proposal-store/`)를 구현 PR 에 포함

의존성: 2→3→4, 5→6, (3,5,7,8)→9→10,11, 9→12, 3→13, 9→15→16.

## 4. 테스트

### 4.1 `src/test/.../domain/journey/JourneyRerouteProposalSchemaIT.java`

`JourneyTelemetrySchemaIT` 골격 그대로. `@Sql` 목록에 `V35`~**`V42`** 전부 나열
(`journey-reset.sql` 이 먼저).

- 같은 `(journey_id, transfer_index)` 로 `ACTIVE` 두 행 → **두 번째가 거절된다**
- 첫 행을 `EXPIRED` 로 바꾼 뒤 → **삽입이 된다** (부분 인덱스가 아니면 여기서 실패한다.
  이 단언이 이 파일의 존재 이유다)
- 다른 `transfer_index` 는 동시에 `ACTIVE` 가능
- `kind`·`status` check 제약이 알 수 없는 값을 거절한다
- `ix_reroute_proposal_expiry` 존재 확인

### 4.2 `src/test/.../application/journey/service/RerouteCandidateContractTest.java`

**③④⑤가 같은 것을 보는지 확인하는 시험.** 순수 단위 테스트(컨테이너 불필요).

- `RemainingPathApply.path` 를 jsonb 로 직렬화 → 역직렬화 → `JourneyCompiler.compile(path)` 가
  성공하고 leg 수가 보존된다. **여기가 깨지면 ⑤가 후보를 적용할 수 없다**
- `RouteOptionApply` 왕복에서 `waitLegIndex`·`routeOptionIndex` 가 보존된다
- **discriminator 이름이 `ROUTE_OPTION`·`REMAINING_PATH` 문자열 그대로 JSON 에 박힌다.**
  클래스 이름이 새어 나오면 실패한다 — 클래스명이 JSON 에 들어가면 리팩터링이 저장된 행을 깬다
- **모르는 `type` 은 역직렬화에서 예외**다. null 로 삼키면 ⑤가 빈 후보를 적용하려 든다
- `apply` 없이 `display` 만 있는 후보는 역직렬화에서 거절된다
- `{"version":1,...}` 봉투가 유지되고, 모르는 `version` 은 읽는 쪽이 가려낼 수 있다
- **`RerouteCandidateSummary` 로 변환하면 `apply` 가 사라진다.** 직렬화 결과 문자열에
  `"path"`·`"subPaths"` 가 없음을 문자열 수준에서 확인한다 — 필드가 하나 늘어도 이 단언이 잡는다
- 기존 fixture 재사용: `JourneyCompilerTest` 가 쓰는 `PathResult` 픽스처를 공유한다

### 4.3 `src/test/.../application/journey/RerouteProposalStoreIT.java`

`AbstractJourneyOutboxPostgresIT` 상속.

- 만료된 `ACTIVE` 가 남아 있어도 새 제안 삽입이 성공한다(lazy 전이 → supersede → insert 한 트랜잭션)
- 개정본이 오른 뒤 이전 개정본 제안이 `SUPERSEDED`·`route-changed` 로 정리된다
- 같은 환승에 새 제안이 오면 앞선 것이 `SUPERSEDED`·`replaced`
- 만료 행은 **지워지지 않는다**(행 수 보존)
- 후보 4개를 넘기면 3개로 잘린다
- 동시 요청 둘 → 한 쪽만 성공하고 다른 쪽은 재시도 가능한 형태로 실패한다

### 4.4 `src/test/.../application/journey/service/RerouteProposalServiceTest.java`

- 적격성 거절이면 **provider 호출 0회**(외부 검색이 안 나가는 것이 핵심)
- provider 가 `priority` 순으로 호출되고, `ROUTE_OPTION` 이 후보를 내면 `REMAINING_PATH` 는 **호출되지 않는다**
- 모든 provider 가 빈손이면 저장 0행 + empty 반환(예외 아님)
- provider 가 예외를 던져도 요청 전체가 500 이 되지 않는다
- 남의 여정이면 소유권에서 거절

### 4.5 `src/test/.../infrastructure/adapter/in/api/journey/JourneyRerouteProposalControllerTest.java`

`JourneyControllerTest` 골격. 인증 주체는 `@AuthenticationPrincipal`.

- `GET` — 제안 없으면 `200` + `proposal: null`
- `POST` — 기본 상태(①·③④ 없음)에서 `409 REROUTE_NOT_ELIGIBLE`
- 본문의 `userId` 를 넣어도 무시된다
- **응답 JSON 에 `apply`·`path` 가 없다**(§1.7). 후보가 있는 상태를 꾸며 응답 문자열로 확인한다

### 4.6 `src/test/.../application/journey/RerouteProposalExpiryIT.java`

`AbstractJourneyOutboxPostgresIT` 상속. **아무도 API 를 안 부르는 상황**을 만든다.

- TTL 이 지난 `ACTIVE` 행이 있고 요청이 하나도 없어도 sweep 이 `EXPIRED` 로 접는다
  (`resolved_reason = 'ttl-sweep'`)
- 접힌 뒤 같은 환승에 새 제안이 삽입된다 — **이 IT 의 존재 이유**다. lazy 만 있을 때 막히던 자리
- 아직 만료 안 된 행은 건드리지 않는다
- batch 상한을 넘는 대상이 있어도 한 tick 이 `batchSize × maxSweepsPerTick` 을 넘지 않는다
- 여정이 `CANCELED`·`COMPLETED`·lease 만료로 끝나면 그 여정의 `ACTIVE` 제안이
  `resolved_reason = 'journey-ended'` 로 접힌다(§2.12 두 경로 각각)

## 5. 검증

```bash
./gradlew compileJava compileTestJava

# 이 PR 의 테스트
./gradlew test --tests '*RerouteProposal*' --tests '*RerouteCandidate*'

# 여정 회귀 (Docker 필요 - IT 가 PostgreSQL 컨테이너를 띄운다)
./gradlew test --tests 'watoo.grd.nextroute.application.journey.*' \
               --tests 'watoo.grd.nextroute.domain.journey.*'

# 전체
./gradlew test
```

통과 기준:

- V42 가 실 PostgreSQL 에서 적용되고 부분 unique 가 §4.1 두 단언을 만족한다
- contract test 가 `PathResult` 왕복 → `JourneyCompiler.compile` 성공과 **discriminator 이름 고정**을
  함께 보인다
- API 응답 어디에도 `apply`·`path` 가 없다
- 요청이 하나도 없는 상태에서 sweep 이 만료 행을 접고, 그 뒤 새 제안 삽입이 성공한다
- 여정 종료 두 경로(명령·lease 만료)에서 제안이 함께 접힌다
- 기본 상태에서 `POST` 가 `409`, `GET` 이 `proposal: null` 이다(기능이 조용히 켜지지 않는다)
- 기존 여정·알림 테스트 회귀 없음

## 6. 함정

- **`.gitignore` whitelist** — V42 `!` 라인을 빠뜨리면 로컬만 통과하고 배포에 SQL 이 안 올라간다.
  이 레포에서 반복된 사고다
- **`git add -A` 금지** — migration 이 whitelist 방식이라 의도 안 한 파일이 딸려 온다. 경로를 지정해 add
- **감사 시각과 도메인 시각** — native 쿼리에서 `auditNow`(LocalDateTime)와 `now`(Instant)를
  바꿔 쓰면 컴파일은 되고 값만 조용히 어긋난다
- **`@Modifying(clearAutomatically = false)`** — 부르는 쪽이 같은 트랜잭션에서 엔티티를 고치고
  있으면 컨텍스트를 비울 때 그 변경이 사라진다(`JourneyNotificationRepository` 의 전례)
- **부분 unique 를 전체 unique 로 쓰지 말 것** — Phase 5 ②가 `delivery_key` 에서 겪은 그대로다
- **`RerouteProposalView` 를 컨트롤러에서 그대로 반환하지 말 것** — `apply` 가 통째로 새어 나가고,
  후보 3개면 응답이 수십 KB 가 된다. 변환은 `RerouteCandidateSummary` 팩터리 한 자리에서만
- **discriminator 이름을 클래스명에 맡기지 말 것** — 클래스를 옮기거나 이름을 바꾸는 순간 저장된
  행을 못 읽는다. `@JsonSubTypes` 의 `name` 은 V42 `kind` check 값과 같은 문자열로 손으로 고정
- **sweep 이 lazy 를 대체하지 않는다** — 둘 다 필요하다. lazy 는 사용자가 기다리는 그 순간을,
  sweep 은 아무도 안 오는 시간을 맡는다. 하나만 두면 각각 "느린 화면"과 "막힌 자리"가 된다
- **VPS 배포 시 V42 수동 배치** — migration 은 untracked convention 이라 배포 전 파일을 서버에 올린다
- **기능 플래그 기본 off** — 이 PR 은 운영에서 아무 일도 일으키지 않는다. 켜는 것은 ①③④ 이후다

## 7. 뒤 PR 이 이 PR 에서 가져가는 것

| PR | 가져가는 것 |
|---|---|
| ① trigger | `RerouteEligibilityPolicy` 구현체를 올려 기본 거절 빈을 대체 |
| ③ route-options | `RerouteCandidateProvider(kind=ROUTE_OPTION, priority=0)` 구현 |
| ④ remaining-search | `RerouteCandidateProvider(kind=REMAINING_PATH, priority=1)` 구현 |
| ⑤ accept | `RerouteProposalView.candidates[].apply` 로 새 개정본 생성. 재검증 전에 `resolveStale` 호출 |
| ⑥ notification | `proposalId` 를 알림 payload·멱등 키에 싣는다(키 확장 여부는 ⑥에서 결정) |
