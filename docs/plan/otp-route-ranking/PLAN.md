# OTP 경로 후보 정렬 — 버스가 지하철을 묻는 문제 (M 티어, PR 1개)

상태: 승인됨·구현 완료(2026-10-12). 배포 대기. 실측 근거는 `implementation_note.md` "OTP 경로 후보 순서: 버스가 지하철을 밀어내는 원인 (2026-10-12)".
경로 접두사 `B` = `src/main/java/watoo/grd/nextroute`, `T` = `src/test/java/watoo/grd/nextroute`.

## 배경 (실측으로 확정된 원인)

1. **정렬 구조.** OTP depart-after 기본 정렬은 거리전용 → **도착시각** → 일반화비용 → 환승수
   (`OpenTripPlanner/.../filterchain/framework/sort/SortOrderComparator.java` `STREET_AND_ARRIVAL_TIME`).
   searchWindow 를 안 주면 동적 1h10m~2h10m 창 → 결과 = 창 안 출발편들의 도착시각 타임라인.
   앱은 `first=5` 를 그대로 노출(`B/application/route/service/RouteSearchService.java:196`).
   GTFS GraphQL `planConnection` 과 router-config 에는 정렬 옵션이 없다 → **앱 코드에서** 고친다.
   강남→홍대(2026-10-12 13:30): 현행 top5 에 지하철 0개, 740 이 출발시각만 바꿔 반복.
2. **버스 탑승시간 데이터 낙관.** 같은 구간 ODsay 대비 버스만 10~30% 짧다(146 노원→강남 49 vs 69분),
   지하철은 ±2분. 네이버: 740 강남→홍대 총 57분(OTP 40분). 어떤 정렬이든 버스가 유리 → 요청에 버스 reluctance 로 보정.

옛 `docs/plan/otp-route-ranking/TODO.md`(공유 트리 미추적)의 "소요→도보→환승" 규칙은 **기각**:
같은 결과에 적용하니 강남→홍대·신촌→강남에서 지하철이 top5 밖으로 밀렸다(버스 소요가 짧게 나오고 1시간 뒤 출발편이 올라옴).

## 결정 (사용자, 2026-10-12)

- 정렬 = OTP `generalizedCost` 오름차순 + 대중교통 지문 중복 제거
- 정적 재정렬 허용. 실시간 기반 강등·MISSED 숨김 금지는 유지
- `searchWindow` = 30분, 버스 reluctance = 1.3
- 데이터 문제는 별건: #385(7호선 하행 누락), #386(버스 시간표 보정)

실험(운영 OTP, 위 조합): 강남→홍대·신촌→강남 2호선 1위, 수원→강남 수인분당→신분당 1위. OTP 응답 1.36~1.74s → 0.66~0.86s.

## 범위 밖

- GTFS·그래프 재빌드(#385, #386)
- 서울역→잠실 4호선→2호선이 혼합 탐색 Raptor 단계에서 빠지는 문제(원인 미확정, 노트 `### 미결`)
- 도보 폴리라인 디코드, ODsay 경로, 재탐색의 자체 정렬 규칙(`RerouteRanking`: 목적지 ETA 순)

## 작업 순서

- [x] **1. 쿼리** — `src/main/resources/otp/planConnection.graphql`
  - 변수 추가: `$searchWindow: Duration`, `$modes: PlanModesInput`
  - `planConnection(... searchWindow: $searchWindow, modes: $modes)` (null 변수 = 미지정과 같음 → OTP 기본)
  - node 필드에 `generalizedCost` 추가
- [x] **2. 설정** — `B/application/route/config/OtpRoutingProperties.java` (`first` L17)
  - `first` 기본 5 → **20**, javadoc "OTP 에 요청하는 후보 수(노출 수 아님)"
  - 신규 필드(+getter/setter, 기존 스타일):
    - `int displayLimit = 5` — 응답 노출 상한
    - `Duration searchWindow = Duration.ofMinutes(30)` — null 이면 변수 미전송(OTP 동적 창)
    - `double busReluctance = 1.3` — 1.0 이면 `modes` 미전송(옛 요청과 바이트 단위 동일)
    - `boolean rankingEnabled = true` — env 킬스위치(`ROUTING_OTP_RANKING_ENABLED`)
- [x] **3. 어댑터 요청** — `B/infrastructure/adapter/out/api/otp/OtpRoutingAdapter.java` `requestBody` (L114~)
  - `searchWindow != null` → `vars.put("searchWindow", searchWindow.toString())` ("PT30M")
  - `busReluctance != 1.0` → `vars.put("modes", Map.of("transit", Map.of("transit", modeList)))`
    - `modeList` = OTP `TransitMode` 전 값 순서대로 `{mode: X}`, BUS 만 `{mode: "BUS", cost: {reluctance: r}}`
    - 전 값: AIRPLANE, BUS, CABLE_CAR, CARPOOL, COACH, FERRY, FUNICULAR, GONDOLA, MONORAIL, RAIL, SNOW_AND_ICE, SUBWAY, TAXI, TRAM, TROLLEYBUS
      (포크 `schema.graphqls` `enum TransitMode`). **목록을 주면 그 모드만 탐색**한다(`ModePreferencesMapper`: transit 미지정일 때만 `NarrowedTransitMode.all()`) → 전부 나열해야 탐색 범위 유지
    - 상수로 두고 javadoc 에 위 이유 기록
  - **창 폴백(사용자 결정 2026-10-12, 구현 후 추가)**: 고정 창 응답이 `routingErrors` `NO_TRANSIT_CONNECTION_IN_SEARCH_WINDOW` 면
    창을 빼고(OTP 자동 창) 같은 예산으로 1회 재검색. 실측: 홍천→인제 14:00 30분 창 0건 → 자동 창 2건(14:40·14:50 시외버스).
    예산이 바닥나면 TIMEOUT(창 밖 경로 유무를 모른 채 "경로 없음" 금지). 다른 무경로 코드·창 미지정이면 재검색 안 함
- [x] **4. 모델** — `B/application/route/routing/RoutingItinerary.java`
  - 마지막 컴포넌트로 `Integer generalizedCost` 추가(nullable. ODsay=null). javadoc `@param`
  - 기존 9인자 생성자를 하위호환 생성자로 유지(→ `generalizedCost=null`). `RoutingLeg` 의 하위호환 생성자 관례와 같음
  - `withLegs` 가 새 칸 보존
- [x] **5. 매퍼** — `B/infrastructure/adapter/out/api/otp/OtpPlanConnectionMapper.java` `toItinerary` (L94~124)
  - `node.path("generalizedCost")` 가 숫자면 int, 아니면 null → 새 생성자로 전달. 순서 보존 그대로
- [x] **6. 정렬기(신규)** — `B/application/route/routing/ItineraryRanker.java`
  ```java
  /** 엔진 순서 후보를 정적 일반화비용 순으로 줄 세우고 같은 대중교통 지문은 하나만 남긴다. 순수 계산. */
  public final class ItineraryRanker {
      public static List<RoutingItinerary> rank(List<RoutingItinerary> engineOrder, int limit)
  }
  ```
  1. 지문 = 대중교통 leg(도보 제외) 순서대로 `mode + "|" + (sourceRouteName != null ? sourceRouteName : sourceRouteId)` 의 리스트. 도보만 = 빈 리스트(한 그룹)
     - ~~route id 기준~~ **이름 기준으로 변경(2026-10-12 배포 후, 사용자 중복 보고)**: 응답엔 이름만 나가서 1호선 계통·급행처럼 id 만 다른 것도 사용자에겐 같은 경로
  2. 그룹 대표 = `staticArrivalAt` 이른 것(null 뒤) → gc 낮은 것(null 뒤) → 엔진 인덱스
  3. 대표 정렬 = gc 오름차순(null 뒤) → 엔진 인덱스
  4. ~~대표 수 < limit 이면 엔진 순서로 채움~~ **폐기(2026-10-12)**: 운영에서 부평→서울 1호선이 출발시각만 달리 3번, 노원→강남 146 이 4번 나와 사용자가 중복으로 보고. 대표만 내보냄(고유 1개면 1개)
  5. limit ≤ 0 이면 빈 리스트, 입력 비면 빈 리스트
  - 도보 모드 판별은 `RoutingMode` enum 의 도보 값 사용(구현 시 확인)
  - 재사용 불가 확인: `B/application/journey/service/RerouteRouteFingerprint.java:24`(legacy `PathResult` 입력), `RerouteRanking.java:32`(cost 미사용)
- [x] **7. 검색 적용** — `B/application/route/service/RouteSearchService.java` `runOtpSearch` (L174~, 매핑 L196)
  - `toLegacy` 전에: `rankingEnabled ? ItineraryRanker.rank(its, displayLimit) : its 앞 displayLimit 개` → `result.withItineraries(...)`
  - `OtpRoutingProperties` 주입(생성자). L202 주석 "재정렬·강등·MISSED 숨김은 하지 않는다" → "정적 일반화비용 재정렬·중복 제거만(OTP 기본 순서는 도착시각 타임라인이라 버스가 상위를 채움). 실시간 기반 강등·MISSED 숨김은 하지 않는다"
- [x] **8. 재탐색 적용** — `B/application/journey/service/RemainingRouteSearchService.java` `loadOtp` (L140~152)
  - 엔진 순서 앞 `displayLimit` 개로 **자르기만**(후보 5개 유지 = 실시간 평가 비용 동일). 재탐색은 자체 ETA 정렬이 있다
  - 같은 쿼리를 써서 창 30분·버스 1.3 은 재탐색에도 적용된다(경계 리뷰 항목)
- [x] **9. 테스트** (아래 표)
- [ ] **10. 운영 OTP 쿼리 검증 → 노트 기록 → PR**

## 테스트 (계약당 최소 1개)

| 계약 | 테스트 |
|---|---|
| gc 오름차순, 동률은 엔진 순서 | `T/application/route/routing/ItineraryRankerTest`(신규) |
| 같은 지문 = 가장 이른 도착 1개 | 〃 |
| 이름 같고 route id 다르면 별개 | 〃 |
| 고유 < limit → 엔진 순서로 채움 / limit 상한 / 빈 입력 / gc null 뒤 | 〃 |
| 6번째 이후 후보가 상위로 올라옴 | 〃 |
| generalizedCost 파싱, 없으면 null | `T/infrastructure/adapter/out/api/otp/OtpPlanConnectionMapperTest`(inline JSON) |
| 요청 body: first=20, searchWindow "PT30M", BUS reluctance 1.3 + 전체 모드 | `T/infrastructure/adapter/out/api/otp/OtpRoutingAdapterHttpTest`(MockWebServer) |
| reluctance 1.0 → modes 없음 / searchWindow null → 변수 없음 | 〃 |
| 창 안 연결 없음 → 창 빼고 1회 재검색 / 다른 무경로·창 미지정은 재검색 안 함 | 〃 |
| 검색: 후보 7개 → 응답 ≤5, 뒤쪽 저비용 후보가 앞으로 / rankingEnabled=false → 엔진 앞 5개 | `T/application/route/service/RouteSearchServiceOtpWiringTest` |
| 재탐색: 후보 7개 → 엔진 순서 앞 5개 | `T/application/journey/service/RemainingRouteSearchServiceOtpTest` |

기존 테스트 스타일: Mockito + 손으로 만든 `RoutingItinerary`(`RouteSearchServiceOtpWiringTest` L101 부근), MockWebServer + `src/test/resources/otp/planconnection-success.json`.

## 검증

1. `./gradlew compileJava compileTestJava`
2. `./gradlew test --tests '*ItineraryRanker*' --tests '*OtpRoutingAdapter*' --tests '*OtpPlanConnectionMapper*' --tests '*RouteSearchService*' --tests '*RemainingRouteSearch*' --tests '*RoutingModel*' --tests '*LegacyRouteResponseMapper*'`
   - 결과를 노트 `### 검증` 에. 원래부터 깨져 있던 실패는 "기존 무관"
3. 운영 OTP 쿼리 검증(읽기 전용, **SSH 1세션**에 요청 파일 묶어 curl 순차): 새 `planConnection.graphql` + 어댑터와 같은 변수로 6개 OD
   (강남→홍대, 신촌→강남, 서울역→잠실, 노원→강남, 수원→강남, 부평→서울)
   - GraphQL 오류 없음(전체 모드 나열 포함)
   - 지연·응답 바이트: 현행(`first=5`, 창 미지정) vs 신규(`first=20`, `PT30M`, BUS 1.3). 응답이 과하면 first 10~15
   - 정렬기 로직으로 top5 재현 → 노트 실험표와 일치

## 배포 (사용자 확인 후)

- 마이그레이션 없음. api·worker 둘 다(재탐색이 worker 에서도 돎)
- 운영 검색 API 로 6개 OD 호출 → 강남→홍대·신촌→강남 2호선 1위 확인
- 되돌리기: env `ROUTING_OTP_RANKING_ENABLED=false`, `ROUTING_OTP_BUS_RELUCTANCE=1.0`, `ROUTING_OTP_SEARCH_WINDOW=`(빈 값), `ROUTING_OTP_FIRST=5` (relaxed binding) + api·worker 재생성
