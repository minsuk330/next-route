# reroute-periodic-board — PLAN v2 (L 티어, 에이전트 실행용)

이슈: minsuk330/next-route#264. 확정 규칙의 원문은 이슈 댓글이다.
- 정리본 v1: `issuecomment-6014705228`
- v2: `issuecomment-6019659021`
- 논의 결과 7: `issuecomment-6019735006`
- 범위 분리: #264 마지막 댓글. #267 이 경기·TAGO 노선 정류장 표를 소유한다.

규칙과 이 문서가 충돌하면 이슈가 이긴다.

기준 커밋은 main `493af781`.
- 선행: **#267**(경기·TAGO 노선 정류장 표 `regional_bus_route_stop` + 월 1회 적재. 마이그레이션 먼저)
- 후행: #265(단독 추적 `kind=TRACK`)

v1 → v2 변경: 독립 리뷰(BLOCKER 4·MAJOR 14·MINOR 11) 반영 + 사용자 결정 3개(§1 끝).

## 0. 목표

서버가 진행 중인 모든 여정에서 정류장·역을 지날 때마다(신호가 없으면 60초마다) 조용히 후보 **보드**를 갱신한다. 앱은 그 보드를 지도 말풍선으로 그린다. 확실한 이득이 있을 때만 push 를 보낸다.

## 1. 확정 규칙 (구현 기준값)

| 영역 | 값 |
|---|---|
| 대상 | 진행 중인 모든 여정. `kind=TRACK` 은 #265 가 거름 조건을 추가한다 |
| 찾는 시점 | 정류장·역 통과 시 1회, 신호가 없으면 60초마다. 직전 실행 후 30초 안이면 건너뛴다. 단, 환승 위험 하락 신호는 30초 최소 간격을 우회한다(§PR-5) |
| 첫 갱신 | 여정 시작 + 60초. 시작 후 3분 동안의 push 차단은 push 판단에서 거른다 |
| 기점: 걷기(`WALK`·`TRANSFER_WALK`) | 현재 위치(`CURRENT_POSITION`) = 앱이 받은 geometry 의 (`geometrySegmentIndex`, `segmentProgress`) 지점을 거리 기준으로 보간. 진행 보고가 180초보다 오래됐으면 건너뛴다. 진행 보고가 없고 구간 활성 후 180초 이내면 구간 시작점을 쓴다 |
| 기점: 기다리기(`WAIT_*`) | 그 정류장·역(`WAITING_STOP`), 출발 시각 = 지금 |
| 기점: 버스 탑승 | 지금 탑승 구간의 중간 정류장(`RIDE_STOP`) + 다음 환승이 있으면 계획 하차점(`PLANNED_ALIGHT`) |
| 기점: 지하철 탑승 | 계획 하차점만. 다음 환승이 없으면 기점 없음 |
| 환승 없는 여정 | 계획 하차점 기점 없음. 기준선은 "현재 탑승 도착 + 마지막 도보" |
| 표시 문턱 | 체인(후보·기준선) 전부 실시간이면 120초, ML 이 하나라도 섞이면 180초. 늘어난 대기 1회당 +60초 |
| 같은 자리 처리 | **(결정 1-가)** 경로 지문이 같으면 숫자를 갱신하고 candidateId 를 유지한다. 지문이 다르면 기존보다 60초 이상 빠를 때만 교체하고, 아니면 기존 후보를 그대로 둔다(숫자도 그대로) |
| 숨김 | 도착 60초 미만, 지남, 문턱 미달(즉시) |
| 재표시 | 문턱 +30초. 숨김 기록은 그 자리를 지나거나 구간이 전이되면 지운다 |
| 못 구한 자리 | 이번 회차가 장애·할당량·예산 때문에 평가하지 못한 자리는 **유지**한다(alightAt − 60초까지) |
| push 조건 | 위험+대안(AT_RISK·MISSED 하락 + 300초 이상) / 새 기회(300초 이상, 직전 push 와 자리·노선이 다르거나 이득이 +120초 이상 커짐) |
| push 차단 | 기준 시각까지 120초 미만. 시작·수락 후 3분 안의 "새 기회". cooldown 5분. 무시 2회(opt-in 이면 예외) |
| push 기준 시각 | `RIDE_STOP`/`PLANNED_ALIGHT` = 하차 도착, `CURRENT_POSITION`/`WAITING_STOP` = 후보 첫 승차 시각 |
| 합치기 | 대안이 있으면 `TRANSFER_RISK` 를 취소하고 재탐색 push 하나만 보낸다 |
| 수락 후 | push 를 영구 차단하지 않는다. 수락 후 3분 동안만 "새 기회"를 막는다 |
| 거절 | **(결정 2-가)** push 만 멈춘다. 보드 갱신과 말풍선은 계속된다. "계속 받기" opt-in 이면 push 도 유지 |
| 무시 | push 단위로 센다. push 후 다음 push 를 판단할 때까지 그 push 에 대한 수락·조회·dismiss 가 없으면 +1. 보드 TTL 만료는 세지 않는다 |
| 지하철 역 통과(미고정) | **(결정 3-가)** 앱이 지하철 진행 보고에도 남은 역 수를 보낸다(HANDOFF). 고정 열차가 있으면 그 열차 위치 변화를 쓴다 |
| 여유 시간 | 기점·말풍선 60초 / push 120초 / 수락 재확인 30초 |
| 보드 저장 | 개정본마다 한 행을 제자리 갱신. `board_version`, `refreshed_at`, `expires_at` = 마지막 갱신 + 3분. 후보 0개여도 행 유지 |
| 앱 갱신 | 화면이 켜져 있으면 15초마다 `/active`(ETag, 304). 즉시 재조회: 포그라운드·push 탭·404/409·구간 전이 |
| 키 보호 | 재탐색 전용 지역별 할당량 `reroute.quota.arrival.{seoul,gyeonggi,tago}`(한도의 50%). 캐시 미스일 때만 차감한다. 소진되면 그 지역 `RIDE_STOP` 만 끈다 |

## 2. PR 분할 · 순서

모든 PR 은 fresh main 에서 독립 브랜치로 진행한다(스택 금지).

**플래그 `journey.reroute.board.enabled`(기본 false)가 꺼져 있으면 기존 동작이 바이트 단위로 같아야 한다.** 이것이 모든 PR 의 회귀 조건이다. 보드 전용 설정은 `journey.reroute.board.*` 로 두고, 기존 키(`remaining-path.minimum-eta-gain-seconds`, `next-stop.lead-time-sec` 등)는 값을 바꾸지 않는다.

| 순서 | PR | 범위 | 마이그레이션 |
|---|---|---|---|
| 0 | #267 | 경기·TAGO 노선 정류장 표 + 적재(별도 이슈) | #267 이 먼저 |
| 1 | PR-1 | 보드 저장 모델 + 병합 규칙 + 판별 읽기 | V57 (#268 의 V56 다음) |
| 2 | PR-2 | 갱신 스케줄러 + 시점 신호 | V57 에 포함 |
| 3 | PR-6 | API 계약(`/active` 보드, ETag) + HANDOFF | 없음 |
| 4 | PR-3 | 기점 확장(걷기·기다리기·환승 없음) + 문턱 + 근거 집계 + 도보 자르기 | 없음 |
| 5 | PR-5 | push 재정의 + 합치기 + 무시·거절·수락 의미 | 없음 |
| 6 | PR-4 | 경기·TAGO `RIDE_STOP`(#267 표 읽기) + 지역별 할당량 | 없음 |

- PR-5 까지 머지되면 **서울만으로 운영 on 여부를 판단**할 수 있다.
- 플래그를 켜기 전에 반드시 PR-5 가 있어야 한다. 기존 `RerouteAlertPublisher` 가 `supersedeActive` 로 보드를 밀어내기 때문이다.

---

## PR-1 보드 저장 모델 + 병합 규칙

### 마이그레이션 `V57__reroute_board.sql` (#268 의 V56 다음)

```sql
ALTER TABLE reroute_proposal
  ADD COLUMN board_version   bigint      not null default 0,
  ADD COLUMN refreshed_at    timestamptz,
  ADD COLUMN push_seq        integer     not null default 0,  -- push 멱등 키 순번
  ADD COLUMN last_push_slot  varchar(64),
  ADD COLUMN last_push_route varchar(64),
  ADD COLUMN last_push_gain  integer,
  ADD COLUMN last_pushed_at  timestamptz,
  ADD COLUMN last_push_answered boolean;   -- 무시 집계용
ALTER TABLE journey_session
  ADD COLUMN reroute_refresh_at      timestamptz,
  ADD COLUMN reroute_last_refresh_at timestamptz,
  ADD COLUMN reroute_refresh_dirty   boolean not null default false, -- 선점 중 들어온 신호
  ADD COLUMN reroute_accepted_at     timestamptz;                    -- 수락 후 3분 차단
CREATE INDEX ix_journey_reroute_refresh_due ON journey_session (reroute_refresh_at)
  WHERE status = 'ACTIVE' AND deleted_at IS NULL AND reroute_refresh_at IS NOT NULL;
```

- 마이그레이션은 git 에 추적된다(#250). 노트 `### 배포` 에 아래를 적는다.
  - "V57 VPS 수동배치 필요(jar 포함 확인)"
  - "#267 → #264 순서"

### 봉투 판별 읽기 (B4)

- 파일: `RerouteCandidateEnvelope`
  - v1 = `{version:1, candidates}`. **기존 경로의 쓰기는 v1 그대로 둔다.**
  - v2 = `{version:2, slots}` 를 추가한다.
- `RerouteCandidateReader.read`(`:49`)와 `RerouteProposalTxService.toView`(`:326`)는 판 값으로 분기한다.
  - v1 → candidates
  - v2 → **VISIBLE 슬롯의 candidate**
  - 모르는 판 → 지금처럼 없음
- 플래그를 켜는 순간 남아 있는 v1 ACTIVE 행은 보드 서비스가 supersede 한 뒤 v2 를 INSERT 한다.

```java
record RerouteBoardSlot(String slotKey, RerouteOriginKind originKind, SlotState state,
    RerouteCandidate candidate, String routeFingerprint, Integer lastGainSec,
    Instant firstShownAt, Instant hiddenAt, Instant evaluatedAt, Instant alightAt)
```

- `slotKey` 는 **stopId 중심**으로 만든다. `PLANNED_ALIGHT(S)` → 하차 후 `WAITING_STOP(S)` 로 바뀌어도 키가 이어진다(m4).
  - 형식: `S:{provider}:{stopId}[:{seq}]`
  - 걷기는 `HERE` 다. `HERE` 의 숨김 기록은 구간 전이 때 지운다.
- `routeFingerprint` = 첫 탑승 노선 id + 하차점 + 환승 노선 목록의 해시. 결정 1-가 판정에 쓴다.

### 병합기 `RerouteBoardMerger` (순수 함수)

```java
BoardMergeResult merge(List<RerouteBoardSlot> current, MergeInput input, Instant now, Thresholds t)
// MergeInput: evaluated(자리→ScoredCandidate|NONE), unevaluated(자리 집합), passed(자리 집합)
```

1. **passed**, 또는 `alightAt − now < 60s` → 슬롯과 숨김 기록을 제거한다.
2. **unevaluated** → 슬롯을 그대로 둔다(M7).
3. **evaluated** + VISIBLE
   - gain < 문턱 → HIDDEN
   - 지문이 같음 → 숫자(display)만 갱신하고 candidateId·apply 를 유지한다. 단, apply 의 경로는 같은 지문이라도 새 값으로 바꾼다. 새 경로가 "본 것"과 같은 노선·하차점이기 때문이다.
   - 지문이 다름 → 기존보다 60초 이상 빠르면 교체(새 candidateId). 아니면 슬롯을 그대로 둔다(결정 1-가).
4. **evaluated** + HIDDEN → gain ≥ 문턱 + 30이면 VISIBLE(새 candidateId).
5. 새 자리 → gain ≥ 문턱이면 VISIBLE.
6. 문턱 = (체인 근거 `REALTIME_ONLY` ? 120 : 180) + 60 × max(0, 후보 남은 대기 수 − 기준선 남은 대기 수).
   - 근거 값은 PR-3 이 채운다. PR-1 에서는 근거가 없으면 180(보수)으로 둔다.

### 보드 서비스 `RerouteBoardService` (B2·m1·m2)

한 트랜잭션에서 순서대로 처리한다. 잠금 순서는 **세션 → 제안**으로, 수락과 같다.

1. 세션을 `FOR SHARE` 로 읽는다. `RerouteProposalTxService.stillCurrent`(`:223-238`)와 같은 확인을 한다: ACTIVE, revision 일치, 계산에 쓴 commandVersion 일치, 현재 leg 일치. 불일치하면 이번 회차를 버린다.
2. ACTIVE 보드가 없으면 INSERT 한다.
   - `ON CONFLICT (journey_id) WHERE status='ACTIVE' AND deleted_at IS NULL DO NOTHING`
   - 0행이면 다시 읽어 3으로 간다.
   - 남아 있는 v1 행은 먼저 supersede 한다.
3. 있으면 조건부 UPDATE 를 한다.
   ```sql
   SET candidates=?, board_version=board_version+1, refreshed_at=?, expires_at=now+3m,
       base_command_version=:current
   WHERE id=? AND board_version=? AND status='ACTIVE'
   ```
   - 0행이면 상태를 다시 읽는다. 만료·supersede 됐으면 즉시 INSERT 로 간다(m2). 수락됐으면 버린다.

### 테스트(계약당 1개 이상)

- `RerouteBoardMergerTest`(표 기반)
  - 지문 같음 → id 유지
  - 지문 다름 59/60초
  - 숨김(즉시)
  - 재표시 +29/+30
  - unevaluated 유지
  - 60초 미만 제거
  - 문턱 120/180/+60
  - slotKey 연속성(`PLANNED_ALIGHT` → `WAITING_STOP`)
- `RerouteCandidateContractTest`
  - v1·v2 판별 읽기
  - 플래그 off 에서 v1 저장 → 조회 → 경로 조회 → 수락 회귀
- `RerouteBoardServiceTest`: INSERT 충돌, 0행 → 재INSERT, revision·commandVersion 불일치 시 버림
- IT
  - **"구간 전이 후 갱신된 보드의 후보를 수락하면 200"**(B2)
  - 수락과 갱신이 경합하면 수락이 이긴다

---

## PR-2 갱신 스케줄러 + 시점 신호 (M8·M10·m3)

### 선점과 완료

- 선점: `JourneySessionRepository.claimRerouteRefreshBatch(now, leaseUntil, batchSize)` 를 추가한다. `claimDueBatch`(`:209`)와 같은 형태이고, 칼럼은 `reroute_refresh_at` 이다.
- 완료는 **CAS** 로 한다. `finishEvaluation`(`:259-262`)의 `reroute_*` 판이다.
  ```sql
  UPDATE journey_session
     SET reroute_refresh_at = CASE WHEN reroute_refresh_dirty THEN GREATEST(:now, :now + 30s) ELSE :now + 60s END,
         reroute_last_refresh_at = :now, reroute_refresh_dirty = false
   WHERE id = ? AND reroute_refresh_at = :claimedLease
  ```
  - 이 쓰기는 보드 저장과 **다른 트랜잭션**에서 한다. 제안 → 세션 순서로 잠그는 일을 피한다.
- 실행
  - 전용 executor 와 전용 Semaphore 를 둔다: `journey.reroute.board.refresh.permits` 기본 3.
  - 평가기 limiter(`journeyEvaluatorProviderLimiter`)와는 **공유하지 않는다**. 위험 판정이 굶지 않게 하기 위해서다.
  - batch 는 executor 크기 × 2 이하, lease 는 60초, 갱신 deadline 은 12초로 강제한다.
  - 재탐색 전용 OTP 동시성 상한(`journey.reroute.board.otp-permits` 기본 4)과 회차당 기점 상한(기본 7)을 둔다.

### 신호 `RerouteRefreshSignal.requestNow(journeyId, bypassMinGap)`

- 선점 중이 아니면 아래처럼 쓴다. `bypassMinGap` 이면 `GREATEST` 를 빼고 `:now` 로 둔다.
  ```sql
  reroute_refresh_at = LEAST(reroute_refresh_at, GREATEST(:now, reroute_last_refresh_at + 30s))
  ```
- 선점 중(lease 가 미래)이면 `reroute_refresh_dirty = true` 만 남긴다. lease 를 앞당기면 같은 여정을 두 워커가 돈다.
- 플래그 off → no-op.

| 신호 | 위치 | 조건 |
|---|---|---|
| 버스 정류장 통과 | `JourneyProgressService.merge` APPLIED(`:125`) | `remainingStops` 가 직전 값보다 작아짐(UPDATE 전 `leg.getRemainingStops()`) |
| 지하철 역 통과(미고정) | 같은 위치 | 결정 3-가: 앱이 지하철 남은 역 수를 보낸다. `ApplyJourneyProgressRequest`(`:24`)의 "버스가 아니면 비운다" 계약을 바꾸고, 계약 테스트를 단다 |
| 지하철 역 통과(고정) | `SubwayRingUpdateService`(조건부 스케줄러가 아니라 서비스 쪽) | 고정 열차의 현재 역이 바뀜 |
| 구간 전이 | `JourneyCommandService`(`:135-142`, `scheduleEvaluationAt` 옆) | 항상 |
| 여정 시작 | `JourneyStartTxService` | `reroute_refresh_at = now + 60s` |
| 수락 | `RerouteAcceptTxService.applyRevision` | `now`, `reroute_accepted_at = now` |
| 환승 위험 하락 | `RerouteTriggerEvaluator`(기존 방아쇠 자리) | `bypassMinGap = true` |
| 사용자 요청(개발 빌드) | `RerouteProposalService.request` | 보드 모드에서는 `requestNow` 후 동기 1회 갱신. api 프로필에서도 돈다. `isTestUser` 는 시간 창·cooldown 우회를 유지한다 |

- 종료·취소·만료 시 `reroute_refresh_at = NULL` 로 둔다.
- **플래그를 켤 때 백필(m3)**: 운영 절차에 `UPDATE journey_session SET reroute_refresh_at = now() WHERE status='ACTIVE'` 를 넣는다. 첫 진행 보고 때 NULL 이면 심는 코드 방어도 둔다.

### 테스트

- 선점 IT: SKIP LOCKED, lease
- 완료 CAS: dirty 반영, lease 불일치 시 무시
- 신호: 30초 간격, 우회, 선점 중 dirty
- `JourneyProgressServiceTest`: 감소일 때만 신호, 지하철 남은 역 수 계약
- 플래그 off 회귀

---

## PR-6 API 계약 + HANDOFF (M1·M2·m8)

### 보드 모드의 `/active` 는 DB 에 쓰지 않는다

- `validate`(`RerouteProposalService.java:107-126`)의 `expireValidatedOut` 과 `findActive` 앞의 `resolveStale`(`:73-81`)을 보드 모드에서 건너뛴다.
- 걸러 내기(60초 미만)는 응답에서만 한다.
- 만료·supersede 처리는 갱신기와 만료 스케줄러(`RerouteProposalExpiryService.expireBatch`)가 맡는다.

### 응답

- 추가 필드: `boardVersion`, `refreshedAt`
- 후보마다 `originType` 을 싣는다. 값은 `RerouteOriginKind` 에서 파생한다: `TRANSFER_WAIT` → `PLANNED_ALIGHT`, `RIDE_INTERIOR` → `RIDE_STOP`, 신규 `CURRENT_POSITION`·`WAITING_STOP`.
- 말풍선 좌표: `origin.x/y`. `RIDE_STOP`/`PLANNED_ALIGHT` 는 `alight.x/y` 와 같다.
- 내보내는 범위: VISIBLE 이면서 `alightAt − now ≥ 60s` 인 슬롯만.
- 빈 보드: `candidates: []` + `refreshedAt`. 보드가 없으면 `proposal: null`.

### ETag

- **실제로 내보낸 (candidateId, display 해시) 집합의 해시**로 만든다. board_version 만 쓰면 시간 거름 결과를 반영하지 못한다.
- `If-None-Match` 가 같으면 304.

### HANDOFF `docs/plan/reroute-periodic-board/HANDOFF.md`

- 15초 polling + ETag
- 즉시 재조회 트리거
- 빈 상태 문구 3종(이슈 7-2)
- originType 별 그리기
- push 진입 시 최신 보드
- **지하철 진행 보고에 남은 역 수 전송(결정 3-가)**

### 테스트

- 304
- 버전이 같아도 슬롯이 지나가면 200
- 빈 보드 모양
- HIDDEN 미노출
- 조회가 DB 를 쓰지 않음(보드 모드)

---

## PR-3 기점 확장 + 문턱 + 근거 집계 + 도보 자르기 (M5·M12·Q4·Q5·Q6)

### 기점 주소

- `RerouteOriginKind` 에 `CURRENT_POSITION`·`WAITING_STOP` 을 추가한다. 평행 enum 은 만들지 않는다.
- `RerouteOrigin` 에 nullable 칸을 추가한다: `Double x`, `Double y`, `String provider`(null = SEOUL).
- `stopId` 의 `requireNonNull` 을 푼다. 새 도우미 메서드에는 `@JsonIgnore` 를 붙인다.
- 롤백한 옛 jar 는 새 kind 를 만나면 그 후보를 404 로 처리한다. 수용하고, 노트에 적는다.

### 공급자 진입점

- `RemainingPathCandidateProvider` 에 보드 전용 진입점 `findBoardCandidates(BoardContext)` 를 새로 만든다.
  - 기존 `findCandidates`(transferIndex·TransferConnection 필수, `:119-136`)는 바꾸지 않는다.
  - 기존 `origins`(`:174`)·`more-transfers` 필터(`:439-444`)도 기존 경로에서는 그대로 둔다.
- 보드 진입점의 동작
  - 기점: §1 표
  - `more-transfers` 는 버리지 않고 문턱 보정으로 넘긴다. 기준은 "남은 대기 수": 후보 대기 수 vs 기준선 `waitsAfterFirst` + 현재 대기.
  - `UpcomingStopWindowResolver.resolveRideInterior` 에 "현재 탑승 구간"을 받는 오버로드를 추가한다. 기존 `waitLegIndex - 1` 호출은 유지한다.
  - 여유 시간은 보드 전용 키 `journey.reroute.board.lead-time-sec=60` 을 쓴다.
  - 후보 생성 하한은 `journey.reroute.board.candidate-min-gain-seconds=120` 이다. 최종 거름은 병합기가 한다.
  - 기준선: `measureBaseline`(`:353`)을 일반화한다. 환승 없음 = 현재 탑승 예상 도착 + 마지막 도보.

### 걷기 위치 (Q4)

- `progressLegFraction` 은 쓰지 않는다. 서버가 의미를 정의하지 않은 값이다.
- `JourneyNavigationGeometryService` 출력의 해당 leg segment.points 위에서 (`progressGeometrySegmentIndex`, `progressFraction`) 지점을 거리 기준으로 보간한다. 계약은 `ClientGeometryProgressArrivalPredictor:66-68` 과 같다.
- 대체 수단: `remainingDistanceMeters` 로 끝에서부터 역산한다. 진행 보고가 없고 활성 후 180초 이내면 구간 시작점을 쓴다.

### 근거 집계 (Q6)

- `BoardingChainEtaCalculator` 가 고리마다 `estimate.source()` 를 보고 `anyModel` 을 집계한다.
- 후보와 기준선 둘 다 계산해 display 에 `evidence: REALTIME_ONLY | MIXED` 로 저장한다.
- 걷기 전용 체인은 `REALTIME_ONLY`(대기 없음)로 둔다.

### 도보 자르기

- 신규 `WalkTruncationResolver` 를 만들고, `JourneyRevisionComposer` 에 분기를 추가한다.
- 진행이 낡았는지만 재확인한다.

### 테스트

- 기점 표 각 행
- 보간 지점 0·중간·끝, 낡은 진행, 시작점 대체
- 환승 없음 기준선
- 대기 증가 보정
- evidence 집계(실시간·ML·걷기)
- 도보 자르기 수락 IT
- **플래그 off 회귀**: 기존 `findCandidates` 결과가 동일

---

## PR-5 push 재정의 (B3·M3·M4·M14·m5·m6·m7)

### 발행 지점

- 보드 갱신 직후 `RerouteBoardPushPolicy` 를 부른다.
- 보드 모드에서 기존 `RerouteAlertPublisher` 는 보드를 만들지 않고, 위험 하락 신호만 넘긴다(`requestNow(bypass)` + 위험 표식).
- 조건: §1 표
  - 기준 시각은 m5 대로 originKind 별로 다르다.
  - "시작·수락 후 3분"은 `startedAt` 과 `reroute_accepted_at` 으로 판단한다.

### 멱등 키 (B3)

- `JourneyNotificationEnqueuer.enqueueRerouteSuggested`(`:122-143`)의 scopeId 를 `(proposalId, push_seq)` 로 바꾼다.
- push 마다 보드 행의 `push_seq` 를 +1 한다. 이 갱신은 보드와 같은 조건부 UPDATE 로 한다.
- 테스트: "같은 보드에서 push 2회 → outbox 2행".

### 수락·거절·무시

- 수락(M3): 보드 모드에서는 `terminateReroute()`(`RerouteAcceptTxService.java:326-328`) 대신 `reroute_accepted_at` 만 기록한다. 이후 push 는 3분 뒤 다시 가능하다.
- 거절(결정 2-가): push 만 멈춘다(`terminateReroute`). 보드 갱신은 계속된다. 보드 행은 닫지 않는다. opt-in 이면 push 도 유지한다.
- 무시(M4)
  - 다음 push 를 판단할 때, 직전 push(`last_push_answered=false`) 이후 그 보드에 대한 수락·경로 조회·dismiss 가 없으면 `reroute_ignored_count +1` 을 한다.
  - `/active` 조회는 응답으로 치지 않는다. 15초 폴링이 자동으로 부르기 때문이다. push 탭은 앱이 `?from=push` 로 알려 준다(HANDOFF).
  - 보드 TTL 만료는 무시로 세지 않는다. 기존 `countUnansweredDue`·`bumpUnanswered` 는 보드 행에서 제외한다.
  - `dismiss` 는 보드를 닫지 않고 그 push 를 "응답함 + 무시 1회"로 기록한다.

### `TRANSFER_RISK` 합치기 (M14·Q1)

1. `JourneyNotificationEnqueuer.enqueue` 에 `scheduledAt` 파라미터를 추가한다. 지금은 `scheduled_at = :now` 고정이다.
2. 보드 모드에서 위험 알림은 `scheduledAt = now + 20s` 로 넣는다. 만료 계산 기준도 `scheduledAt` 으로 옮긴다.
3. 위험 하락 → `requestNow(bypassMinGap=true)` → 갱신(deadline 12초) → 재탐색 push 를 발행하면, 같은 여정의 PENDING `TRANSFER_RISK` 행을 `CANCELED` + 사유 `superseded-by-reroute` 로 바꾼다.
   - `supersedeAllPendingByScope` 를 사유 파라미터로 확장해서 쓴다.
   - V40 check 는 바꾸지 않는다.
4. 대안이 없으면 20초 뒤 원래대로 나간다.
   - 이미 PROCESSING 인 행은 취소하지 않는다(둘 다 나감, 수용).
   - 지연 창 안에 구간이 전이되면 기존 `command-changed` 재확인에 잘린다(수용).
5. 지름길: 직전 보드가 30초 안에 갱신됐고 300초 이상 이득 후보가 VISIBLE 이면, 지연 없이 바로 재탐색 push 를 결정한다.

### 문구 (m6)

- payload(`writeReroutePayload`)에 원점 정류장명·첫 노선명·etaGainSec 를 싣는다.
- `FcmMessageFactory`(`:156,169`)가 그 값으로 문장을 만든다.
  - 하차형: "{정류장}에서 내려 {노선} 타면 {n}분 빨라요"
  - 현재 위치형: "{노선}으로 가면 {n}분 빨라요"

### 테스트

- push 조건 표 기반 경계값(120s/300s/+120s/3분/5분/2회)
- 기준 시각 originKind 별
- 멱등 키 2회
- 수락 후 3분 뒤 push 가능
- 거절 후 보드 계속, push 없음
- 무시 집계(폴링 제외, TTL 제외)
- 합치기: 대안 있음 → 취소, 없음 → 20초 뒤 발송, 지름길

---

## PR-4 경기·TAGO `RIDE_STOP` (#267 표 읽기) (B1·M11·M13)

- **표·적재·포트는 만들지 않는다.** #267 의 `regional_bus_route_stop`(provider, route_id, city_code, stop_id, seq, turn, is_waypoint)을 읽기만 한다.

### 구간 id

- 비서울 탑승 구간은 `routeId`·`startStopId` 가 null 이다. 서울 resolver 결과라서다(`JourneyStartTxService.java:177-181`).
- 원천 id 는 다음에서 가져온다.
  - 정류장: 구간의 `sourceStartStopId`/`sourceEndStopId`
  - 노선: 직전 대기 구간이 고른 `JourneyLegRouteOption.sourceRouteId`. 탑승 구간 자신에게는 노선 후보가 없다.

### 순번 공간

- 앱의 `remainingStops` 는 GTFS 정류장 기준이다.
- 계산 순서
  1. GTFS 정류장 id 로 표의 순번에 조인한다(`is_waypoint` 는 제외).
  2. 하차 정류장의 API 순번에서 "가상점을 뺀 정류장 수"로 거슬러 현재 순번을 구한다.
- `BusRideLocator.locate`(`:62`)를 provider 별로 일반화한다. 회차 중복은 `uniqueSeq` 규칙으로 가른다.

### 내 차

- 경기: 도착 `locationNo` 를 역산한다(가상점 포함 차이).
- TAGO: 순번 규칙만 쓴다. 차량번호가 없으므로 차량 이어 잡기를 하지 않는다.

### 수락 자르기

- `RideTruncationResolver` 의 `alightStationIndex`(`= alightSeq − boardSeq`)를 **스냅샷 subPath 정류장 인덱스 공간**으로 변환한 뒤 자른다. 가상점이 섞이면 엉뚱한 정류장에서 잘리기 때문이다.

### 그 밖

- `currentRouteIds`(`RemainingPathCandidateProvider.java:297-308`)도 원천 노선 id 를 포함하게 한다. "같은 버스 다시 타기" 후보를 거르기 위해서다.

### 할당량 (M13)

- 어댑터 안의 `quota.tryAcquireSearch`(`SearchTimeBusAdapter.java:153`) 앞에 호출 문맥(SEARCH / REROUTE)을 넘긴다.
- 캐시 미스일 때만, 해당 버킷에서만 차감한다.
- 경기·TAGO 도착에도 15초 캐시를 붙인다.

### 지표

- `reroute.origin{type,provider,reason}`
- reason 값: `route-unknown`, `stop-unjoined`, `no-progress`, `stale-progress`, `ambiguous-stop`, `quota-exhausted`

### 테스트

- 순번 역산(가상점 포함)
- 회차 중복
- 조인 누락 제외
- 절단 인덱스 변환
- 할당량 이중 차감 없음
- 수락 재확인 provider 경로

---

## 3. 횡단

- **플래그·설정**: `journey.reroute.board.{enabled, lead-time-sec, candidate-min-gain-seconds, refresh.{tick-ms,batch,lease-seconds,deadline-ms,interval-seconds,min-gap-seconds,permits}, otp-permits, max-origins, risk-merge-delay-seconds}`
- **지표**: `reroute.board.refresh{result}`, `reroute.board.slot{transition}`, `reroute.origin{type,provider,reason}`, `reroute.push{kind,result}`
  - 태그에 stopId·routeId·journeyId 를 넣는 것은 금지한다.
- **구현 노트**: PR 마다 `implementation_note.md` 에 `### 확정/함정/좌표/검증/배포` 를 append 한다.
- **운영 on 체크리스트**(PR-5 이후, 사용자 결정)
  1. 마이그레이션 배치
  2. `reroute_refresh_at` 백필
  3. 플래그 on
  4. 시뮬레이션 1건으로 정류장 통과마다 `board_version` 증가를 확인
  5. OTP·키 지표 관찰

## 4. 남은 위험 (수용)

- 걷기 위치 보간: 경로를 벗어나면 틀린다.
- 지연 창 안에 이미 PROCESSING 인 위험 알림은 취소하지 못한다. 그때는 위험 알림과 재탐색 push 가 둘 다 나간다.
- 롤백 jar 는 새 originKind 후보를 404 로 처리한다.
- 전 여정 1분 주기 OTP 부하: 켜기 전 지표로 확인하고, permits 로 막는다.

## 5. 검증

- PR 마다 실행한다.
  - `./gradlew compileJava compileTestJava` + PR 테스트
  - journey 패키지 회귀(`JourneyTransferAssessmentIT` 는 기존 무관)
  - **플래그 off 회귀 테스트**
- IT 는 로컬 postgis 로 돌린다. 스크래치 클론에서는 `env/*.env` 를 파일 단위로 링크한다.
