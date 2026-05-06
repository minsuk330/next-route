Phase B 구현 플랜: subway_arrival_event →
subway_arrival_event_match_issue

목표

일 배치로 subway_arrival_event 와 subway_timetable 의 운행 row
개수/순서를 검증하고, 불일치를 subway_arrival_event_match_issue 에
저장한다.

▎ Phase B의 1차 목표는 학습 데이터셋이 아닌 시간표 vs 이벤트 정합성
▎  진단이다.

  ---
0. 작업 트리 (Task Tree)

Phase B
├─ B-1. 변환 유틸 (TimetableConverter)
│   ├─ direction: 상행/하행/내선/외선 → U/D
│   ├─ serviceDate → dayType (공휴일 포함)
│   └─ arrTime/depTime("HHmmss" or "0") → service_date 기준
LocalDateTime
│
├─ B-2. 도메인 모델
│   ├─ SubwayArrivalEventMatchIssue 엔티티
│   ├─ SubwayArrivalEventMatchIssueRepository
│   └─ SubwayArrivalEventRepository: Phase B용 추가 쿼리
│
├─ B-3. Phase B 서비스 (TimetableMatchingService)
│   ├─ Step 7: count + order matching
│   ├─ Step 8: MAPPING_MISSING / EXTRA / NO_RAW_EVENT 분류 후 저장
│   └─ delete-and-recompute (idempotent)
│
├─ B-4. 배치 통합
│   └─ SubwayArrivalEventBatchScheduler: Phase A 끝나면 Phase B
자동 실행
│
└─ B-5. 테스트
├─ TimetableConverter 단위 테스트
└─ TimetableMatchingService 시나리오 테스트

  ---
1. 새 파일/변경 파일 목록

파일: application/subway/service/TimetableMatchingService.java
종류: 신규
책임: Step 7~8 메인 로직
────────────────────────────────────────
파일: application/subway/service/TimetableConverter.java
종류: 신규
책임: direction/dayType/arrTime 변환 + service-day order key
────────────────────────────────────────
파일: application/subway/dto/MatchGroup.java
종류: 신규
책임: 비교 그룹 단위 데이터
────────────────────────────────────────
파일: application/subway/dto/HolidayCalendar.java (port-in 또는
단순
컴포넌트)
종류: 신규
책임: 공휴일 판정. 인터페이스(isHoliday(LocalDate)) + 운영용 구현체.
경고: 빈 stub은 ❌. 공휴일을 평일/토요일 시간표로 잘못 매핑하면
      dayType이 틀려 매칭 결과 전체가 어긋난다(특히 dayType=03 일요일
      집중 변경 노선). 초기 운영부터 실제 공휴일 데이터가 필요하다.
      옵션: (a) 공휴일 DB 테이블 + 연 1회 갱신, (b) 한국천문연구원 공공
      API 연동, (c) Jollyday/openholidaysapi 등 라이브러리. 구현 전에
      어떤 옵션을 쓸지 확정한다.
────────────────────────────────────────
파일: domain/subway/entity/SubwayArrivalEventMatchIssue.java
종류: 신규
책임: 진단 row 엔티티
────────────────────────────────────────
파일: domain/subway/entity/MatchIssueType.java (enum)
종류: 신규
책임: MAPPING_MISSING / EXTRA_RAW_EVENT / NO_RAW_EVENT
────────────────────────────────────────
파일: domain/subway/entity/ScheduledTimeSource.java (enum)
종류: 신규
책임: ARR_TIME / DEP_TIME_MINUS_30S_FOR_ZERO_ARRIVAL
────────────────────────────────────────
파일: domain/subway/repository/SubwayArrivalEventMatchIssueReposito
ry.java
종류: 신규
책임: deleteByServiceDate, saveAll
────────────────────────────────────────
파일: domain/subway/repository/SubwayArrivalEventRepository.java
종류: 수정
책임: findByServiceDateOrderByLineStationDirection 등 매칭용 쿼리
────────────────────────────────────────
파일: domain/subway/repository/SubwayTimetableRepository.java
종류: 수정
책임: findByLineIdAndTagoStationIdAndDayTypeAndDirection 추가
(lineId 포함)
────────────────────────────────────────
파일: domain/subway/service/SubwayDataService.java
종류: 수정
책임: match issue / timetable 추가 메서드
────────────────────────────────────────
파일:
application/subway/service/SubwayArrivalEventBatchScheduler.java
종류: 수정
책임: Phase A 후 Phase B 호출
────────────────────────────────────────
파일: db/migration/V*__create_subway_arrival_event_match_issue.sql
종류: 신규 (Flyway)
책임: 진단 테이블 DDL

▎ 진단 테이블 DDL은 Flyway로 만든다 (현재 flyway.enabled: true,
▎ ddl-auto: validate).

  ---
2. 핵심 모듈 설계

2-1. TimetableConverter (B-1)

순수 함수 모음. Spring Bean으로 등록하되 외부 의존 없음
(HolidayCalendar만 주입).

String toTimetableDirection(String rawDirection)  // 상행/내선→U,
하행/외선→D
String toDayType(LocalDate serviceDate)           //
평일01/토02/일·공휴일03
LocalDateTime toScheduledArrivalAt(LocalDate serviceDate, String
arrTime, String depTime)
//   arrTime != "0" → arrTime 기준 LocalDateTime
//   arrTime == "0" → depTime을 LocalDateTime으로 변환한 뒤 -30s
ScheduledTimeSource sourceOf(String arrTime)

long toTimetableOrderKey(LocalDate serviceDate, String arrTime,
                         String depTime)
//   timetable row 전용 정렬 키.
//     arrTime != "0" → toScheduledArrivalAt(serviceDate, arrTime, _)
//     arrTime == "0" → toScheduledArrivalAt(serviceDate, "0", depTime)
//                      → 내부에서 depTime 변환 후 -30s 적용
//   결과 LocalDateTime 을 service-day order key 로 환산해서 반환.
//   "0" 만으로는 시각을 만들 수 없으므로 depTime 이 반드시 함께 필요.

long toEventOrderKey(LocalDate serviceDate, LocalDateTime arrivedAt)
//   event 전용 정렬 키. arrivedAt 은 이미 LocalDateTime 이므로 단순
//   환산만 필요하다.

두 함수 모두 동일한 service-day 환산 규칙을 사용한다:

base = serviceDate.atTime(4, 0).atZone(KST)
orderKey = Duration.between(base, ts.atZone(KST)).toSeconds()

이 규칙으로 04:00 KST 경계 이전(00:00~03:59) 시각도 ts 자체가
service_date+1 LocalDateTime 으로 만들어져 있으면 자연스럽게 큰 양수가
된다. 단순 LocalDateTime 정렬은 arrTime="0" + depTime="000010" → -30s
가 service_date 23:59:40 으로 떨어지는 케이스에서 잘못 정렬되므로 두
함수의 결과 long 값으로만 정렬한다.

서비스-데이 LocalDateTime 변환 규칙 (HHmmss → LocalDateTime):

hh = HHmmss의 시
hh >= 4  → service_date + HH:mm:ss
hh <  4  → service_date + 1day + HH:mm:ss

이 변환은 toScheduledArrivalAt() 내부 단계이며, 정렬은 별도로
toTimetableOrderKey / toEventOrderKey 결과 (long) 로 수행한다.

2-2. MatchGroup DTO

Step 7의 비교 단위.

record MatchGroup(
LocalDate serviceDate,
String lineId,
String tagoStationId,
String stationId,
String stationName,
String direction,        // U/D
String dayType,
List<TimetableEntry> timetables,   // toTimetableOrderKey ASC
List<EventEntry> events            // toEventOrderKey ASC
) {
String matchGroupKey();  //
service_date|lineId|tagoStationId|dayType|direction
}

record TimetableEntry(
SubwayTimetable timetable,
LocalDateTime scheduledArrivalAt,    // toScheduledArrivalAt 결과
ScheduledTimeSource timeSource,      // ARR_TIME / DEP_TIME_MINUS_30S_FOR_ZERO_ARRIVAL
long orderKey                        // toTimetableOrderKey(serviceDate, arrTime, depTime)
)

record EventEntry(
SubwayArrivalEvent event,
long orderKey                        // toEventOrderKey(serviceDate, event.arrivedAt)
)

정렬 기준 (양쪽 모두):
- orderKey 오름차순 (단순 LocalDateTime 비교가 아니라 service-day order)
- tie-break: timetable은 (id ASC), event는 (arrived_at ASC, id ASC)

2-3. SubwayArrivalEventMatchIssue 엔티티

┌──────────────────────────┬────────┬─────────────────────────┐
│           컬럼           │  타입  │          비고           │
├──────────────────────────┼────────┼─────────────────────────┤
│ service_date             │ DATE   │ NOT NULL                │
├──────────────────────────┼────────┼─────────────────────────┤
│                          │        │ NOT NULL                │
│ issue_type               │ VARCHA │ (MAPPING_MISSING /      │
│                          │ R      │ EXTRA_RAW_EVENT /       │
│                          │        │ NO_RAW_EVENT)           │
├──────────────────────────┼────────┼─────────────────────────┤
│ line_id                  │ VARCHA │                         │
│                          │ R      │                         │
├──────────────────────────┼────────┼─────────────────────────┤
│                          │ VARCHA │ event 측 식별자 (MAPPIN │
│ station_id               │ R      │ G_MISSING/EXTRA에서     │
│                          │        │ 사용)                   │
├──────────────────────────┼────────┼─────────────────────────┤
│ station_name             │ VARCHA │                         │
│                          │ R      │                         │
├──────────────────────────┼────────┼─────────────────────────┤
│ tago_station_id          │ VARCHA │ NO_RAW_EVENT의 경우     │
│                          │ R      │ timetable 측            │
├──────────────────────────┼────────┼─────────────────────────┤
│ direction                │ VARCHA │ U/D                     │
│                          │ R      │                         │
├──────────────────────────┼────────┼─────────────────────────┤
│ day_type                 │ VARCHA │ 01/02/03                │
│                          │ R      │                         │
├──────────────────────────┼────────┼─────────────────────────┤
│ match_group_key          │ VARCHA │ 조회 인덱스             │
│                          │ R      │                         │
├──────────────────────────┼────────┼─────────────────────────┤
│ timetable_id             │ BIGINT │ NO_RAW_EVENT만 채움     │
│                          │  NULL  │                         │
├──────────────────────────┼────────┼─────────────────────────┤
│ arrival_event_id         │ BIGINT │ EXTRA_RAW_EVENT/MAPPING │
│                          │  NULL  │ _MISSING만 채움         │
├──────────────────────────┼────────┼─────────────────────────┤
│                          │ TIMEST │                         │
│ scheduled_arrival_at     │ AMP    │ NO_RAW_EVENT            │
│                          │ NULL   │                         │
├──────────────────────────┼────────┼─────────────────────────┤
│                          │ TIMEST │                         │
│ actual_arrived_at        │ AMP    │ EXTRA_RAW_EVENT         │
│                          │ NULL   │                         │
├──────────────────────────┼────────┼─────────────────────────┤
│ timetable_order_index    │ INT    │                         │
│                          │ NULL   │                         │
├──────────────────────────┼────────┼─────────────────────────┤
│ event_order_index        │ INT    │                         │
│                          │ NULL   │                         │
├──────────────────────────┼────────┼─────────────────────────┤
│ timetable_count          │ INT    │                         │
├──────────────────────────┼────────┼─────────────────────────┤
│ event_count              │ INT    │                         │
├──────────────────────────┼────────┼─────────────────────────┤
│ scheduled_time_source    │ VARCHA │ NO_RAW_EVENT인 경우     │
│                          │ R NULL │                         │
├──────────────────────────┼────────┼─────────────────────────┤
│ details                  │ TEXT   │ 추가 진단 메시지        │
│                          │ NULL   │                         │
├──────────────────────────┼────────┼─────────────────────────┤
│ BaseEntity (id/createdAt │        │                         │
│ /updatedAt/deletedAt)    │        │                         │
└──────────────────────────┴────────┴─────────────────────────┘

인덱스: (service_date), (match_group_key).

2-4. TimetableMatchingService.matchForDate(LocalDate) 알고리즘

알고리즘은 event 그룹과 timetable 그룹의 key union 을 순회한다.
event 가 0 이고 timetable 만 있는 그룹(예: 운행 종료 후 collector 정지,
역 단위 데이터 누락)은 event 그룹에 존재하지 않으므로 timetable 쪽
key 도 함께 만들어야 한다.

비교 단위 키 (정규화된 좌표계):

  CompareKey(lineId, stationId, directionUD)
  ── directionUD ∈ {"U", "D"}.

  event 의 원본 direction (상행/하행/내선/외선) 과 timetable 의
  direction (U/D) 은 직접 비교할 수 없다. 한쪽 좌표계로 통일해야
  하며, 본 알고리즘은 U/D 로 정규화한다. 이유:

    · 상행/내선 → U, 하행/외선 → D 는 함수형 매핑(N:1).
    · 반대 방향(U → 상행/내선) 은 1:N 이라 timetable-only key 를
      event 좌표계로 되돌릴 수 없다.
    · 따라서 양쪽을 U/D 좌표계로 변환한 뒤 union 한다.

1. delete from subway_arrival_event_match_issue where service_date
   = :serviceDate
2. events = subway_arrival_event findByServiceDate
3. eventsGrouped = events groupBy
       (lineId, stationId,
        converter.toTimetableDirection(rawDirection))
   ─ rawDirection 이 null 이거나 변환 결과가 null 이면 그룹화에서
     제외하고 dataQuality 로그를 남긴다.
4. timetable-only 그룹 보강:
   a. dayType = converter.toDayType(serviceDate)
   b. timetableCoverage = subwayTimetableRepo.findDistinctCoverage(
                              dayType)
      → 결과 row: (lineId, tagoStationId, directionUD)
   c. CompareKey 로 환산:
      각 (lineId, tagoStationId, directionUD) 에 대해
      subwayStationRepo.findByLineIdAndTagoStationId(lineId,
                                                    tagoStationId)
      → (lineId, station.statn_id, directionUD) 의 CompareKey 생성.
      매핑이 0건인 timetable coverage 는 별도 dataQuality 로그.
      매핑이 N건(드물게 같은 lineId+tagoStationId 가 여러 statn_id 를
      가지는 케이스) 이면 모든 statn_id 를 펼쳐서 CompareKey 후보로
      포함한다.
   d. compareKeys = eventsGrouped.keys ∪ timetableCoverage.keys
      ─ 둘 다 (lineId, stationId, directionUD) 좌표계이므로 직접 union.
5. For each compareKey in compareKeys:
   a. station = subwayStationRepo.findByStationIdAndLineId(stationId, lineId)
   ─ 같은 stationId가 다른 line context(환승역 등)에서 충돌할 수
     있으므로 lineId를 함께 지정해야 한다.
   if station == null or station.tagoStationId == null:
       events 가 있으면 → MAPPING_MISSING (전체 이벤트)
       events 가 없으면 → 매핑 자체가 불가능하므로 추적 불가능,
                          info 로그 후 skip
   continue
   b. directionUD 는 compareKey 에 이미 정규화되어 있으므로 추가
      변환 불필요.
   rawTimetables = subwayTimetableRepo
       .findByLineIdAndTagoStationIdAndDayTypeAndDirection(
           lineId, station.tagoStationId, dayType, directionUD)
   timetables = rawTimetables.map { tt ->
       scheduledAt = converter.toScheduledArrivalAt(serviceDate,
                         tt.arrTime, tt.depTime)
       TimetableEntry(tt, scheduledAt, sourceOf(tt.arrTime),
                      converter.toTimetableOrderKey(
                          serviceDate, tt.arrTime, tt.depTime))
   }.sortedBy { orderKey }
   events = (eventsGrouped[compareKey] ?: []).map { ev ->
       EventEntry(ev,
           converter.toEventOrderKey(serviceDate, ev.arrivedAt))
   }.sortedBy { orderKey }
   ─ 정렬 기준은 둘 다 service-day orderKey (long).
     LocalDateTime 직접 비교는 arrTime="0" + depTime="000010" → -30s
     케이스에서 잘못 정렬되므로 사용 금지.
   c. matchPairs = min(timetable.size, event.size)
   앞에서부터 matchPairs만큼은 MATCHED (저장 X — Phase B는
   진단만 저장)
   d. timetable.size > event.size:
   → 남은 timetable[matchPairs..] → NO_RAW_EVENT issue
      (event.size == 0 인 timetable-only key 의 경우에도 동일하게
       전 timetable 이 NO_RAW_EVENT 로 기록됨)
   e. event.size > timetable.size:
   → 남은 event[matchPairs..] → EXTRA_RAW_EVENT issue
      (timetable.size == 0 인 경우 전 event 가 EXTRA_RAW_EVENT)
6. saveAll(issues)
7. log {service_date, mapping_missing_count, extra_raw_event_count,
   no_raw_event_count}

중요한 가드:
- rawDirection 이 null 이거나 toTimetableDirection 변환이 null 인
  이벤트는 Phase A에서 이미 걸러졌어야 하지만, 방어 코드로 로그 후
  skip (eventsGrouped 에 포함되지 않음).
- timetable 후보가 0이고 event가 0이면 issue 없음 (compareKeys 에
  포함조차 안 됨).
- timetable 후보가 0인데 event가 있다 → 모두 EXTRA_RAW_EVENT.
- timetable이 있고 event가 0이라면 모두 NO_RAW_EVENT
  (4단계 timetable-only 보강이 없으면 이 케이스를 잡을 수 없다).
- 좌표계는 항상 U/D 로 정규화된 directionUD 를 사용한다. raw
  direction (상행/하행/내선/외선) 은 union/비교에 직접 쓰지 않는다.

2-5. 배치 통합

운영 기본값 cron: "0 30 4 * * *" (KST 04:30 — service_day_boundary
04:00 직후, raw 적재가 안정된 시각).

@Scheduled(cron = "${batch.arrival-event.cron:0 30 4 * * *}",
           zone = "Asia/Seoul")
public void run() {
LocalDate serviceDate = yesterday();
int eventCount = derivationService.deriveForDate(serviceDate);
if (phaseBEnabled) {
matchingService.matchForDate(serviceDate);
}
}

운영 기준 cron(`0 30 4 * * *`)은 application.yaml 의
`batch.arrival-event.cron` 으로 외부화한다. 테스트나 수동 실행이 필요한
경우 환경별 yaml(application-local.yaml 등)에서 override한다.
이 문서는 운영 기본값을 기준으로 작성되었으며, 실제 코드의 cron 값과
달라질 경우 이 항목과 코드 중 운영 기본값 쪽을 진실 소스로 본다.

- 별도 트랜잭션. Phase A 실패해도 Phase B 시도하지 않는다
  (try-catch로 격리).
- 설정 플래그: batch.match-issue.enabled: true.

  ---
3. 테스트 시나리오

B-5-1. TimetableConverterTest

- 방향: 상행/내선→U, 하행/외선→D, 그 외→null + 예외
- dayType:
    · 평일(예: 2026-05-04 월) → "01"
    · 토요일(예: 2026-05-02) → "02"
    · 일요일(예: 2026-05-03) → "03"
    · 공휴일(예: 2026-05-05 어린이날) → "03"
  공휴일 케이스는 stub이 아니라 테스트 전용 고정 fixture
  (FakeHolidayCalendar 또는 InMemoryHolidayCalendar)로 주입한다.
  운영 구현체와 동일한 인터페이스를 만족시키되 테스트에서만
  하드코딩된 공휴일 set을 사용한다.
- arrTime: "053000" → 당일 05:30, "010000" → 다음날 01:00, "0" +
  depTime → -30s
- 자정 경계 케이스 ("235930" + arrTime="0" 인 종착역)
- service-day order key:
    · serviceDate 04:30, 05:00 → 04:30 < 05:00
    · serviceDate 23:50, 00:10(다음날) → 23:50 < 00:10 (후자가 더 큼)
    · arrTime="0" + depTime="000010" → -30s 한 23:59:40 이
      23:50 보다 더 큰 orderKey 를 가져야 함

B-5-2. TimetableMatchingServiceTest (Mockito 단위 테스트)

┌─────┬─────────────────────────────┬──────────────────────────┐
│ TC  │            설명             │        기대 결과         │
├─────┼─────────────────────────────┼──────────────────────────┤
│ TC1 │ 1:1 정확 매칭               │ issue 0건                │
├─────┼─────────────────────────────┼──────────────────────────┤
│ TC2 │ timetable 5개 / event 3개   │ NO_RAW_EVENT 2건         │
├─────┼─────────────────────────────┼──────────────────────────┤
│ TC3 │ timetable 3개 / event 5개   │ EXTRA_RAW_EVENT 2건      │
├─────┼─────────────────────────────┼──────────────────────────┤
│ TC4 │ station.tagoStationId ==    │ MAPPING_MISSING N건      │
│     │ null                        │ (event 수만큼)           │
├─────┼─────────────────────────────┼──────────────────────────┤
│ TC5 │ timetable 0 / event N       │ EXTRA_RAW_EVENT N건      │
├─────┼─────────────────────────────┼──────────────────────────┤
│ TC6 │ timetable N / event 0       │ NO_RAW_EVENT N건         │
│     │ (timetable-only key 보강    │ (event 그룹에 키가 없는  │
│     │  경로 검증)                 │  상태에서도 잡혀야 함)   │
├─────┼─────────────────────────────┼──────────────────────────┤
│ TC7 │ 두 방향 (U/D) 분리 검증     │ 각각 독립 매칭           │
├─────┼─────────────────────────────┼──────────────────────────┤
│ TC8 │ arrTime="0" timetable이     │ depTime-30s로 정렬 후    │
│     │ 정렬에 포함                 │ 매칭                     │
├─────┼─────────────────────────────┼──────────────────────────┤
│ TC9 │ delete-and-recompute        │ 같은 날짜 두 번 실행 시  │
│     │ idempotent                  │ 동일 결과                │
└─────┴─────────────────────────────┴──────────────────────────┘

  ---
4. 구현 순서 (의존성 기반)

1. TimetableConverter + 단위 테스트          [B-1]
2. SubwayArrivalEventMatchIssue 엔티티       [B-2 일부]
3. Flyway 마이그레이션 스크립트              [B-2 일부]
4. Repository + SubwayDataService 확장       [B-2 나머지]
5. MatchGroup DTO + TimetableMatchingService [B-3]
6. TimetableMatchingService 테스트            [B-5]
7. SubwayArrivalEventBatchScheduler 통합     [B-4]

각 단계는 컴파일·테스트 통과 후 다음 단계로 넘어갑니다.

  ---
5. 의도적으로 미루는 항목 (out of scope)

- subway_training_dataset 생성 — Phase B 검증 후 별도 단계
- MATCHED row 저장 — 진단 테이블에 기록하지 않음
- 외부 공휴일 자동 연동(공공 API 폴링, 라이브러리 자동 갱신 등)
  → Phase B scope에는 수동 DB/설정 기반 공휴일 데이터 적재까지 포함.
    인터페이스만 두고 빈 구현으로 두면 dayType 매핑이 깨지므로
    초기 적재(예: 향후 1~2년 공휴일 시드 SQL 또는 yaml 설정)는 필수.
- train_no 기반 매칭, 시간차 threshold 매칭
- D-1 외 sliding 재처리 윈도우
- 운영 메트릭 노출 (Actuator/Prometheus)

  ---
6. 검증 쿼리 (구현 후 실행할 항목)

문서 Validation Queries 섹션의:
- 매핑 커버리지 (subway_station.tago_station_id)
- 진단 이슈 분포 (select issue_type, count(*) ...)
- 진단 이슈 위치 (역/방향별)

  ---