# 트랙 정류장 진행 방향 (M, slug `track-stop-heading`, 이슈 #538)

## 목표
단독 추적 선택 화면 응답에 필드를 **추가만** 한다. 앱은 근처 정류장을 버스 진행 방향별로 묶고, 노선 행을
`{다음 정류장} 방면 · {종점}행` 으로 보여 준다. 필드가 없으면 앱은 지금 동작 그대로다.

1. `GET /api/journeys/track/stops/near` → `Stop.routeNames`, `Stop.heading{patternCodes, nextStopNames, bearingDegrees}`
2. `GET /api/journeys/track/stops/{gtfsStopId}/patterns` → `Direction.nextStopNames`

## 사실 (조사, 2026-11-03)
- 근처 조회: `TrackSelectionQueryService.stopsNear` (`application/journey/service/TrackSelectionQueryService.java:52`).
  OTP `stopsNear` 1회를 `source.withPermit` 안에서 부른다. 버스는 근처 결과 안의 형제 묶음(`siblingGroups` :96)을 한 줄로 낸다
- 방향 목록: 같은 클래스 `directions` :130. `TrackPatternSelector.branches` (:61)의 `Branch.remaining` = 승차 다음 정류장(한 바퀴 안)
- `PatternStop(gtfsId, name)` (`application/route/port/out/TransitPatternPort.java:68`) 에 좌표가 없다. 쿼리 `PatternsAtStop`
  (`src/main/resources/otp/trackPatterns.graphql`) 의 `stops { gtfsId name }`. 파싱 `OtpTransitPatternAdapter.java:182-184`
- `new PatternStop(` 쓰는 곳: main 2(`OtpTransitPatternAdapter`, `SubwayStopPatternResolver`), 테스트 11 → 2인자 생성자를 남겨 고치지 않는다
- `TrackViews.Stop`/`Direction` 생성은 `TrackSelectionQueryService` 에만 있다
- `patternsAtStop` 은 `TrackPatternSource` 에 그래프 버전 키로 10분 캐시돼 있다 → 따로 캐시를 두지 않는다
- 마감: `route.search.otp-ms` 8000ms, permit 하나로 묶음 전체
- **운영 실측(강남역 사거리 37.4979,127.0276, 운영 OTP)**: 버스 정류장 20묶음, `patternsAtStop` 15회 합 121ms(p50 6ms, 최대 33ms)
  → 순차 호출로 충분, 병렬화 안 함. 같은 이름 길 건너 쌍(강남역9번출구 2곳, 강남역12번출구 2곳, 강남역10번출구 2곳) 모두
  `patternCodes` 안 겹침, 잇따르는 정류장(강남역12번출구 → 강남역9번출구) 6개 겹침 — 이슈 규칙 그대로 수용 기준 충족
- 실측 주의: 같은 방향이어도 노선마다 다음 정류장이 다르면 표가 갈린다(강남역9번출구 서울 249°·272°, 경기 317° → 7/5/3)

## 사용자 결정 (2026-11-03) — 이슈 원안에서 바꾼 것
- **대표 방향 = 방위각 묶음(±60°)**, 다음 정류장 key 다수결 아님. 운영 6곳(강남·사당·신촌·시청·고속터미널·잠실) 754분기 실측:
  key 다수결 72%, ±45° 90%, ±60° 91% 가 대표에 든다. 빠지는 건 교차로에서 실제로 꺾는 노선
- **80m 보강(다음 정류장이 가까우면 remaining[1] 로 각도) 안 함**: 80m 미만 6/754(1%), 40m 미만 0. 넣어도 커버율·길 건너편
  판정 같음. 바뀐 3건(사당역3번출구 → 50m 건너 4번출구)은 오히려 그 뒤 꺾는 방향을 잡았다
- **왕복 패턴**: 서울 버스 패턴은 차고지 → 차고지 왕복 하나라 같은 코드가 길 건너편 정류장에도 나온다(고속터미널 104m 쌍 20개,
  잠실역.롯데월드 54m 쌍 15개 겹침 — 원안 규칙도 같음). 서버는 그대로 두고, **앱은 `patternCodes` 가 겹치고
  `bearingDegrees` 차이 ≤60° 일 때만 같은 섹션**으로 묶는다(이슈 댓글). 방위각은 정류장 → 다음 정류장이라 사용자 위치와 무관

## 변경

### 1. 패턴 정류장 좌표
- `trackPatterns.graphql` `PatternsAtStop`: `stops { gtfsId name lat lon }`
- `TransitPatternPort.PatternStop(String gtfsId, String name, Double lat, Double lon)` + 2인자 생성자(좌표 null)
- `OtpTransitPatternAdapter.parsePatternsAtStop`: lat/lon 이 숫자일 때만 채움

### 2. 응답 DTO (`application/journey/dto/TrackViews.java`)
- `Stop` 끝에 `List<String> routeNames, Heading heading` 추가
- `record Heading(List<String> patternCodes, List<String> nextStopNames, Double bearingDegrees)`
- `Direction` 끝에 `List<String> nextStopNames` (없으면 빈 목록)

### 3. 설정
- `JourneyTrackProperties.headingLimit = 12` — 근처 버스 묶음 중 거리순 앞 N개만 계산, 나머지 null

### 4. 순수 계산 `application/journey/service/TrackStopHeading.java` (신규)
```java
record BoardPatterns(String boardStopId, List<TransitPattern> patterns)
static Optional<TrackViews.Heading> of(TransitStop origin, List<BoardPatterns> group)
static List<String> nextStopNames(List<PatternStop> remaining)   // 앞 1~2개, 같은 이름이면 1개
static Double bearing(double lat1, double lon1, Double lat2, Double lon2) // 북 0 시계방향, 0~360, 소수 1자리
```
규칙:
1. 묶음 정류장마다 `TrackPatternSelector.branches(boardStopId, patterns)`, `UNSUPPORTED`/`WALK` 제외
2. 분기 방위각 = 승차 정류장 → `remaining[0]`. ±60° 안 이웃이 가장 많은 분기가 기준(동률 노선 이름 사전순), 기준에서 ±60° 안이 대표 방향
3. `patternCodes` = 대표 방향 분기 코드(중복 제거, 사전순). 다음 정류장 = 대표 방향 안 key(`groupKey`, 없으면 gtfsId) 다수결,
   그 key 의 (노선 이름, 코드) 첫 분기의 remaining 앞 1~2개
4. `bearingDegrees` = origin 좌표 → 그 분기 `remaining[0]` 좌표(소수 1자리). 좌표가 하나도 없으면 key 다수결로 대신하고 null
5. 분기 없음 → empty(→ `heading: null`)

### 5. `TrackSelectionQueryService`
- `stopsNear`: 같은 `withPermit` 안에서 OTP `stopsNear` 뒤 버스 묶음(형제 묶음은 대표 한 번) 거리순 앞 `headingLimit` 개만
  묶음 각 정류장 `source.patternsAtStop` → `TrackStopHeading.of`. 정류장 단위 `try/catch(RuntimeException)` → 그 정류장만
  null + DEBUG 로그. 마감 지났으면 남은 정류장은 부르지 않고 null. 목록 응답은 200 유지
- `routeNames` = 묶음 멤버 `NearStop.routeShortNames` 합집합(순서 유지, 중복 제거). 지하철 줄도 자기 `routeShortNames`
- `directions`: `Direction.nextStopNames = TrackStopHeading.nextStopNames(branch.remaining())`
- 지하철 줄 `heading` = null

## 시험
- `TrackStopHeadingTest` (단위): 방위각 다수결(다음 정류장 달라도 한 방향), 동률 → 노선 이름 사전순, 형제 정류장 합산,
  회차 정류장 다수결(꺾는 노선 제외), 왕복 패턴 길 건너편 → 같은 코드·반대 방위각, 패턴 없음/UNSUPPORTED → empty,
  좌표 없음 → key 다수결·방위각 null, nextStopNames 1개·같은 이름 1개, 방위각 값
- `TrackSelectionQueryServiceTest`: `headingLimit` 밖 → null, 한 정류장 OTP 실패 → 그 정류장만 null·목록 성공, routeNames 합집합,
  `Direction.nextStopNames` = remaining 앞 1~2개
- `OtpTransitPatternAdapter` 파싱 테스트: PatternStop 좌표
- 명령: `./gradlew test --tests '*Track*' --tests '*OtpTransitPatternAdapter*'` → 전체 `./gradlew test`(env: 노트 함정 참고)

## 배포
- api 이미지 교체만(마이그레이션 없음). 배포 직전 실여정 테스트 중인지 확인
- 확인: 운영 `stops/near?lat=37.4979&lon=127.0276` 응답에 heading, p95 변화(Prometheus `uri="/api/journeys/track/stops/near"`)

## 순서
- [ ] 1~3 DTO·포트·설정
- [ ] 4 `TrackStopHeading` + 단위 시험
- [ ] 5 서비스 + 시험
- [ ] 전체 시험, 노트 기록, PR, 머지
- [ ] (사용자 확인) api 배포, 운영 확인
