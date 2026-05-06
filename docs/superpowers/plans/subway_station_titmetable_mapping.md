# Subway Station Timetable Mapping Plan

## Goal

기존 `subway_station_tago` 에 적재된 `tago_station_id` 를 `subway_station` 테이블의 각 역/노선 row에 매핑한다.

해당 작업의 목적은 `subway_timetable` 을 적재할 때 TAGO 시간표 API가 요구하는 `tago_station_id` 로 시간표를 조회하기 위함이다.

```text
subway_station_tago.tago_station_id
-> subway_station.tago_station_id
```

`subway_station.statn_id` 는 기존 서울 API 역 ID이므로 유지한다. `tago_station_id` 로 덮어쓰지 않는다.


---

## Source And Target

### Source: `subway_station_tago`

사용 컬럼:

```text
tago_station_id
station_name
route_name
line_id
```

### Target: `subway_station`

사용 컬럼:

```text
id
statn_id
statn_nm
line_id
search_line_name
```

추가할 컬럼:

```text
tago_station_id varchar(255)          -- subway_station_tago.tago_station_id
tago_mapping_status varchar(50)       -- EXACT / NORMALIZED / MANUAL / UNMATCHED / AMBIGUOUS
```



## Matching Keys
기본 매칭 기준:

```text
subway_station_tago.station_name <-> subway_station.statn_nm
subway_station_tago.route_name   <-> subway_station.search_line_name
```

추가 조건:

```text
subway_station_tago.line_id is not null
and subway_station.line_id is not null
=> 두 line_id가 같아야 한다.
```

즉 line_id가 양쪽 모두 존재하는 경우에는 line_id 불일치 row를 자동 매핑하지 않는다.

---

## Normalization

### Station Name

역명은 양쪽 모두 같은 규칙으로 정규화한다.

```text
1. trim
2. 모든 공백 제거
3. 끝의 "역" 제거
4. 괄호 내용 제거
```

예:

```text
서울역 -> 서울
잠실(송파구청) -> 잠실
신촌(경의중앙선) -> 신촌
효창공원앞 -> 효창공원앞
```

### Route Name

호선명은 우선 원문 비교 후, 실패하면 숫자/대표명 기반으로 정규화한다.

예:

```text
1호선 <-> 01호선
2호선 <-> 02호선
경의선 <-> 경의중앙선
수인선 <-> 수인분당선
분당선 <-> 수인분당선
GTX-A <-> 수도권 광역급행철도
```

초기 line dictionary:

```text
1호선, 01호선 -> 1001
2호선, 02호선 -> 1002
3호선, 03호선 -> 1003
4호선, 04호선 -> 1004
5호선, 05호선 -> 1005
6호선, 06호선 -> 1006
7호선, 07호선 -> 1007
8호선, 08호선 -> 1008
9호선, 09호선 -> 1009
경의선, 경의중앙선 -> 1063
공항철도 -> 1065
경춘선 -> 1067
수인선, 분당선, 수인분당선 -> 1075
신분당선 -> 1077
경강선 -> 1081
우이신설선 -> 1092
서해선 -> 1093
GTX-A, 수도권 광역급행철도 -> 1032
```

---

## Matching Algorithm

### Step 1: Exact Match

조건:

```text
source.station_name = target.statn_nm
source.route_name = target.search_line_name
line_id rule passes
```

결과가 정확히 1:1이면 `EXACT` 로 업데이트한다.

### Step 2: Normalized Match

Step 1 실패 row에 대해 정규화된 역명과 정규화된 호선 키로 매칭한다.

조건:

```text
normalize(source.station_name) = normalize(target.statn_nm)
normalizeLine(source.route_name) = normalizeLine(target.search_line_name)
line_id rule passes
```

결과가 정확히 1:1이면 `NORMALIZED` 로 업데이트한다.

### Step 3: Manual Review

자동 매핑 금지:

```text
source 1건이 target 여러 건에 매칭
target 1건이 source 여러 건에 매칭
양쪽 line_id가 존재하는데 서로 다름
역명만 같고 호선이 다름
```

상태:

```text
0건 -> UNMATCHED
2건 이상 -> AMBIGUOUS
사람이 확정 -> MANUAL
```

---

## Update Rule

자동 업데이트는 반드시 unique match만 허용한다.

```text
subway_station.tago_station_id = subway_station_tago.tago_station_id
subway_station.tago_mapping_status = EXACT | NORMALIZED
```

`subway_station.statn_id` 는 기존 서울 API 역 ID로 유지한다. `tago_station_id` 로 덮어쓰지 않는다.

---

## SQL Implementation Sketch

### 1. target 컬럼 추가

```sql
alter table subway_station
    add column if not exists tago_station_id varchar(255),
    add column if not exists tago_mapping_status varchar(50);
```

### 2. 정확 매칭 후보 확인

```sql
with candidates as (
    select
        s.id as subway_station_pk,
        t.id as tago_pk,
        t.tago_station_id,
        s.statn_nm,
        s.search_line_name,
        s.line_id as station_line_id,
        t.line_id as tago_line_id
    from subway_station s
    join subway_station_tago t
      on trim(s.statn_nm) = trim(t.station_name)
     and trim(s.search_line_name) = trim(t.route_name)
     and (
          s.line_id is null
          or t.line_id is null
          or s.line_id = t.line_id
     )
)
select *
from candidates
order by search_line_name, statn_nm;
```

### 3. 정확히 1:1인 exact match 업데이트

```sql
with candidates as (
    select
        s.id as subway_station_pk,
        t.id as tago_pk,
        t.tago_station_id
    from subway_station s
    join subway_station_tago t
      on trim(s.statn_nm) = trim(t.station_name)
     and trim(s.search_line_name) = trim(t.route_name)
     and (
          s.line_id is null
          or t.line_id is null
          or s.line_id = t.line_id
     )
),
unique_candidates as (
    select *
    from candidates c
    where not exists (
        select 1
        from candidates c2
        where c2.subway_station_pk = c.subway_station_pk
          and c2.tago_pk <> c.tago_pk
    )
      and not exists (
        select 1
        from candidates c2
        where c2.tago_pk = c.tago_pk
          and c2.subway_station_pk <> c.subway_station_pk
    )
)
update subway_station s
set tago_station_id = u.tago_station_id,
    tago_mapping_status = 'EXACT'
from unique_candidates u
where s.id = u.subway_station_pk;
```

### 4. normalized match 후보 확인

```sql
with source_norm as (
    select
        id,
        tago_station_id,
        line_id,
        regexp_replace(
            regexp_replace(
                regexp_replace(trim(station_name), '\s+', '', 'g'),
                '\(.*\)', '', 'g'
            ),
            '역$', '', 'g'
        ) as station_key,
        case
            when route_name in ('1호선', '01호선') then '1001'
            when route_name in ('2호선', '02호선') then '1002'
            when route_name in ('3호선', '03호선') then '1003'
            when route_name in ('4호선', '04호선') then '1004'
            when route_name in ('5호선', '05호선') then '1005'
            when route_name in ('6호선', '06호선') then '1006'
            when route_name in ('7호선', '07호선') then '1007'
            when route_name in ('8호선', '08호선') then '1008'
            when route_name in ('9호선', '09호선') then '1009'
            when route_name in ('경의선', '경의중앙선') then '1063'
            when route_name = '공항철도' then '1065'
            when route_name = '경춘선' then '1067'
            when route_name in ('수인선', '분당선', '수인분당선') then '1075'
            when route_name = '신분당선' then '1077'
            when route_name = '경강선' then '1081'
            when route_name = '우이신설선' then '1092'
            when route_name = '서해선' then '1093'
            when route_name in ('GTX-A', '수도권 광역급행철도') then '1032'
            else route_name
        end as line_key
    from subway_station_tago
),
target_norm as (
    select
        id,
        line_id,
        regexp_replace(
            regexp_replace(
                regexp_replace(trim(statn_nm), '\s+', '', 'g'),
                '\(.*\)', '', 'g'
            ),
            '역$', '', 'g'
        ) as station_key,
        case
            when search_line_name in ('1호선', '01호선') then '1001'
            when search_line_name in ('2호선', '02호선') then '1002'
            when search_line_name in ('3호선', '03호선') then '1003'
            when search_line_name in ('4호선', '04호선') then '1004'
            when search_line_name in ('5호선', '05호선') then '1005'
            when search_line_name in ('6호선', '06호선') then '1006'
            when search_line_name in ('7호선', '07호선') then '1007'
            when search_line_name in ('8호선', '08호선') then '1008'
            when search_line_name in ('9호선', '09호선') then '1009'
            when search_line_name in ('경의선', '경의중앙선') then '1063'
            when search_line_name = '공항철도' then '1065'
            when search_line_name = '경춘선' then '1067'
            when search_line_name in ('수인선', '분당선', '수인분당선') then '1075'
            when search_line_name = '신분당선' then '1077'
            when search_line_name = '경강선' then '1081'
            when search_line_name = '우이신설선' then '1092'
            when search_line_name = '서해선' then '1093'
            when search_line_name in ('GTX-A', '수도권 광역급행철도') then '1032'
            else search_line_name
        end as line_key
    from subway_station
    where tago_station_id is null
),
candidates as (
    select
        tn.id as subway_station_pk,
        sn.id as tago_pk,
        sn.tago_station_id
    from target_norm tn
    join source_norm sn
      on tn.station_key = sn.station_key
     and tn.line_key = sn.line_key
     and (
          tn.line_id is null
          or sn.line_id is null
          or tn.line_id = sn.line_id
     )
)
select *
from candidates
order by subway_station_pk, tago_pk;
```

이 후보에서 1:1 unique인 row만 `NORMALIZED` 로 업데이트한다. SQL은 exact update와 같은 `unique_candidates` 패턴을 사용한다.

---

## Validation

매핑률:

```sql
select line_id,
       search_line_name,
       count(*) as total,
       count(*) filter (where tago_station_id is not null) as mapped
from subway_station
group by 1, 2
order by 1, 2;
```

미매핑 target:

```sql
select id, line_id, search_line_name, statn_nm
from subway_station
where tago_station_id is null
order by line_id, search_line_name, statn_nm;
```

중복 매핑:

```sql
select tago_station_id, count(*)
from subway_station
where tago_station_id is not null
group by 1
having count(*) > 1;
```

line_id 불일치 후보:

```sql
select s.id as subway_station_pk,
       s.statn_nm,
       s.search_line_name,
       s.line_id as station_line_id,
       t.id as tago_pk,
       t.station_name,
       t.route_name,
       t.line_id as tago_line_id,
       t.tago_station_id
from subway_station s
join subway_station_tago t
  on trim(s.statn_nm) = trim(t.station_name)
where s.line_id is not null
  and t.line_id is not null
  and s.line_id <> t.line_id
order by s.statn_nm, s.search_line_name;
```

---

## Next Step

1. `subway_station` 에 `tago_station_id`, `tago_mapping_status` 컬럼을 추가한다.
2. exact match를 먼저 수행한다.
3. normalized match를 수행한다.
4. `UNMATCHED`, `AMBIGUOUS`, line_id mismatch를 수동 검증한다.
5. 매핑된 `tago_station_id` 기준으로 기존 `subway_timetable` 을 전체 교체한다.
