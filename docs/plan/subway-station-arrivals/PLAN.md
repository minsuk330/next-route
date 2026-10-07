# PLAN — 역 ID 로 묻는 지하철 도착 API (#277, M 티어)

> 실행용. SUMMARY.md 는 이 문서의 파생물이다.

## 배경
- 모바일 홈 "근처에서 타기"·역 시트는 `GET /api/nearby/subway-stations` 로 서울 실시간 역 ID(`stationId: "1002000222"`)와 `lineId` 를 이미 받는다.
- 도착은 좌표 API `GET /api/arrivals/subway?lat&lon&line_id` 로 다시 묻는다 → 좌표→역 재해석에서 환승역 노선이 바뀌는 버그(#276, PR #280).
- 역 ID 를 아는 호출자용 API 를 둔다. 앱이 하던 방향 묶기·라벨(`nextroute-mobile/src/features/ride-tracking/ride-tracking-model.ts:67` `groupTrainsByDirection`, `:106` `directionLabel`)을 서버로 옮긴다.
- 범위 밖: `departed`(방금 떠난 열차) → #265. 경로 검색·안내는 역 ID 가 없어 좌표 API 유지.

## 계약
`GET /api/subway/stations/{stationId}/arrivals` — 공개(SecurityConfig `anyRequest().permitAll()`, 설정 변경 없음)

```jsonc
{
  "stationId": "1002000222",
  "lineId": "1002",
  "directions": [
    {
      "direction": "내선",          // updnLine 원문. 비어 있으면 null
      "label": "내선",              // 표시용
      "trains": [
        { "trainNo": "2231", "destinationName": "성수", "trainType": "일반",
          "arrivalSeconds": 96,      // null 가능
          "stationsAway": 1,         // null 가능
          "currentMessage": "전역 출발" }
      ]
    }
  ]
}
```
- 없는 stationId → `404`, 본문 code `SUBWAY_STATION_NOT_FOUND`.
- 운행 정보 없음(캐시 비었음 포함) → `200` + `directions: []`.

## 규칙
1. 열차 = `SubwayRealtimeArrivalReader.arrivals(lineId, stationId, null)` (`application/subway/service/SubwayRealtimeArrivalReader.java:53`). 캐시 snapshot 이라 키 소비 0. 지나간 열차 제외·중복 제거·도착 빠른 순(초 없는 열차 뒤)은 reader 가 이미 한다 — 다시 정렬하지 않는다.
2. 방향 묶기: `train.direction` trim. 빈 값 → `direction=null`, 순서 맨 뒤. label 은 3번 규칙 그대로(행선지 우선), 행선지도 없으면 `"방향 정보 없음"` — 앱 기존 동작과 같다.
3. label = 그 방향 열차 행선지(trim, 빈 값 제외) 최빈값 + `" 방면"`. 동률이면 먼저 나온(=더 빨리 오는) 열차 행선지. 행선지가 전부 비면 `direction` 값(그것도 null 이면 `"방향 정보 없음"`).
4. label 이 같은 방향이 둘 이상이면(2호선 내선·외선 → "성수 방면") 그 방향들 label = `direction` 값.
5. 방향 순서 = `direction` 한국어 사전순 `Collator.getInstance(Locale.KOREAN)`, null 은 끝. 앱 `localeCompare('ko')` 와 같다.
6. `stationsAway` = `SubwayStationsAway.resolve(SubwayArrivalCode.from(arrivalCode), currentMessage)` (`SubwayArrivalQueryService.java` `stationsAway` 와 같은 값).
7. 역 조회 = `SubwayStationRepository.findByStationId` (운영 실측: statn_id 중복 0건 / 696행 19노선). `lineId` 는 그 행의 것.

## 파일 (전부 `src/main/java/watoo/grd/nextroute/` 아래)
- `global/error/code/SubwayErrorCode.java` (신규, `JourneyErrorCode` 패턴)
  ```java
  SUBWAY_STATION_NOT_FOUND("SUBWAY_STATION_NOT_FOUND", "지하철역을 찾을 수 없습니다.", HttpStatus.NOT_FOUND)
  ```
- `application/arrival/dto/SubwayStationArrivalsResponse.java` (신규)
  ```java
  public record SubwayStationArrivalsResponse(String stationId, String lineId, List<Direction> directions) {
      public record Direction(String direction, String label, List<Train> trains) {}
      public record Train(String trainNo, String destinationName, String trainType,
                          Integer arrivalSeconds, Integer stationsAway, String currentMessage) {}
  }
  ```
- `application/arrival/port/in/GetSubwayStationArrivalsUseCase.java` (신규)
  `SubwayStationArrivalsResponse getArrivals(String stationId);`
- `application/arrival/service/SubwayStationArrivalQueryService.java` (신규) — 역 조회 → 없으면 `BusinessException(SUBWAY_STATION_NOT_FOUND)` → reader → `static List<Direction> group(List<Arrival>)`.
- `infrastructure/adapter/in/api/subway/SubwayStationController.java` (신규) `@RequestMapping("/api/subway/stations")`, `@GetMapping("/{stationId}/arrivals")`.

## 작업 순서
- [ ] 1. 테스트 먼저: `SubwayStationArrivalQueryServiceTest`(mock reader·repo)
  - 방향별 묶기 + 방향 안 순서 유지(초 없는 열차 뒤)
  - label = 최빈 행선지 + " 방면", 동률은 먼저 온 열차
  - 내선·외선 모두 "성수" → label = direction
  - 방향 순서 고정(입력 순서 뒤집어도 같은 순서)
  - 빈 direction → null·맨 뒤, 행선지 있으면 그 label, 없으면 "방향 정보 없음"
  - 없는 역 → BusinessException(SUBWAY_STATION_NOT_FOUND), reader 호출 없음
  - 열차 없음 → directions []
  - stationsAway 채움(`[3]번째 전역` → 3)
- [ ] 2. 에러 코드·DTO·포트·서비스 구현 → 1 통과
- [ ] 3. `SubwayStationControllerTest`(@WebMvcTest + `addFilters=false`, `BusArrivalControllerTest` 패턴): 200 JSON 경로 / 404 code 본문. 404 본문 형식은 `GlobalExceptionHandler` 확인
- [ ] 4. 컨트롤러 구현 → 3 통과
- [ ] 5. HANDOFF.md(모바일), implementation_note.md append
- [ ] 6. 커밋·PR (본문에 #280 과 독립, Fixes #277)

## 검증
```
./gradlew test --tests '*SubwayStationArrival*' --tests '*SubwayStationController*' --tests '*SubwayArrivalQueryServiceTest'
```
배포 후: `curl <api>/api/subway/stations/1002000222/arrivals` → 내선/외선 2방향, label 이 direction 값.

## 배포
마이그레이션 없음. api 이미지 교체. 플래그 없음(새 경로라 기존 호출자 영향 0).
