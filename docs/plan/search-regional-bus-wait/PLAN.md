# 검색 결과의 경기·TAGO 버스 대기 (L, slug `search-regional-bus-wait`)

> **2026-11-09 오후 결정 변경(사용자) — 이 절이 아래 본문보다 우선한다**
> - **시간표 대기(SCHEDULE)는 하지 않는다**: 신뢰도가 낮아 사용자에게 혼돈. PR-1b 는 배포 뒤 운영에서 끄고(env) PR-2 에서 코드·enum 값을 지웠다.
>   그때 저장된 스냅샷의 `"SCHEDULE"` 은 PR-0 으로 NONE 으로 읽힌다
> - **② 의 뜻**: 앞 버스 결과가 없어도 뒤 버스 실시간은 조회해 내려주되 환승 대기는 계산하지 않는다 → 새 status `ARRIVAL_ONLY`
>   (`predictedArrivalAt` = 지금부터 가장 이른 도착, `waitSeconds`·`userArrivalAt` = null, 다음 버스 계산에 안 씀). 외부 호출 증가 허용.
>   플래그 `route.transfer-arrival.arrival-only-enabled`
> - **PR-2 경기·TAGO 실시간은 보강 wave 안에서**: 서울과 같이 앞 버스가 실시간이면 뒤 버스 환승 대기까지 이어서 계산(`SearchRegionalArrivalService`,
>   검색 전용 몫 경기 2,000·TAGO 100,000(공급자 500,000/일), 공급자별 자리 2, 기한 800ms, 검색당 6회). 플래그 `route.transfer-arrival.regional-enabled`
> - PR-1a(계획 시각 필드)는 그대로 유지 — 앱 ETA 환승 대기 중복 제거용(#569)

## 결론
경로 검색 응답의 버스 대기(`subPaths[].transferArrivals[].waitSeconds`)가 서울 버스에서만 채워진다. 다섯 PR 로 고친다.
- **PR-0 enum 관용 읽기**: 모르는 enum 값을 기본값으로 읽게 한다. api·worker 에 먼저 배포한다(새 값 `SCHEDULE` 이 저장된 스냅샷·여정을 옛 코드가 못 읽는 것 방지)
- **PR-1a 계획 시각 필드**: 버스 구간에 OTP 계획 탑승·하차·계획 대기를 싣는다(enum 변화 없음). 앱 ETA 의 환승 대기 중복을 없앨 재료
- **PR-1b ③ 시간표 대기**: 실시간·모델을 "묻지 못한" 버스는 OTP 계획으로 대기를 채운다(`source = SCHEDULE`). 외부 호출 없음.
  **② 연쇄(앞 버스 결과 없을 때 뒤 버스 실시간 조회)는 하지 않는다**(사용자 결정 2026-11-09: 외부 호출 증가 리스크) — 시간표 값은 다음 버스 계산에 넘기지 않는다
- **PR-2 ① 경기·TAGO 실시간**: 검색에서도 경기·TAGO 도착을 부른다. 검색 전용 몫(캐시 miss 때만 셈), 검색 전용 동시성, wave 안 병렬, 기한 공유
- **PR-3 앱(별도 저장소, 이슈)**: SCHEDULE 표시, ETA 중복 제거

예(운영 재현 11-09, VPS api 직접 호출): 수원역→광교 720-2(경기)는 지금 `STOP_MAPPING_FAILED`·대기 없음, 92-1 → 7002 의 7002 는 `UPSTREAM_UNAVAILABLE`.
PR-1b 뒤 720-2 는 `AVAILABLE/SCHEDULE`(계획 탑승 − 도착), 7002 는 `AVAILABLE/SCHEDULE`(OTP 계획 환승 대기, conditional). PR-2 뒤 720-2 는 `AVAILABLE/REALTIME`.

## 사실 (조사·리뷰 2026-11-09)
- 응답 조립: `LegacyRouteResponseMapper:193-226` 가 경기 구간에 GBIS `stationId`/`routeId`, TAGO 구간에 `nodeId`/`routeId` 를 `startLocalStationID`/`lanes[0].busLocalBlID` 로.
  OTP 버스 구간 lane 은 하나(`:226`). `toPath` = `totalTime = move + waitingTime + walk`. `toSubPath(leg)` 는 구간 하나만 보는 public 메서드(단독 추적이 재사용)
- 보강 `TransferArrivalEnricher.processWave`(`:311-`): 3a wave 0 = `searchStartedAt + 앞 구간 travelSeconds`(분 반올림 sectionTime, OTP 도보는 walkTotalTimeSeconds null),
  wave≥1 = 직전 예측 탑승 + 직전 `sectionTime` + 사이 구간, 직전이 boardable 아니면 `UPSTREAM_UNAVAILABLE`(`:342-347`, `:500-508`). 3b 서울 도착 조회는 wave 안 순차(`:390-407`).
  기한 0 이면 wave 전체 `fillError`(`:177-180`, `:265-277`). `withoutEnded`/`isEnded`(`:189-205`) 가 운행종료 구간을 숨기고 전부 숨으면 422 `ROUTE_ALL_ENDED`.
  `updatePrevWave`(`:685-`): conditional = boardable lane 이 둘 이상(OTP 는 늘 false), 3단 이상에서 앞 conditional 을 잇지 않음
- 정적 해석 `TransferStopResolver`: 서울 `bus_stop` 표만, 다른 지역 id 면 route 빈값 → 경기·TAGO `STOP_MAPPING_FAILED`/`UNSUPPORTED_ROUTE`. 도착 조회 포트 구현 = 서울 API 뿐
- `TransferArrival`: `Source {REALTIME, MODEL, NONE}`, Status 11개. `SubPathResult` 에 계획 시각 없음
- **영속**: 검색 결과 전체가 Redis 스냅샷(`RedisRouteSearchSnapshotAdapter`, TTL 600s)과 여정 `routeSnapshot`(DB, `JourneyStartService:64`)에 들어간다.
  `PathResult` 로 읽는 곳 5개(`JourneyNavigationGeometryService:609`, `JourneyPlannedBusStopReader:55`, `JourneySubwayStationChainReader:64`, `JourneyWaypointResolver:50`,
  `RerouteAcceptTxService:620`). 모르는 enum 관용 설정 없음 → 옛 코드가 `SCHEDULE` 을 만나면 예외 → 다섯 곳이 조용히 저하(직선 형상, 수락 STALE, 빈 정류장)
- `SubPathResult` 생성 지점(main 7): Legacy mapper, `TransferArrivalEnricher.withTransferArrivals:769`, `WalkCoordResolver:93`, `WalkSegmentEnricher`, `JourneyRevisionComposer:239`,
  `RouteSearchService:546`, `OdSayApiAdapter:316`. 시험 11개 파일 약 20곳
- 지역 실시간: `RegionalArrivalLookup`(여정용)은 몫 버킷 이름 하드코딩(`:144-145`), 몫을 캐시 확인 전에 차감, 어댑터엔 `chargeQuota=false`, 메모리 캐시가 `now + seconds` 로
  재계산(최대 15초 밀림). `GyeonggiBusArrivalAdapter` 는 staOrder 스스로 해석, 공개 몫 `gg-arrival`(5,000)은 miss 때만 셈(`:153`), 서버 사이 single-flight(JOIN_WAIT 5s, 잠금 9s).
  `TagoBusArrivalAdapter` 는 **일일 공개 버킷 없음**(동시성만, 서버 합산 8, lease 15s, 연결 3s+읽기 5s). TAGO 일일 버킷은 재탐색 5,000 뿐. 지역 어댑터에 기한 인자 없음
- 앱(nextroute-mobile): `route-eta.ts:30-36` 은 `source ∈ {REALTIME, MODEL}` 대기만 씀 → 새 source 무시(하위 호환, 런타임 스키마 검증 없음). ETA = `totalTime` + 버스별 대기(COMPLETE 일 때만)
- **앱 ETA 환승 대기 중복(실측 확인)**: 강동01→341(서울 2회, 둘 다 REALTIME) totalTime 55 = 구간 51 + OTP 계획 대기 4 → 앱 66.7분, 실제 62.7분. OTP2 는 첫 탑승 전 대기를
  시간 이동으로 없애므로 `waitingTime` = 환승 대기 → **첫 대중교통 구간이 아닌 버스**(지하철 뒤 버스 포함)에서 지금도 중복. SCHEDULE 구간이 끼면 앱은 PARTIAL 이라 더하지 않는다
- 서버 알림 추정기 `RouteSearchTravelTimeEstimator`: 경기·TAGO lane 은 해석 실패로 먼저 건너뜀(`:153,166`) → SCHEDULE 을 안 읽는다. 서울 lane 은 status 만 본다(`:181-183`) →
  LIMITED/ERROR 가 SCHEDULE(AVAILABLE)로 바뀌면 `fallback=false` 로 신뢰도가 LOW → HIGH 로 오른다(막아야 함)
- 서울만 타는 경로도 첫 버스 `NO_VEHICLE/MODEL` 이면 뒤 버스 전부 `UPSTREAM_UNAVAILABLE`(실측)

## 설계

### PR-0 모르는 enum 관용 읽기
- 전역 Jackson `DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE` 켜고 `TransferArrival.Source` 에 `@JsonEnumDefaultValue NONE`, `Status` 에 `@JsonEnumDefaultValue ERROR`
- 다른 enum 은 기본값이 없으면 지금처럼 예외(기능 켜기만으로는 동작 불변). 시험: 모르는 값 `"SCHEDULE"` 이 NONE 으로 읽힘
- 배포: api + worker + worker2 모두. 이 빌드가 PR-1b 의 되돌리기 대상

### PR-1a 계획 시각 필드 (enum 변화 없음)
- `SubPathResult` 에 nullable `plannedBoardAt`, `plannedAlightAt`(Instant), `plannedWaitSeconds`(Integer) — 버스 구간만(지하철은 범위 밖, 필요 시 후속)
- 채우는 곳: `toPath` 루프에서 계산해 `toSubPath` 에 넘긴다(직전 leg 를 알아야 함). `plannedWaitSeconds = boardAt − 직전 대중교통 leg alightAt 뒤 도보 끝` = OTP 환승 대기,
  첫 대중교통 구간은 0. 단독 추적 경로·ODsay·`JourneyRevisionComposer` 절단 구간은 null
- 생성 지점 7곳은 `SubPathResult.withPlanned(...)`/보조 생성자로 바꿔 call site 변경을 줄인다. `withTransferArrivals` 가 새 필드를 복사하는지 시험(매퍼 → WalkCoordResolver → 보강기 사슬)

### PR-1b ③ 시간표 대기 (② 연쇄 없음)
- `TransferArrival.Source` 에 `SCHEDULE`, nullable `fallbackReason`(String, 바꾸기 전 status)
- **대상**: lane 0 이고 status ∈ {`STOP_MAPPING_FAILED, UNSUPPORTED_ROUTE, UPSTREAM_UNAVAILABLE, LIMITED, BLOCKED, ERROR, MODEL_UNAVAILABLE`}("묻지 못함").
  `NO_VEHICLE`(물어봤는데 차 없음)·`DISABLED` 는 바꾸지 않는다
- **대기 두 종류**:
  - 도착 시각을 아는 버스(wave 0, 또는 앞 버스가 실시간/모델로 boardable 이라 `estimatedUserArrivalAt` 이 있는 버스):
    `waitSeconds = plannedBoardAt − userArrivalAt`(지금 출발 기준, REALTIME 과 같은 시계), `predictedArrivalAt = plannedBoardAt`
  - `UPSTREAM_UNAVAILABLE`(앞 버스 결과 없음 → 도착 시각 모름): `waitSeconds = plannedWaitSeconds`(OTP 계획 환승 대기), `predictedArrivalAt = plannedBoardAt`, `conditional = true`
- 가드: `plannedBoardAt` null 이면 안 함(ODsay) / 요청 `departureAt` 이 지금과 5분 넘게 다르면 안 함 / `plannedBoardAt + 60초 < userArrivalAt`(계획 버스 놓침)이면 안 함, 60초 안이면 `wait = max(0, …)`
  (분 반올림·OTP 호출 지연 허용) / 대기 60분 초과면 안 함
- 결과: `AVAILABLE/SCHEDULE`, 앞에 지하철이 있으면(`precedingSubway`) `ARRIVAL_TIME_APPROXIMATE/SCHEDULE`
- **연쇄 금지**: 시간표 대체는 `updatePrevWave` **뒤**(wave 결과를 다음 wave 에 넘긴 뒤)에 하거나 SCHEDULE 을 boardable 에서 뺀다 → 뒤 버스의 외부 호출 수는 지금과 같다(시험으로 고정)
- 위치: 보강 끝 후처리(resultMap 전체, 기한 소진 `fillError` 로 빠진 wave 포함 — 외부 호출 없음)
- 플래그(yaml env 노출): `route.transfer-arrival.schedule-fallback-enabled`(`${TRANSFER_ARRIVAL_SCHEDULE_FALLBACK:true}`). 꺼지면 지금과 바이트 동일
- 지표: `route_search_transfer_wait_total{source, reason}`(SCHEDULE 대체 사유), `…_schedule_skipped_total{why=missed|cap|departure_at|no_plan}`.
  저하 지표(`reportArrivalOutcomes`)는 바꾸기 전 status 로 센다
- **서버 소비처**: `RouteSearchTravelTimeEstimator` 는 `source == SCHEDULE` 이면 fallback(LOW)으로 다룬다(신뢰도 상승 금지). 시험

### PR-2 ① 경기·TAGO 실시간
- 여정용 `RegionalArrivalLookup` 은 쓰지 않는다(버킷 하드코딩, 캐시 전 몫 차감, 15초 밀림). 검색용 `SearchRegionalArrivalService`(application/route) 를 새로 두고 어댑터를 직접 부른다
- 어댑터에 **몫 버킷 인자**를 넘겨 공유 캐시 miss 때만 센다: 경기 `search-gg-arrival`, TAGO `search-tago-arrival`(TAGO 어댑터에 버킷 차감 경로 추가). 캐시 hit 여부를 돌려받아 hit 은 검색당 예산을 쓰지 않는다
- 동시성: 검색 전용 하위 한도(경기·TAGO 각 2, 대기 없이 tryAcquire — 실패하면 LIMITED). TAGO 서버 합산 8 자리를 검색이 독점하지 못하게
- 기한: wave 안 지역 호출을 크기 제한 전용 풀에서 병렬로 띄우고 남은 기한 하나로 `get(timeout)`. 넘으면 포기(호출은 공유 캐시를 채워 다음 검색이 씀). 포기한 호출 수 지표
- 대기 = `fetchedAt + seconds − userArrivalAt`(준비 뒤 첫 도착), 없음 = `NO_VEHICLE`, 몫·자리 없음 = `LIMITED`, 실패 = `ERROR` → PR-1b 시간표가 받는다
- 몫(배포 전 실측으로 확정): 경기 공급자 10,000 = 재탐색 5,000 + 공개 + 검색(공개에서 뗀다). TAGO 는 **공급자 일일 한도부터 확인**(지금 공개 버킷 없음) 후 검색 버킷 크기 결정
- 플래그 `route.transfer-arrival.regional-enabled`(`${TRANSFER_ARRIVAL_REGIONAL:false}`)

### PR-3 앱(nextroute-mobile, 이슈만)
- `source === 'SCHEDULE'` 대기를 "시간표 기준"으로 표시, 큰 대기는 "N분 뒤 출발". 주말·공휴일은 평일 시간표(#557 범위 밖 버스)라 낮은 신뢰 표시
- ETA = `totalTime − Σ plannedWaitSeconds(대기를 쓴 구간) + Σ waitSeconds` 로 환승 대기 중복 제거(실측 4분 과대)

## 변경 파일
- PR-0: Jackson 설정(`common/config` 의 ObjectMapper 설정 또는 `application.yaml` `spring.jackson.deserialization`), `TransferArrival.java`, 시험
- PR-1a: `SubPathResult.java`, `LegacyRouteResponseMapper.java`, 생성 지점 7곳(위 목록), 시험(매퍼, 사슬)
- PR-1b: `TransferArrival.java`, `TransferArrivalEnricher.java`(후처리만, wave 로직 불변), `TransferArrivalProperties.java`, `RouteSearchMetrics.java`, `RouteSearchTravelTimeEstimator.java`, `application.yaml`, 시험
- PR-2: `SearchRegionalArrivalService`(신규), `GyeonggiBusArrivalAdapter`/`TagoBusArrivalAdapter`(버킷 인자·hit 여부), 포트 시그니처, `TransferArrivalEnricher`, `TransferArrivalProperties`, `application.yaml`, 시험
- 공통: `implementation_note.md`

## 순서
- [x] 앱 ETA 중복 실측(노트)
- [ ] PR-0 → 머지 → api·worker·worker2 배포(사용자 확인, 진행 여정 확인)
- [ ] PR-1a → 머지(배포는 PR-1b 와 같이 해도 됨)
- [ ] PR-1b → 머지 → api 배포(worker 는 스냅샷을 읽기만 함 — PR-0 이 들어가 있으면 순서 무관) → 운영 재현(수원역→광교, 청주)
- [ ] PR-2 전: 경기 공개 몫 사용량, TAGO 공급자 일일 한도 확인 → 몫 확정
- [ ] PR-2 → 머지 → 배포(꺼짐) → 켜고 사용량·지연 확인
- [ ] PR-3 앱 이슈
- 되돌리기: PR-1b/PR-2 는 플래그로 끈다. 빌드 되돌리기는 PR-0 이상으로만(그 아래로 내리면 저장된 SCHEDULE 을 못 읽는다)

## 시험
- PR-0: `"source":"SCHEDULE"` 가 든 `PathResult` JSON 이 읽히고 NONE 이 됨, 알려진 값은 그대로
- PR-1a: 버스 2회 경로 `plannedBoardAt/AlightAt`, `plannedWaitSeconds`(첫 버스 0, 환승 버스 = OTP 환승 대기), 지하철 뒤 버스, 단독 추적 `toSubPath` 는 null.
  매퍼 → WalkCoordResolver → 보강기 뒤에도 필드 유지
- PR-1b(`TransferArrivalEnricherTest`):
  - 경기 lane `STOP_MAPPING_FAILED` → `AVAILABLE/SCHEDULE`, wait, fallbackReason / 지하철 뒤면 APPROXIMATE
  - `NO_VEHICLE`·`DISABLED` 는 그대로 / 놓침(60초 넘게) 안 바꿈, 60초 안은 wait 0 / 60분 초과 안 바꿈 / departureAt 지정(5분 넘게 차이) 안 바꿈 / plannedBoardAt null 은 지금과 같음
  - 기한 소진 wave 도 후처리로 SCHEDULE
  - `UPSTREAM_UNAVAILABLE` 뒤 버스 → `AVAILABLE/SCHEDULE`, wait = plannedWaitSeconds, conditional=true
  - **연쇄 없음**: 앞 버스가 SCHEDULE 이 돼도 뒤 버스의 도착·위치·ML 호출 수가 지금과 같다(가짜 포트 호출 횟수 단언)
  - 플래그 꺼짐 = 지금과 같음 / 서울 실시간 경로 결과 바이트 그대로
  - `RouteSearchTravelTimeEstimator`: SCHEDULE lane = fallback(LOW)
- PR-2: 경기·TAGO lane REALTIME, 캐시 hit 은 몫·예산 안 씀, 몫 소진 → LIMITED → SCHEDULE, 하위 동시성 초과 → LIMITED, 기한 초과 → 포기 후 SCHEDULE, wave 안 병렬
- 명령: `./gradlew test --tests '*TransferArrivalEnricher*' --tests '*LegacyRouteResponseMapper*' --tests '*RouteSearchTravelTimeEstimator*'` + 전체 `./gradlew test`

## 검증
- PR-0 배포 뒤: 스냅샷·여정 읽기 정상(기존 여정 형상·정류장)
- PR-1b 배포 뒤: VPS api 로 수원역→광교·청주 → 경기·TAGO 버스 `AVAILABLE/SCHEDULE` + wait, 뒤 버스 `SCHEDULE`(계획 환승 대기). 서울 실시간 status·source 와 검색 p95 가 배포 전과 같음
- PR-2 켠 뒤: 같은 검색 REALTIME, 검색 p95, `search-*-arrival` 사용량, 정류장 화면·여정 쪽 TAGO 자리 고갈 없음

## 경계 리뷰
- 계약: 필드 추가(`plannedBoardAt/AlightAt/WaitSeconds`, `fallbackReason`), enum 값 추가(`SCHEDULE`). 출시된 앱은 새 source 무시. **서버 영속 읽기는 PR-0 으로 보호**
- 상태: 시간표 값은 다음 버스 계산에 넘기지 않는다(연쇄 없음). 뒤 버스 시간표 대기는 conditional
- 실패: 지역 조회 실패·몫·자리·기한 → 시간표. 시간표도 없으면 지금 status. 되돌리기 하한 = PR-0 빌드
- 플래그 기본값: schedule-fallback true(외부 호출 없음), regional false(외부 호출)
- 서버 소비처: 추정기는 SCHEDULE 을 낮은 신뢰로. 여정·재탐색은 스냅샷 transferArrivals 를 의미 있게 쓰지 않음(PR-0 로 읽기만 보장)

## 리스크
- 앱 ETA 환승 대기 중복(지금도 있음, 실측 4분) — PR-3
- 지역 어댑터 기한 없음·TAGO 자리 공유 — 검색 전용 하위 동시성 + 기한 포기
- 시간표 대기는 버스 평일표(주말표 없음) — SCHEDULE 은 낮은 신뢰로만 쓰이게

## 범위 밖
- ② 연쇄(앞 버스 결과 없을 때 뒤 버스 실시간 조회 — 사용자 결정으로 제외), 순위 합성(`OtpBusRealtimeTimingLookup`)의 지역 실시간, 지하철 구간 계획 대기, 버스 주말 시간표, 계획 버스를 놓친 경우의 다음 출발(#407), 앱 구현
