# 지방 지하철 시간표 기반 도착 — 실행 계획 (L)

작성 2026-11-16, 같은 날 공휴일 공식 대조 후 개정. 기준 커밋 `origin/main` dcd9e2b2. 티어 L(스키마, 새 API 계약, 외부 소스 4개, PR 4+3).
경로는 `src/main/java/watoo/grd/nextroute/` 기준(아니면 전체 경로).

## 0. 결론
부산 1~4, 대구 1~3, 대전 1, 광주 1의 역 화면, 검색 결과, 내비 대기에 **운영사 공식 시간표 기준 다음 열차**를 보여 준다.
요일 구분(평일, 토요일, 공휴일, 광주 명절)은 **노선마다 운영사 공식 출처로 확인된 것만** 쓴다.
모든 응답에 `source: "TIMETABLE"`, 앱은 "시간표" 칩. 서울 19개 노선은 아무것도 안 바뀐다.

예: 부산 서면역(1호선) 평일 08:00 → `다대포해수욕장 방면 08:03 (시간표 기준 3분 후)`. 같은 역 일요일이면 공휴일표(dayType 3)로 계산.

**범위 밖(공식 공휴일 시간표 미확인)**: 동해선, 대경선(코레일 공식 시간표를 data.go.kr 에서 못 찾음. 코레일 파일 15052169 는 2021년 51행), 부산김해경전철(공식 시간표 없음, KRIC 2022).
TAGO 와 KRIC(2026-05) 가 동해선에서 2~5분씩 다른 열차가 40~60% 라 어느 쪽도 믿을 근거가 없다. 공식 출처가 생기면 후속 PR.

## 1. 배경과 범위 변경
- 지방 운영사 실시간 API 없음(2026-11-16). KRIC 통합 화면도 전 노선 `rltmStt=T`(시간표), 주석 "2021.01.21 실시간 운행현황 정보 미수집".
- **SC-6 변경**: `docs/plan/journey-navigation/SCOPE-CHANGES.md:212`, `docs/plan/journey-reroute-remaining-search/DESIGN.md:145` 의 "시간표 대체 안 한다"는
  **실시간 소스가 없는 지방 노선에 한해** 뒤집는다(사용자 결정 2026-11-16). 서울 노선 예측기의 "모름" 정책은 유지.
- 버스 `SCHEDULE` 대기 철회(c10fabf4, "신뢰 낮음, 헷갈림")와 차이: 실시간과 섞지 않고, 실시간 없는 노선에만, 항상 라벨, 출처는 운영사 공식.

## 2. 데이터 출처 (요일별, 공식 대조 2026-11-16)

| 노선 | 평일 | 토요일 | 공휴일 | 명절 | 공식 대조 근거 |
|---|---|---|---|---|---|
| 대전1 `DJ1` | TAGO 01 | = 공휴일 | TAGO 03 | = 공휴일 | 공식 엑셀 `djtc.kr .../열차운행시각표(2026.3.30.).xlsx` 와 판암역 평일, 주말·휴일 전 시각 일치. 공식이 "주말, 휴일" 한 표 |
| 대구1~3 `DG1~3` | **대구교통공사 열차시각 API 내보내기 파일** WEEKDAY | 같은 파일 SAT | 같은 파일 HOLIDAY | = 공휴일 | 공식 API 휴일 = TAGO(반월당2 124/124, 영남대2 막차 23:18 일치), 공식 API 토요일 = 140편. 화원1 평일 공식 06:12, 06:23 … 24:02 = TAGO. API 는 서버 호출 불가(봇 확인) → 사람이 브라우저 스크립트로 받아 커밋 |
| 광주1 `GJ1` | **광주교통공사 공식 CSV** 평일 | CSV 토요일 | CSV 휴일 | CSV 명절 | data.go.kr 15111497 `광주교통공사_열차 시간표 데이터_20261101`(2026-11-02 수정, 11-01 녹동행 1편 추가 반영). 휴일 금남로4가 202/202, 운천 202/202 = TAGO. 토요일 ≠ 휴일(183/205), 명절 ≠ 휴일(37/160) |
| 부산1~4 `BS1~4` | **부산교통공사 API** dayType 1 | dayType 2 | dayType 3 | = 공휴일 | 15158990(2026-07-20 갱신, 활용신청 승인 11-16). 1~4호선 모두 3종(서면1 177/167/160, 안평4 145/138/129 방향당) |

### 2.1 출처별 계약 (실측)
- **TAGO** (대전만 주 출처, 대구와 광주는 교차 검사용) `http://apis.data.go.kr/1613000/SubwayInfo/GetSubwaySttnAcctoSchdulList?serviceKey&pageNo&numOfRows&_type=json&subwayStationId&dailyTypeCode(01|03)&upDownTypeCode(U|D)`
  - item: `subwayStationId, subwayStationNm, endSubwayStationId, endSubwayStationNm, depTime("053200"), arrTime, dailyTypeCode, upDownTypeCode`
  - 🔴 `dailyTypeCode=02`(토요일)는 모든 노선 0건. 종착역은 한쪽 방향 0건. 대전 시청 휴일 U 에 `depTime="0"` 행 → 버림.
  - 🔴 결과 1건이면 `item` 이 객체, 0건이면 `"items": ""`. 기존 `TagoBaseResponse` 는 배열만 받음.
  - 🔴 반영이 늦을 수 있음: 광주 11-01 추가 열차(소태 23:34)가 TAGO 에 없음.
- **부산** `https://apis.data.go.kr/B551542/trainTime/getTrainTime?serviceKey&act=json&scode&numOfRows=1500&pageNo=1`
  - item: `scode, sname("서면(1)"), line, trainno, arrtime("05:37:15"), dayType("1|2|3"), updown("0|1"), endcode("95")`
  - 🔴 `day`, `updown`, `stime`, `etime` 파라미터를 **무시**하고 역 전체를 준다(서면1 1008행, 연산3 904행). 행 필드로 분리.
  - `endcode` = 종착역 scode(1호선 `95`=다대포해수욕장, `134`=노포). 시각은 `arrtime` 하나 → 출발 시각으로 쓴다.
  - 키 미승인 `SERVICE_KEY_IS_NOT_REGISTERED_ERROR`(30), 경로 오류 `NO_OPENAPI_SERVICE_ERROR`(12).
- **대구 열차시각 API**: 운영사 원본 `https://www.dtro.or.kr/open_content_new/ko/OpenApi/stationTime.php?STT_NM=반월당&LINE_NO=2&SCHEDULE_METH=UP|DOWN&SCHEDULE_TYPE=WEEKDAY|SAT|HOLIDAY`(키 없음, XML).
  - 응답 `<apiData><LINE_NO>2</LINE_NO><STT_CD>230</STT_CD><STT_NM>반월당</STT_NM><SCHEDULE_METH>상행</SCHEDULE_METH><SCHEDULE>5:42:00, 5:57:00, …, 23:45:00(이곡)</SCHEDULE></apiData>`
    - 시각 뒤 `(이곡)` = 그 열차 종착역(단축 운행). 없으면 노선 끝 종착역. `24:02:00` 처럼 24시 넘는 표기 있음.
  - 🔴 `dtro.or.kr` 은 서버 호출(로컬 PC, VPS 모두)에 **봇 확인 스크립트**(441B, `sabSignature` 쿠키 심고 reload)를 돌려준다. 서버에서 우회하지 않는다.
    data.go.kr 15133855 는 이 주소로 넘기는 링크형 등록이라 게이트웨이 없음(사용자 확인 2026-11-16).
  - **채택(사용자 결정 2026-11-16)**: 사람이 브라우저 콘솔에서 `tools/regional-subway/daegu_export.js` 를 돌려(역 94 × 상/하 × 평일/토/휴일 ≈ 564 요청, 500ms 간격)
    `daegu-timetable-YYYYMMDD.json` 을 받고 `src/main/resources/subway/daegu/timetable.json` + `SOURCE.md`(받은 날짜) 로 커밋. 서버는 이 파일만 읽는다.
  - 파일 형식: `{source, fetchedAt, stationCount, failures[], rows[{line, query, meth: UP|DOWN, type: WEEKDAY|SAT|HOLIDAY, data[{lineNo, sttCd, sttNm, scheduleMeth, schedule["5:42:00", …, "23:45:00(이곡)"]}]}]}`. 종착역 한쪽 방향은 `data: []` 가 정상.
  - **갱신 감지**: 주 1회 같은 역의 TAGO 평일/휴일과 파일 평일/휴일을 60초 창 비교, 일치율 < 0.95 인 역이 있으면 `regional_subway_source_outdated{source="DAEGU_API"}` 게이지 + 로그 "대구 파일 다시 받기".
    TAGO 평일/휴일은 공식과 같음을 확인했으므로 대구 개정 → TAGO 반영 → 불일치로 잡힌다(토요일만 바뀌는 개정은 못 잡음 — 리스크).
  - 쓰지 않음: 대구 파일데이터 6개(2024-10 기준, 낡음 — 1호선 평일 1분 어긋남, 2호선 휴일에 없는 막차 2편).
- **광주 공식 CSV** 15111497: 헤더 `요일,종착역 코드,방향(상_하행),도착시간,역사코드,기준일자,호선,종착역명,역사명`, 14,730행, `요일` ∈ {평일, 토요일, 휴일, 명절}, `도착시간` `HH:MM`.
  - 🔴 `역사코드` 가 역명과 1:1 이 아님(101 이 소태역·학동증심사입구역 둘 다) → **역명으로** 매칭. `역` 접미사 제거.
  - 다운로드: `POST https://www.data.go.kr/tcs/dss/selectFileDataDownload.do`(publicDataDetailPk, publicDataPk) → `atchFileId` → `GET /cmm/cmm/fileDownload.do?atchFileId&fileDetailSn`. 런타임에 받지 않고 **리소스로 커밋**(UTF-8 변환).
  - 갱신 감지: `https://www.data.go.kr/catalog/15111497/fileData.json` 의 `dateModified` 를 주 1회 읽어 커밋된 `SOURCE.md` 날짜보다 새로우면 경고(Prometheus 게이지 + 로그). 갱신은 사람이 파일 교체 PR.
- 쓰지 않음: OTP GTFS stoptimes(대전 3-30 개정 전, 토/일도 평일표), KRIC 파일 id=900(부산 2022, 대구 2025-03), 광주 ZIP 15046047(2019~2021).

## 3. 설계 결정
1. **서울 테이블과 분리.** `subway_station` 에 넣으면: 1001~1099 는 `SubwayLineIdMapper.toLineName`(:93-111)이 서울 API "N호선" 으로 보냄,
   `OtpSeoulIdentityResolver` 정규식 `(?<!\d)([1-9])호선`(:54)이 `부산1호선`→1001, `SubwayStationsAheadService` 400(`SubwayRoutePatternAssembler.LINE_IDS` :42-61),
   `SubwayLineStationIndex`(:145-155) 재시작 전엔 안 보임, `geom` 자동 채움 없음, `statn_id` UNIQUE 없음.
2. **노선 코드는 문자열** `BS1 BS2 BS3 BS4 DG1 DG2 DG3 DJ1 GJ1`. 범위 밖 노선 코드(`BGL DHL DGY`)는 CHECK 에 넣지 않는다(후속 PR 이 마이그레이션으로 추가).
3. **요일 4종** `WD SA HO MH`. MH(명절)는 광주만 별도 행, 다른 노선은 명절에 HO 를 쓴다. 토요일 소스가 같은 표인 대전은 SA 행을 따로 두지 않고 조회 시 HO 로 대체(대체 규칙은 노선별 상수, 4절).
4. **방향 = 종착역 seq.** TAGO U/D, 부산 updown, 대구 UP/DOWN, 광주 방향 코드의 짝을 가정하지 않는다. 열차 = (출발 초, 종착역 seq).
   board→alight 이 seq 증가면 `terminal_seq >= alight_seq`, 감소면 `terminal_seq <= alight_seq` 인 열차만 → 내릴 역 전에 끝나는 열차(대구1 안심행, 광주 녹동/소태행, 대구2 이곡행) 자동 제외.
   역 화면(내릴 역 없음)은 두 끝 방향으로 묶는다. 광주 녹동역은 본선 밖 분기(소태 너머) → 시드 생성 시 `seq` 는 본선 + 분기 끝을 연장한 일직선(평동…소태…녹동)으로 두고 검사.
5. **역 목록은 커밋된 시드 JSON.** 도구가 만들고 PR diff 로 사람이 확인.
6. **역 단위 교체 트랜잭션.** 기존 `SubwayStaticDataService.loadTimetables`(:62-126)의 전체 삭제 후 재적재 금지. 실패한 역은 기존 행 유지.
7. **교차 검사(경고만).** 대구 공식 API 평일/휴일과 광주 CSV 평일/휴일을 같은 역 TAGO 와 60초 창으로 비교, 일치율 < 0.95 인 역을 게이지와 로그로 남긴다. 값은 공식을 쓴다.
8. **플래그 둘, 기본 false.** `regional-subway.timetable.enabled`(worker 적재), `regional-subway.serving.enabled`(api 제공).

## 4. 스키마 — `src/main/resources/db/migration/V76__create_regional_subway_timetable.sql`
```sql
CREATE TABLE regional_subway_station (
    id            bigint generated by default as identity primary key,
    station_key   varchar(16)  not null,          -- 'BS1-119', 'DG2-230', 'DJ1-011', 'GJ1-105'
    line_code     varchar(8)   not null,
    line_name     varchar(32)  not null,          -- '부산1호선' (OTP route shortName 과 같게)
    station_name  varchar(64)  not null,          -- '서면'
    seq           int          not null,
    tago_station_id varchar(16),                  -- 'MTRBS1119'
    provider_station_code varchar(16),            -- 부산 scode '119', 대구 STT_CD '230', 광주 역명 매칭이라 null 가능
    gtfs_stop_id  varchar(64),                    -- '1:RS_ACC1_S-2-0119'
    latitude      double precision not null,
    longitude     double precision not null,
    geom          geography(Point,4326) not null,
    loaded_at     timestamptz  not null,
    CONSTRAINT uq_regional_subway_station_key UNIQUE (station_key),
    CONSTRAINT uq_regional_subway_station_seq UNIQUE (line_code, seq),
    CONSTRAINT regional_subway_station_line_check CHECK (line_code in
        ('BS1','BS2','BS3','BS4','DG1','DG2','DG3','DJ1','GJ1'))
);
CREATE INDEX idx_regional_subway_station_geom ON regional_subway_station USING gist (geom);
CREATE INDEX idx_regional_subway_station_gtfs ON regional_subway_station (gtfs_stop_id);

CREATE TABLE regional_subway_departure (
    id            bigint generated by default as identity primary key,
    station_key   varchar(16)  not null,
    day_type      varchar(2)   not null,          -- 'WD' | 'SA' | 'HO' | 'MH'
    dep_seconds   int          not null,          -- 서비스일 0시 기준. 00:16 = 87360, '24:02:00' = 86520
    terminal_name varchar(64)  not null,
    terminal_seq  int          not null,
    source        varchar(16)  not null,          -- 'TAGO' | 'BUSAN_API' | 'DAEGU_API'(브라우저 내보내기 파일) | 'GWANGJU_CSV'
    source_date   date,                           -- 광주 CSV 기준일자 2026-11-01 등, 모르면 null
    loaded_at     timestamptz  not null,
    CONSTRAINT regional_subway_departure_day_check CHECK (day_type in ('WD','SA','HO','MH')),
    CONSTRAINT regional_subway_departure_source_check CHECK (source in ('TAGO','BUSAN_API','DAEGU_API','GWANGJU_CSV'))
);
CREATE INDEX idx_regional_subway_departure_lookup ON regional_subway_departure (station_key, day_type, dep_seconds);
```
- 엔티티 없이 `JdbcTemplate`(V56 `regional_bus_route_stop`, `application/regionalbus/service/RegionalBusCatalogLoadService` 방식). 컬럼 COMMENT 는 V56 처럼.
- 자정: 04:00 전 시각은 +86400(`TimetableConverter` :66-77 와 같은 규칙). `24:xx` 표기는 그대로 86400+.
- **요일 대체 규칙(코드 상수, `RegionalDayTypePolicy`)**: 조회 요일에 행이 없을 때 쓰는 순서.
  `DJ1: SA→HO, MH→HO` / `DG1~3: MH→HO` / `BS1~4: MH→HO` / `GJ1: 없음(4종 전부 행)`. 표에 없는 대체는 하지 않는다(빈 결과 = "시간표 없음").

## 5. 작업 순서

### PR-1 데이터 (`feat/subway-regional-timetable-pr1`)
- [x] **1-0 선행(사용자)**: 브라우저에서 `tools/regional-subway/daegu_export.js` 실행 → `src/main/resources/subway/daegu/timetable.json`(2026-11-16, 94역, 564건, 실패 0) + `SOURCE.md` 배치 완료(미커밋).
  검증: 평일·공휴일 360건 TAGO 와 분 단위 일치, 반월당2 SAT 140/HOLIDAY 124 공식 응답 일치. 종착역 반대 방향은 `["-"]` → 파서에서 버림. 중앙로 TAGO id 예외 `MTRDG13140`(STT_CD 131, `aliases.json codeOverride`).
- [ ] **1-1 시드 생성 도구** `tools/regional-subway/build_stations.py`(python3 표준 라이브러리만)
  - 입력: 운영 OTP GraphQL(VPS `docker run --rm --network nextroute_default curlimages/curl -s -X POST http://otp:8080/otp/routers/default/index/graphql`, 로컬 `localhost:18080` 터널)
    `routes(transportModes:[SUBWAY]) { shortName longName patterns { stops { gtfsId name lat lon } } }` 중 9개 노선.
  - 입력: TAGO 역 목록 = 운영 `subway_station_tago` 덤프(접두사 `MTRBS1..4, MTRDG1..3, MTRDJ1, MTRGJ1`). 대구 STT_CD = TAGO id 뒤 3자리(`MTRDG20230`→`230`, 반월당2 공식 API `STT_CD 230` 일치).
  - 노선 순서: 가장 긴 패턴. **일직선 검사**(모든 패턴이 가장 긴 패턴의 연속 부분열 또는 역순). 광주는 녹동 분기 처리(3절 4번) 후 검사.
  - 이름 정규화: 괄호 제거(`서면(부산1호선)`→`서면`), 공백, `역` 접미사 제거. 안 맞으면 `tools/regional-subway/aliases.json`. 남으면 실패.
  - 출력 `src/main/resources/subway/regional-stations.json` = `[{stationKey, lineCode, lineName, stationName, seq, tagoStationId, providerStationCode, gtfsStopId, lat, lon}]`.
  - `--check` 출력 노선별 역 수(OTP 실측: 부산1 40, 부산2 43, 부산3 17, 부산4 14, 대구1 35, 대구2 29, 대구3 30, 대전1 22, 광주1 20).
- [ ] **1-2 V76** 4절 SQL. 검증: Testcontainers IT(`src/test/resources/db/it/` 패턴) 또는 `./gradlew test --tests '*Flyway*'`.
- [ ] **1-3 리소스** 광주 `src/main/resources/subway/gwangju/timetable.csv`(UTF-8) + `SOURCE.md`(data.go.kr 15111497, 파일명 `_20261101`, 수정일 2026-11-02, 비고 "녹동행 1편 추가").
  대구 `src/main/resources/subway/daegu/timetable.json`(1-0 산출) + `SOURCE.md`(받은 날짜, 스크립트 경로, 재수집 방법). 스크립트 `tools/regional-subway/daegu_export.js` 도 커밋.
- [ ] **1-4 포트와 DTO** `application/regionalsubway/`
  ```java
  public enum RegionalDayType { WD, SA, HO, MH }
  public record RegionalDeparture(RegionalDayType dayType, int depSeconds, String terminalName) {}
  public interface RegionalTimetableSource {           // 노선 묶음별 구현 4개
      Set<String> lineCodes();
      String sourceCode();                              // TAGO | BUSAN_API | DAEGU_API | GWANGJU_CSV
      List<RegionalDeparture> fetch(RegionalStationSeed station) throws RegionalSourceException;   // 그 역의 지원 요일 전부
  }
  ```
- [ ] **1-5 어댑터** `infrastructure/adapter/out/api/regionalsubway/`
  - 공통: 주입 `RestClient`(static builder 금지, `common/config/ApiClientConfig.java:66-78`), `JsonNode`/XML 파싱, `ApiCallBlocker` 미사용, resultCode 99 와 I/O 오류는 재시도 예외(`infrastructure/adapter/out/api/regionalbus/RegionalBusRouteSourceAdapter.java:77-132` 방식).
  - `TagoSubwayTimetableAdapter`(DJ1 주 출처, DG/GJ 교차 검사): 01, 03 × U, D. 단일 객체, 빈 문자열, `depTime` 6자리 아님 → 버림. 기존 `infrastructure/adapter/out/api/subway/TagoSubwayApiAdapter.java` 는 ML 배치용으로 그대로.
  - `BusanTrainTimeAdapter`: `busan.api.train-time-base-url: https://apis.data.go.kr/B551542/trainTime`, `busan.api.key: ${SEOUL_SUBWAY_API:}`. 빈 키면 호출 안 하고 경고. dayType 1→WD 2→SA 3→HO, `endcode`→시드 역명(같은 노선 `providerStationCode`).
  - `DaeguExportFileSource`: 리소스 `subway/daegu/timetable.json` 파싱. `type` WEEKDAY/SAT/HOLIDAY → WD/SA/HO. `schedule` 항목 `H:MM:SS(종착역)?` 파싱:
    괄호 있으면 그 역이 종착, 없으면 방향 끝 역(UP/DOWN ↔ 노선 끝은 같은 열의 종착 괄호와 시드 seq 로 판정, 라벨 가정 금지). `24:02:00` → 86520. 역 매칭은 `query`/`sttNm` 정규화 → 시드.
    `dtro.or.kr` 서버 호출 코드는 만들지 않는다.
  - `DaeguSourceDriftCheck`: 2.1 갱신 감지(TAGO 평일/휴일 대비).
  - `GwangjuCsvSource`: 리소스 파싱, `요일` 평일/토요일/휴일/명절 → WD/SA/HO/MH, `역사명`·`종착역명` 의 `역` 제거해 시드 매칭, `도착시간` HH:MM.
  - `GwangjuSourceFreshnessCheck`: 주 1회 data.go.kr catalog `dateModified` 읽어 `SOURCE.md` 와 비교 → `regional_subway_source_outdated{source="GWANGJU_CSV"}` 게이지.
  - application.yaml(`seoul:` :248 근처)에 `busan.api.*`, `daegu.api.*`, `regional-subway.timetable.{enabled: ${REGIONAL_SUBWAY_TIMETABLE_ENABLED:false}, cron: "0 30 4 * * SUN", lines: BS1,BS2,BS3,BS4,DJ1,GJ1}`, `regional-subway.serving.enabled: ${REGIONAL_SUBWAY_SERVING_ENABLED:false}`.
  - 메트릭 uri 는 `common/config/ExternalApiObservationConvention` 이 `/{}/trainTime/getTrainTime` 로 만든다(`src/test/java/watoo/grd/nextroute/common/config/ExternalApiObservationConventionTest.java:64-71` 에 케이스 추가).
- [ ] **1-6 적재 서비스** `application/regionalsubway/service/RegionalSubwayTimetableLoadService`
  - 시드 upsert(`station_key` 기준, `geom = ST_SetSRID(ST_MakePoint(lon,lat),4326)::geography`).
  - 역마다: 노선의 소스로 fetch → 종착역 이름을 시드 역명으로 정규화해 `terminal_seq`(못 찾으면 그 열차 버리고 `unmatched_terminal` 카운트) →
    `DELETE ... WHERE station_key=?` + batch INSERT 를 `TransactionTemplate` 한 번에. 요일 대체는 저장하지 않는다(조회 시 `RegionalDayTypePolicy`).
  - 교차 검사(3절 7번), 역 실패 → 기존 행 유지 + `regional_subway_load_failures_total{line}`. 끝에 노선별 역 수/행 수/요일별 행 수 로그.
  - 스로틀: data.go.kr 호출 사이 200ms(TAGO 키 공유, 메모리 `project-tago-bus-realtime` 동시세션 30).
- [ ] **1-7 스케줄러** `infrastructure/adapter/in/scheduler/RegionalSubwayTimetableScheduler`: `@Profile("worker")`, `@ConditionalOnProperty(prefix="regional-subway.timetable", name="enabled", havingValue="true")`,
  기동 시 표가 비었으면 1회, `@Scheduled(cron=..., zone="Asia/Seoul")`. 구조는 `infrastructure/adapter/in/scheduler/RegionalBusCatalogScheduler.java:20-60`.
- 검증: `./gradlew compileJava test --tests '*RegionalSubway*' --tests '*ExternalApiObservationConvention*'`

### PR-2 역 API (`feat/subway-regional-timetable-pr2`, PR-1 머지 후)
- [ ] `RegionalServiceDayResolver`: `(Instant now) -> (LocalDate serviceDate, RegionalDayType, int nowSeconds)`. 04:00 KST 전이면 전날 + 86400.
  명절(MH) 판정 = 설날, 추석 연휴(당일과 앞뒤 하루, 대체공휴일 포함 여부는 광주 CSV 명절표 운영일 기준으로 확인 후 고정). 토 → SA, 일 또는 `application/subway/service/HolidayCalendar.java:5` `isHoliday` → HO, 그 외 WD.
  공휴일 이름이 필요하면 `HolidayCalendar` 구현이 쓰는 `kr-holidays.json`(천문연, ~2027-03-31) 에서 읽는 메서드 추가.
- [ ] `RegionalDepartureQueryService.next(stationKey, Integer alightSeq, int limit, Instant now)`: `RegionalDayTypePolicy` 로 요일 결정 → 3절 4번 방향 규칙 → `dep_seconds >= nowSeconds` 정렬 limit. 막차 뒤는 다음 서비스일 첫차 1개까지.
- [ ] 엔드포인트 `infrastructure/adapter/in/api/subway/RegionalSubwayStationController` `GET /api/subway/regional/stations/{stationKey}/departures?limit=3`(serving off → 404)
  ```java
  public record RegionalDeparturesResponse(String stationKey, String stationName, String lineCode, String lineName,
      String source /* "TIMETABLE" */, String dayType /* WEEKDAY|SATURDAY|HOLIDAY|LUNAR_HOLIDAY */, String timetableDayType /* 실제로 쓴 표, 대전 토요일이면 HOLIDAY */,
      List<Direction> directions) {
    public record Direction(String towardTerminal, List<Departure> departures) {}
    public record Departure(OffsetDateTime scheduledAt, int minutesUntil, String terminalName) {}
  }
  ```
- [ ] 근처 역: `infrastructure/adapter/in/api/nearby/NearbyController.java:38-45` 에 `@RequestParam(defaultValue="false") boolean includeRegional`.
  `application/nearby/service/NearbyTransitService.java:115` 가 true 이고 serving on 일 때만 `regional_subway_station` PostGIS 반경 결과를 합쳐 거리순.
  `application/nearby/dto/NearbySubwayStationResult.java:3-13` 에 `@JsonInclude(NON_NULL) String arrivalSource`(서울 `REALTIME`, 지방 `TIMETABLE`, 지방 `stationId`=stationKey, `lineId`=lineCode).
  **includeRegional=false 응답은 기존과 바이트 단위로 같아야 함**(arrivalSource 도 안 실음).
- 검증: `./gradlew test --tests '*RegionalDeparture*' --tests '*RegionalServiceDay*' --tests '*Nearby*'` + 컨트롤러 슬라이스.

### PR-3 검색 결과 (`feat/subway-regional-timetable-pr3`)
- [ ] `application/route/dto/SubPathResult.java:57-65` 에 `@JsonInclude(NON_NULL) ScheduledDepartures scheduledDepartures`
  (`record ScheduledDepartures(String source, String timetableDayType, List<Item> items)`, `Item(Instant scheduledAt, int minutesUntil)`). `with*` 복사 메서드 전부 전달.
- [ ] `application/route/service/RegionalSubwayDepartureEnricher`: 지하철 leg 의 출발/도착 GTFS stop id(`stations[0]`, `stations[last]`)가 `regional_subway_station.gtfs_stop_id` 에 있으면 next(board, alightSeq, 3).
- [ ] `application/route/service/RouteSearchService.java:239`(OTP 분기 버스 enricher) 뒤 단계, `withRealtime=false` 면 생략. 실패는 값만 비우고 200(`stage()` 패턴, 새 `SearchComponent.REGIONAL_SUBWAY_TIMETABLE`).
- 검증: `./gradlew test --tests '*RegionalSubwayDepartureEnricher*' --tests '*RouteSearchService*'`. 서울 leg null 회귀.

### PR-4 여정 대기 (`feat/subway-regional-timetable-pr4`)
- [ ] `application/journey/dto/CurrentLegArrival.java:31-55` `Source` 에 `TIMETABLE`(주석: 실시간 소스 없는 지방 노선 전용).
- [ ] `application/journey/service/RegionalSubwayTimetableWaitPredictor implements CurrentLegArrivalPredictor`: WAIT_SUBWAY 이고 다음 RIDE_SUBWAY leg 의 `startStopId`/`endStopId`(`domain/journey/entity/JourneyLeg.java:60-63`)가 지방 역이면 다음 열차 시각. Quality 는 기존 근사 계열 값.
  디스패치 `application/journey/service/CurrentLegArrivalService.java:34` `supports` → 서울 예측기와 겹치지 않게: 서울은 `hasSubwayRealtimeLine()`(:344-352), 새 예측기는 지방 역 매핑 성공 leg 만.
- [ ] 같은 규칙으로 `application/journey/port/out/TransferTargetArrivalPredictor` 구현.
- 검증: `./gradlew test --tests '*RegionalSubwayTimetableWait*' --tests '*CurrentLegArrivalService*'`.

### 앱 (nextroute-mobile, 서버 PR-2/3/4 배포 뒤)
- M1: `src/features/transit/transit-api.ts:281-284` 근처 역 `includeRegional=true`, `arrivalSource==='TIMETABLE'` 이면 새 departures API. `src/features/ride-tracking/subway-station-sheet.tsx:155` 자리에 "시간표" 칩 + "시간표 기준 n분 후", 공휴일/토요일/명절이면 "공휴일 시간표" 같은 부제.
- M2: `src/features/transit/route-result-card.tsx:151-159, 245-251` 에서 `scheduledDepartures` 있으면 "실시간 도착" 대신 "시간표".
- M3: `src/features/transit/navigation-screen.tsx:84-91` WAIT_SUBWAY 에 `source==='TIMETABLE'` 표시.

## 6. 경계 리뷰 (사람이 볼 곳)
- 계약: V76 두 테이블, departures 응답(`dayType`, `timetableDayType`), nearby `includeRegional`/`arrivalSource`, SubPath `scheduledDepartures`, `CurrentLegArrival.Source.TIMETABLE`.
- 상태 전이: 역 단위 DELETE+INSERT 트랜잭션. 요일 대체는 `RegionalDayTypePolicy` 상수 표에만.
- 실패 경로: 역 하나 실패 → 기존 행 유지, 다음 역 계속. 부산/대구 키 없음 → 그 노선만 비움.
- 플래그 기본값: 두 플래그 false. 꺼져 있으면 서울 동작과 응답이 지금과 같다.

## 7. 검증 (계약당 테스트 1개 이상)
- 어댑터: TAGO 단일 객체, `"items": ""`, `depTime="0"` 버림 / 부산 1008행 → 요일×방향 6묶음 / 대구 `SCHEDULE` 의 `23:45:00(이곡)` → 종착 이곡, `24:02:00` → 86520 / 광주 CSV 4요일, `역` 접미사, 역사코드 무시.
- 방향: 대구1 반월당→하양 에서 안심행 제외, 반월당→동대구 에서 포함 / 대구2 반월당→영남대 에서 이곡행 제외 / 광주 금남로4가→녹동 에서 소태행 제외.
- 요일: 2026-11-21(토) 대전=HO 행, 대구=SA 행, 광주=SA 행, 부산=SA 행 / 03:30 = 전날 서비스일 / 평일 공휴일 → HO / 추석 당일 광주=MH, 부산=HO.
- 교차 검사: TAGO 와 다른 픽스처에서 게이지만 오르고 값은 공식.
- 회귀: `includeRegional` 없는 nearby 응답 JSON 스냅샷 불변, 서울 leg `scheduledDepartures` 없음.
- **공식 대조(PR-1 완료 조건, `implementation_note.md ### 검증` 에 첨부)**: 노선마다 한 역, 요일마다 전 시각을 공식과 비교해 불일치 0.
  - 대전 판암 WD/HO = 공식 엑셀 2026-03-30 / 대구 반월당2 상행 WD/SA/HO = 공식 API(SA 140, HO 124) / 광주 운천 WD/SA/HO/MH = 공식 CSV(238/206/202/160) / 부산 서면1 WD/SA/HO = 공식 API(177/167/160 방향당).
- 전체: `./gradlew test`(기존 실패는 "기존 무관" 라벨).

## 8. 배포
- PR-1 머지 → Flyway V76 자동 → VPS compose worker env `REGIONAL_SUBWAY_TIMETABLE_ENABLED=true`(compose 는 git 아님, 스크래치 스크립트 + 사용자 실행 패턴, 메모리 `reference-vps-deploy`) →
  `select s.line_code, d.day_type, count(distinct d.station_key), count(*) from regional_subway_departure d join regional_subway_station s using(station_key) group by 1,2 order by 1,2` 확인.
- PR-2~4 머지 → api env `REGIONAL_SUBWAY_SERVING_ENABLED=true` → 서면, 반월당, 판암, 운천 departures 를 공식과 대조(평일과 토요일 각 1회).
- 배포 직전 진행 중 실여정 확인(메모리 `feedback-check-live-testing-before-deploy`).

## 9. 함정 메모 (구현 노트로 옮길 것)
- 부산 API 는 필터 파라미터 무시. TAGO 토요일 0건이고 반영이 늦을 수 있음(광주 11-01 추가 열차 누락). `dtro.or.kr` 봇 장벽(우회 금지, data.go.kr 게이트웨이 사용).
- 대구 파일데이터(2024-10)와 KRIC 파일은 낡음. 광주 ZIP(15046047)은 2019~2021. OTP 시간표는 도착판 출처 금지.
- 광주 CSV `역사코드` 는 역과 1:1 아님 → 역명 매칭.
- 기존 `TagoSubwayApiAdapter` 는 `ApiCallBlocker` 한 번 실패로 하루 차단, 단일 객체 응답 미처리 → 재사용 금지(ML 배치는 그대로).
- `STATIC_DATA_ENABLED` 는 의도적으로 꺼져 있음(`subway_timetable` 2026-08-25 정지). 이 기능과 무관, 켜지 말 것.
- "공식 없음" 결론 전 data.go.kr 운영사명 검색 + 운영사 공공데이터 메뉴 확인(메모리 `feedback-search-operator-data-first`).

## 10. Opus 검토 반영 (2026-11-16) — 위 본문보다 이 절이 우선
- **[B1] 여정 leg 역 id**: 지방 leg 는 `startStopId/endStopId` 가 항상 null(`JourneyTransitIdEnricher` 가 서울 노선만 채움). GTFS id 는 `sourceStartStopId/sourceEndStopId` 에만 있다(`JourneyCompiler:312-341`, `LegacyRouteResponseMapper:344`).
  WAIT_SUBWAY 의 타는 역 = `waitLeg.getSourceStartStopId()`, 내릴 역 = 다음 leg(`legIndex+1`, RIDE_SUBWAY)의 `sourceEndStopId`.
- **[B2] 예측기 디스패치**: `CurrentLegArrivalService:31-38`, `TransferTargetArrivalService:31-35` 는 `supports(JourneyLegType)` 첫 매치라 WAIT_SUBWAY 두 번째 빈은 못 닿는다.
  → 새 예측기 빈을 만들지 않는다. `WaitSubwayArrivalPredictor`, `SubwayTransferTargetArrivalPredictor` 안에서 `leg.getRouteId()==null` 이고 지방 역 매핑이 되면 `RegionalTimetableWaitReader` 로 넘긴다. 서울 leg 가 시간표 경로에 절대 안 가는 테스트 필수. `hasSubwayRealtimeLine()` 은 `JourneyLeg.java:350-352`.
- **[B3] 공휴일 이름**: 런타임 `HolidayCalendar` 는 `GongApiAdapter`(data.go.kr getRestDeInfo, `dateName` 버림)뿐. `kr-holidays.json` 은 클래스패스 밖, 2027-03 까지.
  → `GongApiAdapter` 가 `Map<LocalDate,String>` 을 캐시하고 `holidayName(date)` 제공. `fetchMonth` 실패를 "공휴일 없음"으로 캐시하는 문제, 실패한 `fetchYear` 가 매 요청 12회 재호출하는 문제를 `RegionalServiceDayResolver` 에서 막는다(음수 캐시 + `KoreanPublicHolidayCalendar` 폴백).
- **[M4] 요일 판정 순서**: MH(GJ1, `dateName` 이 `설날|추석` 또는 `대체공휴일(설날|추석)`) → 공휴일 HO → 토요일 SA → 일요일 HO → WD. 2027-02-06(설날, 토요일)·2026-10-03(개천절, 토) 테스트로 고정. 부산·대구 공휴일 토요일 = HO(공휴일표).
- **[M6] 막차 뒤 다음 첫차**: 다음 서비스일의 요일을 다시 판정(토 23:50 → 일요일 HO 첫차).
- **[M7] 이 역에서 끝나는 열차 제외**: `terminal_seq == station seq` 행은 적재에서 버린다(PR-1 `convert` 반영). 안심·소태 테스트.
- **[M8] 대구 방향**: 노선 첫 역(seq 0)에 열차가 있는 방향 = seq 증가 방향, 마지막 역에서 반대로 확인, 다르면 기동 실패(PR-1 `DaeguExportFileSource` 반영). 대구3 처럼 괄호가 없어도 된다.
- **[M9] 일직선 검사**: 순서를 지키는 부분열(부산3·4, 대구3 건너뛰는 패턴). 광주 녹동은 OTP 가 이미 평동…소태→녹동 한 줄이라 분기 처리 없음(PR-1 반영).
- **[M10] 검색 결과 기준 시각**: 지금이 아니라 그 leg 에 닿는 시각 = `plannedBoardAt − plannedWaitSeconds`(첫 leg 는 요청 출발 시각). 표시는 공식 시간표 다음 열차, OTP `plannedBoardAt`(낡은 GTFS)는 건드리지 않는다.
- **[M11] `Source.TIMETABLE` 소비처**: `TransferAssessmentWriter.sourceOf`(:82-91)·`TransferAssessmentMapper.sourceOf`(:41-49) 에 `"TIMETABLE"`, `RemainingPathCandidateProvider.sourceTag`(:670-675), `BoardingChainEtaCalculator:111`, `JourneyEvaluationMetrics.tagOf`(:251-259) 에 TIMETABLE 분기 + 소비처마다 테스트.
- **[M12] 라벨**: 출시된 앱(라이브 액티비티 포함)은 TIMETABLE 을 라벨 없이 보인다 → `regional-subway.serving.enabled` 는 **앱 M1~M3 출시 뒤에만** 켠다.
- **[m13] SubPathResult**: 생성 지점 8곳(WalkSegmentEnricher, TransferArrivalEnricher, LegacyRouteResponseMapper, RouteSearchService, WalkCoordResolver, JourneyRevisionComposer, OdSayApiAdapter, SubPathResult) + 테스트 11개. 새 단계는 `requireRideable(...)`(`RouteSearchService:238-239`) 뒤.
- **[m14] 근처 역**: `GetNearbySubwayStationsUseCase` 시그니처 변경, 합치기는 `searchWithExpanding` 람다(NearbyTransitService:114-127) 안, limit 은 합친 뒤. 지방 항목 `gtfsStopId` = null(앱이 서울 실시간 단독 추적 시작에 쓴다, #298).
- **[m15·16·17] 교차 검사**: 대구 파일 vs TAGO 평일·휴일 주 1회 하나만. 광주는 TAGO 가 늦어 오탐 → 수정일 감시만. `daegu.api.*` yaml 없음.
- **[m19] V76**: 출발 표 UNIQUE(station_key, day_type, dep_seconds, terminal_seq). 역 seq 충돌은 upsert 트랜잭션 안에서 대상 노선 seq 를 음수로 비킨 뒤 upsert. 시드 `gtfsStopIds` 는 실측 역당 1개 → 컬럼은 단일 값(첫 값).
- **station_key 규칙**: `{lineCode}-{TAGO id 뒤 3자리}`, 예외 `tools/regional-subway/aliases.json codeOverride`(대구 중앙로 MTRDG13140→131, 부산 다대포 연장 MTRBS1195~1199→95~99).

## 11. 구현 중 결정 (2026-11-16) — 10절보다 우선
- **PR-3 를 PR-2 에 합치고 계약을 바꾼다**: 검색 응답 `SubPathResult.scheduledDepartures` 대신 구간용 엔드포인트
  `GET /api/subway/regional/departures?boardStopId={stations[0].stationID}&alightStopId={stations[last].stationID}&at={ISO}&limit=3`.
  이유: 앱은 이미 서울 지하철 구간 도착을 검색 응답과 별도로 구간마다 부른다(`route-live-arrivals.ts getSubwayArrivalTarget`). 같은 방식이면
  `SubPathResult` 생성 지점 8곳·테스트 11개를 건드리지 않고, 기준 시각(M10)은 앱이 가진 `plannedBoardAt − plannedWaitSeconds` 로 넘긴다.
  역 화면 엔드포인트는 `/api/subway/regional/stations/{stationKey}/departures` 그대로.
- 근처 역 지방 항목은 시드 메모리 검색(250역 haversine). DB geom 은 적재 기록용.
