# 지하철 탑승 열차 고정·진행 링 — 사용자 시나리오 통합 테스트 (M)

## 배경
M7(탑승 열차 자동 판정 + 진행 링 + 잠금화면 push)은 프로덕션에서 켜져 있지만(2026-09-27 위치 수집 재개) **실제 여정으로 끝까지 흘러 본 적이 없다.** 기존 IT 는 전부 부품 하나의 계약만 본다:

| 기존 IT | 보는 것 | mock 으로 막은 것 |
|---|---|---|
| `SubwayBindingMatchIT` | 판정기 스캔·CAS | 전이 로그 없음(판정 입력을 직접 만듦) |
| `SubwayRingIT` | 링 갱신 스캔·STALE | `SubwayTrainLogPort`·`SubwayRealtimeCachePort` `@MockBean` |
| `SubwayRingPushIT` | push 대기열·재확인 | `LiveActivityPushPort` |
| `SubwayBindingLifecycleIT` | 여정 종료 4길 | — |

`subway_segment` 를 시드하는 IT 가 하나도 없어(`grep` 0건) **실제 인접 판정 → 출발 사건 → 출발 색인 → 고정** 경로는 통합 검증된 적이 없다. 이 PR 은 사용자 이야기 단위로 사슬 전체를 실제 Postgres·Redis 위에서 돌린다. **테스트만 추가, 운영 코드 변경 없음.**

## 설계

### 가짜는 바깥 경계에만
| 경계 | 처리 |
|---|---|
| 서울 위치 API | 가짜 `SubwayPositionFetchResult` → **실제** `SubwayTrainTransitionTracker.observe()` (`application/subway/service/SubwayTrainTransitionTracker.java:74`) → 실제 Redis `subway:pos/dep/arr:*` |
| Redis | Testcontainers `redis:7-alpine` — 운영 설정 `REDIS_HOST` 에 기본값이 없고, 기존 Subway*IT 는 Redis 포트를 mock 해 연결 자체가 없었다 |
| 서울 도착 API | 가짜 `SubwayRealtimeSnapshot` → **실제** `SubwayRealtimeCachePort.saveSnapshot(snapshot, ttl)` (`application/subway/port/out/SubwayRealtimeCachePort.java:9`) |
| 검색 결과 스냅샷 | `@MockBean RouteSearchSnapshotPort` — `SubwayBindingMatchIT:65,75` 와 같은 패턴 |
| FCM/APNs | `@MockBean LiveActivityPushPort` — 나간 payload 를 `ArgumentCaptor` 로 캡처 |
| 시각 | `@TestConfiguration` 의 `@Primary` `MutableClock` 빈. 판정·링·evidence·push 서비스가 전부 `Clock` 을 주입받는다(`common/config/ClockConfig.java:18`) |

**수집기(`RealtimeSubwayPositionCollector`)를 거치지 않는다.** 운행시간 판정 `isActiveHours()` 가 `LocalTime.now(SEOUL)`(벽시계)라 01:00~04:30 에 돌리면 조용히 no-op → 시험이 시각에 따라 흔들린다. 추적기 입력부터가 결정적이다.

**스케줄러는 띄우지 않고 tick 을 직접 부른다.** `@TestPropertySource` 로 `journey.subway.binding.enabled`·`ring.enabled`·`ring.push.enabled` 를 켜되 스케줄러 자동 실행이 끼어들지 않도록 tick 주기를 크게(`tick-ms=3600000`, `relay-interval-ms=3600000`) 둔다. 호출:
- `SubwayBindingMatchService.runOnce()` (`application/journey/service/SubwayBindingMatchService.java:87`)
- `SubwayRingUpdateService.runOnce()` (`.../SubwayRingUpdateService.java:59`)
- `SubwayRingPushRelayService.runOnce()` (`.../SubwayRingPushRelayService.java:65`)

`ring.push.enabled=true` 는 `journey.notification.sender=fcm` 이 없으면 `SubwayRingPushWiringValidator` 가 부팅을 막는다 → `LiveActivityPushPort` 를 `@MockBean` 으로 넣으면 `ObjectProvider` 가 찾으므로 통과(`SubwayRingPushIT` 가 같은 방식). 부팅 실패 시 이 조합부터 확인.

**사용자 행동은 유스케이스로 부른다.**
- 여정 시작: `StartJourneyUseCase.start(userId, new StartJourneyCommand(SESSION_ID, PATH_KEY, false))`
- 이동 시작·정차·리셋: `SubwayBindingUseCase.submit(userId, journeyId, routeRevision, legIndex, SubwayBoardingEvidence)` / `.reset(...)` / `.notBoarded(...)` (`application/journey/port/in/SubwayBindingUseCase.java:8`)
- Live Activity 토큰: `ManageLiveActivityTokenUseCase.register(userId, journeyId, activityToken, pushToken, ...)`
- 앱이 보는 화면: `JourneyQueryUseCase.get(userId, journeyId)` → `JourneySnapshot.subwayGuidance()`
- 여정 종료: `ApplyJourneyEventUseCase.apply(userId, journeyId, JourneyCommand)`

### 무대 — `SubwayScenarioWorld`
2호선(`1002`) 외선 **강남 → 역삼 → 선릉 → 삼성**. 열차 A·B·C 가 2~3분 간격. "시각 t 에 각 열차가 어느 역·어느 상태인가"를 표로 들고, 그 표에서 **위치 응답과 도착 스냅샷을 같은 원천으로** 만든다(두 피드가 서로 모순되지 않게).

```java
public final class SubwayScenarioWorld {
    public static final String LINE = "1002";
    public static final String DIRECTION = "외선";          // 도착 API 공간 표기
    public static final List<Station> STATIONS = List.of(  // statnId 는 시드와 같은 값
            new Station("1002000222", "강남"), new Station("1002000221", "역삼"),
            new Station("1002000220", "선릉"), new Station("1002000219", "삼성"));

    public record Station(String id, String name) {}
    public record TrainAt(String trainNo, Station station, SubwayTrainStatus status, Instant reportedAt) {}

    // 한 시각의 세계 상태 → 두 피드
    public SubwayPositionFetchResult positionFeed(Instant fetchedAt, List<TrainAt> trains);
    public SubwayRealtimeSnapshot arrivalFeed(Instant collectedAt, List<TrainAt> trains);

    // 시드·정리 — JdbcTemplate 로
    public void seed(JdbcTemplate jdbc);      // subway_segment(인접 3구간) + subway_station(4역)
    public void cleanUp(JdbcTemplate jdbc);   // 위에서 넣은 행만 지운다(dev DB 공유)
}
```
시드 뒤 `SubwayStationAdjacency.reload()`(`application/subway/service/SubwayStationAdjacency.java:51`) 호출. `SubwayRingStationResolver` 는 노선별 메모리 캐시(`byLine`)를 들고 있으므로 **시드가 그 노선 첫 사용보다 먼저**여야 한다 — `@BeforeAll` 성격으로 한 번. dev DB 엔 2호선이 없다(메모리 기록) → 충돌 없음, 그래도 정리는 넣은 행 id 기준으로만.

`journey_leg.start_stop_id`(강남 statnId)와 노선 후보 `wayCode` 는 정적 데이터가 꺼진 시험 환경에서 null 이다 → 여정 시작 직후 SQL 로 채운다(`SubwayBindingRerouteInheritanceIT` 와 같은 함정). 방향은 `SubwayDirectionNormalizer.fromWayCode(lineId, wayCode)` 로 도출되므로 `DIRECTION` 과 일치하는 wayCode 를 넣는다.

### `MutableClock`
```java
public final class MutableClock extends Clock {
    private volatile Instant now; private final ZoneId zone;
    public void set(Instant i); public void advance(Duration d);
    // getZone / withZone / instant 구현
}
```
`testsupport/MutableClock.java`. 시나리오마다 `@BeforeEach` 에서 고정 시각(평일 08:30 KST)으로 되돌린다.

## 시나리오 (`@DisplayName` 은 사용자 말투, 각 1 메서드) — 구현 결과 기준
1. **출근길: 앞차 A 를 보내고 B 를 탔다** — 후보 A·B → 잠정 B → 역삼 정차 시각이 B 와 맞아 확정 B
2. **확정 뒤 링이 차오르고 잠금화면이 따라온다** — 역삼 출발 → 선릉(남은 2) → 도착 예정 = 보고시각+110초 → 진입 0.95 → 도착 1.00 → 다음 역 삼성(남은 1). push 는 기기·Activity 주소가 제자리로, content-state `{name, props}` 에 props 문자열
3. **"이 열차 아니에요"** — reset 뒤 다시 골라도 B 가 아니다
4. **한산한 시간대, 후보 하나** — 잠정 B 에 머물고(역삼 로그가 맞아도 `not-discriminative`), 확정 창 8분 뒤 ABANDONED, 링은 한 번도 안 그려짐 (현재 설계를 고정 — 아래 "발견")
5. **도착 목록에 내 열차가 없다** — 위치 기록으로 선릉 도착(1.00) → 다음 tick 삼성, 도착 예정 null
6. **실시간이 끊겼다** — 5분 뒤에도 마지막 관측 다음 역(선릉)에서 멈춤, 진행률 0, 도착 예정 null, `observedAt` 은 마지막 실제 관측 그대로, 보간 여유 0
7. **같은 열차가 두 번 실려 온다(코레일)** — B 출발 회차에 묵은 B 행 중복 → 그래도 잠정 B (PR #240 이전이면 B 가 격리돼 후보가 A 뿐)
8. **위치 수집이 꺼져 있다(9/21 사고)** — PENDING, 열차 없음, 안내 PENDING·진행률 null, 예외 없음
9. **삼성에서 내리고 여정 종료** — `event=end` push, 고정은 CONFIRMED 로 남음

계획 대비 조정: "앞차 보내고 다음 차"는 1번에 합침. "잠정이 틀려 교정"은 뺐다 — 원천 지연 예산(150초) 때문에 도착 구간이 넓어, 두 열차가 3분 이상 떨어지지 않으면 구간이 겹쳐 가려낼 수 없고 3분 넘게 떨어지면 앞차가 후보에 안 오른다. 교정 규칙은 `SubwayTrainMatcherTest:343` 이 값으로 고정한다. 대신 4번(후보 하나)을 넣었다.

## 발견 (운영 코드는 안 고침 — 사용자 결정)
- **후보가 1대뿐이면 확정이 영영 안 된다.** `SubwayTrainMatcher.confirmStage` 의 `comparable.size() < 2 → not-discriminative`(:247) 가 "후보 여럿 중 1위만 기록"뿐 아니라 "후보가 원래 하나"도 막는다. 단위 시험이 의도로 고정(`SubwayTrainMatcherTest:172,296`). 링은 CONFIRMED 에만 그려지므로 한산한 시간대에는 링이 안 뜬다.
- **확정 직후 첫 잠금화면 프레임이 STALE.** 링이 tick 당 한 역만 넘어가서 첫 tick 에 "역삼 출발"을 소화하고 둘째 tick(5초 뒤)에야 선릉 도착 예정을 읽는다.

## 작업 순서
- [ ] 0. 브랜치: 공유 작업트리(다른 세션이 `test/otp-reroute-it` 사용) → **HEAD 를 옮기지 않는다.** 파일은 작업트리에 만들고, 커밋은 임시 인덱스(`GIT_INDEX_FILE`)로 `origin/main` 위에 `commit-tree` → `git push origin <sha>:refs/heads/test/subway-ride-scenario-it`. 다른 세션의 브랜치·미커밋 변경을 건드리지 않는다
- [ ] 1. `MutableClock` 작성 → `./gradlew compileTestJava`
- [ ] 2. `SubwayScenarioWorld` 작성(피드 생성 + 시드/정리) → 컴파일
- [ ] 3. `SubwayRideScenarioIT` 골격 + 시나리오 1(행복 경로)만 → 실행해 사슬이 실제로 이어지는지 먼저 확인. 여기서 막히면 원인 파악이 나머지보다 우선
- [ ] 4. 시나리오 2~9 추가, 하나씩 실행
- [ ] 5. 발견 사항 `implementation_note.md` 에 즉시 append
- [ ] 6. 검증 → 커밋(플랜 문서 포함) → PR

## 버그를 드러내면
**이 PR 에서 운영 코드를 고치지 않는다.** 실패 시나리오는 원인을 노트 `### 함정` 에 적고 사용자에게 보고 → 별도 PR. 테스트 PR 과 운영 수정이 섞이면 경계 리뷰가 무너진다.

## 파일
- 신규 `src/test/java/watoo/grd/nextroute/testsupport/MutableClock.java`
- 신규 `src/test/java/watoo/grd/nextroute/testsupport/subway/SubwayScenarioWorld.java`
- 신규 `src/test/java/watoo/grd/nextroute/application/journey/SubwayRideScenarioIT.java`
- 신규 `docs/plan/subway-ride-scenario-it/{PLAN,SUMMARY}.md`
- 재사용: `SubPathFixture`(test), `SubwayBindingMatchIT` 의 `newUser()`·`deleteJourneysOf()` 패턴(복제, 공용화는 하지 않음 — 범위 밖)

## 검증
- 전제: 로컬 Postgres(5432)·Redis 기동(기존 Subway*IT 와 같은 전제, Testcontainers 아님)
- `./gradlew test --tests '*SubwayRideScenarioIT'` → `build/test-results/test/TEST-*SubwayRideScenarioIT.xml` 에서 tests=9 failures=0 errors=0 (구현 결과: 9/9) (`BUILD SUCCESSFUL` 만 믿지 않는다)
- `./gradlew test --tests '*Subway*'` → 기존 계약 무회귀
- `./gradlew test` 전체 → 기존 실패 `JourneyTransferAssessmentIT.assessmentIsPersisted` 는 "기존 무관"
- 명령·수치를 노트 `### 검증` 에 첨부
