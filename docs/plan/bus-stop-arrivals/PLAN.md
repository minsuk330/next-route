# 근처 버스 도착을 한 번에 (#577) — PLAN v3

티어 **L**(새 API 계약 2개). 이 문서가 구현 기준이다. 사람 검토용 요약은 `SUMMARY.md`. v2 에 대한 Opus 검토(§11)와 사용자 결정을 반영했다.

## 1. 결론

앱이 "근처에서 타기" 버스 탭의 도착을 **한 번의 요청**으로 받게 두 경로를 연다. 원본 공급자는 **정류장마다 한 번**(정류장 단위 API 가 노선을 한 번에 다 준다)만 부른다.

| 경로 | 용도 |
|---|---|
| `GET /api/bus/stops/{stopId}/arrivals` | 정류장 하나의 노선별 도착(이슈 원안) |
| `GET /api/bus/stops/arrivals?stopIds=a,b,c` | 정류장 여러 곳(최대 10)을 한 번에 |

정류장 id 는 앱이 추적 API(전국 근처 정류장, 서울·경기·인천 같은 정류장 합침)에서 받은 것을 그대로 쓴다. 노선(방향)별로 한 줄로 합치는 일은 지금처럼 앱이 한다.

사용자 결정(2026-11-15): 노선 유형 = 원본 코드 + 출처 + 이름, 서울 정류장 단위 몫 5,000 시작, **공개 근처 정류장 확장(V76) 뺌, 좌표 경로 뺌, 표시색 안 넣음**.

## 2. 실측한 사실

### 원본 API (2026-11-15)

| 지역 | 정류장 → 모든 노선 | 한 줄 단위 | 노선 이름, 유형 | 몇 정거장 전 | 차량 |
|---|---|---|---|---|---|
| 서울 | `ws.bus.go.kr/api/rest/stationinfo/getStationByUid?arsId=` | 노선 | `rtNm`, `routeType`("3") | `staOrd − sectOrd1` | `vehId1/2`("0"=없음) |
| 경기 | `apis.data.go.kr/6410000/busarrivalservice/v2/getBusArrivalListv2?stationId=` | 노선 | `routeName`(숫자 또는 문자), `routeTypeCd` | `locationNo1/2` | `vehId1/2` |
| TAGO | `apis.data.go.kr/1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList?cityCode=&nodeId=` | **버스 한 대** | `routeno`, `routetp`("일반버스") | `arrprevstationcnt` | 없음 |

여러 정류장을 한 번에 주는 원본 API 는 세 지역 모두 없다. 근처 정류장 8곳 도착을 한 번 새로 받는 서울 원본 호출: 지금(정류장 × 노선) 32~63회, 노선 단위 전체 21~34회, **정류장 단위 8회**(강남·마포·노원·잠실, 운영 카탈로그로 셈).

### 같은 9자리 id 를 서울과 경기가 같이 쓴다

`121000009` 에는 서울 노선 20개와 경기 노선 35개가 선다(`TrackStopSiblings` 실측 기록, 21만 정류장). `124000360` 은 `bus_stop` 에 있고 서울 341·370 이 서는데, 경기 노선 234001204 도 같은 id 로 선다. → 9자리 id 는 **서울과 경기를 둘 다** 불러야 한다.

## 3. 계약

### 3-1. 노선 한 줄 `BusStopArrivalResponse`

새 평평한 클래스(기존 `BusArrivalResponse` 는 Lombok `@Builder` 라 상속 안 함). 칸:

| 칸 | 타입 | 뜻 |
|---|---|---|
| `routeId` | String | 공급자 노선 id(서울 busRouteId, 경기 routeId, TAGO routeid). 앱이 노선을 맞추는 키 |
| `routeName` | String | 노선 번호 문자열 |
| `routeType` | Integer, nullable | 원본 코드(서울 `routeType`, 경기 `routeTypeCd`). TAGO null |
| `routeTypeSource` | String | `SEOUL` / `GG` / `TAGO` |
| `routeTypeName` | String, nullable | "간선", "일반" 등(서울·경기 코드표), TAGO 는 `routetp` 원문 |
| `direction` | String, nullable | 표시용. 서울 `adirection`(종점 쪽), 경기 `routeDestName`, TAGO null |
| `arrivalMsg1/2` | String | 원본 문구(운행종료, 출발대기 포함) |
| `predictTime1/2` | Integer, nullable | 남은 초. **버스가 없으면 null**(서울 차량 "0" 행의 `traTime` 은 남은 초가 아니다) |
| `congestionNum1/2` | Integer, nullable | 원본 칸 이름을 픽스처로 고정 |
| `stopsAway1/2` | Integer, nullable | 노선 단위 API 와 같은 규칙 |
| `vehicleId1/2` | String, nullable | 서울 "0" → null, TAGO null |

`approachStops1` 은 두지 않는다(정류장 하나에 노선 수만큼 카탈로그 조회).

정렬: `predictTime1` 오름차순, `predictTime1 == null` 은 뒤에 `routeName` 순.

### 3-2. 경로별 응답

- 정류장 하나: `[BusStopArrivalResponse]`. 실패, 모르는 id → `[]`(200).
- 정류장 여러 곳: `[{ "stopId", "status", "arrivals": [BusStopArrivalResponse] }]`, 요청 순서 그대로. `status` = `OK` / `LIMITED` / `BLOCKED` / `ERROR` / `UNKNOWN_STOP`(공급자 하나라도 OK 면 OK, 실패한 공급자 줄은 빠짐). `stopIds` 쉼표 구분, 공백 무시, 중복 제거, 최대 10, 넘으면 400.

## 4. 지역 판별

- `TAGO_ID`(영문 3글자 + 숫자, `BusArrivalQueryService.TAGO_ID`) → TAGO. 도시코드는 `regional_bus_route_stop` 에서 `provider='TAGO' and stop_id=?` 의 `city_code`. **한 정류장이 도시 여럿이면 모두 부른다** — 2026-11-15 측정: TAGO 정류장 2,311곳이 도시코드 둘 이상(예: `MPB100100003` = 목포 36010, 무안 36420). 없으면 접두사 표.
- 9자리 숫자 → 공급자 집합 = {서울: `bus_stop` 에 있고 `deleted_at is null`, `ars_id` 가 비지 않음} ∪ {경기: `regional_bus_route_stop` 에 `provider='GG' and stop_id=?`(V56 의 `(provider, stop_id)` 인덱스)}. 둘 다 부르고 줄을 합친다. **중복 제거는 `routeId` 로만**(노선 번호는 지역 사이에 겹친다 — 경기 시내 "1", "5-1").
- 어느 카탈로그에도 없는 id → 공급자를 부르지 않고 `[]`/`UNKNOWN_STOP`(임의 id 로 한도를 태우지 못하게).
- 서울 행은 요청 stId 와 `stId` 가 같은 것만. 0행이면 `bus_stop.ars_id` 가 낡았을 수 있다 → 로그와 지표. 순환 노선이 같은 `busRouteId` 로 두 행이면 첫 버스가 빠른 쪽.

## 5. 호출, 한도, 캐시

### 서울 — `SearchTimeBusAdapter`(검색용) 에 넣는다

`SeoulBusApiAdapter` 는 수집기용(재시도 3회, 읽기 30 s, 검색 한도 미적용)이라 쓰지 않는다.

- `SearchTimeBusAdapter.getStationByUid(arsId)`: 기존 `getArrInfoByStop` 과 같은 틀(1.5 s, 재시도 없음, 차단기, `BusQueryResult`).
- 한도 끝점 `BusApiQuotaPort.Endpoint.STATION` 신설 → `RedisBusApiQuota` 의 switch, `BusApiQuotaProperties.station`(`bus.quota.search.station: ${SEARCH_QUOTA_STATION:5000}`), 차단기 키도 끝점별.
- 동시 호출: 인스턴스당 8개 공유 제한을 검색과 같이 쓰되, `STATION` 은 대기 없이 바로 포기(검색이 먼저).
- 캐시: `CachedSearchTimeBusAdapter` 에 `getStationByUid` 추가(`getArrInfoByStop` 의 Redis 캐시 + 인스턴스 내·간 single-flight 를 따라 함). 키 `bus:search:station:{arsId}`.

### 경기 — `GyeonggiBusArrivalAdapter.getStationArrivals(stationId)`

- 공급자 한도는 "기능마다 10,000"(`GyeonggiBusArrivalAdapter.java:43`) → `getBusArrivalListv2` 는 자기 10,000 을 가진다. 버킷 `gg-stop-arrival`, 상한 `regional-bus.shared.gyeonggi-stop-daily-limit`(기본 8,000). **캐시 미스일 때만** runner 안에서 센다.
- 차단 이름 따로 `gyeonggi-stop-arrival`(한도 초과 응답이 노선 단위 `gyeonggi-bus-arrival` 을 막지 않게).
- 동시 세션: 포털 키 30개를 경기·TAGO·지하철 TAGO 가 나눠 쓴다 → TAGO 의 `tago-bus` 같은 공유 Redis 세마포어 `gg-bus`(크기 6) 신설.

### TAGO — `TagoBusArrivalAdapter.getStopArrivals(cityCode, nodeId)`

- 공유 세마포어(서버 사이 8) 그대로. 차단 이름 따로. `numOfRows=100`, `totalCount` 가 넘으면 다음 쪽.
- `routeid` 로 묶어 기존 `TagoArrivalMapper.toResponse(routeId, buses)` 재사용.

### 캐시 규칙 (정류장 단위 공통)

- TTL 기본 30 s(`bus.stop-arrival.cache-ttl-seconds`) — 앱 폴링 30 s 와 맞춰 한 사용자가 연달아 부를 때 절반은 캐시에서. 서울 검색 캐시(15 s)와 따로.
- **성공한 빈 결과도 저장**(밤, 버스 없음 — 안 그러면 폴링마다 원본 호출). 실패는 저장하지 않는다.

### 요청 안 병렬

- Java 17 → 가상 스레드 없음. 전용 `ThreadPoolTaskExecutor`(코어 8, 최대 16, 큐 0, 넘치면 거절 → 그 정류장 `LIMITED`).
- 요청 전체 기한 2.5 s. 남은 기한만큼만 각 future 를 기다리고, 늦은 정류장은 `LIMITED` 로 두고 나머지를 돌려준다.

## 6. 매퍼 (기존 규칙 재사용)

- 서울: 차량 "0" → null, `stopsAway = staOrd − sectOrd1` 은 `BusArrivalQueryService` 의 정적 규칙을 꺼내 공유. 새로 `predictTime` 도 차량이 없으면 null.
- 경기: `GyeonggiArrivalMapper` 의 초 규칙("분과 60초 안일 때만 원본 초"), 빈 문자열 → null(`intOrNull`) 재사용.
- TAGO: `TagoArrivalMapper` 재사용.
- 노선 유형 코드표 2개(서울 1~9·0, 경기 11~53)는 `BusRouteTypeNames` 한 곳에.

## 7. 작업 순서

- [ ] **0. 한도 확인(구현 전, 사용자):** 공공데이터포털 마이페이지에서 서울 `정류소정보조회 서비스`(stationinfo)와 경기 `버스도착정보 v2 getBusArrivalListv2` 의 우리 키 일일 트래픽. 서울이 5,000 보다 작으면 기본값을 그 아래로. **TAGO 정류장의 도시코드 중복 측정**(`select stop_id from regional_bus_route_stop where provider='TAGO' group by stop_id having count(distinct city_code) > 1`).
- [ ] 1. 원본 픽스처(키 제거)를 `src/test/resources/arrival/stop/` 에: 서울 버스 있음·차량 "0"(운행종료, 출발대기)·순환, 경기(빈 문자열, 숫자 이름, `121000009`), TAGO(여러 대, 100줄 넘김 페이지).
- [ ] 2. 매퍼 + 코드표 + `BusStopArrivalMappersTest`(차량 "0" → 시간 null, stId 필터, 순환 중복, 경기 빈 문자열·숫자 이름, TAGO 묶기, 운행종료 포함, 정렬, 혼잡도 칸).
- [ ] 3. 서울: `SearchTimeBusAdapter.getStationByUid` + `Endpoint.STATION` + 캐시 데코레이터 + 테스트(한도, 차단기 분리, 캐시 적중, 빈 결과 저장, 실패 미저장).
- [ ] 4. 경기·TAGO 정류장 단위 메서드 + 버킷·차단 이름·`gg-bus` 세마포어 + 테스트(한도 분리, 차단 분리, 세마포어 포화, TAGO 페이지).
- [ ] 5. `BusStopArrivalQueryService` + 판별 + 병렬 + 컨트롤러 + Swagger `@Tag("[공용] 버스 도착 정보")` + 테스트(9자리 두 공급자 합치기, routeId 중복 제거, 모르는 id → 공급자 호출 0, 묶음 순서·status·11개 400, 기한 초과 부분 응답, 트랜잭션 없음).
- [ ] 6. 기존 `/api/arrivals/bus/{stopId}/{routeId}` 테스트 전부, 전체 `./gradlew test`.
- [ ] 7. `RealtimeArrivalLiveIT` 에 정류장 단위 대조: 서울 1곳, 경기 1곳, TAGO 1곳, **두 공급자 정류장 `121000009`, `124000360`(서울·경기 노선이 둘 다 나오는지)**, 인천 `ICB…` 1곳. 노선 집합, 첫 버스 초 ±45 s, stopsAway.
- [ ] 8. 배포(api, worker, worker2 같은 이미지, 마이그레이션 없음), 진행 여정 0 확인, 운영 대상 실데이터 IT, 노트 `### 검증`.

## 8. 검증

- `./gradlew test` 전체(기존 실패는 "기존 무관"), 계약마다 테스트 1개 이상.
- 운영 실데이터 IT: 두 경로가 서울·경기·TAGO·두 공급자 정류장에서 원본과 맞는다.

## 9. 리스크

- 목표 부하 계산: 근처 화면을 연 사용자 한 명은 정류장 8곳 × 시간당 120회(캐시 30 s 면 그 절반) = 서울 시간당 최대 480회. 몫 5,000 은 약 10 사용자·시간. 몫을 넘기면 그 끝점만 자정까지 `[]`(노선 단위 도착과 안내는 영향 없음). 사용자가 늘면 공급자 한도 상향이 먼저다.
- 서울 `bus_stop.ars_id` 는 정적 배치가 꺼져 있어 낡을 수 있다(0행 지표로 감시).

## 10. 뺀 것 (사용자 결정)

- 공개 `/api/nearby/bus-stops` 의 경기·TAGO 확장과 V76: 앱은 추적 API 로 전국 근처 정류장을 이미 받는다.
- 좌표 경로 `/api/bus/nearby/arrivals`: 노선·방향 합치기는 앱이 이미 한다.
- `routeDisplayColor`: 앱은 추적 API 에서 색을 받는다.

## 11. 검토 반영 기록

- Opus 1차(v2 대상, 2026-11-15): 같은 9자리 id 의 서울·경기 공유(BLOCKER → §4 두 공급자), 서울 호출을 수집기 어댑터에 넣음(BLOCKER → `SearchTimeBusAdapter`), Java 17 가상 스레드 없음·동시 세션 공유·기한(→ §5 전용 풀, 2.5 s, `gg-bus`), 한도 모델(경기는 기능별 10,000 → 별도 버킷, 차단 이름 분리, 서울 한도 확인을 0단계로), 근처 확장·V76 위험(→ 사용자 결정으로 뺌), 서울 버스 없는 행의 시간(→ null), 좌표 경로 방향 키(→ 뺌), 노선 이름 중복 제거(→ routeId), 빈 결과 캐시(→ 저장), TAGO 페이지, DTO 상속 불가, 표시색(→ 뺌), 묶음 status, 두 공급자 정류장 실데이터 표본.

## 12. 구현에서 달라진 것

- 경기·TAGO 정류장 호출은 기존 노선 어댑터에 메서드를 더하지 않고 새 어댑터 `GyeonggiStopArrivalAdapter`, `TagoStopArrivalAdapter` 로 뺐다. 노선 단위 어댑터의 캐시·한도 경로를 건드리지 않기 위해서다. 세마포어(`gg-bus`, `tago-bus`)와 TAGO JVM 허가는 공유한다.
- 정류장 캐시는 `CachedSearchTimeBusAdapter` 데코레이터가 아니라 서비스 단 `RedisBusStopArrivalCache` 하나로 세 공급자에 같은 규칙(30 s, 빈 성공 저장, 실패 미저장)을 건다. 같은 JVM 안 같은 정류장 동시 요청은 SingleFlight 로 한 번만 부른다.
- 공급자 판별 카탈로그는 `BusStopCatalogPort` / `JdbcBusStopCatalog` 로 두었다.
