# 구현 노트

구현 중 발견한 사항을 append-only 로 누적하는 실측 로그. 규칙은 `CLAUDE.md` 의 "구현 노트" 절 참고.

- PLAN.md = 무엇을 할지(승인 산출물) / 이 파일 = 실제로 뭐가 튀어나왔는지(실측)
- 틀린 항목은 삭제하지 말고 `~~취소~~ 이유` 로 덮어쓴다
- 컨텍스트 압축 직후 첫 행동으로 이 파일을 읽는다

## 템플릿

```markdown
## YYYY-MM-DD <작업 slug>
### 확정
- 코드만 봐선 안 나오는 결정·계약
### 함정
- <증상> ← <원인> / <가드>
### 좌표
- path/To/File.java:123 — 무엇의 진입점인지
### 미결
- 결정 안 된 것 + 왜 미뤘는지
### 배포
- 마이그레이션 번호 / 플래그 기본값 / 설정 키
### 검증
- 실제로 돌린 명령과 결과
```

---

## 2026-08-13 journey-reroute-accept (Phase 6 ⑤ PR-1·PR-2)

### 확정
- **합성 순서 뒤집기가 옳았다**(codex 지적). 시험이 추가 결함 하나를 더 잡음: 교체 시작점이 원본
  구간 **한가운데**(RIDE 단계)면 짝 대기 단계만 앞에 남아 종류는 같은 채 다른 노선을 가리킨다 →
  `snapshot-unmappable` 로 거절. 가드: `cut == subPathIndexOf(fromLegIndex - 1)`
- `journey_event.eventId` 는 **UUID 타입**이라 문자열 결정론 키를 못 쓴다 →
  `UUID.nameUUIDFromBytes("reroute-accept:{proposalId}:{candidateId}")`
- **마이그레이션은 .gitignore 화이트리스트**에 넣어야 커밋된다(`!src/.../V43__*.sql`). 안 넣으면
  조용히 untracked 로 남아 CI·다른 개발자 환경에서 컬럼이 없다
- Testcontainers IT(`AbstractJourneyOutboxPostgresIT` 계열)는 **flyway 를 끄고 `@Sql` 로 마이그레이션을
  나열**한다. 새 마이그레이션은 그 목록 3곳(StoreIT·ExpiryIT·SchemaIT)에도 넣어야 한다

### 함정
- `journey_notification` 에는 `available_at` 칸이 없다(`scheduled_at`·`expires_at`·`logical_checkpoint_key`·
  `delivery_key` 가 필수) — IT 픽스처 작성 시 주의
- 새 포트를 컨트롤러에 주입하면 기존 `@WebMvcTest` 가 빈 부족으로 전부 깨진다 → `@MockBean` 추가

### 좌표
- application/journey/service/JourneyRevisionComposer.java:75 — 합성→재컴파일→앞 구간 검사
- application/journey/service/RerouteAcceptTxService.java:90 — 세션 잠금→조건부 update→재확인→반영
- domain/journey/entity/JourneyLeg.java:copyToRevision — 진행도·증거·시각 복제(빌더로는 불가)

### 배포
- **V43 수동 배치 대상**. `journey.reroute.enabled` 계속 false

### 검증
- `./gradlew test --tests '*Reroute*' --tests '*Journey*'` 통과(composer 6 + acceptIT 9 포함)

## 2026-08-13 journey-subway-station-chain (M7 PR-1)

### 확정
- 지하철 역 체인은 **새 데이터 소스가 필요 없다.** ODsay `passStopList` 가 이미
  `SubPathResult.stations` 로 들어와 route snapshot 에 얼려져 있다. geometry 서비스가
  안 내려주고 있었을 뿐이다
- **`StopGeometry.stopId` 를 지하철에서는 비운다.** ODsay `StationResult.stationID` 와
  서울 실시간 `statnId` 는 다른 ID 공간이다. 값을 실으면 언젠가 둘을 비교하는 코드가 생기고
  그때는 아무것도 일치하지 않는다. 실시간 쪽 대조는 **역 이름**으로 한다
- `StopGeometry` 를 지하철·버스 공용으로 쓴다. 앱이 하는 일이 같다 —
  순서대로 늘어놓고, 남은 개수를 세고, 마지막이 내릴 곳인지 본다
- **revision 처리는 손댈 게 없었다.** `snapshotOf(journey, revision):359` 가 이미
  `journey_revision.route_snapshot` 을 먼저 보고 세션은 1번 개정본 폴백일 때만 쓴다.
  PLAN 이 걱정한 "세션 것을 직접 읽는" 경로는 애초에 없다. 다만 지하철 체인이 그 경로를
  타는지는 회귀 시험으로 고정했다(`subwayStationsFollowTheCurrentRevision`)

### 좌표
- application/journey/service/JourneyNavigationGeometryService.java:107 — RIDE_SUBWAY 분기
- application/journey/service/JourneyNavigationGeometryService.java:189 — subwayStations() 본체
- application/journey/dto/JourneyNavigationGeometry.java — StopGeometry 계약(지하철 stopId=null)

### 배포
- 마이그레이션 없음, 설정 변경 없음, 플래그 없음
- **응답에 필드가 늘어난다**(지하철 leg 의 `stops`). 기존 앱은 모르는 필드를 무시하므로 안전

### 검증
- `./gradlew test --tests '*JourneyNavigationGeometryServiceTest'` → XML 확인 24건, 실패 0
  (기존 19건 + 신규 5건)
- `./gradlew build` 전체 통과 → 1,634건, 실패 0, 오류 0
## 2026-08-13 m7-subway-train-binding PR-2a (realtimePosition 연결)

### 확정
- position API 키는 **arrival 과 분리**한다(`seoul.api.subway-position-key`, env `SEOUL_SUBWAY_POSITION`).
  PLAN §5 는 "같은 key 라 예산 공유"라고 썼으나 전제가 틀렸다 — 키가 다르다.
  ~~예산 공유~~ 사용자 확인: **서울 지하철 API 는 호출수 무제한**이라 예산 자체가 논점이 아니다
- 위 사실의 파급: PLAN §5 의 "전 노선 폴링 불가 → 감시 집합이 PR-2 의 전제" 논거가 **약해진다**.
  감시 집합은 이제 *필수*가 아니라 *낭비 절감*이다(CPU·Redis 쓰기량). PR-2b 착수 전 재검토 대상
- **ODsay `wayCode` 와 서울 `updnLine` 은 값 규칙이 다르다.** 기존
  `SubwayRealtimeArrivalReader.toDirection` 은 ODsay wayCode 용이라 **재사용 금지**.
  서울 updnLine 은 공식 명세상 `0=상행/내선`, `1=하행/외선` 이고, 기존 코드의
  `wayCode == 1 ? 상행 : 하행` 과 **부호가 반대**다. 별도 정규화기로 분리한다
- 응답 봉투는 arrival 과 같은 모양이다 — `errorMessage.code == "INFO-000"` 이 성공,
  목록 키만 `realtimeArrivalList` → `realtimePositionList` 로 다르다
- 포트가 `List` 가 아니라 **envelope**(`SubwayPositionFetchResult`)를 돌려준다.
  빈 목록만으로는 "정상인데 열차가 없음"과 "호출 실패"를 가를 수 없고, PR-2b 의
  COMPLETE/PARTIAL/FAILED 판정이 그것에 걸려 있다

### 함정
- 서울 API 응답의 `recptnDt` 는 "최종수신시간"이지 사건 발생 시각이 아니다 ← 공식 명세 /
  가드: 시각을 점이 아니라 구간으로 다루고 하한을 과거로 넓힌다(PLAN §PR-2 시각 모델)
- `lastRecptnDt`(날짜)와 `recptnDt`(시간)가 **별도 필드**로 온다. 실응답에서 `recptnDt` 가
  full datetime 인지 시각만인지 확인 전까지 파싱을 단정하지 않는다

### 미결 (실호출 1회로 해소되는 것들)
- `updnLine` 실제 값 공간 (`0/1` 명세 vs 실응답)
- `recptnDt` 포맷과 실제 지연 분포 → `sourceLagBudget`
- `trainSttus=3` 의 `statnId` 가 전역인지 다음 역인지
- `realtimePosition` 미지원 노선 목록
- 로컬 `env/api.env` 는 값이 비어 있어 **실측은 VPS 또는 키 주입 필요**

### 좌표
- infrastructure/adapter/out/api/subway/dto/SubwayApiResponse.java:27 — 성공 판정 규칙(INFO-000)
- infrastructure/adapter/out/api/subway/SeoulSubwayApiAdapter.java:326 — 재시도/차단 호출 패턴
- application/subway/service/SubwayRealtimeArrivalReader.java:145 — ODsay wayCode 용 방향 변환(재사용 금지)

### 확정 (구현하며 굳은 것)
- **위치 API 결과는 캐시하지 않는다.** `CachedSubwayApiAdapter` 는 "같은 역을 여럿이 동시에
  묻는다"를 합치려고 있는데, 위치는 수집기 하나가 노선마다 한 번 부르는 경로라 합칠 중복이 없다.
  오히려 캐시를 끼우면 수집 주기보다 오래된 위치를 새 관측으로 착각해 없던 전이를 만든다
- **위치 호출은 실패해도 예외를 던지지 않고 값(`FAILED`)으로 돌려준다.** 도착정보
  (`callArrivalApi`)는 재시도 끝에 예외를 던지는데, 위치는 노선마다 부르므로 한 노선의 실패가
  예외로 번지면 그 tick 의 나머지 노선까지 통째로 못 받는다
- `PARTIAL` 1차 판정은 `errorMessage.total != rows.size()`. rowCount 중앙값 비교는 PR-2b 로 미룸
- 차단기 키는 `seoul-subway-position:{lineId}` — 노선별. 공통 키면 한 노선 오류가 전 노선을
  하루 막는다(`ApiCallBlocker` 는 하루 단위 차단)
- `SubwayTrainStatus`/`SubwayExpressType` 는 모르는 값을 예외가 아니라 `UNKNOWN` 으로 받는다.
  외부 API 가 코드를 늘리는 순간 수집이 죽으면 안 된다

### 좌표
- application/subway/service/SubwayDirectionNormalizer.java — updnLine → 도착정보 표기. **여기 한 곳만 고친다**
- application/subway/service/SubwayLineIdMapper.java:toLineName — line_id → 호선명(위치 API 경로용)
- application/subway/dto/SubwayPositionFetchResult.java — COMPLETE/PARTIAL/FAILED 봉투
- infrastructure/adapter/out/api/subway/SeoulSubwayApiAdapter.java:getRealtimePosition — 호출 본체
- infrastructure/adapter/out/api/subway/SeoulSubwayApiAdapter.java:callPositionApi — 재시도(예외 대신 null)

### 검증
- `./gradlew compileJava` 통과
- `./gradlew test --tests '*SubwayLineIdMapperTest' --tests '*SubwayDirectionNormalizerTest' --tests '*SubwayPositionDtoTest'` 통과
- `./gradlew build -x test` 통과

### 실측 (2026-08-13 18:55~18:58, 로컬 실호출)
2호선 10초 간격 7회 + 6개 노선 1회씩.

**노선 지원 — 전부 됨**

| 호선명 | code | rows | subwayId | 급행(directAt=1) |
|---|---|---:|---|---:|
| 1호선 | INFO-000 | 95 | 1001 | 19 (20%) |
| 2호선 | INFO-000 | 56 | 1002 | 0 |
| 9호선 | INFO-000 | 45 | 1009 | 18 (40%) |
| 수인분당선 | INFO-000 | 48 | 1075 | 6 |
| 신분당선 | INFO-000 | 19 | 1077 | 0 |
| 우이신설선 | INFO-000 | 17 | 1092 | 0 |
| GTX-A | INFO-000 | 14 | 1032 | 0 |

- `subwayId` 가 **내부 line_id 와 동일**하다. `toLineName` 왕복이 실제로 성립
- `errorMessage.total` == `realtimePositionList.size()` 였다(56/56, 95/95 …). PARTIAL 1차 판정 근거로 쓸 수 있다
- 응답에 `beginRow/endRow/curPage/pageRow/rowNum/totalCount/selectedCount` 가 섞여 오지만 전부 무시해도 된다

**`updnLine` = `0`/`1` 확정 + 뜻 검증**
- 2호선 60초 사이 실제 역 이동으로 방향을 판정했다. `updnLine=0` 인 열차들이
  신당→상왕십리, 사당→낙성대, 구로디지털단지→대림, 영등포구청→당산, 이대→아현 로 움직였다.
  전부 **내선순환** 진행 방향이다. `updnLine=1` 은 그 반대(충정로→아현, 대림→구로디지털단지, 을지로4가→을지로3가)
- 즉 `0=상행/내선`, `1=하행/외선`. **`SubwayDirectionNormalizer` 매핑이 맞다**(추정이 아니라 실측으로 확인됨)

**열차 identity**
- `(subwayId, updnLine, trainNo)` 중복 **0건**. `trainNo` 단독도 2호선 안에서는 유일했다
- 다만 노선 간 중복은 이 표본으로 반증되지 않는다 — identity 에 노선·방향·운행일을 유지한다

**지선이 같은 lineId 로 온다**
- 2호선 종착역: 성수종착 50, 성수지선 2, 신설동 2, 까치산 1, 신도림지선 1, 신도림 1, 성수 1
- 성수지선·신정지선이 `subwayId=1002` 로 섞여 온다. **`statnTnm` 으로 갈라야 한다**

**상태 전이 실관측 (60초, 공통 56대)**
- `1→2` 14, `2→1` 16, `0→1` 6, **`0→2` 2**, `1→0` 2
- **`0→2` 가 실제로 있다** — ENTERING 다음 ARRIVED 없이 바로 DEPARTED. 도착을 못 보고 지나칠 수 있다.
  설계가 `ENTERING` 도 선행 근거로 인정한 것이 맞았다
- `1→0` 2건은 **둘 다 역 이동을 동반**했다(건대입구→성수, 선릉→삼성). 같은 역에서의 상태 역행이 아니다
- 60초 동안 사라진 열차 0, 새로 등장 2

### 함정 (실측으로 드러남 — 설계 무게중심이 바뀐다)
- **원천 갱신이 초 단위가 아니다.** 열차별 `recptnDt` 증가폭 p50 **56초**, p90 84초, max 103초.
  60초 동안 열차별 갱신 횟수 분포 = {0회:4, 1회:32, 2회:18, 3회:2}
- **`recptnDt` 자체가 현재보다 뒤처져 있다.** now − recptnDt = min 56s / p50 91s / p90 118s / max 153s
- 배치가 통째로 갱신되지 않고 **롤링**이다(10초 tick 마다 31→0→0→27→0→17대 변경)
- 결과: **관측 시간 해상도 ≈ 60~90초.**
  러시아워 배차 2분(120초)과 같은 자릿수라, 관문 `e1 + S < e2 - S` 는 `120 > 2×(60~90)` 이 되어
  **거의 항상 실패**한다 → t0 단독 고정은 러시아워에 사실상 불가능.
  **L4(다음 역 정차 후행 확정)가 보조가 아니라 주력이다**
- 파생: **1~2초 폴링은 완전한 낭비.** 원천이 60~90초마다 바뀌는데 그보다 빨리 물어봐야 같은 값이다.
  10~15초면 충분하고, 그러면 20노선 전량 폴링도 1.3 req/s 수준이라
  **감시 집합(`subway:watch`)의 존재 이유가 거의 사라진다** → PR-2b 대폭 단순화 가능

### 미결 (해소됨)
- ~~`updnLine` 실제 값 공간~~ → `0`/`1` 확정, 뜻까지 실측 검증
- ~~`recptnDt` 포맷~~ → `"yyyy-MM-dd HH:mm:ss"` full datetime. `lastRecptnDt` 는 `"yyyyMMdd"` 날짜만
- ~~`realtimePosition` 미지원 노선~~ → 시험한 7개 노선 전부 지원
- `trainSttus=3`(전역출발) 은 이번 표본(2호선 60초)에 **한 건도 없었다**. 판단 보류 — 여전히 보조 근거로만

## 2026-08-13 m7-subway-train-binding PR-2b (수집기 + 전이 로그)

### 확정
- **`subway_segment.departStationId`/`arriveStationId` 에는 ID 가 아니라 역 *이름*이 들어 있다.**
  엔티티 javadoc 은 "출발역 ID" 라고 적혀 있지만 거짓이다 — `SubwayStaticDataService:148` 이
  `info.departStationName()` 을 넣는다. `SubwaySegmentLookup` 도 이름으로 조회한다.
  그래서 인접 판정도 **이름**으로 한다(위치 API 가 `statnNm` 을 주므로 성립)
- **인접 판정을 `SubwaySegmentLookup` 에 얹지 않고 `SubwayStationAdjacency` 를 새로 팠다.**
  저쪽은 `travelTime == null` 인 구간을 **통째로 버린다**. 인접 판정이 그걸 물려받으면
  소요시간이 없는 구간의 이동이 전부 "순간이동"으로 보여 출발 확정이 막힌다
- **인접 판정은 방향을 가리지 않는다.** 전이 추적기는 전역이라 여정 맥락이 없다.
  여기서 할 수 있는 것은 "맞닿아 있는가"까지고, "우리 진행 방향의 다음 역인가"는 PR-4 몫이다.
  이 단계의 목적은 **순간이동(회차·차량기지·역행) 차단**이다
- **정적 데이터가 없으면 `isAdjacent` 는 항상 false** → 출발 확정이 아예 안 나온다.
  모르는 것을 인접이라 답하면 순간이동을 출발로 읽으므로 이쪽이 안전하다
- **Redis 색인을 둘로 나눴다.** PLAN 은 `trainlog` 하나였지만 질의 축이 둘이다 —
  출발은 **역** 기준(`subway:dep:{date}:{line}:{dir}:{station}`), 도착은 **열차** 기준
  (`subway:arr:{date}:{line}:{dir}:{trainNo}`). 하나로 두면 한쪽이 전수 조회가 된다
- 직전 위치(`subway:pos:{date}:{line}` HASH)는 JSON 이 아니라 구분자 문자열이다.
  노선당 수십 대 × 회차마다 전량 재기록이라 파싱 비용이 수집 주기에 그대로 얹힌다
- **쓰는 쪽이 수집기 하나**(노선 순차)라는 전제로 "읽고 지우고 쓴다"에 잠금을 두지 않았다.
  쓰는 곳이 늘면 이 전제가 깨진다 — Lua 로 묶어야 한다. 클래스 javadoc 에 명시
- 출발 구간 상한이 두 갈래다. 직전이 이미 `DEPARTED` 였으면 `before.receivedAt` 까지로 좁히고,
  놓쳤으면 `now.receivedAt` 까지 넓힌다. 그 넓이가 그대로 불확실성이 된다

### 함정
- `record` 컴포넌트 `quality`(String)에 같은 이름의 enum 반환 접근자를 얹으면 컴파일 에러
  (`no suitable method found for valueOf`) ← 컴포넌트 접근자와 시그니처 충돌 /
  가드: `qualityEnum()` 으로 이름을 달리한다
- **`BUILD SUCCESSFUL` 만으로 시험이 돌았다고 믿으면 안 된다.** 필터가 아무것도 안 잡아도
  성공으로 끝난다 / 가드: `build/test-results/test/*.xml` 의 `tests` 개수를 확인

### 좌표
- application/subway/service/SubwayTrainTransitionTracker.java — 전이 판정 본체
- application/subway/service/SubwayStationAdjacency.java — 순간이동 차단(이름 기준)
- application/subway/service/ServiceDateResolver.java — 04:00 경계(기존 규칙을 한 곳으로)
- application/subway/service/RealtimeSubwayPositionCollector.java — @Profile("worker"), 노선 순차
- infrastructure/adapter/out/cache/subway/RedisSubwayTrainLogAdapter.java — 색인 2개 + TTL
- application/subway/config/SubwayPositionProperties.java — enabled/interval/lines/lag/ttl

### 배포
- 마이그레이션 없음. 설정 키만 추가(`realtime.subway.position.*`), **기본값 전부 off**
- 켤 때 `lines` 를 명시해야 한다 — 비어 있으면 수집기가 아무것도 하지 않는다

### 검증
- `./gradlew compileJava` 통과
- `./gradlew test --tests '*SubwayTrainTransitionTrackerTest' --tests '*ServiceDateResolverTest'`
  → XML 확인: TransitionTracker 16건, ServiceDateResolver 2건, 실패 0
- `./gradlew test --tests '*Subway*' --tests '*ServiceDate*'` → 총 125건, 실패 0, 오류 0
- `./gradlew build -x test` 통과

## 2026-08-13 journey-subway-boarding-evidence (M7 PR-3)

### 확정
- 마이그레이션 번호 **V44/V45** 확정 (V43 은 `add_reroute_proposal_accepted_candidate` 로 이미 사용 중).
  PLAN 이 예측한 번호와 일치
- **고정은 `RIDE_SUBWAY` leg 이 소유한다.** 노선 후보(`journey_leg_route_option`)는 `WAIT_*` 가
  소유하므로 축을 나눈다. 앱이 대기 단계 번호로 보내도 서버가 짝 RIDE 로 옮겨 적고,
  응답에 `legIndex` 를 돌려줘 앱이 다음 요청을 제대로 보내게 한다
- **정차 관측이 먼저 와도 binding 을 연다.** 이동 시작을 놓쳤다고 그 뒤를 통째로 버리면 판정
  기회가 사라진다. 단 그때는 `boardedDetectedAt`·`source` 를 비운다 — 정차만으로 지어내지 않는다
- **기기 시각이 범위 밖이어도 관측을 버리지 않는다.** `detail.clockSuspect=true` 로 표시만 한다.
  버리면 시계가 틀어진 기기는 영영 안내를 못 받고 그 사실을 아무도 모른다
- **`ABANDONED` 단계에는 새 관측을 얹지 않는다.** 사용자가 "안 탔다"고 한 뒤 늦게 온 관측이
  그 결정을 뒤집으면 안 된다
- `USER_RESET` 감사행에 **거부한 열차 identity 를 남긴다**. 이게 없으면 판정기(PR-4)가 같은
  관측을 다시 읽어 방금 거부한 열차를 그대로 고른다
- reset 은 행을 지우지 않고 `PENDING` + `version++` + `resetGeneration++` + 순번 비우기.
  지우면 version 이 0 으로 돌아가 늦게 온 CAS 가 되살아난다

### 함정
- `record` 컴포넌트와 같은 이름의 메서드를 얹으면 컴파일 에러 ← PR-2b 에서 이미 겪음 /
  가드: DTO 검증 메서드는 `isXxx()`·`hasXxx()` 로 이름을 달리한다
- 감사행 unique 에서 `event_type` 을 빼면 `MOVEMENT_START` 와 `STATION_STOP_EPISODE` 의
  1번이 충돌해 한쪽이 조용히 사라진다 ← 둘 다 `kind='EVIDENCE'` /
  가드: IT `movementAndEpisodeShareTheSameSequenceSpace`

### 좌표
- resources/db/migration/V44__create_subway_leg_binding.sql — 고정 상태(1:1, 삭제 안 함)
- resources/db/migration/V45__create_subway_binding_event.sql — 관측·판단 로그 + 후보 원본
- domain/journey/entity/SubwayLegBinding.java — resetByUser()/abandon()/advance*Seq()
- application/journey/service/SubwayBoardingEvidenceService.java — 수신·보정 본체
- application/journey/service/SubwayBoardingEvidenceService.java:rideLegOf — WAIT→RIDE 정규화
- infrastructure/adapter/in/api/journey/JourneySubwayBindingController.java — 3개 엔드포인트

### 배포
- **V44·V45 는 VPS 수동배치 필요** (V18+ untracked convention). 누락 시 프로덕션 깨짐
- 신규 설정 `journey.subway.binding.*`, 기본 off. 지금은 `enabled` 를 아무도 안 읽는다 —
  판정기(PR-4)가 붙을 때 쓴다
- **이 PR 만으로는 상태가 영원히 PENDING** 이다. 판정기가 없어서다. 의도한 기본 상태

### 검증
- `./gradlew test --tests '*SubwayBoardingEvidenceServiceTest'` → XML 확인 17건, 실패 0
- `./gradlew test --tests '*SubwayBindingSchemaIT'` → 12건, 실패 0 (마이그레이션 실제 적용 확인)
- `./gradlew build` 전체 → 1,689건, 실패 0, 오류 0

## 2026-08-14 journey-subway-matcher (M7 PR-4a)

### 확정
- **잠금 대기 상한을 JPA 힌트로 못 건다.** PostgreSQL 에 `FOR UPDATE WAIT n` 이 없어 Hibernate 가
  `jakarta.persistence.lock.timeout` 의 임의 값을 그냥 `for update` 로 떨어뜨린다(0=NOWAIT,
  -2=SKIP LOCKED 만 문장이 된다). 플랜 §2-6b 의 `spring.jpa.properties...lock.timeout: 1000` 은
  **있으나 마나**여서 채택하지 않았다 → 대신 `SubwayBindingLockGuard` 가 트랜잭션 안에서
  `SET LOCAL lock_timeout` 을 직접 건다(`journey.subway.binding.lock-timeout`, 기본 1s)
- **CAS 에서 `confidence` 를 쓰지 않는다.** 플랜 SQL 에는 있었지만 그 값은 "기기가 준 값을 그대로
  옮긴다"가 계약이라 판정기가 덮으면 근거별 오판율을 못 잰다. 상태·열차 identity·version 만 쓴다
- **CAS 를 둘로 나눴다** — `casBind`(열차 identity 포함)와 `casAbandon`(상태만). 하나로 두면
  접을 때 `train_*` 4칸을 어떻게 할지 정해야 하는데, "identity 는 남긴다"(§3-1)와 DB 의
  all-or-none check 를 동시에 만족시키려면 결국 갈라야 한다
- **matcher 입력이 `TrainDeparture` 가 아니라 `SubwayBindingCandidate`** 다. 조회창이 04:00 을
  걸치면 두 영업일을 합쳐 부르는데, 어느 줄이 어느 운행일에서 왔는지는 조회한 쪽만 안다.
  matcher 안에서 다시 유도하면 자정 근처에서 열차 identity 가 하루씩 어긋난다
- 잠금 대기 초과 응답은 **409 `SUBWAY_BINDING_BUSY`** (5xx 아님). `JourneyControllerAdvice` 가
  `PessimisticLockingFailureException` 을 받는다
- 후보 복구는 `binding_version == 현재 version` + `reset_generation` 일치 감사행만 읽는다.
  거부 열차(`USER_RESET`)는 **세대를 가리지 않고** 전부 모은다 — 거부는 누적되는 사실이다

### 함정
- `@Modifying` 질의는 트랜잭션 없이 부르면 `No EntityManager with actual transaction available`
  ← `sweepStale()` 이 `@Transactional` 없이 청소 질의를 불렀다 / 가드: `sweepStale` 에
  `@Transactional`, IT 는 `TransactionTemplate` 으로 감싼다
- Java 17 이라 `List.getFirst()`·`Math.clamp` 없다 ← 둘 다 Java 21 / 가드: `get(0)`·`min/max`
- `IS NOT DISTINCT FROM :param` 은 파라미터 타입 추론이 안 될 수 있다 / 가드: `cast(:x as integer)`
- **잠금을 빼면 시험이 실제로 빨간불이 된다(확인함).** `findForUpdate` → `findByJourneyLegId` 로
  되돌리면 `SubwayBindingConcurrencyIT` 의 "판정이 먼저 커밋되면…"이
  `expected: "PROVISIONAL" but was: "PENDING"` 로 깨진다. 되돌린 뒤 원복함

### 좌표
- application/journey/service/SubwayTrainMatcher.java — 관문·L4 판정(순수 계산기)
- application/journey/service/SubwayBindingMatchService.java — 재료 모으기(트랜잭션 밖) + 감사행 복구
- application/journey/service/SubwayBindingMatchTxService.java — 세션 FOR SHARE → 행 잠금 → CAS → 감사행
- application/journey/service/SubwayBindingLockGuard.java — `SET LOCAL lock_timeout`
- application/journey/service/JourneySubwayStationChainReader.java — 개정본 snapshot 의 계획 역 체인
- domain/journey/repository/SubwayLegBindingRepository.java — findMatchable/findForMatch(SKIP LOCKED)/findForUpdate/casBind/casAbandon/abandonStaleWithoutMovement
- infrastructure/adapter/in/scheduler/journey/SubwayBindingMatchScheduler.java — worker 전용, 60 tick 마다 청소

### 미결
- 관문 숫자 전부 잠정값(실기기 관측 전). 지표 `journey.subway.binding.decisions{reason}` 로 조인다
- 급행·종착역 배제는 MVP 에서 뺐다(플랜 §7). 급행 탑승은 "확인 중"에 머문다
- `findMatchable` 이 `ORDER BY updated_at` 인데 그 정렬을 타는 인덱스가 없다. 미완료 고정은
  진행 중 여정 수에 비례해 작아서 지금은 문제없다 — 늘면 부분 인덱스에 `updated_at` 을 붙인다

### 배포
- **마이그레이션 없음.** V44·V45 가 필요한 칸을 전부 들고 있다
- 신규 설정 `journey.subway.binding.*` 전량 추가, **`enabled` 기본 false 유지**.
  켜려면 PR-2 수집기(`realtime.subway.position.enabled` + `lines`)도 함께 켜야 한다 —
  로그가 없으면 후보가 0 이라 아무 일도 안 일어난다
- 스케줄러는 `worker` 프로필 + `enabled=true` 일 때만 뜬다

### 검증
- `./gradlew test --tests '*SubwayTrainMatcherTest'` → XML 확인 28건, 실패 0
- `./gradlew test --tests '*SubwayBindingMatchServiceTest'` → 8건 / `*TxServiceTest` → 8건, 실패 0
- `./gradlew test --tests '*SubwayBindingMatchIT'` → 13건, `*SubwayBindingConcurrencyIT` → 3건, 실패 0
- `./gradlew test --tests '*SubwayDirectionNormalizerTest'` → 9건, `*SubwayRealtimeArrivalReaderTest` → 2건
- `./gradlew test --tests '*SubwayBoardingEvidenceServiceTest'` → 20건(기존 17 + 잠금 계약 3), 실패 0
- `./gradlew build` 전체 → **1,768건, 실패 0, 오류 0**
