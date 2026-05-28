# ML Subway Delay Truth V2 Matching

## Background

`ml_subway_delay_truth` V1은 `EventTimetablePairer`의 `ORDINAL` 매칭 결과를
그대로 truth row로 저장한다.

현재 V1 매칭은 같은 `service_date + line_id + station_id + direction` 그룹에서
시간표와 도착 이벤트를 각각 `orderKey`로 정렬한 뒤, 같은 index끼리 붙인다.

```text
timetable[0] <-> event[0]
timetable[1] <-> event[1]
...
```

이 방식은 다음 조건에서 깨진다.

- 특정 그룹의 앞 시간대 `arrival_code=1` 이벤트가 수집되지 않음
- 심야 이벤트만 일부 관측됨
- 시간표는 첫차부터 존재하지만 이벤트는 심야부터 존재함
- count가 맞지 않는데도 `min(timetable_count, event_count)` 만큼 강제 매칭함
- 시간 차이와 행선지 불일치를 매칭 전에 검증하지 않음

실제 실패 예:

```text
scheduled_arrival_at = 2026-05-27 05:48:50
actual_arrived_at    = 2026-05-28 00:54:02
delay_seconds        = 68712
destination_name     = 한강진
end_station_name     = 응암
match_strategy       = ORDINAL
```

이 row는 실제 지연이 아니라 매칭 실패다. 같은 그룹에 더 적합한 시간표 row가
존재한다.

```text
arr_time = 005110
scheduled_arrival_at = 2026-05-28 00:51:10
end_station_name = 한강진
expected_delay ~= 172초
```

## Goal

V2 목표는 큰 `delay_seconds`를 truth label로 만들지 않는 것이다.

```text
large delay = train delay 아님
large delay = match failure
```

V2는 `ORDINAL`을 제거하지 않고, 안전한 happy path로 제한한다. count가 맞지
않는 그룹은 ordinal로 강제 매칭하지 않고, 먼저 code=3 보강을 수행한 뒤 count를
다시 비교한다.

```text
count equal + 30-minute time-window pass + optional metadata pass -> ORDINAL matched
count mismatch -> code=3 supplementation -> recount
time-window fail -> MATCH_REJECTED_TIME_DISTANCE issue, no truth
```

## Non-Goals

- V2에서 완전한 최적화 매칭(`COST_ALIGN`)까지 바로 구현하지 않는다.
- 모든 inferred event를 학습 데이터로 사용하지 않는다.
- API 수집 주기를 1초로 바꾸는 것을 해결책으로 삼지 않는다.
- 기존 V1 truth row를 소급 수정하지 않는다. V2 재생성으로 비교한다.

## Decisions

### Issue 저장 위치

`MATCH_REJECTED_TIME_DISTANCE`, `COUNT_MISMATCH`, `DESTINATION_MISMATCH`는 기존
`subway_arrival_event_match_issue`에 저장한다.

근거:

- `issue_type`은 DB에서 `VARCHAR(50)`이므로 새 issue type 저장용 DB migration은 필요 없다.
- Java `MatchIssueType` enum 확장은 필요하다.
- 기존 컬럼(`timetable_count`, `event_count`, `scheduled_arrival_at`,
  `actual_arrived_at`, `details`)이 V2 진단에 충분하다.
- 별도 diagnostics table은 schema 분기와 조회 분기 비용이 크다.

`details`에는 JSON 문자열로 부가 정보를 기록한다.

```json
{
  "delay_seconds": 68712,
  "destination_event": "한강진",
  "destination_timetable": "응암",
  "rejection_reason": "TIME_DISTANCE"
}
```

V3에서 diagnostics 양이 폭증하면 별도 table 분리를 재검토한다.

### Reject 정책

V2-1은 group-level reject를 사용한다.

```java
boolean rejectGroup = pairs.stream().anyMatch(p ->
    p.destinationMismatch() || Math.abs(p.delaySeconds()) > maxMatchDistanceSeconds
);
if (rejectGroup) {
    emitIssues();
    return emptyMatched();
}
```

근거:

- 한 pair가 19시간 차이면 group order가 깨졌다는 강한 신호다.
- ordinal 기반에서 pair 일부만 살리면 뒤쪽 label 오염 가능성이 크다.
- 구현이 단순하고 검증하기 쉽다.

pair-level reject는 V2 운영 데이터 검증 후 V2-2에서 재검토한다.

### Match Window

V2-1은 전 호선 공통 `MAX_MATCH_DISTANCE_SECONDS = 1800`을 사용한다.

```yaml
batch:
  delay-truth:
    matching-version: v2
    max-match-distance-seconds: 1800
```

근거:

- 호선별 baseline 데이터가 아직 없어 호선별 임계값 근거가 없다.
- 단일 값이 운영과 튜닝에 단순하다.
- 15분 이상 실제 지연 가능성을 고려해 30분으로 넉넉하게 둔다.

V2-2에서 운영 데이터 기반 호선별 override를 검토한다.

```yaml
batch:
  delay-truth:
    max-match-distance-seconds: 1800
    line-overrides:
      # V2-2에서 추가 예정
```

### Versioning

`matching-version: v1|v2` feature flag를 둔다. V1과 V2를 1주 dual-run으로 비교한 뒤
V2를 default로 전환하고 V1은 deprecated 처리한다.

V1 호환을 위해 기존 `EventTimetablePairer`는 유지하고, V2는
`EventTimetablePairerV2` 신규 클래스로 구현한다. `SubwayDelayTruthGenerationService`가
version flag로 분기한다.

### COST_ALIGN

`COST_ALIGN`은 V2에 포함하지 않고 V3로 분리한다.

단계:

```text
V2-1: ORDINAL + count guard + time-window + destination hard reject
V2-2: 호선별 임계값 override + pair-level reject 일부 도입 검토
V3:   COST_ALIGN 도입
```

V3 진입 조건:

- V2-1을 1주 운영한다.
- truth p50/p90이 현실 범위로 정상화된다.
- 잔여 `COUNT_MISMATCH` 비율이 10% 미만에 근접한다.

## Definitions

### Match Group

기본 그룹 키:

```text
service_date
line_id
station_id
direction
day_type
```

시간표 조회에는 `station_id` 대신 `tago_station_id`를 사용한다.

### Event Source

```text
OBSERVED_CODE_1
INFERRED_FROM_PREV_DEPARTURE
```

### Time Window

초기값:

```text
MAX_MATCH_DISTANCE_SECONDS = 1800
```

즉 `abs(actual_arrived_at - scheduled_arrival_at) <= 30분`일 때만 match로 인정한다.
15분 이상 지연도 실제 운행에서 발생할 수 있으므로 V2-1은 넉넉한 30분 window를
사용한다.

### Destination Check

행선지가 명확히 다르면 다른 열차로 본다.

```text
both known and normalize(event.destination_name) == normalize(timetable.end_station_name)
-> destination pass

both known and normalize(event.destination_name) != normalize(timetable.end_station_name)
-> DESTINATION_MISMATCH hard reject

one or both unknown
-> no hard reject, record destination_unknown
```

`DESTINATION_MISMATCH`는 truth label이 아니다. 다만 `destination_name` 또는
`end_station_name`이 null/unknown이면 hard reject하지 않는다. API/시간표 데이터 누락
때문에 정상 후보를 버리지 않기 위함이다.

## Current Phase C

이미 code=3 보강 로직은 존재한다.

```text
arrival_code = '3' 전역출발
-> received_at + segment travel time
-> inferred arrival event
-> event_source = INFERRED_FROM_PREV_DEPARTURE
```

한계:

현재 Phase C는 B-1의 `NO_RAW_EVENT` issue만 입력으로 삼는다. 그런데 B-1이
`ORDINAL`로 잘못 matched 처리하면, 실제로 비어 있는 시간표 slot이
`NO_RAW_EVENT`로 남지 않는다. 그래서 code=3 보강 대상에서도 빠진다.

V2에서는 B-1이 `time-window`를 통과하지 못한 pair를 matched로 확정하지 않아야
큰 delay가 truth label로 저장되지 않는다.

## V2 Flow

### Phase B-1. Group Count Classification

그룹별로 시간표 수와 이벤트 수를 비교한다.

```text
if event_count == timetable_count:
    ORDINAL_CANDIDATE
else:
    COUNT_MISMATCH
```

`COUNT_MISMATCH` 그룹은 즉시 truth matched로 보내지 않는다.

진단:

```text
TIMETABLE_MORE_THAN_EVENT
EVENT_MORE_THAN_TIMETABLE
```

중요: count mismatch 그룹에서는 V2-1에서 nearest matching을 하지 않는다. 먼저
code=3 보강으로 이벤트 수를 복원할 수 있는지 확인하고, 보강 후에도 count가
맞지 않으면 진단으로 남긴다.

### Phase B-2. Matching Candidate Validation

`event_count == timetable_count` 그룹에 대해서만 순서 기반 pair를 만든다.

각 pair에 대해 행선지와 시간창을 검증한다.

```text
delay = actual_arrived_at - scheduled_arrival_at

if known-known destination mismatch:
    DESTINATION_MISMATCH
else if abs(delay) > MAX_MATCH_DISTANCE_SECONDS:
    MATCH_REJECTED_TIME_DISTANCE
else:
    MATCHED
```

destination 상태도 함께 기록한다.

```text
destination_match = true | false | unknown
```

`MATCH_REJECTED_TIME_DISTANCE`와 `DESTINATION_MISMATCH`는 truth label이 아니다.

`event_count != timetable_count` 그룹은 ordinal pair를 만들지 않는다. V2-1에서는
nearest matching도 수행하지 않는다. 해당 그룹은 Phase C 보강 대상으로 넘긴다.

```text
COUNT_MISMATCH
-> ordinal 금지
-> Phase C code=3 보강 대상
-> 보강 후 recount
```

이 방식은 다음 케이스를 올바르게 처리하기 위한 최소 조건이다.

```text
timetable = 05:48, ..., 00:51
event     = 00:54

expected:
Phase C가 code=3으로 결측 복원을 시도
-> code=3 후보가 충분하면 event_count와 timetable_count 재비교
-> count equal이면 ordinal + 30-minute time-window 검증
-> 여전히 mismatch면 COUNT_MISMATCH issue
```

즉 count mismatch에서 `NO_RAW_EVENT` slot을 개별 시간표 row 단위로 확정하지 않는다.
V2-1에서는 group 단위 `COUNT_MISMATCH`를 Phase C 입력으로 삼고, 보강 후에도 count가
맞지 않는 그룹만 최종 mismatch issue로 남긴다.

### Phase C. Code 3 Supplementation

대상:

```text
event_count < timetable_count인 COUNT_MISMATCH 그룹
```

입력 slot:

```text
COUNT_MISMATCH groups
```

slot 정의:

```text
COUNT_MISMATCH group =
    event_count < timetable_count
```

초기 V2-1에서 count mismatch 보강은 group 단위로 수행한다. code=3 후보 생성 시
해당 group의 raw만 대상으로 삼고, 보강 저장 수는 필요한 결측 수를 초과하지 않는다.
필요 결측 수는 `max(timetable_count - event_count, 0)`이다. event가 더 많은 그룹은
code=3으로 보강하지 않는다.

`MATCH_REJECTED_TIME_DISTANCE`는 V2-1에서 Phase C 보강 대상으로 삼지 않는다. count는
맞지만 시간창 검증에 실패한 상태이므로 필요한 결측 수가 정의되지 않는다. 이 경우는
issue로 남기고 truth row를 생성하지 않는다.

`DESTINATION_MISMATCH`도 V2-1에서 Phase C 보강 대상으로 삼지 않는다. 행선지가 명확히
다른 pair는 다른 열차로 보고 issue로 남긴다.

처리:

```text
arrival_code=3 raw
group by line_id + station_id + direction + train_no
split by 10 minute gap
arrived_at = first_received_at + segment_travel_time(prev_station -> current_station)
dedup against OBSERVED_CODE_1 within 5 minutes
save as INFERRED_FROM_PREV_DEPARTURE
```

주의:

code=3 후보는 모든 event로 만들지 않는다. 보강 대상 slot이 있는 group에 대해서만
candidate를 만든다.

### Phase B-3. Rematch After Supplementation

최종 event set:

```text
OBSERVED_CODE_1
INFERRED_FROM_PREV_DEPARTURE
```

다시 그룹별 count를 비교하고 매칭 후보를 만든다.

```text
if event_count == timetable_count:
    ordinal pair
    time-window validation
else:
    remain COUNT_MISMATCH issue
```

보강 후에도 time-window 실패면 matched로 저장하지 않는다.

### Phase Truth. Truth Row Generation

truth row 생성 대상:

```text
MATCHED only
```

학습 가능 여부:

```text
event_source = OBSERVED_CODE_1
and abs(delay_seconds) <= MAX_MATCH_DISTANCE_SECONDS
and no known-known destination mismatch
```

저장 정책:

```text
OBSERVED_CODE_1 matched
-> excluded_from_training = false

INFERRED_FROM_PREV_DEPARTURE matched
-> excluded_from_training = true
-> exclude_reason = INFERRED_EVENT

MATCH_REJECTED_TIME_DISTANCE
-> truth row 생성 안 함
-> issue로 저장
```

권장: V2에서는 rejected pair를 truth row로 만들지 않는다. 큰 delay를 truth table에
남기면 이후 분석에서 계속 label처럼 보인다.

## Issue Types

기존:

```text
MAPPING_MISSING
NO_RAW_EVENT
EXTRA_RAW_EVENT
```

V2 추가 후보:

```text
MATCH_REJECTED_TIME_DISTANCE
COUNT_MISMATCH
DESTINATION_MISMATCH
```

현재 코드에는 `MatchIssueType` enum이 있으므로 Java enum 확장이 필요하다.
DB 컬럼은 `VARCHAR(50)`이므로 DB migration은 필요 없다.

```java
public enum MatchIssueType {
    MAPPING_MISSING,
    EXTRA_RAW_EVENT,
    NO_RAW_EVENT,
    MATCH_REJECTED_TIME_DISTANCE,
    COUNT_MISMATCH,
    DESTINATION_MISMATCH
}
```

## Matching Policy

### Allowed Ordinal Match

```text
event_count == timetable_count
and every pair abs(delay_seconds) <= 1800
and no known-known destination mismatch
```

### Rejected Ordinal Match

```text
event_count == timetable_count
but any pair abs(delay_seconds) > 1800
```

이 경우 해당 group 전체를 rejected로 볼지, 실패 pair만 rejected로 볼지 결정해야 한다.

초기 V2 권장:

```text
group-level reject
```

이유:

한 pair가 19시간 차이면 group order 자체가 깨졌을 가능성이 높다. 일부 pair만
살리면 뒤쪽 label에 오염이 남을 수 있다.

known-known destination mismatch도 group-level reject로 처리한다.

### Count Mismatch

```text
event_count < timetable_count
-> ordinal 금지
-> Phase C code=3 보강 대상
-> 보강 후 recount

event_count > timetable_count
-> ordinal 금지
-> Phase C 보강 대상 아님
-> EXTRA_RAW_EVENT / COUNT_MISMATCH 진단 대상
```

## Destination Normalization

초기 정규화:

```text
trim
remove whitespace
remove trailing "행"
remove trailing "역"
remove parenthesis suffix
```

예:

```text
한강진행 -> 한강진
한강진역 -> 한강진
잠실(송파구청) -> 잠실
```

주의:

환승역/동명이역은 `line_id`와 함께 해석한다.

## Implementation Plan

### Step 1. Pairing Result 확장

`EventTimetablePairer` 또는 V2 pairer에 다음 결과를 추가한다.

```text
matched
rejectedByTimeDistance
countMismatchGroups
unmatchedTimetables
unmatchedEvents
mappingMissing
invalidEvents
invalidTimetables
```

V1 호환을 위해 기존 pairer를 바로 바꾸기보다 `EventTimetablePairerV2` 또는
strategy option을 두는 편이 안전하다.

### Step 2. Count Equal Guard

현재:

```java
int matchPairs = Math.min(timetableCount, eventCount);
for (int i = 0; i < matchPairs; i++) matched.add(...)
```

V2:

```text
if timetableCount != eventCount:
    emit count mismatch diagnostic
    skip ordinal matching
    send event_count < timetable_count groups to Phase C
else:
    build ordinal pairs
    validate time-window
```

### Step 3. Time Window Guard

ordinal pair 생성 후:

```text
if knownKnownDestinationMismatch(event, timetable):
    reject group as DESTINATION_MISMATCH
else if abs(Duration.between(scheduled, actual).getSeconds()) > 1800:
    reject group
```

초기 V2는 group-level reject.

`MATCH_REJECTED_TIME_DISTANCE` group은 V2-1에서 Phase C로 보내지 않는다. issue로만
남기고 truth row를 생성하지 않는다.

`DESTINATION_MISMATCH` group도 Phase C로 보내지 않는다. issue로만 남기고 truth row를
생성하지 않는다.

### Step 4. Phase C Input 확장

현재 Phase C input:

```text
NO_RAW_EVENT only
```

V2 Phase C input:

```text
COUNT_MISMATCH groups where event_count < timetable_count
```

단, `event_count > timetable_count` 그룹은 code=3 보강 대상이 아니다.

### Step 5. Truth Generation 변경

`SubwayDelayTruthGenerationService`는 `result.matched()`만 truth row로 저장한다.

`rejectedByTimeDistance`는 저장하지 않거나 issue table로만 보낸다.

### Step 6. Feature Flag

설정:

```yaml
batch:
  delay-truth:
    matching-version: v2
    max-match-distance-seconds: 1800
```

초기에는 V1/V2를 같은 serviceDate에 비교할 수 있게 별도 branch 또는 local run으로 검증한다.

## Verification Queries

### V2 전후 outlier 비교

```sql
select line_id,
       count(*) as total,
       count(*) filter (where excluded_from_training = false) as trainable,
       count(*) filter (where abs(delay_seconds) > 1800) as outlier,
       percentile_cont(0.5) within group (order by delay_seconds) as p50,
       percentile_cont(0.9) within group (order by delay_seconds) as p90
from ml_subway_delay_truth
where service_date = :service_date
  and deleted_at is null
group by line_id
order by line_id;
```

### 심야 이벤트가 첫차 시간표와 붙는지 확인

```sql
select id, line_id, station_name, direction,
       scheduled_arrival_at, actual_arrived_at, delay_seconds,
       destination_name, end_station_name, match_strategy
from ml_subway_delay_truth
where service_date = :service_date
  and deleted_at is null
  and actual_arrived_at::time < time '04:00'
  and scheduled_arrival_at::time >= time '04:00'
  and abs(delay_seconds) > 1800
order by abs(delay_seconds) desc
limit 50;
```

V2 성공 시 위 결과는 0에 가까워야 한다.

### Phase C 효과 확인

```sql
select line_id,
       count(*) filter (where event_source = 'OBSERVED_CODE_1') as observed,
       count(*) filter (where event_source = 'INFERRED_FROM_PREV_DEPARTURE') as inferred
from subway_arrival_event
where service_date = :service_date
  and deleted_at is null
group by line_id
order by line_id;
```

## Success Criteria

- `abs(delay_seconds) > 1800` truth row가 대폭 감소한다.
- 00:00~04:00 실제 이벤트가 05:00~06:00 첫차 시간표와 붙지 않는다.
- `MATCH_REJECTED_TIME_DISTANCE`가 issue로 남고 truth label로 저장되지 않는다.
- `DESTINATION_MISMATCH`가 issue로 남고 truth label로 저장되지 않는다.
- code=3 보강 이후 `event_count < timetable_count`인 `COUNT_MISMATCH` 그룹이 감소한다.
- code=3 보강으로 count equal이 된 그룹 수를 리포트한다.
- `INFERRED_FROM_PREV_DEPARTURE`는 저장되지만 학습 제외된다.
- 9호선/2호선/6호선/8호선의 p50/p90 delay가 현실 범위로 돌아온다.

## Rollout

1. V1 결과 백업 또는 serviceDate 기준 재현 가능 상태 확보
2. V2 pairer 구현
3. 단위 테스트 추가
4. 2026-05-27 하루치 재실행
5. V1/V2 분포 비교
6. 문제 호선 샘플 수동 검증
7. 1주 dual-run으로 V1/V2 비교
8. 통과 시 V2를 기본 matching version으로 전환하고 V1 deprecated 처리

## Required Tests

- count equal + all pairs within 30 minutes -> matched
- count equal + one pair over 30 minutes -> group rejected
- count equal + known-known destination mismatch -> DESTINATION_MISMATCH group rejected
- count equal + destination unknown + within 30 minutes -> matched with destination_unknown recorded
- event_count < timetable_count -> no ordinal matched, Phase C supplementation attempted
- event_count < timetable_count + code3 fills missing count -> recount equal, ordinal + time-window validation
- event_count < timetable_count + code3 insufficient -> remain COUNT_MISMATCH issue
- event_count > timetable_count -> no ordinal matched, no code3 supplementation, issue remains
- MATCH_REJECTED_TIME_DISTANCE group does not create Phase C supplement target in V2-1
- code=3 candidate near timetable slot becomes inferred event
- inferred event matched truth is excluded with `INFERRED_EVENT`
- midnight timetable `005110` maps to `serviceDate + 1 day 00:51:10`
- midnight event does not match first-train timetable when time-window fails

## Future Work

- V2-2: 호선별 `max-match-distance-seconds` override
- V2-2: 운영 데이터 기반 pair-level reject 일부 도입 검토
- V3: `COST_ALIGN` 도입
- V3: diagnostics 양 폭증 시 별도 issue table 분리 검토
