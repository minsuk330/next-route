# 서울·경기 밖 버스 실시간 도착 연결 (TAGO) — PLAN

티어: **M**. 브랜치: **PR #251(경기 GBIS) 머지 후** fresh `main` 기준 `feat/tago-bus-arrival`.
같은 파일(`OtpSeoulIdentityResolver`, `LegacyRouteResponseMapper`, `BusArrivalQueryService`)을 건드려 #251 위에 쌓으면 스택이 되므로 머지를 기다린다.

## 배경 (실측, 2026-10-05)

- 국토교통부 TAGO 버스도착 API `https://apis.data.go.kr/1613000/ArvlInfoInqireService` (운영계정, 기능별 일 500,000).
  **서울·경기는 범위 밖**(서울=서울 API, 경기=GBIS #251). 도시코드 목록 138개에 서울 없음.
- **ID 는 OTP 값 그대로, 접두사 포함**: OTP `1:BS_TAGO_BSB167850202` → `nodeId=BSB167850202`, `1:BR_TAGO_BSB5200186000` → `routeId=BSB5200186000`.
  `getSttnAcctoSpcifyRouteBusArvlPrearngeInfoList?cityCode=21&nodeId=BSB167850202&routeId=BSB5200186000&_type=json` → 부산 186 두 대 정상.
- **`cityCode` 필수.** OTP 접두사(3글자) → 도시코드를 정류장마다 138개 코드를 대입해 실측. 47개 중 **38개가 1:1**(아래 표).
- 응답: 버스 한 대 = item 하나. `arrtime`(초), `arrprevstationcnt`(몇 정류장 전), `routeno`, `vehicletp`. 혼잡도 없음.
  결과 없으면 `"items": ""`. (item 하나일 때 배열이 아니라 객체로 올 수 있음 — data.go.kr 공통 관례, 파서에서 둘 다 처리.)
- ⚠️ **동시 세션 한도 30**: 병렬 16 실측 중 `resultCode 99 "가용한 세션이 존재하지 않습니다. (30/30)"` 41건. 같은 키를 GBIS·TAGO 지하철과 공유.
- 전국 OTP 버스 탑승지점 비중: TAGO 확정 38 = **41.0%**, TSB(농어촌, 시군 110여 개) 20.9%, 미확정 8 = 6.1%, 비TAGO 15.6%, 경기 13.2%, 서울 3.3%.

### 접두사 → 도시코드 (실측 확정 38)

| 접두사 | cityCode | 지역 | 접두사 | cityCode | 지역 |
|---|---|---|---|---|---|
| ADB | 37040 | 안동 | ICB | 23 | 인천 |
| ASB | 34040 | 아산 | JCB | 33030 | 제천 |
| BSB | 21 | 부산 | JJB | 38030 | 진주 |
| CAB | 34010 | 천안 | JUB | 35010 | 전주 |
| CHB | 33020 | 충주 | KJB | 24 | 광주 |
| CJB | 33010 | 청주 | KUB | 37020 | 경주 |
| CWB | 38010 | 창원 | KYB | 36060 | 광양 |
| DGB | 22 | 대구 | NJB | 36040 | 나주 |
| DJB | 25 | 대전/계룡 | NWB | 35050 | 남원 |
| GCB | 37030 | 김천 | PHB | 37010 | 포항 |
| GHB | 38070 | 김해 | SAB | 38060 | 사천 |
| GMB | 37050 | 구미 | SCB | 36030 | 순천 |
| GOB | 34020 | 공주 | SJB | 12 | 세종 |
| GRB | 34070 | 계룡 | SSB | 34050 | 서산 |
| GSB | 36320 | 곡성 | USB | 26 | 울산 |
| GUB | 35020 | 군산 | WJB | 32020 | 원주/횡성 |
| GYB | 37100 | 경산 | YCB | 37070 | 영천 |
| HPB | 36430 | 함평 | YDB | 37350 | 영덕 |
| | | | YEB | 36020 | 여수 |
| | | | YSB | 38100 | 양산 |

범위 밖(후속): TSB(농어촌, 접두사 하나가 여러 시군 — ID 앞 3자리가 시군 번호로 보임, 미검증), 미확정 8개 BEB·CGB·CNB·GJB·JEB·MPB·MYB·OCB(표본 정류장에 버스가 없었거나 세션 한도로 막힘).

## 확정 결정

1. **엔드포인트·응답 계약 불변**: 같은 `/api/arrivals/bus/{stopId}/{routeId}`. 검색 응답의 `startLocalStationID`/`busLocalBlID` 에
   **접두사 포함 TAGO id**(`BSB167850202`/`BSB5200186000`)를 넣는다.
2. **provider 판별 = id 모양**: 서울·경기 id 는 숫자 9자리, TAGO id 는 영문 3글자 접두사. 도착 조회에서 `stopId`·`routeId` 가
   `^[A-Z]{3}\d+$` 이고 접두사가 표에 있으면 TAGO, 아니면 기존 흐름(서울 순번 → 없으면 경기). DB 조회 없음.
3. **도시코드는 접두사 표(코드 상수)**. 표 밖 접두사는 id 를 채우지 않는다(null) — 프론트가 호출 자체를 안 하게.
4. **동시 호출 제한**: TAGO 어댑터 전용 `Semaphore(8)` + 15초 캐시(성공만) + `SingleFlight`. `resultCode 99`(세션 한도) → ERROR(차단 안 함, 일시적).
   포털 오류 20/22/30~33 → GBIS 와 같이 자정까지 차단.
5. **변환**: `arrtime` 오름차순 앞 두 대 → `predictTime`=초 그대로, 메시지 ≤60초 `곧 도착` / 그 외 `{분}분후[{정류장수}번째 전]`(분=초/60 내림),
   두 번째 버스 없으면 `도착 정보 없음`, 혼잡도 0(정보 없음). 결과 0대 → 빈 리스트(서울 OK-빈 결과와 같은 의미).
6. 키: GBIS 와 같은 포털 키. env `TAGO_BUS_API` 를 따로 두되 값은 같은 키(서비스별로 키를 바꿀 수 있게). 저장소에 원문 금지.

## 작업 순서

- [ ] **0.** #251 머지 확인 → `git checkout main && git pull` → `feat/tago-bus-arrival`. VPS `env/api.env` 주입 위치는 #251 과 같음.
- [ ] **1. 도시코드 표** — 신규 `application/route/mapping/TagoCityCodes.java`:
  `static Optional<String> cityCodeOf(String prefix)` (위 38개, 불변 Map). 미확정 접두사는 넣지 않는다.
- [ ] **2. 식별** — 신규 `TagoBusIdentity(String cityCode, String nodeId, String routeId, String routeName)`;
  `OtpSeoulIdentityResolver.resolveTagoBus(stopGtfsId, routeGtfsId, routeName)`:
  정규식 `^\d+:BS_TAGO_([A-Z]{3})(\d+)$`, `^\d+:BR_TAGO_([A-Z]{3})(\d+)(?:_[UD])?$`, 정류장·노선 접두사 같고 SEB/GGB 아니고 표에 있을 때만.
  nodeId/routeId 는 **접두사 포함** 문자열.
- [ ] **3. 매퍼** — `LegacyRouteResponseMapper` BUS 분기: 서울 → 경기 → TAGO 순으로 시도.
- [ ] **4. 포트·어댑터** — `application/arrival/port/out/TagoBusArrivalPort`
  (`BusQueryResult<TagoBusArrival> getArrivals(String cityCode, String nodeId, String routeId)`),
  DTO `TagoBusArrival(String routeId, Integer arrSeconds, Integer stopsAway, String vehicleType)`,
  어댑터 `infrastructure/adapter/out/api/tago/TagoBusArrivalAdapter` (`@Primary RestClient`, `exchange()`, items 객체/배열/"" 모두 처리).
  `application.yaml`: `tago.api.bus-key: ${TAGO_BUS_API:}`, `tago.api.bus-arrival-base-url: https://apis.data.go.kr/1613000/ArvlInfoInqireService`.
- [ ] **5. 도착 분기** — `BusArrivalQueryService.getArrivals`: id 모양이 TAGO 면 `TagoCityCodes` 로 cityCode → TAGO 포트 → `TagoArrivalMapper`.
  그 외는 #251 흐름 그대로.
- [ ] **6. 검증**: 컴파일 + 아래 테스트 + 전체 `./gradlew test --continue`(IT 환경 실패는 "기존 무관" 분류) → 노트 `### 검증`.
- [ ] **7. PR** (플랜 포함, staged 검증). **8. 배포**(사용자 승인 후): `env/api.env` 에 `TAGO_BUS_API` 추가 → api 재생성 →
  `curl /api/arrivals/bus/BSB167850202/BSB5200186000` 확인.

마이그레이션 없음.

## 테스트 목록

| 계약 | 테스트 |
|---|---|
| BSB 정류장+노선 → `TagoBusIdentity(21, BSB167850202, BSB5200186000)` | resolver |
| 정류장·노선 접두사 불일치 / SEB / GGB / 표 밖(TSB, BEB) → empty | resolver |
| 노선 `_U`/`_D` 접미사 제거 | resolver |
| 매퍼: 부산 leg → 접두사 포함 id 채움, 서울·경기 결과 불변 | mapper |
| 어댑터: item 배열 / 단일 객체 / `""` 파싱 | adapter |
| 어댑터: resultCode 99 → ERROR, 차단 안 함 | adapter |
| 어댑터: 포털 22 → BLOCKED + 이후 호출 없음 | adapter |
| 어댑터: 키 미설정 → 호출 없음 / 15초 캐시 / 실패 비캐시 / 동시 8 초과 시 대기 후 LIMITED | adapter |
| 서비스: TAGO id → TAGO 포트만(서울 DB·경기 미호출) | service |
| 서비스: 변환(초 그대로, 메시지, 두 번째 없음, 혼잡도 0, 정렬) | service |
| 서비스: 숫자 id 는 기존 흐름(서울/경기) 그대로 | service |

## 리스크·주의

- **프론트가 id 를 숫자로 가정하는지 미확인.** 영문 접두사가 들어간다 → 모바일 인계에 명시(`docs/plan/tago-bus-realtime/HANDOFF.md` 1줄).
- **동시 세션 30 공유**: GBIS·TAGO 지하철과 같은 키. 사용자가 늘면 99 오류 증가 → 로그 관측, 필요 시 서비스별 키 분리 신청.
- 접두사 표는 하루 한 시각(평일 17시) 실측. 노선 개편으로 도시코드가 바뀔 일은 드물지만, 결과 0대가 장기간이면 표 재검증.

## 후속(별건)

- TSB(20.9%)·미확정 8개(6.1%): 3자리 시군 번호 → 도시코드 표를 낮은 동시성(≤8)으로 실측하는 배치. 표가 생기면 `TagoCityCodes` 에 추가만.

## 관련 좌표

- #251: `OtpSeoulIdentityResolver.resolveGyeonggiBus`, `GyeonggiBusArrivalAdapter`(포털 오류·캐시·SingleFlight 패턴 재사용), `GyeonggiArrivalMapper`
- `BusArrivalQueryService.getArrivals` / `LegacyRouteResponseMapper` BUS 분기
