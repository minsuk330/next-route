# PR3 — 식별자 보존 + Resolver + Dedupe Orchestration + 응답 DTO

> 환승 지점 도착예측 통합 — 경로검색에 버스 도착예측을 실제로 붙이는 핵심 PR.
> 선행: [PR2](./PR2-java-ml-client.md) (머지 후 갱신 main에서 분기, 스택 아님) · 후속: [PR4](./PR4-ops-hardening.md)

## Context

`GET /api/route/search`의 각 **버스 승차 지점**(trafficType=2, 최초 탑승 포함)마다 그 버스가 사용자 도착 시점
기준 언제 오는지를 응답에 싣는다. 실시간 버스 도착 API를 우선 쓰고, 이미 지나간 경우에만 ML 모델로 다음 버스를
예측한다. 모델은 top-30 노선만 학습됐다.

## 코드 검증으로 확정된 사실
- **trafficType**: `1=지하철, 2=버스, 3=도보, 4=도시간열차` (`WalkCoordResolver.java:20-21`, SUBWAY=1/BUS=2).
- **sectionTime 단위 = 분**. 초 환산 `*60`. 비교는 **절대 시각** 기준.
- ODSAY DTO는 식별자를 이미 받지만 응답 DTO 변환에서 버림: `OdSaySubPath`(`startID/startLocalStationID/
  startArsID/endID/endLocalStationID/endArsID`), `OdSayLane`(`busID/busLocalBlID`).
- `BusStop`(table `bus_stop`): PK `stopId`(=서울 stId), `arsId` 별도. repo `findByStopId`/`findByArsId`.
- `BusRouteStop`(table `bus_route_stop`): `routeId,stopId,seq,sectionId`. repo `findByRouteIdOrderBySeq`,
  `existsByRouteIdAndStopIdAndSeq` — **`findByRouteIdAndStopId` 없음 → 추가**.
- `BusRoute`: `routeId,routeName`. repo `findByRouteNameIn`. `BusDataService.findRoutesByNames` 사용.
- `getArrInfoByStop`는 **stId** 요구(ARS 아님), `getBusPosByRtid`는 `busRouteId`.
- 기존 `SeoulBusApiAdapter`는 3회 재시도(2s/4s) + 공용 RestTemplate read timeout 30s → 검색 경로 직접 사용 위험.
- top-30 노선 = 수집 대상 = `application.yaml:97-127` `collector.bus-arrival.target-route-names`
  (`BusCollectorProperties.targetRouteNames`).

## 승차 지점별 흐름 (절대 시각)
- **시간 규칙**: 내부 계산 전부 `Instant`, 응답은 ISO-8601 offset. **공통 시각 파서**로 `mkTm`/`dataTm`을
  **Asia/Seoul로 해석**해 Instant 변환 — **`mkTm`·`dataTm` 둘 다 숫자 14/17자리 그리고 `yyyy-MM-dd HH:mm:ss[.S]`
  모두 지원**. (기존 `SeoulBusApiAdapterTest`가 `2026-06-06 13:00:01.0` 형식을 정상 응답으로 검증 — 숫자 전용
  파서면 그 차량을 전부 stale/invalid로 버려 모델 후보 손실 + REALTIME 분기 실패.) stale 범위
  `snapshotAt ∈ [calculatedAt-120s, calculatedAt+60s]`. (`mkTm`은 `BusArrivalItem`에만 있으므로
  `BusArrivalInfo`/SearchTime 경로에 매핑 추가 필요.) 포맷별 후보 생성 테스트 추가.
- `calculatedAt = searchStartedAt = now`
- **순차 타임라인 `estimatedUserArrivalAt`** (이동시간만 합산하면 앞선 버스·지하철 **대기시간**이 빠져 2번째 이후
  승차에서 잡을 수 없는 버스를 AVAILABLE로 오판):
  - 최초 버스 승차: `searchStartedAt + Σ(앞 subPath 이동시간)` 허용(기존 방식).
  - 후속 승차: 직전 구간들의 **예측 대기시간을 순차 타임라인에 누적** 반영.
  - 이동시간: **도보는 `subPath.walkTotalTimeSeconds`(3a, TMAP totalTime) 우선, null이면 ODSAY `sectionTime[분]*60`**.
    차량은 `sectionTime[분]*60`.
  - 이전 지하철 대기시간을 산출 못 하면 status `ARRIVAL_TIME_APPROXIMATE`(또는 신뢰도 표시)로 명시.
- **복수 lane 시간축 모호성**: 한 버스 subPath에 대안 노선(lane)이 여럿이면 lane마다 대기시간이 달라 후속 승차의
  `estimatedUserArrivalAt`이 **이전 lane 선택에 종속**됨. 모든 lane 결과를 path당 단일 타임라인에 합치면 불가능한
  환승을 AVAILABLE로 오판. → **대표 lane 규칙 확정**: 이전 버스 구간은 **가장 이른(`min`) 예측 도착 lane**을
  타임라인 기준으로 사용(낙관적 catchability). 그리고 후속 예측이 **어느 선택에 조건부인지 DTO에 표현**
  (3e의 `basisLaneIndex`·`conditional` 필드).
- 실시간: `getArrInfoByStop(stId)`의 해당 route 항목 → `realtimeArrivalAt = mkTm + predictTimeN`
  - `realtimeArrivalAt >= userArrivalAt` 인 가장 이른 predictTime(1→2) → **REALTIME**
  - 둘 다 `< userArrivalAt`(이미 지나감) → 모델 분기
- 모델 (route ∈ top-30 일 때만): `getBusPosByRtid` → 접근 차량(**`sectOrd <= target_seq`** —
  `build_dataset.py`가 `target_seq >= current_section_order`로 equality(`remaining_stop_count=0`)까지 학습하므로
  가장 임박한 차량을 버리면 안 됨, `snapshotAt ∈ [calculatedAt-120s, calculatedAt+60s]`) → feature → ML batch →
  `modelArrivalAt = snapshotAt + predSeconds` → `>= userArrivalAt` 중 가장 이른 차량 → **MODEL**. 없으면 `NO_VEHICLE`.

## 작업

### 3a. ODSAY 식별자 + 도보 소요시간 보존
- `LaneResult`에 `busID`, `busLocalBlID` 추가. `SubPathResult`에 `startArsID,startLocalStationID,
  endArsID,endLocalStationID,endID` 추가(`startID` 기존).
- **도보 totalTime 보존**: 현재 `SubPathResult`엔 TMAP `totalTime` 필드가 없고 `WalkSegmentEnricher`는 ODSAY
  `sectionTime`만 유지 → enricher가 totalTime 우선 규칙을 못 씀. **`SubPathResult`에 `walkTotalTimeSeconds`(초)
  추가**하고 **`WalkSegmentEnricher`가 `WalkSegment`/TMAP `totalTime`을 여기에 보존**(없으면 null → 흐름에서
  ODSAY `sectionTime[분]*60` fallback). `sectionTime` 자체는 ODSAY 그대로 유지(혼동 방지).
- 매핑 `OdSayApiAdapter.toLaneResult`/`toSubPathResult`에서 채움(신규 ID는 채우고 `walkTotalTimeSeconds`는 null 초기화).
- **caller 동기화 필수**(record 인자 변경): `RoutePolylineEnricher.java:149`, `WalkSegmentEnricher.java:201`,
  `RouteSearchService.stripWalkSubPath(:105)` — 컴파일러가 전부 잡음.

### 3b. resolver — **2단계** (정적=후보집합만, 실시간 조회 후 최종 확정)
> 다중 routeId·중복 seq 해소는 실시간 노선/`BusArrivalInfo.seq`가 필요하므로, **정적 단계에서 routeId/targetSeq를
> 먼저 확정하면 유효한 모호 케이스가 stop API 조회 전에 `UNSUPPORTED_ROUTE`/`STOP_MAPPING_FAILED`로 조기 종료됨.**
> → 정적 단계는 stopId와 **후보 집합**만 만들고, stop API dedupe 조회 뒤 최종 확정.

**정적 단계(조회 전)**:
- stopId: `subPath.startArsID` → `BusStopRepository.findByArsId` → `stopId`. 실패 시 `startLocalStationID`가
  stopId일 때 fallback. (stopId는 여기서 확정 — stop API 호출 키.)
- routeId **후보집합**: **`lane.busLocalBlID` 우선** — `findByRouteId`로 검증되면 그것으로 **단건 확정**(이름 검색
  합치지 않음). `busLocalBlID`가 없거나 DB 검증 실패일 때만 `lane.busNo` → `findRoutesByNames` fallback(다건 허용).
  (검증된 정확 ID를 이름 후보와 union하면 동명 다른 노선이 끼어 stop API 실패/양노선 경유 시 정확 ID도 모호 종료됨.)
- targetSeq **후보집합**: **`BusRouteStopRepository.findByRouteIdAndStopId`(신규, `List` 반환)** — 후보별 `seq` 목록.

**확정 단계(stop API 조회 후)**:
- routeId/targetSeq를 **실시간 노선·`BusArrivalInfo.seq`·ODSAY 방향(`way`)·정류장 포함 여부**로 후보 중 **유일 결정**.
- 유일하지 않으면 임의 선택 없이 `UNSUPPORTED_ROUTE`(routeId)/`STOP_MAPPING_FAILED`(seq).
- **stop API 실패(응답 없음) 시**: 확정 근거가 없으므로 **정적 후보가 routeId 1개·seq 1개인 경우에만** 그 값으로
  모델 fallback 허용. 후보가 다건이면 임의 결합 금지 → `STOP_MAPPING_FAILED`/`ERROR`로 종료.
- stopId 매핑 자체 실패는 정적 단계에서 즉시 `STOP_MAPPING_FAILED`(예외 아님).

### 3c. 전용 검색용 client (no-retry, 단축 timeout)
- 신규 `RestClient` bean `@Qualifier("busRealtimeRestClient")`(~1.5s timeout) + 신규 port/adapter
  `SearchTimeBusQueryPort`/`SearchTimeBusAdapter` — `getArrInfoByStop`/`getBusPosByRtid` 동일 엔드포인트를
  **재시도 없이** 호출. 기존 collector `SeoulBusApiAdapter`(3회 재시도/30s)는 건드리지 않음.

### 3d. TransferArrivalEnricher (**dependency-aware wave** fan-out + dedupe)
신규 `application/route/service/TransferArrivalEnricher`. `RouteSearchService.search`에서 도보 보강 뒤,
`saveLog` 전 호출.
- **toggle 분리**(기능 toggle ≠ ML toggle): `route.transfer-arrival.enabled`와 `ml.predictor.enabled`를
  **별도 설정**으로. ML이 꺼져도 REALTIME은 제공 가능해야 함.
  | 설정 | 동작 |
  |---|---|
  | `route.transfer-arrival.enabled=false`(**기본값**) | enricher가 버스 lane 수집만 하고 `source=NONE,status=DISABLED` 부착, 외부 호출 전부 생략 |
  | `transfer-arrival=true` & `ml.predictor.enabled=false` | REALTIME 분기는 동작, 모델 분기만 생략 → 미해결 항목 `status=MODEL_UNAVAILABLE` |
  | 둘 다 true | 전체 동작 |
- **왜 wave인가**: 한 path 안에서 **후속 버스 승차의 `estimatedUserArrivalAt`은 직전 버스 승차의 예측 대기시간에
  종속**된다(버스→버스). 모든 context 시각을 먼저 다 계산하고 단일 패스로 stop 조회+ML 1콜 하면, 1번째 결과가
  나오기 전에 2번째 catchability를 못 정해 대기시간 누락/잘못된 AVAILABLE 발생. → **path 내 승차 순서(=wave
  index)로 의존성 분리**. 같은 wave 내 항목은 서로 독립 → wave 단위로 dedupe·batch.
- 동작 (wave 루프):
  1. 전 path/subPath/lane 순회 → **모든 버스 승차(trafficType==2)** context 수집. 각 context에 **path 내 버스 승차
     순번 = waveIndex** 부여(path별 1번째=wave0, 2번째=wave1...). path 간에는 독립.
  2. **정적 resolve**(3b 정적 단계): stopId 확정 + routeId/targetSeq **후보집합**(wave 무관, 1회).
  3. **`wave = 0,1,2...` 순서로 반복**:
     a. 이 wave의 모든 context에 대해 `estimatedUserArrivalAt` 계산 — wave0은 `now + 앞 이동시간`,
        wave≥1은 **직전 wave에서 확정된 그 path의 도착/대기 결과**를 타임라인에 반영(대표 lane = min 도착, 3e `basisLaneIndex`).
     b. **stopId별 `getArrInfoByStop` dedupe** 호출(이 wave의 미조회 stopId만).
     c. **확정 resolve**(3b 확정 단계): 실시간 노선/seq로 routeId·targetSeq 유일 결정 → REALTIME 판정.
     d. REALTIME 미해결 routeId만 **`getBusPosByRtid` dedupe** 호출(`isRunYn=="1"` & `sectOrd<=targetSeq` & stale 내).
     e. 이 wave 차량 feature를 **wave별 ML batch 1콜**(`MlArrivalPredictorPort.predict`) → MODEL 판정.
     f. 결과 확정 → 다음 wave의 타임라인 입력.
  4. 결과를 원래 path/subPath/lane에 재결합.
- **wave 실패 전파**: wave≥1은 직전 wave 확정 `predictedArrivalAt`이 필요한데 `NO_VEHICLE`/`MODEL_UNAVAILABLE`/
  `STOP_MAPPING_FAILED`/`ERROR`엔 도착시각이 없음. → **그 path에서 이전 승차에 boardable lane이 하나도 없으면
  후속 wave의 외부 호출을 중단**하고 후속 승차에 `status=UPSTREAM_UNAVAILABLE` 부착(이동시간만으로 계속 계산해
  잡을 수 없는 버스를 AVAILABLE로 내지 않음). path 간에는 영향 없음. 상태별 회귀 테스트 추가.
  > 대부분 경로는 버스 승차 1~2회라 wave 수는 작음. stop/position 조회는 wave 간에도 캐시/dedupe해 중복 최소화.
- **부분 실패 격리**(전체 try/catch가 한 호출 실패로 모든 경로를 ERROR로 만들면 안 됨):
  | 실패 지점 | 영향 범위 |
  |---|---|
  | 정류장 API(`getArrInfoByStop`) 실패 | 해당 stopId만. **정적 후보가 routeId 1개·seq 1개일 때만** 그 값으로 모델 fallback 진행; 모호(다건)면 `STOP_MAPPING_FAILED`(임의 결합 금지) |
  | 위치 API(`getBusPosByRtid`) 실패 | 해당 routeId만 → `MODEL_UNAVAILABLE` |
  | ML 실패 | REALTIME 결과는 유지, unresolved 항목만 `MODEL_UNAVAILABLE` |
  경로검색 자체는 어떤 경우에도 안 깨짐.

### 3e. 응답 DTO
- 신규 record `TransferArrival`: `routeId, laneIndex, source(REALTIME|MODEL|NONE),
  status(AVAILABLE|ARRIVAL_TIME_APPROXIMATE|DISABLED|UNSUPPORTED_ROUTE|STOP_MAPPING_FAILED|NO_VEHICLE|MODEL_UNAVAILABLE|UPSTREAM_UNAVAILABLE|ERROR),
  calculatedAt, userArrivalAt, predictedArrivalAt, waitSeconds, vehicleId, modelVersion,
  basisLaneIndex, conditional`.
  - `basisLaneIndex`: 이 결과의 `userArrivalAt`이 어느 이전 lane 선택을 기준으로 했는지(대표 lane). 후속 환승만 non-null.
  - `conditional`(bool): `userArrivalAt`이 이전 구간 lane 선택에 종속(=대안 lane이 여럿이라 실제는 달라질 수 있음)이면 true.
- `SubPathResult`에 **`List<TransferArrival> transferArrivals`** 추가(lane 다중 대안 대응). record 끝에 append,
  3a의 모든 caller에서 전달.
- 로그는 작은 필드라 그대로 포함(stripWalkDetailsForLog는 도보만 제거).

### 3f. feature 빌더
`BusPositionInfo` + 시간 + target_seq → `MlFeatureVector`. 이름/파생은 `ml/build_dataset.py`·`train.py`와
**정확히 동일**: `current_section_order, section_progress(=section_distance/full_section_distance),
current_section_distance, current_full_section_distance, next_stop_time, last_stop_time, congestion,
gps_x, gps_y, target_seq, remaining_stop_count(=target_seq-current_section_order), hour_of_day,
minute_of_day, day_of_week(1=월~7=일), is_weekend, route_id`.
- **시간 feature는 검색시각이 아니라 차량 `dataTm`(=snapshotAt) KST 기준으로 생성** — 학습이 snapshot 시각으로
  만들었으므로 검색시각 사용 시 train/serve skew 발생.
- **nullable feature 계약**(학습에서 GPS·congestion·section_progress 등 null 가능):
  - 누락 key → 422(요청 거부), **존재하는 key의 null은 허용**하고 serving이 Pandas NaN으로 변환.
  - `section_progress`는 분모(`full_section_distance`)가 null/0이면 null.
- **차량 후보 필터**(feature 만들기 전): `isRunYn=="1"`인 차량만, `currentSectionOrder`·`targetSeq`·
  `snapshotAt`(dataTm) 누락 차량은 제외.

## Verification
- **시각 파서**: `mkTm`/`dataTm` 숫자 14/17자리 **및** `yyyy-MM-dd HH:mm:ss[.S]` 각 포맷에서 후보 생성/REALTIME 분기 테스트.
- **equality 차량**: `sectOrd == targetSeq`(remaining_stop_count=0) 차량의 REALTIME/MODEL 선택 테스트(제외되면 안 됨).
- **resolver 2단계**: 정적=후보집합만, stop API 조회 후 유일 결정. 모호 케이스가 조회 전 조기 종료 안 됨 확인.
  ars→stopId, routeId(busLocalBlID/이름 다건), targetSeq(중복 seq→실시간 seq로 유일) 경계.
- **복수 lane**: 대표 lane(min 도착) 기준 후속 타임라인, `basisLaneIndex`/`conditional` 세팅 검증.
- **wave 다중 승차**: 버스→버스 경로에서 wave0 대기 결과가 wave1 `estimatedUserArrivalAt`에 반영되는지(회귀).
  단일 패스로 합치면 잡을 수 없는 버스를 AVAILABLE로 내는 케이스 방지 검증.
- **도보 시간**: `walkTotalTimeSeconds` 있으면 그 값, null이면 ODSAY `sectionTime*60` fallback 타임라인.
- **stop API 실패 fallback**: 후보 1개→모델 fallback 진행 / 다건→`STOP_MAPPING_FAILED`, 각각 테스트.
- **wave 실패 전파**: wave0이 NO_VEHICLE/STOP_MAPPING_FAILED 등 boardable 없음 → 같은 path wave1은
  `UPSTREAM_UNAVAILABLE`(외부 호출 안 함), 다른 path는 정상 진행.
- **routeId 우선순위**: 검증된 `busLocalBlID`는 단건 확정(동명 다른 노선 안 섞임), 없을 때만 `busNo` fallback.
- **Enricher 단위**: 포트 모킹 분기 — REALTIME / MODEL / NONE(UNSUPPORTED_ROUTE) / STOP_MAPPING_FAILED /
  NO_VEHICLE / ERROR-fallback. dedupe(같은 stopId 1콜, 같은 routeId 1콜)·batch 1콜·절대시각 경계 검증.
- **Service**: `RouteSearchServiceTest`에 enricher 호출 순서(InOrder).
- **e2e**: serving 기동(`enabled=true`) + 앱 기동 → top-30 버스 승차 포함 좌표로 `GET /api/route/search`
  → 버스 subPath의 `transferArrivals[].source/status/predictedArrivalAt/waitSeconds` 확인.

## Rollout 게이트
- **PR3만 배포 시 `route.transfer-arrival.enabled=false`를 명시적으로 강제**. `ML_PREDICTOR_ENABLED=false`만으로는
  REALTIME stop API fan-out이 계속 동작해(여러 ODSAY 경로 외부 API 순차 호출) PR4 안전장치 없이 검색 지연·사용량
  위험 경로가 열림. 기능 toggle(`transfer-arrival`)을 꺼야 fan-out 자체가 차단됨.
- 대안: `transfer-arrival`을 켜야 한다면 **realtime fan-out의 deadline·호출 상한을 PR3에 포함**(PR4 캐시/병렬제한 전까지 최소 방어).
- 운영 활성화 조건은 [PR4](./PR4-ops-hardening.md) 참고.
