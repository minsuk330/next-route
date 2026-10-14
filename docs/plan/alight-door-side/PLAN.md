# #335 지하철 내리는 문 방향(alightDoorSide) — PLAN (M)

결론: 하차역의 **승강장 형식(섬식/상대식)** 과 **노선 통행 방향(좌/우측)** 으로 내리는 문을 정해, 지하철 탑승 단계 안내에
`alightDoorSide`(`LEFT` | `RIGHT` | null)를 붙인다.

비유: 열차가 오른쪽 길로 다니면 가운데 섬 승강장은 열차 왼쪽, 바깥 승강장 둘은 열차 오른쪽에 있다. 왼쪽 길로 다니면 반대다.

## 0. 배경·실측 (상세 = 루트 `implementation_note.md` "2026-10-12 #335" 절)

- #266 안내 시트 4항목(정류장 번호·출구·방면·빠른 하차) 중 마지막 남은 것. 이슈 #335.
- 문 좌우를 직접 주는 공공 데이터는 없다.
  - 서울 실시간 도착 `subwayHeading`: 2026-10-12 낮 13:5x 에 8역 80여 건 전부 null.
  - ODsay subPath `door`: 빠른환승 칸-문(`6-1`)이지 좌우가 아니다.
- KRIC 레일포털 `convenientInfo/stPlf`(역사별 승강장 정보) 키 승인 2026-10-12(사용기간 ~2027-10-12).
  - **노선 단위 조회가 된다**(`railOprIsttCd` + `lnCd`, `stinCd` 생략). 전국 47노선 = 47회 호출.
  - 응답 행 = 승강장 하나: `stinCd`, `plfNo`, `updnDvCd`, `plfTpCd`/`plfTpNm`(섬식·상대식·2폼3선식·쌍섬식(2폼 4선식)·쌍상대식·1섬 1상대식·null).
  - 역 이름은 응답에 없다 → 레일포털 자료실 `운영기관_역사_코드정보_2026.07.11_일반.xlsx`(1,108역, `RAIL_OPR_ISTT_CD, RAIL_OPR_ISTT_NM, LN_CD, LN_NM, STIN_CD, STIN_NM`)로 이름을 붙인다.
    다운로드: `https://data.kric.go.kr/rips/download.file?type=L&id=17&answerId=17&fileId=1` (로그인 불필요).
  - 담당자 요청: 1일 1회 또는 배치, 원천 갱신은 분기·반기, 과도 GET 모니터링.
- 운영 역 마스터 fixture(`src/test/resources/otp/subway-patterns/subway-stations.json`, 696역 19노선) vs KRIC 이름 매칭(아래 정규화):
  신분당 16역(KRIC 코드 `D007` ↔ 마스터 `4305` 불일치) + 기타 15역 빼고 전부 맞음.
- 형식 null 이 많은 노선: 수인분당 55/63, 경춘 17/25 → 빈칸 유지.
- 통행 방향 출처(웹 검색): 1호선(서울교통공사 서울역~청량리 지하 포함)·코레일 4호선(과천·안산선, 남태령~선바위 꽈배기 굴에서 바뀜)·분당선·경의중앙·서해선 = 좌측 /
  일산선(코레일 3호선) = 우측 / 서울교통공사 2~8호선·9호선 = 우측.
- **사용자 결정(2026-10-12)**: 출처 확인 노선 + 같은 열차가 이어 달리는 연장 구간(7호선 인천·8호선 별내·수인선)만 채운다. 나머지는 null.

## 1. 계약

- `JourneyLegView.alightDoorSide`: `"LEFT"` | `"RIGHT"` | `null`. **RIDE_SUBWAY 단계에만** 값이 있을 수 있다. 여정 생성 시 1회 정해지고, 재탐색 수락으로 하차점이 바뀌면 새 하차점 값으로 바뀐다.
- null 조건: 표에 없는 노선 / KRIC 형식이 섬식·상대식 단일이 아님 / 역 이름 매칭 실패·중복 / 하차역이 노선 끝 역 / 노선 끝 판정 불가(패턴 캐시 빔) / 대기 단계 후보의 노선이 하나로 안 정해짐.
- DB: `journey_leg.alight_door_side varchar(8)` null 허용(V65).
- 런타임에 KRIC 를 부르지 않는다. 키는 커밋 금지.

## 2. 작업 순서

- [x] **S1 데이터 도구 + CSV**
  - 신규 `tools/subway-platform-type/fetch_platform_type.py` (stdlib 만. xlsx 는 `zipfile` + `xml.etree` 로 읽는다 — openpyxl 추가 안 함)
    - 사용: `KRIC_SERVICE_KEY=... python3 -I tools/subway-platform-type/fetch_platform_type.py <역코드.xlsx> > src/main/resources/subway/platform-type.csv`
    - 또는 `--responses <dump.json>` 으로 이미 받은 응답에서 재생성(키 없이). `--dump <path>` 로 응답 저장.
    - 노선 목록 = xlsx 의 (opr, ln_cd) distinct. 노선마다 1회, 1초 간격.
    - 역 판정: 그 역 응답 행들의 `plfTpNm` 집합이 `{섬식}` → `ISLAND`, `{상대식}` → `SIDE`, 그 밖(복합·혼재·null) → `OTHER`.
    - 이름 = xlsx `STIN_NM`(원문 그대로, 정규화는 Java 가 한다). xlsx 에 없는 코드(신분당·부산 코드 불일치)는 버리고 stderr 에 노선별 개수.
    - 출력: `# source=KRIC convenientInfo/stPlf fetched=<YYYY-MM-DD> codes=<xlsx 파일명>` + 헤더 `opr,ln_cd,stin_cd,stin_nm,platform_type`, (opr, ln_cd, stin_cd) 정렬.
    - 키는 출력·로그 금지(fast-alight 도구와 같은 규칙, `tools/subway-fast-alight/fetch_fast_alight.py` 참고).
  - 신규 `src/main/resources/subway/platform-type.csv` (스크래치 `kric-dl/stplf.json` 덤프로 생성)
  - 검증: CSV 행 수·노선별 ISLAND/SIDE 개수 출력, fixture 696역 매칭 수가 실측(§0)과 같은지.

- [x] **S2 `SubwayDoorSideIndex`** — 신규 `src/main/java/watoo/grd/nextroute/application/subway/service/SubwayDoorSideIndex.java`
  - `SubwayFastAlightIndex`(같은 패키지) 형식 그대로: `@Component`, 클래스패스 CSV 지연 적재(`volatile` + `synchronized`), 적재 실패 시 경고 로그 + 빈 목록, `static of(List<Row>)` 팩토리.
  ```java
  public enum Side { LEFT, RIGHT }
  public enum PlatformType { ISLAND, SIDE, OTHER }
  public record Row(String opr, String lnCd, String station /*정규화된 이름*/, PlatformType type) {}
  /** 내부 line_id 의 KRIC 구간 하나. leftHand=좌측통행 */
  record Section(String opr, String lnCd, boolean leftHand) {}
  static final Map<String, List<Section>> SECTIONS = Map.ofEntries(...);
  public Optional<Side> lookup(String lineId, String alightStation);
  ```
  - `SECTIONS`(노선마다 출처 주석 한 줄):
    - 1001: S1|1 좌, KR|1 좌
    - 1002: S1|2 우
    - 1003: S1|3 우, KR|3 우(일산선 — 3호선 직결로 서울 방식)
    - 1004: S1|4 우, KR|4 좌(과천·안산선), NU|4 우(진접선 연장, 현재 응답 0)
    - 1005: S1|5 우 / 1006: S1|6 우 / 1009: S9|9 우
    - 1007: S1|7 우, IC|7 우(인천 연장)
    - 1008: S1|8 우, GU|8 우, NU|8 우(별내 연장)
    - 1063: KR|K4 좌 / 1075: KR|K1 좌(분당선 확인 + 수인선 직결) / 1093: SW|WS 좌, KR|WS 좌
    - 넣지 않음(→ empty): 1032 GTX-A, 1065 공항, 1067 경춘, 1077 신분당, 1081 경강, 1092 우이신설, 1094 신림
  - `lookup`: 이름 정규화 `SubwayPatternStationMatcher.displayName`(끝 괄호·"역"·"행"·공백 제거). 그 line_id 의 Section 들에서 이름이 같은 Row 를 모은다.
    **정확히 1개**이고 type 이 ISLAND/SIDE 일 때만 값. `ISLAND` + 우측 = LEFT, `SIDE` + 우측 = RIGHT, 좌측통행이면 뒤집는다.
  - 검증: `./gradlew compileJava`

- [x] **S3 노선 끝 역 판정** — 신규 `src/main/java/watoo/grd/nextroute/application/subway/service/SubwayLineEnds.java`
  ```java
  @Component @RequiredArgsConstructor
  public class SubwayLineEnds {
      private final SubwayLinePatternService patternService;
      /** empty = 모름(패턴 캐시 빔). true = 노선 끝 역 */
      public Optional<Boolean> isLineEnd(String lineId, String stationName);
      static Optional<Boolean> isLineEnd(List<List<String>> patternStops, String stationName); // 순수 함수, 테스트용
  }
  ```
  - 원천: `SubwayLinePatternService.cachedPatterns(lineId, direction)`(`SubwayLinePatternService.java:87`, OTP 호출 없음). 방향 = `SubwayLinePatternWarmup.directions(lineId)`(2호선만 내·외선).
    api 는 부팅 시 `SubwayLinePatternWarmup`(@Profile("!worker"))이 전 노선을 데운다.
  - 규칙: 양 방향 모든 패턴의 정차 이름(정규화)에서, 그 역이 **어떤 패턴의 첫/끝**이고 **어느 패턴에서도 중간 정차가 아니면** 끝 역.
    중간 회차역(4호선 사당 등)은 다른 패턴의 중간 정차라 끝이 아니다. 패턴이 하나도 없으면 empty.
  - 이유: 종착역은 도착 선로가 번갈아 바뀌어 같은 승강장이라도 문 방향이 뒤집힌다.
  - `DirectedRailPattern`/`RailPattern.Stop` 의 이름 접근자는 구현 시 확인.

- [x] **S4 도메인·마이그레이션**
  - `src/main/java/watoo/grd/nextroute/domain/journey/entity/JourneyLegGuidance.java`: 7번째 값 `String alightDoorSide`.
    - 기존 6인자 생성자를 보조 생성자로 유지(alightDoorSide=null) — 호출부·테스트 변경 최소.
    - `EMPTY`·`withStopNos`·`withExits`·`withDirectionName` 은 값 유지, `withAlightDoorSide(String)` 추가.
    - `withEndFrom`(현재 43-47): 하차 쪽 값이므로 `o.alightDoorSide` 로 같이 교체.
  - `src/main/java/watoo/grd/nextroute/domain/journey/entity/JourneyLeg.java`: 컬럼 `@Column(name = "alight_door_side", length = 8) private String alightDoorSide;`(현재 guidance 컬럼 145-162 옆),
    `getGuidance()`(190-192)·`applyGuidance()`(194-202)에 반영. `copyToRevision`(232)·`copyToRevisionTruncated`(277)는 guidance 통째 전달이라 추가 변경 없음.
  - 신규 `src/main/resources/db/migration/V65__journey_leg_alight_door_side.sql`:
    ```sql
    -- #335 지하철 내리는 문 방향(LEFT/RIGHT). 여정을 만들 때 한 번 채운다. null = 모름 → 앱이 줄을 숨긴다.
    ALTER TABLE journey_leg ADD COLUMN alight_door_side varchar(8);
    ```
  - IT 13개(`grep -rln V63__journey_leg_guidance_info src/test`)의 `@Sql` 마이그레이션 목록에 V65 추가 — IT 는 Flyway 가 아니라 파일 나열로 스키마를 만든다.
  - 검증: `./gradlew compileJava`

- [x] **S5 채우기·내보내기**
  - `src/main/java/watoo/grd/nextroute/application/journey/service/JourneyGuidanceInfoEnricher.java`
    - 생성자 의존성 추가: `SubwayDoorSideIndex doorSideIndex`, `SubwayLineEnds lineEnds`.
    - `enrich`(44-46) 끝에 `withDoorSide(...)`. 구조는 `withFastAlight`(52-94)와 같다: RIDE_SUBWAY 마다 짝 대기 단계(`pairedLegIndex`)의 routeOptions `routeId` 중 **하나로 정해지는** 내부 line_id
      (빠른 하차의 `.distinct().reduce((a,b)->-1)` 규칙과 같게, 단 1001~1008 제한 없이 문자열 line_id), `ride.endName()`.
    - `lineEnds.isLineEnd(line, endName)` 가 `Optional.of(false)` 일 때만 `doorSideIndex.lookup` 결과를 넣는다. 예외는 경고 로그 후 원래 plan.
  - `src/main/java/watoo/grd/nextroute/application/journey/dto/JourneyLegView.java`: 마지막 컴포넌트로 `String alightDoorSide` 추가, 옛 계약 생성자(47-54)는 null 전달. javadoc 에 뜻 한 줄.
  - `src/main/java/watoo/grd/nextroute/application/journey/service/JourneyQueryService.java`(117-137): `guidance.alightDoorSide()` 매핑.
  - 생성자 직접 부르는 테스트 갱신: `JourneyStartServiceTest:70`, `TrackJourneyStartServiceTest:92`, `JourneyRevisionComposerTest:46`, `JourneyGuidanceInfoEnricherTest:23-42`. JourneyLegView 전체 인자 생성자 쓰는 테스트(`JourneyControllerTest:109`, `JourneySnapshotEtaComposerTest`, `JourneyStateMachineTest`) 컴파일 맞춤.
  - 검증: `./gradlew compileJava compileTestJava`

- [x] **S6 테스트** (계약당 1개+)
  - 신규 `SubwayDoorSideIndexTest`: 섬식×우측=LEFT, 상대식×우측=RIGHT, 섬식×좌측=RIGHT, 상대식×좌측=LEFT, OTHER→empty, 표 밖 노선(1065)→empty, 괄호 이름(`양재(서초구청)`) 정규화,
    4호선 운영기관 경계(번들 CSV: S1 남태령 = 우측 기준, KR 선바위 = 좌측 기준), 같은 이름 2건 → empty, 번들 CSV 적재 + 노선별 하한(예: 1002 ≥ 45, 1001 ≥ 80 판정 가능 역 — 재생성 회귀 방지).
  - 신규 `SubwayLineEndsTest`: 순수 함수로 — 끝 역 true, 중간 회차역(다른 패턴의 중간) false, 패턴 없음 empty. 가능하면 `routes-with-patterns.json` fixture 에서 4호선 패턴을 읽어 실데이터 확인.
  - `JourneyGuidanceInfoEnricherTest`: 탑승 단계에 값, 노선 끝 하차 → null, 끝 판정 모름 → null, 노선 모름 → null.
  - `JourneyLegGuidanceCopyTest`: `copyToRevision` 유지, 자르기(`withEndFrom`) 시 새 하차점 값으로 교체.
  - `JourneyControllerTest`: `$.legs[0].alightDoorSide` jsonPath.
  - 검증: `./gradlew test --tests '*SubwayDoorSideIndex*' --tests '*SubwayLineEnds*' --tests '*JourneyGuidanceInfoEnricher*' --tests '*JourneyLegGuidance*' --tests '*JourneyController*'` → 이후 `--tests '*Journey*' --tests '*Subway*'` 묶음. 기존부터 깨진 실패는 "기존 무관" 라벨.

- [x] **S7 문서·기록**
  - `docs/plan/guidance-stop-info/HANDOFF.md`: 3줄의 "내리는 문 방향은 #335(데이터 대기)" 를 지우고 표에 `alightDoorSide | RIDE_SUBWAY | 내리는 문 LEFT/RIGHT — #335 | 내리실 문: 왼쪽` 행 + 범위·null 조건 bullet.
  - 루트 `implementation_note.md`(공유 작업트리 쪽이 정본, append-only): `### 검증`(명령·결과·커버리지 수치), `### 배포`(**V65 VPS 수동배치 필요**, api·worker 같은 jar).
  - 이슈 #335 코멘트: 할 일 1·2·4 결과, 3(실탑승 역 10곳 대조)은 배포 후 사용자 체크리스트.

- [ ] **S8 PR**: 브랜치 `feat/subway-alight-door-side`(fresh main f15b9e61, 스크래치 clone), staged 검증(`git diff --cached --name-only`) 후 커밋·푸시·PR. 머지·배포는 별도 지시.

## 3. 참고 좌표
- 선례: `application/subway/service/SubwayFastAlightIndex.java`(CSV 적재·조회), `JourneyGuidanceInfoEnricher.withFastAlight`(52-94), `JourneyQueryService.fastAlight`(140-151)
- 저장 흐름: `JourneyStartTxService.java:167`, `RerouteAcceptTxService.java:369,392,418-421`
- 내부 line_id 표: `SubwayRoutePatternAssembler.LINE_IDS`(47-67)
- 이름 정규화: `DestinationNormalizer.normalize`(공백·끝 괄호·끝 행/역 제거) via `SubwayPatternStationMatcher.displayName`

## 4. 범위 밖
- 미확인 노선(공항·GTX-A·신분당·경춘·경강·우이신설·신림)의 통행 방향 확정 → 확인되면 `SECTIONS` 한 줄 추가로 끝
- 복합 승강장(1섬1상대·쌍섬식 등) 방향별 판정, 수인분당·경춘 형식 null 메우기(OSM 승강장 위치 판정은 미검증 대안)
- 부산·대구 등 지방(내부 line_id 없음), 인천1·2·의정부·에버라인·김포골드(내부 line_id 없음)
