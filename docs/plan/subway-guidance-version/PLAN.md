# 지하철 링 버전(guidanceVersion) + 전역 출발 진행률 되감김 수정 — PLAN

이슈: #307 (조건부 조회가 링 변경을 못 잡음), #306 후속(앱이 옛 스냅샷을 가릴 값 없음), 조사 중 발견한 진행률 되감김.
티어 M. 브랜치 `feat/subway-guidance-version` (fresh main f2692354). 마이그레이션 없음.

## 결론
1. 링 화면이 **눈에 띄게 바뀔 때만** 오르는 단조 값 `guidanceVersion`(epoch millis)을 링 상태에 저장하고 스냅샷에 싣는다.
2. `GET /api/journeys/{id}` 에 `afterGuidanceVersion` 을 받는다. 세 값이 모두 "안 바뀜"일 때만 204. 안 보내면 지금 그대로(하위 호환).
3. 앱은 받은 스냅샷의 `guidanceVersion` 이 들고 있는 값보다 작으면 버린다(#306 옛 응답 덮어쓰기 방지). 단조 증가라 가능하다.
4. 계산기 버그 수정: 도착 API `arvlCd 3/4/5`(전역 출발·진입·도착)가 **같은 구간에서 반복될 때마다 진행률을 0 으로 재앵커** → 앱 링이 15~30초마다 0 으로 되감긴다. 같은 구간이면 `reanchored`(지금 화면 값을 base 로 이어 그림), ETA 없으면 freeze.

## 결정
- **버전 = 화면 변경 시각(millis)**, 카운터 아님. 링 키가 고정 세대(bindingVersion·routeRevision)마다 바뀌어 카운터는 재탐색·계승 때 0 으로 돌아간다. 시각은 세대가 바뀌어도 단조. 같은 millis 충돌은 `max(now, prev+1)`.
- **"화면 변경" 판정 = `SubwayRingPushDecider.decide(previous, current).isPresent()`** — push 와 같은 기준(다음 역·phase·quality·도착·앵커 지문[진행 0.05 눈금, ETA 30초 눈금]). 매 tick(5초) 오르면 204 가 무의미해진다.
- **ETA(`etaComposer`)는 버전에 안 넣는다** — 조회 시점 계산값이라 저장 지점이 없다. 링만.
- **링이 없으면 `guidanceVersion = 0`**(지하철 아님·pending 포함). 링이 생기는 순간 >0 이 되어 200.
- 비교는 기존 두 버전과 같은 `<=`. 링이 사라져 0 이 되어도 204(단계 전이는 commandVersion 이 잡는다).
- 첫 링을 train-selection 응답에 싣는 안은 **안 한다**(#304 노트: api 가 링을 먼저 만들면 worker 가 첫 push 를 놓친다). 대신 버전 조건으로 다음 15초 조회에 잡힌다.
- 이벤트 CAS 의미가 있는 `evaluationVersion` 은 건드리지 않는다(이슈의 기각안).

## 변경 파일
### 링 상태·버전
- `application/journey/dto/SubwayRingState.java` — 마지막 component `long guidanceVersion` 추가. 기존 18인자 생성자 보존(=0). `withGuidanceVersion(long)` 추가. Redis 옛 값은 필드가 없어 0 으로 읽힌다.
- `application/journey/service/SubwayRingUpdateService.java` (`processOne`, 현재 :105~) — `calculator.next` 뒤 `state = state.withGuidanceVersion(versionOf(previous, state, now))`, 그 다음 저장. 규칙: `previous == null || previous.guidanceVersion() == 0 || decider.decide(previous, state).isPresent()` → `max(now.toEpochMilli(), previous==null?0:previous.guidanceVersion()+1)`, 아니면 `previous.guidanceVersion()`. 계산기는 순수 함수 그대로 둔다.
  - 주의: `calculator.next` 가 `prior` 를 그대로 돌려주는 경로(하차역 도착 완료)도 version 은 유지된다.
- `application/journey/service/SubwayRingPushDecider.java` — 변경 없음(재사용). 단 지문(`signatureOf`)에 version 을 넣지 않는다.

### 스냅샷·API
- `application/journey/dto/SubwayGuidance.java` — 마지막 component `Long version` 추가. `pending(...)` = null, `of(..., ring)` = `ring.guidanceVersion()`.
- `application/journey/dto/JourneySnapshot.java` — component 추가 없이 `@JsonProperty("guidanceVersion") public long guidanceVersion()` = `subwayGuidance == null || version == null ? 0 : version`. (레코드 생성자·`withEta`·`of` 시그니처 불변 → 기존 시험 무변경.)
- `infrastructure/adapter/in/api/journey/JourneyController.java` (`detail` :86~, `unchanged` :102~) — `@RequestParam(required=false) Long afterGuidanceVersion` 추가. `unchanged`: 셋 다 null 이면 false, 보낸 값만 `<=` 비교, 전부 참일 때 true. javadoc 갱신.

### 계산기 버그
- `application/journey/service/SubwayRingCalculator.java` `fromArrival` 의 `case PREV_DEPARTED, PREV_ENTERING, PREV_ARRIVED`:
  - `sameSegment(prior, target)` 이고 ETA 있음 → `reanchored(prior, context, target, observation, now)`
  - `sameSegment` 이고 ETA 없음 → `freeze(prior, context, target, now)`
  - 처음 보는 구간 → 기존 0 앵커(ETA 없으면 STALE)
  - javadoc 표의 `3/4/5 전역` 행을 "구간 첫 관측은 0 앵커, 이후는 이어 그림"으로 고친다.

## 순서
- [x] 1. 계산기 수정 + `SubwayRingCalculatorTest` 케이스: 같은 구간 PREV_DEPARTED 연속 → 진행률이 줄지 않고 경과만큼 오름 / ETA 없는 반복 → 직전 값 freeze / 첫 관측 → 0
- [x] 2. `SubwayRingState.guidanceVersion` + 갱신기 버전 규칙 + 시험(`SubwayRingIT`: 첫 저장 >0, 같은 화면 tick → 같은 값, 다음 역 변경 → 증가)
- [x] 3. `SubwayGuidance.version` / `JourneySnapshot.guidanceVersion()` + 컨트롤러 파라미터 + 시험(컨트롤러 시험: guidance 같으면 204, 다르면 200, 파라미터 없으면 기존 규칙; JSON 에 `guidanceVersion`·`subwayGuidance.version` 노출)
- [x] 4. 모바일 인계 `HANDOFF.md`(이 폴더): 파라미터·필드·옛 응답 버리기 규칙·임시 조치 철거
- [x] 5. 전체 `./gradlew test --continue`(일회용 DB·Redis·더미 env), 노트 `### 검증`

## 검증 명령
- `./gradlew test --tests '*SubwayRingCalculatorTest' --tests '*SubwayRingIT' --tests '*JourneyController*Test' --tests '*JourneySubwayGuidance*'`
- 전체: `POSTGRES_PORT=<일회용> REDIS_PORT=<일회용> GONG_API=dummy KAKAO_REST_API_KEY=dummy SEOUL_BUS_USAGE=dummy SEOUL_SUBWAY_MASTER=dummy SEOUL_SUBWAY_ROUTE=dummy ./gradlew test --continue` — 기존 무관 실패: JourneyEvalReadPathIT·JourneyEvaluationIT·JourneyTransferAssessmentIT·RerouteRemainingPathOtpIT(+간헐 JourneyNotificationSchemaIT)

## 배포
마이그레이션 없음. api(스냅샷·파라미터)·worker(버전 저장·계산기) 둘 다. worker 먼저 → api(api 가 먼저면 버전 0 만 보임, 무해).
