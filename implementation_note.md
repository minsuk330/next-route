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

## 2026-08-14 journey-subway-binding-lifecycle (M7 PR-4b)

### 확정
- **정리를 기능 스위치에 묶지 않는다.** `journey.subway.binding.enabled` 를 끈 뒤에도 켜져 있던
  동안 만들어진 행은 남는다. 정리까지 스위치를 보면 그 행들이 영영 미완료로 남고 아무도 모른다.
  접는 질의는 대상이 없으면 0행이라 꺼진 상태의 비용은 문장 하나다
- **수명주기 질의에 `clearAutomatically` 를 켜지 않았다.** 부르는 자리가 종료·재탐색 트랜잭션
  한가운데라, 영속성 컨텍스트를 비우면 부르는 쪽이 들고 있던 세션·단계 엔티티가 떨어져 나가
  그 뒤의 변경(`bumpRouteRevision` 등)이 조용히 사라진다. 기존 `cancelPendingByJourneyId` 도
  같은 이유로 `clearAutomatically = false` 다 — 관례를 따랐다
- **접는 것은 단일 UPDATE 문장이라 행 잠금을 따로 잡지 않는다.** PR-4a 의 "이 행을 고치는 모든
  경로가 잠금 조회" 규칙은 *읽고 고치는* 경로(더티체킹)에 대한 것이다. 문장 하나는 읽기·쓰기
  사이가 없어 그 병이 없다
- **계승 → 접기 순서가 계약이다.** 뒤집으면 이어받을 원본이 이미 `ABANDONED` 라 아무것도 안
  옮겨진다. 접기는 `route_revision = oldRevision` 으로 좁혀 방금 만든 새 행을 죽이지 않는다
- **계승 판정을 노선 후보 저장 뒤에 부른다.** 방향(`wayCode`) 비교가 새 단계의 선택 후보 행을
  읽기 때문이다. `saveRouteOptions` 앞에 두면 새 단계의 방향이 항상 null 이라 계승이 전멸한다
- **계승 조건은 타입·`routeId`·`startStopId`·`endStopId`·`wayCode` 전부 일치 + 전부 non-null.**
  모르는 것을 같다고 답하면 결과가 엉뚱한 역 하차 안내다. 빠뜨렸을 때 안전한 쪽이 "고정 없음"
- **새 행은 `version = 0` 이지만 `reset_generation`·관측 순번은 그대로 가져온다.** 플랜은
  version 만 적었는데, 세대를 0 으로 되돌리면 함께 재발행한 근거 감사행(세대 1)을 판정기가
  하나도 못 읽어 확정 경로가 통째로 죽는다
- **`USER_RESET` 감사행도 새 단계로 옮긴다**(플랜 §3-3 이 명시하지 않은 추가). 거부는 누적되는
  사실이라 세대를 가리지 않는데, 감사행은 *단계 번호*로 조회된다 — 안 옮기면 재탐색 한 번으로
  거부 이력이 사라지고 방금 거부한 열차를 다시 고른다
- 재발행하는 감사행의 `binding_version` 은 전부 **0** 으로 다시 적는다(새 행 기준). 옛 값을
  그대로 옮기면 후보 복구의 "현재 version 과 일치하는 판단만 읽는다" 규칙에 걸려 옮기자마자 무효
- **접기에는 감사행을 남기지 않는다**(플랜 §3-1 대로). 사유는 지표
  `journey.subway.binding.lifecycle{event=abandoned,reason}` 로만 갈린다 — 행마다 감사행을
  적으려면 벌크 UPDATE 를 건별 읽기로 되돌려야 하고, 종료 경로가 그만큼 길어진다
- 만료 배치는 `IN (:journeyIds)` 한 문장이다. 여정마다 부르면 배치 하나가 200 왕복이 된다

### 함정
- 저장소 인터페이스에 메서드를 더하면 `SubwayBoardingEvidenceServiceTest` 의 손수 만든 대역
  (`FakeBindingRepository implements SubwayLegBindingRepository`)이 **컴파일 에러**로 깨진다 ←
  4개 추가하며 겪음 / 가드: 새 메서드는 그 대역에도 `UnsupportedOperationException` 로 추가
- 계승 IT 에서 `start_stop_id` 는 정적 데이터가 없으면 **null** 이다(`JourneyTransitIdEnricher`
  가 `resolveStation` 실패 시 null). 그대로 두면 non-null 조건에 걸려 계승이 안 일어나 시험이
  거짓 음성이 된다 / 가드: IT 가 여정 시작 뒤 그 두 칸을 직접 채운다
- 계승 부정 케이스는 **접두 구간으로는 만들 수 없다** — 교체 지점 앞 단계는 `copyToRevision`
  복제라 정의상 항상 동일하다. 그래서 부정 시험은 교체 지점을 지하철 단계 자체로 내려
  (`RemainingPathApply(0, …)`) 새 단계가 버스가 되게 만든다

### 좌표
- application/journey/service/SubwayBindingLifecycleService.java — 접기 3종 + 계승 + 근거 재발행
- application/journey/dto/SubwayBindingAbandonReason.java — 지표 tag 어휘(4값)
- domain/journey/entity/SubwayLegBinding.java:inheritTo — 새 단계용 행 복제(version 0, 세대 보존)
- domain/journey/repository/SubwayLegBindingRepository.java — abandonOpenByJourney/…In/…AndRevision/findBoundOfRevision
- application/journey/service/JourneyCommandService.java — ① 종료 전이
- application/journey/service/JourneyLeaseExpiryService.java — ② 만료 배치(생성자 인자 추가)
- application/journey/service/JourneyStartTxService.java — ③ 여정 갈아타기
- application/journey/service/RerouteAcceptTxService.java — ④ 계승 후 옛 개정본 접기

### 미결
- 계승 뒤 **새 단계에서 L4 확정이 실제로 나는지**는 IT 로 안 봤다. Redis 열차 로그까지 세워야
  해서다. 대신 확정에 필요한 재료가 새 단계에 다 있는지를 고정했다 — 근거 감사행 4종 재발행 +
  `binding_version = 0` + 새 행이 `findMatchable` 에 잡힘
- 계승 동일성 판정의 **음성 분기 중 `routeId`·`wayCode`·역 불일치**는 시험으로 못 눌렀다(위 함정).
  타입 불일치와 `PENDING` 만 실측으로 막았다
- `ring` 삭제는 넣지 않았다(플랜대로). PR-5 가 자기 정리를 같이 가져간다

### 배포
- **마이그레이션 없음.** V44·V45 가 필요한 칸을 전부 들고 있다
- 신규 설정 없음. `journey.subway.binding.enabled` 기본 false 유지 — 수명주기는 스위치와 무관하게 돈다
- 새 지표 `journey.subway.binding.lifecycle{event=abandoned|inherited, reason}`

### 검증
- `./gradlew test --tests '*SubwayBindingLifecycleIT'` → XML 확인 5건, 실패 0
- `./gradlew test --tests '*SubwayBindingRerouteInheritanceIT'` → 4건, 실패 0
- `./gradlew test --tests '*Journey*' --tests '*Reroute*' --tests '*Subway*'` → 761건, 실패 0
- `./gradlew build` 전체 → **1,777건, 실패 0, 오류 0** (PR-4a 1,768 + 신규 9)

## 2026-08-15 m7-pr5-subway-ring 실호출 접지 (서울 실시간 도착 API)

VPS 컨테이너 키로 실호출. 표본 107행(12개 역: 강남·신도림·잠실·동대문·건대입구·서울역·
홍대입구·왕십리·사당·교대·성수·합정), 위치 API 는 2·4·7호선.

### 확정
- **도착 API `btrainNo` 와 위치 API `trainNo` 는 같은 번호 공간이다.** 4개 표본(2·4·7호선)
  교집합 **16/16**, 도착에만 있는 번호 0건. binding 은 위치 API 로 고정하고 ring 은 도착 API 로
  ETA 를 얻는데, 이 축이 어긋났으면 전 구간 STALE 이 될 뻔했다
- **`arvlMsg3` 는 그 열차의 현재 역 이름이다.** "강남 도착"→`강남`, "[3]번째 전역 (청계산입구)"
  →`청계산입구`, "6분 50초 후"→`사당`. **도착 API 하나로 열차 현재 위치까지 얻는다**
- **`barvlDt`(남은 초)는 표본의 절반이 0 이고, 0 의 뜻이 `arvlCd` 에 따라 정반대다**
  - `arvlCd=0`(진입)·`1`(도착) + 0 → **다 왔다**(이벤트)
  - `arvlCd=99`(운행중) + 0 → **미산출**(아직 멀다. 문구가 `[N]번째 전역`)
- `arvlCd` × 문구 실측 (n=107)

  | arvlCd | 뜻 | n | 초 있음 | 문구 |
  |---|---|---|---|---|
  | 99 | 운행중 | 73 | 51 (70%) | `M분 S초 후`(초 있음) / `[N]번째 전역`(초 없음) |
  | 1 | 도착 | 17 | 1 | `X 도착` |
  | 5 | 전역 도착 | 6 | 6 | `전역 도착` (중앙값 120초) |
  | 2 | 출발 | 4 | 0 | `X 출발` |
  | 3 | 전역 출발 | 4 | 3 | `전역 출발` (150초) |
  | 4 | 전역 진입 | 3 | 2 | `전역 진입` (120초) |

- **`[N]번째 전역` 의 N 은 2 이상만 나온다**(실측 2,3,4,5,6,7,9,11,21,32). N=1 자리는
  `arvlCd=3/4/5`(전역 출발·진입·도착)가 차지한다 → **남은 정거장이 이산 스케일로 완결된다**
  (`N번째 전역` N≥2 → 전역 3/4/5 = 1 → 진입·도착 0/1 = 0)
- 방향은 이미 정규화돼 있다. 도착 API `updnLine` = `상행/하행/내선/외선`, 위치 API = `0/1`
  이고 위치 수집기가 저장 시점에 arrival 공간으로 바꿔 넣는다 → `binding.train_direction` 과
  `SubwayRealtimeArrivalReader.select()` 의 필터가 같은 공간

### 함정
- **`SubwayRealtimeArrivalReader.select()` 를 링에 그대로 쓰면 안 된다.** `adjustedSeconds >= 0`
  필터가 있어 `barvlDt=0` 인 **진입·도착 열차를 버린다** → 링이 1.00 을 영영 못 찍는다 /
  가드: 링 조회는 그 필터를 타지 않는 별도 경로
- **`newArrivalSeconds <= 0` 을 "곧 도착"으로 읽으면 안 된다.** PLAN 개정 3(codex 리뷰 반영)이
  그렇게 적었는데 실측이 뒤집었다 — `arvlCd=99` 의 0 은 "3정거장 전"이다. 그대로 두면 멀리 있는
  열차를 90%로 표시한다 / 가드: `arvlCd` 로 먼저 가른 뒤에만 초를 본다
- `arvlMsg2` 파싱은 문자열이라 표기 변경에 깨진다. **1차 판정은 코드값 `arvlCd`**, N 추출은
  보조로만

### 좌표
- infrastructure/adapter/out/api/subway/SeoulSubwayApiAdapter.java:200 — 위치 `item.getTrainNo()`
- infrastructure/adapter/out/api/subway/SeoulSubwayApiAdapter.java:320 — 도착 `item.getBtrainNo()`
- application/subway/service/SubwayRealtimeArrivalReader.java:100-116 — `select()` 필터(그대로 쓰면 안 됨)
- application/subway/service/SubwayRealtimeArrivalReader.java:159 — `adjustedSeconds` (raw - elapsed)

### 미결
- ETA(초)가 나오는 비율 70%(운행중 기준). 나머지는 `[N]번째 전역` 이라 **구간 초반에는 초가 없다**.
  링을 이산 앵커(`arvlCd`)로 채울지 freeze 로 둘지는 제품 결정

### 추가 실측 (2026-08-15) — 두 원천 교차 검증과 커버리지

**위치 API 와 도착 API 는 같은 것을 가리킨다.** 같은 시각에 긁어 같은 `trainNo` 의 현재 역을
대조(2·4·7호선, 교집합 106대): **일치 99 / 불일치 7 (6.6%)**. 불일치는 전부 인접 역이고 전부
`recptnDt` 가 음수 lag(도착 데이터가 4~51초 더 오래됨) — **의미 차이가 아니라 갱신 시차**다.

| 위치 `trainSttus` | 현재 역 일치율 |
|---|---|
| `1` 도착 | 63/64 (98%) |
| `2` 출발 | 29/29 (100%) |
| `0` 진입 | **7/13 (54%)** ← 불일치가 전부 여기 |

**그러나 도착 API 는 전 열차를 커버하지 않는다.** 위치 API 열차 177대 기준(2·4·5·7·9호선):

| 노선 | 위치 열차 | 도착에 없음 | 인접행 있음 | 먼 역만 | 인접행 중 초 있음 |
|---|---|---|---|---|---|
| 2호선 | 39 | 1 | 38 | 0 | 46/46 |
| 4호선 | 46 | 1 | 44 | 1 | **28/84 (33%)** |
| 7호선 | 30 | 1 | 29 | 0 | 44/44 |
| 5호선 | 35 | 2 | 33 | 0 | 50/59 |
| 9호선 | 27 | 1 | 18 | **8 (30%)** | 25/25 |
| 합 | 177 | **6 (3%)** | **162 (91%)** | 9 (5%) | 193/258 (74%) |

역당 행 수는 **4행이 최빈**(417개 역) = 방향별 2대. 우리 열차가 그 역 기준 상위 2대에 못 들면
목록에서 사라진다. 노선 편차가 크다 — 9호선 30%가 먼 역에서만(급행 추정), 4호선 초 확보율
33%(코레일 직결 구간 추정).

### 확정 (위 실측에서 나온 설계 결론)
- **두 원천을 역할로 나눈다. 위치 API 를 판정에서 완전히 내리면 안 된다.**
  도착 API 만 남은 초를 주지만 3%+5% 는 값이 없고, 위치 API 는 초를 못 주지만 전 열차를 준다

  | 신호 | 주 원천 | 폴백 |
  |---|---|---|
  | 남은 초(0→0.90 보간) | 도착 `barvlDt` | 없음 → freeze + `STALE` |
  | 진입 0.95 / 도착 1.00 | 도착 `arvlCd=0/1` | 위치 `trainSttus=0/1` |
  | 구간 전환(0% 리셋) | 도착 `arvlCd=1` | **위치 `statnNm` 변화** ← 커버리지 안전망 |
  | 열차 identity | 위치(binding 이 이미 씀) | — |

  이 배치면 도착 API 에 안 잡히는 열차도 **구간은 잃지 않는다**. 링이 0% + `STALE` 로 멈추되
  역이 바뀌면 다음 구간으로 정상 진행한다. 도착 하나에만 매달리면 그 여정은 내내 링이 죽는다
- `arvlMsg3`(열차의 절대 현재 역)는 **판정에 쓰지 않는다.** 우리가 쓰는 값은 전부 조회역 기준
  (`arvlCd`·`barvlDt`·`arvlMsg2`)이라 위치 API 와 좌표계를 맞출 필요 자체가 없다
- **도착 ALL 응답은 한 시점 스냅샷이 아니다** — `recptnDt` 가 210종(역마다 수집 시각이 다르다).
  `observedAt` 은 응답 전체가 아니라 **그 행의 `recptnDt`** 를 쓴다(`adjustedSeconds` 가 이미 그렇게 한다)
- 폴링 주기를 **도착·위치 둘 다 15초**로 통일한다. 현재 도착은 10초
  (`application.yaml:303` `realtime.subway.poll-interval-ms`), 위치는 15초. 통일하면 arrival 일일
  호출이 8640 → 5760 회로 줄어 공유 quota 에 여유가 생긴다

## 2026-08-15 m7-pr5a-subway-ring (진행 링 + 스냅샷 확장)

### 확정
- **구간 전환 표시를 따로 두지 않았다. `segmentProgress == 1.00` 자체가 표시다.** PLAN 의
  `SubwayRingState` 에 없던 칸을 더할 뻔했는데, 이동 중 상한 0.90 / 진입 0.95 는 실제 도착
  사건 없이는 만들어지지 않으므로 1.00 이면 도착을 본 것이다 → 다음 tick 이 그 값을 보고
  구간을 넘긴다(`SubwayRingState.arrivedAtNextStation()`)
- **`arvlCd=2`(조회역 출발)도 구간 전환이다.** PLAN 표에는 없던 경우다. 우리는 언제나 바로
  다음 역만 조회하므로 "그 역을 출발했다" = 이미 지났다는 뜻이라 target 을 +1 한다
- **남은 초를 값으로 들고 다니지 않고 `recptnDt + barvlDt` 절대 시각으로 바꿔 저장한다.**
  `adjustedSeconds`(raw - elapsed)를 안 쓰는 이유 두 가지 — ①`ALL` 응답은 행마다 `recptnDt`
  가 달라 "지금부터 N초"가 아니라 "그때부터 N초"다 ②앱이 앵커로 보간하므로 어차피 절대
  시각이 필요하다. 결과로 `select()` 의 `adjustedSeconds>=0` 필터 문제를 우회하는 것이
  아니라 **그 경로 자체를 안 탄다**(`SubwayRingSignalReader` 가 스냅샷을 직접 읽는다)
- **역 이름 해석은 `SubwayRingStationResolver` 한 곳에서 한 번만.** 그 뒤의 판정은 전부
  `statnId`. 노선별 `Map<정규화이름, statnId>` 를 메모리에 캐시하되 **빈 표는 캐시하지 않는다**
  — 정적 데이터 적재 전에 캐시하면 그 노선이 재시작 전까지 영영 해석되지 않는다
- **스냅샷 조립은 "지금 화면이 지하철일 때만" 고정 한 건을 더 읽는다.** 단계마다 읽으면 정확히
  N+1. `WAIT_SUBWAY` 면 짝 `RIDE` 를 메모리에서 찾아 붙여 질의를 더 만들지 않는다
- **링 정리는 `abandonOpen`/`abandonOpenOfRevision` 세 자리에 `afterCommit` 으로 얹었다.**
  재탐색은 계승 뒤에 `abandonOpenOfRevision` 이 불리므로 그 경로도 함께 덮인다
- PLAN §6 의 `journey.subway.ring.keys` 지표는 **안 넣었다**. 키 수를 세려면 `SCAN` 이 필요한데
  그건 이 PR 이 금지한 것이다. TTL 회귀는 `RedisSubwayRingAdapterIT` 의 `getExpire` 단언으로
  잡는다

### 함정
- **`DestinationNormalizer` 는 끝 "역"을 한 번만 뗀다.** 시험 픽스처에서 마스터 이름을
  `중간역역` 으로 두면 `중간역` 이 되고 계획 이름 `중간역` 은 `중간` 이 되어 안 맞는다 /
  가드: 표기 흔들림은 괄호 접미사(`중간역(2호선)`)로 시험한다
- **시험 환경에서 `journey_leg.route_id` 는 `2호선` → `1002` 로 해석돼 채워진다**
  (`JourneyTransitIdEnricher` + `SubwayLineIdMapper.resolveOdsay`). 처음엔 null 일 거라
  의심했는데 아니었다 — 링 IT 는 그 값을 읽어 `subway_station` 을 심는다
- `JourneySnapshot` 에 필드를 더하면 `withEta()` 가 조용히 떨어뜨린다. `of()` 시그니처는
  건드리지 않고 `withSubwayGuidance()` 를 따로 뒀다 — `of()` 를 고치면 시험 6개가 깨진다
- `SubwayLegBindingRepository` 에 메서드를 더하면 `SubwayBoardingEvidenceServiceTest` 의
  손수 만든 `FakeBindingRepository` 가 컴파일 실패한다(추상 메서드 미구현)

### 좌표
- application/journey/service/SubwayRingCalculator.java:105 — `next()` 본체(코드→초 순서)
- application/journey/service/SubwayRingCalculator.java:88 — `targetStationSequence()`
  (갱신기가 조회할 역을 여기서만 정한다)
- application/journey/service/SubwayRingSignalReader.java:85 — 도착 스냅샷 직접 조회
- application/journey/service/JourneySubwayGuidanceComposer.java:57 — 스냅샷 조립 진입점
- infrastructure/adapter/out/cache/journey/RedisSubwayRingAdapter.java:44 — 저장 Lua(값+TTL+색인)
- domain/journey/repository/SubwayLegBindingRepository.java:57 — `findRingTargets` (CONFIRMED 만)

### 미결
- `freshnessWindow` 120초는 잠정값. 실기기 관측 전에는 근거 없음
- UI 역행 금지가 앱에 실제로 걸려 있는지 미확인. 서버는 앵커 단조까지만 보장한다
- PR-5b(V46 토큰·V47 outbox·FCM 발송)는 모바일 토큰 등록 확인 후 착수

### 배포
- **마이그레이션 없음**(PR-5a). 최신은 여전히 V45
- 신규 설정 `journey.subway.ring.*` — `enabled` 기본 false
- **기존 설정 변경 1건**: `realtime.subway.poll-interval-ms` 10000 → **15000**
  (위치 수집기와 주기 통일. 도착 API 일일 호출 8640 → 5760)
- 켜는 순서: `realtime.subway.enabled` + `realtime.subway.position.*` →
  `journey.subway.binding.enabled` → `journey.subway.ring.enabled`

### 검증
- `./gradlew test --tests '*SubwayRingCalculatorTest'` → **21 tests, 0 failures**
  (XML 5개 합산: 본체 11 + Nested 2/3/2/3)
- `./gradlew test --tests '*RedisSubwayRingAdapterIT'` → **6 tests, 0 failures** (Testcontainers Redis)
- `./gradlew test --tests '*SubwayRingIT'` → **11 tests, 0 failures**
- `./gradlew test --tests '*JourneySubwayGuidanceIT'` → **9 tests, 0 failures**
- `./gradlew test --tests '*Journey*' --tests '*Subway*'` → 98 classes / **720 tests, 0 failures**
- `./gradlew build` → 263 classes / **1824 tests, 0 failures**

---

## 지하철 도착정보 — 미산출 열차 표기 (feat/subway-arrival-prev-station)

### 확정
- **`barvlDt`(남은 초) 0 은 "0초 남음"이 아니라 미산출이다.** 뜻이 `arvlCd` 에 따라 갈린다 —
  진입·도착(0/1)이면 사건, 전역류(3/4/5)·운행중(99)이면 "거리로만 답했다". 어느 쪽이든
  더할 시각이 없다. `SubwayRealtimeArrivalReader.adjustedSeconds()` 는 이제 `raw <= 0` 에
  **null**(모름)을 준다. 0 을 흘리면 경과시간을 뺀 음수가 되어 지나간 열차로 둔갑한다
- **지나간 열차의 근거는 초가 아니라 `arvlCd=2`(조회역 출발)다.** 초는 *진짜 예정 시각이
  있었는데*(raw>0) 그마저 넘겼을 때만 부차적 근거
- **거리(`stationsAway`)는 서버가 확정해 내려 준다.** `arvlMsg2` 파싱을 클라이언트에 맡기지
  않는다 — 원천이 문구 표현을 한 번 바꾸면 모든 화면이 동시에 깨진다
- 응답 계약에 `stationsAway` 추가: 조회역 진입·도착=0, 전역류=1, 운행중은 `[N]번째 전역`
  파싱, 그 밖(출발·미상)=null. **`arrivalSeconds` 가 null 이어도 열차를 감추면 안 된다**
- 코드가 답할 수 있는 자리에서는 문구를 보지 않는다(코드 우선). 문구는 `ENROUTE` 에서만 읽음
- ~~segment 로 미산출 열차의 ETA 를 추정~~ 이번 범위에서 제외. 사용자 결정=표기만.
  필요 부품 4개가 빔(아래 미결)

### 함정
- **미산출 열차가 목록에서 통째로 사라짐** ← `select()` 의 `adjustedSeconds >= 0` 필터가
  `0 - 경과초 = 음수` 를 "지나갔다"로 읽음. 실측 표본의 **절반**이 `barvlDt=0` 이라 피해가 큼.
  가드 = `SubwayRealtimeArrivalReaderTest.keepsTrainWhoseEtaIsNotComputed`
- **필터를 초 기준으로 그냥 풀면 지나간 열차까지 통과하는 반대 회귀.** `arvlCd=2` 명시 판정이
  같이 들어가야 함. 가드 = `dropsTrainThatAlreadyDepartedQueriedStation`
- 여정 예측 경로(`WaitSubwayArrivalPredictor`·`SubwayBoardingTimeEstimator`)는 `hasSeconds()`
  로 거르므로 **동작 불변**. 예전엔 목록에서 빠졌고 지금은 목록에 있으나 초가 null 이라 무시됨
- `SubwayRingSignalReader` 클래스 주석이 옛 필터(`adjustedSeconds >= 0`)를 설명하고 있었음 →
  `arvlCd=2` 기준으로 갱신. 그 판단("select() 를 안 쓴다")은 그대로 유효
- 테스트에서 `given(...)` 인자 안에서 mock 을 또 스터빙하면 `UnfinishedStubbingException`.
  변수로 먼저 빼야 함 (`SubwayArrivalQueryServiceTest.givenStation`)

### 좌표
- application/subway/service/SubwayRealtimeArrivalReader.java:104 — `hasPassed` 필터
- application/subway/service/SubwayRealtimeArrivalReader.java:150 — `hasPassed` 본체
- application/subway/service/SubwayRealtimeArrivalReader.java:172 — `adjustedSeconds`, `raw<=0 → null`
- application/subway/service/SubwayStationsAway.java:41 — `resolve(code, arvlMsg2)` (신규)
- application/arrival/dto/SubwayArrivalResponse.java:28 — `stationsAway` 필드
- application/arrival/service/SubwayArrivalQueryService.java:80 — 응답 조립부

### 미결
- segment 기반 ETA 추정에 빠진 부품 4개:
  ① `arvlMsg3`(열차 현재 위치 역명)이 `SubwayRealtimeTrain` 까지 안 옴(`toTrain` 둘 다 누락)
  ② N홉 누적 합산기 없음(`SubwaySegmentLookup` 은 인접 1칸만)
  ③ 정차시간(dwell) 데이터 없음 — 역당 20~30초 과소추정
  ④ `travelTime == null` 구간을 `SubwaySegmentLookup.reload()` 가 버림 → 그 구간 끼면 합산 불가
- `SubwayRealtimeQueryService.adjustArrivalSeconds()`(별도 엔드포인트)는 여전히 0 으로 clamp.
  같은 함정을 안고 있으나 이번 범위 밖

### 배포
- **마이그레이션 없음.** 최신은 여전히 V45
- 설정 변경 없음
- **클라이언트 영향**: `arrivalSeconds: null` 인 열차가 목록에 새로 나타난다. 앱이 초 없는
  열차를 안 그리면 화면이 그대로다 — `stationsAway`/`currentMessage` 표기 대응 필요

### 검증
- `./gradlew compileJava` → 성공
- `./gradlew test --tests '*SubwayRealtimeArrivalReaderTest*' --tests '*SubwayStationsAwayTest*'
  --tests '*SubwayArrivalQueryServiceTest*'` → **18 tests, 0 failures**
- `./gradlew test --tests '*Test'` → 1467 tests, **1 failed (기존 무관)**
  - 실패 = `SubwayDataServiceTest.flow_test` — `Schema-validation: missing column [deleted_at]
    in table [subway_ring_push_outbox]`. 작업트리에 딸려 있던 **PR-5b 미커밋 엔티티**
    (`SubwayRingPushOutbox`, untracked)의 마이그레이션 미작성 탓. 이 브랜치와 무관
  - 같은 원인으로 `@SpringBootTest` 계열 IT 79개가 컨텍스트 기동 실패(전량 동일 스택)

## 2026-08-15 m7-pr5b-subway-live-activity (Live Activity 토큰 + push outbox + 발송)

### 확정
- **`ApnsConfig.payload["aps"]` 는 `Aps` 객체가 아니라 그 안의 Map 이 펴져서 들어간다.** SDK 가
  빌드 시점에 `aps.getFields()` 를 꺼내 넣는다. 시험에서 `Aps.fields` 를 찾으려다 실패해서 알았다
  — payload 계약 시험은 `payload.get("aps")` 를 **바로 Map 으로** 캐스팅한다
- **`aps_timestamp` 를 칼럼으로 들고 `GREATEST(지금, 직전+1)` 로 채번한다.** PLAN 은 "sequence 와
  같은 원천에서 파생"만 요구했다. 실제 시각에 붙여야 APNs 가 다른 알림과 견줄 수 있는데 같은
  초에 두 건이 나가면 값이 같아져 순서가 사라진다 → 직전보다 반드시 1 크게 만들어 **단조성과
  실시간성을 함께** 지킨다. 채번은 INSERT ... SELECT 한 문장 안에서 한다
- **`ON CONFLICT DO NOTHING` 이 경합의 정답이다.** 워커 둘이 같은 여정을 동시에 계산하면 순번
  unique 나 대기 unique 에 걸리는데, 그것은 "누가 더 새 걸 넣었다"는 뜻이라 예외가 아니다.
  던지면 그 tick 전체가 죽는다
- **종료 outbox 는 부르는 쪽 트랜잭션에 올라탄다**(`REQUIRES_NEW` 아님). 링 삭제를 `afterCommit`
  으로 미룬 것과 방향이 다른 이유 — 링은 Redis 라 롤백이 안 되지만 이건 같은 DB 행이라 함께
  커밋되거나 함께 되돌아갈 수 있다. `afterCommit` 도 아니다: 커밋과 콜백 사이에 죽으면 그
  종료는 영영 안 만들어진다(outbox 를 쓰는 목적이 그 창을 없애는 것)
- **종료 프레임은 마지막으로 보낸 payload 를 그대로 쓰고 `updatedAt` 만 새로 찍는다.** 진행률을
  1.0 으로 채우지 않는다 — 종료는 하차로만 오는 것이 아니라 lease 만료·갈아타기로도 온다
- **보낸 적 없는 여정에는 종료 행을 만들지 않는다.** 지울 화면이 없다
- **재탐색(`abandonOpenOfRevision`)에서는 종료를 보내지 않는다.** Activity 는 여정 단위라 이어진다
- signature 는 **뭉갠 값**으로 만든다(도착 예정 30초 버킷 + 진행률 0.05 눈금). 정확한 시각을
  넣으면 재앵커마다 바뀌어 결국 15초마다 push 한다. 중요 이벤트(다음 역·국면·신선도)는 안 뭉갠다
- 토큰은 **journey 단위**. leg/binding 축을 더하면 계승마다 재등록이 필요해 push 가 끊긴다.
  낡은 payload 는 outbox 의 `binding_version` + 발송 직전 대조가 막는다

### 함정
- **`LiveActivityPushPort` 를 relay 에 필수 의존으로 넣으면 push 를 끈 기본 설정에서 부팅이
  죽는다.** 어댑터가 `push.enabled AND sender=fcm` 일 때만 뜨기 때문 / 가드: relay 는
  `ObjectProvider` 로 받고 `push.enabled` 를 먼저 본다. 켜 놓고 창구가 없는 조합은
  `SubwayRingPushWiringValidator` 가 부팅에서 읽을 수 있는 문장으로 막는다
  (시험 `SubwayLiveActivityTokenIT` 가 이 사고를 잡아 줬다)
- **`BaseEntity` 를 상속하면 `deleted_at` 칼럼이 없으면 스키마 검증에서 부팅이 죽는다.**
  V47 에 안 넣었다가 걸렸다 / 가드: 쓰지 않아도 칼럼은 둔다(V40 과 같은 관례)
- **Jackson 은 `BigDecimal("0.4500")` 을 `0.45` 로 직렬화한다.** payload 시험에서 자릿수를
  그대로 기대하면 깨진다
- 링 tick 에서 relay 를 부르지 않는다 — 외부 왕복이 링 계산을 밀어낸다. 스케줄러가 따로다
- `SubwayLegBindingRepository` 에 메서드를 더하면 `SubwayBoardingEvidenceServiceTest` 의
  손수 만든 Fake 가 또 컴파일 실패한다(PR-5a 에 이어 두 번째)

### 좌표
- application/journey/service/SubwayRingPushDecider.java:56 — 보낼지 판정 + signature
- application/journey/service/SubwayRingPushEnqueueService.java:69 — 링 변화 → 대기열
- application/journey/service/SubwayRingPushRelayTxService.java:76 — **발송 직전 재확인**(이 PR 최대 난소)
- domain/journey/repository/SubwayRingPushOutboxRepository.java:97 — 채번 INSERT(순번+timestamp)
- domain/journey/repository/SubwayRingPushOutboxRepository.java:186 — 실패 원자 판정(CASE 3갈래)
- infrastructure/adapter/out/notification/FcmLiveActivityPushAdapter.java:96 — Message 조립(계약 전부)

### 미결
- 앱이 `props.updatedAt` 과 `aps.timestamp` 중 무엇으로 순서를 가리는지 미확인
- 앱이 재탐색 뒤에도 같은 Activity 를 유지하는지 미확인(유지 전제로 만들었다)
- `push.ttl` 2분·`maxAttempts` 3 은 잠정값

### 배포
- **V46·V47 VPS 수동배치 필수**(V44·V45 와 같은 untracked 관례). 파일 전문은 PR 본문에 붙였다
- 신규 설정 `journey.subway.ring.push.*` — `enabled` 기본 false
- **켜는 순서**: `journey.notification.sender=fcm`(+ 자격증명 마운트) → `journey.subway.ring.enabled`
  → **모바일 토큰 등록 확인** → `journey.subway.ring.push.enabled`
- push 를 켰는데 sender 가 noop 이면 **부팅이 막힌다**(의도된 fail-fast)
- 신규 의존성 없음 — `ApnsConfig.setLiveActivityToken` 이 firebase-admin 9.10 에 실재(javap 확인)

### 검증
- `./gradlew test --tests '*SubwayRingPushPayloadFactoryTest'` → **6 tests, 0 failures**
- `./gradlew test --tests '*SubwayRingPushDeciderTest'` → **12 tests, 0 failures**
- `./gradlew test --tests '*FcmLiveActivityPushAdapterTest'` → **11 tests, 0 failures**
- `./gradlew test --tests '*SubwayRingPushIT'` → **20 tests, 0 failures**
- `./gradlew test --tests '*SubwayLiveActivityTokenIT'` → **9 tests, 0 failures**
- `./gradlew build` → 272 classes / **1900 tests, 0 failures**

#### 정정 (같은 날)
- 위 `### 검증` 의 `./gradlew build` 수치 ~~272 classes / 1900 tests~~ 는 **다른 브랜치 위에서
  잰 값이다**. 작업 중 이 작업 트리가 다른 세션에 의해 `feat/subway-arrival-prev-station`
  (PR #155)로 바뀌어 있었고, 커밋이 그 위에 얹혔다. fresh main 기준으로 옮겨 다시 재니
  **271 classes / 1885 tests, 0 failures**(차이는 PR #155 이 더한 시험)
- 함정으로 남길 것: **한 작업 트리를 두 세션이 공유하면 `git checkout -b` 뒤에도 브랜치가
  바뀌어 있을 수 있다.** 커밋 직후 `git log --oneline -2` 로 부모를 확인한다 / 가드: PR 생성이
  "No commits between main and <branch>" 로 실패하면 이 상황부터 의심한다

### 리뷰 반영 (sonnet, PR #156)
- **blocker 실재했다.** `resolveFailure` 가 `DataIntegrityViolationException` 을 잡고 **같은
  트랜잭션에서** `markSkipped` 를 다시 쓰려 했다. PostgreSQL 은 문장 하나가 실패하면 트랜잭션을
  통째로 abort 하고 이후 모든 문장을 25P02 로 거절한다 — savepoint 없이는 복구 쓰기가 불가능하다.
  실제로는 그 두 번째 문장이 또 던져서 배치 루프까지 예외가 올라가고, 지표는 정상 결말을
  `failed/exception` 으로 기록하고 있었다 / 가드: 충돌을 **그대로 던지고**(트랜잭션 롤백)
  트랜잭션 <b>밖</b>인 `SubwayRingPushRelayService.resolve()` 가 새 트랜잭션으로 `SKIPPED` 처리.
  정리 자체의 실패도 그 건만 죽도록 삼킨다
- **N+1 실재.** relay 가 토큰마다 `pushTokenRepository.findById` → 배치 100 × 기기 수만큼 단건
  조회가 2초마다 돌았다 / 가드: `findAllById` 로 행마다 한 번
- **lease 만료 배치에 여정 수만큼 왕복을 얹고 있었다.** 바로 옆 주석이 "외부 호출이나 다른 계산을
  끼우면 만료 하나가 커넥션 풀을 마르게 한다"고 경고하는 자리였다 / 가드: 종료 enqueue 를
  **배치 2문장**으로 바꿈(`skipOpenUpdatesByJourneyIn` + `enqueueTerminalByJourneyIn`).
  `DISTINCT ON (journey_id)` + window `max()` 로 여정별 마지막 행과 채번을 한 번에 집는다
- 그 결과 **자바 쪽 종료 프레임 조립을 지웠다**(`terminalContentState`). 같은 변환이 두 벌이면
  한쪽만 고쳐지는 순간부터 종료와 진행이 다른 모양으로 나간다. 쓰이지 않게 된
  `decider.terminal()` 과 `findFirstByJourneyIdOrderBySequenceDesc` 도 함께 제거
- **PG jsonb 는 수치 표기를 원본 그대로 보존한다**(`0.4500`). 자바 경로는 재직렬화하며 `0.45` 로
  정규화했었다 — SQL 경로가 오히려 더 충실하다. 대신 키 순서가 안 지켜지므로 payload 단언은
  문자열 `contains` 가 아니라 **파싱해서 값으로** 해야 한다
- 리뷰가 "통과"로 확인한 것: 순번·timestamp 단조 / 발송 직전 재확인(최신성+세대) / 종료 접점
  구분(재탐색 제외) / payload 두 겹·단위 분리 / 토큰 비노출 + 조건부 deactivate / 부팅 안전성

### 검증 (리뷰 반영 후)
- `./gradlew test --tests '*SubwayRingPushIT'` → **22 tests, 0 failures**
- `./gradlew build` → 271 classes / **1885 tests, 0 failures**

### 리뷰 nit 도 닫음 (앞선 "이번 결함 아님" 판단을 뒤집는다)
- ~~payload factory 가 하위 계약을 그대로 신뢰하는 것은 PR-5a 의존이라 이번 결함이 아니다~~
  → **넘길 게 아니었다.** `props` 에 null 이 하나라도 들어가면 앱 디코딩이 통째로 실패하고
  **조용히 무시**된다(실패 응답도 안 온다). 이 PR 이 내내 막으려는 실패가 정확히 그 모양인데,
  거기에만 방어가 없었다
- 가드: `SubwayRingPushEnqueueService.isRenderable()` 이 넣기 전에 non-optional 값을 확인한다.
  비면 `incomplete_ring` 으로 세고 **순번을 태우지 않는다**
- **`estimatedNextStationAt` 은 일부러 안 본다** — 없는 것이 정상이고(신호 상실) 그때 앱은
  보간을 멈추기로 돼 있다. 이걸 필수로 넣으면 STALE 구간의 화면이 통째로 안 나간다

### 검증 (nit 반영 후)
- `./gradlew test --tests '*SubwayRingPushIT'` → **24 tests, 0 failures**
- `./gradlew build` → 271 classes / **1887 tests, 0 failures**
## ml/ ↔ nextroute-ml 동기화 (2026-08-17, 브랜치 chore/ml-sync-target-encoding)

### 확정
- `ml/` 은 **서브모듈 아님, ignore 아님**. 메인 repo 가 일반 파일로 추적. `cd ml && git pull` 은 메인 repo 를 당기는 것이라 **하면 안 됨**
- 리모트 이름은 `ml-origin` (https://github.com/minsuk330/nextroute-ml.git). `ml-remote` 아님
- `ml-origin/main` 은 같은 파일들이 **루트**에 있는 subtree split repo. prefix 없이 merge 하면 프로젝트 루트에 `train.py`, `serve/` 가 쏟아진다
- 동기화 방법 = `git merge -X subtree=ml ml-origin/main`. `git subtree pull` 은 unrelated history 라 실패
- 이번 병합 커밋(45cba74) 이후 `ml-origin/main` 이 조상이 되었으므로 **다음부터는 `git subtree pull --prefix=ml ml-origin main` 이 정상 동작**해야 함

### 함정
- unrelated history → 공통 조상이 없어서 공유 파일 8개가 전부 **add/add 충돌**로 뜬다
  (README.md, gate.py, ops/deploy_model.sh, ops/train_local.sh, serve/app.py, serve/test_app.py, split.py, train.py)
- 이번엔 `git diff HEAD:ml 9a0505a` 가 **빈 출력** = 로컬 ml/ 트리가 ml-origin base 와 바이트 동일임을 확인한 뒤
  전부 theirs 채택 → 로컬 손실 0. **다음번엔 이 사전 확인 없이 theirs 채택 금지**

### 좌표
- 병합 결과 27파일 +60582/-86. 로컬에만 있고 upstream 에 없는 ml 파일은 0개(단방향)
- 신규: `ml/feature_engineering.py` `ml/train_advanced.py` `ml/train_compare.py` `ml/test_train.py`
  `ml/test_feature_engineering.py` `ml/check_api_eta_leakage.py` `ml/docs/api-eta-leakage.md` `ml/.gitignore`
- 모델 아티팩트 포함(사용자 승인): `ml/models/lgbm_2026-06-12_to_2026-08-06_corrected_20260816T060405Z/`
  — parquet 인코딩 테이블 약 5.7MB + `model.txt` 38155줄. **바이너리가 메인 repo 에 영구 적재됨**
- 업스트림 작업 내용 = target-encoding routing, MAE 117.19s (taehyoni7413/nextroute-ml 에서 merge)

### 검증
- `git diff <staged-tree>:ml ml-origin/main` → **빈 출력** (병합 후 ml/ 이 upstream 과 동일)
- `git diff --cached HEAD -- . ':(exclude)ml'` → **빈 출력** (ml/ 밖 변경 0)
- `git ls-files ml | grep -E '\.env$|credentials|\.pem$|token'` → 없음 (비밀값 유입 없음)
- 파이썬 테스트는 미실행 (ml 은 Gradle 밖, 이번 작업은 동기화 한정)

### 배포 영향 점검 (push 전, 2026-08-17)

**결론: 이 머지 자체로 프로덕션 예측 동작은 안 바뀐다. 단, main 머지 시 GHCR ml 이미지가 자동 재빌드된다.**

- **모델은 안 바뀐다.** repo 에 커밋된 `ml/models/lgbm_..._corrected_...` 는 배포물이 아님.
  VPS serve 는 `ML_MODEL_PATH=/data/model/current` → `/srv/nextroute/ml-data/experiments/lgbm_2026-06-12_to_2026-06-29_label_20260630T174810Z` 심볼릭 링크.
  바꾸려면 `ops/deploy_model.sh` 로 rsync + 심볼릭 스왑 해야 함
- **CI 자동 트리거**: `.github/workflows/ml-image.yml` 이 `paths: ml/**` → main 머지 순간
  `ghcr.io/minsuk330/nextroute-ml:latest` 재빌드/푸시됨
- **:latest 태그를 batch·serve 두 서비스가 공유**한다 (compose.yaml:110 nextroute-ml, :131 nextroute-ml-serve
  `${ML_SERVE_IMAGE:-...:latest}`, VPS .env 에 `ML_SERVE_IMAGE` 없음).
  VPS cron `10 5 * * * docker compose pull nextroute-ml` 이 매일 로컬 :latest 를 갱신 →
  **serve 컨테이너는 그 순간엔 안 바뀌지만, 이후 아무 recreate 에서나 새 serve 코드로 올라간다**
- `deploy_model.sh` 는 `--with-image` 없으면 pull 을 안 하고 `up -d --force-recreate` 만 함 →
  이미 cron 이 당겨둔 로컬 :latest 를 쓰게 된다. 즉 다음 모델 배포 때 serve 코드도 같이 올라감

**호환성 — 실측으로 확인함(추정 아님)**
- 현행 프로덕션 모델(28 feature / 30 route / manifest 에 router·te 필드 없음)을 **새 이미지에서 로드 성공**,
  `router=False te=False`, predict 스모크에서 AVAILABLE + UNSUPPORTED_ROUTE 그대로
- Java 가 보내는 feature 27개 + route_id == serve `RAW_FEATURE_NAMES` 27개와 **집합 일치**,
  새 모델 `feature_list_b`(27) ⊆ Java 전송 필드. 누락 없음
- predict 응답에 `"model":"a"|"b"` 필드가 새로 붙지만 `MlPredictResponseDto` 에
  `@JsonIgnoreProperties(ignoreUnknown = true)` 있어 안전.
  `/metadata` 새 필드(`router_enabled`, `feature_list_b`, `target_encoding_enabled`)는
  `MlMetadataResponseDto` 에 ignoreUnknown 이 없지만, 이미 `categorical_feature`/`model_route_count` 를
  안 받고도 돌고 있음 = Spring 기본 FAIL_ON_UNKNOWN_PROPERTIES=false 로 이미 검증된 상태
- serve 가 새로 import 하는 `polars`(base dep), `feature_engineering`→`split`→`dotenv`, numpy(lightgbm 전이)
  전부 serve extra 로 설치됨. pyproject/uv.lock **변경 없음**
- 배치 cron 이 쓰는 `archive.py` / `validate.py` / `retention.py` / `build_dataset.py` 는 **이번 diff 에 없음**

**주의(미결/후속)**
- **router(model B) 는 현재 구조에서 절대 안 탄다.** Java 가 predict 호출 전에
  `PredictionSupportService.support()` 로 미지원 route 를 먼저 걸러낸다
  (SingleTransferPredictor:125, TransferArrivalEnricher:383/410) → unseen route 는 요청 자체가 안 감.
  router 모델을 배포해도 이득 보려면 Java 게이팅 또는 `/metadata` 의 지원 목록 계약을 먼저 손봐야 함
- **새 모델은 절대 MAE 가 더 나쁘다**: 현행 배포 모델 overall MAE **88.34s**(30 route, test 777,777행)
  vs 신규 **117.19s**(146 route, test 7,000,000행). 커버리지가 30→146 route 로 넓어진 대가.
  `gate.py` docstring 예시가 `--abs-mae 95` → `123` 으로 바뀐 것도 이 뜻 —
  배포하려면 `ML_ABS_MAE` 상한을 올려야 통과한다. **배포 전 별도 판단 필요**
- `ml/models/` 23MB 가 `COPY . .` 로 이미지에 그대로 들어간다(`.dockerignore` 에 `models/` 없음).
  빌드된 이미지 1.3GB. 모델 아티팩트를 계속 git 에 둘 거면 `.dockerignore` 에 추가 고려

### 검증 (ml 동기화)
- `.venv/bin/python -m pytest serve/test_app.py test_feature_engineering.py test_train.py -q` → **31 passed**
- `docker build -t nextroute-ml:mergecheck ml/` → **exit 0** (1.3GB)
- 새 이미지 + 신규 모델 로드 → `error=None loaded=True router=True te=True routes=146 featA=53 featB=27`
- 새 이미지 + **현행 프로덕션 모델**(vps 에서 rsync) 로드 → `error=None router=False te=False routes=30 featA=28`
- 위 조합 predict 스모크 → `AVAILABLE(model=a)` + `UNSUPPORTED_ROUTE` 정상
- Java 컴파일/테스트 미실행 — 이번 diff 에 java 변경 0건 (`git diff --cached HEAD -- . ':(exclude)ml'` 빈 출력)

---

## FCM 발송 배선 — 프로덕션 첫 활성화 시도 (2026-08-18)

### 함정
- **`sender=fcm` 을 켜자 worker 부팅이 죽었다** ← `FirebaseAdminMessagingClient` 의 생성자가 **둘**인데
  `@Autowired` 표시가 없었다. Spring 은 후보가 여럿이면 표시를 찾고, 없으면 인자 없는 생성자로
  폴백한다 → `NoSuchMethodException: <init>()` → `BeanInstantiationException: No default constructor
  found` → `fcmNotificationSender` → `journeyNotificationRelayService` 순으로 컨텍스트 refresh 취소.
  시험용 생성자의 `MessageDispatcher` 가 빈이 아니라는 사실로 나머지를 골라 주지 않는다.
  가드: `@Autowired` 명시 + `FirebaseAdminMessagingClientWiringTest`(컨텍스트에서 빈이 실제로 만들어지는지)
- **단위시험이 이 경로를 영원히 못 밟는다.** 시험은 `new` 로 이음매 생성자를 직접 부른다.
  `FirebaseConfigTest` 도 "자격증명 없음 → 부팅 실패"만 보고 **"자격증명 있음 → 정상 부팅"** 은 안 봤다.
  `sender=noop` 이라 빈이 아예 안 떠서 여태 안 드러났고, **첫 실행이 프로덕션이었다**
- **`FcmLiveActivityPushAdapter`(PR #156, 미머지)도 동일 패턴** — public(FirebaseMessaging,…) +
  package-private(MessageDispatcher,…). 머지 후 push 를 켜면 같은 방식으로 죽고, 게다가
  `SubwayRingPushWiringValidator` 의 친절한 안내문이 뜨기 **전에** 빈 생성이 먼저 터진다.
  그 브랜치에서 같은 수정 필요
- **compose 의 `<<:` merge 는 `env_file`·`volumes` 를 병합하지 않고 대체한다.** worker 에 fcm.env 만
  추가하려면 앵커(`x-app-service`)의 목록을 **전부 다시 써야** 한다. 안 그러면 db/api/toss/auth/oauth
  env 와 logs·toss.p12 마운트가 통째로 사라진다
- **이번 실패에서 자격증명 유효성은 검증되지 않았다.** 로그에 `FirebaseApp`·`FCM 초기화 완료` 0건 —
  어댑터 빈 생성이 먼저 터져 `FirebaseConfig` 가 실행되지 않았다. 마운트 경로 오타가 남아 있어도 미탐지

### 좌표
- `infrastructure/adapter/out/notification/FirebaseAdminMessagingClient.java:54` (@Autowired 추가 지점)
- `common/config/FirebaseConfig.java:41` (자격증명 읽기, 실패 시 부팅 차단)
- VPS `/root/apps/nextroute/compose.yaml` worker 블록 / `env/fcm.env` / `/srv/nextroute/firebase-service-account.json`

### 배포
- VPS 에 배치 완료: `/srv/nextroute/firebase-service-account.json`(400 root, project `nextroute-4b01b`),
  `env/fcm.env`(600, `JOURNEY_NOTIFICATION_SENDER=fcm` + `FIREBASE_CREDENTIALS_PATH`)
- compose worker 패치는 **롤백해 둔 상태**. 시도본은 `compose.yaml.fcm-attempt.20260818`,
  직전 정상본 백업은 `compose.yaml.bak.20260818-1828`. 이 픽스가 배포된 이미지에 들어간 뒤에 재적용
- `JOURNEY_NOTIFICATION_RELAY_ENABLED` 는 미설정(기본 false) — sender 만 fcm 이어도 실제 발송은 안 나감
- iOS 전달에는 Firebase 콘솔에 APNs 인증키(.p8 + Key ID + Team ID) 등록이 별도로 필요(미확인)

### 검증
- `./gradlew test --tests '*FirebaseAdminMessagingClient*' --tests '*FirebaseConfigTest'` → 7 tests, 0 failures
- **가드가 실제로 잡는지 확인**: `@Autowired` 를 제거하고 재실행 → `sender 가 fcm 이면 창구 빈이
  만들어진다` 만 FAILED (2 tests, 1 failed). 복원 후 재통과
- `./gradlew build` → **265 classes / 1841 tests, 0 failures**
- 프로덕션: 롤백 후 worker `Up (healthy)`, `Started NextrouteApplication in 16.324 seconds`

---

## 2026-08-19 지하철 도착 조회 — 역 탐색 반경 단계 확대 (S)

브랜치 `fix/subway-arrival-station-radius-expand`.

### 확정
- `SubwayArrivalQueryService.resolveStation` 의 반경은 **고정 50m → 50m 시작, 50m 단위 확대,
  300m 한계**. 후보가 나오는 첫 반경에서 멈춘다.
- **50m 에서 잡히면 이 변경 전과 완전히 동일**하게 동작한다. 넓힌 반경은 50m 가 침묵할 때만 쓰인다.
  회귀 위험을 여기로 몰아 넣은 것이 설계 의도다.
- 선택 규칙(`selectStation`)은 **건드리지 않았다**. 확대는 "어떤 후보를 볼 것인가"만 바꾸고
  "후보 중 무엇을 고를 것인가"는 그대로다.
- 상수는 `INITIAL_RADIUS=50.0` / `RADIUS_STEP=50.0` / `MAX_RADIUS=300.0`, 테스트가 읽을 수 있게
  package-private. 후보 수 `CANDIDATE_LIMIT=5` 는 기존 값 유지.
- 반복 호출 대신 300m 단발 조회로 줄이자고 제안했으나 **사용자가 단계 확대를 선택**. 근거:
  좁은 반경 우선이 코드에 드러나는 편이 낫다. `NearbyTransitService.searchWithExpanding` 과
  같은 패턴이라 결도 맞는다.

### 함정
- **50m 로는 안 잡히는데 300m 로는 잡히는 역이 실재한다**(사용자 실측). 원인은 역 좌표와
  경로/GPS 좌표의 기준 차이(역사 중심 vs 승강장·출구). 반경을 고정으로 두면 사용자는 눈앞의
  역을 두고 "도착정보 없음"을 본다.
- `findNearby` SQL 에 `WHERE geom IS NOT NULL` 이 있다. `geom` 이 비어 있는 역은 **반경을
  아무리 넓혀도 안 나온다**. 이번 건의 주원인은 반경이라고 확인됐지만, 여전히 0 이 나오는
  자리가 있으면 `SELECT count(*) FROM subway_station WHERE geom IS NULL` 부터 보고
  `SubwayStationRepository.backfillGeom()` 을 쓴다.
- **미해결 위험 — 넓힌 반경에서의 노선 폴백.** `selectStation` 은 `lineId` 가 후보 중 어디에도
  없으면 `nearby.get(0)`(최근접, 노선 무관)로 떨어진다. 50m 일 때는 사실상 "같은 환승역의 다른
  호선"이라 손해가 작았지만, 300m 까지 넓히면 **다른 역**이 걸릴 수 있다. 그때 응답에는 역
  이름이 없어(`SubwayArrivalResponse` 에 `stationName` 없음) 사용자가 틀린 줄 모른다.
  → 후속: 응답에 `stationName`/`distanceMeters` 를 싣거나, 확대 경로에서는 노선 미일치 시
  빈 값으로 떨어뜨린다. 이번 범위에서 제외(사용자가 반경만으로 범위 확정).
- `JourneyTransitIdEnricher.resolveSubwayStation` 은 뒤에 `lineId.equals` + 이름
  `KNOWN_MATCH` 이중 검증이 있어 위 위험이 없다. 확대는 그쪽에 순이득.

### 좌표
- `application/arrival/service/SubwayArrivalQueryService.java:21` (반경 상수 3개)
- `application/arrival/service/SubwayArrivalQueryService.java:65` (확대 루프)
- `application/arrival/service/SubwayArrivalQueryService.java:80` (`selectStation`, 미변경)
- `domain/subway/repository/SubwayStationRepository.java:35` (`findNearby`, `geom IS NOT NULL`)
- `application/journey/service/JourneyTransitIdEnricher.java:123` (검증 있는 caller)

### 배포
- 마이그레이션 없음. VPS 수동배치 **불필요**.

### 검증
- `./gradlew test --tests '*SubwayArrivalQueryServiceTest*'` → **7 tests, 0 failures**
  (기존 3 + 신규 4). 기존 스텁이 `findNearbyStations(lat, lon, 50, 5)` 정확 인자 매칭인데
  루프 첫 반경이 그대로 50.0/5 라 **수정 없이 통과** — 무회귀의 실측 증거다.
- `./gradlew test --tests '*SubwayArrival*' --tests '*JourneyTransitIdEnricher*'
  --tests '*SubwayStationsAway*' --tests '*SubwayRealtimeArrivalReader*'`
  → **40 tests, 0 failures, 0 errors**
- 신규 계약 4개: ①50m 0 → 150m 에서 잡힘 ②50m 히트 시 100m 조회 안 함(`never()`)
  ③300m 까지 0 → 빈 값, 350m 조회 안 함 ④확대 반경에서도 요청 호선 선택 유지

---

## 2026-08-19 지도 버스정류장 스냅샷 API (`feat/map-bus-stop-snapshot`, PLAN=docs/plan/map-bus-stop-snapshot)

### 확정
- 지도 마커용 계약을 `nearby` 와 **분리**했다. 신규 `GET /api/map/bus-stops/snapshot` 이 서울 전체
  정류장을 한 번에 주고, 클라는 앱 시작 1회 받아 로컬에 둔다. 팬/줌에는 요청이 없다.
- `version = "<활성 정류장 수>-<max(updated_at) epoch초>"`. ETag 도 같은 값.
  `If-None-Match` 일치 → 304, **본문 생성·정류장 조회 없음**. 이게 기능의 존재 이유다.
- 프로브 쿼리와 본문 쿼리의 WHERE 절은 **같아야 한다** (`deleted_at IS NULL AND latitude IS NOT NULL
  AND longitude IS NOT NULL`). 다르면 버전은 안 바뀌는데 본문만 바뀌는 상태가 생긴다. IT 로 고정.
- 응답은 JSON **array-of-arrays** + `columns` 동봉. 키 반복 제거 + 표준 파서 사용.
  CSV 는 정류장명 이스케이프 규칙을 모바일이 구현해야 해서 기각.
- 좌표는 소수 5자리 절삭(≈1.1m). 행 순서는 `stop_id ASC` **고정** — 같은 데이터면 바이트가 같아야
  ETag 가 안정적이다.
- 캐시는 **직렬화된 바이트**로 들고 있는다(DTO 아님). 요청마다 1.3만 행 재직렬화가 최대 낭비.
  재빌드는 `synchronized` single-flight + 락 안 double-check.
- 버전 프로브는 60초 memoize. 304 가 요청마다 DB 를 때리지 않게 하는 것이 목적.
- Redis 기각(정적 페이로드, api 컨테이너 1개, 힙 1MB 미만). MVT 기각(네이버 SDK 미지원).
  `ShallowEtagHeaderFilter` 기각(매 요청 본문 전체 생성 + MD5 — 목적과 정반대).
- 워밍업은 `@Profile("!worker")`. worker 는 이 엔드포인트를 서비스하지 않으므로 힙만 먹는다.

### 함정
- **서버 PR 만으로는 끊김이 안 없어진다.** 원인 5개 중 ①20개 상한 ②줌 무관 반경 ③밀도 의존 확대루프
  ⑤응답 순서 뒤집힘만 죽는다. **④"응답마다 마커 전체 교체"는 클라만 고칠 수 있다.**
  거기에 12,986 마커 동시 렌더 = 프레임 사망이라 줌 게이팅/클러스터가 성능상 필수.
  → 클라 4항목을 `docs/plan/map-bus-stop-snapshot/HANDOFF.md` 로 인계. **판정은 실기기 프레임에서만 난다.**
- 프로브 시점과 본문 생성 시점 사이에 행이 들어오면 본문이 버전보다 **새로울** 수 있다. 이 방향은
  다음 프로브에서 재빌드로 수렴하므로 안전. 반대로 프로브를 본문 생성 **뒤로** 옮기면 낡은 본문이
  영구히 굳는다 — 옮기지 말 것.
- Mockito: `given(...)` **인자 안에서** helper 가 다시 stubbing 하면 `UnfinishedStubbingException`.
  projection fake 는 mock 대신 record 로 만들어야 한다 (`BusStopSnapshotServiceTest` 의 `StopRow`).
- `updated_at` 은 `timestamp without time zone`. Java 시각 객체로 꺼내면 존 해석이 끼어들고 드라이버
  매핑도 흔들린다 → `extract(epoch FROM max(updated_at))::bigint` 로 **SQL 에서** 변환. `long` 매핑 IT 확인됨.
- 페이로드 크기 첫 추정(753 kB)은 **틀렸다** — JSON 문법 오버헤드를 뺀 수치였다. 실측 raw 939,929 B.
- gzip 을 `application.yaml` 전역으로 켰다. 2KB 넘는 **모든** JSON 응답이 압축된다(경로검색 포함).
  의도된 side effect지만 리뷰에서 의식할 항목.
- 작업 시작 시 `application.yaml` 에 **다른 세션의 미커밋 변경**(`JOURNEY_SUBWAY_RING_PUSH_ENABLED`
  기본값 true→false)이 있었다. 보존하고 내 커밋에서 제외했다(백업:
  scratchpad `foreign-ring-push-flag.patch`). 이 PR 에 그 플래그 변경은 없다.
- 스냅샷 필터는 좌표 컬럼 기준, 기존 `findNearby` 는 `geom IS NOT NULL` 기준으로 **계약이 다르다**.
  현재 프로덕션은 결과가 같다(12986행 전부 geom·좌표 있음, 소프트삭제 0건).
- IT 스키마에서 `geom` 컬럼을 뺐다(postgis 없는 `postgres:17` 사용). `findNearby` 를 이 IT 에
  추가하려면 postgis 이미지로 바꿔야 한다.

### 좌표
- `infrastructure/adapter/in/api/map/MapBusStopController.java` (신규, ETag/304 직접 처리)
- `application/map/service/BusStopSnapshotService.java` (신규, 프로브 memoize + 바이트 캐시)
- `application/map/service/BusStopSnapshotWarmup.java` (신규, `@Profile("!worker")`)
- `application/map/port/in/GetBusStopSnapshotUseCase.java`, `application/map/dto/BusStopSnapshotPayload.java`
- `domain/bus/repository/BusStopRepository.java:59` (`findAllForSnapshot`), `:75` (`probeSnapshotVersion`)
- `domain/bus/repository/BusStopSnapshotProjection.java`, `BusStopSnapshotVersionProjection.java` (신규)
- `domain/bus/service/BusDataService.java:213` (스냅샷 위임 2개)
- `src/main/resources/application.yaml:50` (`server.compression`)
- 문제 원인 쪽: `application/nearby/service/NearbyTransitService.java:27-30`(상수), `:119`(확대 루프)

### 배포
- **마이그레이션 없음. VPS 수동배치 불필요.** api 컨테이너 재배포만.
- 배포 후 확인: `curl -H 'Accept-Encoding: gzip' -D- -o/dev/null https://<host>/api/map/bus-stops/snapshot`
  → `ETag` 존재 + gzip `Content-Length` 약 233 kB, 같은 ETag 재요청이 304.

### 검증
- `./gradlew compileJava` → 성공
- `./gradlew test --tests '*BusStopSnapshotServiceTest*' --tests '*MapBusStopControllerTest*'`
  → **14 tests, 0 failures** (서비스 9: 버전 포맷/빈 표/좌표 절삭/열 순서/arsId null 유지/캐시 재사용
  /프로브 memoize/버전 변경 재빌드/좌표 null 스킵, 컨트롤러 5: 200 본문·헤더/304 시 `snapshot()`
  `never()`/weak ETag 304/stale 200/ETag 는 본문 버전 추종)
- `./gradlew test --tests '*BusStopSnapshotQueryIT*'` → **3 tests, 0 failures**
  (소프트삭제·좌표null 제외 + `stop_id` 정렬 / 프로브 count·epoch 매핑 / 빈 표 0-0)
- 실데이터 페이로드 실측(프로덕션 12,986행에서 동일 형태 JSON 생성): raw **939,929 B**,
  gzip -6 **238,846 B (233 kB)**. PLAN 기준 400 kB 이하 통과.
- 미검증(코드로 판정 불가): 실기기 지도 조작 체감. 클라 4항목 구현 후 프레임으로 판정해야 한다.

#### 추가 (2026-08-19, 사용자 요청)
- `Cache-Control` max-age **1시간 → 1주일**(`maxAge(7, TimeUnit.DAYS)`, 604800s). 정류장 표는 며칠~몇 주
  단위로나 변해서 1시간 재검증은 얻는 것 없이 왕복만 만든다.
  대가: 공유 캐시(프록시/CDN)는 이 기간 재검증하지 않으므로 변경 시 최대 1주일 낡은 응답 가능.
  앱은 이 헤더를 따르지 않고(자기 로컬 저장소 + 시작마다 If-None-Match) 영향 없음.
- 검증: `./gradlew test --tests '*MapBusStopControllerTest*'` → 5 tests, 0 failures
  (헤더 단정 `max-age=604800, must-revalidate, public` 로 갱신)

## PR1 — Bus 데이터 접근 deepen (domain/bus/service 재편, refactor/bus-data-access-deepen)

### 확정
- `BusDataService`(37 public 메서드, caller 10개) 삭제. 진짜 동작 있는 4개만 남기고 재편:
  `BusCatalogWriter`(upsertRoutes/upsertStops/replaceRouteStops), `BusArrivalCandidateStore`(saveAllArrivalCandidates,
  lifecycle_id 멱등 insert), `BusArrivalLabelStore`(label 6종 + flushAndClear, EntityManager 배치 경계).
- 순수 위임 21개(1~2줄)는 모듈로 옮기지 않고 caller 가 `BusRouteRepository`/`BusStopRepository`/`BusRouteStopRepository`
  /`BusPositionRawRepository` 를 직접 주입받게 전환. 새 port/out 만들지 않음 — Spring Data repository interface 가
  이미 seam(adapter 하나뿐인 port/out 이 45개 중 36개라 가설적 seam 이 이미 넘치는 상태였음).
- 죽은 메서드 11개(`saveRoute`, `saveAllRoutes`, `saveStop`, `saveAllStops`, `saveAllRouteStops`, `saveAllArrivals`,
  `existsRouteByRouteId`, `existsStopByStopId`, `existsRouteStop`, `findLatestArrivalsByStopId`,
  `countLabelEventsByServiceDate`) repo-wide grep 0 hit 재확인 후 삭제.
- projection 타입 5개 domain 으로 하향: `BusRouteStopMatchData`/`BusPositionLabelRow`/`BusArrivalCandidateLabelRow`
  → `domain/bus/repository`(JPQL constructor expression 대상이라 repository 소속), `BusRoutePolylineData`/
  `BusRoutePolylineSnapshot` → `domain/bus/service`(`BusRoutePolylineDataService` 전용, repository 아님).
  domain→application 역참조 6건 중 5건(bus) 제거, 1건(`OdsayRoutePolylineDataService`→route.dto)은 route 소관이라
  이번 PR 범위 밖으로 의도적으로 남김.

### 함정
- `application.bus.dto` 패키지 안에서 `BusRoutePolylineData`/`BusRouteStopMatchData` 를 같은 패키지 암묵 참조로
  쓰던 파일이 3개 있었음(`BusRouteSegmentPolylineData`, `BusRouteSegmentPolylineRange`, `BusRouteSegmentPolylineSection`
  — `List<BusRoutePolylineData.Point>` 필드). 이동 후 import 누락으로 컴파일 실패 ← 최초 grep(`import ...dto.X`)이
  같은 패키지라 import 문 없는 참조를 못 잡았음. 패키지 이동 시 "이 타입을 참조하는 파일" 은 import 문 grep 만으로
  불충분, `grep -n '<TypeName>'` 로 FQCN 없는 same-package 참조도 같이 훑어야 함.
- `@Query` JPQL 문자열 리터럴 안의 `new watoo.grd.nextroute.application.bus.dto.X(...)` constructor expression 은
  import 문이 아니라 문자열이라 IDE/grep import 검색에 안 걸림. `BusRouteStopRepository`/`BusPositionRawRepository`/
  `BusArrivalCandidateRawRepository` 3곳에서 FQCN 문자열을 직접 sed 치환해야 했음.
- `NearbyTransitService.findSupportedStopIds` 를 caller 로 인라인하면서 `stopIds.isEmpty()||routeIds.isEmpty()` 가드가
  caller 코드로 옮겨감. 기존 테스트 2개(`getNearbyBusStops_mapsProjectionToResult`,
  `_stopsExpandingWhenResultsFound`)가 `predictionSupportService.supportedRouteIds()` 를 빈 Set 으로 스텁해서
  가드가 리포지토리 호출 자체를 막음 → `UnnecessaryStubbingException`(Mockito strict stubs). 가드 인라인은 caller
  쪽 스텁을 "레포 호출 없음" 전제로 다시 봐야 한다는 뜻 — 단순 리네임이 아니라 제어흐름 이동임을 놓치기 쉬움.

### 좌표
- 신규: `domain/bus/service/{BusCatalogWriter,BusArrivalCandidateStore,BusArrivalLabelStore}.java`
- 이동: `domain/bus/repository/{BusRouteStopMatchData,BusPositionLabelRow,BusArrivalCandidateLabelRow}.java`,
  `domain/bus/service/{BusRoutePolylineData,BusRoutePolylineSnapshot}.java`
- 삭제: `domain/bus/service/BusDataService.java`
- caller 10개 전환: `application/notification/service/BusAlertService.java`,
  `application/bus/service/{BusRouteSegmentPolylineBuildService,BusArrivalLabelGenerationService,BusPositionService,
  BusStaticDataService,BusArrivalService}.java`, `application/nearby/service/NearbyTransitService.java`,
  `application/map/service/BusStopSnapshotService.java`, `application/arrival/service/BusArrivalQueryService.java`,
  `application/stopselection/service/StopSelectionService.java`

### 배포
- 마이그레이션 없음. 스키마 변경 없이 코드 재배선만. VPS 수동배치 불필요, 통상 배포로 충분.

### 검증
- `./gradlew compileJava compileTestJava` → 성공 (경고만: 기존 `@MockBean` deprecation, 이번 PR 무관)
- `./gradlew test --tests "*Bus*"` → 231 tests, 8 failed. 실패 4개 클래스 전부
  `journeyNotificationProperties`("relay 켜면서 sender 를 noop 으로 두면 안 된다") 빈 검증 실패로 귀결 —
  `main` 브랜치 기준선에서 동일 필터로 재현(19 tests, 19 failed) 확인 완료, **기존 무관**.
  bus 리팩터가 만든 실패는 0건 (`GetNearbyBusStopsUseCaseTest` 2건은 위 가드 인라인 때문에 스텁 제거로 수정 완료).
- `grep -rn "BusDataService" src` → 0 hit
- `grep -rn "^import watoo.grd.nextroute.application" src/main/java/.../domain/bus` → 0 hit

## PR2 — Subway 데이터 접근 deepen (domain/subway/service 재편, refactor/subway-data-access-deepen)

### 확정
- `SubwayDataService`(41 public 메서드, caller 8개) 삭제, PR1(bus)과 동일 원칙 적용 — 사용자 지시로 "PR1과 동일(직접주입)" 확정.
- 실측: 41개 중 33개가 본문 1줄 순수 위임. 죽은 메서드 14개(repo-wide 0 hit 확인 후 삭제):
  `countDelayTruthByServiceDate`, `countSegments`, `existsByStationId`, `findByLineIdAndTagoStationId`,
  `findByStationId`, `findByStationIdAndLineId`, `findByStationNameLikeAndLineName`, `findDelayTruthByServiceDate`,
  `findLatestArrivalsByStationId`, `findStationsByLine`, `findStationsByLineIdIn`, `findTimetableCoverage`,
  `saveAllArrivals`, `saveAllStations`(3줄 real logic 있었지만 dead라 원칙대로 삭제 — depth 있어도 안 쓰이면 안 남김).
- 새 domain 모듈 3개, PR1의 "caller 가 repo 3개+ 이상 묶어써야 하면 모듈로, 아니면 caller 가 repository 직접 주입" 판단을
  그대로 적용:
  - `SubwayArrivalRawIngestStore` — `insertArrivalRawIgnoreDuplicates`(22줄, code1 카운트 집계 real logic) 단독.
  - `SubwayDelayTruthStore` — `MlSubwayDelayTruthRepository`(delete+save) + `EntityManager`(flush) 2콜라보레이터.
    caller=`SubwayDelayTruthGenerationService` 전용.
  - `SubwayMatchIssueStore` — `SubwayArrivalEventMatchIssueRepository`(delete+save+find×2) + `EntityManager`.
    caller 2개(`TimetableMatchingService` 전체 사용, `SubwayInferredArrivalCompletionService` 는 find 만 사용) —
    2 caller 공유라 모듈로 남길 근거가 bus 보다 강함("두 caller 가 진짜 seam 을 정당화").
  - 순수 위임(캐시 없는 1~2줄)은 caller 가 `SubwayStationRepository`/`SubwaySegmentRepository`/
    `SubwayTimetableRepository`/`SubwayArrivalEventRepository`/`SubwayArrivalRawRepository` 직접 주입 —
    caller당 최대 4 repo(EntityManager 없음)까지는 직접주입, EntityManager 결합되면 모듈로 격상(PR1과 동일 경계).
- projection 없음(bus 와 달리 subway 는 애초에 domain→application 역참조가 없었음 — subway 쪽 project 타입은
  처음부터 domain 패키지에 있었음). 그래서 이번 PR 은 타입 이동 작업이 없음.
- `findPrevDepartureCandidatesInRangeNarrowed` 의 `stationIds.isEmpty()` 가드는 caller
  (`SubwayInferredArrivalCompletionService`)로 인라인 — PR1의 `findSupportedStopIds` 처리와 동일 패턴.
- `updateCoordinates`(findById+ifPresent 엔티티 메서드 호출)는 caller(`SubwayCoordinateEnrichService`)로 직접
  인라인 — 2줄짜리 load-mutate 는 깊이 없음.

### 함정
- `domain/subway/service/SubwayDataServiceTest.java` 는 `@SpringBootTest` 로 컨텍스트만 띄우고 `assertEquals` 없이
  `System.out.println` 만 하는 스모크 테스트였음. `SubwayDataService` 삭제 대상이라 검증 없이 삭제 — 남길 이유 없음
  (테스트가 검증하는 게 없어서 "이 로직을 어디로 옮길지" 판단 자체가 불필요).
- 8개 mock 기반 테스트 전환 중 `verify(...)` 가 멀티라인(메서드명이 다음 줄에 있는 형태)일 때 sed 한 줄 패턴이
  못 잡음 — `SubwayInferredArrivalCompletionServiceTest`(2곳), `SubwayArrivalQueryServiceTest`(2곳)에서 컴파일
  에러로 드러난 뒤 수동 수정. PR1 에서도 동일 함정이 있었는데 이번에도 반복됨 — sed 로 caller 치환할 때
  `verify(x, never())\n.method(...)` 형태의 개행 분리 호출은 grep -n 으로 컴파일 에러 나기 전엔 못 잡는다는 걸
  다시 확인.
- `TimetableMatchingService`, `SubwayInferredArrivalCompletionService` 둘 다 `findMappableStations()` 를 쓰는데
  실체가 `subwayStationRepository.findByTagoStationIdIsNotNull()` 이었음 — 메서드명이 원본 리포지토리 메서드명과
  달라서 grep 만으론 안 보이고 body 를 직접 읽어야 확인됨(PR1 의 `saveAllArrivalCandidates` 류 이름 불일치와 같은 패턴).

### 좌표
- 신규: `domain/subway/service/{SubwayArrivalRawIngestStore,SubwayDelayTruthStore,SubwayMatchIssueStore}.java`
- 삭제: `domain/subway/service/SubwayDataService.java`, `src/test/.../domain/subway/service/SubwayDataServiceTest.java`
- caller 8개 전환: `application/subway/service/{SubwayInferredArrivalCompletionService,SubwayDelayTruthGenerationService,
  SubwayArrivalEventDerivationService,SubwayArrivalRawRecorder,SubwayStaticDataService,SubwayCoordinateEnrichService,
  SubwayStationAdjacency,SubwaySegmentLookup,TimetableMatchingService}.java`,
  `application/nearby/service/NearbyTransitService.java`(subway 부분만),
  `application/arrival/service/SubwayArrivalQueryService.java`

### 배포
- 마이그레이션 없음. 스키마 변경 없이 코드 재배선만. VPS 수동배치 불필요.

### 검증
- `./gradlew compileJava compileTestJava` → 성공
- `./gradlew test --tests "watoo.grd.nextroute.application.subway.service.*" --tests
  "watoo.grd.nextroute.application.nearby.port.in.GetNearbyBusStopsUseCaseTest" --tests
  "watoo.grd.nextroute.application.arrival.service.SubwayArrivalQueryServiceTest"` → 성공(exit 0), 관련 unit
  테스트 전부 통과.
- `./gradlew test --tests "*Subway*" --tests "*NearbyBusStops*"` → 365 tests, 108 failed. 실패 전부
  `journey/*IT` 컨텍스트 로드 실패로, root cause 는 `journeyNotificationProperties`("relay 켜면서 sender 를 noop
  으로 두면 안 된다") — PR1 조사에서 이미 main 기준선 재현 확인된 **기존 무관** 실패와 동일 시그니처.
  실패 클래스 8개 표본 확인 결과 이번에 옮긴 도메인 클래스(`SubwayStationRepository` 등) 참조 0건 — 완전 무관.
- `grep -rn "SubwayDataService" src` → 0 hit
- `grep -rn "^import watoo.grd.nextroute.\(application\|infrastructure\)" domain/` → route/polyline 1건만
  (이번 PR 범위 밖, 의도적으로 남김), subway 관련 0건.

## account-withdrawal (2026-08-27)

### 확정
- 삭제 경계: 하이브리드(발송/로그인수단 물리삭제, journey기록 소프트삭제). 근거 docs/plan/account-withdrawal/ADR-deletion-boundary.md, 용어 CONTEXT.md
- 마이그레이션 0: users.deleted_at 은 V1 baseline 기존재, 삭제대상 22테이블 전부 deleted_at 보유
- END(Live Activity 종료 푸시) 미포함: 릴레이가 토큰 살아있어야 발송하는데 탈퇴가 그 토큰 지움 → 양립불가. 위젯은 앱이 로컬로 그리다 만료
- PR 분할: PR1=인증가드(브랜치 feat/account-withdrawal-auth-guard), PR2=탈퇴본체

### 함정
- .gitignore 가 docs/* 무시, docs/plan/ · docs/mobile/ 만 화이트리스트 → docs/adr/ 커밋 안 됨. ADR 을 docs/plan/account-withdrawal/ADR-deletion-boundary.md 로 이동함
- TokenGenerationIT(및 모든 @SpringBootTest) 로컬 컨텍스트 로드 실패 ← JourneyNotificationProperties:197: relay.enabled 기본 true + sender 기본 noop 조합 거부. 기존 무관(clean main 도 동일 실패). 가드=@TestPropertySource 에 journey.notification.relay.enabled=false 추가

### 좌표
- 인증 세대검증: JwtAuthFilter.isStillValid → TokenGenerationPort → UserTokenGenerationAdapter (deleted_at 인지로 교체)
- SessionService.requireUser: findById → findByIdAndDeletedAtIsNull (reissue 우회 차단)
- SubwayRingPushRelayService.java:145 "보낸 뒤에만 지운다" — END 와 탈퇴 양립불가 근거

### 검증
- PR1: ./gradlew compileJava compileTestJava = OK
- PR1: SessionServiceTest (단위) = 통과
- PR1: TokenGenerationIT 12 tests 통과 (신규 탈퇴가드 2개 포함: access/refresh 세대맞아도 deleted_at 이면 401)

### 미결
- Apple /auth/revoke: authorization code 교환 경로 신설 필요, 앱심사 제출 전 필수, 별건 PR
- 토스 unlink 콜백 계정 미삭제 비대칭(현재 토큰만 폐기)
- codex 플랜 리뷰 반영 완료(2026-08-27): 누락 favorite_route·route_search_log 물리삭제 추가, journey_notification(V40)·subway_leg_binding(V44) 삭제금지→손대지않음, users 벌크update(detach 함정), 스키마테스트 SQL(referential_constraints+순환가드+public필터)

## account-withdrawal PR2 (2026-08-27, 이어서)

### 확정
- #165(PR1 가드) main 병합 완료(2af36fe). PR2 브랜치 feat/account-withdrawal = fresh main 기준
- **CI 에 Java 테스트 워크플로 없음**(.github/workflows=ml-image.yml 뿐). → 경계 IT 는 로컬 전용 게이트. untracked V44~V47 마이그레이션 CI 미반영 무관. IT 는 Flyway full migrate(flyway.enabled=true, classpath:db/migration)로 구성
- .gitignore 마이그레이션 화이트리스트 V43 까지만. V44~V47 은 gitignore 처리(untracked, 디스크엔 존재)
- FK 그래프 실측 2단(users→직접자식→alert/journey손자). WITH RECURSIVE 불필요, 명시적 2단 쿼리로 완전성 검사

### 좌표 (엔티티 FK 필드)
- 물리 userId(Long) 필드: AuthIdentity.userId, UserPushToken.userId, SubwayLiveActivityToken.userId
- 물리 user(User) 관계(→ .user.id): RouteArrivalAlert, BusArrivalAlert, RouteAlertSchedule, FavoriteRoute
- 물리 alert(관계, → .alert.id): RouteArrivalAlertNotification, BusAlertNotification
- 물리 journeyId: SubwayRingPushOutbox.journeyId (→ journeyId in (select JourneySession.id where userId))
- RouteSearchLog: 필드명 userId 인데 타입 User(user_id nullable) → l.userId.id
- RouteSearchHistory: 기존 deleteByUser_Id(Long) 재사용. TossUserToken: 기존 deleteByUserKey(Long)
- 소프트 userId 직접: JourneySession.userId, JourneyTelemetryEvent.userId, RerouteProposal.userId(+journeyId)
- 소프트 journeyId: JourneyLeg, TransferConnection, JourneyEvent, JourneyRevision, SubwayBindingEvent(+journeyLegId)
- 소프트 journeyLegId: JourneyLegRouteOption (→ 2단 서브쿼리 journeyLegId in (select JourneyLeg.id where journeyId in (...)))
- BaseEntity.deletedAt=LocalDateTime, markDeleted(). User.bumpTokenGeneration(), tossUserKey
- 컨트롤러 패턴: MeController @RequestMapping("/api/me") @AuthenticationPrincipal Long userId + @ExceptionHandler(SessionException). Withdraw 는 신규 @RequestMapping("/api/auth/me") DELETE 204

### 함정 (PR2 구현 실측)
- 경계 IT 스키마: **Flyway full migrate 불가**. V1__baseline 은 pg_dump 스냅샷이라 선형 replay 안 됨(prod 는 baseline-on-migrate+baseline-version:1 로 V1 미실행). 증거: myuser 롤 없음→geography 타입 없음(postgis)→spatial_ref_sys 중복→subway_station_source_id_seq 미생성→V3 가 V1 미포함 subway_arrival_raw 참조. V19~V32 가 V1 버스테이블 FK 참조라 V1 스킵도 불가
- **해결: ddl-auto=create-drop 로 엔티티에서 스키마 생성**(flyway off, plain postgres:17). 엔티티에 JTS/geometry 필드 없음(geom 은 DB전용 네이티브)라 가능. 컬럼 정합성은 기능별 스키마 IT 가 이미 커버, 경계 IT 는 서비스 행위 계약 담당
- 좌표컬럼: `startX`→`startx` (CamelCaseToUnderscores 가 끝자리 단일 대문자에 underscore 안 붙임). route_arrival_alert/route_alert_schedule/route_search_history/route_search_log 전부 startx/starty/endx/endy (엔티티 기준). `sequence` 는 SQL 예약어→따옴표
- enum 전부 @Enumerated(STRING)=varchar+check. checkpoint 는 JourneyCheckpoint(TRANSFER_RISK_AT_RISK 등), 'CP' 같은 임의값 check 위반
- **clearAutomatically 함정 재현**: 파생 delete(deleteByUser_Id, deleteByUserKey)는 영속성 컨텍스트 remove 에 의존 → 뒤이은 bulk @Modifying(clearAutomatically=true, flushAutomatically=false)가 flush 전 컨텍스트 비워 삭제 유실. route_search_history 가 안 지워져서 발견. **전 삭제를 bulk @Modifying 으로 통일**(즉시 DML)로 해결. toss 도 동일 위험이라 deleteAllByUserKey bulk 추가
- **보안 갭 발견**: SecurityConfig 가 DELETE /api/auth/me 미보호 → permitAll 로 떨어짐(익명 탈퇴 가능). `.requestMatchers(HttpMethod.DELETE,"/api/auth/me").authenticated()` 추가

### 검증
- compileJava/compileTestJava = OK
- WithdrawalDeletionBoundaryIT 7개 + WithdrawControllerTest 2개 = 통과(BUILD SUCCESSFUL). 버킷3종/users최종/JPA재조회/멱등/타사용자격리/FK그래프완전성/401·204 커버

## 2026-08-27 apple-revoke (PR-A 로그인 code 교환+저장)

### 확정
- **V48 는 untracked(VPS 수동배치)**. .gitignore 화이트리스트 확인: `!...V43__*` 까지만 등록,
  V44~V47 전부 untracked. 관례는 V18+ 미커밋. → V48 도 커밋 안 함(git add 강제 금지)
- **owner to 절 생략**. 마이그레이션 66개 중 owner 절은 V47 1개뿐 → V46 스타일(references users(id),
  created/updated not null) 따름
- **토큰 교환은 login() @Transactional 안에서**. 외부 HTTP 가 tx 안이지만 실사용자 0·저빈도라 수용.
  교환 실패 = 로그경고 후 로그인 성공 유지(로그인 막지 않음). code 누락(APPLE) = 400
- SecretCipher 키 = env `APP_SECRET_ENC_KEY`(Base64 32B), 생성자 주입. 단위테스트는 env 대신
  생성자로 키 직접 주입. tossRestClient 처럼 미설정이면 로그경고+실호출만 실패(부팅은 됨)
- 새 Apple config 필드(clientId/teamId/keyId/privateKeyPath/tokenBaseUrl)는 validate() 하드체크
  안 넣음 — .p8 없이도 부팅돼야 함(tossRestClient keystore 선례)

### 좌표
- application/auth/service/OAuthLoginService.java:login — APPLE 검증 성공 후 교환·저장 훅
- infrastructure/adapter/out/auth/AppleTokenExchanger.java — POST /auth/token
- common/security/SecretCipher.java — AES-GCM
- infrastructure/adapter/out/auth/AppleClientSecretJwtBuilder.java — ES256 client_secret

### 배포
- **V48__create_apple_user_token.sql = VPS 수동배치 필수**(untracked, CI 미반영)
- 신규 시크릿: .p8 볼륨 마운트 + env(OAUTH_APPLE_CLIENT_ID/TEAM_ID/KEY_ID/PRIVATE_KEY_PATH,
  APP_SECRET_ENC_KEY) → compose.yaml. 모바일이 authorizationCode 동반 전송 필요(HANDOFF)

### 검증 (PR-A 2026-08-27)
- `./gradlew compileJava compileTestJava` 성공
- `./gradlew test --tests '*SecretCipher*' '*AppleClientSecret*' '*AppleTokenExchang*'
  '*OAuthLoginServiceTest' '*AppleIdentityVerifier*' '*KakaoIdentityVerifier*'` BUILD SUCCESSFUL
- SecretCipher: round-trip/IV상이/변조거부/키길이/미설정부팅 5, ClientSecret ES256 서명·클레임,
  Exchanger MockWebServer 성공·refresh없음·HTTP에러 3, LoginService 애플 code필수·암호저장·카카오제외·
  교환실패도로그인성공 4

### 함정 (PR-A)
- SecretCipher 생성자 2개(String @Value / byte[] test) → @Autowired 로 DI 생성자 명시(메모리
  fcm-live-activity 함정 재현 방지). 빈 키는 부팅 통과·use 시 예외(tossRestClient 선례)
- OAuthCredential 레코드에 authorizationCode 5번째 필드 추가 → 기존 호출부 4곳(verifier 테스트)
  전부 null 인자 보강 필요

## 2026-08-27 apple-revoke (PR-B 탈퇴 시 revoke)

### 확정
- **revoke = 삭제 tx 밖·앞, best-effort**. WithdrawController 가 revokeIfApple(userId) 먼저 →
  withdrawUseCase.withdraw(userId). WithdrawService 내부 무수정(단일 tx·외부호출0 계약 유지)
- **순서 이유**: 삭제 tx 가 apple_user_token 을 지우기 전에 그 토큰을 읽어야 함. 그래서 앞에서 호출
- AppleRevokePort(out) + AppleTokenRevoker 어댑터 = AppleTokenExchanger 패턴 복제(appleRestClient
  @Qualifier + clientSecretBuilder.build()). POST /auth/revoke, 본문없는 200 = 성공
  (.toBodilessEntity()). client_id/client_secret/token/token_type_hint=refresh_token
- AppleRevocationOnWithdrawal(application.service): findByUserId → decrypt → revoke, 모든 예외 삼킴
  (탈퇴 막지 않음). 토큰 없으면(애플 아님) 조용히 넘어감
- WithdrawService 물리삭제에 appleUserTokenRepository.deleteAllByUserId 추가(로그인 수단 그룹,
  authIdentity 다음). deleteAllByUserId 는 PR-A 가 이미 벌크 DML 로 넣어둠(clearAutomatically)

### 좌표
- application/auth/port/out/AppleRevokePort.java — revoke 포트
- infrastructure/adapter/out/auth/AppleTokenRevoker.java — POST /auth/revoke
- application/auth/service/AppleRevocationOnWithdrawal.java — tx밖 조율, 예외 삼킴
- infrastructure/adapter/in/api/auth/WithdrawController.java:withdraw — revokeIfApple 앞·withdraw 뒤

### 함정 (PR-B)
- WithdrawController 에 의존성 추가 → WithdrawControllerTest 에 @MockBean AppleRevocationOnWithdrawal
  누락 시 컨텍스트 로드 실패. InOrder 로 revoke→withdraw 순서 고정
- WithdrawalDeletionBoundaryIT: PHYSICAL + userRowCount switch(user_id 그룹) + seedAll + setUp
  cleanup 4곳 전부 apple_user_token 반영해야 everyChildTableIsAccountedFor(users 자식) 통과

### 배포 (PR-B)
- 신규 마이그레이션 없음(V48 은 PR-A). 코드만. 새 env·시크릿 없음
- 모바일 HANDOFF 는 PR-A 와 동일(authorizationCode 동반). PR-A/PR-B 동시 릴리스

### 검증 (PR-B 2026-08-27)
- `./gradlew compileJava compileTestJava` 성공(기존 @MockBean deprecation 경고만)
- `./gradlew test --tests '*AppleTokenRevoker*' '*AppleRevocationOnWithdrawal*'
  '*WithdrawControllerTest' '*WithdrawalDeletionBoundaryIT'` BUILD SUCCESSFUL
- Revoker MockWebServer 성공(form 검증)·HTTP에러전파 2, Revocation 토큰있음호출·토큰없음무동작·
  실패삼킴 3, Controller revoke→withdraw 순서·401 2, BoundaryIT 7(apple_user_token 물리0 포함)

## recent-place-flatten (2026-08-28)
### 확정
- GET /api/route/my/searches 응답: 출/도착 쌍(row) → 좌표 dedup 최근 장소 리스트. 항목 {id,lat,lng,name,searchedAt}
- 좌표 매핑 lat=Y(startY/endY), lng=X(startX/endX). DB는 X=경도,Y=위도
- dedup 대표 = putIfAbsent + 포트의 (searched_at DESC, id DESC) 사전정렬 활용 → 명시적 max 불필요
- 삭제경로 무변경: 대표 id=실제 row id라 기존 deleteByIdAndUser 그대로. Q11=A 반쪽삭제(같은좌표 다른행 남으면 재등장) 감수
- 마이그레이션 없음(앱단 평탄화), 포트/repository/엔티티/저장경로 무변경
### 함정
- 엔티티 id 는 BaseEntity 소속 → subclass @Builder 로 못 넣음. 테스트는 ReflectionTestUtils.setField(h,"id",..)
- 계약 breaking(flat 필드 폐기) → 모바일 동시배포 필요
### 좌표
- 평탄화 로직 RouteSearchHistoryService.java:getAll (putIfAbsentPlace)
### 검증
- ./gradlew compileJava compileTestJava → OK (경고=기존 @MockBean deprecation, 무관)
- ./gradlew test --tests *RouteSearchHistoryServiceTest(7) --tests *RouteSearchHistoryControllerTest(5) → 12 pass, 0 fail
- 계약 테스트: 평탄화·좌표매핑/dedup대표/tie-break/빈리스트 각 1개

## place-search-merge (feat/place-search-merge) — 2026-09-07

### 확정
- 서버 통합 장소검색: 네이버(주, 전부 상단) + 카카오(dedup 보강). 승인된 플랜.
- 네이버 = NAVER API HUB(ntruss), env NAVER_CLIENT_ID/NAVER_SECRET. 카카오 = dapi.kakao.com KakaoAK(KAKAO_REST_API_KEY).
- dedup: 정규화 이름 일치 AND 좌표 ≤50m → 카카오건 버림(source 병합 안 함).
- 네이버 실패=카카오 단독+degraded, 카카오 실패=네이버+degraded, 둘 다 실패=503. outcome full/partial.
- limit: @Max(5)→@Max(15) 기본15. 네이버 display=min(limit,5), 카카오 size=min(limit,15).
- 기존 KakaoApiPort.searchSubwayStation(SW8, SubwayCoordinateEnrichService 전용)은 손대지 않음. 신규 KakaoLocalSearchPort 별도.

### 좌표
- 기존 네이버: application/place/**, adapter/out/api/naver/**, adapter/in/api/place/PlaceSearchController (전부 untracked, Sep4)
- PlaceSearchResult.Item{key,name,address,roadAddress,lat,lng,category,sources[]} + Meta{outcome,degraded[]} 이미 멀티프로바이더 설계
- RestClient @Primary bean = common/config/ApiClientConfig (SimpleClientHttpRequestFactory, 3s/5s)

### 배포
- 마이그레이션 없음. VPS env NAVER_API_HUB 키 이미 세팅 가정(api.env 확인함).

### 검증 (place-search-merge)
- `./gradlew compileJava compileTestJava` 통과
- `./gradlew test --tests *PlaceDeduplicatorTest *PlaceSearchServiceTest *KakaoLocalSearchAdapterTest *NaverApiHubLocalSearchAdapterTest` → BUILD SUCCESSFUL
- 계약 테스트: dedup(이름+50m 경계/원거리/타상호), 조합(네이버선두+카카오dedup), 네이버실패=카카오폴백+degraded, 카카오실패=네이버+degraded, 둘실패=503, limit provider별 클램프(5/15)
### 함정
- KakaoLocalSearchAdapter URL 하드코딩→MockWebServer 라우팅 불가. base-url @Value 주입식(kakao.api.local-base-url, 기본 dapi.kakao.com)으로 고쳐 네이버 어댑터 테스트 패턴 미러
- application.yaml 에 무관 변경(transfer-arrival cache-ttl 15→30) 섞여 있어 naver 블록 hunk 만 git apply --cached

## national-otp-p2 매칭 실증 (2026-09-08, VPS 실 DB + OTP fixture 대조)
목적: P2-D 매핑 "구조가 실제로 성립하나" 검증. 실 DB(vps postgres myuser/mydatabase, 읽기전용) + OTP planConnection fixture(src/test/resources/otp/, tools/transit-routing-benchmark/results/otp-seoul-p1/).

### 확정 (실측)
- **버스 매핑 = 결정적 prefix strip. 퍼지 불필요.**
  - OTP stop `1:BS_TAGO_SEB<9digit>` → `SEB` 떼면 bus_stop.stop_id (1:1). fixture 9/9 존재, stop_name 정상(광화문역·롯데백화점·고속터미널 등).
  - OTP route `1:BR_TAGO_SEB<9digit>` → bus_route.route_id (1:1). fixture 5/5, route_name==OTP shortName(143/150/501/641/643).
  - bus_stop.stop_id 전부 9자리(12986행), bus_route.route_id 전부 9자리(728). 포맷 안정.
- **지하철 매핑 = 좌표+라인 확정. id 도 decode 됨.**
  - OTP subway feed=`ACC1`, stop `1:RS_ACC1_S-1-<line2><seq2>`, route `1:RR_ACC1_S-1-04-1D`(shortName=서울4호선).
  - ~~OTP `S-1-0427`→statn_id `1004000427`(숙대입구)… 공식 statn_id=`100`+lineNo+`00`+tail~~ **취소: id decode 는 4호선 2샘플만 우연 일치. 일반화 불가(아래 정정 참조)**.
  - **1차 키는 좌표**: OTP stop lat/lon vs subway_station.geom 거리 = 2.9m / 2.0m(사실상 동일). line 은 route(서울4호선)로 disambiguate(동명역 동대문역사문화공원=1002/1004 등 존재).

### 함정
- **지하철 OTP feed(ACC1, `S-1-04` 스킴) ≠ DB subway_station.tago_station_id(MTR 스킴, 예 MTRS14427)**. tago 매핑은 딴 소스(TAGO 실시간). OTP↔statn_id 는 별도 decode, MTR 재사용 금지.
- bus_stop 첫자리 `1`(11241 서울)·`2`(1745 경기권) 2군. OTP prefix `SEB`=서울 기준 확인됨. 경기권 정류장의 OTP prefix(GB/IB?) 는 미확인 — 매핑 생성 배치에서 prefix universe 확인 필요(V49 feed_id/agency_id 로 흡수).
- 지하철 statn_id decode 공식은 4호선 2샘플만 실증. 타 노선(2·9호선 등) 회차/지선(1D 방향코드) 일반화는 미검증 — 좌표를 1차키로 두면 안전.

### 검증 함의 (P2-D 재평가)
- V49 의 REVIEW/퍼지검증 파이프라인은 **수도권 기준 과설계**. 버스=prefix규칙, 지하철=좌표(≤3m)+라인 → 규칙 생성기로 VERIFIED 대량 발급 가능. V49 표(immutable/versioned artifact)는 유지하되 "생성기"는 예상보다 단순.
- 미검증 잔여: repo IT(Testcontainers, NULLS NOT DISTINCT 실증), 경기권 버스 prefix, 지하철 타노선 decode 일반화, resolver 배선 end-to-end.

### 좌표 (검증 스크립트)
- probe SQL: /Users/wa/.claude/jobs/d6757bfd/tmp/probe{,2,3}.sql (일회성). fixture id 추출: ids.txt.

### 정정 (2026-09-08, 지하철 id decode 반증)
- **지하철 id-only 매핑 = 불가능/위험. 좌표가 유일 신뢰키.** 추가 노선 실증으로 앞의 decode 공식 반증됨:
  - `S-1-1806`=부평(1호선) — decode `1018001806` DB 없음. 실제 부평 statn_id=`1001000152`.
  - `S-1-0150`=서울역(OTP "서울(지하)") — decode `1001000150`=**송내**(틀린 역). 실제 서울역 line1=`1001000133`.
  - `S-1-04..`(4호선)만 우연히 맞았음.
- 원인: OTP stop id `S-1-<BB><SS>` 의 `BB`(예 18)=라인 아님, **지선 코드**(부평=경인선). route(`S-1-01`=1호선)가 라인, stop 의 BB 는 별개. DB statn_id 중간2자리(00/07/08/80=지선분기)와 대응규칙 없음. seq 도 지선별 재사용.
- line_id==statn_id[1:4] 는 100% 성립(DB 일관). 동명역 다수(서울=1001/1004/1032/1063/1065). → **매핑키 = OTP 좌표(≤3m) + route 라인**. id 는 후보축소 보조로만(신뢰 금지).
- 버스는 반대: id(prefix strip)가 완벽, 좌표 불필요.

### 결정 (2026-09-08): 매핑 = 방법 B 실시간 규칙 resolver (생성기·V49 표 미사용)
- 검증결과 매칭이 결정적이라 versioned 표(P2-D V49)는 서울 기준 과설계. **표 없이 요청시 규칙으로 즉석 해석**하는 resolver 로 간다.
- 규칙: 버스=`BS_TAGO_SEB<9d>`→stop_id / `BR_TAGO_SEB<9d>`→route_id (prefix strip+존재검증). 지하철=route shortName `(\d)호선`→line_id `100`+N (1~9만) + StopOccurrence 좌표 최근접 subway_station(≤50m).
- **스코프 하드제약: 서울 버스 + 지하철 1~9호선만.** 경기(`BS_TAGO_GGB`)·인천(`BS_TAGO_ICB`)·기타 노선(경의중앙 1063/신분당 1075 등)·RS_TAGO/SS_ACC2 = 미지원→STATIC. 추후 확장 시 동일규칙 여부 **미검증**(prefix·좌표계·라인코드 다를 수 있음).
- P2-D #181(V49 표+JPA adapter+생성기)은 이 결정으로 **미사용/보류**. port 인터페이스(TransitIdentityMappingPort)는 재사용 가능하나 좌표입력 안 맞음 → 신규 resolver 계약 별도.

### 구현 (2026-09-08): OtpSeoulIdentityResolver (방법 B 코어)
- 신규 `application/route/mapping/`: SeoulTransitIdentity(sealed)/SeoulBusIdentity(stopId,routeId,routeName)/SeoulSubwayIdentity(stationId,lineId,distanceMeters)/OtpSeoulIdentityResolver.
- resolveBus: 정규식 `^\d+:BS_TAGO_SEB(\d{9})$`/`^\d+:BR_TAGO_SEB(\d{9})$` strip → busStopRepo.existsByStopId + busRouteRepo.existsByRouteId 둘 다 true 여야. routeName=OTP shortName.
- resolveSubway: shortName `(\d)호선`(1~9)→lineId `100`+N, subwayRepo.findNearby(lat,lon,50m,8) 거리오름차순에서 라인일치 첫 후보. (findNearby·NearbySubwayStationProjection 기존재).
- resolve(leg,stop) mode 분기(BUS/SUBWAY·RAIL/else). 스코프밖·미존재=empty→호출자 STATIC.
- 배선 없음(standalone). 프로덕션(ODSAY) 무변경. 소비자=후속(P2-D resolver 연계/P2-E 실시간 대체).
### 함정 (resolver)
- 테스트 projection 은 mockito 중첩 스터빙(when 안 when) 터짐 → 익명 구현체로 대체.
- findNearby 시그니처 (lat, lng, radiusMeters, lim) 순서 주의(lng=경도).
### 검증 (resolver, 2026-09-08)
- ./gradlew test --tests *OtpSeoulIdentityResolverTest → BUILD SUCCESSFUL, 7/7.
- 계약: 버스 정상매핑 / stop DB미존재 empty(phantom방지) / GGB·ICB 스코프밖 empty / 지하철 라인+좌표 최근접 / 호선1~9밖 empty / 반경내 라인후보없음 empty / WALK dispatch empty.

### 검증 (resolver Sonnet 리뷰 반영, 2026-09-08)
- Minor: seoulLineId `(\d)호선` 미앵커 → 두자리 "11호선" 뒷자리 오매치 위험. `(?<!\d)([1-9])호선` 로 앵커.
- Minor: resolve() dispatch 가 WALK 만 테스트됨 → BUS/SUBWAY dispatch 테스트 추가.
- 리뷰서 solid 확인: SEB 정규식 앵커·phantom 방지·좌표우선 disambiguation. 총 11/11.

## national-otp-p2 P2-E ETA composer (2026-09-08, branch feat/otp-p2-eta-timeline)
### 확정
- 신규 `application/route/timeline/`: RouteTimelineComposer + ConnectionFeasibilityEvaluator + ComposedTimeline/TimelineSegment(SegmentType WAIT/RIDE/WALK)/Feasibility(FEASIBLE/MISSED/CANCELLED/NO_CATCHABLE)/RealtimeLegTiming/RealtimeLegTimingLookup(주입 FI).
- 하나의 시간축: cursor=reqDep 시작, WALK=cursor+소요, 대중교통 leg 마다 userReadyAt=cursor, WAIT=boardAt-userReadyAt, RIDE=board..alight, cursor=alight. effective total=최종도착-요청출발(분 Math.round 마지막), 초기대기 포함. static/effective 도착 둘 다 보존.
- 실시간 대체: leg.enrichment.coverage==REALTIME_CAPABLE + lookup present + plannedBoard horizon 내(maxFutureMinutes*60) + observedAt fresh(staleAfterSeconds) + realtimeDeparture!=null 일 때만. arrival 도 실시간값 보존(도착지연 캐스케이드). cancelled→CANCELLED(정적 fallback 금지). stale/horizon밖→정적.
- **buffer 적용범위(근사 정책, PLAN 4 신뢰도 명시)**: 첫 탑승=정류장 대기중이라 buffer 미적용(출발<준비면 MISSED). 환승편=실시간/드리프트로 시각 바뀐 경우만 evaluate(userReadyAt, effBoard, buffer). 순수 정적 환승=OTP 계획 신뢰(buffer 재적용 안 함=중복 금지). drifted=rtApplied AND effAlight≠plannedAlight.
- buffer/stale=TransferArrivalProperties(boardingBufferSeconds=60, realtimeStaleAfterSeconds=90) 신규필드, horizon=TransferPredictProperties.maxFutureMinutes 재사용.
- standalone(배선 없음). 프로덕션 ODSAY 무변경. reReadyAt/reFromLegIndex 는 재검색 앵커만(실제 재검색·재귀상한=P2-F).
### 함정
- 첫 탑승에 buffer 적용하면 오탈락(대기중인데 MISSED). 첫 탑승/환승 구분 필수. 실시간 조기출발 MISSED 는 환승에서만 의미.
- 도착지연 전파는 realtimeArrivalAt 로(출발만 쓰면 캐스케이드 못 잡음). RealtimeLegTiming 에 depart+arrive 둘 다.
- 자정넘김=Instant 절대시각이라 특수처리 불필요(25시 테스트 통과).
### 검증 (P2-E, 2026-09-08)
- ./gradlew test --tests *RouteTimelineComposerTest *ConnectionFeasibilityEvaluatorTest → BUILD SUCCESSFUL, 14/14.
- composer 9: 정적WAIT/RIDE분리+total(buffer중복없음)/0초대기/버스·지하철·버스체인/실시간도착지연캐스케이드MISSED/stale fallback/cancelled/환승실시간조기출발MISSED/자정25시/horizon밖정적. evaluator 5: feasible/buffer내MISSED/지나감MISSED/cancelled/경계포함.
- 미커버(후속): TMAP 도보증가(도보=OTP정적, P2-E밖), 재검색상한(P2-F), 실제 enrich 배선.

### 검증 (P2-E Sonnet 리뷰 반영, 2026-09-08)
- Critical: transit leg boardAt null(ODsay shape) 가드 없어 cursor null→NPE/조용한0. → plannedBoard==null 이면 NO_CATCHABLE 후보제외(fail-loud). 테스트 transitLegWithoutAbsoluteTime.
- Major: MODEL_CAPABLE 게이트 누락(PLAN "실시간 도착/ML" 라 스코프) → isEnrichmentCapable 로 REALTIME_CAPABLE+MODEL_CAPABLE 포함. 테스트 modelCapableApplied(source=MODEL).
- Major: realtimeArrival<departure 모순 → cursor 역행. → 그 leg 정적 fallback(log warn). 테스트 realtimeArrivalBeforeDepartureFallsBackToStatic.
- Minor: RealtimeLegTimingLookup javadoc 에 boardingStop null 가능 명시. buffer/drift/cascade 산술은 리뷰서 정상 확인.
- 결과: RouteTimelineComposerTest 12 + Evaluator 5 = 17/17.

## national-otp-p2 배선 PR1: OTP leg coverage (2026-09-08, branch feat/otp-p2-leg-coverage)
### 확정
- 신규 OtpLegCoverageResolver(application/route/mapping): RoutingResult 의 대중교통 leg 마다 OtpSeoulIdentityResolver.resolve → coverage 확정(present=REALTIME_CAPABLE, else STATIC_SCHEDULE). walk/미지원 불변(NONE). resolver 는 boardingStop null 방어됨(그대로 전달 가능).
- 배선점: OtpRoutingAdapter.search — mapper.map 성공(RoutingResult)일 때만 applyCoverage, RoutingFailure 는 그대로. 모든 OTP 결과 일관 처리(1지점). OTP 는 selector 뒤라 프로덕션 ODSAY 무변경.
- 신규 copy 메서드: RoutingLeg.withEnrichment / RoutingItinerary.withLegs / RoutingResult.withItineraries — record 16필드 수동 재생성 유실 위험(codex 지적) 회피용.
### 함정
- OtpRoutingAdapter 생성자 5→6인자(coverageResolver 주입) → 기존 어댑터 테스트 2개 생성자 수정. Mockito 는 Optional 반환에 기본 empty → mock(OtpSeoulIdentityResolver) 만으로 coverage=STATIC 유지(기존 assertion 무영향).
### 검증 (배선 PR1)
- ./gradlew test --tests *OtpLegCoverageResolverTest → 4/4(매핑됨 REALTIME_CAPABLE/미매핑 STATIC/walk 불변·resolver 미호출/shape 보존). 회귀 *Routing* *Otp* *RouteResponseMapper* *RouteSearchServiceTest* *timeline* 그린.
### 미결(다음 배선)
- PR2 timing lookup(resolved id→기존 실시간 도착 조회→RealtimeLegTimingLookup 구현), PR3 composer 호출(OTP 표시 ETA), PR4 P2-F reroute(MISSED→routeOptions/남은검색+policy 복원).

## national-otp-p2 배선 PR2: 버스 실시간 timing lookup (2026-09-08, branch feat/otp-p2-realtime-timing)
### 확정
- OtpBusRealtimeTimingLookup implements RealtimeLegTimingLookup(#183): 버스 leg → resolve(SeoulBusIdentity) → resolveSeq 단일 → SearchTimeBusQueryPort.getArrInfoByStop → RealtimeArrivalCalculator.earliest(승차 stop 도착=출발) → RealtimeLegTiming. 재사용: 기존 SearchTimeBusQueryPort/RealtimeArrivalCalculator/BusTimeParser/TransferStopResolver.
- 근사(PLAN step4): 하차 예측 없음→arrival=null(정적 ride). trip-exact 매칭 없이 (stop,route,seq) 계획시각 이후 최초도착. 버스 취소신호 없음→cancelled=false. 조회 실패/차단/버스없음/seq다중→empty(정적).
- 지하철 identity→empty(후속 PR2b). 게이트=route.transfer-arrival.enabled(기존 공유, 기본 false). standalone(composer 호출은 PR3). 프로덕션 무변경.
### 검증 (배선 PR2)
- OtpBusRealtimeTimingLookupTest 7/7: 정상(출발=승차도착·arrival null·REALTIME·observedAt)/미매핑empty/지하철empty/seq다중empty/BLOCKED empty/버스없음empty/disabled empty. fixture=BusArrivalInfoFixtures.arrivalWithSectionTime.
### 미결(다음 배선)
- PR2b 지하철 timing(SubwayArrival 예측 재사용). PR3 composer 호출(coverage 게이트+lookup 주입→표시 ETA). PR4 P2-F reroute.

## national-otp-p2 배선 PR3: composer 호출 (2026-09-08, branch feat/otp-p2-timeline-wiring)
### 확정
- RouteTimelineService.reorderByEffectiveTotal(RoutingResult, departureAt): 각 itinerary 를 RouteTimelineComposer 로 합성(주입된 RealtimeLegTimingLookup=버스 lookup, coverage 게이트) → 보강 total 로 정렬(FEASIBLE 우선·오름차순·안정, 불가 뒤로). result.withItineraries 로 재구성. 응답 매핑은 불변.
- 배선점: RouteSearchService.runOtpSearch — port 결과(RoutingResult, coverage 배선됨)를 reorderByEffectiveTotal 후 legacyMapper.toLegacy. OTP 경로만(selector 뒤) → 프로덕션 ODSAY 무변경. enabled 기본 false 라 실시간 미적용=정적정렬(OTP 시간순과 사실상 동일 → OtpWiring 회귀 무영향).
### 함정
- RouteSearchService @RequiredArgsConstructor 필드 추가 → 생성자 인자 4개 테스트 전부 수정(마지막 위치). ODSAY 테스트는 timeline service 미사용이나 컴파일 위해 주입.
- RealtimeLegTimingLookup impl 현재 1개(버스)라 인터페이스 주입 무모호. PR2b 지하철 추가 시 @Primary/@Qualifier 또는 List 주입 필요(주의).
### 검증 (배선 PR3)
- RouteTimelineServiceTest 3(보강total 정렬/불가 뒤로/0~1개 noop) + RouteSearchServiceTest 8(ODSAY 회귀) + OtpWiringTest(정렬 무영향) + timeline/otp/routing 그린.
### 미결(다음 배선)
- PR2b 지하철 timing(+lookup 다중 impl 조합). PR4 P2-F reroute(MISSED→routeOptions/남은검색+policy). v2 응답에 ComposedTimeline(feasibility/segments) 노출은 별도.

### 검증 (배선 #184~#186 Sonnet 리뷰 반영, 2026-09-08)
- 리뷰 Critical 0, mergeable. 반영:
  - **M3 전용 킬스위치**: OtpRoutingProperties.realtimeEnabled(routing.otp.realtime-enabled, 기본 false). OtpBusRealtimeTimingLookup 게이트 = transfer-arrival.enabled AND otp.realtime-enabled. transfer-arrival.enabled 는 배포 yaml 에서 true(ODSAY 공유)라, OTP 라우팅 켜도 실시간은 이 플래그 전엔 off. 테스트 otpRealtimeDisabledIndependently.
  - **m6 예외가드**: RouteTimelineService.reorderByEffectiveTotal try/catch(RuntimeException)→정적(원래) 순서 degrade(DB/외부콜 예외가 검색 전체 500 만드는 것 방지).
  - **테스트 보강**: realtimeChangesOrder(coverage→lookup 적용 end-to-end 정렬 뒤집힘), stableOnTie(동률 안정), emptyStopsNoNpe(coverage resolver stops 빈 leg).
- **후속(문서화, OTP off 라 비긴급)**:
  - **M1(최중요, OTP 켜기 전 필수)**: RouteTimelineService→composer→timingFor 에 공유 decaying budget(RoutingDeadline) 없음 → N×L 순차 버스콜 각 full 1500ms, 상한·metrics 없음. 완전 해결=composer.compose/RealtimeLegTimingLookup.timingFor 시그니처에 budget 추가 + TransferArrivalEnricher 식 AtomicInteger 예산 + stage() 계측. 별도 PR(P7 OTP 활성 전).
  - **M2**: OtpLegCoverageResolver 가 OtpRoutingAdapter 에서 모든 OTP-port 호출(RemainingRouteSearchService 재탐색 포함)에 적용됨. 재탐색은 현재 timing 미소비(runOtpSearch 만) → coverage DB비용 낭비. P2-F 재탐색이 소비 예정(전방투자)이나, 아니면 소비처로 이동 검토.
  - **m4 이중 resolve**: coverage + lookup 이 identityResolver.resolve 중복(레그당 2x 인덱스조회). M1 대비 작음, defer. PR2b 시 coverage 결과를 leg/ctx 에 캐시해 lookup 재조회 제거 검토.
  - **m5 lookup 다중 impl**: RealtimeLegTimingLookup 현재 1개(버스). PR2b 지하철 추가 시 @Autowired 모호(NoUniqueBean) → mode 키 dispatch/@Primary/List 조합 필요.

## national-otp-p2 M1: 실시간 배선 예산 상한 (2026-09-09, branch feat/otp-p2-realtime-budget)
### 확정
- RouteTimelineService.reorderByEffectiveTotal 에 budgetMillis 추가 + Clock 주입. wall-clock realtimeDeadline=now+budget. 주입 lookup 을 예산게이트로 감싸(소진 후 timingFor→empty) composer 에 전달 → N×L 순차 외부콜이 검색 deadline 넘기지 않게(누적 ≈ budget + 진행중 1콜 상한). budget<=0 이면 실시간 전면 skip(정적).
- 배선: RouteSearchService.runOtpSearch 가 deadline.remainingMillis(clock) 을 예산으로 전달 + metrics.recordStage(BUS_REALTIME) 로 계측(가시성).
### 함정
- RouteTimelineService 생성자 +Clock → RouteSearchService 4개 테스트 생성자 갱신. reorder 시그니처 +budgetMillis → 호출부/테스트 갱신.
### 검증 (M1)
- RouteTimelineServiceTest 6(실시간→정렬뒤집힘/budget0 skip 정적우선/동률안정/불가뒤로/0~1 noop) + RouteSearchServiceTest 8(ODSAY) + OtpWiringTest 그린.
### 미결 (M1 잔여)
- **per-call timeout decay 미구현**: 각 버스콜은 여전히 props.getDeadlineMs(1.5s) full timeout. 진행중 1콜은 최대 1.5s. 완전 해결 = RealtimeLegTimingLookup.timingFor + RouteTimelineComposer.compose 에 잔여예산 인자 추가(composer 12테스트 ripple) → P7 OTP 활성 전 별도 PR. 현재 wall-clock 게이트로 누적은 상한됨.

## national-otp 설계 correction: #1 검색 정적화 + 서울 id 채우기 (2026-09-09, 사용자 지시)
### 확정 (코드만 봐선 안 나오는 계약)
- **원칙 재확정([[feedback-server-static-search-front-realtime]])**: #1 초기검색 응답 = 정적 ODsay 드롭인만. 실시간·재정렬·라벨·coverage노출 서버 금지. 실시간은 프론트가 **별도 API** 로 호출. 검색응답이 할 일 = 프론트가 실시간 호출하게 **서울 provider id 채우기**.
- **버스 실시간 = id 기반**: `GET /api/arrivals/bus/{stopId}/{routeId}` (BusArrivalController). stopId=bus_stop.stop_id, routeId=bus_route.route_id. → legacy 응답에 채워야: `SubPathResult.startLocalStationID`←resolver.stopId (PR3규칙 startLocalStationID→stop_id), `LaneResult.busLocalBlID`←resolver.routeId (busLocalBlID→route_id), `endLocalStationID`←하차정류장 resolve.
- **지하철 실시간 = 좌표 기반**: `GET /api/arrivals/subway?lat&lon&line_id(optional)` (SubwayArrivalController→SubwayArrivalQueryService.resolveStation). 서버가 `findNearby` 50→300m + lineId 필터로 역 확정 = resolver.resolveSubway 와 동일 로직. → **지하철 leg 은 legacy 에 station_id 채울 필요 없음**. 매퍼가 이미 넣는 좌표(startX/Y, StationResult x/y)로 프론트가 호출. startId(Integer) vs station_id(String) 타입불일치 문제는 **폐기**(애초 불필요).
- **correction 3파트**: ① runOtpSearch 에서 reorderByEffectiveTotal 호출+BUS_REALTIME 계측 제거 → toLegacy(outcome) 직행(엔진 빠른순 유지, 강등/숨김 금지). ② LegacyRouteResponseMapper 에 OtpSeoulIdentityResolver 주입해 버스 id 채우기(지하철 skip). ③ OtpRoutingAdapter 의 applyCoverage 제거(#1 미사용) — resolver/OtpLegCoverageResolver/RouteTimelineService/composer/timing/budget 은 **폐기 아님**, #2(주행중 재탐색 P2-F)용으로 main 보존.
- **#187(M1 예산상한) = 먼저 머지 후** 그 위에서 correction(사용자 Q1). reorder 를 #1 에서 걷어내도 RouteTimelineService/budget 은 #2 용으로 살아있음.
### 미결
- ② 하차정류장 resolve: resolveBus 를 last stop sourceStopId 로 재호출(같은 routeGtfsId/routeName). endLocalStationID 만, endArsID 는 원천 ARS 없음 → null 유지.
- coverage 를 #2 어디서 소비할지는 P2-F(PR4) 구현 시 확정(현재는 adapter 에서 제거만).

### 검증 (correction, 2026-09-09)
- 편집: ③ OtpRoutingAdapter(coverageResolver 필드/생성자/applyCoverage 제거, import 정리) ① RouteSearchService.runOtpSearch(reorder+BUS_REALTIME 제거→toLegacy 직행, RouteTimelineService 필드 제거) ② LegacyRouteResponseMapper(OtpSeoulIdentityResolver 주입, 버스 leg 만 startLocalStationID/endLocalStationID + LaneResult.busLocalBlID 채움; 지하철 skip).
- 계약 실증: 기존 프론트 지하철 실시간 = 좌표 기반(/api/arrivals/subway?lat&lon, SubwayArrivalQueryService.resolveStation=findNearby+lineId 필터). 버스 = id 기반(/api/arrivals/bus/{stopId}/{routeId}). → 지하철 id 매핑 불필요 확정.
- `./gradlew compileJava compileTestJava` 그린. 생성자 시그니처 변경 파급(테스트 6파일): RouteSearchServiceTest/OtpWiringTest/RouteSearchDeadlineIT/RouteSearchRealtimeDeadlineIT(RouteTimelineService 인자 제거+mapper resolver), RemainingRouteSearchServiceTest/OtpTest(mapper resolver), OtpRoutingAdapterHttpTest/BudgetTest(coverageResolver 인자 제거).
- `./gradlew test` (11클래스) = **48 tests, 0 fail/error**. RouteResponseMapperTest 4(신규 busSeoulIdFilled/busUnmappedKeepsDisplay 포함), RouteSearchServiceTest 8(ODSAY 회귀), OtpWiringTest 2, OtpSeoulIdentityResolverTest 10, OtpLegCoverageResolverTest 5(클래스 존치 확인), Otp/Remaining/Deadline 나머지.
### 함정 (correction)
- SubPathResult 위치인자 31개 — lanes(4번째)·startLocalStationID(25)·endLocalStationID(26)만 채우고 나머지 null 유지. ArsID/startId 는 원천부재로 계속 null.
- LegacyRouteResponseMapper 는 Lombok 미사용 → 수동 생성자 추가. 무인자 생성자 호출 6곳(1곳은 FQN `...service.LegacyRouteResponseMapper()` 라 단순 grep 누락) 전부 mock resolver 주입.
- OtpLegCoverageResolver/RouteTimelineService/composer/timing/budget = main 존치(빈 유지), #2 P2-F 소비지점 배선만 남음.

## national-otp P2-F PR-2: route-option 후보 출처 추상화 (2026-09-10, branch feat/otp-p2f-route-option-source)
### 확정
- RerouteRouteOptionSource 포트 도입 + SourceOutcome(OK/UNAVAILABLE/TIMEOUT/ERROR — 정상0vs장애, codex B7). optionsFor(JourneyLeg, RoutingDeadline).
- StoredRouteOptionSource(@Component): 현행 findByJourneyLegIdInAndDeletedAtIsNull + optionIndex 정렬을 그대로 이관. 저장 조회는 장애상태 없음→항상 OK.
- RouteOptionCandidateProvider: routeOptionRepository 필드 제거→routeOptionSource. collect()가 source.optionsFor(waitLeg, RoutingDeadline.of(clock, routeOption.evaluationBudgetMs)) 호출, non-OK면 metrics.recordCandidateFiltered("source-<status>")+빈손. baseline/alternatives/dedup/budget 로직 무변경.
### 함정
- **무행위변경 성립 조건**: StoredSource 가 항상 OK 라 non-OK 분기 미발동 → 기존 20 테스트 그린 유지. 테스트 provider 생성자는 routeOptionRepository 대신 new StoredRouteOptionSource(mockRepo) 래핑(기존 stub findByJourneyLegIdIn 그대로 동작).
- PR-4 에서 OtpPatternRouteOptionSource 추가 시 RerouteRouteOptionSource 빈 2개 → DI 모호. @Primary/selector 로 가름(PR-4). PR-2 는 단일 빈.
### 검증 (PR-2)
- StoredRouteOptionSourceTest 2(optionIndex 정렬/빈=OK) + RouteOptionCandidateProviderTest 20 무회귀 + RerouteAcceptIT 9(컨텍스트 부팅, 새 빈+재배선). 마이그레이션 없음.
## national-otp P2-F PR-3: 동적 route-option 수락 계약 (2026-09-10, branch feat/otp-p2f-dynamic-route-option-accept)
### 확정 (코드만 봐선 안 나오는 계약)
- **문제(codex B1)**: RerouteAcceptTxService.saveRouteOptions 가 저장 journey_leg_route_option 을 optionIndex 로 선택, 불일치=첫 후보 fallback → OTP 동적 후보(저장 미존재) 수락 시 엉뚱 노선 선택.
- **해법**: RerouteCandidateApply.RouteOptionApply 를 self-sufficient snapshot 으로 확장(RemainingPathApply 가 full path 담는 선례와 동일). routeOptionIndex 음수(DYNAMIC=-1)=동적 후보 판별. saveRouteOptions: 저장 후보=optionIndex 경로(무회귀), 동적 후보=(routeId,directionKey) 매칭 후 없으면 스냅샷으로 새 행 insert→select.
- **하위호환 장치**: 4-arg 편의 생성자(snapshot null) 유지 → 기존 ~14 호출부·옛 저장 JSON 무변경. 기존 applies 전부 routeOptionIndex≥0 → 현행 경로 그대로.
### 함정
- **lane_index >= 0 CHECK(V35:97)**: 동적 후보 laneIndex 는 대응 lane 없음. -1 sentinel 이 CHECK 위반 → laneIndex null 로 두고 insert 시 nextIndex(=option_index, ≥0, 원래 lane 범위 밖이라 클라 stale graphic 안붙음)로 채움.
- **isDynamic() Jackson 오직렬화**: boolean isDynamic() 가 "dynamic" 프로퍼티로 직렬화→역직렬화 UnrecognizedPropertyException. @JsonIgnore 필수.
### 좌표
- src/.../dto/RerouteCandidateApply.java (RouteOptionApply 10-arg + 4-arg 편의 ctor + DYNAMIC + isDynamic@JsonIgnore)
- src/.../service/RerouteAcceptTxService.java (saveRouteOptions routeOption 분기 + chooseRouteOption + selectByIndex)
### 검증 (PR-3)
- 신규 IT RerouteAcceptIT.acceptsDynamicRouteOptionByInsertingNewRow: 저장에 없는 BL-DYNAMIC 동적 후보 수락 → 새 journey_leg_route_option 행 insert + selectedRouteOptionId 가 BL-DYNAMIC 가리킴(첫후보 fallback 아님).
- ./gradlew test 79 tests 0 fail: RerouteAcceptIT 10(동적 포함) + Contract 9 + StoreIT 13 + ExpiryIT 4 + ProposalServiceTest 12 + RouteOptionCandidateProviderTest 20 + RevisionComposerTest 7 + SubwayBindingRerouteInheritanceIT. 무회귀 확인.
### 미결
- toCandidate 는 아직 snapshot 안 채움(4-arg, routeOptionIndex≥0) — PR-4 OTP source 가 동적 apply(DYNAMIC+snapshot) 생성. PR-3 은 수락측 계약+테스트만.
## national-otp P2-F PR-1: StopPatternPort + OTP 어댑터 (2026-09-10, branch feat/otp-p2f-stop-pattern-port)
### 확정
- OTP `stop(id).patterns` 조회 배관. 도메인 StopPattern(routeGtfsId, routeShortName, directionId, stopGtfsIds 운행순서보존) + StopPatternResult(OK/TIMEOUT/ERROR — 정상0 vs 장애 구분, codex B7). Port StopPatternPort.patternsThroughStop(stopGtfsId, RoutingDeadline)=예외 안던짐.
- 어댑터 OtpStopPatternAdapter 는 OtpRoutingAdapter 패턴 미러(RestClient.Builder 주입 clone+per-call readTimeout=deadline 남은예산, GRAPHQL_PATH 동일, sendOnce/OtpCallException, ClassPathResource loadQuery). 파싱은 OtpStopPatternMapper(@Component 순수). graphql=resources/otp/stopPatterns.graphql(stable 필드만).
- directionId 는 KTDB direction_id 부재로 전 노선 -1 → 매퍼는 원천값 그대로, 방향판정은 소비처(PR4)가 stops 순서로.
- **미배선**: 소비 없음(회귀0). PR-4 가 RerouteRouteOptionSource 로 소비.
### 좌표
- src/main/java/.../application/route/routing/{StopPattern,StopPatternResult,StopPatternPort}.java
- src/main/java/.../infrastructure/adapter/out/api/otp/{OtpStopPatternAdapter,OtpStopPatternMapper}.java
- src/main/resources/otp/stopPatterns.graphql, src/test/resources/otp/stopPatterns-success.json
### 검증 (PR-1)
- OtpStopPatternMapperTest 3(route/stops 순서보존/directionId -1 원천/모르는 stop 빈목록) + OtpStopPatternAdapterHttpTest 5(200 OK 파싱/stop null=OK 빈목록=정상0/GraphQL errors=ERROR/5xx=ERROR/예산0=TIMEOUT). 8 그린. 기존 OTP 어댑터·체인 무회귀. 마이그레이션 없음.
## national-otp P2-F PR4a: reroute policy 값 재산정 (2026-09-09, branch feat/otp-p2f-reroute-policy)
### 확정 (사용자 결정)
- REVIEW 값 4개 재산정(cost 근거 제거, OTP 무료 전제): remaining-path.max-paths 2→4, search-budget-ms 2000→3000, route-option.max-evaluations 3→10(사실상 전부, 0/무제한은 @PostConstruct 가 막음), trigger.cooldown PT2M→PT1M. KEEP: min-eta-gain 300, max-external 12, horizon 40.
- **플래그는 안 건드림**: 마스터 enabled·trigger.enabled(shadow) 그대로. shadow OFF(실제 제안 ON)는 4b·4c(대안 OTP pattern·composer 재평가) 완성 후 배포결정. 지금 켜면 반쪽 부품으로 제안 나감.
- **⚠ OTP 전제 리스크**: 값 확대 근거=OTP 내부호출 무료. OTP 아직 OFF(기본)라 provider=ODSAY 면 재탐색이 여전히 유료 ODsay. max-paths 2→4=재탐색당 호출 2배. 재탐색=사용자요청+shadow 라 저사용, OTP 전환 예정이라 수용. policy-inventory/yaml/javadoc 에 "OTP 전제" 명시.
### 함정
- 기본값 변경이 기존 테스트 2곳 깨뜨림: RemainingPathCandidateProviderTest.trimsToMaxPaths(옛 default 2 가정 → setMaxPaths(2) 명시로 기본값 독립화), RedisRerouteCooldownAdapterTest(default cooldown ofMinutes(2)→(1) 5곳, 단 ttlComesFromProperties 는 명시 45s 라 유지).
- shadow 세만틱 헷갈림: trigger.enabled=true 가 "shadow(로그만)". 실제 제안=마스터 enabled. yaml enabled 기본 ${...:true} 지만 shadow 가 실질 게이트.
### 검증 (PR4a)
- ./gradlew compileJava compileTestJava 그린. 신규 JourneyReroutePropertiesTest 4(값4개/플래그 불변/KEEP/validate) + RemainingPathCandidateProviderTest 13 + RedisRerouteCooldownAdapterTest 8 + RouteOption 20 + RerouteTrigger/Window 등 전부 그린.
### 미결 (P2-F 다음)
- PR4b: route-option 대안을 저장값 아닌 OTP pattern 에서 생성(미결정). PR4c: 대안 우열을 RouteTimelineComposer 재평가로(브리지 지점 미결정). shadow OFF 는 4b·4c 후.

## national-otp P2-F PR-4: OtpPatternRouteOptionSource (2026-09-10, branch feat/otp-p2f-otp-pattern-source)
### 확정 (코드만 봐선 안 나오는 계약)
- **B6 해법 = 사용자 방향 확정(2026-09-10)**: PR-1 topology 쿼리(`stop.patterns`) 대신 `stop.stoptimesForPatterns(omitNonPickups:true, omitCanceled:true)` 로 교체. OTP 가 애초에 **승차가능(pickupType)·미취소·운행중(timeRange 내 출발편 존재=service 활성)** pattern 만 grouped-by-pattern 으로 내려줌 → 우리가 pickup/dropoff/service 검증 로직 복제 불필요, codex B6 자동해결. StoptimesInPattern.pattern = 기존 Pattern(route/directionId/stops) 이라 **StopPattern DTO·Port·Result 무변경**, graphql 쿼리 body + mapper JSON path(`data.stop.stoptimesForPatterns[].pattern`) + fixture + mapperTest 만 변경.
  - 스키마 실측(schema.graphqls:2348): `stoptimesForPatterns(numberOfDepartures=5, omitCanceled=true, omitNonPickups=false, startTime=0, timeRange=86400): [StoptimesInPattern]`. StoptimesInPattern{pattern:Pattern, stoptimes:[Stoptime]}. Stoptime.pickupType/dropoffType:PickupDropoffType(2483/2470). Pattern.stops:[Stop!]/route:Route!/directionId(1024). → omit 플래그로 boarding 측 필터를 OTP 에 위임.
  - **잔여(문서화)**: 하차정류장 dropoff 금지는 boarding stop stoptimes 로 못 봄(그 stop 것만 옴) — 서울 버스에 극히 드묾, topology 로 정차만 확인. "모름>틀림" 위배 아님(승차측이 요점).
- **union 유지(codex B3, 순수대체 금지)**: baseline(지금 타는 노선)은 저장 selected option 에서만 나옴(provider.evaluateBaseline 이 options 에서 getId==selectedRouteOptionId 로 찾음) → OTP-only 면 baseline 소실. + resolver 9자리만 매핑(`_D/_U` suffix 실패 다수) → OTP-only 후보 감소. → **게이트 on = Stored ∪ OTP, off = Stored(PR-2 무변경)**. OTP non-OK(off/timeout/error)=Stored 폴백.
- **union dedup 키 = (routeId, boardingSeq)** (codex B5/B8): OTP 후보 중 (routeId,boardingSeq)가 저장에 이미 있으면 제외(baseline·중복 재출력 방지). directionKey 는 directionId=-1 이라 dedup 키로 못 씀.
- **OTP 후보 = transient JourneyLegRouteOption**(SourceOutcome.options 타입이 이거라): builder journeyLegId=waitLeg.id, **optionIndex=DYNAMIC(-1)** (동적 판별 마커), routeId/sourceRouteId/routeName/directionKey(routeId+"|-1")/boardingSeq/alightingSeq, wayCode=null, laneIndex=0(미사용, DB 미저장). getId()=null.
- **provider 2곳 손봄(회귀 가드)**: ① `evaluateBaseline`/`alternatives` 의 `option.getId().equals(selectedRouteOptionId)` → transient id=null NPE → `java.util.Objects.equals` 로 null-safe(OTP 후보는 baseline 아님=false 정상). ② `toCandidate`: optionIndex<0 → **동적 RouteOptionApply**(DYNAMIC + snapshot: sourceRouteId/routeName/wayCode/boardingSeq/alightingSeq/laneIndex=null) 생성 → PR-3 수락계약이 새 행 insert. optionIndex≥0(저장) → 기존 4-arg.
- **DI 구조**: RerouteRouteOptionSource 빈 3개. StoredRouteOptionSource(@Component 기존) + OtpPatternRouteOptionSource(@Component, StopPatternPort+OtpSeoulIdentityResolver+TransferStopResolver) + **UnionRouteOptionSource(@Primary @Component, gate=routeOption.otpPatternEnabled)**. provider 는 여전히 단일 RerouteRouteOptionSource 주입 → @Primary 로 Union 받음(구조 무변경, 기존 테스트는 stored 직접 주입이라 무회귀).
- **전용 게이트(codex B10)**: `journey.reroute.route-option.otp-pattern-enabled`(기본 false). RoutingProviderSelector(경로검색 provider) 재사용 금지 — 의미 다름.
- **boardingSeq = 서울 DB 단건규칙 재사용**: TransferStopResolver.resolveSeq(routeId, seoulStopId).isSingle() 이면 seq, 아니면 후보 제외(JourneyTransitIdEnricher.uniqueSeq 와 동일 규칙, codex B4). seoul routeId·stopId 는 OtpSeoulIdentityResolver.resolveBus(sourceStartStopId, pattern.routeGtfsId, shortName) 한 방에(존재검증 포함).
- **방향 = pattern.stops 정차순서**(direction_id 부재): boarding gtfsId(sourceStartStopId) index < alighting gtfsId(sourceEndStopId) index. boarding stop 이 pattern 에 2회 이상=순환/지선 occurrence 불확정 → 제외(codex B5). alighting 은 boarding 뒤 아무 index 하나라도 있으면 OK.
- **실제 제안 ON 금지(codex B2) 계속**: PR-4 는 후보집합 확장까지. ETA 노선별 실주행 미반영(공통 plannedDurationSec)은 PR4c(composer 재평가) 몫. shadow OFF 는 PR4c 후.
### 미결
- boardingSeq 3단매핑 커버리지(OTP route↔서울 route_id↔bus_route_stop seq) 실측 미측(OTP off).
- N+1: pattern 마다 resolveBus+resolveSeq. route dedup 먼저로 완화, 진짜 bulk(routeIds×stopId) 미구현 — OTP off 라 프로덕션 0.
### 검증 (PR-4)
- 편집: stopPatterns.graphql(patterns→stoptimesForPatterns+omitNonPickups/omitCanceled), OtpStopPatternMapper(JSON path data.stop.stoptimesForPatterns[].pattern), fixture 재작성. 신규 OtpPatternRouteOptionSource/UnionRouteOptionSource(@Primary). JourneyRerouteProperties.RouteOption.otpPatternEnabled(기본 false). RouteOptionCandidateProvider(Objects.equals null-safe 2곳 + applyFor 동적/저장 분기).
- 계약 실증(스키마): schema.graphqls:2348 stoptimesForPatterns(omitNonPickups/omitCanceled) → StoptimesInPattern{pattern}. omitNonPickups:true=승차가능 pattern 만, omitCanceled+timeRange=운행중만 → B6 를 OTP 가 처리(우리 검증 로직 없음). StopPattern DTO 무변경.
- `./gradlew compileJava compileTestJava` 그린(warnings=기존 MockBean deprecation).
- 신규/회귀 테스트 전부 그린: OtpPatternRouteOptionSourceTest 9(방향/순환제외/미매핑/비단건seq/dedup/timeout/error/blank) + UnionRouteOptionSourceTest 4(게이트off·on union·dedup(routeId,boardingSeq)·otp非OK 폴백) + RouteOptionCandidateProviderTest 21(20 회귀 + 동적 apply 신규) + OtpStopPatternMapperTest 3 + OtpStopPatternAdapterHttpTest 5 + StoredRouteOptionSourceTest 2. 마이그레이션 없음.
- DI: RerouteRouteOptionSource 빈 3개(Stored/Otp/Union@Primary) — RerouteAcceptIT 컨텍스트 부팅 그린(NoUniqueBean 없음). provider 는 @Primary Union 주입.
- 넓은 회귀(`*Reroute* *RouteOption* *Otp* *JourneyReroutePropertiesTest`) BUILD SUCCESSFUL, 실패 xml 0. JourneyReroutePropertiesTest 는 신규 필드(기본 false)에 무영향.

## national-otp P2-F PR4c: route-option 노선별 실주행 ETA (설계 확정 2026-09-10, 구현 대기)
### 확정 (설계 결정, 사용자 승인)
- **문제(B2 진범)**: RouteOptionEtaCalculator 가 목적지 ETA = 예측승차 + Σ(하차이후 leg plannedDurationSec). 후보 RIDE leg 소요를 **원래 선택 노선 값**으로 씀 → 다른 버스(급행/완행) 실주행 차이 소실.
- **결정적 관찰**: 하차이후 leg 은 baseline·모든 후보 동일 → 개선폭 뺄셈에서 상쇄. 후보마다 다른 건 **board→alight 소요 하나뿐**.
- **A안 확정(경량, composer 안 씀)**: EtaCalculator 의 RIDE leg(waitLeg+1) 소요만 **노선별 OTP 실값**으로 교체, 나머지 합산 그대로. B안(composer 통짜 재평가) 기각 — 하차이후 leg 절대시각 위조 필요(composer 는 진짜 OTP itinerary 전제) + "이후 대기 모름" 문제 재발 + 실시간 주행보정 효과 미미(predict 가 이미 승차시각 실시간).
- **데이터 출처 = OTP 확정**: `Trip.stoptimes[].scheduledArrival/scheduledDeparture`(초, serviceDay 기준) → `alight.scheduledArrival − board.scheduledDeparture` = 노선별 board→alight 소요. serviceDay 무관 delta. 스키마 실측(schema.graphqls:2470 Stoptime, Trip.stoptimes:88). pattern 별 소요는 trip 간 거의 일정 → 대표 trip 하나.
- **같은 하차 보장**: PR-4 필터(pattern.stops 승차 idx<하차 idx)로 후보는 그 하차 정류장 실제 정차 확정 → board·alight 둘 다 pattern 위 → delta 산출 가능.
- **⚠ 공정성 계약("합산 규칙 한 벌")**: baseline 도 후보도 같은 방식으로 rideSec 조회해야. baseline=plannedDurationSec / 후보=OTP 면 비교가 데이터출처 차이가 됨. → **baseline·후보 전부 OTP rideSec**. 조회 실패 시 폴백=plannedDurationSec + 실주행 미반영 표시(실제 제안 게이트).
- **모듈**: 새 포트 `RouteRideDurationPort`(routeGtfsId, boardGtfsId, alightGtfsId, deadline → Optional<Integer> sec) + OTP 어댑터(stop.stoptimesForPatterns→pattern 매칭→trip.stoptimes delta, 또는 전용 쿼리). EtaCalculator.compute +rideSecOverride(null=기존, 하위호환→④ 무변경). provider.evaluate 가 baseline+후보에 rideSec 주입, deadline(evaluationBudgetMs) 안. 실제 제안 ON 게이트(rideSec 반영시만).
### 미결 (구현 중)
- OTP 쿼리 형태: 기존 stoptimesForPatterns 확장(trip{stoptimes}) vs 전용 trip 쿼리. 후보가 pattern/trip id 안 들고 옴 → (boardGtfsId, routeGtfsId)로 재조회.
- rideSec 캐시(graphVersion 키) — OTP off 라 후순위.
- #193(PR-4) 머지 의존성: 코드상 독립(stored 후보에도 적용) but 실제 제안 ON 은 PR-4+PR4c 둘 다 필요. PR4c 는 fresh main 독립 브랜치.
### 검증 (예정)
- OTP rideSec 어댑터(JSON→sec delta, 미매칭 empty), EtaCalculator rideSecOverride(교체/null 하위호환), provider baseline+후보 동일 조회·폴백. RouteOptionCandidateProviderTest 무회귀.

## national-otp P2-F PR4c-1: RouteRideDurationPort + OTP 어댑터 (구현, 브랜치 feat/otp-p2f-route-ride-duration)
### 확정
- **단일쿼리 채택**(plan 의 2단 alias batch 대신): main 은 stopPatterns.graphql=stop.patterns.stops 뿐(stoptimesForPatterns 전환은 PR-4 #193 브랜치, main 미머지). PR4c 전용 쿼리 routeRideDuration.graphql = `stop(id).stoptimesForPatterns(numberOfDepartures:1){pattern{route{gtfsId}} stoptimes{trip{gtfsId stoptimes{stop{gtfsId} scheduledArrival scheduledDeparture}}}}`. board 정류장 한 번 조회로 pattern별 대표 출발편의 **trip 전체 stoptimes** 를 함께 받아 board dep→alight arr delta. `pattern.trips`(limit 없음) 확장 안 함 — numberOfDepartures 로 유계.
- Port=단일노선(plan 시그니처 그대로). 대안 후보 N개는 provider(PR4c-3)가 N회 호출(baseline1+대안), 공유 deadline 안. batch alias 는 최적화 옵션이었을 뿐 계약 아님.
- 방향 판정: trip.stoptimes 순서로 board 를 먼저 만난 뒤 alight 를 만나야 OK. 반대(하행 pattern)면 그 pattern skip→다음 매칭 pattern. 어떤 매칭도 board<alight 유효 delta 없으면 NOT_FOUND.
- RideDurationResult(OK/NOT_FOUND/TIMEOUT/ERROR) — StopPatternResult 철학. NOT_FOUND=정상 미매칭(이 노선이 이 구간 안 감), TIMEOUT/ERROR=장애. 공정성 게이트가 이 구분으로 승격 가름.
### 좌표
- application/route/routing/RouteRideDurationPort.java, RideDurationResult.java — 계약
- infrastructure/adapter/out/api/otp/OtpRouteRideDurationAdapter.java — OtpStopPatternAdapter 미러(RestClient clone+readTimeout=deadline, GRAPHQL_PATH, sendOnce/OtpCallException)
- infrastructure/adapter/out/api/otp/OtpRouteRideDurationMapper.java — 순수, delta 계산
- resources/otp/routeRideDuration.graphql, test/resources/otp/routeRideDuration-success.json
### 검증
- `./gradlew compileJava compileTestJava` OK(경고만, 기존). `--tests OtpRouteRideDuration*` = mapper 5 + adapter HTTP 5 = 10 그린(failures 0). 미배선(회귀0).

## national-otp P2-F PR4c-2: RouteOptionEtaCalculator override 오버로드 (구현)
### 확정
- 4-arg `compute(legs, waitLeg, boardingAt, rideSecOverride)` 추가. 기존 3-arg=null 위임(하위호환). draft 경로 compute(List<JourneyLegDraft>,...)=override null,-1(④ 무변경).
- override 대상 = `waitLeg.legIndex+1` 자리 **정확히 하나**, 반드시 RIDE_BUS. computeFrom 에 (rideSecOverride, rideLegIndex) 전달, 합산 규칙 한 벌 유지 — 그 자리만 분기.
- **후보 폐기 조건 2개**(빈 결과): ①rideLegIndex 자리가 RIDE_BUS 아님(WALK/TRANSFER_WALK 오대체 방지) ②override 지정했는데 그 자리가 아예 없음(overrideApplied=false). 둘 다 "승격 대상 아님".
- RIDE_BUS override 는 walking 합 불변(RIDE_BUS≠WALK/TRANSFER_WALK).
### 좌표
- application/journey/service/RouteOptionEtaCalculator.java:compute(4-arg)/computeFrom(rideSecOverride,rideLegIndex)
### 검증
- `--tests RouteOptionEtaCalculatorTest` = 10 그린(기존 6 무회귀 + override 4: 교체/null 하위호환/RIDE_BUS 아님 폐기/자리 없음 폐기).

## national-otp P2-F PR4c-3: provider 재배선 (구현 중)
### 함정
- **alight 정류장은 waitLeg 가 아니라 RIDE leg(waitLeg.legIndex+1) 의 sourceEndStopId 다.** JourneyCompiler:200-232 확인 — WAIT leg 은 sourceStartStopId=sourceEndStopId=승차정류장(둘 다 board). RIDE leg 이 sourceStartStopId=board, sourceEndStopId=alight. 플랜 SUMMARY 의 "waitLeg.getSourceEndStopId()(alight)" 는 오류. rideSec 조회 alight 는 rideLeg.getSourceEndStopId() 사용. ③ 전제(같은 board+alight)라 baseline·모든 후보 동일 alight.
### 확정 (사용자 결정)
- **easy path 채택**: baseline·후보 둘 다 option.getSourceRouteId() 를 OTP routeGtfsId 로 조회. main 은 busLocalBlID(seoul)라 OTP 미매칭→NOT_FOUND→shadow. 무거운 stopPatterns 재조회+resolveBus 되찾기는 PR-4 머지 후 별건(지금 구현하면 OTP off·PR-4 미머지라 죽은 코드). 프로덕션 journey.reroute.enabled=false(VPS compose 확인) 라 영향 0.

### 확정 (구현)
- **공정성 게이트 위치**: collect() 대안 루프에서 gain 임계 통과 후 `baseline.rideDurationOk() && evaluation.rideDurationOk()` 아니면 filtered("ride-duration-incomparable")+continue(shadow). gain 계산·지표는 종전대로(위에서 이미 계산).
- **예산 공유**: evaluate() 진입 시 RoutingDeadline.of(clock, budgetMs) 하나 생성 → predict 는 deadline.remainingMillis, rideSec 조회도 같은 deadline(predict 뒤 남은 만큼). 이중소비 없음. 진입 시점엔 clock 미tick 이라 predict budget 인자는 종전과 동일 → budget 테스트 무회귀.
- **override cancel 성질**: baseline·후보가 같은 rideSec(테스트 상수)면 승차 leg 소요 대체가 개선폭 뺄셈에서 상쇄 → 기존 gain 단정(540/660 등) 유지. 그래서 기존 20 provider 테스트 + IT 는 rideDurationPort=OK 스텁만 추가하면 무회귀.
- **가드**: option.sourceRouteId / waitLeg.sourceStartStopId / rideLeg(waitLeg+1).sourceEndStopId 중 하나라도 blank 면 OTP 호출 없이 NOT_FOUND. main stored 후보는 sourceRouteId=seoul 이라 실제론 미매칭→shadow(설계대로).
### 함정
- **JourneyRerouteMetrics.KNOWN_FILTER_REASONS 화이트리스트**: 새 사유 "ride-duration-incomparable" 안 넣으면 조용히 "unknown" 으로 접혀 지표가 안 뜬다(테스트 incomp=0 으로 30분 헤맴). 사유 추가 필수.
- IT RerouteRouteOptionIT: givenResolvedBoarding 이 baseline option 에 source_route_id 를 안 넣어 baseline 이 항상 shadow → 제안 0. baseline source_route_id + wait/ride leg source stop id 채우고 @MockBean RouteRideDurationPort=OK 추가해야 함.
### 검증
- `--tests RouteOptionCandidateProviderTest` = 22 그린(20 무회귀 + 게이트 승격막힘/shadow폴백 2). RouteOptionEtaCalculatorTest 10. Otp*RideDuration* 10. RerouteRouteOptionIT 5(부팅=DI 단일 RouteRideDurationPort 빈 OK). 광역 `*Reroute* *RouteOption* *RemainingPath* *Otp* *RideDuration*` NO FAILURES.

## OTP 수도권 shadow 배포 (L, docs/plan/otp-vps-shadow-deploy) — 착수 전 환경 실측 (2026-09-15)
### 확정 (§6 미결 해소)
- **OTP fork 상태 == PLAN §1 정확 일치**: branch codex/otp-phase0-3 @ baseline 02c0b4b. 미커밋 = 프로덕션4(H TripPatternForDates + F DestinationArrivalPaths/PathConfig/McRangeRaptorConfig) + 테스트3(TripPatternForDateTest/RaptorRoutingRequestTransitDataCreatorTest/TripPatternForDatesTest) + untracked test/performance/seoul-metro/. B/C/E2 오염 없음.
- **Java 25 확정**: OTP fork pom `<release>25</release>`. 런타임 base = eclipse-temurin:25-jre (docker manifest inspect = 존재, amd64 O). 로컬엔 25-jdk·maven-3.9-eclipse-temurin-25 있음(빌드용), 25-jre 는 PR-1 에서 pull+digest 고정. temurin jre=Ubuntu 계열이라 curl 없음 → Dockerfile apt 설치 유지.
- **port 127.0.0.1:18080 = FREE** (VPS compose+env grep 0건). 충돌 없음.
- **OTP env 부재 확인 = clean shadow**: VPS compose/env 어디에도 ROUTING_OTP/OTP/18080 0건. api/worker 는 ROUTING_OTP_BASE_URL 없음 → 전환 시 재생성 필수(PLAN 함정대로). reroute flag 도 compose/env 부재 → journey.reroute.enabled 코드 기본값(OTP off 라 영향 0).
- **soak wrapper 위치 = OTP fork test/performance/seoul-metro/**: 형제 스크립트(compare_graphql.py/collect_process_memory.py/summarize_run.py) 존재, run_isolated.py 도 있으나 PLAN대로 재사용 금지(2nd JVM). wrapper 는 형제 import 하는 fork 코드 → PR-0 harness 커밋에 포함이 자연스러움.
- **이미지 배치**: JAR 빌드=fork(PR-0), Dockerfile+GHCR push=nextroute repo(PR-1). otp-config(feature flags)=이미지 굽기(codex), 런타임 graph.obj 만 file-mount.
### 함정 (환경 실측)
- **VPS 메모리 실측**: total 7901M, available 5195M(free 698 + buff/cache 4941 회수가능). 현 RSS 합 ≈ api675+worker631+pg2127+obs470(6컨테이너)+ml36+redis9 ≈ 3.95G. +OTP peak~2.0G = ~5.95G(PLAN "actual 5.9G" 일치). obs 스택 실측 470M = PLAN 예산 0.5G 안. **디스크 22G free**.
- **⚠ 호스트 swap 843M 이미 사용중**(PLAN 은 clean 가정). OTP 자체는 memswap_limit=3000m 로 swap 0 이라 OTP OOM 무관, 단 호스트 전반 압력 존재 → soak 중 `free -m` swap 증가 모니터 중요도 ↑.
- **app 실제 JVM = -Xmx768m / mem_limit 1200m**(PLAN §3 좌표의 -Xmx768m 확인). worst-case 합산 PLAN 계산 유효.
- `/srv/nextroute/otp` 디렉터리 부재 → runbook 에 mkdir -p /srv/nextroute/otp/<graphVersion> 선행 명시 필요.
### 미결 (여전히 착수 후 확정)
- eclipse-temurin:25-jre 정확 digest 고정 = PR-1 에서.
- OTP adapter in-flight semaphore = 배포 범위 밖, runbook 선행조건.

## OTP shadow 배포 — 세션 분리 확정 (2026-09-15, 사용자 조율)
### 확정 (실행 구조)
- **3 세션** (git 의존 아님, 값 핸드오프 순서 강제):
  - **S1** = PR-0. OTP fork(codex/otp-phase0-3). H+F 4+테3+seoul-metro harness+soak-wrapper 한 커밋 → 깨끗 재빌드 → JAR. 하드경계(다른 repo).
  - **S2** = PR-1(Dockerfile.otp+GHCR push, nextroute 브랜치) + P2(그 이미지로 graph 재빌드, 로컬 op). 묶은 이유=graph 를 그 이미지로 빌드해야 serialization identity 일치. graph 는 커밋 안 함(대용량).
  - **S3** = PR-3(compose otp 서비스+app env+OtpRoutingProperties.graphVersion bump) + PR-4(runbook+release-manifest). 둘 다 nextroute·둘 다 S2 값 소비. 브랜치 2개(파일 안 겹침).
- **graphVersion bump = PR-3 에 접음**(compose 가 graphVersion 경로 참조 → 응집). 별 PR 안 만듦.
- **핸드오프 파일**: docs/plan/otp-vps-shadow-deploy/handoffs/S{n}-out.md 로 고정(세션간 컨텍스트 미인계 대비 재현성).
  - S1→S2: JAR 경로+JAR SHA-256+fork commit SHA+revision+id 276.
  - S2→S3: GHCR image digest+temurin base digest+graph.obj SHA-256/크기+graphVersion+service window(2026-11-07).
  - S3: 전부 → manifest 대조.
- feedback-per-task-pr: nextroute PR(1·3·4) 서로 다른 파일 → 각자 fresh main 독립 브랜치, 스택 0.

## OTP shadow S1(PR-0) 구현 로그 (2026-09-15)
### 확정
- fork 커밋 = **8dbad55** (부모 02c0b4b baseline). 프로덕션4(H TripPatternForDates + F DestinationArrivalPaths/PathConfig/McRangeRaptorConfig)+테스트3+seoul-metro harness+신규 soak_compose.py+test_soak_compose.py 한 커밋. .gitignore(__pycache__) 추가.
- H 실체: TripPatternForDates 가 pattern×date 별 nStops*nTrips arr/dep int 배열 물질화 → TripTimes[]+per-trip offset lazy lookup.
- F 실체: DestinationArrivalPaths.qualify() allocation-free MutableDummyPath 프리필터, scalarOnlyComparator 플래그 게이트(호출부 true). ⚠정확성 민감=P4_FORMAL_PENDING, README correctness fingerprint 재현 필요.
- soak_compose.py = compose otp(이미 뜬 컨테이너) 대상 soak. run_isolated 와 대비(신규 JVM 안 띄움). load 종료후 collector SIGTERM graceful shutdown, deploy identity(image digest/cgroup Memory·MemorySwap/JAR·graph SHA) 기록. 순수헬퍼(deploy_identity/collector_covered/measured_end) 단위테스트 7.
### 함정
- **OTP 빌드가 Spotless(prettier) 단계에서 죽는다** ← maven:3.9-eclipse-temurin-25 이미지에 node/npm 없음. 증상="Can't automatically determine npm executable", validate 단계라 전체 블록. **가드: `-Dplugin.spotless.skip=true`** (pom 이 `<skip>${plugin.spotless.skip}</skip>` 로 파라미터화 — `-Dspotless.skip` 은 무효, 30분 헤맴). 포맷 체크라 바이트코드/JAR identity 무관(소스 이미 커밋). CI 는 node 있어 무관.
- **⚠ stale JAR 함정**: 첫 빌드 실패했는데 `mvn ... | tail` 파이프가 exit 0 으로 보고 → otp-shaded/target/ 의 **Sep-11 dirty JAR(SHA 54bbdbcf..db7f, git.dirty=true, commit=02c0b4b)** 를 확정본으로 오인할 뻔. PLAN 이 경고한 그 JAR. **가드: 빌드 후 otp-project-info.properties 의 git.commit.id==8dbad55 && git.dirty=false 확인 후에만 JAR SHA 채택.** clean package 로 stale 제거.
### 미결
- 재빌드(8dbad55, clean, spotless skip) 진행중 → JAR SHA-256 + git.dirty=false 확인 후 handoffs/S1-out.md 확정. id 276 은 소스=276 확인됨.
### 검증 (S1)
- clean 재빌드 JAR = SHA-256 `dcd08dedf7060bca3f89a14bd645bf972eda4998cc3acd8e5bab17a02ce45284`, git.dirty=false, commit 8dbad55, id 276. (dirty 54bb 와 다름 = 진짜 재현본.)
- 빌드: `docker run maven:3.9-eclipse-temurin-25 mvn clean package -pl otp-shaded -am -DskipTests -Dplugin.spotless.skip=true` 성공.
- 커밋된 H+F 테스트3 = 18 그린(TripPatternForDateTest 7 / TripPatternForDatesTest 7 / RaptorRoutingRequestTransitDataCreatorTest 4, failures 0). 이 3개 모두 application 모듈=H 커버.
- soak_compose 순수헬퍼 테스트 7 그린, 기존 harness 40 그린.
- ⚠ **전체 8,225 스위트 미실행**(비용). F(raptor path qualify) 회귀 = raptor 모듈 전체 테스트로 커버중. F 정확성 최종 게이트 = P4 correctness fingerprint(4 OD, PENDING, 전환 전).
### 배포
- handoffs/S1-out.md 확정. S2 입력 = JAR경로+SHA dcd08ded+commit 8dbad55+id276+빌드명령.
- **PR**: minsuk330/OpenTripPlanner#1 (https://github.com/minsuk330/OpenTripPlanner/pull/1), base dev-2.x, isCrossRepository=false (upstream 아님). 브랜치 원격 push 완료.

## OTP shadow S2(PR-1 이미지 + P2 graph) 구현 로그 (2026-09-15)
### 확정
- **Dockerfile.otp = nextroute PR #195** (base main, feat/otp-shadow-image). FROM temurin 25-jre amd64 digest 고정, curl apt, otp.jar+otp-config.json COPY, exec-form entrypoint, OCI label(revision/jar-sha/ser-id/base). 빌드컨텍스트(jar+config)=fork 산출물이라 커밋 제외.
- **GHCR image**: `ghcr.io/minsuk330/nextroute-otp:20260915-8dbad55` digest `sha256:77c240387c804135700bb466fb61ee7c1b0d969998a6bd2420f58099416eaa5a` (amd64 단일). base amd64 `sha256:65655f70..`, manifest-list `sha256:15090d15..`.
- **graph.obj**: SHA `4e098383e95566805f88a5de77e1cff9fe5838ef970cfa12345236d97e7ed72c`, 229,616,986 bytes, header 0000276(ser id 일치→load OK). \|V\|808,616 \|Patterns\|3,936 \|Stops\|43,783. 입력 SHA=기존 manifest 동일(gtfs 0cb23b0d/osm 0be8f32b). 로컬 원본=tools/transit-routing-benchmark/data/otp-seoul/graph-otp276-4e098383.obj(커밋금지). S3 scp 원본.
- OTP transitService 창 = [2026-09-14, 2026-11-16) (빌드일 -P1D~P2M) → 만료 2026-11-15. graphVersion 제안 seoul-bbox-202503-cal20260707_20261108-otp276-g4e098383 (S3 확정).
### 함정
- **🔴 PLAN healthcheck 쿼리 `{feeds}` 가 2.10 에서 죽는다** ← 2.9 는 feeds=scalar list 였으나 2.10 은 `[Feed]` object → subselection 필수. 증상 `Validation error (SubselectionRequired@[feeds])`. **가드: S3 PR-3 healthcheck 를 `{feeds{feedId}}` 로 정정**(data.feeds feedId 존재 + errors 없음). 안 고치면 컨테이너 영원히 unhealthy. S2-out.md 에 수정 compose 스니펫 있음.
- **GHCR 로컬 push 최초 = 403** ← docker cred 에 write:packages 없음(gh CLI 토큰도 없음). 가드: `docker login ghcr.io -u minsuk330` + classic PAT(write:packages) 필요. CI(GITHUB_TOKEN)만 써와서 로컬 첫 push 라 발생.
- otp-config 이미지 굽기 검증: serve 때 graph.obj **파일만** file-mount → 베이크된 /var/opentripplanner/otp-config.json 유지 확인(dir mount 시 가려짐). graph build 때는 stage dir 통째 mount 라 build-config+otp-config 다 stage 에 둠(필수).
### 검증 (S2)
- buildx --push 성공, 원격 digest 77c24038 확인, label 4값 정확.
- graph 재빌드(PR-1 이미지 --build --save) 3m26s, header 0000276.
- readiness smoke(graph.obj file-mount, -Xmx1900m): `{feeds{feedId}}`={"data":{"feeds":[{"feedId":"1"}]}}(errors 없음), planConnection 서울시청→강남 실경로(버스143/640,472/144). post-load RSS≈1.693GiB(로컬 참고, 3000m soak=S3).
### 배포
- handoffs/S2-out.md 확정. S3 입력 = image digest+base digest+graph SHA/크기/ser276+service window(2026-11-15)+graphVersion+**healthcheck 정정**.
- **PR**: minsuk330/next-route#195 (https://github.com/minsuk330/next-route/pull/195), base main.

## OTP shadow S3(PR-3 compose+env+graphVersion / PR-4 runbook+manifest) 구현 로그 (2026-09-15)
### 확정
- **PR-3 = nextroute #196** (feat/otp-shadow-compose, base main). compose.yaml otp 서비스 신설 + app-environment 앵커에 ROUTING_OTP_BASE_URL=http://otp:8080 + 토글2개(otp-enabled=false/default=ODSAY, api·worker 양쪽 상속). OtpRoutingProperties graphVersion→seoul-bbox-202503-cal20260707_20261108-otp276-g4e098383, schemaVersion→otp-2.10.0-planConnection-v1.
- **PR-4 = untracked 산출물**(GitHub PR 아님): docs/plan/otp-vps-shadow-deploy/deployment-runbook.md + release-manifest.json. tools/·docs/plan/ 이 untracked 관례라 manifest·runbook 도 로컬 파일. 2.9 benchmark manifest(tools/.../data/otp-seoul/release-manifest.json)는 **손 안 댐**(live prod graph 기록 보존) — shadow manifest 는 plan 디렉터리에 새로 씀.
### 함정
- **compose $ 보간**: healthcheck 쉘의 `$R`/`$(...)` 는 compose 가 변수 보간 → `$$R`/`$$(...)` 로 이스케이프 필수. `docker compose config` 로 렌더 확인.
- **bind mount create_host_path**: graph.obj 소스 없이 `up -d otp` 하면 Docker 가 **빈 디렉터리 graph.obj** 생성. runbook: scp+SHA검증 → 그 다음 up. 순서 강제.
- **adapter planConnection 쿼리는 2.10 에서 안 깨짐**(feeds 와 달리): src/main/resources/otp/planConnection.graphql 를 컨테이너에 실투입 → edges 정상 반환. 전환 시 이 쿼리는 재작성 불필요(feeds healthcheck 만 정정 대상). 첫 curl 시도 EOF 에러는 쉘 이스케이프 문제였고 python POST 로 정상 확인.
- schemaVersion/graphVersion 은 단순 버전태그 string(캐시키·메타). 신규 로직 없어 계약 테스트 불필요, 검증=compose config+compileJava.
### 검증 (S3)
- `docker compose config` 유효(exit 0). otp 블록: mem_limit/memswap 3145728000=3000m, ports 127.0.0.1:18080, graph.obj bind, healthcheck `{feeds{feedId}}`. ROUTING_OTP_* env 가 api·worker 양쪽 렌더 확인.
- `./gradlew compileJava` BUILD SUCCESSFUL.
- release-manifest.json JSON valid. config SHA build=16bfbf50/otp=342b442d 기록.
### 배포
- **VPS 수동**: OTP_IMAGE=digest .env, mkdir /srv/nextroute/otp/<graphVersion>, scp graph.obj+SHA검증, `docker compose up -d otp`만(api/worker 재생성 금지). 상세=deployment-runbook.md.
- **PR**: #196. shadow 세션 정리 — S1=PR-0 fork(OpenTripPlanner#1) / S2=PR-1 Dockerfile+image(#195) / S3=PR-3 compose(#196)+PR-4 untracked(runbook+manifest). 코드 전부 끝.

## OTP shadow VPS 배포 실행 (2026-09-15, 사용자 "VPS 배포까지 자율" 승인)
### 확정
- **#195·#196 main 머지**(ea4596c). **VPS 배포 라이브**: otp 컨테이너 healthy, feeds+planConnection(버스472/140) 실경로 응답.
### 🔴 함정 (하마터면 프로덕션 파손)
- **VPS compose.yaml 은 git repo 아님 + repo main 과 드리프트**. VPS 에만 있는 미커밋 프로덕션 설정: app `-Xmx768m`/`mem_limit 1200m`, Apple `.p8` 마운트, `JOURNEY_NOTIFICATION_RELAY_ENABLED=false`(api 부팅 가드). **repo compose scp 로 덮으면 앱 OOM+apple revoke+worker relay 가드 파손.** 가드: VPS 사본에 otp 블록+env 3줄만 **외과적 additive 삽입**(diff 로 removal 0 확인) 후 scp. 원본은 compose.yaml.bak-20260915 백업.
- bind mount 순서: graph.obj scp+SHA검증(4e098383) → 그 다음 `up -d otp`. dir /srv/nextroute/otp/<graphVersion> 선mkdir.
### 검증 (배포)
- `docker compose up -d otp` = **otp 만 생성**(api/worker created 2026-09-07 그대로, 8일 uptime healthy = 재생성 0).
- otp healthy 22s(x86 native). `{feeds{feedId}}`=feeds OK. RSS 1.464GiB/2.93GiB(50%, 한도밑). available 4637→3466M, swap 845→874M(+30M, 무압력).
- **rollback 드릴**: `docker compose stop otp` → api/worker healthy 유지 → `up -d otp` 재기동 healthy. 앱 무영향 확인.
- manifest ↔ 실 image digest 77c24038 / graph SHA 4e098383 / id 276 대조 일치.
### soak (짧은 c1/c2/c4, VPS 실측 2026-09-15)
- harness = OTP fork test/performance/seoul-metro/{soak_compose,compare_graphql,collect_process_memory,summarize_run}.py 를 VPS /root/otp-soak 로 scp. VPS python3.10, docker exec PID1 RSS + host port 18080 부하. corpus 4 OD(cityhall-gangnam/cheonggye-sadang/bupyeong-seoul/bupyeong-gangnam).
- **결과(req40/warmup10)**: c1 peakRss 1566M/contPeak 1612M, c2 1568/1614, **c4 1723/1771M**. 전 run memErr 0, coverage before+after true, 샘플갭 없음. **최대 1771M ≪ cgroup memory.max 3145728000(3000m), 59%**. -Xmx1900m heap 근처도 안 감. host swap 869→870M 평탄, available >3.5G = 이웃 무영향.
- status=INCOMPLETE 는 **정상**(--gc-log 안 줌 → gcLogMissing, 메모리 soak 유효. soak_compose.py:170 주석 확인).
- latency(c1 measured): p50 1161/p95 1406/p99 1653/max 2246ms — elaborate planConnection(다필드) 응답, 오류 0.
- **함정(무해)**: collect_process_memory 가 mac용 `sysctl vm.swapusage` 프로브 → Linux 에서 "cannot stat" 경고. RSS 는 docker exec 로 수집돼 무관. output-dir 는 exist_ok=False → run 별 fresh 경로 필수.
### 미결 (선택)
- **30분 지속 soak(c4)** 미실행 — 짧은 run 은 누수 징후 0(delta +27M/163s)이나 장시간 누적 확인은 별도. 전환 전 권장.
- OTP_IMAGE 는 VPS .env 에 digest 고정 기록됨.
## 2026-09-15 journey-reroute-nextstop PR-0 (스파이크 — 절단표현 + 진행판정)

산출 = 문서(이 항목) + fixture `src/test/resources/otp/planconnection-stopcall-times.json`. 코드·마이그레이션·플래그 없음. 결론: **두 축 다 실현 가능. 단 legacy 경계 우회(Q1) + copyToRevision trim 변형(Q2)이 전제.**

### 확정 (Q1 — board→X 소요 + 지도선 슬라이스, 투영 없음)
- 현재 `planConnection.graphql:42` 는 `stopCalls.stopLocation{gtfsId,name,lat,lon}` 만 받고 **시각을 안 받는다**. `legGeometry.points`(:49)는 이미 받음. 진짜 결핍 = **stopCall 시각뿐**(PLAN 리뷰가 맞음). PR-A 가 `schedule{time{arrival departure}}` + `realTime{arrival{time delay} departure{time delay}}` 를 추가.
- board→X 소요 = `X.stopCall.time − board.stopCall.time` = **순수 뺄셈, 투영 0**. fixture 실측: 143번 롯데백화점(occ0 08:12:28)→용산구청(occ5 08:22:50) = **622s**, board→고속터미널(occ8) = 936s = `leg.duration` 일치.
- 절단 지도선 board→X = `stopCalls[board..X]` **좌표 이어그림, 투영 0**(fixture 6좌표). polyline 디코드·투영·오차·폴백 전부 불필요(PLAN 확정과 일치).
- **유실 경계 = `LegacyRouteResponseMapper.toSubPath`(:114)**: polyline=null(:153 3번째 인자), `sectionTime`=분단위 반올림(:121 — 초·정류장별 시각 소멸), `StationResult`(:164 `toStations`)=`(occurrenceIndex, sourceStopId, name, lon, lat)` **시각 필드 없음**. 즉 `RoutingLeg.geometry.points`+`boardAt/alightAt`+`StopOccurrence` 좌표가 원천엔 있으나 PathResult 변환이 **전부 버린다**.
- `RemainingRouteSearchService.loadOtp`(:155)가 검색 직후 즉시 `toLegacy` 를 탄다 → **PR-A 는 mapper 확장이 아니라 legacy 우회가 깨끗**: RIDE 재탐색 경로만 `RoutingResult`/`RoutingLeg`(+stopCall 시각) 를 직접 소비(중립 `StopCall` 모델). SubPathResult 34필드 확장/StationResult 시각추가는 검색(#1) 정적 계약까지 흔들어 회귀면 큼([[feedback-server-static-search-front-realtime]]).
- `StopOccurrence`(record) 시각 필드 없음. `sourceStopSequence`·`tripPattern` 자리는 있으나 mapper 가 null 로 채움(`OtpPlanConnectionMapper:175,:189`). PR-A 중립 StopCall = StopOccurrence + `schedule/realTime` 시각 + `(trip, sourceStopSequence, occurrenceIndex)` 식별.

### 확정 (Q2 — 진행중 RIDE 하차점 교체 + progress 보존)
- `JourneyLeg.copyToRevision`(:179)이 progress 전 필드 承계: `lastProgressId·progressGeometrySegmentIndex·progressFraction·progressLegFraction·progressObservedAt·progressSectionId·remaining{DistanceMeters,DurationSeconds,Stops}·predictedEndAt·evidenceSource/ObservedAt`. `selectedRouteOptionId` 만 비움(유령 FK 방지).
- **절단은 꼬리 자르기**(X 가 현재위치보다 앞, 옛 하차점보다 뒤) → `progressGeometrySegmentIndex`·물리위치·`lastProgressId` 안 흔들림. **"몇 분 남음"이 뒤로 안 튄다**(꼬리 truncation 은 현재위치 이전 인덱스 불변).
- 진행 가드 = `applyProgressIfNewer`(`JourneyLegRepository:52`) WHERE `progressObservedAt is null or progressObservedAt < :observedAt` = **시각 단조, id 아님**. `lastProgressId` 는 DUPLICATE 판정 전용(`JourneyProgressService:145`).
- "재생 id" 방어 이중: ① 새 revision 발행 → 옛 revision 태그 progress 는 `JourneyProgressService:62`(`command.routeRevision() != journey.getRouteRevision()` → STALE_ROUTE_REVISION)에서 **leg 도달 전 선차단** → 클라 강제 재조회 ② 같은 revision 내 늦게 온 관측은 observedAt 단조가 막음.

### 함정
- **copyToRevision 만으로는 절단 RIDE 가 틀린다**: `endStopId/endName`=옛 하차점, `plannedDurationSec`=옛 leg 전체, `remaining{Stops,Duration,Distance}`·`progressLegFraction`=옛(먼) 하차점 기준(분모). X 로 절단하면 이 값들이 **옛 값 그대로** → "몇 분 남음"이 X 아닌 옛 하차점까지. **PR-4 필요 = `copyToRevisionTruncated(newRev, X식별, board→X초)` 변형** or copy 후 조정: `endStopId/Name→X`, `plannedDurationSec→board→X`, `remaining*·legFraction→X 기준 재계산`. **보존**: `progressGeometrySegmentIndex·lastProgressId·progressObservedAt·progressFraction`(구간내 비율).
- **⑤ `rebuild` 경계가 PR-4 에 안 맞는다**(`RerouteAcceptTxService:229`): 규칙 = `legIndex<fromLegIndex` → copyToRevision(진행보존) / `>=fromLegIndex` → 새 JourneyLeg(PENDING, 진행 없음). ⑤ 절단점=**미래 WAIT**라 새로 만들어도 진행 없음이 정상. PR-4 절단점=**현재 RIDE(진행중)** → 그 규칙대로 새로 만들면 진행 전멸 = GPS 엔진 리셋. 현재 RIDE 는 **copy+trim = 제3케이스**(순수 copy 도 순수 rebuild 도 아님). PR-C composer 가 절단 leg 를 이 케이스로 방출하고 rebuild 가 `legIndex==currentLegIndex && 절단` 특수분기.
- `clearProgressAndPrediction`(`JourneyLeg:220`)은 **꼬리(Y) 전용**. 절단 RIDE 에 쓰면 진행 소멸 — 쓰지 말 것.
- 절단 RIDE 의 짝 WAIT(`pairedLegIndex`, `JourneyLegDraft`)은 프리픽스(복제)라 불변. 갈아탈 Y = 새 WAIT+RIDE 꼬리, pairing 은 꼬리에서 새로 맺음.

### 좌표
- src/main/resources/otp/planConnection.graphql:42 — stopCalls(시각 미쿼리, PR-A 확장점)
- infrastructure/adapter/out/api/otp/OtpPlanConnectionMapper.java:166 toStops — StopOccurrence 시각·seq null(:175 stopCall / :189 from·to)
- application/route/service/LegacyRouteResponseMapper.java:114 toSubPath — polyline null(:153)·sectionTime 분단위(:121)·시각 유실 경계(PR-A 우회 대상)
- application/route/routing/StopOccurrence.java — record, 시각 필드 없음(중립 StopCall 신설 근거)
- application/journey/service/RemainingRouteSearchService.java:144 loadOtp — clock.instant() 고정 + :155 즉시 toLegacy
- domain/journey/entity/JourneyLeg.java:179 copyToRevision — progress 承계(절단엔 trim 변형 필요) / :220 clearProgressAndPrediction(꼬리 전용, 절단 금지)
- domain/journey/repository/JourneyLegRepository.java:52 applyProgressIfNewer — observedAt 단조 가드(id 아님)
- application/journey/service/JourneyProgressService.java:62 — routeRevision 불일치 선차단(재생 id 방어①)
- application/journey/service/RerouteAcceptTxService.java:229 rebuild — ⑤ 선례(PR-4 확장 대상, 경계규칙 부적합)
- src/test/resources/otp/planconnection-stopcall-times.json — PR-A 시각 fixture(PR-0 산출, board→X 622s·지도선 6좌표 실측)

### 미결
- PR-A **legacy 우회 vs DTO 확장** 최종 택 = PR-A 착수 시 확정(우회 권장, 위 근거). **national OTP 마이그(P2-B/P2-G) 담당과 조율 필수**(PLAN 명시 — 병렬 중복 위험).
- 절단 RIDE progress 의 앱 GPS 엔진 정합: `progressGeometrySegmentIndex` 가 절단 geometry 기준으로 재기준 필요한지(앱이 slice 된 지도선을 다시 받으므로 segmentIndex 의미가 바뀔 수 있음). 앱 계약 확인 = PR-4.

### 배포
- PR-0 = 문서+fixture. 마이그레이션·코드·플래그·설정 **없음**.

### 검증
- 코드 리딩 스파이크(변경 0). fixture JSON 파싱 유효 + worked example 실측(python): board→X(용산구청)=622s(순수 뺄셈), board→full=936s=leg.duration 일치, 지도선 slice=6좌표, realTime shape(occ0·occ1 delay=0/60) 확인. fixture 소비 테스트는 PR-A 가 작성.

## 2026-09-15 otp-provider-switchover PR-1 (OTP 동시성 gate)

### 확정
- **interface 에 role 파라미터 추가**: PLAN §4 는 `tryAcquire(RoutingDeadline)` 였으나 codex 가 요구한 "메트릭 역할(api/worker) 태그"를 지키려면 어댑터가 호출자 역할을 알아야 한다 → `tryAcquire(RoutingDeadline, String role)`. 단일 글로벌 semaphore 는 그대로(역할 분할 아님). role 은 저카디널리티 고정값("api"/"worker").
- **메트릭 3종을 어댑터가 발행**: `otp.sem.acquire{role,result=success|saturated|redis_error}` 카운터 + `otp.sem.wait{role,result}` 타이머. PLAN 이 따로 적은 `otp.sem.redis_error` 는 acquire{result=redis_error} 로 흡수(별 이름 안 만듦, 카디널리티 절약).
- **startup validation = 별도 @PostConstruct 빈**(`OtpConcurrencyConfigValidator`). properties 안에 넣지 않은 이유: `leaseMs ≥ max(api odsayMs, worker searchBudgetMs) + margin` 은 3개 properties 를 교차 참조 → @ConfigurationProperties 자체 검증으로는 부족. 현값 api 2000/worker 3000/margin 2000 → 요구 최소 5000, 기본 lease 8000 통과.
- **worker 예산 출처 = `JourneyRerouteProperties.getRemainingPath().getSearchBudgetMs()`**(=3000). api = `RouteSearchDeadlineProperties.getOdsayMs()`(=2000).
- **deadline 1개 공유가 계약**: api(runSearch)·worker(load) 둘 다 deadline 을 한 번 만들어 gate 대기 + OTP 호출에 같은 인스턴스 주입. worker 는 기존 loadOtp 가 자체 생성하던 것을 제거하고 loadOtpGated 에서 만들어 넘김. 테스트가 `isSameAs` 로 동일 인스턴스 고정.
- **포화 응답**: api = `OdSayApiException.timeout("OTP semaphore saturated")`(handleOtpSaturated, 기존 504 계약 재사용, ODSAY 폴백 없음). worker = 빈 목록 + `metrics.recordRemainingSearch("otp-saturated")`(재탐색 실패 계약, OTP-only).
- **fail-closed**: Redis 예외 = empty(포화와 동일). mem_limit≠동시성상한이라 fail-open 금지.
- **best-effort 상한**: 공용 allkeys-lru Redis 에 ZSET(`otp:sem:permits`), 축출 가능 → 코드/주석에 "강한 전역 상한 아님, 하드 상한=OTP mem_limit" 명시(사용자 리스크 감수 결정).

### 함정
- `redisTemplate.execute(script, keys, args...)` 는 varargs → Mockito 스텁 매처 개수를 실제 호출 인자 수(keys 1 + args 3)에 맞춰야 매칭됨. fail-closed 테스트에서 `any(),any(List),any(),any(),any()` 5개로 맞춤.
- Lua now 는 **Redis TIME**(`redis.call('TIME')`)로 계산 — app Clock 아님(api/worker skew 회피). ZREMRANGEBYSCORE + ZCARD + ZADD 를 한 EVAL 로 원자화.
- acquire 실패(정원 초과)시 Lua 가 ZADD 안 함 → 실패 토큰 잔류 없음(재시도마다 새 UUID 여도 leak 없음).
- **JourneyTransferAssessmentIT 실패는 기존 무관**: origin/main(ea4596c) 클린 베이스라인(내 변경 stash 후)에서도 동일 실패(1/1). 정적 데이터(busLocalBlID 미해석) 의존, PR-1 무관. 재조사 방지.

### 좌표
- application/route/routing/OtpConcurrencyGate.java — Port(+OtpPermit record)
- infrastructure/adapter/out/cache/route/RedisOtpConcurrencyGateAdapter.java — Lua EVAL semaphore
- application/route/config/OtpConcurrencyProperties.java — permits(4)/leaseMs(8000)/retryIntervalMs(20)/safetyMarginMs(2000)
- application/route/config/OtpConcurrencyConfigValidator.java — @PostConstruct lease≥예산+margin 강제
- application/route/service/RouteSearchService.java:100 runSearch OTP 분기(gate 대기→runOtpSearch(deadline)) / handleOtpSaturated
- application/journey/service/RemainingRouteSearchService.java loadOtpGated → loadOtp(deadline)

### 배포
- **마이그레이션 없음**. 신규 설정 `routing.otp.concurrency.*`(전부 기본값, yaml 미기재 = 코드 기본 사용).
- **PR-1 머지 후 앱 재배포 시점엔 gate dormant**: otp-enabled=false(기본) → runSearch/load 의 OTP 분기 미진입 = gate 미호출. 회귀 0.
- 전환(별도 결정): compose api/worker 에 `ROUTING_OTP_BASE_URL=http://otp:8080` + `ROUTING_PROVIDER_DEFAULT_PROVIDER=OTP` + `ROUTING_PROVIDER_OTP_ENABLED=true` 3값 + 재생성. 롤백=3값 되돌림.

### 검증
- `./gradlew compileJava` / `compileTestJava` 통과.
- 신규 단위 27: RouteSearchServiceTest 11(+OTP gate 3), RouteSearchServiceOtpWiringTest 2, RemainingRouteSearchServiceTest 8(+OTP gate 2), RemainingRouteSearchServiceOtpTest 2, RedisOtpConcurrencyGateAdapterTest 4(fail-closed/interrupt/expired/release-null). 실패 0.
- 신규 IT 5: RedisOtpConcurrencyGateAdapterIT(정원N/반납재획득/lease만료회수/idempotent/동시반납). 실 Redis(testcontainers). 실패 0. → @SpringBootTest 기동으로 OtpConcurrencyConfigValidator @PostConstruct 도 통과 확인.
- 전체 `./gradlew test` = 2091 tests, 1 failed(JourneyTransferAssessmentIT=기존 무관, 위 함정), 3 skipped.

## 2026-09-15 otp-provider-switchover 전환 시도 → 롤백 (예산 블로커 발견)

### 확정 (전환 블로커 = PR-1 무관, OTP 호출 예산 부족)
- **실 트래픽 OTP 전환 시 검색 100% 502**. 원인 = OTP planConnection 왕복이 api mandatory-stage 예산 `route.search.odsay-ms=2000ms` 초과 → RestClient readTimeout(=남은예산) 발화 = "Request cancelled" I/O error → handleOtpFailure ENGINE_ERROR → 502.
- **실측(2026-09-15 VPS)**:
  - otp:18080 직접 planConnection(강남역→서울역, warm) = **1.6s**(3회, edges 정상, routingErrors 빈). 
  - api 경유 검색 = **매번 502 at t=2.01s**(5/5). readTimeout 2000ms 정확히 발화. 차이 ~0.4s = api 측 RestClient 연결+요청직렬화+대용량 응답 역직렬화(legs/geometry/stopCalls) 오버헤드.
- **gate(PR-1) 정상 무관**: permit N=4 즉시 획득(대기 0), validator 통과. 502 는 permit 획득 후 OTP 호출 자체가 timeout. semaphore 는 제 일 함.
- **ODSAY 는 200**(롤백 후 t=2.3~3.7s): ODSAY 도 느리지만 mandatory ODSAY 단계는 odsayMs 안에 들고 나머지는 enricher 라 degrade. OTP 는 OTP 호출 자체가 mandatory 단계라 초과=하드 502.

### 함정
- 전환 = `ROUTING_PROVIDER_OTP_ENABLED:"true"` + `DEFAULT_PROVIDER:OTP` compose 외과삽입(diff 2줄만, removal 0 확인) + `docker compose --env-file .env up -d --no-deps --force-recreate api worker`. 정상 반영·기동됨(env printenv/validator 로그 확인). **문제는 배선이 아니라 예산.**
- 롤백 = 백업 compose 복원(compose.yaml.bak-20260915-toggle) + 재생성. 즉시 ODSAY 200 복구.

### 미결 (전환 재개 전 해결 필요 = 새 PR)
- **OTP 호출 예산 상향**: 현 `runOtpSearch`/`loadOtp` 는 `ctx.budgetMillis(odsayMs=2000)` 재사용. OTP 왕복 ~2.0s+ 이라 부족. 선택지: ① OTP 전용 예산 property(`route.search.otp-ms`, 예 3000~3500) 신설 + deadlineMs(현 3000) 동반 상향 ② odsayMs 자체 상향(ODSAY 롤백 경로 tail 도 영향, 단 ODSAY 는 60ms라 무해). deadlineMs 가 상한이라 같이 올려야. **tail-cutting 설계(RESULTS.md 근거) 재검토 대상 — 사용자 결정 필요.**
- 재확인 필요: 복잡 경로(다환승)의 planConnection latency 분포. 1.6s 는 단순 경로 warm 값 — p90/p99 로 예산 정해야.

### 배포 (현재 상태)
- **전환 롤백 완료 = dormant 유지**. api/worker = nextroute:20260915-223926-eb50084(PR-1 gate 코드 포함, dormant), OTP_ENABLED=false/DEFAULT_PROVIDER=ODSAY. ODSAY 라이브 정상.
- VPS 백업: compose.yaml.bak-20260915-toggle(전환 전=dormant, 현재 이걸로 복원됨), compose.yaml.bak-20260915-otptoggle-attempted(전환 시도 상태 보존).

## 2026-09-16 national-otp P2-C → 재탐색 캐시 제거 (접기)

### 확정
- **P2-C(시간의존 캐시+graph pinning) 를 "구현"이 아니라 "제거"로 종결.** 근거: 캐시의 유일 소비자 = 재탐색(RemainingRouteSearchService)인데, 재탐색은 계약상 실시간(feedback-server-static-search-front-realtime: "실시간 판정은 주행 재탐색에서만"). 구조를 TTL 10분 공유하면 이미 떠난 편·바뀐 상황을 굳은 값으로 돌려줘 재탐색 목적과 충돌. P2-C의 ②envelope(떠난편 거르기)는 그 충돌 땜빵이었고, 계약과 안 맞는 캐시는 땜빵보다 제거가 맞다(사용자 결정).
- **SingleFlight 도 재탐색에서 제거**(클래스는 bus/subway/검색이 써서 존치). 캐시 없으니 stampede 억제 대상도 사라지고, 재탐색 키는 거의 안 겹쳐 사실상 죽은코드. OTP 과부하 backpressure = 캐시/SingleFlight 아니라 OtpConcurrencyGate(permit N).
- **RoutingCacheContext 삭제**: 캐시 전용 래퍼였음. provider 선택(OTP/ODSAY 분기)만 남겨 RemainingRouteSearchService.search() 안에 인라인(providerSelector.select(false) 1회).
- **동작 변경 0 (dormant)**: reroute enabled=false(기본) + OTP dormant. 캐시는 원래 죽은코드였음 → 프로덕션 회귀 없음.
- metrics 계약 유지: 성공="called", 빈답/실패="failed", "otp-saturated"/"otp-failed-*" 그대로. 제거된 것=캐시 전용 "cache-hit"/"single-flight-join".

### 함정
- yaml `journey.reroute.remaining-path.cache-ttl: PT10M` 은 field 삭제해도 @ConfigurationProperties 가 unknown 무시라 부팅은 안 깨지지만 dead config → 같이 제거. validator 의 cache-ttl 양수검사도 제거(안 지우면 getter 없어 컴파일 깨짐).
- RemainingRouteSearchService 생성자 시그니처 변경(cache, OtpRoutingProperties 2개 param 제거) → 테스트 3곳(Test/OtpTest/IT) 전부 생성자·MockBean 수정 필요. otpRoutingProperties 는 캐시 graphVersion 용도뿐이라 같이 제거됨.
- IT unresolvedStop 테스트: "미해석 정류장 skip" 은 캐시키 섞임이 아니라 상위(candidate provider) 가드라 캐시 제거 후에도 유효. verify(cache,never) 줄만 제거.

### 좌표
- 삭제: application/journey/port/out/RemainingRouteCachePort.java, infrastructure/adapter/out/cache/journey/RedisRemainingRouteCacheAdapter.java, application/route/routing/RoutingCacheContext.java (+ 테스트 RoutingCacheContextTest, RedisRemainingRouteCacheAdapterTest)
- 수정: application/journey/service/RemainingRouteSearchService.java (캐시·SingleFlight·cacheContext 제거), application/journey/config/JourneyRerouteProperties.java (cacheTtl field+validation 제거), resources/application.yaml:504 (cache-ttl 제거), 테스트 RemainingRouteSearchServiceTest/OtpTest, RerouteRemainingPathIT

### 미결
- P2-C 원문의 "graph 인스턴스 pinning + 응답버전 검증"(#5)은 캐시와 함께 소멸했지만, 별개 리스크로 남음: RoutingResult.graphVersion = config 하드코딩(OtpRoutingProperties), 라이브 OTP graph 검증 0. 캐시 없으니 "오염" 리스크는 사라졌으나, 응답 메타(graphVersion)가 거짓일 여지는 여전 → 전환 전 별건 판단.
- P2 남은 갭: D(ID매핑 #181 열림·보류), F(route-option #193 열림), H 예산(#198 열림). C 는 이 커밋으로 "제거 결론" 종결.

### 검증
- `./gradlew compileJava compileTestJava` 통과(EXIT 0, 경고=기존 MockBean deprecation만).
- 단위: RemainingRouteSearchServiceTest(5), RemainingRouteSearchServiceOtpTest(2), JourneyRerouteProperties* green.
- IT: RerouteRemainingPathIT green(풀 Spring context 부팅 성공 = 빈 배선 정상, RemainingRouteCachePort @Component 삭제 후에도 컨텍스트 OK).
- 잔존참조 grep: RemainingRouteCachePort/RoutingCacheContext/RedisRemainingRouteCacheAdapter 0(다른 cache-ttl-seconds 는 ML/bus/subway 무관).

## 2026-09-16 national-otp P2-D → #181 close (과확장 폐기, resolver 로 완료)

### 확정
- **P2-D = 방법B resolver(OtpSeoulIdentityResolver, main 라이브, #182 머지)로 완료 처리. #181(versioned 매핑 표) close.**
- #181 = 필요 이상 확장 사례: 실제 필요 = "OTP id→서울 id" 결정적 strip(`BS_TAGO_SEB<9자리>`→stop_id, `BR_TAGO_SEB<9자리>`→route_id) 하나인데, 이를 버전표+VERIFIED/REVIEW 상태기계+JPA영속화+V49 마이그+스키마IT 로 부풀림.
- **폐기 근거**: ① TransitIdentityMappingPort 소비자 0(배선·데이터 없음, grep 빈결과) ② 규칙이 결정적이라 모호매핑 중재(VERIFIED/REVIEW)·버전표·영속화 전부 미사용 ③ 실 id 해석은 이미 resolver 가 4곳(LegacyRouteResponseMapper 검색id채움/OtpLegCoverageResolver/OtpBusRealtimeTimingLookup/self)에서 라이브 ④ 머지 시 죽은코드+V49 VPS 수동배치 리스크만.
- P2-C 캐시 접기와 동일 패턴(과설계 fold). reopen 가능하게 close(삭제 아님).

### 미결
- P2-D 잔여 리스크(표와 무관, 별개): RoutingResult.graphVersion=config 하드코딩, 라이브 OTP graph 검증 0. 전환 전 판단.
- resolver 스코프 하드제약 = 서울 버스+지하철1~9. 경기(GGB)/인천(ICB)/기타노선=empty(STATIC). 수도권 확장 시 재실측 필요(표가 아니라 resolver 규칙 확장으로).
- P2 남은 = F(#193 열림), H예산(#198 머지됨). C·D 종결.

### 검증
- 코드변경 0(PR close 만). main resolver 는 기존 테스트가 커버(#182 7 계약테스트).

## 2026-09-16 national-otp P2-F PR-4 (#193) 머지 — 드리프트 검증 후

### 확정
- **#193(OtpPatternRouteOptionSource + UnionRouteOptionSource) main 머지(7baebc9).** 2026-09-10 생성 후 main 8커밋 전진(#195~199) → 드리프트 검증하고 머지.
- merge-tree = CONFLICT 0(JourneyRerouteProperties 는 otpPatternEnabled 를 RouteOption 에 추가 = 내 P2-C cacheTtl 제거(RemainingPath)와 다른 위치라 auto-merge). #193 은 캐시 파일 무관 → PR #199(P2-C 제거)와 독립, 순서제약 없음.
- 게이트 `journey.reroute.route-option.otp-pattern-enabled` 기본 false → 머지해도 동작변경 0(dormant). 실제 제안 ON 은 PR4c(노선별 실주행 ETA) 후.

### 함정
- 광역 IT 슬라이스(`*Reroute* *RouteOption*`) 28 실패 → **전부 Docker 데몬 off**(testcontainers Postgres 못 뜸, `localhost:5432 refused`, Flyway 단계서 컨텍스트 로드 실패=앱 빈 생성 전). #193 무관(마이그레이션·DB변경 0). Docker 켜고 재실행 → BUILD SUCCESSFUL. **IT 대량 실패 시 `docker ps` 부터 확인**(재조사 방지).

### 검증
- merged(main+#193) `compileJava compileTestJava` clean.
- 유닛 green: OtpPatternRouteOptionSourceTest 9 + UnionRouteOptionSourceTest 4 + RouteOptionCandidateProviderTest 21 + JourneyRerouteProperties* + OtpStopPatternMapper.
- Docker 재가동 후 `*Reroute* *RouteOption*` IT 슬라이스 **BUILD SUCCESSFUL**(RerouteAcceptIT/RerouteRouteOptionIT/RerouteRemainingPathIT 등 = 드리프트 회귀 0).
- 경계 리뷰: 게이트 off=저장값(PR-2 무변경), OTP실패=저장 폴백, dedup=(routeId,boardingSeq) baseline 제외, 방향=정차순서 count==1, 매핑/순번≠1 제외, DYNAMIC transient. codex B3/B4/B5/B6/B8/B10 부합.

### 미결 (P2-F 남음)
- **PR4c**(노선별 실주행 ETA, RouteRideDurationPort) = 설계확정(위 2026-09-10 블록), 구현대기. 실제 제안 ON 은 이거 후.
- P2 전체: A/B/C(제거)/D(#181 close)/E/F(#193) 종결. 남은=PR4c + H(전환 재토글, #198 머지됨).

## 2026-09-16 national-otp P2-F PR4c (#200) 머지 — P2 코드 완결

### 확정
- **PR4c(RouteRideDurationPort + 노선별 실주행 ETA + 공정성 게이트) main 머지(#200, c9dbc6e).** 사전빌드 브랜치 feat/otp-p2f-route-ride-duration(3커밋, 2026-09-10) 를 현 main 에 통합.
- **이로써 national-otp P2 코드 완결**: A/B/E(기존) + C(제거 #199) + D(#181 close, resolver) + F(#193 + PR4c). G/H = 증분 스트림이 커버(별도 코드 없음). 남은 것 = 배포(전환 재토글, #198 머지됨) + P3~P8(지도/모바일/배포, 미착수·나중).

### 함정 (드리프트)
- PR4c 브랜치는 #193 머지 전 빌드 → main 12커밋 전진(#193·#195~199) 후 병합 시 **코드 auto-merge clean, 유일 드리프트 = 테스트 생성자**. #193 이 추가한 RouteOptionCandidateProviderTest.dynamicCandidateCarriesSnapshotApply 의 `new RouteOptionCandidateProvider(...)` 호출이 PR4c 신규 인자 rideDurationPort 누락(git 텍스트충돌로 못 잡음, 컴파일 에러로만 드러남). 별도 커밋(#200 의 10ed6fc)으로 rideDurationPort mock 주입. **교훈: 두 브랜치가 같은 생성자를 늘리면 auto-merge 후 반드시 compile 검증**(merge-tree clean ≠ compile clean).
- 병합 검증 = verify 브랜치(main+PR4c+픽스)에서 compile+유닛+IT green 확인 후 그 브랜치로 PR #200.

### 검증
- merged compile clean. 유닛: OtpRouteRideDuration Mapper/AdapterHttp, RouteOptionEtaCalculator(override/하위호환), RouteOptionCandidateProvider(공정성/승격/shadow/RIDE_BUS 폐기) green.
- IT `*Reroute* *RouteOption*` BUILD SUCCESSFUL(Docker up).

### 미결
- 실제 제안 ON(shadow OFF) = 배포 결정(otp-pattern-enabled + enabled + comparable 게이트, 현 dormant).
- baseline OTP 되찾기 커버리지·rideSec 캐시 = OTP on 후 실측(프로덕션 0 지금).
- 전환 재토글(#198) + P3(지도 형상)가 다음 큰 축.

## 2026-09-16 national-otp P3 착수 — OSM 형상 방향 확정(pfaedle) + 스파이크

### 확정 (설계 결정, 사용자 승인)
- **P3 형상 주경로 = pfaedle**(안3), NOT OSM route relation(안1, 죽음). 근거는 스파이크 2건:
  - **안1(OSM route relation) 스파이크 = 실패**: 서울 버스 13노선 표본 중 11개는 OSM에 route_master 자체가 없음(`candidates=[]`), 있는 502·152도 platform 매칭 33~59%·gap 수km. 한국 버스노선 OSM relation 매핑이 성김. 알고리즘 개선으로 못 고침(데이터 부재).
  - **안3(pfaedle) 스파이크 = 성공**: pfaedle(OSM 도로망 map-matching)로 5노선 shape 생성 → VPS 기존 line(bus_route_segment_polyline)과 **평균 4~6m 일치**(p90 ~10m). 커버리지 100%.
- **조사 근거**: 시중 앱 = ①메이저(네이버/카카오/티맵)=자체 실측 노선경로 ②중소=ODsay loadLane(실측) ③OSS=OSM도로+OSRM/Dijkstra map-matching. pfaedle = ③의 GTFS 특화 표준(ad-freiburg, 2018 SIGSPATIAL). KTDB GTFS엔 shapes.txt 없음→OTP가 정류장 직선 그림(intent 금지)→pfaedle이 shapes.txt 생성→OTP native 소비.
- **하이브리드 curation**: pfaedle 전노선 base + VPS 기존 line 교차검증. 일치(~5m)=고신뢰 / VPS REVIEW·깨짐=pfaedle 채택(273 사례: VPS 111구간중 107 REVIEW=96% 깨짐, pfaedle이 full 노선 메움) / 국소 Hausdorff 스파이크(143=198m·152=117m·502=369m)=플래그→degenerate 버리기 or REVIEW. **불일치 자동 "정답판정"은 불가**(oracle 없음)—명백히 깨진 쪽만 자동, 애매하면 REVIEW/degraded(가짜 형상 금지, intent 준수).

### 함정 (재현 도구)
- **pfaedle Docker ENTRYPOINT=`pfaedle`**: `docker run img pfaedle -x ...` 처럼 `pfaedle` 단어 또 넣으면 그게 feed명으로 잡혀 "Multiple feeds only allowed in filter mode" 에러. → `docker run ghcr.io/ad-freiburg/pfaedle:latest -D -x sk.osm.pbf gtfs-sub`(명령어 없이 인자만).
- 이미지 amd64(arm64 에뮬), OSM 273M 파싱 ~47s, 804 trip 매칭 OK. `-m bus`로 MOT 한정 가능(tram/coach 0매칭 로그는 무해).
- VPS DB: user=myuser db=mydatabase(postgres 롤 아님). 폴리라인 테이블=bus_route_segment_polyline(route_id+from/to_seq+geom LineString4326+polyline jsonb+quality_status OK/REVIEW). 서울 route_id=SEB strip 9자리(100100022=143). 지하철=odsay_route_polyline, subway_segment.
- **기존 VPS line도 26% REVIEW**(577구간중 150). 사용자 지적("ODsay도 깨진 게 많다") 실측 확인 — 그래서 pfaedle base가 더 나음.

### 미결 (본구현 스코프에서 확정)
- 표본=서울 간선 5개만. 지선·지하철(rail MOT)·경기 경계 미측 → 파이프 구축 중 전량 실행으로 확인.
- 방향/variant: VPS line ↔ pfaedle shape 방향 정합(현 스파이크는 최근접 1개 비교).
- OTP legGeometry 소비 배선: #188이 OTP 검색 geometry 제거(applyCoverage) — pfaedle shape가 graph에 들어가도 앱까지 어떻게 전달할지(OTP native vs 별도 폴리라인 API) P3-B에서 결정.
- graph 재빌드=P1 파이프(prepare_gtfs→**pfaedle 스텝 삽입**→zip→build)로 새 graphVersion. VPS 배포.

### 도구 좌표 (스크래치패드, 재현용)
- venv: osmvenv(osmium 4.3.1, shapely 2.1.2). pfaedle: ghcr.io/ad-freiburg/pfaedle:latest.
- GTFS subset 스크립트=gtfs_subset.py(route_id set→참조폐쇄). 비교기=compare.py(shapes.txt vs VPS geojson, 미터투영 point-to-line 거리+Hausdorff).

## 2026-09-17 national-otp P3 PR-A1 — 형상 파이프 + 게이트 (전량 실행)

### 확정 (실측 결정)
- **pfaedle 전량 실행 성공**: seoul-bbox-202503 정규화피드(146670 버스trip) → `-m bus -x south-korea-260903.osm.pbf` → 146670 trip 전부 shape 매칭(0 미shape). shape 3536개(패턴 dedup). 소요 ~2분(arm64 에뮬), 출력 gtfs-out(shapes.txt 81M, stop_times 840M).
- **pfaedle 이미지 digest 고정**: `ghcr.io/ad-freiburg/pfaedle@sha256:af4fdc647699ea1ffe5150fd1f9d264c465c2876710ea749e8cb284f13abd20d`(2026-09-17 pull). build_seoul_graph.sh 에 하드핀.
- **품질 게이트 지표 4축(전량 분포)**: residual_p90(p50=19.6 p99=49.2 max=126.2) / residual_max(p50=41.9 p99=161.8 max=196.7) / anchor_err(p50=22.6 p99=137 max=193.8) / detour_ratio(p50=1.1 p99=1.4 max=3.4). **핵심: 파국적 broken 형상 없음**(worst p90 126m). VPS line(96% REVIEW)과 정반대 — pfaedle base 균질하게 양호. 게이트 = thin tail 만 fallback.
- **지표 독립적**: worst-residual/worst-anchor/worst-detour trip 전부 다름. anchor>100(4483) 중 residual>50 은 94개뿐 → anchor 이상치 = 종점 setback(회차지/차고지, 정류장이 도로에서 194m 뒤)로 대체로 benign. detour<1.0 = 0건(불가능 형상 없음).
- **임계 tradeoff(전량 flag count)**: loose{p90>70,max>190,anchor>170,detour>3.0}=1.01% degraded / moderate{50,170,130,2.0}=3.04% / strict{40,140,100,1.6}=11.45%. 미shape 0이라 degradedTripIds = DEGRADED 만.

### 함정 (지표 설계)
- **monotonicity(전방 단조) 게이트는 폐기**. ①nearest-point along 은 out-and-back/루프(한국버스 대다수 기점→회차→기점)에서 복귀leg 정류장이 병행 outbound 세그먼트에 snap → 가짜 backtrack 수십km(초안 backtrack_max p50=23km, max=161km 전부 거짓). ②greedy 전방제약 snap 은 초반 1개 오snap 이 이후 전부 오염(전방제약판 residual p50 4.8km로 폭발, 90% 거짓 degraded). → **residual(무제약 nearest)=참 적합도**, 역주행/오종점은 anchor_err 가, degenerate 는 detour 가 잡음. 자기근접 형상의 낮은 residual 은 진짜 정상. compute_shape_quality.py distance() 주석에 근거.
- 게이트 = **stdlib 전용**(numpy/shapely 미사용, 미터 등거투영 LAT0=37.55 단일). venv 의존 0 → 릴리즈 파이프 재현 pin 불필요. compare_pfaedle_vps.py(telemetry, oracle 아님)만 shapely+numpy 필요.

### 좌표
- 신규: runner/build_seoul_graph.sh(오케스트레이터, --report-only 로 게이트까지만) / runner/compute_shape_quality.py(게이트, stdlib) / runner/compare_pfaedle_vps.py(VPS 대조 telemetry).
- 산출: data/otp-seoul/shape-build/gtfs-out(shaped feed) / .../coverage-metrics.csv(trip별). coverage-index.json 은 임계 확정 후 생성(--thresholds --graph-version).

### 미결 (사용자 결정 대기)
- **게이트 임계값 = 사용자 선택**(loose 추천: 1% degraded, 99% SHAPE_OK, 안전측 fallback=현행 직선). 확정 후 coverage-index.json 생성 → PR-B1(앱 마킹).
- 지하철/철도(Stage2)=STREET_FALLBACK 유지. coveredModes=["bus"].
- coverage-index trip_id = raw GTFS trip_id(BR_TAGO_..._Ord001). 앱 leg.trip.gtfsId 는 feedId prefix 붙음("feed:trip") → PR-B1 에서 strip 필요(확인).

### 검증
- pfaedle exit0, 146670/146670 매칭. `python3 -m py_compile` 3스크립트 OK, `bash -n build_seoul_graph.sh` OK. 게이트 전량 실행 46s(무제약)/재실행 확인.

## 2026-09-17 national-otp P3 PR-B1 — 앱 형상 마킹 (src/main)

### 확정 (계약)
- **SHAPE_OK 판정은 오직 coverage-index 로만**. OtpPlanConnectionMapper.toGeometry: `transit && shapesEnabled && ShapeCoverageIndex.isShapeOk(activeGraphVersion, modeToken, tripId)` → `GeometrySource.GTFS_SHAPE` + `GeometryQuality.SHAPE_OK` + points(version=graphVersion). 아니면 STREET_FALLBACK. OTP 가 shape 없어도 직선 points 주므로 points 존재로 판단 금지(계약).
- **ShapeCoverageIndex = degraded 만 담음**(게이트 미통과 ∪ 미shape). 통과 trip 은 목록에 없음 → graphVersion 일치 + mode 커버 + trip 미degraded 면 기본 SHAPE_OK. 낡거나 어긋난 인덱스 = isShapeOk 항상 false = **under-claim(전량 fallback), 오마킹 0**.
- **graphVersion 게이트**: index.graphVersion == 설정 graphVersion(OtpVersions 로 흘러온 값) 정확일치. 불일치=전량 fallback. 현재 앱 기본 graphVersion=`...-otp276-g4e098383`(배포된 shadow) ≠ 형상 그래프 `...-shaped-gad62d423c307` → 형상 그래프 배포 전엔 자동 fallback.
- **shapesEnabled 기본 false**(OtpRoutingProperties). off 면 인덱스 무관 STREET_FALLBACK = 현행 프로덕션 동작. dormant 회귀 0.
- **modeToken**: RoutingMode BUS→"bus"/SUBWAY→"subway"/RAIL→"rail"/else null. coveredModes=["bus"] 라 지하철/철도 자동 fallback(Stage1).
- **tripId feed prefix strip**: OTP leg.trip.gtfsId="1:BR_TAGO_..._Ord001", coverage-index=raw "BR_TAGO_..._Ord001". ShapeCoverageIndex.stripFeed 가 ':' 앞 제거.
- **네비 차단 로직**: anyTransitFallback = 대중교통 leg 중 quality 가 OSM_RELATION_OK/SHAPE_OK 아닌 것 존재 → "GEOMETRY_STREET_FALLBACK". SHAPE_OK 도 정상으로 인정(isAcceptableGeometry).
- **legacy 무변경**: LegacyRouteResponseMapper 손 안 댐(polyline=null 유지). 형상은 v2 경로만.

### 함정
- **3-생성자 @Autowired**: OtpPlanConnectionMapper 에 (ShapeCoverageIndex,OtpRoutingProperties)@Autowired + (ShapeCoverageIndex,boolean) + no-arg. Spring 은 @Autowired 것만 씀. no-arg=기존 5개 테스트(shapes off 기본) 무변경 보존용. [[project-fcm-live-activity-rollout]] 2생성자 함정 회피(정확히 1개만 @Autowired).
- **ShapeCoverageIndexConfig 부재 폴백**: coverage-index.json 없으면(기본 classpath:otp/coverage-index.json 미배치) disabled() → 부팅 안 깨짐. VPS 는 그래프와 함께 파일 배치 + coverageIndexPath=file:... 설정.

### 좌표
- 신규: application/route/routing/ShapeCoverageIndex.java(+isShapeOk/stripFeed), application/route/config/ShapeCoverageIndexConfig.java(@Bean 로더). enum: GeometrySource.GTFS_SHAPE, GeometryQuality.SHAPE_OK. OtpRoutingProperties.shapesEnabled/coverageIndexPath.
- OtpPlanConnectionMapper: 생성자 3개 + toGeometry(+mode,tripId,graphVersion) + toItinerary/toLeg 에 graphVersion 스레드 + isAcceptableGeometry/modeToken.

### 검증
- `./gradlew compileJava compileTestJava` OK. 계약테스트 OtpPlanConnectionMapperShapeTest 6그린(①covered=SHAPE_OK ②degraded=fallback+네비차단 ②b 전부SHAPE_OK=미차단 ③graphVersion mismatch=전량fallback ④지하철=fallback ⑤flag off=fallback). OtpShapeWiringTest 1(DB없이 빈 배선+부재폴백). otp 패키지 전량 무회귀(Error3/기존Mapper2/RideDuration/StopPattern/Adapter). RouteResponseMapperTest 5.
- **기존 무관**: NextrouteApplicationTests(@SpringBootTest) = localhost:5432 refused(Flyway, 로컬 DB 없음). 내 변경 전부터 그럼. 빈 배선은 OtpShapeWiringTest 로 DB없이 증명.

## 2026-09-17 national-otp P3 PR-B2 — 배포 원자성

### 확정 (계약)
- **ShapeMarkingStatus 로 "조용한 fallback" 을 명시화**: 기동 시 ShapeCoverageIndex.markingStatus(shapesEnabled, configGraphVersion) → DISABLED/NO_INDEX/GRAPH_VERSION_MISMATCH/ACTIVE. ACTIVE 만 SHAPE_OK 발행 가능. isShapeOk 의 graphVersion·활성 규칙을 배포 관점으로 요약(중복 아님, 로그/검증 전용).
- **ShapeCoverageIndexConfig 기동 로그**: ACTIVE=INFO(gv+modes+degraded), DISABLED=INFO, NO_INDEX/GRAPH_VERSION_MISMATCH=**WARN**(잘못된 배포 조합 즉시 감지). 런타임 fallback 은 B1 이 이미 함 — B2 는 그걸 시끄럽게.
- **releaseBundle bundleHash**(release-manifest.json): identity 5종 = graphVersion + otp.imageDigest + gtfsShapedSha256 + coverageIndexSha256 + graph.sha256 → sha256. 부분 스왑(그래프만 바꾸고 인덱스 안 바꿈) 차단.
- **verify_release_bundle.py**(stdlib): 실제 파일(graph/shaped-gtfs/coverage-index) 재해시 vs manifest + bundleHash 재계산 대조. 불일치=exit1. `--write`=빌드직후 bundleHash 기록. 파일 sha=실측, 이미지digest·graphVersion=manifest 문자열 pin.
- **스코프 밖(PLAN 명시)**: 라이브 graph 실검증(앱↔OTP 실제 그래프 질의)=P2-C 미결 별건. B2 는 manifest/config graphVersion 매칭까지만.

### 좌표
- 신규: application/route/routing/ShapeMarkingStatus.java(+isActive), ShapeCoverageIndex.markingStatus(). config/ShapeCoverageIndexConfig 리팩터(load 분리 + logMarkingStatus). runner/verify_release_bundle.py. release-manifest.json releaseBundle + otp.imageDigest. build_seoul_graph.sh step5 안내.

### 배포 (shadow, dormant 유지)
- VPS shadow swap 순서: ①번들 5개(graph.obj + coverage-index.json + shaped-gtfs + OTP 이미지 digest + app graphVersion 설정) **원자 교체** ②`verify_release_bundle.py` PASS 확인 ③app 재기동 — **shapesEnabled=false 유지**(형상 노출 0), 기동 로그 "형상 마킹 OFF" 확인 ④나중 활성화 시 shapesEnabled=true + graphVersion 을 shaped-gad62d423c307 로 갱신 → 로그 "형상 마킹 ACTIVE" 확인. 불일치면 WARN + 전량 fallback(안전).
- coverage-index.json 은 그래프와 같은 위치 배포 + `routing.otp.coverage-index-path=file:...` 설정. 기본 classpath 는 앱 jar 에 없어 NO_INDEX.

### 검증
- `compileJava compileTestJava` OK. ShapeMarkingStatusTest 4그린(DISABLED/NO_INDEX/MISMATCH/ACTIVE + isActive). OtpShapeWiringTest 1, OtpPlanConnectionMapper*Test 무회귀. verify_release_bundle: 정상 PASS(bundleHash=15957c81), 변조(graph sha 조작) → exit1 로 file sha + bundleHash 둘 다 불일치 감지 확인. `bash -n build_seoul_graph.sh` OK.

## 2026-09-17 national-otp 전환 재시도(#198 반영 후) → 재롤백. 블로커=otp-ms 3500 도 부족

### 확정
- **①재배포(dormant) 성공**: main f473beb(P2 완결+#198예산+P3 A1/B1/B2 전부 포함) → 로컬 bootJar → scp → VPS `DEPLOY_REVISION=f473beb ./deploy.sh all`. api+worker healthy. 기동로그 "형상 마킹 OFF"+"gate 설정 검증 통과 permits=4 leaseMs=8000" 확인. 토글 여전히 off라 이 단계는 회귀 0.
- **②토글(OTP_ENABLED=true+DEFAULT_PROVIDER=OTP) → 즉시 재롤백**. compose.yaml 외과 삽입(diff 2줄만) + api/worker 재생성 → 기동 정상. 그러나 **실검색 전부 502, 시청→강남(~10km) 3회 반복 전부 t=3.516~3.519s 로 완전히 균일**(otpMs=3500 예산 벽에 정확히 부딪힘 → "Request cancelled" 그대로 재현). #198 은 예산을 2000→3500 으로 올렸을 뿐, **이 노선 자체가 3.5s 도 넘긴다** — 예산을 올려도 못 따라가는 케이스가 있다는 뜻.
- **OTP 엔진 직접 호출(18080, planConnection 최소필드 start/end만) 동일좌표 = 2.62s(HTTP 200)**. 앱이 쓰는 실제 쿼리(전체 itinerary/legs/fare/geometry)는 이보다 더 무거울 수밖에 없어 3.5s 초과가 이상하지 않음. **9/16 벤치마크(p99=1.91/max=2.04s, otp:18080 직접, 경로5종×12)가 실제 프로덕션 쿼리 무게·이 노선 거리대를 대표 못 함** — 벤치마크 쿼리와 앱 실쿼리(필드수·응답크기) 간극이 근본 원인 후보.
- **즉시 롤백 완료**: compose.yaml.bak-20260917-otptoggle(dormant, 토글 시도 전) 로 복원 + api/worker 재생성. ODSAY 200 확인(3.33s, 정상). 토글 시도 상태는 compose.yaml.bak-20260917-otptoggle-attempted 로 별도 보존.

### 함정
- **예산을 올리는 접근 자체가 근본 해법이 아닐 수 있음**: 이번처럼 딱 예산치 부근에서 균일하게 잘리는 패턴(3.516~3.519s, 편차 3ms 이내)은 "가끔 느림"이 아니라 "이 쿼리는 구조적으로 이 정도 걸린다"는 신호. 무작정 otpMs 를 더 올리면(예: 5000) 이 노선은 통과하겠지만 사용자 체감 지연·타 노선 동시성(gate permit 점유시간) 이 같이 늘어남 — 트레이드오프 재계산 필요.
- **벤치마크 쿼리 ≠ 앱 실쿼리**: 9/16 실측이 어떤 GraphQL 필드셋으로 쟀는지 재검증 필요. 앱 쿼리(`OtpRoutingAdapter` 가 로드하는 .graphql 파일, `first=5`) 기준으로 노선거리별(단거리/중거리/장거리) 재벤치마크 없이 예산만 올린 게 이번 재현 실패의 직접 원인.

### 좌표
- 재현 요청: `curl "http://127.0.0.1:8080/api/route/search?startX=126.9779&startY=37.5665&endX=127.0276&endY=37.4979"` (시청→강남). otp 직접: `POST :18080/otp/routers/default/index/graphql`.
- otpMs 설정: `RouteSearchDeadlineProperties.otpMs`(기본 3500), 앱 쿼리 파일=`OtpRoutingAdapter` loadQuery() 참조 리소스.

### 미결
- **전환 전 재조사 필요**: 앱 실쿼리 필드셋으로 노선거리별(단/중/장거리 각 수개) 재벤치마크 → 실제 p99/max 재산정. 그 다음 otpMs 재조정(또는 쿼리 자체 경량화 — first 축소·불필요 필드 제거) 검토.
- VPS 상태: dormant 로 복귀됨(otp-enabled=false/default=ODSAY), 배포된 이미지는 f473beb(예산fix+P3 전부 포함, 안전하게 유지). 다음 전환 시도는 재배포 불필요 — 재벤치마크+otpMs 조정(코드 or 설정)만 하면 됨.

### 검증
- 롤백 후 실검색 curl 200(3.33s) 확인. api/worker healthy(20260917-162147-f473beb 로 그대로 유지, 이번 롤백은 compose 토글만 되돌림 — 이미지 재배포 아님).

## 2026-09-17 national-otp 전환 진짜 원인 발견+수정: JdkClientHttpRequestFactory POST body 전송 버그 (OTP 무죄)

### 확정 (근본원인 — 격리 재현으로 확정)
- **"otpMs 예산 부족"은 오진이었다. 진짜 원인 = `OtpRoutingAdapter` 가 쓰던 `JdkClientHttpRequestFactory`(JDK HttpClient 기반) 가 POST request body 를 못 보내고 멈추는 버그.** OTP 는 완전히 무죄.
- **격리 3종 테스트로 OTP/게이트/네트워크 전부 결백 증명**(otp:18080 고정 시청→강남 좌표, 앱과 동일 실쿼리 그대로):
  1. VPS host → otp:18080 직접(otp 방금 재생성한 콜드 상태 포함): 0.99~1.48s, 3회 반복 안정.
  2. 임시 컨테이너(`nextroute_default` 네트워크) → `otp:8080` 내부DNS 직접(앱과 완전 동일 경로): 0.99~1.35s.
  3. 필드 ablation(stopCalls/legGeometry/steps/fareProducts 다 제거해도): FULL 1.12s vs BARE(최소필드) 1.20s — 거의 차이 없음. "실쿼리가 무겁다" 가설 기각.
  4. Prometheus `otp_sem_acquire_total{role,result}`: 실패 3회 전부 `result=success`(즉시 획득), worker 동시호출 0건 — 게이트/동시성 무죄.
- **스모킹건 = 토글+캡처 동시진행 프로브**(`/tmp/otp_toggle_probe.sh`, 토글→api/worker 재생성→otp/api 로그+`docker stats` 0.3s 간격 캡처→curl 3회→즉시 롤백, 한 스크립트로 원자실행):
  - otp 컨테이너 로그: 3번의 실패 각각과 정확히 시각이 맞아떨어지는 `EOFException`(OTPExceptionMapper) — 스택트레이스가 `UTF8StreamJsonParser._loadMore`→`InputStreamWrapper.read`→...→`ServerInputBuffer.blockingRead`. **OTP 가 응답을 만들다가 실패한 게 아니라 REQUEST BODY 를 읽다가(blockingRead) 커넥션이 끊겨서 실패**.
  - `docker stats` 캡처: 실패 3건 내내 **otp 컨테이너 CPU = 0.13~2.11%(사실상 유휴)**. OTP 는 그 3.5초 동안 라우팅 계산을 전혀 안 했다 — 그냥 소켓에서 body 를 기다리며 블록되어 있었다.
  - 즉: 클라이언트(api)가 request body 를 다 못 보낸 채 자기 read timeout(otpMs=3500) 에 걸려 커넥션을 끊었고, OTP 는 그 순간 body read 도중 EOF 를 맞았다.
- **JdkClientHttpRequest 바이트코드 확인**(spring-web 6.2.16, javap): body 전송에 `HttpRequest.BodyPublishers.fromPublisher(Flow.Publisher[, long])` 사용 — 단순 buffered send 가 아니라 **Reactive Streams(Flow.Publisher) 기반 비동기 write 경로**. 이 경로에서 write 콜백이 제때 안 일어나면 body 가 소켓에 flush 안 되고 멈출 수 있음(정확한 트리거는 미확정이나, 증상과 정확히 부합).
- **왜 ODSAY 는 오랫동안 멀쩡했나**: `OdSayApiAdapter` 도 같은 `JdkClientHttpRequestFactory` 패턴을 쓰지만 **GET**(`client.get()`, body 없음) — 이 버그의 코드경로(request body 전송)를 아예 안 탄다. `OtpRoutingAdapter` 만 POST+body 라 유일하게 이 hot-path 에서 걸림. appleRestClient/tossRestClient 도 JdkClientHttpRequestFactory+POST 지만 저빈도(로그인/토큰 교환)라 안 걸렸거나 눈에 안 띔 — 재조사 대상 아님(스코프 밖).
- **수정**: `OtpRoutingAdapter` 의 `JdkClientHttpRequestFactory`+공유 `java.net.http.HttpClient` 필드를 제거하고, 다른 모든 어댑터(OdSay 제외 대부분)와 같은 **`SimpleClientHttpRequestFactory`(HttpURLConnection 기반, 동기 blocking)** 로 교체. connectTimeoutMs/budgetMs 그대로 매 호출 새 factory 에 설정(기존 패턴 유지).
- **수정 후 재검증(동일 프로브 스크립트로 토글→3회 호출→즉시롤백)**: **3/3 전부 HTTP 200**, t=1.968s(콜드 첫 호출)/1.339s/1.236s. 이전엔 3/3 502 at ~3.5s 였던 게 완전히 뒤집힘. **otpMs=3500 을 전혀 안 건드리고 해결** — 예산 문제가 아니었다는 최종 증거.

### 함정
- **"예산을 올리면 되겠지" 접근이 근본적으로 잘못된 방향이었다**: 9/15(2000→) #198(→3500) 두 번의 "예산 늘리기" 시도가 전부 이 버그를 못 잡았던 이유 — 애초에 시간이 오래 걸린 게 아니라 **아예 응답을 못 받는 상태**였기 때문. 심볼릭한 실패모드(같은 값 근처에서 균일하게 반복 실패)를 보면 "느려서 예산초과"와 "멈춰서 타임아웃"을 구분해야 한다 — 전자는 반복시 개선/변동 있고, 후자는 완전히 균일하다(3.516/3.517/3.519 — 오차 3ms 이내였던 게 결정적 단서였음).
- **격리 재현(python/curl 직접호출)이 "정상"으로 나온다고 앱 경유도 정상이라 단정하면 안 된다** — 클라이언트 구현체 자체(JDK HttpClient vs 다른 HTTP 클라이언트)가 다르면 완전히 다른 버그를 탈 수 있다. 이번 건 OTP·네트워크·게이트를 다 격리해서 무죄 증명한 뒤에도 실제 원인은 못 찾고, **실패 순간을 otp 서버측 로그+컨테이너 CPU 로 동시에 잡아야만** 드러났다(클라이언트 쪽 에러 메시지 "Request cancelled" 만으로는 절대 못 찾음).
- Micrometer `http_client_requests_seconds_max{client_name="otp",...}` 가 실제 클라이언트측 경과시간(3.437~3.502s)을 정확히 기록하고 있었다 — 이 프로젝트에 이미 있는 관측 스택(observability-prometheus-1, `nextroute_default`+`observability_default` 양쪽 네트워크에 물려있어 임시 컨테이너로 내부 조회 가능)이 이런 진단에 그대로 유용했다.

### 좌표
- 수정 파일(1개, S 티어): `src/main/java/watoo/grd/nextroute/infrastructure/adapter/out/api/otp/OtpRoutingAdapter.java` — import `JdkClientHttpRequestFactory`→`SimpleClientHttpRequestFactory`, 필드 `httpClient` 제거, 생성자에서 HttpClient 빌드 제거, `sendOnce()` 의 factory 생성부만 교체.
- 재현/검증 스크립트(VPS, 임시): `/tmp/otp_toggle_probe.sh`(토글+캡처+테스트+롤백 원자화) — 다음 세션도 재사용 가능. `/tmp/otp_bench.py`(OTP 직접 격리 벤치, 노선거리별+필드ablation), `/tmp/otp_internal_bench.py`(도커내부망 격리 벤치). 로컬 스크래치패드에도 원본 있음.
- Prometheus 조회 패턴: `docker run --rm --network nextroute_default curlimages/curl -s "http://observability-prometheus-1:9090/api/v1/query_range?query=..."` — 호스트에 프로메테우스 포트가 안 열려있어(9090 미노출) 이 방식으로만 조회 가능.

### 미결
- appleRestClient/tossRestClient 도 같은 `JdkClientHttpRequestFactory`+POST 조합이라 이론상 같은 버그에 노출될 수 있음(저빈도라 안 걸렸을 뿐). **이번 스코프에서는 안 건드림** — 재발하면 동일 패턴(SimpleClientHttpRequestFactory 로 교체) 적용.
- `JdkClientHttpRequest` 의 정확히 어떤 조건에서 body publisher flush 가 멈추는지(Flow.Subscriber request() 신호 지연? 스레드풀 고갈?)까지는 안 팠음 — 재현·수정만 확정, Spring/JDK 레벨 근본기전은 불명.
- 수정 후 VPS 는 다시 dormant(otp-enabled=false/default=ODSAY) 로 롤백된 상태. 배포 이미지=nextroute:20260917-170550-simpleclient. **실제 전환(토글 유지)은 아직 안 함** — 사용자 결정 대기.

### 검증
- `compileJava compileTestJava` OK. `./gradlew test --tests "*Otp*"` green(OtpRoutingAdapterHttpTest 3+OtpRoutingAdapterBudgetTest 등, MockWebServer 기반이라 factory 교체와 무관하게 그대로 통과).
- VPS 실측(수정 전 vs 후, 동일 프로브 스크립트·동일 좌표·동일 조건): **수정 전 3/3 502 (t=3.516~3.519s, otp EOFException+CPU 0%) → 수정 후 3/3 200 (t=1.968/1.339/1.236s)**. otpMs=3500 무변경.

## 2026-09-17 P4 Track B PR-1 — 검색 응답 polyline 배선(버스·도보)

### 확정 (계약)
- **polyline 채움 규칙**: 대중교통은 `leg.geometry().quality() == SHAPE_OK` 일 때만, 도보는 `RoutingMode.WALK` 면 quality 무관 항상(OTP 가 OSM 실경로를 street 출처로만 표기하므로 게이트 대상이 아님). 그 외(STREET_FALLBACK/MISSING/REVIEW)는 null. 디코드 결과 2점 미만도 null(선은 2점부터 의미).
- **EncodedPolyline(신규)**: OTP `legGeometry.points`(위도·경도 순 인코딩) → `List<CoordPoint>`(x=lon,y=lat, 기존 ODSAY 계약과 동일 축 순서 유지). fail-closed: try/catch 로 varint 파싱 예외 흡수 + 디코드 후 `|lat|>90 || |lng|>180` 범위 체크로 조용한 오염(garbage 좌표)도 차단 — 둘 다 빈 리스트.
- `SubPathResult` 생성자는 31개 위치 인자 record — polyline 은 12~17번째(trainType/payment/startId/way/wayCode/polyline) 묶음의 **마지막 자리**. distance(3번째) 근처 null 과 헷갈리지 않게 자리를 세어서 확인함(플랜이 미리 경고한 함정).

### 함정
- **B4 측정 하니스 1차 버그**: before/after 를 각각 다른 `OtpPlanConnectionMapper`(shapesEnabled false/true) 로 만들었지만 **같은(수정된) `LegacyRouteResponseMapper` 인스턴스**를 둘 다에 통과시켜서, walk leg polyline(내 변경상 shapesEnabled 무관 항상 채움)이 "before" 쪽에도 섞여 들어가 point당 바이트가 왜곡됨(같은 itinerary인데 case별 B/point 가 3.7~17.9 로 들쭉날쭉). **고침**: after 결과 하나만 실제로 매핑하고, JSON 트리에서 `polyline` 필드만 지워 "before"를 파생 — 그랬더니 B/point 가 25.9~28.0 로 수렴(일관성으로 버그 확인).
- 이 레포는 저트래픽(포트폴리오 프로젝트) — Redis 용량가드 계산 시 이 특성을 반영해야 한다(대량 트래픽 가정하면 과잉설계).

### 좌표
- 신규: `application/route/routing/EncodedPolyline.java`.
- 변경: `application/route/service/LegacyRouteResponseMapper.java` — `toPolyline(RoutingLeg)` 헬퍼 추가 + `toSubPath()` 배선(17번째 인자) + 클래스 javadoc 의 "polyline 항상 null" 문구 갱신(더 이상 사실 아님).
- 테스트(신규): `routing/EncodedPolylineTest.java`(5: 실fixture 디코드+50m내/null·빈문자열/중간절단/범위밖/1점), `service/LegacyRouteResponseMapperTest.java`(5: SHAPE_OK 채움/STREET_FALLBACK null/지하철 null/도보 항상 채움/1점미만 null).
- B4 측정 하니스는 임시 파일(`PolylineSizeScratch.java` + 리포트)로 실행 후 삭제 — 커밋 안 함(계약 테스트 아님, 결과만 이 섹션에 보존).

### 검증
- `./gradlew compileJava compileTestJava` OK.
- `./gradlew test --tests "*Otp*" --tests "*LegacyRouteResponse*"` → **87 tests, 0 failure, 0 error, 0 skipped**.
- 기존 `RouteResponseMapperTest`(5) 무회귀: `bus.polyline()).isNull()`(toLegacy 테스트, shapesEnabled=false 컨텍스트라 SHAPE_OK 자체가 안 뜸) 그대로 통과 — ODSAY/기존 계약 영향 0.
- **B4 응답 바이트 실측**(fixture=otp/planconnection-success.json·stopcall-times.json, shapesEnabled=true+버스전량SHAPE_OK 근사로 "after" 재현, JSON 트리에서 polyline 만 제거해 "before" 파생):
  - 버스단독(WALK,BUS,BUS,WALK/환승1, 84pts) : 6554B → 8788B (**+2234B**)
  - 지하철포함(WALK,BUS,WALK,SUBWAY,WALK/환승1, subway 12pt 는 Stage1 미커버라 제외한 69pts) : 4891B → 6824B (**+1933B**)
  - 버스단독무환승(단일 BUS, 16pts) : 2122B → 2537B (**+415B**)
  - success.json 전체 응답(itinerary 3개 합산) : 17107B → 24218B (**+7111B**, 전부 일관되게 **~26~28B/point** — 플랜이 경고한 "인코딩 문자열 길이로 추정 금지"와 별개로 실측 point-JSON 단가 확보).
- **Redis 용량가드 실측(VPS, 2026-09-17 23시경, 읽기전용 조회)**:
  - `redis-cli INFO memory`: used_memory=9.09MB / maxmemory=128MB(7.1%), `--scan`으로 키 분포 확인 → 전부(3663+58+21) `bus:arrival:*`/`bus:search:pos:*` 실시간 캐시고 **`route:snapshot:*` 키는 현재 0개**(최근 10분 검색 없음, 이 앱 자체가 저트래픽).
  - Prometheus(`observability-prometheus-1:9090`, `http_server_requests_seconds_count{uri="/api/route/search"}`): 48시간 SUCCESS 총합≈84건(~42/day), `max_over_time(rate(...[5m])[48h:5m])` 피크≈0.035 req/s(≈2.1/min).
  - **계산**: TTL 600s 기준 피크 시 동시 상주 스냅샷 수 ≈ 0.035×600 ≈ **21개**. 응답당(first=5 근사) 증가량 ≈ 7111B×(5/3) ≈ **11.6KB**. 예상 추가 상주량 ≈ 21×11.6KB ≈ **0.24MB** — 가드 기준 32MB 의 **0.8%**.
  - **판정: 가드 미충족 → 플랜 §B4 규칙대로 대응(스냅샷 제외/Douglas-Peucker 단순화/TTL 단축) 아무것도 안 함.** 트래픽이 수십 배 이상 커지면 재측정 필요(경고성 기록, 지금 조치 아님).

### 미결
- (플랜 §7 미결 그대로 유지: geometryQuality 노출 여부/네비 API 형상 이원화/degraded 1477건 — 이번 PR 스코프 아님)

### 배포 (2026-09-17 23:22~23:24, VPS 실적용)
- PR #205 머지(main e079237d) → 로컬 `./gradlew bootJar` → `scp build/libs/nextroute-0.0.1-SNAPSHOT.jar vps:/root/apps/nextroute/app.jar`(144,057,888B, 양쪽 사이즈 일치 확인) → `ssh vps 'DEPLOY_REVISION=e079237d ./deploy.sh all'`.
- `deploy.sh all` 이 이미지 `nextroute:20260917-232259-e079237d` 빌드 후 api+worker 둘 다 `--force-recreate` → 둘 다 `healthy`(스크립트 자체 내장 헬스체크, 실패시 자동 이전 이미지 롤백 — 이번엔 불필요).
- 마이그레이션 파일 없음(이번 PR 은 Java 클래스+테스트+문서뿐) → V__ 수동배치 대상 아님.

### 검증 (배포 후 VPS 실측)
- 기동 로그: `형상 마킹 ACTIVE: graphVersion=...shaped-gb75848ddb4ee coveredModes=[bus] degraded=1477` 유지(그래프/커버리지 무변경 확인).
- 실검색(`GET /api/route/search?...시청→강남`, 127.0.0.1:8080) HTTP 200, 1.20s, **paths=5**.
- **폴리라인 non-null 실측**(모든 path 의 모든 leg): trafficType=2(버스) pts=[169,37,66,35,18], trafficType=3(도보) pts=[26,6,26,10,31,10] — **전부 non-null, 정류장 수보다 훨씬 큼**(플랜 §6 완료판정 조건 충족). trafficType=1(지하철) leg 은 이 OD 에 없어 이번 실측엔 미포함(Track A 전까지는 나와도 null 이 맞음).
- Redis: 배포+실검색 전후 `used_memory` 9.09MB→8.85MB(사실상 무변화, bus:arrival TTL 자연변동 수준). `route:snapshot:*` 키 1개 생성 확인(내 테스트 검색 1건, TTL 600s 로 자연만료 예정) — B4 예측과 정합.
- **P4 Track B(PR-1) 완료.** 다음은 Track A(지하철 pfaedle Stage2, PR-2) — A1 임계값은 플랜에 사용자 확인 게이트가 명시돼 있어 별도 착수 필요.

## 2026-09-17 P4 Track A — A0(모드 일반화) + A1(지하철 매칭 품질 측정)

### 확정 (계약)
- **A0 스코프 경계**: `compute_shape_quality.py` 의 로딩/필터링(route_type→mode)과 분포 리포트는 완전히 일반화(`--modes bus,subway,rail` 지원, rail 도 매핑은 넣어둠 — 실제로 쓰는 건 사용자 결정). 반면 `--thresholds`/coverage-index 작성 경로는 **여전히 flat 단일 threshold dict** — mode 별 임계 구조(`{"bus":{...},"subway":{...}}`)는 A2 몫으로 명시적으로 미룸. 그 경계를 코드로 강제: `--thresholds` 와 다중 mode(`len(modes)>1`) 를 같이 주면 `parser.error`(플랜 §A1 "버스 임계 재사용 금지"를 실수로 어길 수 없게).
- **GTFS route_type 매핑 실측 확정**(routes.txt 전수): `3=bus`(2676개 노선), `1=subway`(59개), `2=rail`(2개, memory 의 "rail_2: 2" 와 일치), `4=ferry`(1개, 스코프 밖). `MODE_ROUTE_TYPES={"bus":"3","subway":"1","rail":"2"}`.
- **coverage-index counts 일반화 규칙**: `{mode}Trips` 를 요청된 mode 마다 하나씩(예: modes=["bus"] → `busTrips` 키 그대로, 기존 산출물과 동일). shapeOk/degraded/missing/unjudged 는 여전히 집계 총합(mode 분리는 A2 에서 필요해지면 추가) — 지금 A1 실행은 `--thresholds` 자체를 안 써서 이 경로가 실행되지도 않았음.
- **pfaedle 는 기존 shape 를 안 건드린다(실측 확인)**: `-m subway` 로만 재실행해도 같은 입력 feed 의 버스 shape 결과가 **완전히 동일하게 재현됨**(residual_p90 분포가 이전 실행과 숫자 하나까지 일치) — 즉 mode 별로 여러 번 나눠 돌려도 서로 덮어쓰지 않는다.

### 함정
- **pfaedle 자체 로그의 소요시간이 버그성 오버플로**: "Matched 8186 trips in 836461584.00 ms"(≈9.68일) 이라고 찍히지만 실제로는 로그 타임스탬프 기준 ~1초. pfaedle 내부 타이머 버그로 보임 — 이 숫자로 성능 판단하면 안 됨.
- **anchor_err 가설이 숫자로 확인됨**: 버스 임계(anchor_err≤170)를 지하철에 그대로 쓰면 anchor_err 단독으로 390/8186(4.8%) 이 걸리는데, 임계를 170→200 으로만 풀어도 anchor_err 원인이 0건이 됨(나머지 residual_p90 411건은 그대로) — "지하철역 좌표=역사중심, 실제 선로는 옆을 지나간다"는 플랜의 가설이 실측으로 확인됨(역사중심 vs 승강장 차이는 [[project-subway-station-radius]] 에서도 이미 문제였던 축).

### 좌표
- 변경: `runner/compute_shape_quality.py`(`MODE_ROUTE_TYPES`/`_load_trip_modes`/mode 별 리포트 루프/`--modes` 옵션), `runner/build_seoul_graph.sh`(`--mots`=pfaedle `-m` 그대로 전달, `--modes`=gate 스코프, 둘 다 기본값 `bus` 라 무옵션 재실행은 완전히 예전과 동일).
- 산출물: `docs/plan/otp-p4-polyline-delivery/handoffs/STAGE2-DISTRIBUTION.md`(분포표+임계 재사용 위험 실측+제안 임계값, 최종 확정은 사용자 결정 대기).
- 로컬 스크래치(gitignore 대상, 커밋 안 됨): `data/otp-seoul/shape-build/gtfs-out`(bus+subway shapes 둘 다 포함하게 됨, 154856 trips), `coverage-metrics.csv`(mode 컬럼 추가).

### 검증
- `python3 -m py_compile runner/compute_shape_quality.py` OK, `bash -n runner/build_seoul_graph.sh` OK.
- **A0 회귀-0 실측**: 기존 P3 Stage1 shaped feed(`data/otp-seoul/shape-build/gtfs-out`, 13:19 산출물)에 `--modes bus --thresholds config/otp-seoul/shape-gate-thresholds.json`(기존 그대로) 실행 → 출력 JSON 을 저장된 `coverage-index.json` 과 비교: **파싱 후 구조적으로 완전히 동일**(`a == b` True). 바이트 diff 는 트레일링 개행 1곳뿐(내 변경과 무관 — 이전 graphVersion 수동 편집 때 생긴 기존 흔적, 파일 재작성 전에도 있었음).
- **A1 매칭 실행**: `./runner/build_seoul_graph.sh --report-only --mots subway --modes bus,subway` → 지하철 8,186 trip 전량 매칭(누락 0, unjudged 0). 버스 쪽 숫자는 기존과 완전히 동일하게 재현(교차검증).
- **버스 임계 재사용 위험 정량화**(coverage-metrics.csv 별도 분석): 버스 임계 그대로 → 지하철 degraded 9.13%(747/8186). anchor_err 만 170→200 완화 → 5.02%(411/8186), anchor_err 원인 완전 소멸. 지하철 자체 p99 근처로 맞춘 제안 임계(`residual_p90≤130, anchor_err≤175, detour≤3.5`) → 0.68%(56/8186), 버스의 1.01% 와 비슷한 수준.

### 미결
- rail(2개 노선) 은 이번 A1 스코프에 안 넣음(`--mots subway` 만 실행) — 노선 수가 너무 적어 별도 분포 실측+판단 필요.

## 2026-09-18 P4 Track A2 — 지하철 임계 확정 + 그래프 재빌드 + VPS 배포(형상 ACTIVE, coveredModes=[bus,subway])

### 확정 (계약)
- **지하철 임계값 확정(사용자 승인)**: `residual_p90≤130, residual_max≤175, anchor_err≤175, detour_ratio∈[0,3.5]` — STAGE2-DISTRIBUTION.md 제안값 그대로. `config/otp-seoul/shape-gate-thresholds.json` 을 `{"bus": {...}, "subway": {...}}` mode-keyed 구조로 확장(버스 값은 완전히 보존).
- **compute_shape_quality.py mode-keyed threshold 적용**: `--thresholds` 가 mode 이름을 키로 가진 dict 면 요청한 `--modes` 전부가 자기 키를 가져야 함(하나라도 없으면 즉시 에러 — 다른 mode 임계가 새어들지 않음). 레거시 flat dict 는 단일 mode 에서만 허용(기존 A0 가드 유지, 이제는 "flat dict + 다중 mode" 조합만 막고 "mode-keyed + 다중 mode" 는 정상 허용).
- **coverage-index.json 최종**: `coveredModes=["bus","subway"]`, `counts={busTrips:146670, subwayTrips:8186, shapeOk:153323, degraded:1533, missing:0, unjudged:0}`(1533=1477버스+56지하철, 정확히 일치). `graphVersion` 을 그래프 sha 접두 기반으로 재명명(`...otp276-shaped-g13d196cb4979`).

### 함정
- **compose.seoul-otp.yml 의 `otp-build` 기본 `-Xmx11g` 가 그대로 있어서 재빌드가 GTFS `stop_times.txt` 읽는 도중 조용히 죽었다**(에러 로그 없이 로그만 뚝 끊김 — Bash 타임아웃이 아니라 OOM 킬로 보임). Docker Desktop VM 이 7.75GB 뿐인데 11g 를 요청하니 애초에 못 들어간다. 지난 P3 배포 때는 `docker compose run -e JAVA_TOOL_OPTIONS="-Xmx5g..."` CLI 플래그로 그 자리에서만 우회했을 뿐 파일엔 반영을 안 해서, 스크립트만 보고 재실행하면 똑같이 또 죽는 함정이 남아있었다. **고침**: compose 파일의 커밋된 기본값 자체를 `-Xmx5g` 로 바꿔서 다음부터는 아무 플래그 없이도 안전.
- 증상 재인식 포인트: 실패해도 Java/OTP 쪽 스택트레이스나 "Killed" 문구가 전혀 안 남고 로그가 그냥 특정 줄에서 멈춘다 — "에러 메시지가 없다" 자체가 OOM 킬의 신호일 수 있다(디스크/네트워크 에러라면 보통 예외가 찍힌다).
- pfaedle `-m subway` 를 이미 bus-shaped 인 입력 feed 에 또 돌려도 bus shape 는 그대로 보존된다(A1 에서 이미 확인, A2 전체 빌드에서도 버스 지표가 다시 한 번 완전히 동일하게 재현되어 재확인됨).

### 좌표
- 변경: `config/otp-seoul/shape-gate-thresholds.json`(mode-keyed 재구조화), `runner/compute_shape_quality.py`(mode-keyed threshold 로딩+적용, `--thresholds` 도움말 갱신), `compose.seoul-otp.yml`(`otp-build` Xmx11g→5g + 이유 주석), `data/otp-seoul/{coverage-index.json,release-manifest.json}`(Stage2 값으로 갱신, release-manifest 는 verify_release_bundle.py --write 로 bundleHash 재계산).
- 그래프 산출물(git 미추적, 로컬+VPS 만): `data/otp-seoul/graph.obj`(236,193,367B, sha256=13d196cb...), `data/input/seoul-bbox-202503.gtfs.zip`(bus+subway shape 포함, sha256=db280f9f...).

### 배포 (2026-09-18 00:04~00:06, VPS 실적용)
- 순서: ①로컬에서 배포본 이미지(`nextroute-otp:h-only-20260917`, ser.ver.id 276)로 그래프 재빌드 + 로컬 부팅으로 "Graph loaded" 선확인(P3 와 동일 절차) ②`/srv/nextroute/otp/<newGraphVersion>/` 새 디렉터리에 graph.obj+coverage-index.json scp, 양쪽 sha256 대조 ③`compose.yaml.bak-20260918-p4stage2` 백업 후 4곳(env 1 / coverage-index 마운트 2 / graph.obj 마운트 1) 전부 새 graphVersion 문자열로 외과 패치(`sed` 전역치환, 치환 전 개수=4 로 다른 곳에 안 섞였는지 먼저 확인) ④`docker compose config -q` PASS ⑤`up -d otp` → healthy(다운타임 19초, P3 의 21초와 비슷) ⑥`up -d api worker` → healthy.
- 앱 이미지는 손대지 않음(코드 변경 0, OTP 그래프/설정만 교체).

### 검증
- 로컬: `python3 -m py_compile compute_shape_quality.py` OK. A0 회귀 재확인(bus 만 요청 시 counts/degradedTripIds 불변, mode-keyed 파일로 바꼈어도 동일) + mode-keyed 다중 mode 실행이 정상 동작.
- 로컬 그래프 로드 증명: `nextroute-otp:h-only-20260917` 로 `--load --serve` → "Graph loaded. \|V\|=808,576 \|E\|=2,068,133" 정상(빌드 로그 자체도 ser.ver.id 276 확인) → 컨테이너 정지+삭제로 정리.
- `verify_release_bundle.py`: `--write` 로 bundleHash 기록 후 재검증 `RELEASE BUNDLE OK`.
- **VPS 기동 로그**: api/worker 둘 다 `형상 마킹 ACTIVE: graphVersion=...shaped-g13d196cb4979 coveredModes=[subway, bus] degraded=1533`.
- **실검색 지하철 polyline 실측**(숙대입구→사당4호선, 과거 fixture 와 동일 좌표): **trafficType=1(지하철) polyline_pts=84** — non-null, 정류장 수(6개)보다 훨씬 큼(플랜 §6 완료판정 조건 충족, Stage2 까지 완료). 버스(223/193pts)·도보 leg 도 여전히 정상.
- Redis `used_memory`: 배포 전후 7.72~9.09MB 범위(무의미한 변동, B4 가드와 무관한 수준 유지).
- **P4 Track A(A0+A1+A2) 완료 — 지하철 polyline 실서비스 라이브.** 플랜 §7 잔여 미결(geometryQuality 노출/네비 API 형상 이원화/버스 degraded 1477건/rail 2노선)은 전부 별건.

## 2026-09-18 journey-reroute-nextstop PR-A (OTP 정류장별 모델) — 진행중

### 확정
- **PR-0 는 이미 끝나 있었음**(2026-09-15, 문서+fixture, 코드 0). memory "새 세션 PR-0 대기"는 그 시점 기준 stale — implementation_note 재확인으로 바로잡음. 오늘은 PR-A 착수.
- **"OTP 전환 담당과 조율" 블로커 = 해소**: open PR 목록에 planConnection.graphql/JourneyCompiler 건드리는 것 없음(#59 outbox 만, 무관). `docs/plan/national-otp-map-migration/`(P2-B/P2-G 원본 phase 문서, 2026-09-07 작성)는 이미 실제로는 다른 번호체계(#182~209)로 진행되어 handoffs/PHASE-2.md 자체가 없음 — 그 문서의 "완료 판정"이 아니라 **실제 코드 재확인**(현재도 stopCalls 시각 미쿼리)으로 중복 없음 검증.
- **StopCall GraphQL 스키마 실측 확인**(`tools/transit-routing-benchmark/otp-native/app/resources/org/opentripplanner/apis/gtfs/schema.graphqls`, 이 저장소에 포함된 OTP 2.9.0(포크 베이스) 스키마): PLAN 의 쿼리 문구(`schedule{time{...on ArrivalDepartureTime{arrival departure}}}` + `realTime{arrival{time delay} departure{time delay}}`)가 **스키마와 정확히 일치**. `StopCall{realTime:CallRealTime, schedule:CallSchedule, stopLocation:CallStopLocation!}`, `CallRealTime{arrival/departure: EstimatedTime}`, `EstimatedTime{delay:Duration!, time:OffsetDateTime!}`, `CallSchedule.time: CallScheduledTime`(union `ArrivalDepartureTime|TimeWindow`).
- **StopCall 에는 GTFS stop_sequence 필드가 없다**. `stopPosition`/`stopPositionInPattern` 은 전혀 다른(legacy) `Stoptime` 타입 소속(pattern.stopTimes 계열), `Leg.stopCalls[]` 의 `StopCall` 타입엔 realTime/schedule/stopLocation 3필드뿐. → PLAN "확정 결정"의 식별 튜플 `(trip/pattern, sourceStopSequence, occurrenceIndex)` 중 **sourceStopSequence 는 planConnection 경로로 못 채움**(쿼리 자체가 불가능). `StopOccurrence.sourceStopSequence`/`tripPattern` 필드는 계속 null 유지(기존과 동일, 정직하게 비워둠 — 억지로 딴 값 채우지 않음). 실질 식별자 = leg 의 `trip.gtfsId`(이미 leg 레벨에서 쿼림) + `occurrenceIndex`(콜-로컬, 루프 재방문도 배열 위치라 유일).
- **`Leg.stopCalls[]` 는 board/alight 양끝을 포함한다**(중간정차만이 아님) — 실측 fixture 2건(PR-0 스파이크 + 기존 `planconnection-success.json`, 후자는 테스트 주석상 실제 캡처 응답) 전부 `stopCalls[0]==leg.from`, `stopCalls[last]==leg.to` (gtfsId/좌표/이름 완전 일치). 스키마의 deprecated `intermediatePlaces`/`intermediateStops`("Use leg.stopCalls instead") 주석만 보면 "중간만"처럼 읽히지만, **실측 캡처 데이터가 우선** — 이 배포(포크)에서 stopCalls 는 양끝 포함 전량.

### 함정
- **PR-0 fixture(`planconnection-stopcall-times.json`)의 `realTime.arrival.delay` 값이 틀렸다**: `60`(순수 정수)로 써뒀지만 실제 스키마 `Duration` 스칼라는 `java.time.Duration.parse()` 포맷(ISO-8601, 예 `"PT1M"`/`"PT60S"`) 문자열. `.asInt()` 아니라 `Duration.parse(text).getSeconds()` 로 파싱해야 함. 이 fixture 는 PR-0 산출물이라 지금 고치는 중(PR-A 테스트가 소비하기로 되어 있었으므로).
- **`OtpPlanConnectionMapper.toStops()` 의 `addPlace(from)`/`addPlace(to)` 가 `stopCalls[0]`/`stopCalls[last]` 와 중복**: 기존 코드는 `[addPlace(from), ...stopCalls(전체, from/to 포함)..., addPlace(to)]` 순서로 쌓아서 occurrenceIndex 0 과 1 이 물리적으로 같은 정류장(그리고 끝에서도 같은 일)이 됨. 기존 테스트는 `isNotEmpty()`만 확인해서 안 걸림(주석 `// from + stopCalls + to` 로 의도적 기술한 흔적은 있음). **시각을 addPlace 쪽엔 못 채운다**(from/to place 노드엔 schedule/realTime 없음, leg 레벨 start/end 뿐) → 이대로 두면 `stops().get(0)`(=board) 이 timing null 인 채 남아 board→X 뺄셈이 깨짐. PR-A 에서 고침: stopCalls 가 비지 않으면 addPlace 생략(stopCalls 를 그대로 신뢰), 비어있을 때만(방어적) 기존 addPlace 폴백 유지.

### 좌표
- src/main/java/.../infrastructure/adapter/out/api/otp/OtpPlanConnectionMapper.java:192 toStops, :209 addPlace, :279 instant() 헬퍼(재사용 대상)
- src/main/java/.../application/route/routing/StopOccurrence.java — 7필드, 시각 없음(추가 대상)
- src/test/java/.../OtpPlanConnectionMapperTest.java:58 — 기존 `from + stopCalls + to` 주석(중복 인지 흔적, 이번에 정정)
- src/test/resources/otp/planconnection-success.json — 실캡처 fixture, stopCalls 양끝포함 실측 근거
- tools/transit-routing-benchmark/otp-native/app/resources/org/opentripplanner/apis/gtfs/schema.graphqls:2424(StopCall) :193(ArrivalDepartureTime) :564(EstimatedTime) :91(union CallScheduledTime) — 이 저장소에 포함된 스키마 원본, 쿼리 문구 검증 근거

- **`RoutingLeg.stops()` 자체 javadoc 이 이미 "승·하차 포함"이라 적혀 있었음**(RoutingLeg.java:17) — addPlace 중복은 이 기존 계약과도 어긋나 있었던 것. 이번 수정이 문서화된 의도에 더 맞음.

### 확정 (추가 — 절단 연산 설계)
- **`TruncatedRide`(신규, `application/route/routing/TruncatedRide.java`) 는 `JourneyLegDraft`/`JourneyCompiler` 를 거치지 않는다** — PLAN 원문은 "JourneyCompiler 절단 leg 컴파일"이라 썼지만, 실제로 안 맞는다:
  - `JourneyLegDraft` 에는 geometry/polyline 필드 자체가 없다(startX/Y·endX/Y 양끝 좌표만) → "지도선 슬라이스"를 얹을 자리가 없음.
  - PR-0 함정 기록이 이미 확정: 진행중 RIDE 절단의 실제 메커니즘은 **entity 레벨 `JourneyLeg.copyToRevision` + trim 변형**(PR-4 몫)이지, Draft 를 새로 컴파일하는 게 아니다("copyToRevision 만으로는 틀린다... PR-4 필요 = copyToRevisionTruncated 변형"). Draft 컴파일 경로(`emitTransit`)는 늘 `PENDING` 새 leg 를 만드는데, 절단 RIDE 는 **이미 진행중인 leg 라 PENDING 이 아니고 progress 도 보존해야** 해서 Draft 컴파일 모델 자체가 안 맞는다.
  - 그래서 PR-A 의 "절단 연산"은 **PathResult/JourneyLegDraft 를 전혀 안 거치는 순수 함수**로 뒀다: `TruncatedRide.truncateAt(RoutingLeg, int alightOccurrenceIndex)` → `{boardAt, alightAt, rideDurationSeconds, stops(board..X)}`. PR-4 가 이 결과값을 가지고 `copyToRevisionTruncated` 류 변형에 필요한 값(endStopId/Name→X, plannedDurationSec→rideDurationSeconds, remaining*·legFraction 재계산 입력)을 채운다. **지도선은 `stops()` 좌표 자체**(별도 polyline 타입 안 만듦 — 이미 각 StopOccurrence 가 lon/lat 보유, 중복 방지).
  - X 지정은 **occurrenceIndex 로만**(sourceStopId 단독 금지 — 루프 재방문 모호). leg.stops() 안에 없으면/board 자신이면 `IllegalArgumentException`(호출자가 이미 같은 leg.stops() 에서 뽑은 값을 주는 내부 API 계약 위반이라 방어적 null 대신 즉시 실패).
  - 시각 = `bestDeparture(board)`/`bestArrival(alight)` = 실시간 우선, 없으면 시간표, **둘 다 없으면 0 으로 지어내지 않고 `rideDurationSeconds=null`**(mapper 의 "부재값 안 지어냄" 관례와 통일 — PLAN PR-1 의 "실시간·ML 둘 다 없음 → 후보 제외" 처리가 이 null 을 그대로 신호로 쓸 수 있음).
  - **버스/지하철 모드 제한을 이 함수 안에서 강제하지 않음**("초기 스코프=버스만"은 PLAN 이 호출자/정책 레이어 몫으로 둔 것으로 해석 — 이 함수는 주어진 leg 를 그대로 자를 뿐, 모드 정책은 PR-2/PR-4 후보 선별 단계 책임).

### 좌표 (추가)
- src/main/java/.../application/route/routing/TruncatedRide.java — 신규, board→X 절단 순수 함수
- src/main/java/.../application/journey/dto/JourneyLegDraft.java — geometry 필드 없음 확인(위 확정 근거)
- src/main/java/.../application/journey/service/JourneyCompiler.java:45 compile / :186 emitTransit — 절단 경로가 왜 이걸 안 타는지의 대조군으로 확인만, 변경 없음(기존 검색 컴파일 경로 그대로 보존)
- src/main/java/.../application/route/routing/RoutingLeg.java:17 — stops javadoc "승·하차 포함" 기존 계약 재확인

### 미결
- "버전드 스냅샷 리더(P2-G revision 바인딩)"(PLAN PR-A 3번째 불릿) — journey snapshot 에 아직 아무것도 안 씀(PR-A 는 검색/매퍼/절단연산 레이어까지만) → 지금 만들 대상 없음. PR-C/PR-4 가 실제로 truncated leg 를 revision 에 얹을 때 필요해짐. 캐리포워드.
- `TruncatedRide` 를 실제로 소비하는 코드(PR-C composer, PR-4 copyToRevisionTruncated)는 아직 없음 — 이번 PR 은 기반만, 배선은 후속 PR.
- `StopOccurrence.tripPattern`/`sourceStopSequence` 는 OTP StopCall 스키마 한계로 이 경로에선 영구히 null(위 확정 참고) — 나중에 누가 이 필드 채우려 하면 이 노트부터 볼 것.

### 배포
- **마이그레이션 없음**(DB 스키마 변경 0). GraphQL 쿼리 파일 + Java 코드만 — 평소 앱 재배포(빌드+배치)로 충분, VPS 수동 마이그레이션 절차 불필요.
- 신규 쿼리 필드(`stopCalls.schedule`/`stopCalls.realTime`)는 기존 배포된 VPS OTP 서버(shadow, [[project-otp-shadow-deploy]])의 GTFS GraphQL API 스키마에 이미 존재하는 필드만 사용(schema.graphqls 실측 확인) — OTP 쪽 재배포 불필요, 앱만 새 쿼리를 보내면 됨.

### 검증
- `./gradlew test --tests OtpPlanConnectionMapperTest` GREEN(3/3, mapsSuccess 기존 회귀 없음 + 신규 mapsStopCallTiming RED→GREEN 확인).
- `./gradlew test --tests TruncatedRideTest` GREEN(6/6, RED 먼저 확인: TruncatedRide 클래스 없음 컴파일에러 → 구현 후 통과).
- 관련 패키지 통합 재실행(route.routing/*, route.service.LegacyRouteResponseMapperTest, route.mapping/*, route.timeline/*, RouteSearchServiceOtpWiringTest, RemainingRouteSearchServiceOtpTest, JourneyCompilerTest, JourneyNavigationGeometryServiceTest, odsay/*) 전부 GREEN, 회귀 없음.
- `./gradlew compileJava compileTestJava` 전체 저장소 컴파일 성공(StopOccurrence 생성자 호출부 13곳 — 프로덕션 2곳 갱신, 나머지 11곳은 하위호환 7-arg 생성자로 무변경 컴파일 확인).
- `./gradlew test`(route/journey 패키지 대상 광범위 실행) 시 221개 실패 — **전부 기존 무관**: `ApplicationContext failure threshold exceeded` ← 최초 원인은 `PSQLException: Connection refused`(Flyway/Testcontainers, 이 샌드박스에 Docker/Postgres 없음). 내 변경은 Spring 컨텍스트/DB 전혀 안 건드림(순수 GraphQL 쿼리+record+매퍼+신규 pure 클래스). XML 리포트로 원인 교차검증 완료, 재조사 불필요.

### 확정 (추가 — legacy 경계 유실 처리 범위 결정, PR-A 완료 판정)
- 새 세션에서 PLAN "리뷰가 확정한 사실"(`LegacyRouteResponseMapper:152` 유실 + `RemainingRouteSearchService.loadOtp` 가 즉시 그 lossy 변환을 탐)과 SUMMARY "건드는 곳"(LegacyRouteResponseMapper 명시)을 재확인 — 지난 세션이 이 경계 자체는 안 건드렸음을 확인, 범위 판단 필요했음.
- **결론 = 지금 안 고침, PR-1/PR-C 착수 시점으로 이연**(버전드 스냅샷 리더를 이연한 것과 동일 이유). 실측: `RemainingRouteSearchService.loadOtp`(:120)는 `RoutingResult`를 받아 즉시 `legacyRouteResponseMapper.toLegacy(...).paths()`로 접어 `List<PathResult>`만 반환·나머지(RoutingResult/RoutingLeg/StopOccurrence)는 버림. 이 반환 타입은 현재 ④(`RemainingPathCandidateProvider`)가 그대로 소비 중이라 지금 넓히면 "④ 무회귀" 계약을 먼저 건드리게 됨. 반대로 PR-1(WAIT 체인)·PR-2(origin별 fresh search)가 정류장별 시각을 정확히 어떤 모양으로 필요로 할지 아직 안 정해져 있어, 지금 시그니처를 넓히면 추측 설계가 되어 나중에 다시 고칠 위험이 더 크다. 원재료(StopCall 시각)는 이미 `OtpPlanConnectionMapper`→`RoutingLeg.stops()`까지 닿아있으므로, 경계를 어떻게 넓힐지는 실제 소비자(PR-1/PR-C)가 자기 필요 모양대로 정하는 게 낫다.
- **PR-A 완료 판정**: PLAN 체크리스트 4개 중 graphql 쿼리·mapper 시각채움·절단연산(TruncatedRide) 3개 완료. "legacy 경계+버전드 스냅샷 리더"는 의도적 이연(둘 다 이유 동일=아직 소비자 없음) — PR-1/PR-C 착수 시 이 절과 위 미결절 먼저 볼 것. 남은 산출물은 fresh 브랜치로 분리해 PR 오픈.

### 검증 (추가, 새 세션 재확인)
- `./gradlew compileJava compileTestJava` 재실행 OK(무출력).
- `./gradlew test --tests "*OtpPlanConnectionMapper*" --tests "*TruncatedRide*"` 재실행 GREEN(무출력=성공, `-q`).

## 2026-09-18 journey-reroute-nextstop PR-A 정정 — OTP 는 실시간을 모른다(죽은 필드 제거)

### 확정 (앞 절 정정)
- **위 PR-A 절의 `realTime` 관련 서술 전부 ~~취소~~**: "stopCalls 의 schedule + realTime 을 받아 StopOccurrence 시각을 채운다" 는 **틀렸다**. 배포된 OTP 에는 실시간 피드가 없어서 `realTime` 은 언제나 빈 값이다. 근거(실측):
  - `build-config.json` 의 `transitFeeds` = static GTFS(`seoul-bbox-202503.gtfs.zip`) 하나뿐, GTFS-RT 항목 없음.
  - **`router-config.json` 자체가 저장소·VPS 어디에도 없다** — OTP 에서 실시간 updater 를 다는 파일이 바로 그것이다.
  - VPS `/srv/nextroute/otp/<graphVersion>/` 에는 `graph.obj` + `coverage-index.json` 뿐.
  - 사용자도 "지금 실시간 안 물려있는 걸로 안다" 로 확인.
- **역할 분리 확정**: **OTP = 정적 시간표·경로·구간 소요** / **서버 = 실시간**(버스 도착·위치 캐시 [[project-bus-realtime-cache]], 지하철 도착 API). 둘을 합치는 계산은 서버가 한다. 재탐색은 "그 정류장에 내가 도착할 시각"(서버 실시간)으로 OTP 를 부르는 구조이지, OTP 에게 "언제 도착하냐"를 묻는 구조가 아니다.
- **제거한 것**: 쿼리의 `stopCalls.realTime{...}`(내가 PR-A 에서 추가한 것) + leg 단위 `start/end.estimated{time}`·`realtimeState`(**기존부터 있었으나 어떤 Java 코드도 안 읽고 있었음** — grep 으로 확인, 매퍼는 `start.scheduledTime`/`end.scheduledTime` 만 읽고 Enrichment 는 `staticOnly(STATIC_SCHEDULE)` 하드코딩). `StopOccurrence.realtimeArrival/realtimeDeparture` 필드도 삭제 — 영원히 null 인 칸을 모델에 두면 다음 사람이 "실시간이 있나 보다" 로 읽는다.
- **`TruncatedRide` 의미 변경**: `boardAt`/`alightAt`/`rideDurationSeconds` 는 이제 **시간표 값**이다. 실시간 보정은 부르는 쪽(서버 캐시) 몫. 근거 = 지연은 운행 전체를 미루지 정류장 사이 소요를 바꾸지 않으므로, "구간 소요는 시간표, 기준 시각은 실시간" 분리가 안전하다.
- **원점 후보 규칙 변경(사용자 결정)**: 버스 실시간이 안 잡히는 정류장은 **시간표로 폴백하지 않고 후보에서 제외**한다(PLAN PR-2 해당 줄도 정정). 시간표 기반 제안은 지연 중인 실제 차와 어긋나는데, 사용자는 그 제안을 믿고 내리기 때문.

### 함정
- **fixture 가 실전에 없는 데이터를 만들어 테스트를 초록불로 만들고 있었다**: `planconnection-stopcall-times.json` 에 `realTime` 9건 + `realtimeState:"UPDATED"` + `estimated` 2건이 손으로 지어 넣어져 있었고, `TruncatedRideTest.prefersRealtimeOverScheduled` 가 그 위에서 "실시간 우선" 을 통과시키고 있었다. **피드가 없으니 프로덕션에서 절대 실행될 수 없는 경로**였다. fixture 를 지어낼 때는 "이 값이 실제 배포에서 나올 수 있는 값인가" 를 먼저 확인할 것.
- 스키마에 필드가 존재하는 것과 그 필드에 값이 오는 것은 다르다. PR-A 때 `schema.graphqls` 로 `realTime` 필드 존재를 확인한 것은 맞았지만, **그것은 "파싱된다"는 증거이지 "값이 온다"는 증거가 아니었다**. 외부 시스템 필드를 새로 받을 때는 스키마 확인 + **실제 응답 1건 확인**을 둘 다 해야 한다.

### 좌표
- 변경: `src/main/resources/otp/planConnection.graphql`(realTime·estimated·realtimeState 제거), `application/route/routing/StopOccurrence.java`(realtime 2필드 제거 + 역할분리 javadoc), `application/route/routing/TruncatedRide.java`(시간표 기반으로 명시, bestDeparture/bestArrival 제거), `infrastructure/.../otp/OtpPlanConnectionMapper.java`(realTime 파싱 제거)
- 테스트: `TruncatedRideTest`(realtime 우선 테스트 → 시간표 전용 테스트로 교체), `OtpPlanConnectionMapperTest`(realtime 단언 제거), fixture `planconnection-stopcall-times.json`(realTime/estimated/realtimeState 전부 제거)
- 문서: `docs/plan/journey-reroute-nextstop/PLAN.md`(리뷰 확정사실·PR-A·PR-2 세 곳 정정)

### 검증
- `./gradlew compileJava compileTestJava` OK(에러 0, 경고는 기존 `@MockBean` deprecation).
- `TruncatedRideTest` **6/6**, `OtpPlanConnectionMapperTest` **3/3**, `LegacyRouteResponseMapperTest` **34/34** green.
- **기존 무관 실패 1건 — `RouteResponseMapperTest.subwayNoIdCoordsKept`**: `subway.lanes()` 가 null 이길 기대하는데 `[LaneResult[name=서울4호선, ..., routeDisplayColor=#009BCE]]` 가 온다. **내 변경을 stash 하고 clean HEAD 에서 돌려도 동일하게 1건 실패** — 원인은 PR #210(routeDisplayColor)이 지하철 leg 에 lane 을 만들기 시작한 것이고, 그 PR 이 `*LegacyRouteResponseMapper*`·`*RouteSearchService*` 만 돌려서 이 클래스(`RouteResponseMapperTest`, 이름이 비슷하지만 다른 클래스)를 놓쳤다. **별건 PR 로 고칠 것**(이 PR 에 섞지 않음).

## 2026-09-18 OTP legacy routeDisplayColor — 지하철·서울 버스 고정표

### 확정
- legacy `LaneResult`에 nullable `routeDisplayColor`를 추가했다. OTP `SUBWAY`/`RAIL` leg는 `sourceRouteName`(GTFS `route.shortName`)으로 23개 도시철도 고정표를 조회해 lane 하나에 표시색을 넣는다. 표 밖 노선은 기존처럼 `lanes=null`을 유지한다.
- OTP 서울 버스는 기존 `OtpSeoulIdentityResolver`가 검증한 `busLocalBlID`를 그대로 `BusRouteRepository.findByRouteId`에 넘기고, DB `routeType` 2(마을)·3(간선)·4(지선)·5(순환)·6(광역)을 고정색으로 변환한다. 그 외 유형 및 서울 ID 미해석은 `routeDisplayColor=null`이다.
- ODSAY는 제거 예정이라 색상 해석 대상에서 제외했다. 레코드 생성자 호환을 위해 adapter에는 신규 인자로 `null`만 전달한다.

### 검증
- `./gradlew compileJava compileTestJava` → **BUILD SUCCESSFUL**.
- `./gradlew test --tests '*LegacyRouteResponseMapper*' --tests '*RouteSearchService*'` → **BUILD SUCCESSFUL**.
- `LegacyRouteResponseMapperTest`에서 실제 OTP 형식의 GTFS route/stop ID로 23개 지하철 `route.shortName` 전부와 서울 버스 5개 `routeType` 전부가 예상 hex로 연결됨을 검증했다. routeType 7(인천)은 `null` 유지도 검증했다.

## 2026-09-18 journey-reroute-nextstop PR-B stage1(확장) — RerouteOrigin + nullable origin 컬럼

### 확정
- PR-B 전체 영향범위 실측: `transferIndex`/`transfer_index` 참조 61파일(프로덕션 30+테스트 31). PLAN 의 "34파일" 추정치보다 실제가 큼 — stage3(이중 읽기/쓰기)에서 이 규모를 그대로 마주치게 될 것. **stage1(확장)은 이 중 단 하나도 안 건드림**(순수 추가라 회귀 0, 아래 검증의 근거).
- `RerouteOrigin{legIndex, tripId, sourceStopSequence, occurrenceIndex, stopGtfsId, kind}` 신설(`application/journey/dto`). stop 식별은 PR-A 가 이미 정한 관례 그대로 재사용 — tripId+occurrenceIndex 가 실질 키, sourceStopSequence 는 OTP `StopCall` 스키마 한계로 이 경로에선 보통 null(`StopOccurrence` 의 같은 경고 참고, journey-reroute-nextstop PR-A 절).
- `RerouteOriginEnvelope{version, origin}` 은 기존 `RerouteCandidateEnvelope` 패턴을 그대로 복제 — 버전 discriminator 는 별도 DB 컬럼이 아니라 **JSON 봉투 안에** 둔다(이미 `candidates` 컬럼이 이 방식으로 잘 작동 중이라 새 패턴을 안 만들고 기존 것을 재사용).
- `reroute_proposal.origin`(nullable jsonb) 컬럼을 V50 으로 추가. `transfer_index` 는 그대로 NOT NULL + 기존 부분 unique 유지 — PLAN 이 미리 경고한 대로 JPA 필드가 primitive `int` 라 지금 nullable 로 못 바꾼다(컷오버=stage5 몫).
- `RerouteProposal` 엔티티: `origin` nullable `JsonNode` 필드 + `@Builder` 생성자에 신규 파라미터로 추가. Lombok `@Builder` 는 이름 있는 세터 체인이라 기존 호출부(`RerouteProposalTxService.store()`)가 `.origin(...)` 을 안 부르면 자동 null — 하위호환 확인(컴파일+테스트로 증명, 아래).
- 신규 DTO 3개(RerouteOrigin/Kind/Envelope)는 아직 소비자가 없다(stage3 dual-write 가 처음 씀) — 전용 유닛테스트는 **의도적으로 안 만듦**: 이 저장소는 자매 타입 `RerouteCandidateEnvelope` 도 전용 유닛테스트가 없고 실제 쓰는 코드(TxService)의 IT 로만 간접 검증하는 관례라, 새 테스트 패턴을 만들지 않고 그대로 따름(쓰이기 시작하는 stage3 에서 자연히 커버됨).

### 함정
- **이 저장소에 앱 테스트를 돌리는 CI 가 아예 없다**(`.github/workflows/` 에 `ml-image.yml` 하나뿐, ML 도커 이미지 빌드용이고 `ml/**` 경로 변경에만 반응 — `./gradlew test` 를 부르는 워크플로 자체가 없음). "V18+ untracked convention 이라 CI 로 안 나감"(CLAUDE.md)의 실제 의미는 "CI 가 통과/실패로 알려주지 않는다"가 아니라 **"검증이 전적으로 로컬 개발자 실행에 의존한다"** — 그래서 VPS 수동배치 누락이 어떤 자동 게이트로도 안 걸린다.
- **마이그레이션 gitignore 가 V43 에서 끊긴다**: `.gitignore` 의 `db/migration/*` 기본무시+allowlist 가 V8~V43 까지만 있고 V44~V49 는 **전부 실제로 git 미추적**(`git ls-files` 로 실측 확인 — CLAUDE.md 표현은 "V18+" 이지만 실제 경계는 V43/V44 사이). 내 V50 은 이 경계 그대로 따름(allowlist 에 추가 안 함) — 일관성 유지.

### 좌표
- 신규: `application/journey/dto/{RerouteOrigin,RerouteOriginKind,RerouteOriginEnvelope}.java`
- 변경: `domain/journey/entity/RerouteProposal.java`(origin 필드+builder 파라미터), `test/.../domain/journey/JourneyRerouteProposalSchemaIT.java`(`@Sql` 체인에 V50 추가 — V42/V43 과 같은 파일이 대상이라 V43 추가 때와 동일 전례)
- 마이그레이션(파일시스템에만 존재, git 미추적 — 아래 배포 참고): `src/main/resources/db/migration/V50__add_reroute_proposal_origin.sql`

### 배포
- **V50 VPS 수동배치 필요**(V18+/V44+ untracked convention, 위 함정 참고). PR 머지와 VPS 배치는 분리된 두 단계 — 머지만 하고 VPS 에 파일을 안 올리면 다음 배포에서 Flyway 가 이 버전을 못 찾거나(다른 인스턴스는 이미 스키마가 있는데 마이그레이션 이력만 없는 상태) 체크섬 불일치로 깨진다.

### 검증
- `./gradlew compileJava compileTestJava` OK(무출력).
- `./gradlew test --tests "*RerouteProposalServiceTest"` → **12/12 green**(순수 mock 기반, DB 불필요 — 기존 서비스 계약 무회귀 확인).
- `JourneyRerouteProposalSchemaIT`(V50 추가한 @Sql 체인 포함)는 이 샌드박스에 Postgres/Docker 가 없어 로컬 실행 불가(PR-A 세션에서도 동일 확인된 환경 제약, 기존 무관) — V50 SQL 자체는 V42/43 과 같은 문법(`ALTER TABLE ADD COLUMN`+`COMMENT`, 제약·인덱스 없음)이라 위험은 낮으나, **실제 실행 확인은 로컬 Postgres 있는 다음 세션/개발자 몫**(위 함정대로 이 저장소엔 이를 대신해 줄 CI 가 없다).

## 2026-09-18 PR-B stage1 모델 정정 — RerouteOrigin 을 서울 좌표계로 (OTP 좌표계 아님)

### 확정 (stage1 정정)
- **~~`RerouteOrigin{legIndex, tripId, sourceStopSequence, occurrenceIndex, stopGtfsId, kind}`~~ 취소** → **`RerouteOrigin{legIndex, stopId, routeId, seq, kind}`**(서울 id). 이유 = 원점은 *진행 중인 여정*의 한 지점이고 그 세계는 전부 서울 id 로 도는데, OTP 좌표계를 씌우면 조회할 때마다 번역이 필요한 데다 그 번역이 **반만 된다**. 실측 근거 3개:
  1. **실시간 캐시 키가 서울 좌표계**: `BusRealtimeCacheReadPort.getArrivals(String stopId, String routeId, int seq)`. "실시간 없으면 후보 제외"가 확정 규칙이므로 **모든 원점은 이 키로 조회 가능해야 한다**.
  2. **`JourneyLeg` 에 `tripId` 가 없다**(routeId·vehicleId·startStopId·sourceStartStopId 는 있음). 내가 required 로 박았던 필드를 여정 데이터로 채울 출처가 아예 없었다.
  3. **여정은 정류장 목록을 저장하지 않는다** — `JourneyNavigationGeometryService` 가 `busRouteStopRepository.findStopsByRouteId(routeId)` 로 **서울 DB 에서** 읽고 `seq` 로 정렬한다. 즉 "앞으로 지날 정류장"의 canonical 출처가 OTP 가 아니라 서울 DB 다. 데이터가 서울에서 태어나는데 OTP 주소를 씌웠다가 되돌리는 왕복이 애초에 불필요했다.
- **번역이 반만 되는 지점(실측)**: `OtpSeoulIdentityResolver.resolveBus(stopGtfsId, routeGtfsId, routeName)` → `SeoulBusIdentity{stopId, routeId, routeName}` — **seq 가 없다**. 그리고 OTP `occurrenceIndex` 를 서울 seq 로 간주하는 것은 `StopOccurrence` javadoc 이 명시적으로 금지한다(서울 API 순번/sectionId 로 간주 금지).
- **세계가 둘이라는 것을 계약으로 못박음**: **OTP 좌표계(`gtfsId + occurrenceIndex`) = 검색 결과 안에서만**(PR-A `StopOccurrence`/`TruncatedRide` — 거긴 지금도 맞다) / **서울 좌표계(`stopId + routeId + seq`) = 여정·실시간·원점**. 루프 재방문 구분은 서울 `seq` 가 이미 한다(캐시 키가 stopId 와 seq 를 같이 받는 이유가 그것).
- **`isRealtimeAddressable()`(routeId·seq 둘 다 있음) = 제안 후보 자격**. 못 푸는 원점(지하철·서울 DB 미매핑)은 여기서 자연히 걸러진다 — 시간표 폴백 없음(사용자 결정). `routeId`/`seq` 는 그래서 nullable, `stopId`/`kind` 는 required.

### 함정
- **record 에 `isX()` 를 넣으면 Jackson 이 속성으로 오해해 origin jsonb 왕복이 깨진다.** 이 저장소가 `RerouteCandidateEnvelope` 에 이미 같은 주석으로 경고해 둔 함정이라 `@JsonIgnore` 를 붙였다. origin 컬럼에 들어가는 타입에 파생 메서드를 더할 때 매번 확인할 것.
- 더 큰 교훈: **모델의 주소 체계는 "그 값을 실제로 조회할 인프라의 키"에서 거꾸로 정해야 한다.** stage1 에서는 PLAN 의 식별 튜플을 그대로 따랐는데, 그 튜플은 OTP 검색 결과용으로 정해진 것이었고 원점의 소비자(실시간 캐시·여정 leg·버스 DB)를 확인하지 않았다.

### 좌표
- 변경: `application/journey/dto/RerouteOrigin.java`(필드 전면 교체 + 두 좌표계 경계 javadoc + `isRealtimeAddressable`)
- 영향 없음: `RerouteOriginKind`/`RerouteOriginEnvelope`/V50/엔티티 — **아직 아무도 origin 을 읽거나 쓰지 않아서**(stage3 가 첫 소비자) 모델 교체 비용이 사실상 0이었다. 미소비 상태에서 계약을 뒤집을 수 있는 게 expand 단계를 먼저 둔 실질 이득.
- PLAN 정정(확정 결정 "stop 식별" 의 적용 범위를 OTP 검색 결과로 축소)은 **PR-A 브랜치 몫** — PLAN.md 가 아직 #211 에만 있고 main 에 없다.

## 2026-09-18 PR-B stage3 (1/2) — 이중 쓰기/읽기 배선

### 확정
- **`RerouteOriginResolver` 신설**: `(journeyId, routeRevision, transferIndex)` → `Optional<RerouteOrigin>`. 경로 = `transfer_connection.toLegIndex` → 그 **탑승 leg 의 승차 정류장**(`JourneyLeg.startStopId`) → `bus_route_stop` 에서 seq. **못 풀면 예외가 아니라 빈 결과** — 그 제안은 옛 주소만 들고 저장되고 읽는 쪽이 폴백한다. 여기서 실패를 올리면 원점을 못 푸는 여정에서 재탐색 자체가 죽는다.
- **애매하면 seq 를 비운다**: `findByRouteIdAndStopId` 가 2건 이상이면(순환노선 재방문) 어느 순번인지 단정할 수 없으므로 null. seq 가 없으면 `isRealtimeAddressable()` false → 그 원점은 실시간 후보가 못 되고, 이것이 "애매하면 제안하지 않는다"는 확정 규칙과 같은 결론이다. **가짜 seq 를 고르지 않는다.**
- **이중 쓰기**: `RerouteProposalTxService.store()` 가 origin(있으면)과 `transfer_index`(항상) 를 함께 남긴다.
- **이중 읽기**: `toView()` 가 origin 봉투를 읽되 **모르는 version 이면 없는 것으로 친다**(후보 봉투와 같은 가드 — 계약이 바뀐 행을 새 뜻으로 읽으면 엉뚱한 정류장을 가리킨다). `RerouteProposalView.origin` 은 nullable 이고 **전환 기간에 null 인 것이 정상**이다.

### 함정
- **`JourneyLeg.routeId` 는 `selectRouteOption()` 으로만 채워진다**(빌더에 없음). 즉 노선이 아직 확정되지 않은 leg 는 routeId 가 null 이고, 그러면 seq 도 못 풀어 실시간 주소가 안 된다. 지하철 leg 도 같은 이유로 자연히 걸러진다 — 초기 스코프가 버스만인 것과 맞물려 **추가 모드 가드를 둘 필요가 없었다**.

### 좌표
- 신규: `application/journey/service/RerouteOriginResolver.java`
- 변경: `RerouteProposalTxService`(resolver 주입 + 이중쓰기 + `readOrigin()` + 뷰 배선), `dto/RerouteProposalView.java`(nullable `origin` 컴포넌트 추가 — 호출부 4곳 전부 명시적으로 갱신, 하위호환 생성자는 두지 않음: 4곳뿐이라 숨은 기본값보다 명시가 낫다)

### 검증
- `RerouteOriginResolverTest` **5/5**(정상 해석 / 순환노선 seq 비움 / 노선없음 / 환승행 없음 / stopId 없음).
- `RerouteProposalOriginReadTest` **3/3**(origin 왕복 / 옛 행 null 폴백 / 모르는 version 무시).
- 무회귀: `RerouteProposalServiceTest` 12/12, `JourneyRerouteProposalControllerTest` green.

### 미결 (stage3 2/2)
- `supersedeActive`·`stillCurrent`·조회·cooldown 키·트리거·수락을 canonical origin 키로 옮기는 부분은 아직. 지금은 전부 `transfer_index` 기준 그대로다(무회귀 유지).

## 2026-09-19 PR-B stage4 + stage3/stage5 재배치

### 확정 (PLAN 순서 변경 — 중요)
- **"supersede·stillCurrent·조회·cooldown 키·트리거·수락을 canonical origin 키로 이동"(PLAN stage3 3번째 불릿)을 stage5(컷오버)로 옮긴다.** 빼먹은 게 아니라 **지금 하면 반드시 깨지기 때문**이다.
  - 이유 = **"자리를 정의하는 것"(부분 unique 인덱스)과 "자리를 비우는 것"(`supersedeActive`)은 짝**이고, 지금 둘 다 `(journey_id, transfer_index)` 로 정확히 일치해 있다. 목표 상태는 **여정당 1개**(stage5)라 키가 다르다.
  - 지금 supersede 만 **여정 기준**으로 바꾸면 → insert 는 되지만 **아직 살아있어야 할 다른 환승의 제안까지 조기에 죽는다**(현재 스키마 IT `differentTransfersCoexist` 가 고정한 동작을 코드가 먼저 깸).
  - 지금 supersede 만 **origin 기준**으로 바꾸면 → origin 이 null 인 행(전환 기간의 정상 상태)을 못 밀어내는데 DB unique 는 여전히 transfer_index 라 **새 insert 가 unique 위반으로 튕긴다**. V42 주석이 경고한 "살아있는 제안이 하나도 없는 채로 사용자를 기다린다" 의 사촌.
  - stage5 는 순서 자체가 ①충돌 ACTIVE 정리 ②여정당 unique 생성 ③origin-only 배포 ④TTL+sweep ⑤옛 인덱스/컬럼 제거 라서 **②와 ③이 같은 배포에 들어간다** — 인덱스와 supersede 가 어긋나는 창이 없다.
  - 🔴 **이 오류가 특히 안 잡히는 조건**: 재탐색은 flag off 라 프로덕션에 티가 안 나고, 이 경로는 Postgres IT 로만 검증되는데 이 저장소엔 CI 가 없다. "일단 옮기고 stage5 에서 맞추자" 가 최악인 이유.
- **`request(...int)` 호환 shim 도 지금은 안 만든다**: origin 으로 부르는 진입점이 아직 없어 shim 할 대상이 없다(PR-2/PR-4 가 생길 때 함께).
- **stage4 완료**: `RemainingPathApply` 에 nullable `origin` 추가. **새 하위타입을 만들지 않고 record 에 칸을 덧붙이는 방식** — `RouteOptionApply` 가 동적후보 스냅샷 필드를 덧붙일 때 쓴 선례 그대로다(`@JsonSubTypes` 이름 `REMAINING_PATH` 유지). 이유: origin 은 "다른 종류의 후보"가 아니라 같은 종류의 추가 정보라 `kind` 축을 늘릴 일이 아니다. 옛 저장 JSON 은 칸이 없어 null 로 읽히고 그것이 정확히 옛 동작(leg 경계 교체)이다.
- `fromLegIndex` 로는 **중간 하차를 표현할 수 없다**는 것이 stage4 의 존재 이유: leg 를 통째로 버리는 게 아니라 중간에서 자르기 때문. 수락 경로가 어디서 자를지 알려면 후보가 그 지점을 들고 있어야 한다.

### 좌표
- 변경: `application/journey/dto/RerouteCandidateApply.java`(`RemainingPathApply` 에 `origin` + 하위호환 2-arg 생성자)
- 신규 테스트: `dto/RemainingPathApplyOriginTest.java`

### 검증
- 재탐색 유닛 스위트 전량 **63 tests / 0 failures**: RerouteOriginResolverTest 5, RerouteProposalOriginReadTest 3, RemainingPathApplyOriginTest 4, 재탐색 제안 요청 12, 재탐색 방아쇠 계측 8, 남은 구간 재검색 후보 13, 재탐색 적격성 판정 13, 재탐색 제안 API 5.
- **기존 무관**: `RerouteAcceptIT` 등 Postgres IT 는 `PSQLException: Connection to localhost:5432 refused` 로 컨텍스트 로드 실패(이 샌드박스에 DB 없음, PR-A 세션에서 확인된 것과 동일 원인). XML 로 원인 교차검증함.

### 미결 (PR-B 이후)
- stage5(컷오버) = 여정당 unique 신규 + 키 이동 + 옛 인덱스/컬럼 제거 + V번호 신규. **별도 배포 단위**라 PR-B 에 넣지 않는다.
- `origin` 을 실제로 채우는 것은 PR-2(원점 창)·PR-4(수락) 몫. 지금은 TRANSFER_WAIT 만 이중 쓰기로 채워진다.

## 2026-09-19 journey-reroute-nextstop PR-C — composer 중간 절단 합성

### 확정
- **절단의 두 입력(자를 자리·소요)은 composer 가 만들지 않는다. 부르는 쪽이 준다** — 신규 `JourneyRevisionComposer.RideTruncation{alightStationIndex, rideSeconds}`. 근거 2개(실측):
  1. **자를 자리를 composer 안에서 못 맞힌다**: 스냅샷 정류장 목록의 id 는 검색이 준 것(`LegacyRouteResponseMapper.toStations` :197-203 → `StationResult.stationID = StopOccurrence.sourceStopId()` = **OTP gtfsId**)이고, `RerouteOrigin.stopId` 는 **서울 id** 다. 여기서 둘을 맞히려 들면 PR-B stage1 정정이 경고한 "번역이 반만 되는" 자리를 하나 더 만든다. 서울 id 가 들어 있는 칸은 `startLocalStationID`/`endLocalStationID` 뿐이고 그건 양끝이라 중간 정류장엔 없다.
  2. **board→X 소요를 composer 재료로 만들 수 없다**: PR-A `TruncatedRide` 는 **검색 결과 `RoutingLeg`**(stopCall 시각 보유) 전용인데, 절단 대상은 *진행 중인 여정의 스냅샷*(`PathResult`/`SubPathResult`)이라 정류장별 시각이 애초에 없다(`SubPathResult.sectionTime` = 구간 전체, 분 단위). 게다가 하차점 근거는 **실시간만**(PLAN 확정, 시간표 폴백 금지)이라 스냅샷에서 뽑을 수 있는 값이 아니다. → **PR-C 는 `TruncatedRide` 를 쓰지 않는다**(PLAN 원문 "truncated board→X(PR-A)" 는 *개념*이 같을 뿐 그 클래스를 재사용한다는 뜻이 될 수 없다).
- **절단은 오염 가드의 예외지 해제가 아니다**: `truncation == null` 이면 기존 "구간 한가운데 거절"(snapshot-unmappable) 그대로. `truncation != null` 이면 오히려 조건이 더 좁다 — ①`origin != null` ②`kind == RIDE_INTERIOR` ③`origin.legIndex == fromLegIndex` ④실제로 구간 한가운데(=그 subPath 의 주행 leg)여야 통과. 하나라도 어긋나면 `truncation-unexpected`. 가드가 막던 사고(짝 대기 단계만 남아 다른 노선을 가리킴)는 **앞 절반을 남겨 두는 방식**으로 막는다 — 대기 단계가 여전히 타고 있는 그 노선을 가리킨다(테스트로 고정).
- **버스만 자른다**(`trafficType==2`, 아니면 `truncation-unmappable`): 하차 정류장의 서울 id 가 `endLocalStationID`(버스 칸)에 들어가므로 지하철 구간에 쓰면 주소 체계가 섞인다. PR-A `TruncatedRide` 가 모드 정책을 호출자에 위임한 것과 달리 **여기서는 강제한다** — 그 함수는 순수 계산이지만 여기는 *저장될 스냅샷의 칸*에 값을 쓰기 때문.
- **지어내지 않는 값**: 잘린 구간의 `distance`·`payment`·`endArsID`·`endID`·`endExit*` 는 **비운다**. 원래 값을 그대로 남기면 "그만큼 더 간다"는 뜻이 된다. `lanes`·`transferArrivals`·`start*` 는 승차 쪽이라 그대로 보존.
- **지도선 = 남은 정류장 좌표를 이은 선**(투영 없음, PLAN 확정 그대로). 좌표가 빠진 정류장이 하나라도 있거나 2점 미만이면 null(가짜 선 금지 — `LegacyRouteResponseMapper.toPolyline` 과 같은 규칙).
- **새 reason 어휘 `REMAINING_PATH_MID_RIDE_ACCEPTED`**(`journey_revision.reason` varchar(50), check 제약 없음 — 실측). 중간 하차는 "앞 절반을 계속 타고 간다"라 leg 경계 교체와 운영상 구별돼야 한다.
- **`Composed.truncatedLegIndex`(nullable) 신설**: 잘린 주행 단계는 **새로 만드는 단계도 그대로 복제하는 단계도 아닌 제3의 경우**(PR-0 함정 기록과 같은 결론)라, 저장 쪽(PR-4)이 알아보려면 번호가 필요하다. 기존 5-arg 생성자는 하위호환으로 남김(truncatedLegIndex=null).

### 함정
- **잘린 구간의 소요는 스냅샷에서 초 단위로 복원되지 않는다**: `SubPathResult.sectionTime` 이 분 단위라 `Math.round(rideSeconds/60)` 로 접힌다(`JourneyCompiler` 가 다시 ×60 → 최대 ±30초 오차). 지금은 `plannedDurationSec` 이 이 반올림 값이다. **PR-4 는 entity 에 `RideTruncation.rideSeconds()` 를 직접 써야 한다**(draft 값을 그대로 쓰면 30초가 조용히 틀어진다).
- **정류장 목록이 없는 옛 스냅샷은 중간 하차가 불가능**(`truncation-unmappable`) — PLAN 리스크 "전환 전 시작 여정은 정류장별 데이터 없어 중간하차 불가"가 코드에서 이 형태로 나타난다. ODSAY 스냅샷도 `stations` 가 비면 같은 결과.
- 🔴 **기존 무관 but main 이 이미 깨져 있다**: `RerouteProposalStoreIT`/`RerouteProposalExpiryIT` 가 `initializationError` — `No qualifying bean of type ...RerouteOriginResolver`. PR-B stage3(#213)이 `RerouteProposalTxService` 생성자에 resolver 를 추가했는데 두 `@DataJpaTest` 의 `@Import({...})` 목록에 안 넣어서다. **내 변경 전(stash 후) clean main 에서도 같은 실패 재현 확인.** 프로덕션은 무관(`@Component` 라 컴포넌트 스캔이 잡음) — 테스트 배선만. 별건 PR 로 `@Import` 에 `RerouteOriginResolver.class` 추가 필요.

### 좌표
- 변경: `application/journey/service/JourneyRevisionComposer.java`(4-arg `compose` + `truncationRejection`/`truncate`/`spliceTruncated`/`stopLine`, `Composed.truncatedLegIndex`, `Reason.REMAINING_PATH_MID_RIDE_ACCEPTED`, 신규 `RideTruncation`)
- 변경: `test/.../JourneyRevisionComposerTest.java`(중간 절단 8케이스 추가), `test/.../SubPathFixture.java`(`payment` 세터 — 절단이 요금을 비우는지 검증하려면 원본에 값이 있어야 함)
- 소비자 없음(무회귀): `RerouteAcceptTxService:116` 은 3-arg 그대로 → 지금 프로덕션 동작 변화 0. 배선은 PR-4.

### 배포
- **마이그레이션 없음**. 코드만. 단 `journey_revision.reason` 에 새 값이 들어가므로 운영 대시보드/쿼리가 reason 을 열거하고 있으면 `REMAINING_PATH_MID_RIDE_ACCEPTED` 추가 필요(제약은 없어서 DB 는 안 깨진다).

### 검증
- `./gradlew test --tests "*JourneyRevisionComposerTest*"` → **16/16 green**(기존 7 + 신규 9).
- **뮤테이션 확인**(테스트가 실제로 무는지): `truncate` 의 소요·거리를 원본 그대로 두도록 일부러 바꾸니 `truncatesRideAtMidStop`·`truncatedSnapshotRecompilesToSameLegs` 2건 FAILED → 되돌림.
- `./gradlew test --tests "watoo.grd.nextroute.application.journey.service.*" --tests "watoo.grd.nextroute.application.route.*"` → **907 tests / 0 failures**(XML 집계).
- 기존 무관 2건: 위 함정의 `RerouteProposalStoreIT`/`RerouteProposalExpiryIT`(clean main 에서도 동일 실패, stash 로 교차검증). `RerouteAcceptIT` 등 Postgres IT 는 이 샌드박스에 DB 가 없어 여전히 실행 불가 — PLAN 의 "⑤ 무회귀(RerouteAcceptIT)"는 **로컬 Postgres 있는 세션에서 확인 필요**(단, accept 경로는 3-arg 호출 그대로라 코드상 변화 없음).

### 미결
- PR-4 가 채울 것: `alightStationIndex` 해석(서울 origin → 스냅샷 정류장 위치), `rideSeconds` 실시간 산출, `truncatedLegIndex` 특수분기(copy+trim, progress 보존), 초 단위 `plannedDurationSec`.
- `RerouteAcceptTxService.saveRouteOptions` 는 `legIndex < fromLegIndex` 로 복제/신규를 가른다 — 잘린 주행 단계는 `== fromLegIndex` 라 신규 쪽으로 떨어진다. 주행 단계는 노선 후보를 안 가져서(후보는 대기 단계 소유) 지금은 무해하지만 PR-4 가 확인할 것.

## 2026-09-19 PR-C 후속 — PR-B stage3 가 깨뜨린 두 IT 복구 (같은 PR, 별도 커밋)

### 확정
- `RerouteProposalStoreIT`·`RerouteProposalExpiryIT` 가 `@DataJpaTest` 라 **컴포넌트 스캔이 없다** — `@Import` 에 적힌 빈만 산다. PR-B stage3 가 `RerouteProposalTxService` 생성자에 `RerouteOriginResolver` 를 추가했는데 두 `@Import` 목록을 안 늘려 `initializationError`(No qualifying bean). 프로덕션은 `@Component` 스캔이라 무관.
- **빈만 넣으면 그다음에 또 깨진다**: 두 IT 의 `@Sql` 체인이 V43 에서 끝나는데 `RerouteProposal` 엔티티는 이제 `origin` 컬럼을 매핑한다 → **V50 도 같이 넣어야** 한다(`JourneyRerouteProposalSchemaIT` 는 stage1 에서 이미 넣어둔 것과 같은 조치). 한 증상을 고치면 바로 다음 증상이 나오는 짝이라 **둘을 한 커밋으로** 처리.

### 함정 (앞선 기록 정정)
- **~~"이 샌드박스엔 Postgres/Docker 가 없어 Postgres IT 로컬 실행 불가"~~ 취소(2026-09-19 실측)**: 지금은 붙는다. 같은 명령이 실제로 초록불로 돌았다(아래 검증 — Store 13, Expiry 4, Accept 10, Schema 5). PR-A/PR-B 세션의 "기존 무관: Connection refused" 라벨은 **그 시점의 환경 상태**였을 뿐이고, 그 라벨을 관례로 굳혀 Postgres IT 를 안 돌리면 이번 같은 배선 파손이 계속 안 잡힌다. **매 세션 한 번은 실제로 돌려 확인할 것.**

### 좌표
- 변경: `test/.../application/journey/RerouteProposalStoreIT.java`(`@Import` 에 `RerouteOriginResolver` + `@Sql` 에 V50), `test/.../application/journey/RerouteProposalExpiryIT.java`(동일)

### 검증
- `./gradlew test --tests "*RerouteProposalStoreIT*" --tests "*RerouteProposalExpiryIT*"` → **13/13, 4/4 green**(수정 전 둘 다 initializationError).
- `./gradlew test --tests "*RerouteAcceptIT*" --tests "*JourneyRerouteProposalSchemaIT*"` → **10/10, 5/5 green** — PLAN PR-C 의 "⑤ 무회귀(RerouteAcceptIT)" 를 실제 Postgres 로 충족(이연 아님).
## 2026-09-19 journey-reroute-nextstop PR-1 — overlay 체인 + 표준 rank()

### 확정
- **체인을 `RemainingPathEtaComposer` 안에 두지 않고 신규 `BoardingChainEtaCalculator` 로 뺐다.** 이유 = PLAN 이 요구한 "baseline 도 같은 체인"을 지키려면 **저장된 여정(`JourneyLeg`)과 방금 검색한 후보(`JourneyLegDraft`)가 같은 계산을 타야** 하는데, composer 는 초안만 안다. 계산기는 중립 `Step{legIndex, type, plannedDurationSec, BoardingQuery}` 만 받고, 두 호출자가 각자 자기 모양을 옮긴다(`RouteOptionEtaCalculator` 가 엔티티/초안을 `Segment` 로 접는 기존 선례와 같은 방식).
- **R1("이후 대기 0") 폐기 범위 = ④ 경로만**. `RouteOptionEtaCalculator` 는 그대로 둔다 — ③(`RouteOptionCandidateProvider`)이 아직 쓰고 있고 PR-5 에서 통째로 사라진다. 지금 건드리면 폐기 예정 코드의 회귀를 떠안는다.
- **실패 사유를 첫 대기와 그 뒤로 가른다**: 첫 대기 미해석 = `no-prediction`(옛 이름 유지), 두 번째 이후 = **`boarding-unresolved`**(신규 등재). 한 이름으로 묶으면 "체인을 켜서 제외가 얼마나 늘었는지"를 지표에서 못 읽는다.
- **신뢰도 = 체인에서 가장 나쁜 고리**(`Result.quality`). 예전 `hasFurtherTransfer` 기반 낮춤은 폐기 — "뒤에 대기가 남아 있다"가 더 이상 근거가 아니다(이제 그 대기도 예측한다). 대신 두 번째 차가 ML 추정이면 목적지 시각도 그만큼만 믿는다.
- **`walkingSec` 의미 확장**: 예전에는 *승차 이후* 도보만 셌는데, 체인은 **경로 전체**(정류장까지 가는 접근 도보 포함)를 센다. 근거 = 그 값이 화면에도 나가고 `rank()` 의 3순위 키이기도 한데, 접근 도보를 빼면 "멀리 걸어가서 타는 후보"가 실제보다 편해 보인다. 기존 테스트의 240 → 420 변경이 이 결정의 흔적.
- **표준 `RerouteRanking`**(정적 유틸): `ORDER = 목적지 ETA ↑ → 환승수 ↑ → 도보 ↑ → 여유 ↓`. **개선폭(gain)으로 정렬하지 않는 이유** = gain 은 기준선이 있을 때만 존재하는 값이라, 기준선을 못 잰 요청(지금 차를 못 타는 사람 = 가장 재탐색이 필요한 사람)에서는 정렬 규칙이 사라져 자리마다 다른 순서가 나온다. 기준선이 고정된 요청 안에서는 "ETA 이른 순 = gain 큰 순"이라 옛 정렬과 결과가 같고 **동점 처리만 달라진다**. PR-2 의 "정류장당 시간표 rank 상위 1개"는 같은 비교기의 `Scheduled` 변형을 쓴다.
- **경로 간 overlay 병렬**(신규 `rerouteOverlayExecutor`, size 4/queue 8): 후보 하나가 이제 대기 수만큼 예측을 부르므로 순차로 돌리면 예산이 앞의 한둘에서 소진돼 **검색이 준 순서가 곧 당선 순서**가 된다. 체인 *안*은 앞 승차가 다음 앵커라 순차일 수밖에 없다. 풀 포화(`RejectedExecutionException`)면 **부르는 스레드에서 실행** — 후보를 잃느니 느린 편이 낫고 예산 가드가 전체 대기를 여전히 막는다.
- **예산 처리 변경**: 예전에는 "예산 끝 → 남은 후보는 시작도 안 함"이었는데, 병렬에서는 이미 나간 호출의 결과를 버리는 셈이라 **기다리기만 멈춘다**(끝난 것은 쓰고, 안 끝난 것만 `budget-exhausted`). 체인은 대기마다 `min(perCall, 남은 시간)` 으로 호출한다(`Budget{chainBudgetMs, perCallBudgetMs}`).

### 함정
- **기준선이 이제 예측을 여러 번 부른다**: 남은 환승 수만큼. 기준선은 예전처럼 후보 예산 밖이지만(없으면 개선폭 자체를 못 잼), 총 상한을 `remaining-path.evaluationBudgetMs` 로 걸어 뒀다. **요청 하나의 최악 대기 = 검색 + 기준선 체인 + 후보 체인(병렬)** 으로 늘었다 — 라이브에서 p95 를 볼 것.
- **기준선이 더 늦어져 개선폭이 커 보인다(정상)**: 옛 기준선은 이후 대기를 0으로 접어 **낙관적**이었다. 체인이 그 대기를 살리면 기준선 ETA 가 뒤로 밀리고 후보의 gain 이 늘어난다. 테스트 `baselineOverlaysEveryRemainingWait` 가 이 값을 1620초로 고정해 둠 — "gain 이 갑자기 커졌다"는 관측은 버그가 아니라 이 변경이다. 반대로 후보 쪽도 같은 규칙이라 구조가 다른 후보끼리는 이제 공정하다.
- 🔴 **기존 무관**: `JourneyTransferAssessmentIT.assessmentIsPersisted` 가 `Expecting actual not to be null` 로 실패한다. **clean main(stash 후)에서도 동일 재현** — 내 변경과 무관. 별건으로 조사 필요(재탐색과 무관한 환승 판정 저장 경로).

### 좌표
- 신규: `application/journey/service/BoardingChainEtaCalculator.java`, `application/journey/service/RerouteRanking.java`, `application/journey/config/RerouteOverlayExecutorConfig.java`
- 변경: `RemainingPathEtaComposer`(체인 사용, `walkSecondsBefore`·estimator 조회 삭제, `Attempt.eta` 타입 교체), `RemainingPathCandidateProvider`(기준선 체인 + `storedSteps` + 병렬 overlay + `RerouteRanking` 정렬 + `Scored implements Ranked`), `JourneyRerouteMetrics`(`boarding-unresolved` 등재)
- 무변경(의도): `RouteOptionEtaCalculator`(③ 전용, PR-5 에서 제거)

### 배포
- **마이그레이션 없음**. 새 설정 키 `journey.reroute.overlay-pool.{size,queue}`(기본 4/8) — 미설정이면 기본값이라 배포 파일 수정 불필요.
- 재탐색은 여전히 flag off 라 프로덕션 영향 0. 켤 때 `boarding-unresolved` 비율을 먼저 볼 것(체인 때문에 제외가 늘 수 있다 — PLAN 리스크 "overlay A 제외율 폭주").

### 검증
- `BoardingChainEtaCalculatorTest` **9/9**(체인 앵커·2번째 미해석 제외·첫 대기 no-prediction·unboardable·worst quality·missing-duration·예산 0·앞 구간 건너뜀·걷기 전용).
- `RerouteRankingTest` **6/6**(ETA 우선·환승수·도보·여유 동점·시간표 변형·빈 목록).
- `RemainingPathEtaComposerTest` **15/15**(신규 2: 두 대기 모두 질의·두 번째 미해석 제외), `RemainingPathCandidateProviderTest` **15/15**(신규 2: 기준선 체인 앵커·rank 동점 정렬).
- **뮤테이션 확인**: 두 번째 대기부터 앵커를 안 밀도록(옛 R1) 바꾸니 3개 FAILED(체인·composer·provider 각 층에서 하나씩) → 되돌림.
- 전체 `application.journey.*` + `application.route.*` → **1266 tests, 1 failed** = 위 함정의 `JourneyTransferAssessmentIT`(clean main 동일 실패, 기존 무관). 나머지 전부 green. `NextrouteApplicationTests`(전체 컨텍스트 로드) green — 신규 executor 빈 배선 확인.
## 2026-09-19 journey-reroute-nextstop PR-B stage5 1/2 — 여정당 unique + 키 이동

### 확정
- **stage5 를 두 PR 로 쪼갰다**(PLAN 은 한 덩이로 적혀 있었음). 1/2 = **V51 여정당 unique + 키 이동**(옛 인덱스·컬럼 유지), 2/2 = TTL·sweep 여유 뒤 **옛 인덱스/컬럼 제거**. 이유 = PLAN 자체가 "③origin-only 배포 → ④TTL+sweep 여유 → ⑤옛 것 제거"로 **배포를 두 번** 요구한다. 한 PR 에 넣으면 그 사이의 대기 창을 코드로 표현할 수 없다.
- **옛 `uq_reroute_proposal_active(journey_id, transfer_index)` 를 1/2 에서 안 지운다.** 새 인덱스가 **더 좁으므로**(여정당 1 ⊂ 환승당 1) 공존해도 새 삽입을 막지 않고, 롤백하면 옛 코드가 그대로 돈다. 이 순서 덕에 "인덱스만 좁히거나 코드만 좁혀서 깨지는" PLAN 의 경고 상황이 안 생긴다.
- **자리를 비우는 규칙과 자리를 정의하는 인덱스는 같은 배포다**: `supersedeActive(journeyId, transferIndex, …)` → `supersedeActive(journeyId, …)` 와 V51 unique 가 한 PR. 이것이 PLAN 이 stage3 에서 키 이동을 여기로 미룬 이유 그대로다.
- **cooldown 키에서 transferIndex 를 뺐다**(`journey:reroute:cooldown:{scope}:{journeyId}:{revision}`). 살아 있는 제안이 여정당 하나면 잠금도 여정 단위여야 한다 — 아니면 연타 한 번이 자리를 계속 갈아치운다. **마이그레이션 불필요**: 옛 키는 TTL(기본 1분)로 저절로 사라진다. `Scope`(TRIGGER/REQUEST) 분리는 그대로 유지(D6).
- **`stillCurrent` 의 "지나갔는가" 기준을 원점의 `legIndex` 로 옮겼다.** 원점을 못 푼 여정만 옛 환승 행으로 폴백한다(전환 기간 정상 상태, 2/2 에서 폴백도 제거). 지금은 `RerouteOriginResolver` 가 transfer_connection 을 거쳐 origin 을 만들므로 **둘의 값이 항상 같다** — 실제로 갈리는 것은 대응하는 환승 행이 아예 없는 **RIDE_INTERIOR 원점**(PR-2·PR-4)이다. 그래서 지금 바꿔 두지 않으면 그때 이 확인이 통째로 막는다.
- **수락 경로는 키 이동 대상이 아니었다**(PLAN 목록에는 있었음): `acceptIfActive(id, …)` 가 이미 proposalId 기준이고, `RerouteAcceptTxService` 의 transferIndex 참조는 전부 **transfer_connection**(다른 표) 쪽이다. 조회도 `findActive(journeyId)` 로 이미 여정 단위였다 — 실제로 옮길 것은 supersede·cooldown·stillCurrent 셋.
- **죽은 쿼리 제거**: `findByJourneyIdAndTransferIndexAndStatusAndDeletedAtIsNull` 은 프로덕션에서 아무도 안 불렀다(선언만 남아 있었음).

### 함정
- **sweep IT 가 같은 여정에 ACTIVE 를 여러 건 심고 있었다** → 여정당 unique 에 걸려 실패. 여정을 나눠 심자 이번엔 `uq_journey_active_per_user`(사용자당 ACTIVE 여정 1개)에 걸렸다 — **사용자까지 새로 만들어야** 한다. 배치 sweep 은 여정을 가리지 않으므로 이 수정이 테스트 의도를 바꾸지 않는다.
- **StoreIT `@BeforeEach` 가 `journey_leg` 를 안 비웠다**: 원점 해석이 그 표를 읽게 되면서(이 PR 에서 처음 journey_leg 행을 넣는 테스트가 생김) `delete from journey_session` 이 FK 위반으로 터진다. 지우는 순서에 journey_leg 를 추가.
- **IT 에는 `bus_route_stop` 표가 없다**(`journey-it-schema.sql` 은 `users` 만 만든다). 원점 해석이 거기까지 가면 IT 가 깨지는데, `seqOf` 가 `routeId == null` 에서 먼저 끊어 안 간다 — 그래서 테스트용 leg 는 **노선 미선택** 상태로 넣는다. 이 우연에 기대고 있다는 것을 알아 둘 것.
- **이 PR 은 #214(PR-C)의 IT 복구 커밋을 cherry-pick 해 담았다**(`@Import` 에 `RerouteOriginResolver` + `@Sql` 에 V50). 그것 없이는 `RerouteProposalStoreIT`/`ExpiryIT` 가 컨텍스트 로드 단계에서 죽어 stage5 를 **검증할 방법이 없다**. #214 가 먼저 머지되면 같은 내용이라 충돌 없이 흡수된다.

### 좌표
- 신규 마이그레이션: `db/migration/V51__reroute_proposal_journey_active_unique.sql`(정리 UPDATE + 부분 unique). **git 미추적**(V44+ convention)
- 변경: `RerouteProposalRepository`(supersedeActive 여정 단위, 죽은 쿼리 삭제), `RerouteProposalTxService`(origin 먼저 해석 → stillCurrent(context, origin)), `RerouteCooldownPort`·`RedisRerouteCooldownAdapter`(키에서 transferIndex 제거), `WindowCooldownEligibilityPolicy`·`RerouteTriggerEvaluator`(호출부)
- 테스트: `JourneyRerouteProposalSchemaIT`(환승당→여정당 계약 뒤집기 + 인덱스 목록 + V51), `RerouteProposalStoreIT`(여정 단위 supersede·원점 기준 지나감 확인 + V51 + journey_leg 정리), `RerouteProposalExpiryIT`(sweep fixture 여정·사용자 분리 + V51), `RedisRerouteCooldownAdapterTest`(키 문자열), 그 밖 cooldown 호출부 4곳

### 배포
- **V51 VPS 수동배치 필요**(V44+ untracked convention). 🔴 **선행 조건: 아직 VPS 는 V49 다** — V50(origin 컬럼)이 안 올라가 있다. prod `ddl-auto: validate` 라 V50 없이 현재 main 을 올리면 부팅이 실패한다. **배포 순서 = V50 · V51 을 담은 jar 를 이 작업트리에서 `./gradlew bootJar` → 한 번에 적용**(둘 다 이 트리에만 있는 파일).
- 인덱스 생성은 `CREATE UNIQUE INDEX`(CONCURRENTLY 아님) — reroute_proposal 은 재탐색 flag off 라 사실상 빈 표이므로 잠금 시간이 무시할 수준이다. 표가 커진 뒤에 돌릴 계획이면 CONCURRENTLY 로 바꿔야 한다.
- 2/2(옛 인덱스·컬럼 제거)는 이 배포가 라이브에서 TTL(5분)+sweep 을 넘긴 뒤 별도 PR·별도 배포.

### 검증
- `JourneyRerouteProposalSchemaIT` **6/6**(계약 뒤집기 포함), `RerouteProposalStoreIT` **15/15**(신규 2), `RerouteProposalExpiryIT` **4/4**.
- **뮤테이션 2건**: ①supersede 를 `transfer_index = 99` 로 좁히자 StoreIT 2건 FAILED(자리를 못 비워 unique 위반) ②cooldown 키에 `:0` 을 되돌려 붙이자 adapter 테스트 4건 FAILED. 되돌림.
- `application.journey.*` + `adapter.out.cache.*` → **1026 tests, 1 failed** = `JourneyTransferAssessmentIT`(clean main 동일 실패, 기존 무관 — PR-1 절 참고).

## 2026-09-19 PR-B stage5 2/2 — 옛 주소(transfer_index) 제거

### 확정
- **V52 = 주소 없는 ACTIVE 접기 → 옛 부분 unique 삭제 → 컬럼 삭제** 순. 1단계를 먼저 하는 이유: 컬럼이 사라지면 origin 이 null 인 ACTIVE 행은 "어디에 대한 제안인지"를 영영 못 말한다(사유 `cutover-no-origin` 으로 접는다, 지우지는 않는다 — 늦은 수락을 404/409 로 갈라야 해서).
- **저장 시 origin 필수**(`RerouteProposalTxService.store`): 못 풀면 `metrics.recordRejected("origin-unresolved")` + 빈 결과. 주소 없는 제안을 만들지 않는 것이 이 단계의 실질 계약이다. 지표 사유 신규 등재.
- **`stillCurrent` 의 옛 환승 폴백 제거** — 이제 origin 이 non-null 이라 분기가 아예 없다.
- **응답 DTO 계약 변경**: `ProposalBody.transferIndex` → `origin`(RerouteOrigin 그대로 내보냄. legIndex·stopId·routeId·seq·kind). 🔴 **모바일 계약 변경**이지만 재탐색은 flag off 라 라이브 영향 0.
- **`RerouteContext.transferIndex` 와 요청 DTO(`CreateRerouteProposalRequest.transferIndex`)는 남겼다.** PLAN 2/2 항목에는 "걷는다"고 적혀 있었지만 실제로 보니 **그 값은 저장 키가 아니라 입력**이다 — ③·④ 공급자와 `RerouteOriginResolver` 가 전부 그것으로 transfer_connection 행을 찾는다. 서버가 스스로 원점을 고르는 것은 PR-2(원점 창)의 일이라, 여기서 걷으면 대체물 없이 부수는 셈.

### 함정
- **테스트가 이 컬럼을 8개 파일에서 손으로 넣고 있었다**(reroute_proposal raw insert): RerouteAcceptIT·SubwayBindingRerouteInheritanceIT·JourneyNotificationCancelOnEndIT·WithdrawalDeletionBoundaryIT·SchemaIT·StoreIT·ExpiryIT. 컬럼 목록만 지우면 `INSERT has more expressions than target columns` 로 죽는다 — **VALUES 의 리터럴 0 도 함께** 지워야 한다.
- **store() 가 origin 을 요구하자 ExpiryIT·StoreIT 의 대부분이 죽었다**: 두 IT 는 transfer_connection 만 심고 journey_leg 는 안 심었는데, 원점 해석이 그 leg 에서 정류장을 읽는다. 각 setUp 에 **탑승 leg(노선 미선택)** 을 추가. 노선을 채우면 `seqOf` 가 `bus_route_stop` 을 읽는데 IT 스키마에 그 표가 없다(stage5 1/2 절의 같은 함정).
- 🔴 **Flyway 체크섬 불일치로 IT 가 통째로 죽는다**: V52 를 한 번 돌린 뒤 파일을 고치자 `Migration checksum mismatch for migration version 52` 로 컨텍스트 로드가 실패했다. **로컬 개발 DB(`nextroute-postgres-1`)에 실제로 적용돼 있기 때문** — 이 저장소의 Postgres IT 는 그 DB 를 쓴다. 복구 = `update flyway_schema_history set checksum = <새 값> where version='52'`(flyway repair 와 같은 일). **마이그레이션 파일은 한 번 돌린 뒤 고치지 말 것.**
- **V52 의 `COMMENT` 를 두 줄 문자열로 나눠 쓰면 Postgres 가 거절한다**(인접 리터럴 연결 불가). 한 줄로 쓴다.

### 좌표
- 신규: `db/migration/V52__drop_reroute_proposal_transfer_index.sql`(git 미추적)
- 변경: `RerouteProposal`(필드·빌더 파라미터 삭제), `RerouteProposalView`(컴포넌트 삭제), `RerouteProposalResponse`(`transferIndex`→`origin`), `RerouteProposalTxService`(origin 필수 + 폴백 삭제), `JourneyRerouteMetrics`(`origin-unresolved`)
- 테스트: 위 8개 IT + `RerouteProposalOriginReadTest`·`RerouteProposalServiceTest`·`JourneyRerouteProposalControllerTest`(뷰 생성자)

### 배포
- **V52 VPS 수동배치**. 🔴 **1/2(V51)가 라이브에서 TTL(5분)+sweep 을 넘긴 뒤에 올린다** — 그 전에 올리면 아직 origin 이 없는 ACTIVE 행이 `cutover-no-origin` 으로 접히며 사용자가 보던 제안이 사라진다. VPS 가 V49 인 지금은 V50·V51·V52 가 한 번에 적용되므로 **빈 표에 도는 것과 같아 안전**하지만, 그 뒤 전략을 바꿔 단계 배포를 한다면 이 순서를 지킬 것.
- 🔴 **모바일**: 제안 응답의 `transferIndex` 가 `origin` 으로 바뀐다. 재탐색 flag off 라 지금은 무영향이지만 켜기 전에 전달 필요.

### 검증
- `JourneyRerouteProposalSchemaIT` **6/6**(여정당 하나 / 접히면 자리 비움 / **transfer_index 컬럼 없음+origin 있음** / 옛 인덱스 없음 / check 제약 / kind·status).
- `RerouteProposalStoreIT` **16/16**(신규 `원점을 못 풀면 저장하지 않는다`), `RerouteProposalExpiryIT` **4/4**, `RerouteAcceptIT` **10/10**.
- **뮤테이션**: origin 이 없을 때 가짜 원점으로 저장하게 바꾸자 신규 테스트 FAILED → 되돌림.
- `application.journey.*` + `application.auth.*` + `adapter.in.api.journey.*` → **1075 tests, 1 failed** = `JourneyTransferAssessmentIT`(clean main 동일, 기존 무관).

### 검증 (추가 — 통합 커버리지 구멍 메움, 사용자 질문 계기)

- **구멍 실측**: 이 저장소의 재탐색 통합 테스트는 *슬라이스·컴포넌트* 위주고 **여정 관통 시나리오가 없다**. 특히 두 곳이 비어 있었다.
  1. **API 주소 계약 미검증**: `JourneyRerouteProposalControllerTest` 가 `candidates[*]` 만 단언해 `transferIndex`→`origin` 교체를 아무 테스트도 안 잡았다(필드를 지웠는데 초록불).
  2. **여정당 1개가 흐름에서 미검증**: `RerouteRemainingPathIT` 의 계획 경로는 **환승 1개**라, 앞 제안이 뒤 제안에 자리를 내주는 장면 자체가 나오지 않았다.
- **메운 것 (A)**: 컨트롤러 테스트에 `$.proposal.origin.{legIndex,stopId,kind}` 단언 + `transferIndex`·`realtimeAddressable` **부재** 단언. 뮤테이션(origin 을 null 로 내보냄) → FAILED 확인.
- **메운 것 (B2)**: `RerouteRemainingPathIT` 에 **버스3·환승2 여정**을 시작하는 케이스 추가 — 환승 0 제안 → 환승 1 제안 요청 시 ①ACTIVE 는 1건 ②앞 행이 `replaced` ③origin 의 legIndex 가 바뀜. 뮤테이션(supersede 를 `1=0` 으로 무력화) → FAILED 확인.
- **IT 함정**: 한 사용자에 ACTIVE 여정은 하나라, 두 번째 여정을 시작하려면 앞 여정을 먼저 접어야 한다. 상태 어휘는 `ACTIVE|COMPLETED|CANCELED|EXPIRED` — `ENDED` 는 check 제약 위반이다(`journey_session_status_check`).
- 재실행: `application.journey.*` + 컨트롤러 → **968 tests, 1 failed**(기존 무관 `JourneyTransferAssessmentIT`).

### 미결 (통합 커버리지)
- **제안→수락 관통 IT 없음**: 제안 생성(실 provider)과 수락(`RerouteAcceptIT`, 제안을 손으로 심음)이 각자 논다. 둘을 잇는 시나리오는 별건.

## 2026-09-19 머지 정리 — PR-C·PR-1·stage5 전부 main

### 배포
- 머지 완료: **#214(PR-C) → #215(PR-1) → #216(stage5 1/2) → #219(stage5 2/2, 아래 함정)**. main = `a7dcbe6b`. 합쳐진 main 에서 컴파일 + 재탐색 전량 테스트 green 확인.
- **VPS 는 아직 V49**. 배포 시 V50·V51·V52 가 한 번에 적용된다(그 표는 flag off 라 사실상 비어 있어 순서 위험 없음). 세 파일 모두 git 미추적이라 **이 작업트리에서 `./gradlew bootJar`** 해야 jar 에 실린다.
- 🔴 **모바일 전달**: 제안 응답의 `transferIndex` → `origin`(legIndex·stopId·routeId·seq·kind).

### 함정
- 🔴 **`git add -A` 한 번이 추적 대상 아닌 3,066개 파일을 main 에 올렸다**(`.artifacts/` 의 16MB tar.gz 포함, 다른 작업의 계획 문서·도구 스크립트). 머지 충돌을 풀며 쓴 한 줄이었다. 되돌림 = #218(인덱스에서만 제거, 작업 트리 파일은 보존) + `.gitignore` 에 `.artifacts/`·`.tmp/` 추가. **커밋 이력의 blob 은 남아 저장소 용량은 안 줄었다** — 지우려면 filter-repo + force push 라 공유 브랜치 판단이 필요해 하지 않았다. **충돌 해결 때도 `git add <path>` 로 파일을 지목할 것**([[feedback-staged-contamination]] 이 경고하던 사고의 변종).
- **base 브랜치를 지우면 그 위에 쌓은 PR 이 닫힌다**: #216 을 `--delete-branch` 로 머지하자 base 가 사라진 #217(stage5 2/2)이 **CLOSED** 되고 재오픈도 거부됐다(`Could not open the pull request`). 같은 브랜치로 #219 를 새로 열어 머지. **스택 PR 은 아래 PR 을 머지하기 전에 위 PR 의 base 를 main 으로 먼저 바꿀 것**(닫힌 뒤에는 base 변경도 막힌다).
- **append-only 노트는 머지마다 충돌한다**(양쪽이 파일 끝에 덧붙이므로). 해결은 언제나 "양쪽 다 살리기" — 이번에 2회.

### 미결
- **개발 DB 를 쓰는 `@SpringBootTest` IT 가 브랜치 간 스키마 오염에 노출된다**: stage5 마이그레이션이 로컬 개발 DB 에 적용된 뒤, 옛 엔티티를 가진 브랜치(PR-1 등)에서 `Schema-validation: missing column [transfer_index]` 로 컨텍스트가 통째로 죽었다. 이 때문에 PR-1 의 여정 관통 테스트(B1)를 **작성했다가 폐기**(실행 증거를 못 만듦). Testcontainers 로 이관(같은 저장소의 `AbstractJourneyOutboxPostgresIT` 가 선례)하거나 전용 테스트 DB 로 가르는 것이 근본 해법 — **별건**.
- **여정 관통 테스트 재시도**: 이제 main 에 체인(PR-1)과 새 스키마(stage5)가 함께 있으므로 B1 을 main 기준으로 다시 쓰면 그냥 돈다(버스3·환승2 여정에서 후보의 두 번째 승차까지 물어보는지).

## 2026-09-19 VPS 배포 — V50·V51·V52 라이브

### 배포
- **완료. VPS 스키마 v49 → v52, api·worker 둘 다 healthy.** 이미지 `nextroute:20260919-142058-9a73dd4b`(이전 = `20260918-145151-manual`, `.env` 의 `PREVIOUS_*` 에 보존 → `./deploy.sh rollback-api|rollback-worker` 로 되돌릴 수 있다).
- 절차(실측): ①이 작업트리에서 `./gradlew bootJar` ②`unzip -l` 로 **V50·V51·V52 가 jar 안에 있는지 확인**(총 52개) ③VPS `app.jar` 를 `app.jar.bak-20260919` 로 백업 ④scp 로 교체 ⑤`DEPLOY_REVISION=9a73dd4b ./deploy.sh all`.
- **마이그레이션은 jar 안에 실려 간다**(`BOOT-INF/classes/db/migration/`). VPS 에 .sql 을 따로 올리는 절차가 아니다 — "V18+ 수동배치"의 실제 의미는 **"그 파일을 가진 트리에서 빌드해야 한다"**. 다른 클론에서 빌드하면 파일이 없어 조용히 빠진다.
- Flyway 로그 실측: `Current version 49` → `Migrating to 50 → 51 → 52` → `Successfully applied 3 migrations, now at version v52`. 배포 후 api·worker 에러 로그 **0건**.
- 스키마 확인: `reroute_proposal` 에 `origin` 만 있고 **`transfer_index` 없음**, 인덱스 = `uq_reroute_proposal_journey_active`(옛 `uq_reroute_proposal_active` 사라짐) + `ix_reroute_proposal_journey`/`ix_reroute_proposal_expiry`/pkey.
- `deploy.sh all` 은 build → api·worker 재생성 → **health gate(각 180s)** → 실패 시 이전 이미지로 자동 복구. 이번엔 둘 다 healthy 로 통과.

### 함정
- **VPS 에서 psql 을 ssh 한 줄로 부르면 따옴표가 세 겹**(로컬 zsh → ssh → docker exec sh -lc → psql)이라 `'reroute_proposal'` 같은 리터럴이 식별자로 해석돼 `column "reroute_proposal" does not exist` 가 난다. **SQL 을 파일로 써서 `scp` + `docker cp` 후 `psql -f`** 로 부르는 편이 확실하다.

### 검증
- `flyway_schema_history` 상위 4행 = 52·51·50·49 전부 `success=true`.
- `docker ps`: `nextroute-api-1`·`nextroute-worker-1` 둘 다 `(healthy)`.
- 재탐색은 여전히 flag off — 이 배포로 사용자에게 보이는 변화는 없다(스키마·코드만 준비된 상태).

## 2026-09-19 journey-reroute-nextstop PR-2 — 원점 창 + 정류장 축 목록

### 확정
- **PLAN 의 "계획 하차점 fresh search 안 함"을 좁게 읽었다**: 다시 검색하지 않는 것은 **기준선**(저장된 계획을 overlay 해서 잰다)이고, 계획 하차 **정류장 자체는 원점으로 남긴다**. 근거 = 그 자리를 빼면 ④(환승 정류장 발 재검색)와 ③(같은 정류장 다른 노선)이 **둘 다** 사라지는데, ③은 폐기가 아니라 이 작업이 **흡수**하기로 한 기능이다. 스펙(2026-09-11) 의 창 정의도 "…~ 원래 계획 하차 정류장까지"라 정류장을 포함한다.
- **계획 하차 always 는 resolver 가 아니라 공급자가 든다.** `UpcomingStopWindowResolver` 는 **중간 정류장만** 돌려준다 — 그 자리는 이미 환승 대기 원점(`waitLeg.startStopId` + `userReadyAt`)으로 공급자에 있고, 두 곳에서 만들면 같은 정류장이 목록에 두 번 나온다.
- **내 위치 = 앱이 보낸 `remainingStops`**(`journey_leg.remaining_stops`, 관측 180초 이내). 하차 순번에서 그만큼 뺀 값이 지금 지난 정류장이다. 그 값이 없거나 낡으면 **창을 아예 열지 않는다**(= 계획 하차점 하나로 도는 옛 동작). 지나간 정류장을 앞으로 올 정류장으로 착각하면 제안대로 못 내린다.
- **내 차 고르기 = 도착 정보의 `sectionOrder`**. 서버는 사용자가 탄 차량을 모른다(버스엔 지하철 같은 열차 바인딩이 없다). 그래서 정류장마다 오는 한두 대 중 `sectionOrder ∈ {currentSeq, currentSeq+1}` 인 차를 내 차로 본다. 한 번 잡으면 그 뒤 정류장부터는 **같은 `vehicleId` 만** 받는다(구간 순번은 밀리지만 차량 번호는 안 밀린다).
  - **비대칭은 의도**: 내 **앞**의 차를 잘못 잡으면 도착이 이르게 나와 자리가 버려지거나 조금 이른 시각을 말할 뿐이다. 내 **뒤**의 차를 잡으면 도착이 늦게 나와 "아직 여유 있다"가 되고 사용자가 내릴 곳을 지나친다. 그래서 `currentSeq` 미만은 절대 안 받는다.
- **도착 시각 = `dataTimestamp` + `sectionTime`**(`RealtimeArrivalCalculator`·`SingleTransferPredictor` 와 같은 규칙). `predictTime`(exps) 이 아니다 — 이 저장소는 `sectionTime1/2`(traTime)를 도착 소요로 쓰기로 이미 정해져 있다.
- **정류장당 후보 하나**. overlay 전에 **시간표 소요**로 줄을 세워(`RerouteRanking.Scheduled`) 그 자리의 최선 하나만 예측을 태운다. 화면의 축이 하차 정류장이라 어차피 한 줄만 나가고, overlay 는 대기 수만큼 예측을 부른다.
- **응답은 중첩하지 않는다.** 정류장당 후보가 하나뿐이라 후보마다 `display.alight{stopId,name,alightAt,planned}` 를 달고 앱이 그것으로 구역을 나눈다. 그룹 자료구조를 만들면 껍데기만 한 겹 는다.
- **제안 자체의 origin(저장 키)은 그대로 TRANSFER_WAIT** 이다(`RerouteProposalTxService` 가 `context.transferIndex` 로 푸는 값). 한 제안이 **여러 자리의 후보**를 담으므로 제안 단위 origin 은 "어느 환승을 구하려는 제안인가"로 남고, "어디서 내리는가"는 **후보의** `apply.origin` 이 든다. stage5 의 여정당 unique·stillCurrent 는 그대로 맞는다.
- **`apply.origin` 은 중간 하차 후보에만 실린다.** 계획 하차점 후보는 null 이고 그것이 그대로 옛 뜻(leg 경계부터 교체)이라 composer·수락 경로가 안 바뀐다. 중간 후보의 `fromLegIndex` 는 **주행 중인 leg** 이고 `origin.legIndex` 와 같다 — PR-C 가드(`origin.legIndex() == apply.fromLegIndex()`)가 요구하는 값이다.
- **원점끼리 검색을 병렬로 돌린다**(`rerouteOverlayExecutor` 재사용, size 4). 자리 하나가 검색 한 번(최대 3s)이라 순차로 돌리면 여섯 자리에서 분 단위가 된다. 풀 포화면 부르는 스레드에서 실행(자리를 잃느니 느린 편이 낫다).
- **지금 타고 있는 노선도 `same-as-current` 로 거른다.** 예전에는 갈아타려던 노선만 봤는데, 중간에 내려 **같은 버스를 다시 타는** 후보가 생길 수 있다.

### 함정
- **④의 후보 수 계약이 바뀐다**: 예전에는 한 정류장에서 최대 `max-paths`(4) 개가 후보로 나왔는데, 이제 **정류장당 1개**다. 창이 안 열리는 상황(위치 모름·실시간 없음·지하철 주행)에서는 후보가 **하나**만 나온다. 기존 테스트 3개가 이 전제를 못박고 있어 함께 고쳤다(`trimsToMaxPaths`·`withoutBaselineOnlyEarliestSurvives`·`ordersByTheStandardRanking`).
- **`max-paths` 의 뜻이 옮겨갔다**: "평가(overlay)할 경로 수"가 아니라 "**쪼개서 줄 세울** 경로 수"다. 유료 overlay 는 그중 하나에만 나간다.
- **원점 여섯 자리 = OTP 검색 여섯 번**(병렬). `OtpConcurrencyGate` 는 검색과 합산이라 재탐색 한 건이 사용자 검색 슬롯을 밀어낼 수 있다. 포화면 그 자리만 빈손이 되고 제안은 계속 나가지만, **라이브에서 gate 포화율을 보고 `max-origins` 를 조일 것**(미결).
- **`remainingStops` 는 앱이 보낸 값이다**(서버가 재판정하지 않는 계약, `JourneyProgressCommand`). 앱이 그 값을 안 보내면 이 기능 전체가 조용히 옛 동작으로 떨어진다 — 지표 `journey.reroute.origin.window{outcome=no-progress}` 가 그것을 말한다.
- **ODSAY provider 에서는 미래 출발이 먹지 않는다**(API 에 출발 시각 칸 없음). 중간 정류장 후보의 시각 근거가 약해지므로, 이 기능을 켜는 것은 provider=OTP 전제다.

### 좌표
- 신규: `application/journey/dto/RerouteOriginStop.java`, `application/journey/service/UpcomingStopWindowResolver.java`
- 변경: `RemainingRouteSearchService`(`search(..., departAt)` 오버로드 + OTP 질의 출발시각), `RemainingPathCandidateProvider`(원점 목록·자리별 검색 병렬·시간표 상위 1·miss-risk·`apply.origin`·`display.alight`), `RerouteCandidateDisplay`(`AlightStop` 추가 + 옛 생성자 유지), `JourneyRerouteProperties`(`NextStop` 블록 + validate), `JourneyRerouteMetrics`(`journey.reroute.origin.window` + 사유 `miss-risk`)
- 테스트: `UpcomingStopWindowResolverTest`(신규 10), `RemainingPathCandidateProviderTest`(15→20), `RemainingRouteSearchServiceTest`(5→7)

### 배포
- **마이그레이션 없음.** 새 설정 키는 전부 기본값이 있어 배포 파일 수정 불필요.
- 재탐색은 여전히 flag off → 프로덕션 영향 0. 켤 때 볼 지표 = `journey.reroute.origin.window{outcome}`(특히 `no-realtime`·`no-progress` 비율 = 이 기능의 커버리지)와 `candidate.filtered{reason=miss-risk}`.

### 검증
- `UpcomingStopWindowResolverTest` **10/10**(창·리드타임·계획하차 제외·실시간 없음 제외·뒤차 거부·낡은 관측·`remainingStops` 없음·지하철·PENDING·상한·off).
- `RemainingPathCandidateProviderTest` **20/20**(신규 5: 중간 정류장 원점+도달시각 검색·계획 하차점 옛 모양·miss-risk·주행 노선 거르기·정류장당 overlay 1).
- `RemainingRouteSearchServiceTest` **7/7**(신규 2: departAt 전달·지난 출발은 현재로).
- **뮤테이션 2건**: ①내 차 고르기를 `sectionOrder <= currentSeq+1` 로 넓히자 뒤차 테스트 FAILED ②시간표 상위 1 대신 검색 순서 첫 번째를 고르자 overlay 테스트 FAILED. 둘 다 되돌림.
- 전체 `application.journey.*` → **1007 tests, 1 failed** = `JourneyTransferAssessmentIT`(clean main 동일 실패, 기존 무관). `application.route.*` + `adapter.in.api.journey.*` 전부 green.
- **Postgres IT 실제 실행**: `RerouteAcceptIT` 10·`RerouteProposalStoreIT` 16·`RerouteProposalExpiryIT` 4·`JourneyRerouteProposalSchemaIT` 5 → 전부 green(저장 JSON 에 새 칸이 늘어도 왕복이 깨지지 않는지 확인).

### 미결
- **원점 수 ↔ OTP gate**: `max-origins=6` 이 라이브에서 검색 슬롯을 얼마나 먹는지 미실측. 포화율 보고 조정.
- **차량 식별**: `sectionOrder` 매칭은 근사다. `getBusPosByRtid` 로 노선 전체 위치를 한 번 받아 내 차를 먼저 확정하는 편이 정확하지만 호출이 하나 더 는다 — 커버리지 지표를 보고 판단.
- **PR-3(알림)이 붙기 전까지 이 목록은 pull 로만 보인다.** 트리거·validate·응답 3갈래는 다음 PR.

## 2026-09-19 journey-reroute-nextstop PR-3 — 알림 계약(서버가 먼저 알린다)

### 확정
- **계약이 뒤집혔다**: "제안은 사용자 요청만"(D1) → **"알림은 서버가 먼저, 경로 변경은 사용자 선택만. 자동 적용 없음."** `RerouteTriggerEvaluator` 의 "협력자가 없으니 실수로 켜질 수도 없다"는 구조적 보장은 **의도적으로 해제**했고, 대신 스위치 `journey.reroute.alert.enabled`(기본 꺼짐)가 그 자리를 맡는다.
- **발행은 방아쇠가 아니라 신규 `RerouteAlertPublisher` 가 한다.** 방아쇠의 일은 "찾아볼 만한가"까지고, "깨울 만한가"(문턱·리드타임·상한·채널)는 다른 판단이다. 한 클래스에 두면 shadow 측정 코드와 사용자를 깨우는 코드가 같은 자리에서 자란다.
- **판단 순서가 계약**: 스위치 → 여정 상태(종료·무응답 상한) → cooldown → 후보 생성 → 개선폭 문턱 → 리드타임 → 채널. **cooldown 이 후보 생성 앞**인 이유 = 어차피 안 보낼 알림 때문에 OTP·실시간·ML 을 부를 이유가 없다. **채널 0이면 저장도 안 한다** — 아무도 못 볼 제안이 여정당 하나뿐인 자리를 5분 막는다.
- **알림 TTL(5분)과 요청 TTL(2분)을 가른다**(`alert.snapshot-ttl`). 누른 사람은 화면을 보고 있지만 알림은 주머니 속 진동에서 시작한다.
- **cooldown 을 scope 별로 가른다**(`Scope.ALERT`, 5분). 방아쇠·요청 잠금은 **외부 호출**을 아끼고 알림 잠금은 **사용자의 주머니**를 아낀다 — 한 값이면 관측 주기를 조이는 일이 알림 피로를 바꾼다.
- **무응답은 Redis 가 아니라 표다**(`journey_session.reroute_ignored_count`). "여정당 2회" 상한은 TTL 로 사라지면 뜻을 잃는다. 세는 자리 셋 = 명시적 무시 · lazy 만료 · **배치 sweep(본류 — 앱을 안 열면 lazy 전이가 한 번도 안 온다)**.
- **`reroute_proposal.alerted` 를 둔 이유**: 사용자가 직접 요청한 제안에는 무응답이라는 개념이 없다(그 화면은 이미 보고 있었다). 이 칸이 없으면 버튼을 누른 사용자가 스스로 알림 상한을 깎는다. 표시는 **저장 시점이 아니라 알림 행이 실제로 생긴 뒤**(채널 0건이면 안 찍는다).
- **sweep 을 두 문장으로 갈랐다**(`lockDueBatch` → `expireByIds`). 한 문장으로 접으면 어느 여정이 몇 건이었는지 되돌려 받을 수 없다. `FOR UPDATE SKIP LOCKED` 로 잡은 채 같은 트랜잭션에서 접으므로 두 문장의 집합은 같다.
- **거절과 무시는 다른 상태다**(`REJECTED` / `DISMISSED`, V54 의 CHECK). `SUPERSEDED` 로 뭉치면 "새 제안이 밀어냈다"와 "사용자가 싫다고 했다"가 구별되지 않는데, 둘은 다음 알림 여부에 정반대로 작용한다.
- **멱등은 한 문장이 만든다**(`closeIfActive` 는 ACTIVE 일 때만 0/1행). 재전송이면 여정 상태를 **건드리지 않는다** — 무시를 두 번 세면 재전송 한 번이 상한을 통째로 먹는다. 응답은 그래도 200 + `closed:false`.
- **응답에 여정 알림 상태를 함께 싣는다**(`rerouteTerminated`·`rerouteAlertOptIn`). 규칙(수락 1회·거절+미체크 종료·무시 상한)을 앱이 다시 계산하면 두 벌이 되고, 어긋나는 날 사용자에게 거짓말을 한다.
- **수락도 알림을 끝낸다**(`RerouteAcceptTxService` 에서 `terminateReroute()`). 고른 길로 막 바꿨는데 또 바꾸라고 알리면 남은 이득보다 피로가 크다 — 스펙의 "1회 한정".
- **마이그레이션을 표마다 갈랐다**(V53 journey_session / V54 reroute_proposal). 한 파일로 두면 journey 표만 만드는 @Sql 체인(알림·텔레메트리 IT 9곳)이 `ALTER TABLE reroute_proposal` 에서 통째로 막힌다.
- **PLAN 의 "전멸 시 1회 재계산"은 넣지 않았다.** 조회 경로에 재검색을 넣으면 화면 열기가 OTP 호출이 되고, 그 자리에서 transferIndex 를 되찾으려면 원점을 역추적하는 코드가 하나 더 생긴다. 다시 찾기는 다음 방아쇠나 사용자의 "다시 찾아줘"가 한다(미결).

### 함정
- 🔴 **V53 을 한 번 돌린 뒤 쪼갰다 — 로컬 개발 DB 를 손으로 되돌려야 했다.** 알림 IT 들이 먼저 실패하는 바람에 쪼개기로 결정했는데, 그때 이미 Flyway 가 V53 을 dev DB(`nextroute-postgres-1`)에 적용한 뒤였다. 복구 = `delete from flyway_schema_history where version='53'` + 그 파일이 만든 컬럼·제약을 손으로 되돌리기(그래야 다음 부팅이 V53·V54 를 새로 적용한다). **"마이그레이션은 한 번 돌린 뒤 고치지 말 것"이 이번에도 맞았다** — 파일을 쪼갤지는 IT 를 돌리기 **전에** 정했어야 했다.
- **엔티티에 컬럼을 더하면 그 표를 만드는 @Sql 체인이 전부 깨진다**: `journey_session` 에 3칸을 더하자 알림·텔레메트리·스키마 IT 112건이 `column "reroute_alert_opt_in" ... does not exist` 로 죽었다. 재탐색과 무관한 체인들이라 "@Sql 3곳"이라는 기존 메모가 이번엔 틀렸다 — **journey 체인 9곳 + reroute 체인 3곳 = 12곳**.
- **`JourneyCheckpoint` 값을 늘리면 두 곳이 컴파일·테스트로 잡는다**: `FcmMessageFactory` 의 switch 3개(messageKey·title·body)와 "어휘가 셋"을 못박은 `JourneyTargetApproachEnqueueIT`. 후자는 개수가 아니라 **"서버가 보낼 수 없는 종류를 만들지 않는다"**(N1 하차·N3 정류장 접근)를 지키는 시험이라, 넷째를 더하며 의도를 주석으로 남겼다.
- **`FcmMessageFactoryTest` 가 messageKey 접두어를 `journey.transfer.` 로 못박고 있었다** — 재탐색 알림(`journey.reroute.suggested`)이 생기며 공통 접두어(`journey.`)로 넓혔다.
- **알림의 `scopeId` 를 0 으로 두면 안 된다**: 논리 키가 `journeyId:rev:JOURNEY:0:REROUTE_SUGGESTED` 라 같은 여정의 **두 번째 제안이 ON CONFLICT 에 삼켜진다**. proposalId 를 넣어 제안마다 다른 키가 되게 했다.
- ⚠ **`int scopeId` 에 proposalId(bigint)를 캐스팅해 넣는다.** 제안 번호가 21억을 넘으면 키가 겹친다 — 지금 규모에선 안 오지만 `journey_notification.scope_id` 의 타입을 넓히는 것이 근본 해법(미결).

### 좌표
- 신규 마이그레이션: `V53__add_reroute_alert_state.sql`(journey_session 3칸), `V54__add_reroute_proposal_alerted.sql`(alerted + status CHECK). **git 미추적**(V18+ convention)
- 신규: `application/journey/service/RerouteAlertPublisher.java`, `RerouteAlertTxService.java`, `application/journey/dto/RerouteDecision.java`, `infrastructure/adapter/in/api/journey/dto/{RejectRerouteProposalRequest,RerouteProposalDecisionResponse}.java`
- 변경: `JourneySession`(3필드+`terminateReroute`·`optInRerouteAlert`·`recordRerouteIgnored`·`isRerouteAlertAllowed`), `RerouteProposal`(`alerted`+`reject`·`dismiss`), `RerouteProposalStatus`(+REJECTED·DISMISSED), `JourneyCheckpoint`(+REROUTE_SUGGESTED), `RerouteProposalRepository`(`lockDueBatch`·`expireByIds`·`countUnansweredDue`·`closeIfActive`·`markAlerted`), `JourneySessionRepository`(`bumpRerouteIgnored`), `RerouteProposalTxService`(무응답 집계·`close`·`expireValidatedOut`), `RerouteProposalService`(`collect`/`store` 분리·`validate`·`decide`), `RerouteProposalExpiryService`(2문장 sweep + 무응답), `RerouteTriggerEvaluator`(발행 연결), `RerouteAcceptTxService`(수락=알림 종료), `JourneyNotificationEnqueuer`(`enqueueRerouteSuggested`), `FcmMessageFactory`(문구 3), `RedisRerouteCooldownAdapter`(scope 별 TTL), `RerouteCooldownPort`(+ALERT), `JourneyRerouteProperties`(`Alert` 블록+validate), `JourneyRerouteMetrics`(`journey.reroute.alert`), 컨트롤러(+reject·dismiss)
- 테스트: `RerouteAlertPublisherTest`(신규 11), `RerouteProposalServiceTest`(12→17), `RerouteProposalStoreIT`(16→23), `RerouteProposalExpiryIT`(4→6), `JourneyRerouteProposalSchemaIT`(5→7), `JourneyRerouteProposalControllerTest`(6→9), `RerouteAcceptIT`(수락=종료 단언 추가), @Sql 체인 12곳

### 배포
- 🔴 **V53·V54 VPS 수동배치 필요**(V18+ untracked convention). VPS 는 현재 **v52** — 이 트리에서 `./gradlew bootJar` 해야 jar 안에 두 파일이 실린다.
- prod 는 `ddl-auto: validate` 이고 `JourneySession` 이 새 3칸을, `RerouteProposal` 이 `alerted` 를 매핑한다 → **V53·V54 없이 올리면 앱이 안 뜬다**.
- 새 설정 키 `journey.reroute.alert.*` 는 전부 기본값이 있고 **`enabled=false`** 라 compose 수정 불필요. 재탐색 자체도 여전히 flag off → 프로덕션 동작 변화 0.
- 켤 때 순서: `journey.reroute.enabled` → `alert.enabled`. 볼 지표 = `journey.reroute.alert{outcome}`(`published` 대비 `below-threshold`·`no-lead-time`·`no-channel`·`cooldown` 비율), `unanswered`·`rejected`·`dismissed`·`opt-in`.

### 검증
- `RerouteAlertPublisherTest` **11/11**(발행·스위치 off·종료 여정·상한·opt-in 이 상한 무시·cooldown 이 검색 전 차단·문턱·리드타임·채널 0·중복·예외 삼킴).
- `RerouteProposalServiceTest` **17/17**(신규 5: 지나간 하차점 제거·전멸 시 제안 접기·옛 후보 통과·꺼져도 거절 접수·소유권).
- `RerouteProposalStoreIT` **23/23**(신규 7: 거절·opt-in·무시·멱등·여정 불일치·무응답 집계·요청 제안은 무응답 아님), `RerouteProposalExpiryIT` **6/6**(신규 2: sweep 무응답), `JourneyRerouteProposalSchemaIT` **7/7**(신규 2: 상태 어휘·알림 칸), `JourneyRerouteProposalControllerTest` **9/9**(신규 3).
- **뮤테이션 2건**: ①`isRerouteAlertAllowed` 를 항상 true 로 → publisher 상한 테스트 FAILED ②`close` 의 "이미 닫힘" 분기 삭제 → StoreIT 멱등 FAILED. 둘 다 되돌림.
- 전체 `application.journey.*` + `infrastructure.*` + `domain.journey.*` → **1493 tests, 1 failed** = `JourneyTransferAssessmentIT`(clean main 동일 실패, **기존 무관**). `application.route.*` + `NextrouteApplicationTests`(전체 컨텍스트 로드 — 신규 빈 배선 확인) green.

### 미결
- **전멸 시 재계산**(PLAN 항목): 조회 경로가 OTP 를 부르게 되는 비용과 원점 역추적 코드 때문에 뺐다. 필요해지면 PR-4 이후 별건.
- **`scope_id` 가 int** 라 proposalId 캐스팅이 들어간다(위 함정). 표 타입 확장은 별건.
- **알림 문구**: "더 빠른 길이 있어요 / 앱에서 새 경로를 확인하세요."로 고정. 발송을 켜기 **전**에 바꿔야 한다(켠 뒤 변경은 사용자 눈에 보인다).

## 2026-09-20 journey-reroute-nextstop PR-4 — 진행중 RIDE 절단 수락

### 확정
- **잘린 주행 단계는 복제도 신규도 아닌 제3의 경우**라는 PR-C 의 결론이 저장 쪽에서 `JourneyLeg.copyToRevisionTruncated(newRev, Truncation)` 로 굳었다. `rebuild` 는 `legIndex < fromLegIndex`(복제) / `>= fromLegIndex`(신규) 두 갈래였는데, 잘린 주행은 번호가 `== fromLegIndex` 라 그대로 두면 **신규 쪽으로 떨어져 진행도가 전멸**한다(= 앱 GPS 엔진 리셋). `Composed.truncatedLegIndex` 가 그 분기의 열쇠다.
- **자를 자리는 서울 노선 순번의 차이로 찾는다**(`origin.seq - boardSeq`). 스냅샷 정류장 id 는 검색 id(OTP gtfsId/ODsay), 원점은 서울 id 라 직접 못 맞댄다(PR-C "번역이 반만 된다"). 대신 **이름 대조**를 `RideTruncation.expectedStopName` 으로 함께 넘겨 합성 단계가 확인한다 — 두 정류장 목록이 어긋나면 그 자리엔 다른 정류장이 있고, 그대로 자르면 **엉뚱한 곳에서 내리라는 안내**가 된다. 대조는 공백만 무시(`"정류장 E"=="정류장E"`); 더 느슨하게 맞추면 옆 정류장까지 접힌다.
- **재확인은 fail closed(하차) / fail open(승차)** — 기존 비대칭(PLAN 2026-09-18 사용자 결정)을 수락 경로에서도 그대로 지킨다. 하차점 실시간이 없거나 막혔으면 **거절**(`no-realtime`·`arrival-blocked`), 갈아탈 첫 차 추정이 없거나 예측기가 터지면 **통과**(`boarding-unknown`). 근거: 못 내리는 것은 되돌릴 수 없고, 못 타는 것은 더 기다리면 된다.
- **수락 전용 리드타임 `next-stop.accept-min-lead-sec=30`**(제안 리드타임 90 과 별도). 제안은 "읽고 판단하고 움직일" 시간을 재지만 수락은 이미 누른 뒤라 남은 것은 문 앞으로 가는 시간뿐이다. 같은 값이면 **알림 보고 바로 누른 수락이 거절된다**.
- **전용 오류 코드 `REROUTE_ALIGHT_UNAVAILABLE`(409)**. `REROUTE_PROPOSAL_STALE` 과 가른 이유 = 앱이 할 일이 다르다. 이쪽은 제안이 낡은 게 아니라 **버스가 그 사이 더 간 것**이라 목록을 새로 받아 남은 자리를 보여 주면 된다. 여정은 트랜잭션째 되돌아가므로 제안도 ACTIVE 로 남고 다시 누를 수 있다(IT 로 고정).
- **`plannedDurationSec` 은 초 단위 실측**(`RideTruncation.rideSeconds`, 승차 `activatedAt` → 실시간 하차 시각). PR-C 함정 그대로 — draft 값을 쓰면 스냅샷의 분 단위를 되돌린 값이라 최대 ±30초가 조용히 틀어진다. IT 는 **분으로 접으면 960 이 되는 930초**를 일부러 골라 이 차이를 뮤테이션으로 가른다.
- **보존/재계산 경계**: 보존 = `progressGeometrySegmentIndex`·`progressFraction`·`progressSectionId`·`progressObservedAt`·`lastProgressId`·`activatedAt`·`state`(버스 geometry segment 는 정류장 사이 구간이라 **앞부분이 그대로 남는다** — 절단은 뒤를 잘라내는 것뿐이라 번호가 유효). 재계산 = `endStopId`/`endName`(초안에서), `plannedDurationSec`·`predictedEndAt`·`remainingStops`·`remainingDurationSeconds`(재확인 실측에서), `progressLegFraction`(정류장 수 비율로 다시). **비움** = `remainingDistanceMeters`(잘린 구간의 남은 거리는 알 수 없다 — 지어내지 않는다).
- **`BusRideLocator` 로 위치·내 차 판정을 한 자리에 모았다**(PR-2 `UpcomingStopWindowResolver` 에서 추출). 제안할 때와 수락할 때 규칙이 갈리면 **제안은 탈 수 있다고 하고 수락은 못 탄다고 하는** 어긋남이 생긴다. 내 차 비대칭(`sectionOrder ∈ {current, current+1}`, 뒤차 금지)도 여기 한 벌만 남는다.
- **지표 신설 `journey.reroute.accept.midride{outcome}`**: `applied` · `alight-passed` · `no-realtime` · `arrival-blocked` · `below-lead-time` · `not-riding` · `no-progress` · `stale-progress` · `ambiguous-stop` · `position-out-of-range` · `no-boarding-time` · `boarding-missed` · `boarding-unknown` + 합성 거절(PR-C 어휘). **사용자가 이미 고른 수락을 서버가 되돌리는 자리**라 사유별로 갈라야 손댈 곳이 보인다(`alight-passed` 많으면 리드타임, `no-realtime` 많으면 커버리지 — 정반대).

### 함정
- **재확인이 트랜잭션 안에서 외부 호출을 한다**: `journey_session` 행을 잠근 채 도착 조회(≤1.5s) + 승차 추정(≤1.5s)이 나간다. 최악 3초 동안 그 여정의 단계 전이가 막힌다. 순서를 바꿔 tx 밖에서 재확인하면 "확인한 뒤 커밋 전에 버스가 지나가는" 창이 생겨 PLAN 이 요구한 **반영 직전** 확인이 아니게 된다. 라이브에서 수락 p95 를 볼 것(미결).
- **`RerouteAcceptTxService` 의 `saveRouteOptions` 는 잘린 주행 단계를 안 건드린다**(PR-C 미결의 답): 주행 단계 draft 는 `routeOptions` 가 비어 있고(`JourneyCompiler.emitTransit`), 선택 후보는 **짝 대기 단계**(`fromLegIndex-1`, 복제 쪽)가 `select()` 로 함께 채운다. 순서도 맞다 — newLegs 는 legIndex 오름차순이라 대기가 먼저다.
- **`origin.routeId != ride.routeId` 면 거절한다**(`not-riding`). 순번은 노선 안에서만 뜻이 있어, 다른 노선의 seq 로 자를 자리를 계산하면 조용히 엉뚱한 정류장이 나온다.
- **Mockito 기본값이 NPE 로 새는 자리**: IT 에서 `SearchTimeBusQueryPort` 를 `@MockBean` 으로 두고 특정 인자만 stub 하면 나머지는 null 을 돌려준다 → `result.isOk()` NPE. `anyString()...` 로 **기본 `blocked()` 를 먼저 깔고** 특정 인자를 덮었다(그 자체가 "실시간 없으면 승차 추정은 fail open" 을 시험한다).
- **`BusQueryResult` 가 record 라 `@MockBean` 만으로는 아무 값도 안 나온다** — 위와 같은 이유로 PR-2 테스트와 같은 stub 형태를 그대로 썼다.

### 좌표
- 신규: `application/journey/service/BusRideLocator.java`(PR-2 에서 추출), `application/journey/service/RideTruncationResolver.java`
- 변경: `RerouteAcceptTxService`(`resolveTruncation`/`requireStillBoardable`/`estimateBoarding`/`truncated` + 4-arg compose 호출 + `applyRevision`·`rebuild` 시그니처), `JourneyLeg`(`copyToRevisionTruncated` + `Truncation` record), `JourneyRevisionComposer`(`RideTruncation.expectedStopName` + `nameMismatch` 대조), `UpcomingStopWindowResolver`(locator 위임, private 판정 3개 제거), `JourneyRerouteProperties`(`next-stop.accept-min-lead-sec` + validate), `JourneyRerouteMetrics`(`accept.midride`), `JourneyErrorCode`(`REROUTE_ALIGHT_UNAVAILABLE`)
- 테스트: `RideTruncationResolverTest`(신규 11), `JourneyRevisionComposerTest`(16→18: 이름 불일치·띄어쓰기), `RerouteAcceptIT`(10→12: 중간 하차 수락·지나간 정류장 롤백 + `givenProposalOf` 공통화), `UpcomingStopWindowResolverTest`(생성자 locator)

### 배포
- **마이그레이션 없음.** 새 설정 키 `journey.reroute.next-stop.accept-min-lead-sec` 는 기본값이 있어 compose 수정 불필요.
- 재탐색은 여전히 flag off(`journey.reroute.enabled=false`) → 프로덕션 동작 변화 0. 켤 때 볼 지표 = `journey.reroute.accept.midride{outcome}` 의 `applied` 대비 `alight-passed`·`no-realtime` 비율.
- **모바일 전달물**: ①`REROUTE_ALIGHT_UNAVAILABLE`(409) 를 받으면 **목록 새로고침**(제안은 아직 살아 있다) ②PLAN 의 "확인 화면(서버 요약)"은 클라 몫 — 서버는 후보의 `display.alight{stopId,name,alightAt}` 로 이미 재료를 준다.

### 검증
- `RideTruncationResolverTest` **11/11**(자리·소요·지나감 2종·실시간 없음/막힘·리드타임·수락 리드타임이 제안보다 짧음·낡은 관측·버스 아님 3종·다른 노선·승차시각 없음·진행 없음).
- `JourneyRevisionComposerTest` **18/18**, `RerouteAcceptIT` **12/12**(실제 Postgres).
- **뮤테이션 3건**: ①`copyToRevisionTruncated` → `copyToRevision` 으로 바꾸니 IT 가 `end_stop_id` 를 `ST-F`(옛 하차점)로 잡아 FAILED ②`alightSeq <= currentSeq` 가드를 지우니 `누르는 사이 지나간 정류장은 거절한다` FAILED ③`rideSeconds` 대신 draft 의 `plannedDurationSec` 을 쓰니 900 vs [920,940] FAILED. 셋 다 되돌림.
- `application.journey.*` + `application.route.*` → **1327 tests, 1 failed** = `JourneyTransferAssessmentIT`(PR-1·PR-2·PR-3 세션에서 clean main 재현 확인, **기존 무관**).
- `NextrouteApplicationTests`(전체 컨텍스트 로드 — 신규 빈 2개 배선) + `domain.journey.*` + `adapter.in.api.journey.*` + `architecture.*` → **111 tests, 0 failed**.

### 미결
- **tx 안 외부 호출 지연**(위 함정). 수락 p95 관측 후 예산 조정.
- **PLAN PR-4 의 "확인 화면"** 은 서버 산출물이 아니라 클라 UX — 모바일 HANDOFF 로 넘어간다.
- 다음 = **PR-5(③ `RouteOptionCandidateProvider` 제거)**. PR-2~4 가 전부 들어왔으므로 착수 조건 충족.
## 2026-09-20 journey-reroute-nextstop PR-5 — ③(노선 후보) 폐기

### 확정
- **지운 것 = ③의 생산 경로 전부**: `RouteOptionCandidateProvider`(494줄)·`RouteOptionEtaCalculator`(155)·출처 4종(`RerouteRouteOptionSource`/`StoredRouteOptionSource`/`UnionRouteOptionSource`/`OtpPatternRouteOptionSource`)·테스트 5개·`RerouteRouteOptionIT`·설정 블록 `journey.reroute.route-option.*`(클래스+yaml+validate 3개)·`RouteOptionApply` 하위타입·수락/합성의 ROUTE_OPTION 분기.
- **`RerouteProposalKind.ROUTE_OPTION` 은 남긴다(PLAN "정리" 의 정정).** 이 enum 은 DB 컬럼을 **읽는** 어휘고 V42 의 check 제약은 아직 그 값을 받는다 — 지우면 그 값으로 저장된 옛 행을 읽는 순간 `IllegalArgumentException` 이다. 어휘를 좁히려면 이력 행을 지우거나 고쳐 써야 하는데 그건 기록을 바꾸는 일이라 하지 않았다. **만드는 쪽을 없애면 어휘는 자연히 죽는다**(javadoc 에 "폐기됨, 새로 만들지 않는다" 명시).
- **옛 ROUTE_OPTION 행의 후보는 "모르는 봉투"로 거절된다**(`RouteOptionApply` 가 사라져 Jackson 이 `InvalidTypeIdException`). 이게 맞는 결말이다 — 사라진 뜻으로 여정을 바꾸느니 거절이 낫고, 제안은 TTL(2~5분)로 사라진다. 계약 테스트로 고정(`retiredRouteOptionTypeIsRejected`).
- **`Composed.keepsStructure()` 도 함께 사라졌다.** "구조가 안 바뀌는 수락"은 ③ 전용 개념이었고(legs 가 비는 유일한 경우), 남기면 언제나 false 인 분기가 저장 경로 세 곳(`copyAll`·`saveRouteOptions`·`saveTransfers`)에 남는다.
- **선택 노선은 이제 언제나 그 단계의 첫 후보다**(`firstOption`). `chooseRouteOption`(저장 후보 optionIndex / 동적 후보 새 행 insert)은 통째로 사라졌다 — 사용자가 고르는 것은 **경로 전체**이지 노선 하나가 아니다.
- **사다리(`RerouteCandidateProvider`)는 남긴다.** 칸이 하나뿐이라도 `priority` 는 **요금 순서**를 뜻하고, 값싼 칸이 생기면 그 앞자리에 들어가야 한다. 순서 계약 테스트는 폐기된 `ROUTE_OPTION` 을 **대역**으로 쓴다(그 뜻을 주석으로 못박음 — 서로 다른 enum 값 두 개가 필요해서다).
- **OTP 포트 2개는 지우지 않았다**(`StopPatternPort`·`RouteRideDurationPort` + 어댑터·매퍼·테스트). 소비자가 ③뿐이라 지금은 **부르는 곳이 없다**. 근거 = ①`stopPatterns` 는 national-otp 후속(baseline 의 OTP 노선 id 되찾기)이 재사용 대상으로 지목한 질의고 ②그쪽은 다른 작업 축이라 PR-5 가 임의로 걷을 자리가 아니다. javadoc 에 "지금은 부르는 곳이 없다"를 명시했다(미결).

### 함정
- 🔴 **`WithdrawalDeletionBoundaryIT` 가 main 에서 이미 7/7 깨져 있었다**(clean main stash 교차검증). 원인 = PR-3(#223)이 `JourneySession` 에 3칸·`RerouteProposal` 에 `alerted` 를 더했는데, 이 IT 의 스키마는 마이그레이션이 아니라 **ddl-auto** 가 만든다 — Hibernate 는 원시타입 필드를 **기본값 없는 not null** 로 내므로 V53 의 `default false` 가 오지 않는다. raw insert 가 `null value in column "reroute_alert_opt_in"` 로 죽는다. **이 PR 에서 고쳤다**(insert 에 4칸 추가). PR-3 세션이 `application.auth.*` 를 안 돌려 못 잡은 건이다 — **엔티티에 칸을 더하면 @Sql 체인뿐 아니라 ddl-auto IT 의 raw insert 도 본다.**
- `RerouteAcceptTxService.requireStillApplicable` 이 쓰던 `fromLegIndexOf(apply)`(타입 분기)가 사라지고 **`RerouteCandidateApply.fromLegIndex()`** 가 인터페이스 계약이 됐다. 하위타입이 하나뿐이라 분기가 무의미해졌기 때문이지만, 종류가 다시 늘면 이 메서드가 그 계약을 강제한다.
- 지표 `ride-duration-incomparable`(③ 공정성 게이트, PR4c)은 **생산자가 사라져** KNOWN_FILTER_REASONS 에서 뺐다. 목록에 남겨 두면 "0 인 계량기"가 대시보드에 영원히 남는다.

### 좌표
- 삭제(12): `service/{RouteOptionCandidateProvider,RouteOptionEtaCalculator,RerouteRouteOptionSource,StoredRouteOptionSource,UnionRouteOptionSource,OtpPatternRouteOptionSource}.java`, `test/.../service/{RouteOptionCandidateProviderTest,RouteOptionEtaCalculatorTest,StoredRouteOptionSourceTest,UnionRouteOptionSourceTest,OtpPatternRouteOptionSourceTest}.java`, `test/.../journey/RerouteRouteOptionIT.java`
- 변경(주요): `RerouteAcceptTxService`(ROUTE_OPTION 분기 3곳+`chooseRouteOption`·`copyAll`·`selectByIndex`→`firstOption`), `JourneyRevisionComposer`(compose 분기·`Reason.ROUTE_OPTION_ACCEPTED`·`keepsStructure`), `RerouteCandidateApply`(하위타입 제거 + `fromLegIndex()` 계약), `JourneyRerouteProperties`(RouteOption 블록+validate), `application.yaml`(route-option 블록), `RerouteProposalKind`(폐기 주석), `RerouteCandidateProvider`(사다리 주석), `JourneyRerouteMetrics`, ③을 현재형으로 말하던 javadoc 10여 곳
- 테스트: `RerouteCandidateContractTest`(ROUTE_OPTION 왕복 → **거절** 테스트로 교체), `RerouteProposalServiceTest`(대역 주석), `JourneyRevisionComposerTest`(16→14), `RerouteAcceptIT`(10→9, 동적 노선 수락 삭제), `RerouteProposalStoreIT`·`RerouteProposalExpiryIT`·`JourneyRerouteProposalSchemaIT`·`JourneyNotificationCancelOnEndIT` 픽스처 kind 교체, `JourneyReroutePropertiesTest`, **`WithdrawalDeletionBoundaryIT`(기존 파손 수리)**

### 배포
- **마이그레이션 없음.** 설정 키 `journey.reroute.route-option.*` 은 코드에서 사라졌다 — compose/VPS env 에 그 키를 넣어 둔 곳이 있으면 **무시된다**(Spring 은 모르는 키를 조용히 버린다). VPS compose 에는 없음(확인 필요 항목 아님).
- 재탐색은 여전히 flag off → 프로덕션 동작 변화 0. 사라진 계량기 = `journey.reroute.candidate.filtered{reason=ride-duration-incomparable}`.

### 검증
- `application.journey.*` + `application.route.*` + `domain.journey.*` → **1305 tests, 1 failed** = `JourneyTransferAssessmentIT`(clean main 동일, **기존 무관**).
- `NextrouteApplicationTests`(전체 컨텍스트 로드 — 빈 6개가 사라져도 배선이 성립하는지) + `infrastructure.*` + `architecture.*` + `application.auth.*` → **478 tests, 0 failed**(수리 전 7 fail).
- 사다리 무회귀: `RerouteProposalServiceTest` 17/17(순서·폴스루·빈손).
- 계약: `RerouteCandidateContractTest` 9/9(폐기 봉투 거절 신규 1).

### 미결
- **부르는 곳 없는 OTP 포트 2개**(`StopPatternPort`·`RouteRideDurationPort` + 어댑터·매퍼). national-otp 후속이 `stopPatterns` 를 쓰기로 되어 있어 남겼다. 그 작업이 접히면 함께 지울 것.
- `reroute_proposal.kind` 의 DB 어휘는 그대로(ROUTE_OPTION 허용). 좁히려면 이력 행 처리 정책이 먼저다.

## 2026-09-20 nextstop PR-4·PR-5 머지 + 리베이스

### 배포
- **#225(PR-4) → #226(PR-5) 순서로 main 머지 완료.** main = `233abf2b`. nextstop 8 PR(A·B·C·1~5) **전부 머지 — 코드 끝**.
- PR-5 는 fresh main 기준이라 #225 머지 뒤 **리베이스**했다(force-with-lease). 충돌 3곳, 전부 예상대로:
  1. `RerouteAcceptTxService.applyRevision` — PR-4 가 `rebuild(..., truncation, now)` 를 넣은 자리에 PR-5 가 `keepsStructure` 삼항을 지웠다. 해결 = **삼항 없이 truncation 넘기는 한 줄**.
  2. `RerouteAcceptIT` — PR-5 가 지운 `givenRouteOptionProposal` 자리. 해결 = PR-5 쪽(삭제). 딸린 `selectedRouteIdAt` 도 소비자가 사라져 함께 제거.
  3. `implementation_note.md` — 둘 다 끝에 절을 붙였다. append-only 라 **둘 다 남긴다**(PR-4 절 → PR-5 절).
- 🔴 **force-push 직후 GitHub 은 `CONFLICTING | DIRTY` 를 답한다** — mergeability 재계산 전의 값이다. 몇 초 뒤 다시 물으면 `MERGEABLE | CLEAN`. 그 값을 보고 손대면 멀쩡한 브랜치를 다시 만지게 된다.
- ⚠ **main 직접 커밋은 훅이 막는다**(CLAUDE.md 티어 규칙). 그리고 훅은 명령 **전체**를 거절하므로 `checkout -b && commit` 을 한 줄로 묶으면 브랜치 생성까지 안 돈다 — 브랜치를 먼저 따로 만들 것.

### 검증 (합본 main 기준)
- `application.journey.*` + `application.route.*` + `domain.journey.*` → **1320 tests, 1 failed** = `JourneyTransferAssessmentIT`(**기존 무관**, clean main 동일).
- 전체 컨텍스트 로드 + `infrastructure.*` + `architecture.*` + `application.auth.*` → **478 tests, 0 failed**.
- `RerouteAcceptIT` **11/11** — PR-4 의 중간 하차 2건과 PR-5 의 ③ 삭제가 한 파일에서 함께 성립함을 확인(리베이스가 맞았다는 증거).
- 머지된 main 재컴파일(main+test) 통과.

### 미결 (배포 전 남은 것)
- 🔴 **V53·V54 VPS 수동배치**(VPS=v52, prod `ddl-auto: validate` → 없으면 앱이 안 뜬다). 이 트리에서 `./gradlew bootJar` 해야 jar 안에 실린다.
- **모바일 HANDOFF**: ①`transferIndex` → `origin` 응답 계약 ②`REROUTE_ALIGHT_UNAVAILABLE`(409) = 목록 새로고침 ③중간 하차 확인 화면(서버는 `display.alight` 로 재료 제공).
- 켜는 순서: `journey.reroute.enabled` → `alert.enabled`.

## 2026-09-20 ③ 잔여 OTP 포트 2개 삭제 (PR-5 후속, C안)

### 확정
- **`StopPatternPort`·`RouteRideDurationPort` 계열을 전부 지웠다**(-1,082줄, 17파일). PR-5 가 ③을 지우면서 둘 다 **호출부 0** 이 됐다.
  - stopPattern: `StopPattern`·`StopPatternPort`·`StopPatternResult`·`OtpStopPatternAdapter`·`OtpStopPatternMapper`·`stopPatterns.graphql` + 테스트 2·fixture 1
  - rideDuration: `RideDurationResult`·`RouteRideDurationPort`·`OtpRouteRideDurationAdapter`·`OtpRouteRideDurationMapper`·`routeRideDuration.graphql` + 테스트 2·fixture 1
- **~~PR-5 의 "`stopPatterns` 는 national-otp 후속이 쓰기로 한 질의라 남긴다"~~ 취소(2026-09-20 실측)**: 그 "후속"이 가리키던 것은 **PR4c SUMMARY 안의 "baseline OTP id 되찾기(board 정류장 재조회+resolveBus)"** 이고, 그 작업의 목적은 **③의 shadow OFF**(OTP 후보를 실제 제안으로 승격)였다. ③이 폐기된 순간 그 후속도 함께 죽는다. 근거 = `stopPatterns` 를 언급하는 플랜 문서가 ③ 전용 둘(`otp-p2f-route-option-pattern`·`otp-p2f-route-option-eta`)뿐이고, **national-otp 지도 마이그레이션 플랜(PHASE-0~8)에는 한 번도 안 나온다**(grep 실측).
- **두 포트는 애초에 ③ 전용으로 태어났다**: PR-4(#193)가 후보 *집합*을 넓히려고 stopPattern 을, PR4c(#194)가 그 후보의 *우열 ETA* 를 정확히 하려고 rideDuration 을 만들었다. PR4c 는 "③ 실제 제안 ON" 의 마지막 조각이었고 nextstop A′ 결정으로 파킹(#194 닫힘)됐다.
- 되살릴 필요가 생기면 **브랜치·이력에 그대로 있다**(#193 / `feat/otp-p2f-route-ride-duration`).

### 함정
- 참조 검사에서 `rideDuration` 문자열이 **관계없는 곳에 많이 걸린다**(`RoutingLeg.rideDurationSeconds`·`TruncatedRide`·`JourneyRevisionComposer.RideTruncation.rideSeconds` 등, nextstop 자체 필드). 파일명 grep 만 보고 지우면 멀쩡한 코드를 건드린다 — **타입명(`StopPatternPort`·`RouteRideDurationPort`·`OtpStopPattern*`·`OtpRouteRideDuration*`)으로 좁혀서** 확인했고 외부 참조는 **0건**이었다.

### 좌표
- 삭제(17): `application/route/routing/{StopPattern,StopPatternPort,StopPatternResult,RideDurationResult,RouteRideDurationPort}.java`, `infrastructure/adapter/out/api/otp/{OtpStopPatternAdapter,OtpStopPatternMapper,OtpRouteRideDurationAdapter,OtpRouteRideDurationMapper}.java`, `main/resources/otp/{stopPatterns,routeRideDuration}.graphql`, 테스트 4 + `test/resources/otp/{stopPatterns-success,routeRideDuration-success}.json`
- 변경: 없음(호출부가 없어 손댈 자리가 없다).

### 배포
- **마이그레이션 없음. 설정·env 변화 없음.** 지운 GraphQL 은 앱이 시작할 때 읽는 파일 목록에 있지 않았다(각 어댑터가 자기 파일만 읽었고 그 어댑터도 함께 사라졌다).
- OTP 서버 쪽은 무관하다 — 우리가 **안 보내는 질의가 둘 늘었을** 뿐이다.

### 검증
- 컴파일(main+test) 통과. 타입 참조 grep **0건**.
- 전체 컨텍스트 로드 + `infrastructure.*` + `application.route.*` + `architecture.*` → **679 tests, 0 failed**(빈 2개가 사라져도 배선 성립).
- `application.journey.*` + `domain.journey.*` → **1040 tests, 1 failed** = `JourneyTransferAssessmentIT`(**기존 무관**).

## 2026-09-20 national-otp P7 PR-1 — provider 코드 기본값을 OTP 로

### 확정 (계약)
- `RoutingProviderProperties` 기본값 `defaultProvider=OTP`, `otpEnabled=true`. **이전까지 프로덕션 전환은 VPS compose env 주입에만 있었다** — env 가 빠지면 코드가 조용히 ODSAY 로 되돌아가는 상태였다. 코드가 전환 사실을 적게 만드는 것이 이 PR 의 전부.
- `application.yaml` 에 `routing.provider.*` 블록 신설(`${ROUTING_PROVIDER_DEFAULT_PROVIDER:OTP}` / `${ROUTING_PROVIDER_OTP_ENABLED:true}`). 기존 VPS env 이름과 같은 키라 **배포 쪽 변경 0**(env 가 그대로 이긴다).
- `routing.otp.*`(base-url/graph-version/coverage-index-path/shapes-enabled)는 yaml 로 안 끌어온다. 그래프 버전 같은 값이 코드와 compose 두 곳에 갈리면 어느 쪽이 산 값인지 알 수 없어진다.
- `otp-enabled` 의 뜻을 문서로 못박음: 전환 스위치가 아니라 **승인 게이트**(P2-B). false 면 default 가 OTP 라도 ODSAY 로 내려간다. `RoutingProviderSelector` 로직은 한 줄도 안 바꿨다.
- ODSAY 는 **롤백 통로로 보존**(사용자 결정). env 로 `default-provider=ODSAY` 만 주면 즉시 복귀.

### 함정
- **기본값을 뒤집으면 "ODSAY 를 암묵 가정하던" Spring IT 가 조용히 OTP 분기로 샌다.** `RerouteRemainingPathIT` 2케이스가 이렇게 깨졌는데, 증상이 "제안이 안 만들어진다"(Optional empty)라 provider 와 무관한 재탐색 로직 버그처럼 보인다. 이 IT 는 `OdSayApiPort` 를 MockBean 으로 세우는데 OTP 분기는 그 Mock 을 아예 안 타니 후보가 통째로 비는 것. 고침 = `@TestPropertySource` 에 `routing.provider.default-provider=ODSAY` 명시.
- 같은 이유로 단위테스트의 `new RoutingProviderProperties()` 도 전부 위험하다 — 기본값 의존을 없애고 ODSAY 를 명시하는 `odsayProps()` 헬퍼로 바꿨다. 기본값이 또 바뀌어도 테스트 뜻이 안 흔들린다.
- `OdSayApiPort` 를 MockBean 으로 쓰는 IT 3개 중 나머지 2개(`RerouteAcceptIT`, `SubwayBindingRerouteInheritanceIT`)는 provider 분기를 안 타서 무사했다. MockBean 존재 자체가 위험 신호는 아니고, **재검색/검색을 실제로 호출하는지**가 기준.

### 좌표
- `src/main/java/watoo/grd/nextroute/application/route/config/RoutingProviderProperties.java:19,22` (기본값 2개)
- `src/main/resources/application.yaml:58~66` (routing.provider 블록 신설, odsay 블록 바로 위)
- `src/test/java/watoo/grd/nextroute/application/journey/RerouteRemainingPathIT.java:64~72` (provider 고정)
- 신규 `src/test/java/watoo/grd/nextroute/application/route/config/RoutingProviderPropertiesTest.java` (기본값 계약 가드)

### 검증
- `./gradlew test` (전체): **2205 tests, 1 failed, 3 skipped**.
- 유일한 실패 = `JourneyTransferAssessmentIT.assessmentIsPersisted` — **기존 무관**. 이 PR 변경분을 stash 한 상태에서 같은 테스트를 돌려 baseline 에서도 동일 실패(`5 tests completed, 1 failed`)임을 확인했다.
- 대상 테스트 green: `RoutingProviderPropertiesTest`, `RoutingProviderSelectorTest`, `RouteSearchServiceTest`, `RemainingRouteSearchService*Test`, `RouteSearch*DeadlineIT`, `RerouteRemainingPathIT`, `RerouteAcceptIT`, `SubwayBindingRerouteInheritanceIT`.

### 배포
- 마이그레이션 없음. VPS 조치 없음 — compose 의 `ROUTING_PROVIDER_*` 가 이미 같은 값이라 재배포해도 동작 변화 0. 이 PR 이후에는 그 env 를 지워도 OTP 로 남는다(원하는 상태).

## 2026-09-20 national-otp P7 PR-2 — ODsay 폴리라인 스택 제거

### 확정 (계약)
- **지우는 건 폴리라인 스택뿐. ODSAY 라우팅 분기는 롤백 통로로 그대로 둔다**(사용자 결정). `OdsayRoutingAdapter`·`OdSayApiAdapter.searchPath`·`RoutingProvider.ODSAY`·`walkOnlyFallback(-98)`·TMAP 도보·환승 도착보강 전부 보존.
- 스택은 **닫힌 고리**였다: 검색 seed(`RoutePolylineEnricher.requestCollection`) → `odsay_route_polyline_collection_job` → worker 스케줄러(60s) → `loadLane`(ODsay 유료) → `odsay_route_polyline` 캐시 → 다시 enricher 가 읽음. **진입점이 ODSAY 검색 분기 하나뿐**이라 OTP 전환 뒤 신규 유입이 0 이었고, 프로덕션 실측도 seed 0행·PENDING job 0행이었다.
- `SearchComponent.POLYLINE` 상수 제거 = **응답 meta 계약 변화**. 검색 응답의 `meta.degraded` 목록에 `polyline` 이 다시는 안 나온다(OTP 전환 뒤로 이미 안 나오던 값). 클라는 목록을 소비만 하므로 깨지는 쪽은 없다.
- `OdSayApiPort.loadLane` 제거로 포트가 `searchPath` 단일 메서드가 됐다. `OdSayApiAdapter` 의 `RestTemplate` 의존도 같이 사라짐 — **RestTemplate 은 loadLane 전용이었다**(searchPath 는 요청별 read timeout 때문에 RestClient 를 호출마다 만든다). RestTemplate 빈 자체는 kakao/subway 어댑터가 쓰므로 유지.
- `LaneGraphicResult` 는 **남긴다** — `PathResult.laneGraphics` 가 legacy 응답 계약이고 `LegacyRouteResponseMapper` 가 채운다(항상 빈 목록). `OdSayLane` 도 검색 응답 `OdSaySubPath` 가 공유하므로 남긴다. 지운 DTO 는 lane 전용 4개(`OdSayLaneGraphic`/`OdSayLoadLaneResponse`/`OdSayLoadLaneResult`/`OdSayBoundary`)뿐.
- 버스 형상 계열(`BusRoutePolyline*`, PostGIS·서울 API 축)은 **ODsay 와 무관** — 이름만 비슷하다. 손대지 않음.

### 함정
- `git rm` 이 `OdsayRoutePolylineDataService.java` 에서 막혔다 — 이전 세션이 붙여둔 미커밋 `@Deprecated`+미사용 import 때문. 이 PR 이 파일을 통째로 지우므로 `-f` 로 **삭제에 흡수**했다(별도 커밋 불필요).
- 삭제 후 컴파일 에러가 **테스트 쪽에서만** 12개 났다. main 은 한 번에 통과 — 죽은 코드를 살아 있게 보이게 하던 건 전부 테스트였다는 뜻. 익명 `OdSayApiPort` 구현이 3곳(`RouteSearchServiceOtpWiringTest`, `RemainingRouteSearchServiceOtpTest`, `OdsayRoutingAdapterTest`)에 있어 포트 메서드를 지우면 "does not override" 로 터진다.
- `RuntimeRoleProfileTest.WORKER_COMPONENTS` 가 worker 프로필 빈 목록을 하드코딩하고 있다 — 스케줄러를 지우면 여기도 같이 지워야 한다(컴파일로 잡히긴 한다).

### 좌표
- 삭제 26파일: `application/route/service/{RoutePolylineEnricher,OdsayRoutePolylineLoadService,OdsayRoutePolylineCollectionService,OdsayRoutePolylineSlicer,OdsayMapObjParser}`, `application/route/dto/{OdsayMapObjFragment,OdsayRoutePolylineData,OdsayRoutePolylinePoint}`, `domain/route/polyline/**`(entity 4·repository 3·service 1), `infrastructure/adapter/in/api/admin/OdsayRoutePolylineAdminController`, `infrastructure/adapter/in/scheduler/OdsayRoutePolylineCollectionScheduler`, odsay lane DTO 4, 테스트 4.
- 수정: `OdSayApiPort.java`(loadLane 제거), `OdSayApiAdapter.java`(lane 블록 88줄 + RestTemplate 필드), `RouteSearchService.java`(폴리라인 단계·필드), `SearchComponent.java`(POLYLINE 상수).
- 회귀 가드: `RouteSearchServiceTest.TC_ODSAY_롤백_경로는_폴리라인_없이_도보_환승_보강만_거친다`.

### 검증
- `./gradlew test` (전체): **2161 tests, 1 failed, 2 skipped**. 실패 = `JourneyTransferAssessmentIT.assessmentIsPersisted` — **기존 무관**(PR-1 에서 baseline 확인한 그 실패).
- 테스트 총수가 2205 → 2161 로 준 것은 폴리라인 전용 테스트 4클래스(44케이스)를 지웠기 때문.
- 남은 참조 0 확인: `rg 'OdsayRoutePolyline|RoutePolylineEnricher|OdsayMapObj|loadLane|LaneGraphic'` → V55 주석 외 히트 없음.

### 배포
- **V55 VPS 수동배치 필요** (`V55__drop_odsay_route_polyline.sql`). V18+ untracked convention 이라 CI 로 안 나간다 — 누락 시 테이블만 남고 기능 영향은 없지만, 배치 전까지 정리가 안 끝난 상태다.
- ⚠ 선행 미배포분: **V53·V54 가 아직 VPS 에 없다**(journey-reroute-nextstop). V55 를 올리기 전에 번호 순서를 확인할 것.
- 프로덕션 영향 없음: 드롭 대상 3테이블은 polyline 27행 / seed 0행 / job 27행(PENDING 0)뿐이고, 읽는 코드가 이 PR 로 사라졌다.

## 2026-09-20 OTP 지하철 방향(wayCode) 복원 + 모바일 인계 문서

### 확정
- **OTP 전환 후 모바일이 지하철 실시간을 못 부르던 원인**: 검색 응답의 지하철 칸 중 `startID`/`endID`·`wayCode`·`lanes[].subwayCode` 가 전부 null 이다(`LegacyRouteResponseMapper` 가 "지하철은 좌표 기반이라 id 를 안 채운다"로 의도한 동작). 그런데 `/api/arrivals/subway` 는 **원래 좌표 기반**(`lat`·`lon` 필수, `line_id`·`wayCode` 선택)이라 **좌표만으로 부를 수 있다** — 진짜로 잃은 것은 **방향뿐**이었다.
- **방향은 feed 가 준다. 우리가 안 읽고 있었을 뿐**: KTDB 는 `direction_id` 를 안 주지만(전 노선 -1) `route.longName` 에 방향을 적는다. **VPS OTP 실측**으로 확인 — `서울2호선(본선)<내선>`, `신림선<상행>`. `missing.add(DIRECTION)` 의 "feed 에 방향/headsign 없음" 주석이 틀렸던 것이고, 이제 **읽어 낸 leg 에는 DIRECTION 을 안 붙인다**.
- **표기가 두 가지다(전수 조사로 발견)**: 꺾쇠로 덧붙이는 도시철도 59개 + **이름 자리에 방향만 있는 RAIL 2개**(`[일반]교외서울의정부` 의 longName = `상행`). 처음 구현한 꺾쇠 전용 파서는 후자를 통째로 놓쳤다 — **"전 노선으로 돌려 보라"는 지시가 아니었으면 안 잡혔다.**
- **순환선 판별은 노선 id 가 아니라 라벨이 한다**(`SubwayDirectionNormalizer.toWayCode`): 내·외선으로 말하면 그 자체가 순환선이다. id 로 가르면 `인천2호선` 이 2호선(1002)으로 둔갑해 상·하행이 내·외선 값 공간으로 나간다.
- **값 공간은 ODsay 그대로 유지**(1=상행/2=하행, 순환 0=내선/1=외선). 앱 코드가 안 바뀌고, `외선`(1)과 `상행`(1)이 겹치는 것은 읽는 쪽이 노선을 알아 `fromWayCode` 로 정확히 되돌린다(왕복 테스트로 고정).
- **덤으로 여정 쪽도 살아난다**: `JourneyCompiler.routeOptions` 가 `sub.wayCode()` 를 `JourneyRouteOptionDraft.wayCode` 로 옮기고, 그 값이 없으면 지하철 승차 예측이 `no-direction` 으로 죽는다. 이제 채워진다.
- `RoutingLeg` 에 `sourceRouteDirection`(원문 라벨) 신설. **16-arg 하위호환 생성자**를 함께 둬서 23곳 생성부를 안 건드렸다(`RideTruncation`·`Composed` 선례와 같은 방식).

### 함정
- 🔴 **fixture 가 현실과 달랐다**: `planconnection-subway.json` 의 `shortName`=`1호선`·`longName`=`-` 는 실제 feed(`서울1호선`, `서울1호선(의정부-서동탄)<상행>`)와 다르다. 그 상태로 테스트를 짜면 **색상 매핑도 방향 파싱도 통과하지 못할 값**으로 초록불을 본다. 실측값으로 갱신했다.
- **`SubwayLineColors` 에 없는 노선은 `lanes` 가 통째로 null 이다**(legacy 경계가 색이 있을 때만 만든다). 도시철도 23종은 전부 있지만 **RAIL(교외선)은 없다** → 그 구간은 앱에 노선명이 안 나간다. 문서에 적어 뒀다(미결).
- **dev DB `subway_station` 에는 2호선이 없다**(645행, prod 는 696행에 1002 51행). 로컬에서 2호선 실시간이 빈손이어도 프로덕션 문제가 아니다.

### 좌표
- 변경: `SubwayDirectionNormalizer`(`toWayCode`·`labelFrom` 신설), `RoutingLeg`(`sourceRouteDirection` + 하위호환 생성자), `OtpPlanConnectionMapper`(longName→라벨, DIRECTION 조건부), `LegacyRouteResponseMapper`(지하철 `wayCode` 채움)
- 신규 테스트: `GtfsRailDirectionCoverageTest`(4) + fixture `otp/gtfs-rail-routes.json`(**VPS OTP 실측 61 노선**)
- 변경 테스트: `SubwayDirectionNormalizerTest`(9→13), `OtpPlanConnectionMapperTest`(3→5), `LegacyRouteResponseMapperTest`(+7), fixture `planconnection-subway.json` 실측값 갱신
- 문서: `docs/plan/national-otp-map-migration/handoffs/SUBWAY-REALTIME.md`(모바일 인계)

### 배포
- **마이그레이션·설정 없음.** 검색 응답에 지하철 `wayCode` 가 다시 채워지는 것이 유일한 변화다(없던 칸이 생기는 것이라 옛 앱도 안 깨진다).

### 검증
- `GtfsRailDirectionCoverageTest` **4/4** — feed 전 노선(61)에 대해 ①방향 해석 ②wayCode 변환 ③노선별 왕복 ④도시철도 표시색. 방향 없는 노선은 `DIRECTIONLESS` 화이트리스트(서울6호선)로만 허용.
- **뮤테이션 2건**: ①`labelFrom` 을 꺾쇠 전용으로 되돌리니 커버리지 테스트가 **RAIL 2개를 정확히 지목**하며 FAILED ②legacy 경계에서 `wayCode = null` 로 두니 `LegacyRouteResponseMapperTest` 4건 FAILED. 둘 다 되돌림.
- 전체: `application.route.*` + `application.subway.*` + `infrastructure.*` + `application.journey.*` + `architecture.*` + 컨텍스트 로드 → **1791 tests, 1 failed** = `JourneyTransferAssessmentIT`(**기존 무관**, clean main 동일).

### 미결
- 교외선(RAIL)은 표시색이 없어 `lanes` 가 비어 나간다. 색을 넣을지, legacy 경계가 색 없이도 `lanes` 를 만들지는 별건.
- 버스 `longName` 에도 방향이 있는지 미확인(2,676 노선). 버스는 원천에도 `wayCode` 가 없어 지금 쓸 데가 없다.
## 2026-09-20 nextstop 모바일 HANDOFF 작성

### 확정
- `docs/plan/journey-reroute-nextstop/HANDOFF.md` 신설. 8 PR 에 흩어져 있던 "모바일 전달물"을 한 장으로 모았다 — 그전까지는 PR 본문과 이 노트에만 있어서 **모바일에 그대로 건넬 문서가 없었다**.
- 담은 것 8절: ①깨지는 변경(`transferIndex`→`origin`, 요청 DTO 는 그대로) ②후보 모양(`display.alight` 축, 정류장당 1개, `walkingSec` 의미 확장) ③거절/무시 엔드포인트(멱등, 응답 3필드) ④수락 오류표(신규 `REROUTE_ALIGHT_UNAVAILABLE` vs 기존 STALE 의 차이 = "버스가 더 갔다" vs "제안이 낡았다") ⑤push data 페이로드 실물(`scopeId=proposalId`, deepLink) ⑥**`remainingStops` 의존**(안 보내면 기능 절반이 조용히 죽는다) ⑦확인 화면 재료 ⑧켜는 순서.
- **필드·경로는 코드에서 직접 뽑았다**(추측 금지): `RerouteProposalResponse.ProposalBody`·`RerouteCandidateSummary`·`RerouteCandidateDisplay.AlightStop`·`RejectRerouteProposalRequest`·`RerouteProposalDecisionResponse`·컨트롤러 4개 경로·`FcmMessageFactory.data()`·`JourneyNotificationEnqueuer.enqueueRerouteSuggested`. 진행 보고 경로는 `PUT /api/journeys/{journeyId}/progress`(`JourneyController:177`) 로 확인.
- **앱 배포가 플래그 ON 보다 먼저**라고 문서에 못박았다 — `origin` 을 못 읽는 옛 앱이 남아 있는 동안 켜면 제안 화면이 깨진다.

### 좌표
- 신규: `docs/plan/journey-reroute-nextstop/HANDOFF.md`

## 2026-09-20 VPS 배포 — V53·V54·V55 + 재탐색 알림 ON

### 배포
- **완료. 스키마 v52 → v55, api·worker 둘 다 healthy, 에러 로그 0.** 이미지 `nextroute:20260920-162800-6570fa29`(이전 = `20260919-142058-9a73dd4b`). 백업 = `app.jar.bak-20260920` · `compose.yaml.bak-20260920`.
- 적용된 마이그레이션: **V53**(journey_session 알림 3칸) · **V54**(reroute_proposal.alerted + status CHECK) · **V55**(odsay_route_polyline 제거, P7 PR-2).
- compose 변경 = `x-app-environment` 앵커에 **`JOURNEY_REROUTE_ALERT_ENABLED: "true"`** 한 줄. api·worker 가 함께 상속한다. 편집은 **VPS 인플레이스**(compose 는 git 이 아니고 로컬 repo 와 drift 가 있다 — 덮으면 프로덕션이 깨진다).
- 기동 확인: `형상 마킹 ACTIVE`(coveredModes=[subway,bus], degraded=1533) · `FCM 초기화 완료` · `JourneyNotificationRelayLoop started` · `Started NextrouteApplication` ×2.

### 확정 (실측으로 뒤집힌 전제)
- 🔴 **"재탐색은 flag off 라 프로덕션 영향 0" 은 틀렸다.** `journey.reroute.enabled` 의 yaml 기본값이 **2026-08-25 커밋 `8961bed1`("feat: add test notification api")에서 `false` → `true`** 로 바뀌었고 VPS 에 override env 가 없다. 즉 그 뒤의 모든 배포에서 **재탐색 기능은 켜져 있었다**. 지금까지 아무 일도 안 일어난 이유는 플래그가 아니라 **앱이 그 엔드포인트를 안 불렀기 때문**이다(`reroute_proposal` 0행).
  - 이 문장을 노트·PR 여러 곳에 "영향 0" 근거로 반복해 적었다 — **근거가 코드 기본값(false)이었고 yaml(true)을 안 봤다.** 앞으로 플래그 실효값은 **yaml → 그리고 컨테이너 env** 순으로 확인할 것.
- 🔴 **발송 경로는 이미 살아 있었다.** `JOURNEY_NOTIFICATION_RELAY_ENABLED=false` 는 compose 의 **api 블록에만** 있고, relay 는 **worker 프로필 전용**이라 worker 는 yaml 기본값(true)으로 돌고 있었다. worker 는 `SENDER=fcm` + 인증서 마운트 상태. 그래서 **이번에 켠 `alert.enabled` 하나로 실제 푸시가 나간다**.
- 프로덕션 규모(배포 시점): users 84 · journeys 73 · **active 0** · **user_push_token 1** · reroute_proposal 0. 도달 범위가 작아 알림을 켜는 위험이 낮았다.
- 지하철 실시간 노선 표(`subway_station`)는 prod 696행(1002 51행 포함), **dev 645행(2호선 없음)** — 로컬에서 2호선이 빈손인 것은 프로덕션 문제가 아니다.

### 함정
- **144MB jar 의 scp 가 에이전트 권한에서 막힌다.** 사용자가 터미널에서 직접 올린 뒤 이어서 진행했다. 배포 절차를 자동화할 때 이 단계가 사람 손을 탄다는 것을 전제로 둘 것.
- `deploy.sh all` 은 build → api·worker 재생성 → **health gate(각 180s)** → 실패 시 이전 이미지 자동 복구. 이번엔 둘 다 통과.

### 검증
- `flyway_schema_history` 상위 4행 = 55·54·53·52 전부 `success=true`.
- `docker ps` = api·worker 모두 `(healthy)`, 새 이미지 태그 확인.
- 배포 후 로그 `ERROR|Exception` = api **0건**, worker **0건**.
- worker env 반영 확인: `JOURNEY_REROUTE_ALERT_ENABLED=true`, `JOURNEY_NOTIFICATION_SENDER=fcm`.

### 미결
- **이제 앱 차례다.** 서버는 제안을 만들고 push 도 보내지만, 앱이 `origin` 계약(`transferIndex` 삭제)과 409 `REROUTE_ALIGHT_UNAVAILABLE`, `remainingStops` 전송을 처리해야 기능이 사용자에게 닿는다 — `docs/plan/journey-reroute-nextstop/HANDOFF.md`.
- **켠 뒤 볼 지표**: `journey.reroute.alert{outcome}`(`published` 대비 `no-channel`·`below-threshold`·`cooldown`), `journey.reroute.origin.window{outcome}`(`no-progress` 비율 = 앱이 `remainingStops` 를 보내는지), `journey.reroute.accept.midride{outcome}`.
- **알림 문구**는 `"더 빠른 길이 있어요 / 앱에서 새 경로를 확인하세요."` 로 라이브가 됐다. 이제 바꾸면 사용자 눈에 보인다.
- api 블록의 `JOURNEY_NOTIFICATION_RELAY_ENABLED=false` 는 남겨 뒀다(relay 가 worker 전용이라 무해). 정리는 별건.

## 2026-09-20 지하철 실시간 전 노선 커버리지 — 조사 실측 (백로그 등록, 착수 전)

### 확정
- **목표(사용자)**: 최종적으로 **수도권 모든 지하철 노선에 실시간 정보 제공**. 지금은 조사만 끝냈고 착수 전. 백로그 = `docs/plan/subway-realtime-full-coverage/BACKLOG.md`.
- 현행 소스 = 서울시 `realtimeStationArrival` 하나. 실제로 **19개 노선**이 들어온다(1001~1009, 1032 GTX-A, 1063 경의중앙, 1065 공항철도, 1067 경춘, 1075 수인분당, 1077 신분당, 1081 경강, 1092 우이신설, 1093 서해, 1094 신림). "1~9호선만"이 아니다.
- 실시간 미커버 = **노선 5개 98역**: 인천1(30)·인천2(27)·김포골드(10)·의정부경전철(16)·용인에버라인(15). 공통점은 서울시가 데이터를 받아오지 않는 운영사.
- 19개 노선 안에서 도착정보가 한 번도 안 온 역 = 5개(1호선 광명·서동탄, 경의중앙 운천·임진강, 경춘 광운대).
- 외부 API 조사 결론: **운영기관을 가로지르는 공식 실시간 오픈API는 없다.** 코레일 열차운행정보=과거 데이터(1일 전~3개월), 인천광역시 도착정보=버스 전용, 인천교통공사=실시간 *로그* 파일만, KRIC 오픈API 60종=정적(시간표/혼잡도/공기질), ODsay=실시간 없음. 단 **KRIC 철도통합정보시스템(station.kric.go.kr)은 수도권+지방 4대도시 실시간을 화면으로 이미 통합**해 뿌린다 — 데이터는 있는데 API가 없다.

### 함정
- **서울시 API 문서(`data.seoul.go.kr` OA-12764)의 "서울시 이외의 역구간은 미제공"은 실제와 다르다.** 수집 데이터에 신창·천안·춘천·문산·광교·연천·소요산 도착정보가 전부 쌓여 있다. 문서 문구를 근거로 "서울 밖은 안 된다"고 판단하면 오진한다 — 실측(`subway_arrival_raw`)이 먼저다.
- **실시간 갭보다 검색 갭이 더 크다(별개 축).** P1 bbox 필터(west 126.5/east 127.4)의 "경계 밖 정류장을 지나는 trip 전체 제외" 규칙 때문에 OTP graph 에서 노선 끝이 잘렸다: **공항철도 14→8역(인천공항1·2터미널·운서·영종·청라 없음 — 즉 지금 길찾기로 인천공항을 못 간다)**, 경강선 12→**0**(여주가 bbox 밖이라 노선 통째), 1호선 102→79(천안·신창), 경춘 25→14(춘천), 경의중앙 57→47(문산 이북). 외부 API 없이 bbox 조정+graph 재빌드로 고칠 수 있는 영역.

### 좌표
- 현행 수집: `SeoulSubwayApiAdapter:123`(`/realtimeStationArrival/ALL`), `:159`(`/realtimePosition` — 이쪽은 호선명 필요, `SubwayLineIdMapper.toLineName`).
- 라벨 재료(이미 있음): `OtpLegCoverageResolver` 의 `REALTIME_CAPABLE`/`STATIC_SCHEDULE`.
- 재현 쿼리·GraphQL 명령은 BACKLOG.md §8.

### 미결
- 착수 순서 제안 = ①검색 갭(bbox/graph) ②시간표 기반 정적 라벨로 미커버 노선 메우기 ③KRIC 스크래핑 스파이크 또는 운영기관 제휴. **사용자 결정 대기.**
- bbox 를 넓히면 graph 크기·heap·빌드시간이 는다(P1 capacity-report 기준 재산정 필요). 인천공항만 필요하면 west 126.5→126.4 정도의 최소 확장으로 될지 먼저 측정할 것.

## 2026-09-20 탑승 대기가 프론트에 안 내려가는 건 — 조사 + 승차추정 miss 사유 계측 (S)

### 확정 (조사 결과, 실측)
- **예측 모델 배선은 정상이다.** 프로덕션에서 여정 74 가 실제로 쓴 값으로 `/api/transfer/predict` 직접 호출: 앵커 +3분 → `source=REALTIME status=AVAILABLE waitSeconds=235`, 앵커 **+15분 → `source=MODEL status=AVAILABLE waitSeconds=270 modelVersion=lgbm_2026-06-12_to_2026-08-06_corrected_20260816T060405Z`**. 이 호출로 ml-serve `/predict` 카운터(role=api)가 올라가는 것까지 확인. 스펙도 맞다 — 모델 `route_categories` 146개에 `100100053`·`100100061` 포함, `ML_PREDICTOR_ENABLED=true`.
- **끊긴 자리는 여정 평가기다.** ml-serve `/predict` 는 **worker role 24시간 0건**. 평가기가 낸 예측 3건 전부 `journey_transfer_prediction_total{source="none"}`. 평가기 자체는 돈다(`claimed=3`, `duration{outcome=evaluated}=3`, `provider_slot{result=acquired}=3`, `failures=0`).
- **그래서 프론트에 null 이 간다.** `evaluator.enabled=true` 면 조회 경로는 계산하지 않고 저장된 값만 읽는다(`JourneySnapshotEtaComposer:132` → `StoredJourneyEvaluationReader.boardingArrival`). 저장이 비면 `CurrentLegArrival.unknown()` → `eta.currentLeg` 전부 null. DB 실측: `journey_leg.predicted_end_at` 이 WAIT_BUS 89행 중 3행(마지막 2026-09-08), WAIT_SUBWAY 0행.
- 여정 74 의 `transfer_connection`: `status=UNKNOWN source=NONE next_bus_arrival_at=NULL`, `user_ready_at=08:18:01Z`, `calculated_at=08:02:31Z` → 앵커까지 930초라 20분 창 안, 즉 `REALTIME_THEN_MODEL` 모드였는데도 모델 호출이 없었다.

### 함정
- **`source=none` 하나로는 원인을 못 가린다.** `TransferArrival.Status`(LIMITED / NO_VEHICLE / BLOCKED / STOP_MAPPING_FAILED / UNSUPPORTED_ROUTE …)가 메트릭 라벨에도, `transfer_connection` 컬럼에도, 로그에도 안 남아 있었다. 막혀서 못 물어본 것·물어봤는데 탈 차가 없던 것·애초에 정류장이 안 풀려 안 물어본 것은 **고칠 곳이 서로 다른데** 관측상 전부 같은 값이었다. 이번 PR 이 그 구멍을 메운다.
- 같은 입력을 지금 부르면 성공한다 — 즉 코드가 틀린 게 아니라 **평가기 조건에서만** 실패한다. 남은 후보 둘: ①평가기 예산 `predictDeadlineMs=1500`(검색 2500 보다 짧다), 워커가 16:28 재시작 후 17:02 첫 여정이라 콜드 경로였을 수 있음(지금 재보면 콜드 0.25s/웜 0.012s) ②그 순간 `NO_VEHICLE`.

### 좌표
- 신규 메트릭 `journey.transfer.boarding-miss{mode,reason}` — `JourneyEvaluationMetrics.recordBoardingMiss`.
- `BusBoardingTimeEstimator` — 예측 결과에 시각이 없으면 `reason=<status 소문자>` 로 세고 stopId/routeId/seq/status/source 를 INFO 한 줄. 물어보기 전 종료는 `no_anchor`·`no_scope`.
- `SubwayBoardingTimeEstimator` — `no_anchor`·`no_scope`·`no_way_code`·`no_arrival_rows`(조회가 비어 옴)·`no_boardable_train`(조회는 됐는데 앵커 이후 열차 없음).
- 읽는 법(PromQL): `sum by (mode,reason) (increase(journey_transfer_boarding_miss_total[1h]))`.

### 검증
- `./gradlew test`: **2181 tests, 1 failed, 2 skipped**. 실패 = `JourneyTransferAssessmentIT.assessmentIsPersisted` — **기존 무관**(P7 PR-1 때 baseline 확인한 그 실패).
- 대상: `BoardingTimeEstimatorTest`(버스 4사유 + 성공 시 0 + 지하철 3사유), `TransferTargetArrivalPredictorTest` 40건 green.

### 미결
- 위 기존 실패 IT 는 사실 같은 계열이다: 평가기가 켜져 있으면 조회 경로가 계산을 안 하는데, 그 IT 는 조회가 계산하던 시절 계약을 검사한다. 이번 스코프 밖 — 고치려면 IT 가 `journey.navigation.evaluator.enabled=false` 를 명시하거나 계약 자체를 다시 정해야 한다.
- 원인 확정은 **다음 여정 1건**이면 난다. 배포 후 `journey_transfer_boarding_miss_total` 의 reason 을 보고 ①예산이면 `predictDeadlineMs` 상향 ②`no_vehicle` 이면 정상(그 시각 그 노선에 차가 없던 것) ③`no_scope`/`stop_mapping_failed` 면 id 해석 문제로 간다.

## 2026-09-20 VPS 배포 — P7(#230·#231) + 승차추정 miss 계측(#235) 라이브

### 배포
- **완료. 이미지 `nextroute:20260920-173303-27cab2bf`**(이전 = `20260920-162800-6570fa29`, `.env` `PREVIOUS_*` 에 보존 → `./deploy.sh rollback-api|rollback-worker`). api·worker 둘 다 healthy, 기동 후 ERROR 로그 0건.
- 절차: 이 트리에서 `./gradlew bootJar` → jar 검사(마이그레이션 56개, V53·V54·V55 포함, 폴리라인 스택 클래스 0개, `journey.transfer.boarding-miss` 문자열 존재) → VPS `app.jar` 백업(`app.jar.bak-20260920-pre-p7metric`) → scp(md5 대조 b46dc650…) → `DEPLOY_REVISION=27cab2bf ./deploy.sh all`.
- **마이그레이션은 이번 배포에서 돌 것이 없었다.** VPS 는 이미 v55 였다 — 16:27 에 다른 세션이 올린 jar 이 (공유 작업트리에 있던) V53·V54·V55 를 함께 싣고 갔기 때문. 즉 **한동안 "테이블은 지워졌는데 코드는 그 테이블을 읽던" 상태**였다. 실해는 없었다(provider=OTP 라 폴리라인 경로를 안 타고, 수집 스케줄러 오류 로그도 0건). 이번 배포로 코드가 따라잡았다.
- 스모크: 검색 시청→강남 `HTTP 200 t=1.59s`, `meta.outcome=full degraded=[]`. OTP provider 정상.

### 함정 (이번 배포에서 실측으로 잡힌 것)
- **재시작 직후 첫 예측은 예산에 걸린다.** 배포 직후 같은 환승예측을 4번 연속 호출: 1회차 `status=LIMITED`(모델 호출 전 예산 소진) → 이후 `MODEL AVAILABLE` `t=0.187s → 0.024s → 0.036s`. 콜드 1회만 비싸고 그 다음부터 싸다.
- 이것이 [[2026-09-20 탑승 대기]] 조사에서 남겨둔 후보 ①(평가기 예산 `predictDeadlineMs=1500`)을 강하게 지지한다. 여정 74 는 **워커 16:28 재시작 → 17:02 첫 여정**이었고, 그 첫 평가가 콜드였다. 검색 예산(2500ms)에서도 콜드 1회차가 잘렸으니 1500ms 는 더 잘 잘린다.
- **확정은 아니다** — 다음 여정에서 `journey_transfer_boarding_miss_total{reason="limited"}` 가 실제로 오르는지 봐야 한다. 오르면 예산 상향 또는 워밍업(기동 후 1회 사전 호출)으로 간다.

### 검증
- `docker ps`: api·worker `(healthy)`, 이미지 `20260920-173303-27cab2bf`.
- `flyway_schema_history` 상위 = 55·54·53 전부 success(이번 배포에서 신규 적용 0건).
- 신규 메트릭은 아직 시계열 없음 — Micrometer 카운터는 첫 증가 때 등록된다. 여정 1건 지나야 보인다.

## 2026-09-20 계약 되돌림 — 검색 응답이 버스 도착예측을 직접 싣는다

### 확정 (계약 변경)
- **2026-09-09 의 "검색은 정적 드롭인, 실시간·예측은 프론트가 별도 API" 계약을 사용자가 취소했다.** ODsay 시절처럼 **서버가 예측을 응답에 한 번에 실어 내린다.** 이전 계약 문서(`docs/plan/otp-search-static-seoul-id/`)는 이력으로만 남는다.
- `RouteSearchService.runOtpSearch` 가 매핑 후 **ODSAY 분기와 같은 `TransferArrivalEnricher`** 를 탄다. 새로 만들지 않았다 — 두 분기가 다른 코드로 예측하면 같은 버스에 다른 값이 나온다.
- **`withRealtime` 플래그는 지킨다.** `search()`(공개 길찾기)만 보강하고 `searchWithoutRealtime()`(알림 STATIC, `RouteSearchTravelTimeEstimator:49`)은 그대로 정적이다.
- **버스 전용**(`trafficType==2`). 지하철 도착예측 모델은 없다 — 없는 값을 지어내지 않는다.
- **순서는 그대로.** enricher 는 값만 채운다. 취소된 것은 "예측을 싣지 마라"까지이고, **"서버가 경로를 골라 주지 마라"(재정렬·강등·MISSED 숨김 금지)는 그대로 유효**하다.
- **전체 검색 예산 3000 → 4500ms**(`route.search.deadline-ms`, `RouteSearchDeadlineProperties.deadlineMs`). 근거: OTP 호출 실측 0.9~2.0s + 보강 상한 1500ms. 3000 이면 보강이 상시로 잘려 `transferArrivals` 가 빈 채 `meta.degraded=[bus_realtime]` 만 남는다 — 값을 실으라는 계약을 못 지킨다.
- **모바일 직접호출 HANDOFF 는 폐기**(사용자 결정). `docs/plan/transfer-predict-mobile/HANDOFF.md` 삭제 — 통로가 둘이면 모바일이 어느 쪽을 따를지 갈린다. **`/api/transfer/predict` 엔드포인트 자체는 남긴다** — 여정 평가기·재탐색이 같은 `PredictTransferUseCase` 를 쓰므로 지우면 그쪽이 죽는다.

### 함정
- **enricher 를 mock 으로만 세워 둔 테스트가 조용히 깨진다.** OTP 경로가 보강 단계를 타기 시작하면 `mock(TransferArrivalEnricher.class)` 이 null 을 돌려주고, 그 null 이 `withMeta()` 에서 NPE 로 터진다(`RouteSearchServiceTest.TC_OTP선택_permit있으면...`). 고침 = 보강기를 **받은 결과를 그대로 돌려주도록** stub. 이 함정은 "보강 단계를 하나 더 붙일 때마다" 되풀이된다.
- enricher 가 요구하는 입력은 OTP 매핑이 **이미** 채우고 있었다(`SubPathResult.startLocalStationID`, `LaneResult.busLocalBlID`/`busNo` — `TransferArrivalEnricher:308,318`). 그래서 배선은 한 줄이었지 새 해석 코드가 필요하지 않았다. 9/9 에 id 를 채워 둔 작업이 그대로 재료가 됐다.

### 좌표
- `src/main/java/watoo/grd/nextroute/application/route/service/RouteSearchService.java` — `runSearch` 가 `withRealtime`·`searchStartedAt` 를 `runOtpSearch` 로 넘기고, 매핑 뒤 `stage(ctx, SearchComponent.BUS_REALTIME, ...)` 추가. javadoc 의 "실시간 보강은 타지 않는다" 문구 정정.
- `src/main/resources/application.yaml` `route.search.deadline-ms` + `RouteSearchDeadlineProperties.deadlineMs` = 4500.
- 계약 테스트: `RouteSearchServiceOtpWiringTest`(실시간 켬 → 보강 호출 / 끔 → 미호출 / 보강 빈손이어도 200·순서 유지), `RouteSearchDeadlinePropertiesTest`(예산이 OTP+보강을 담는지).

### 검증
- `./gradlew test`: **2185 tests, 1 failed, 2 skipped**. 실패 = `JourneyTransferAssessmentIT.assessmentIsPersisted` — **기존 무관**(PR #230 에서 baseline 확인).

### 배포
- 마이그레이션 없음. 배포 후 실측 3종 필요: ①잠실역 8호선→청계천 `paths[2]` 303번 구간의 `transferArrivals` non-null ②검색 지연 before(0.88s)/after ③`meta.degraded` 에 `bus_realtime` 이 상시로 오르는지(오르면 예산 재조정) + 검색 1건당 `ws.bus.go.kr` 호출 수가 상한(12) 안인지.

## 2026-09-20 VPS 배포 — 검색 응답 도착예측 라이브 (#238)

### 배포
- **완료. 이미지 `nextroute:20260920-180917-bf756257`**(이전 `20260920-173303-27cab2bf` 은 `.env` `PREVIOUS_*` → `./deploy.sh rollback-api|rollback-worker`). api·worker healthy, 기동 후 ERROR 로그 0건. 마이그레이션 없음.
- 절차: `./gradlew bootJar` → jar 안 `deadline-ms: ${ROUTE_SEARCH_DEADLINE_MS:4500}` 확인 → `app.jar.bak-20260920-pre-transfer-arrival` 백업 → scp(md5 b31dc518…) → `DEPLOY_REVISION=bf756257 ./deploy.sh all`.

### 검증 (잠실역 8호선 → 청계천, 프로덕션 실측)
- **`transferArrivals` 가 채워진다.** 환승 2번 경로에서 두 구간이 서로 다른 근거로 답했다:
  - 303번(6분 뒤 승차) → `source=REALTIME status=AVAILABLE waitSeconds=123 vehicleId=107017143`
  - 271번(32분 뒤 승차) → `source=MODEL status=AVAILABLE waitSeconds=124 modelVersion=lgbm_2026-06-12_to_2026-08-06_corrected_20260816T060405Z`
  - **실시간이 못 보는 먼 환승을 모델이 받는다**는 설계가 응답에서 그대로 보인다.
- **지연**: 배포 전 0.76/0.87/0.91s → 배포 후 10회 연속 **0.88~1.16s (중앙값 ~0.95s)**. 보강이 붙었는데도 +0.1s 안팎.
  - 단 배포 직후 표본에 **3.54s 1회**가 섞였다(콜드). 이후 10회는 전부 1.2s 이내.
- `meta.degraded` = 10회 모두 **빈 배열**. 예산 4500ms 상향이 유효했다(3000 이었으면 콜드 표본이 잘렸다).
- 검색 1건당 외부 호출: 10분간 `ws.bus.go.kr{role=api}` **8.3회** — 같은 OD 반복이라 15초 캐시·dedupe 가 대부분 흡수했다. 상한(12/검색) 근처도 안 간다.

### 함정
- **"보강 상한 1500ms = 지연 +1.5초"가 아니다.** 상한은 cap 이고 실제 비용은 버스 도착 API 평균 63ms + ML 100ms + wave 병렬(동시 8)이다. 실측 증가분은 ~0.1s. 예산을 정할 때 cap 을 비용으로 읽으면 과하게 잡게 된다.

## OTP 재탐색 IT (branch test/otp-reroute-it, 2026-09-27)

### 확정
- IT 2개로 나눔: `RerouteRemainingPathOtpLiveIT`(실 OTP, 행복 경로 1개) + `RerouteRemainingPathOtpIT`(MockWebServer, 출발시각·500·GraphQL errors·gate 포화). 실 서버로는 요청 body 를 꺼낼 수 없고 죽일 수도 없어서다
- 계획 하차점 원점의 OTP `when` = `transfer_connection.user_ready_at` (RemainingPathCandidateProvider#origins). ODSAY 에는 출발시각 칸이 없어 이 계약은 OTP IT 로만 검증 가능

### 함정
- `@MockBean OtpRoutingAdapter` 불가 ← `RoutingProviderSelector` 생성자가 부팅 때 `port.provider()` 호출, mock 은 null → `EnumMap.put(null)` NPE 로 컨텍스트 사망 / 가드: OTP 는 MockWebServer 로 대체
- `@MockBean OtpConcurrencyGate` 기본 반환 = `Optional.empty()` = **포화** ← Mockito 가 Optional 을 비워 준다 / 가드: setUp 에서 permit stub 필수
- 롯데백화점→고속터미널은 graph 가 **143번만** 줌(3 itinerary 전부, trip 만 다름). 계획 경로의 갈아탈 노선을 143 으로 두면 same-as-current 필터로 후보 전멸 / 가드: 계획 노선은 가짜 721
- OTP id 가 서울 id 로 안 풀려도 에러 없음 ← `OtpSeoulIdentityResolver` 가 bus_stop/bus_route 존재검증 실패 시 empty → 후보 조용히 "예측 없음" / 가드: live IT 단언을 "예측이 서울 routeId 로 불렸다"까지 내림
- live IT 시드 id(101000041·121000020·100100022)는 graph `g4e098383` 실측값. graph 재빌드 시 깨질 수 있음(의도된 감지)

### 좌표
- 원점 departAt: `RemainingPathCandidateProvider.java` origins() — 계획 하차점 = userReadyAt
- OTP 요청 body `when`: `OtpRoutingAdapter.java` requestBody() — Asia/Seoul OffsetDateTime
- 서울 id 해석: `OtpSeoulIdentityResolver.java` resolveBus()

### 검증
- `./gradlew compileTestJava` exit=0 (경고=@MockBean deprecation, 기존 무관)
- 기준선: `RerouteRemainingPathIT` 4/4 실패 — `Connection to localhost:5432 refused` (로컬 Postgres 미기동, 기존 무관·환경)
- `./gradlew test --tests RerouteRemainingPathIT --tests RerouteRemainingPathOtpIT` → 4/4 + 4/4 통과 (로컬 Postgres 기동 후. 앞의 5432 거부는 해소)
- 뮤테이션: `RemainingRouteSearchService.loadOtp` 의 departureAt 을 `now` 로 고정 → 출발시각 테스트 실패(`[지금이 아니다] -0.129804S`), 나머지 3개 통과. 원복 확인(git diff 없음)
- `RerouteRemainingPathOtpLiveIT` 터널 없이 실행 → skipped=1 (@EnabledIf 정상)

### 함정 (추가)
- `localhost:18080` 은 로컬 OTP 가 아니라 **VPS 운영 OTP 로 가는 SSH 터널**이다 ← VPS `nextroute-otp-1` 이 `127.0.0.1:18080` 에만 바인딩. 터널이 닫히면 live IT 는 조용히 skip / 가드: 돌릴 때 `ssh -N -L 18080:127.0.0.1:18080 vps` 로 명시적으로 연다. live IT 는 **운영 OTP 에 읽기 질의**를 보낸다는 점을 알고 돌릴 것
- 실호출(터널 경유 VPS 운영 OTP): `RerouteRemainingPathOtpLiveIT` 1/1 통과, 1.46s. 운영 OTP 에 경로 질의 1건
- 뮤테이션: `OtpSeoulIdentityResolver.resolveBus` 가 empty 반환 → live IT 실패 `Wanted but not invoked ... "100100022" ... zero interactions` (노린 "조용한 예측 누락" 그대로). 원복 확인
- ~~시드를 빼면 live IT 가 실패한다~~ 취소: 로컬 DB 에 실 정적표가 있다(bus_stop 12,981 / bus_route 729, 143번·두 정류장 포함). 시드는 `on conflict do nothing` 이라 이 DB 에선 아무것도 안 넣고, 빈 DB(CI 등)에서만 효과가 있다

### 함정 (추가 2)
- live IT 가 터널이 열려 있어도 skip ← `@EnabledIf` 가드의 JDK HttpClient 가 기본으로 h2c 업그레이드를 붙이고, OTP 가 그 POST 를 받다가 멈춤(curl `--http2` 도 status=000 타임아웃) / 가드: `HttpClient.Version.HTTP_1_1` 고정. 2026-09-17 `JdkClientHttpRequestFactory` POST 멈춤(PR #204)과 같은 계열일 가능성 — 미검증

## 지하철 위치 수집기 "16/16 노선 실패" (branch fix/subway-position-no-trains, 2026-09-29)

### 확정
- 원인은 호출 한도가 아니다. 서울 위치 API 는 **"데이터 없음"(INFO-200)을 errorMessage 없이 최상위 봉투**로 준다: `{"status":500,"code":"INFO-200","message":"해당하는 데이터가 없습니다.",...}`. 코드는 `errorMessage.code` 만 읽어 `code=null` → `isSuccess=false` → `ApiCallBlocker` 가 그 노선을 **자정까지** 차단했다
- 열차 없는 노선은 매일 생긴다: 막차 뒤(00:0x~00:5x)와 첫차 전(수집 시작 04:30). 그래서 매일 전 노선이 05시 전후로 막혀 수집기가 하루 종일 죽어 있었다. 9/28·9/29 VPS 로그로 같은 패턴 확인(9/29: 00:35~00:53 5개, 04:30:03 10개, 05:11 1092)
- 운영 키 자체는 정상: 2026-09-29 VPS 에서 worker 키로 2호선 호출 → `INFO-000`, 47편성
- 수정: INFO-200 = `SubwayPositionFetchResult.noTrains()`(COMPLETE, 0행, 차단 없음). 추적기는 "안 보임"을 출발로 읽지 않으므로 빈 COMPLETE 는 사건 0건으로 끝난다. 최상위 code/message 도 읽어서 한도 초과(ERROR-337)가 로그에 코드째 남는다
- 로그: 차단 중이라 안 부른 노선(`isBlocked()`)은 수집기 요약의 "실패"로 세지 않는다. 차단은 걸릴 때 `[ApiBlocker] BLOCKED` 로 한 번 남는다. 9/29 하루 3,230줄이던 `16/16 노선 실패` 가 사라진다

### 함정
- 9/21 의 "한도 초과"와 이번 증상이 로그상 똑같이 `code=null` 로 보였다 ← 두 응답 모두 최상위 봉투라서 / 가드: `SubwayPositionResponse.code()` 가 최상위도 읽음 + 테스트 `topLevelErrorCodeIsRead`
- 도착정보 응답(`SubwayApiResponse.isSuccess`)도 같은 errorMessage 전용 판정이다. 도착 쪽은 차단을 걸지 않아 피해가 없어 이번 범위에서 뺐다(미결)

### 좌표
- 판정: `SubwayPositionResponse.java` code()/isNoData()
- 분기: `SeoulSubwayApiAdapter.java` getRealtimePosition() — isNoData 가 isSuccess 보다 먼저
- 요약 로그: `RealtimeSubwayPositionCollector.java` collect()

### 미결
- 진짜 오류(5xx 등 일시 장애)도 여전히 자정까지 차단된다. 한 번의 일시 오류로 그 노선 하루가 죽는 정책은 그대로다 — 별건 판단 필요
- `SubwayApiResponse`(도착정보) 최상위 봉투 미처리

### 배포
- worker 코드 변경. VPS 배포 필요(마이그레이션 없음). 배포 전까지 매일 05시 전후로 전 노선 차단이 되풀이된다

### 검증
- RED 확인: 어댑터 테스트 3개 실패(`expected COMPLETE but was FAILED`, 두 번째 호출 `BLOCKED`, `expected "ERROR-337" but was null`), 수집기 테스트 2개 실패(차단 노선을 실패로 셈, `2/2`)
- GREEN: `SeoulSubwayApiAdapterPositionTest` 5/5, `RealtimeSubwayPositionCollectorTest` 3/3
- 지하철 단위 스위트 177개 통과. 같은 실행의 IT 82개 실패는 기존 무관(환경): 로컬 Postgres `Connection refused` + Docker 미기동 `Could not find a valid Docker environment`. 모두 컨텍스트/컨테이너 기동 단계 실패, 단언 실패 0

## 재탐색 후보 경로 조회 + 하차 좌표 (#259, branch feat/reroute-candidate-route, 2026-10-06)

### 확정
- 탑승 구간은 `ride` 칸 분리(b). path 앞 합성(a)은 path 가 수락 원천과 달라져 기각(사용자 결정)
- `rideSec` 대신 `alightAt` — GET 은 제안 생성 뒤 불려 초 단위 값은 이미 낡음(사용자 승인 계약)
- `path` 는 저장된 `RemainingPathApply.path` 를 가공 없이 반환. 타입이 검색 `paths[]` 원소와 같은 `PathResult` 라 모양이 자동으로 같다
- 후보 읽기·stale 판정을 `RerouteCandidateReader` 로 추출해 수락·조회가 공유. 조회만 status≠ACTIVE·여정 비활성을 추가로 STALE 처리(수락은 status 를 CAS 로 본다)
- 조회는 validate-on-read(지나간 하차점) 안 함 — 목록이 맡고 수락은 `REROUTE_ALIGHT_UNAVAILABLE` 로 막음
- 좌표 출처 = `Origin.waypoints().origin()`(검색 출발점과 같은 값). 계획 하차점=대기 leg 시작, 중간 하차=`RerouteOriginStop.x/y`
- 선택항목3(능동 제안 테스트 완화) 서버 변경 없음, 앱 개발 빌드가 요청 진입점 유지

### 함정
- ride 의 routeName 을 대기 leg 에서만 찾으면 안 됨 ← 주행 leg 에도 `selectedRouteOptionId` 가 들어간다(`RerouteAcceptTxService.select`) / 주행 leg 값 그대로 사용
- 클론에서 IT 가 `password authentication failed for user "${POSTGRES_USER}"` 로 전멸 ← `env/` 는 `.example` 이 추적되는 실제 디렉터리라 `ln -s .../env env` 가 안 먹음 / 무시 대상 파일(`env/db.env` 등)을 개별 링크

### 좌표
- 조회: `RerouteCandidateRouteQueryService.java` get()/ride()/stopsUntil()
- 공용: `RerouteCandidateReader.java` read()/isStale()
- 좌표 주입: `RemainingPathCandidateProvider.java` toCandidate()
- API: `JourneyRerouteProposalController.java` candidateRoute()

### 배포
- 마이그레이션 없음. api·worker 재빌드만
- 저장 JSON(`reroute_proposal.candidates`)에 `alight.x/y` 추가 — 커스텀 Jackson 설정 없음(Spring Boot 기본 FAIL_ON_UNKNOWN_PROPERTIES=false)이라 배포 중 옛 이미지가 새 행 읽어도 무시
- 모바일 인계: `docs/plan/reroute-candidate-route/HANDOFF.md`

### 검증
- `./gradlew compileJava compileTestJava` 통과
- 단위: `RerouteCandidateRouteQueryServiceTest` 15/15, `RerouteCandidateContractTest` 11/11(신규 2), `RemainingPathCandidateProviderTest` 21/21, `JourneyRerouteProposalControllerTest` 13/13(신규 4)
- IT(로컬 postgis 컨테이너): `RerouteAcceptIT` 14/14(신규 3: 같은 원천·ride·STALE)
- 회귀: journey 패키지 전체 1156개 중 실패 1 = `JourneyTransferAssessmentIT` "환승이 있는 여정을 조회하면 판정이 저장되고…" — **기존 무관**(변경 stash 한 main 상태에서도 동일 실패)

## 2026-10-07 경기 버스 형상·정류장 GBIS 전면 교체 (L, docs/plan/gyeonggi-gbis-routes)

### 확정
- 운영 전국 그래프 형상(shaped zip sha 62a8fb2d) vs GBIS `getBusRouteLineListv2` 887노선: 50m 내 ≥95% 3% / ≥85% 62% / ≥60% 27% / <60% 7%. 서울 bbox(9/17) 형상과 등급 884/887 동일 → 전국 그래프도 같은 pfaedle 결과.
- 정류장 표본 138노선: 순서 완전 동일 8%, 집합 Jaccard 중앙 0.92, <0.5 16%.
- **GBIS 정류장 목록은 통과 지점을 함께 준다 — 이름이 `…(경유)` 로 끝나고 mobileNo 공백**(G7426·M6410·G1690 확인: IC·JC·대교 북단 등). 이것만 제외하면 실제 정차. 서울 쪽 정류장(신사역(중) 등)은 mobileNo 공백이어도 정차 → mobileNo 로 거르면 안 됨.
- GBIS stationId 9자리 = 우리 stop_id `BS_TAGO_GGB<9>` (서울·인천 정류장도 같은 규칙으로 들어있음) → id 교체 없이 실시간 그대로.
- 일부 노선은 형상은 있는데 정류장 목록이 resultCode 4(결과 없음), 예 M7111(229000102) → 형상만 교체, 정류장 유지.
- 운영 OTP 는 `compose.yaml` + override `/root/apps/observability/otp/compose.otp-national.yaml`(graph 마운트·GC 로그) 두 파일로 뜬다 → 배포 시 둘 다 graphVersion 경로 교체(9/30 절차, docs/plan/national-otp-shapes/PLAN.md Phase 6).

### 함정
- 왕복 선 투영 후보를 "인접 선분 묶음" 으로 고르면 회차점 근처에서 갈 때·올 때가 한 묶음으로 합쳐져 귀로 정류장이 출발 쪽에 붙는다 ← 회차점에서 두 통과가 연속 선분 / 가드: 후보를 measure 50m 창 안 최근접으로 고름(test_return_leg_stations_project_on_return_leg).
- id 가 안 맞을 때 좌표 300m 폴백이 "반경 안 아무 정류장" 을 고르면 구간이 짧게 잘림 ← 점수에 거리 없음 / 가드: 점수 = (공통 정류장 수, -끝점 거리합, -길이차) (test_coordinate_fallback_prefers_nearest_station_over_any_within_radius).
- 이전 비교에서 `_U/_D` 접미사 노선을 `rid[-9:]` 로 잘라 누락 → 정규식 `(\d{9})(_[UD])?$` 로 9자리 추출.

### 좌표
- tools/transit-routing-benchmark/runner/fetch_gbis_routes.py — 캐시 수집(resultCode 0/4 는 재호출 안 함, flock)
- tools/transit-routing-benchmark/runner/apply_gbis_routes.py — `choose_span`(trip→GBIS 구간), `monotone_measures`(DP 단조 투영), `synthesize_times`(LCS 앵커+거리 보간), `run`(zip 재작성)
- tools/transit-routing-benchmark/runner/build_otp_graph.sh — `--from-shaped-zip`, `--gbis DIR`(step 2b), 출력 `*-shaped-gbis.gtfs.zip`(원본 zip 보존)

### 검증
- `python3 -m unittest runner/test_apply_gbis_routes.py` → Ran 10 tests OK.
- 부분 캐시(1,594노선) 시험 실행: 3m14s, peak 7.0GB. rewritten 48,913 / shapeOnly 1,877 / 구간 실패 56. 정류장-형상 잔차 p50 8.8 / p90 17.2 / p99 25.1m. 최대 12.8km 는 shapeOnly(옛 정류장이 개편된 GBIS 선에서 멂) — GBIS 정류장 쪽 200m 초과 0건.

### 함정 (전량 실행 후 추가)
- **비교 도구 버그로 초기 수치가 과장됐다(정정)**: 스크래치 compare.py 의 격자 최근접 탐색이 링을 넓힐 때 가운데 칸 최솟값을 버려 거리를 키웠다. 고친 뒤 기존 pfaedle 형상 vs GBIS(887노선): 50m 내 ≥95% **67%** / ≥85% 18% / ≥60% 9% / <60% 6% (처음 보고 3/62/27/7 은 틀림). 교체 후 형상: ≥95% 96%.
- 순환 노선을 GTFS 와 GBIS 가 다른 정류장에서 시작하면 첫·끝 id 가 GBIS 의 이웃 두 정류장에 맞아 구간이 정류장 2개짜리가 된다(216000110, 649m) ← 끝점 id 만으로 구간 선택 / 가드: 구간이 공통 정류장 절반도 못 덮고 trip 이 왕복이면 GBIS 전체 순환(`loopRotated`, 264 trip).
- 왕복 판정을 trip 이 쓰는 shape 로 하면 짧은 회차 trip(정류장 3개)이 54정류장 순환 전체로 늘어난다 ← 같은 왕복 shape 를 공유 / 가드: trip 자신의 첫·끝 정류장 300m 이내만 왕복 + 새 구간 길이가 옛 shape_dist 의 1/2.5~2.5배(+1km) 밖이면 정류장 유지·형상만 교체(85 trip).
- 순환에서 같은 정류장이 두 번 나오면 공통 정류장 수를 중복으로 세어 구간이 옛 trip 끝을 넘어간다 → 넘어간 정류장이 전부 마지막 시각으로 뭉침(끝 4정류장 동일 시각 478 trip) / 가드: 공통 수는 서로 다른 id 만 + 옛 첫·끝 정류장이 새 목록 중간에 맞으면 고정점에서 제외.
- 시각 고정점을 id 만으로 잡으면 다른 통과(왕복의 반대편)에 맞은 정류장이 75초에 14km 를 가게 만든다(경기 hop >108km/h 38,115·0초 6,269) / 가드: 고정점은 앞 고정점에서 오는 속도·끝까지 가는 속도 모두 ≤25m/s, trip 전체 평균 >25m/s 면 형상만 교체. 결과 >108km/h 271(옛 피드 6,426), 0초 0.

### 검증 (전량, 최종 코드)
- `python3 -m unittest runner/test_apply_gbis_routes.py` → Ran 16 tests OK.
- GBIS 수집: 3,045노선 line 3,045 ok / stations 2,725 ok, 320 resultCode 4(정류장 없음 → 형상만 교체), error 0. 호출 약 5,050.
- apply: 87,235 경기 trip → 정류장+형상 교체 84,091 / 형상만 3,077 / 무변경 67(투영 구간 0). 정류장 추가 1,008, 좌표 이동 1,093. 정류장-형상 잔차 p50 8.5 / p90 16.6 / p99 24.4 / max 139m. 형상만 교체 중 옛 정류장이 150m 넘게 떨어진 노선 44개(GBIS 정류장 목록 없음 — OTP 가 ShapeGeometryTooFar 로 직선 처리).
- 경기 외: stop_times 1,302만 행·shapes 1,741만 행 해시 동일, routes/agency/calendar/transfers 바이트 동일. 시각 역행 0. validate_gtfs.py error_count 0.

### 배포
- 마이그레이션 없음, 앱 이미지 재빌드 없음(형상 설정은 compose env).
- ✅ VPS `/srv/nextroute/otp/national-nojeju-202503-cal20260729_20261130-otp278-shaped-gbis-g71dca926c27c/` 에 graph.obj(sha 4b55ecb1…, 912,570,783 B)·coverage-index.json(sha 638411c6…) 배치, 양쪽 sha 일치 확인(2026-10-07 05:36).
- ~~⏳ compose 치환·재생성은 에이전트 권한에서 막혀 사용자 실행 대기~~ 사용자 권한 허용 후 2026-10-07 12:56 실행. 치환 대상: `compose.yaml` 4곳 + `/root/apps/observability/otp/compose.otp-national.yaml` 1곳(옛 graphVersion `...-shaped-g035004114e7b`). OTP 는 두 파일로, api·worker 는 compose.yaml 만으로 재생성(.env 이미지 = 현재 실행 이미지 확인함).
- 로컬 그래프 빌드가 머신 메모리 경합(다른 작업·시뮬레이터)으로 스왑 → 1h24m(평소 11분). 빌드 전 메모리 확인.
---

## 2026-10-07 regional-bus-route-catalog (#267) — 경기·TAGO 노선 정류장 표 + TAGO 노선 단위 도시코드

플랜 = `docs/plan/regional-bus-route-catalog/{PLAN,SUMMARY}.md`. 브랜치 `feat/tago-route-city-mapping`(fresh main 493af781, 스크래치 clone 에서 작업).

### 확정
- TAGO `getRouteNoList?cityCode=` 를 `routeNo` 없이 부르면 도시 노선 전체. 138개 도시 → 21,393노선, **도시 간 중복 0** → routeId → cityCode 는 함수. 양양군 0건.
- **경기 노선도 TAGO 목록에 `GGB` 로 있다(2,158, 31개 시)** → 노선 목록을 OTP 에서 가져올 필요 없음. 경기 정류장은 경기 API 로(경기 실시간과 같은 순번 체계). 표의 경기 `route_id` 는 접두사 뗀 9자리.
- 폐선 정리 기준 = "이번 노선 목록에 없는가". ~~"loaded_at < 실행 시작"~~ 취소 — 0건·실패 노선의 옛 값까지 지워진다(플랜 초안의 실패율 1% 조건도 함께 폐기). 목록은 한 번이라도 실패하면 실행 전체를 멈추므로 정리는 온전한 목록일 때만 돈다.
- 도시코드 조회 = 노선 표 → 없으면 `TagoCityCodes`(접두사) 폴백. 표가 비어도(V56 미배치 포함) 지금과 같은 동작. `TagoCityCodes` 는 안정화 뒤 별건 삭제.
- 정류장만 보는 자리(`BusRealtimeStopIds`, 노선 모름)는 "그 접두사의 실시간을 물을 수 있나"만 본다 = 표 접두사 ∪ 접두사 표. 실제 호출은 노선으로 도시를 찾는다.
- 원천 어댑터는 `ApiCallBlocker` 를 건드리지 않는다 — 노선정보 서비스의 미등록·한도 초과가 도착 조회를 막으면 안 된다.

### 함정
- TAGO 노선 id 가 숫자만이 아니다: `BSB5201002F00`(영문) 등 OTP 에도 9개. `BusArrivalQueryService.TAGO_ID`(`[\d-]`)·`OtpSeoulIdentityResolver.TAGO_BUS_ROUTE` 가 이걸 조용히 버려 부산 9개 노선 실시간이 빠지고 있었다 ← 정규식 `[\dA-Z-]` 로 넓힘(서울·경기 id 는 숫자뿐이라 안 겹침). 정류장 id 는 실측상 숫자뿐이라 그대로.
- 기존 접두사 표의 `ADB`(2도시)·`YDB`(3도시)·`DJB`(대전/계룡)·`WJB`(원주/횡성)는 다도시 — 두 번째 도시 노선은 틀린 cityCode 로 호출돼 빈 결과였을 가능성(미검증, 배포 후 실호출 확인 항목).
- 처음 보는 접두사 `CCB`·`ISB` — 접두사 표에도 미확정 목록에도 없었다.
- `BusRealtimeStopIds` 를 static 유틸에서 빈으로 바꿈 → 유일 호출자 `JourneyNavigationGeometryService` 생성자 인자 +1(시험 1곳 갱신).
- `OtpSeoulIdentityResolver` 생성자 인자 +1 → 시험 3곳(`OtpSeoulIdentityResolverTest`, `LegacyRouteResponseMapperTest`, `RouteResponseMapperTest`) 갱신. 시험용 대역 `testsupport/InMemoryRegionalBusRouteStopStore.prefixTableOnlyLookup()`.

### 좌표
- 적재: `application/regionalbus/service/RegionalBusCatalogLoadService.java`
- 원천: `infrastructure/adapter/out/api/regionalbus/RegionalBusRouteSourceAdapter.java`
- 저장: `infrastructure/adapter/out/persistence/regionalbus/JdbcRegionalBusRouteStopStore.java`
- 조회: `application/route/mapping/TagoCityCodeLookup.java`
- 스케줄러: `infrastructure/adapter/in/scheduler/RegionalBusCatalogScheduler.java`(worker, `REGIONAL_BUS_CATALOG_ENABLED`, 매월 1일 03:30 KST)

### 미결
- TAGO 경유 가상점 판별(`waypoint` null). #264 TAGO `arrprevstationcnt` 실측 후.
- 적재 소요 실측(예상: 약 2.15만 호출, 호출당 약 0.94초 직렬 → 병렬 3 이면 약 2시간).

### 배포
- **V56 VPS 수동배치 필요**(V18+ untracked convention). 운영은 v55.
- 순서: V56 → 이미지(api·worker) → worker env `REGIONAL_BUS_CATALOG_ENABLED=true` → 부팅 시 표가 비어 있어 1회 적재(별도 스레드) → 완료 로그 `[RegionalBusCatalog] routes=… loaded=…`.
- api 는 적재를 안 하므로 6시간 주기 갱신으로 반영(즉시 원하면 api 재시작). worker 는 적재 직후 갱신.
- 확인: TSB 정류장 도착 실호출, ADB·YDB 두 번째 도시 노선 실호출, 부산 `BSB5201002F00` 실호출.

### 검증
- 대상 시험: `RegionalBusCatalogLoadServiceTest` 7/7, `RegionalBusRouteSourceAdapterTest` 9/9, `JdbcRegionalBusRouteStopStoreIT` 5/5(Testcontainers postgres:17, 실제 V56 실행), `TagoCityCodeLookupTest` 6/6, `BusArrivalQueryServiceTest` 17/17(신규 2), `OtpSeoulIdentityResolverTest` 19/19(신규 2), `JourneyNavigationGeometryServiceTest`·`LegacyRouteResponseMapperTest`·`RouteResponseMapperTest` 통과
- 전체 `./gradlew build`: 2,305건 중 실패 11 — **전부 기존 무관**. 같은 4개 클래스(`JourneyEvalReadPathIT`·`JourneyEvaluationIT`·`JourneyTransferAssessmentIT`·`BusAlertWakeupBurstIT`)를 main(493af781)에서 같은 환경으로 돌려도 똑같이 11건 실패.
- 실행 환경(함정): 스크래치 clone 에는 gitignore 대상 `env/*.env` 가 없어 컨텍스트 IT 가 DB 접속에서 전부 죽는다(259건). 공유 로컬 DB·Redis 를 건드리지 않으려고 일회용 컨테이너를 띄웠다 — postgis/postgis:17-3.5(55432) 에 로컬 개발 DB 의 `--schema-only` + `flyway_schema_history` 만 복원(빈 DB 에 V1 부터 재생 불가), redis:7(56379), 기본값 없는 env(`GONG_API`·`KAKAO_REST_API_KEY`·`SEOUL_*`)는 더미. `env/*.env` 를 통째로 source 하면 `fcm.env` 가 FCM 을 켜 `/secrets/...` 자격증명 부재로 부팅이 죽는다 — DB·Redis 변수만 넣을 것.
## 재탐색 주기 보드 PR-1 — 보드 저장 모델 + 병합 규칙 (#264, branch feat/reroute-periodic-board, 2026-10-07)

### 확정
- 보드 = 개정본당 ACTIVE `reroute_proposal` 한 행을 제자리 갱신. 봉투 v2 `{version:2, slots}`, 사건 기반 경로는 v1 쓰기 그대로(`slots` 는 NON_NULL 로 빠져 v1 행 모양 불변)
- 읽기는 판 분기: `isReadable()`(v1|v2) + `visibleCandidates()`(v1 전체 / v2 VISIBLE 슬롯). v2 를 "현재 판"으로 올리면 플래그 off 에서도 v1 행이 전부 "없음"이 된다(리뷰 B4)
- 보드 저장 시 `base_command_version` 을 현재 값으로 다시 찍는다 — 안 하면 구간 전이 한 번 뒤 수락이 영구 409(리뷰 B2, IT 로 고정)
- 저장 순서 세션 FOR UPDATE → 제안(수락과 같음). `stillCurrent` = ACTIVE·commandVersion·revision·currentLegIndex 일치
- 병합 규칙: 문턱 120/180/+60·대기, 같은 지문=숫자 갱신+id 유지, 다른 지문=+60 이상만 교체(아니면 기존 그대로), 숨김 즉시, 재표시 +30, 60초 미만·passed 제거, 언급 없는 자리=유지
- 근거(realtimeOnly) 집계는 PR-3. 그 전엔 부르는 쪽이 false → 180초(보수)
- 보드 설정은 `journey.reroute.board.*` 전용 키. 기존 키 값 불변(리뷰 M12)

### 함정
- `@Sql` 로 마이그레이션을 직접 나열하는 IT 3개(`RerouteProposalStoreIT`·`RerouteProposalExpiryIT`·`JourneyRerouteProposalSchemaIT`)는 새 마이그레이션을 수동 추가해야 한다 ← 엔티티에 칼럼 매핑 추가 시 `column ... does not exist`
- `WithdrawalDeletionBoundaryIT` 는 ddl-auto 스키마라 원시타입 칼럼이 default 없는 not null ← raw insert 에 `board_version` 손으로 채움(V53 관례). PR-2 에서 journey_session 칼럼을 엔티티에 매핑하면 같은 처리 필요
- 로컬 DB 마이그레이션 순서: V57 이 #268 의 V56 보다 먼저 로컬에 적용되면 나중에 V56 이 out-of-order 로 막힌다 ← IT 실행 동안만 #268 의 V56 파일을 작업트리에 untracked 로 둠(커밋 제외). 로컬 DB 는 이제 V56(#268 내용)·V57 적용 상태
- #268 의 provider 코드는 `GG`/`TAGO` (PLAN 의 GYEONGGI 아님) — PR-4 에서 맞출 것

### 좌표
- 병합: `RerouteBoardMerger.merge`
- 저장: `RerouteBoardService.write` / `RerouteProposalRepository.insertBoard`(ON CONFLICT 부분 unique)·`updateBoard`(board_version CAS)·`findStatusById`
- 판 분기: `RerouteCandidateEnvelope.visibleCandidates`, `RerouteCandidateReader.read`, `RerouteProposalTxService.toView`

### 배포
- **V57 VPS 배치 필요(jar 포함 확인). 순서 #268(V56) → 이 PR(V57).** #268 보다 먼저 머지·배포 금지
- 플래그 `JOURNEY_REROUTE_BOARD_ENABLED` 기본 false. PR-1 은 호출부가 없어 동작 변화 없음

### 검증
- `./gradlew compileJava compileTestJava` 통과
- `RerouteBoardMergerTest` 23/23, `RerouteCandidateContractTest` +3(v1 모양 불변·v1 읽힘·v2 VISIBLE 만)
- `RerouteAcceptIT` 20/20(+6: 제자리 갱신·전이 후 수락 200·stale 버림·v1 대체·숨김 자리 선택 불가·수락 후 늦은 갱신 버림)
- 전체 `./gradlew test`: 2306 중 실패 2 = `JourneyTransferAssessmentIT`·`BusAlertWakeupBurstIT` — **기존 무관**(clean main 493af781 클론에서 동일 실패 확인)

## 재탐색 주기 보드 PR-2 — 갱신 스케줄러 + 시점 신호 (#264, branch feat/reroute-board-refresh, 2026-10-07)

### 확정
- **V58 추가(PLAN 에 없던 것)**: `journey_session.reroute_refresh_claimed_until`. V57 의 `reroute_refresh_at` 하나로는 "선점 lease"와 "다음 예정"을 가를 수 없다(둘 다 미래 시각). 선점 중 신호가 예정을 당기면 다른 워커가 같은 여정을 다시 선점해 검색을 두 번 돈다 → 선점은 claimed_until 로만, 선점 중 신호는 `reroute_refresh_dirty` 로만
- 마무리 CAS: `claimed_until = :lease` 일 때만, dirty 면 now+30s 아니면 now+60s. 갱신이 터져도 finally 로 마무리
- 신호 자리: 진행 보고(remainingStops 감소, 첫 보고는 시드), 구간 전이(`JourneyCommandService`, 상태기계 아님), 여정 시작(+60s, 이미 있으면 유지), 수락(accepted_at + 즉시), 고정 열차 역 통과(`SubwayRingState.advancedFrom`), 위험 하락(보드 모드 방아쇠 → `requestUrgent` 최소간격 우회, alertPublisher 호출 안 함)
- 보드 모드 사용자 요청: 적격성 통과 후 v1 제안 대신 보드 동기 갱신 + findActive
- 후보 원천은 임시로 기존 공급자(다음 환승 기준). 근거 false(180s), 대기 증가 0. 문턱 미만 숨김·환승 없음·걷기/대기 기점은 PR-3
- 지하철 진행 보고에도 남은 역 수를 받는다(결정 3-가) — 서버는 원래 막지 않았고 문서 계약만 바꿈
- 전용 실행기 `rerouteBoardRefreshExecutor`(permits 3, queue=batch), 평가기 슬롯 비공유. 스케줄러·실행기 모두 worker + 보드 플래그 조건부 빈

### 함정
- native `CASE`/`LEAST` 안의 파라미터가 타입 없이 text 로 추론 → `column ... is of type timestamp with time zone but expression is of type text` ← 모든 파라미터에 `CAST(... AS timestamptz|boolean|integer)`
- 신호가 트랜잭션 밖(방아쇠 평가기·링 갱신)에서 불리면 `@Modifying` 이 "No EntityManager with actual transaction" ← 신호 메서드 `@Transactional(REQUIRED)`. REQUIRES_NEW 금지: 수락·명령은 이미 세션 행을 잠근 트랜잭션이라 같은 행을 새 트랜잭션으로 쓰면 자기 잠금에 걸린다
- 신호는 예외를 삼키므로(사용자 경로 보호) 쿼리 오류가 조용히 묻힌다 — 이번 두 함정도 IT 단언이 아니었으면 몰랐다. 신호 SQL 은 IT 로만 검증 가능

### 좌표
- 선점·마무리·신호 SQL: `JourneySessionRepository.claimRerouteRefreshBatch / finishRerouteRefresh / requestRerouteRefresh / scheduleRerouteRefreshIfAbsent / markRerouteAccepted`
- `RerouteRefreshSignal`, `RerouteBoardRefreshClaimService`, `RerouteBoardRefreshService`, `RerouteBoardRefreshScheduler`, `RerouteBoardRefreshPoolConfig`, `RerouteRouteFingerprint`

### 배포
- **V58 VPS 배치 필요(jar 포함 확인). 순서 V57 → V58.** 보드 플래그 off 라 동작 변화 없음(신호 no-op, 스케줄러 빈 없음)

### 검증
- 단위: `SubwayRingStateAdvanceTest` 3/3, `RerouteBoardRefreshSchedulerTest` 2/2, `RerouteTriggerEvaluatorTest` 9/9(+1), `RerouteProposalServiceTest` 18/18(+1)
- IT: `RerouteAcceptIT` 27/27(+7: 플래그 off 무쓰기, 최소간격·우회, 선점 중 dirty·재선점 불가, 마무리 lease CAS, 시작 +60s, 정류장 감소 신호, 갱신 1회가 v2 보드 행 남김)
- 전체 `./gradlew test` 2351 중 실패 2 = `JourneyTransferAssessmentIT`·`BusAlertWakeupBurstIT` — 기존 무관(PR-1 에서 clean main 동일 확인)

## 재탐색 주기 보드 PR-6 — API 계약 + HANDOFF (#264, branch feat/reroute-board-api, 2026-10-07)

### 확정
- 보드 모드 `/active` 는 **DB 에 쓰지 않는다**: resolveStale·expireValidatedOut 생략. 응답에서만 거름(개정본 불일치·만료=없음, 도착 60초 미만 자리 제외). 빈 보드도 `candidates: []` 로 내보냄(접지 않음)
- 응답 신규 칸 `boardVersion`·`refreshedAt`(ProposalBody), `originType`(후보) — 전부 `@JsonInclude(NON_NULL)` 이라 사건 기반 응답 모양 불변(테스트 고정)
- originType 매핑: TRANSFER_WAIT→PLANNED_ALIGHT, RIDE_INTERIOR→RIDE_STOP, 그 외 enum 이름(PR-3 의 CURRENT_POSITION·WAITING_STOP)
- ETag = 내보낸 본문 JSON 의 SHA-256 앞 16바이트. board_version 이 아니라 본문 해시인 이유: 읽는 시각에 자리가 빠지므로 버전이 같아도 내용이 바뀐다(리뷰 M2). 보드일 때만, `If-None-Match` 같으면 304
- `RerouteProposalView` 에 `Board(version, refreshedAt, originKinds)` 추가, 옛 9인자 생성자 유지

### 함정
- `findActiveBoard` 에서 `journey.getId()` 로 조회하면 단위 테스트 픽스처(id 없는 엔티티)에서 빈 결과 ← 요청의 `journeyId` 를 그대로 사용

### 좌표
- `RerouteProposalService.findActiveBoard`, `RerouteProposalTxService.boardOf`, `RerouteCandidateSummary.from(list, kinds)`/`originType`, `JourneyRerouteProposalController.active`(ETag)
- 모바일 인계: `docs/plan/reroute-periodic-board/HANDOFF.md`

### 배포
- 마이그레이션 없음. 플래그 off 면 응답 바이트 동일

### 검증
- `RerouteProposalServiceTest` 21/21(+3: 보드 조회 무쓰기·60초 거름, 빈 보드 유지, 만료·개정본 불일치=없음)
- `JourneyRerouteProposalControllerTest` 16/16(+3: 보드 칸·ETag, 304, v1 모양 불변)
- `RerouteAcceptIT` 28/28(+1: 보드 모드 조회가 보이는 후보·originKind 를 주고 행을 바꾸지 않음)
- 전체 2358 중 실패 2 = 기존 무관 2건(동일)

## 재탐색 주기 보드 PR-3 — 기점 확장 + 근거 집계 (#264, branch feat/reroute-board-origins, 2026-10-07)

### 확정
- 보드 진입점 `RemainingPathCandidateProvider.evaluateBoard` — 기점을 **지금 단계**에서 연다: 걷기=보간 현재 위치(`HERE`, `CURRENT_POSITION`), 대기=그 정류장(`WAITING_STOP`), 버스 탑승=중간 정류장(보드 여유 60초 오버로드) + 다음 환승 있으면 계획 하차점, 지하철 탑승=계획 하차점만. 환승 없으면 계획 하차점 없음. 기존 `findCandidates`(사건 기반)는 그대로
- 자리 3분류: 평가함(후보|대안없음) / 평가 못 함(검색 빈손·예측 실패·예산·기준선 실패 → 넘기지 않음 = 말풍선 유지) / 제공함(`offered`). 병합기는 `offered` 에 없는 단계형 자리(RIDE_INTERIOR 제외)를 지운다
- 검색 빈손은 "평가 못 함"(OTP 장애가 말풍선을 지우지 않게), 경로는 왔는데 전부 지금과 같은 노선이면 "평가함·대안 없음"(숨김)
- 기준선 일반화: 대기 중이면 그 대기부터 now, 그 밖은 현재 단계 끝(예측→앱 보고→계획) 다음부터. 대기 수는 "현재 단계 이후 전체 대기 수" vs 후보(걷기만=0, 그 외 waitsAfterFirst+1) → extraWaits. 기존 more-transfers 거름은 사건 기반 경로에만 남음
- 근거: `BoardingChainEtaCalculator.Result.modelBased`(체인 중 하나라도 REALTIME_ARRIVAL 아님). realtimeOnly = !기준선.modelBased && !후보.modelBased. 옛 8인자 생성자는 modelBased=true(보수)
- 걷기 위치: `CurrentPositionEstimator` — geometry(`GetJourneyNavigationGeometryUseCase`) 의 (segmentIndex, progressFraction) 를 거리 기준 보간. progressLegFraction 미사용. 180초 낡음=모름, 보고 전·활성 180초 내=시작점
- `RerouteOrigin` 에 x·y 추가(옛 5인자 생성자 유지), stopId 필수는 CURRENT_POSITION 만 면제. `RerouteOriginKind` += CURRENT_POSITION·WAITING_STOP(평행 enum 없음)
- **PLAN 변경**: 도보 자르기 생략(단계 경계 교체로 충분, 걸은 진행 이력만 손실), `candidate-min-gain-seconds` 키 생략(병합기 문턱이 대신)

### 함정
- 롤백한 옛 jar 는 새 enum(CURRENT_POSITION·WAITING_STOP) 을 저장 행에서 만나면 그 후보/보드를 못 읽는다(Jackson enum 실패 → 후보 없음/404). 플래그 off 면 보드 행이 없어 무관

### 좌표
- `RemainingPathCandidateProvider.evaluateBoard / boardOrigins / plannedAlight / measureBoardBaseline / currentLegEnd`
- `CurrentPositionEstimator`, `UpcomingStopWindowResolver.resolveRideInterior(…, leadTimeSec)`, `RerouteBoardMerger.noLongerOffered`, `RerouteBoardRefreshService`(evaluateBoard 사용)

### 배포
- 마이그레이션 없음. 플래그 off 면 동작 변화 없음(보드 진입점은 갱신기만 부름)

### 검증
- `RemainingPathCandidateProviderTest` 26/26(+5: 대기 기점·이득·근거, 걷기 기점 HERE·적용 원점, 환승 없는 탑승=중간 정류장만·검색 빈손 미평가, 같은 경로뿐=대안 없음, ML 섞임=realtimeOnly false)
- `CurrentPositionEstimatorTest` 4/4, `BoardingChainEtaCalculatorTest` 10/10(+1 근거 집계), `RerouteBoardMergerTest`+`RerouteCandidateContractTest` 43(+6: offered 규칙 3, 원점 주소 3)
- `RerouteAcceptIT` 29/29(+1: 걷기 현재 위치 후보가 도보 단계부터 교체 수락)
- 전체 2375 중 실패 2 = 기존 무관 2건(동일)

## 재탐색 주기 보드 PR-5 — push 재정의 (#264, branch feat/reroute-board-push, 2026-10-07)

### 확정
- push 판단은 보드 갱신 직후 `RerouteBoardPushService.considerPush`. 정책 `RerouteBoardPushPolicy`(순수): 보이는 자리 중 이득 ≥300(alert.minimum-gain-seconds) & 하차형은 도착 ≥120초 → 최대 이득 1개. 다음 환승 AT_RISK·MISSED 면 "위험+대안"(시작·수락 3분 차단 무시), 아니면 3분 조용함 + 새로움(자리·지문 다르거나 이득 +120) 필요
- 순서: 정책 → 직전 push 무응답이면 무시+1(answerPush CAS 로 한 번만) → 무시 상한 → cooldown ALERT 5분 → 채널 → [push_seq CAS + outbox(키 `…:REROUTE_SUGGESTED:p{seq}`) + 대기 중 TRANSFER_RISK CANCELED(`superseded-by-reroute`)] → relay 깨움
- 환승 위험 알림 합치기(안 A): 보드+알림 켜짐이면 `TransferRiskNotifier` 가 scheduled_at = now+20s(`board.risk-merge-delay-seconds`)로 넣고 만료도 그 기준. 재탐색 push 가 나가면 취소. PROCESSING 은 못 거둠(둘 다 나감, 수용)
- 수락: 보드 모드면 terminateReroute 안 함(accepted_at 은 PR-2 신호가 기록). 거절·무시: 보드 행(`board_version>0`)은 닫지 않고 answerPush + (거절=terminate|opt-in, 무시=ignored+1), 응답 closed=false. 사건 기반 행은 기존대로
- 경로 조회는 push 응답(REQUIRES_NEW `RerouteBoardPushAnswerService`, 읽기 전용 트랜잭션 밖). `/active` 폴링은 응답 아님
- 문구: `FcmMessageFactory` 가 payload(stopName·routeName·etaGainSec·originType)로 "X에서 내려 Y 타면 n분 빨라요" / "Y(으)로 가면 n분 빨라요". 재료 없으면 기존 확정 문구. 노선명은 legSummaries[0] "… 승차" 에서 추출(화면 값에 노선 칸 없음)
- 보드 행은 alerted=false 로 두어 기존 만료 무응답 집계(countUnansweredDue)에 섞이지 않게 함

### 함정
- 같은 빈 안 `@Transactional` 메서드 호출은 프록시를 안 지나 트랜잭션이 없다 ← 쓰기를 `RerouteBoardPushTxService` 로 분리
- `JourneySession` 에 reroute_accepted_at 을 매핑하면 마이그레이션을 손으로 나열하는 IT 9개(V53 에서 멈춤, reroute_proposal 없음)가 전부 깨진다 ← 엔티티 매핑 대신 네이티브 `findRerouteAcceptedAt`
- 슬라이스 IT(`AbstractJourneyNotificationIT`)는 `TransferRiskNotifier` 를 @Import 하므로 새 의존성(JourneyRerouteProperties)도 @Import 필요
- `RerouteProposal.pushSeq`(원시 int) 매핑 → ddl-auto IT(`WithdrawalDeletionBoundaryIT`) raw insert 에 push_seq 추가

### 좌표
- `RerouteBoardPushPolicy`, `RerouteBoardPushService`, `RerouteBoardPushTxService`, `RerouteBoardPushAnswerService`
- `JourneyNotificationEnqueuer.enqueueBoardPush / enqueueTransferRisk(…, delay)`, `JourneyNotificationRepository.cancelPendingByCheckpoints`, `RerouteProposalRepository.recordPush / answerPush`
- `RerouteProposalTxService.closeBoardPush`, `RerouteAcceptTxService`(board 모드 terminate 생략), `FcmMessageFactory.rerouteAction`

### 배포
- 마이그레이션 없음(V57 칼럼 사용). 플래그 off 면 동작 동일(위험 알림 지연·push 모두 보드+알림 켜짐 조건)
- **운영 on 체크리스트(사용자 결정)**: V57·V58 배치 확인 → `UPDATE journey_session SET reroute_refresh_at = now() WHERE status='ACTIVE'` 백필 → `JOURNEY_REROUTE_BOARD_ENABLED=true`(api·worker) → 시뮬레이션 1건 board_version 증가·push 확인

### 검증
- `RerouteBoardPushPolicyTest` (경계값 300/120/3분/+120, 위험 우회, 최대 이득), `FcmMessageFactoryTest` +1(행동 문구 2종·폴백)
- `RerouteAcceptIT` 34/34(+5: 같은 보드 push 2회=2행·push_seq 2, 위험 알림 취소, 무응답 1회만 집계, 보드 무시 시 ACTIVE 유지, 보드 모드 수락 비종료·accepted_at)
- 전체 2391 중 실패 2 = 기존 무관 2건(동일)

## 재탐색 주기 보드 PR-4 — 경기·TAGO 중간 하차 기점 (#264, branch feat/reroute-board-regional, 2026-10-07)

### 확정
- #268 표(`regional_bus_route_stop`)를 읽기만 한다: `RegionalBusRouteStopStore.findRouteStops(provider, routeId)` 추가(순번 오름차순, 가상점 표시 포함)
- `RegionalRideLocator`: 노선 = 주행 단계가 고른 노선 후보의 `sourceRouteId`(GBIS·TAGO id), GG 표에 없으면 TAGO. 정류장 조인 = 구간 `sourceStartStopId`·`sourceEndStopId`(승차 이후 첫 일치). 지금 위치 = 하차에서 앱 남은 정류장 수만큼 **가상점을 빼고** 거슬러 오름
- `RegionalArrivalLookup`: 내 차 = "몇 정류장 전"이 [기대-1, 기대] 인 차(더 먼 = 내 뒤 차 거절). 경기 predictMin×60, TAGO arrSeconds. 재탐색 전용 일일 버킷(`reroute-gg-arrival`·`reroute-tago-arrival`, 기본 5000) 소진 시 그 회차 지역 기점 중단. 15초 메모리 캐시
- 창: 서울 id 없는 탑승 + **보드 켜짐**일 때만 지역 스캔(사건 기반 경로는 그대로). 원점 `RerouteOrigin.provider`=GG|TAGO, stopId/routeId/seq 는 API 공간
- 수락 자르기: `origin.isRegional()` 이면 지역 재확인. 자를 위치 = 승차 이후 **실제 정류장 수**(가상점 제외 = GTFS 순번 공간). 어긋나면 합성 단계 이름 대조가 막는다(409)
- "같은 버스 다시 타기" 거름에 원천 노선 id 추가(`isSameAsCurrent` 가 firstOption.sourceRouteId 도 비교, 보드 currentRouteIds 에 원천 id)

### 미결
- 서울 도착 조회의 재탐색/검색 할당량 분리(어댑터 내부 차감 문맥) — 이번 범위 밖
- 경기·TAGO 중간 하차 후보의 후보 경로 조회 `ride.stopCount` 는 null(서울 위치기만 계산)

### 좌표
- `RegionalRideLocator`, `RegionalArrivalLookup`, `UpcomingStopWindowResolver.scanRegional`, `RideTruncationResolver.resolveRegional`
- `JdbcRegionalBusRouteStopStore.findRouteStops`, `BusApiQuotaPort.tryAcquire(bucket, limit)`, `RerouteOrigin.provider/isRegional`

### 배포
- 마이그레이션 없음(#268 의 V56 표를 읽음). 보드 플래그 off 면 동작 동일. 표가 비어 있으면(적재 전) 지역 기점은 `route-unknown` 으로 안 열림

### 검증
- `RegionalRideLocatorTest` 3/3, `RegionalArrivalLookupTest` 4/4, `UpcomingStopWindowResolverTest` 12/12(+2), `RideTruncationResolverTest` 13/13(+2), `JdbcRegionalBusRouteStopStoreIT` 6/6(+1)
- 전체 2403 중 실패 2 = 기존 무관 2건(동일)

## regional-catalog-io-retry (2026-10-07, S)
### 함정
- 운영 첫 적재 `routes=0 … aborted=true elapsed=45s` ← TAGO getRouteNoList `Read timed out` 을 `RegionalBusRouteSourceAdapter.call()` 이 `failed`(재시도 불가)로 분류. 목록 단계는 한 도시라도 실패하면 전체 중단(부분 목록으로 prune 하면 안 되므로 의도). / 가드: `ResourceAccessException`(I/O·timeout) → `retryable`, `withRetry` 3회 백오프로 넘김
### 확정
- 목록 단계 전부-아니면-중단은 유지. 재시도 소진 시 중단 = 다음 회차(월 1회 또는 load-on-empty 재기동)에 다시
### 검증
- `./gradlew test --tests '*RegionalBusRouteSourceAdapterTest' --tests '*RegionalBusCatalogLoad*'` → 10/0, 7/0 (신규 `ioErrorIsRetryable` 포함)
### 배포
- 마이그레이션 없음. worker 재배포 시 표가 비어 있으면 load-on-empty 로 재적재

## standalone-track PR-1 — journey kind + TRACK 재탐색 제외 (2026-10-07, L 의 1/5, #265)
### 확정
- `journey_session.kind` NOT NULL DEFAULT 'NAVIGATION' + CHECK(NAVIGATION|TRACK). 엔티티 `updatable=false`(시작 때 정해지고 안 바뀜). 빌더 기본값은 생성자에서 `null → NAVIGATION`
- `JourneyStartTxService.start` 는 7인자(kind) 오버로드 추가, 기존 6인자는 NAVIGATION 위임 — 기존 호출부(`JourneyStartService`)·mock 시험 무변경. TRACK 이면 `scheduleInitial` 미호출
- 보드 SQL 4개(`claimRerouteRefreshBatch`·`requestRerouteRefresh`·`scheduleRerouteRefreshIfAbsent`·`markRerouteAccepted`)에 `kind = 'NAVIGATION'`. `RerouteBoardRefreshService.refresh` 첫머리에 kind 필터(사용자 요청 제안이 선점 없이 직접 부르는 길 방어)
- 사용자 요청 제안 409 는 새 코드 없이 기존 "no-transfer" 판정으로 나온다(TRACK = 1구간 = 환승 행 0). 고정 시험은 기존 `WindowCooldownEligibilityPolicyTest.missingTransferIsRejected`·`testUserStillNeedsAnUpcomingTransfer`(테스트 계정도 no-transfer 거절) — kind 를 보지 않으므로 TRACK 전용 시험을 따로 두지 않음
- `JourneySnapshot.kind` 는 `status` 바로 뒤. `of(...)` 3번째 인자
### 함정
- 엔티티에 칼럼을 매핑하면 **마이그레이션을 손으로 나열하는 `@Sql` IT 12개**가 전부 V59 를 요구한다(insert 가 kind 를 쓰므로). `V35__create_journey.sql` 을 나열하는 IT 전부에 V59 추가함 — V60 이후 journey_session 칼럼을 또 매핑하면 같은 작업 필요(`grep -rl V35__create_journey src/test`)
### 좌표
- `JourneySessionRepository` 보드 섹션(`claimRerouteRefreshBatch` 등), `JourneyStartTxService.start(…, JourneyKind)`, `RerouteBoardRefreshService.refresh`, `JourneyQueryService.snapshotOf`
- 시험: `JourneyKindRerouteExclusionIT`(6), `JourneySnapshotKindTest`(3), `JourneyStartTxServiceKindTest`(2), `RerouteBoardRefreshServiceKindTest`(1)
### 배포
- **V59 VPS 수동배치 필요** — 운영 `ddl-auto: validate` 라 칼럼 매핑 앱 배포 **전에** 적용. 동작 변화 없음(기존 여정 전부 NAVIGATION)
- 함정 추가: `WithdrawalDeletionBoundaryIT` 는 **ddl-auto=create-drop** 스키마라 V59 의 DEFAULT 가 안 온다 → 손으로 쓰는 `insert into journey_session` 에 `kind` 를 넣어야 함(7건 실패로 발견, 수정 후 7/7)
### 검증
- 일회용 postgis(`st-pg` :55432, 로컬 dev DB `--schema-only` + `flyway_schema_history`(V58) 복원) + redis(`st-redis` :56379), env 는 db/api/auth/oauth/toss 만 export(fcm 제외)
- `./gradlew test --tests '*JourneySnapshotKindTest' --tests '*RerouteBoardRefreshServiceKindTest' --tests '*JourneyStartTxServiceKindTest' --tests '*JourneyKindRerouteExclusionIT' --tests '*JourneySchemaIT' --tests '*RerouteProposalStoreIT'` → 3/2/1/6/19/23 전부 통과
- 변이 확인: 선점 SQL 의 `kind` 조건 제거 시 `JourneyKindRerouteExclusionIT` 1건 실패(시험이 조건을 잡음) → 원복
- 전체 `./gradlew test --continue`: 2416 중 실패 17 = Withdrawal 7(내 것, 위 수정 후 7/7) + **기존 무관 10**(`JourneyEvaluationIT` 7·`JourneyEvalReadPathIT` 2·`JourneyTransferAssessmentIT` 1 — 변경 stash 한 main 에서도 같은 10건 실패 확인)
## #277 역 ID 지하철 도착 API (2026-10-07, feat/subway-station-arrivals)

### 확정
- `GET /api/subway/stations/{stationId}/arrivals`. 소스 = `SubwayRealtimeArrivalReader.arrivals(lineId, stationId, null)`(캐시, 키 소비 0). 방향 순서 = direction 한국어 Collator, null 맨 뒤.
- 빈 direction 의 label 도 행선지 우선(앱 `groupTrainsByDirection` 과 같음), 행선지도 없으면 "방향 정보 없음". PLAN 초안의 "빈 방향 → 무조건 방향 정보 없음"은 규칙 3 과 충돌해 앱 동작 쪽으로 정정.
- label 충돌 시 direction 원문으로 바꾸는 건 direction 이 null 이 아닌 방향만(null 방향은 원문이 없다).
- 운영 statn_id 중복 0건(696행 19노선) → `findByStationId` 단건 조회 안전.

### 좌표
- `SubwayStationArrivalQueryService.group` / `SubwayStationController` / `SubwayErrorCode.SUBWAY_STATION_NOT_FOUND`

### 검증
- `./gradlew test --tests '*SubwayStationArrival*' --tests '*SubwayStationController*' --tests '*SubwayArrivalQueryServiceTest'` → 9/0, 3/0, 7/0 (BUILD 성공). 전체 테스트는 미실행.

### 배포
- 마이그레이션 없음. api 이미지 교체. 플래그 없음.
## #283 남은 역 목록 PR-1: OTP 지하철 패턴 포트·커버리지 (2026-10-07, feat/subway-stations-ahead-pr1)

### 확정
- 포트=원천만(`SubwayRoutePatternPort`), 선택·반전·분할=순수 `SubwayRoutePatternAssembler`, 캐시 24h+낡은 값=`SubwayLinePatternService`. PLAN 초안(어댑터가 다 함)에서 바꿈 — 커버리지 테스트가 운영 코드를 그대로 돌리게.
- OTP 방향 라벨이 실시간과 반대인 노선 4: KJ-2(경의중앙 서울역), 02-2(2호선 성수지선, 내↔외), 09-*(9호선), AP-1(공항철도). 신정지선(02-3)은 같음.
- 6호선(06-00, 방향 없음): 응암 첫 등장까지(포함)=상행, 응암부터=하행. 응암 없는 패턴은 버림.
- 매칭: 같은 노선·같은 이름이면 ≤5km, 다르면 최근접 ≤150m. 리뷰안 500m 는 도봉(1.1km)·인천공항2터미널(~3km)에서 깨짐.
- 실시간 행선지 대조는 OTP 이름·DB 이름 둘 다로(개명 당고개→불암산, 응암↔응암순환(상선)). 비율 5% 미만 행선지는 잡음.

### 함정
- 반대 방향 역 목록 ← OTP 라벨을 그대로 믿음 / 가드=`SubwayPatternCoverageTest.realtimeDestinationsLieAheadInTheirDirection`(9호선 반전 빼면 실패 확인함)
- 틀린 역 연결(도봉→도봉산) ← 좌표 우선 매칭 + OTP 좌표 뒤바뀜 / 가드=이름 우선 + `nearestFallbackNeverPicksAnotherStation`(개명 목록 고정)
- 인천2호선→1002 ← 숫자 추출 매퍼 / 가드=shortName 명시표
- 패턴 응답 노선당 최대 1.4MB·1~2.5s(trip 마다 stoptimes, GraphQL 로 trip 수 제한 불가) → 24h 캐시
- 리뷰가 "경강선 route 0개"라 했지만 이번 채집엔 있음(fixture 61→91 route 로 갱신된 feed)

### 좌표
- `application/subway/service/SubwayRoutePatternAssembler` / `SubwayPatternStationMatcher` / `SubwayLinePatternService`
- `infrastructure/adapter/out/api/otp/OtpSubwayRoutePatternAdapter`
- fixture `src/test/resources/otp/subway-patterns/` (routes-with-patterns 2.5MB, subway-stations 696행, realtime-destinations 103조합)

### 미결
- 경춘선 상행 용산 직결 행선지(OTP 패턴에 용산 없음) → 그 열차는 상봉·청량리까지만
- 4호선 진접 연장 3역 역 마스터 누락 → stationId null 로 나감(정적 일배치 off 영향)

### 검증
- `./gradlew test --tests '*SubwayPattern*' --tests '*SubwayRoutePatternAssembler*' --tests '*OtpSubwayRoutePatternAdapter*' --tests '*SubwayLinePatternService*' --tests '*GtfsRailDirectionCoverageTest'` → 5/0, 5/0, 7/0, 3/0, 5/0, 3/0. 전체 테스트 미실행(스크래치 clone 은 DB env 없음).
- 운영 OTP 에 어댑터 질의문(variables 포함) 그대로 POST → errors 없음.

### 배포
- 마이그레이션 없음. API 노출 없음(빈 등록만) — 배포해도 동작 변화 0.

## #283 남은 역 목록 PR-2: 계산기·API (2026-10-07, feat/subway-stations-ahead-api)

### 확정
- `GET /api/subway/stations/{stationId}/stations-ahead?direction&destination`. 응답 = lineId·boardStationId·boardStationName·direction·destinationApplied·endReason(TERMINAL|BRANCH|LOOP)·branches·stations.
- 오류: 404 NOT_FOUND / 400 DIRECTION_INVALID(값 공간 밖 or 그 노선에 그 방향 없음) / 422 STATION_NOT_ON_ROUTE / 503 ROUTE_UNAVAILABLE(낡은 캐시도 없을 때만).
- LOOP 는 **승차역 재등장으로 끊긴 꼬리**일 때만. 2호선이라도 지선(신도림→까치산)은 TERMINAL.
- branches[].destinationName = 갈래 대표 꼬리의 끝 역. 다시 보내면 그 갈래가 나온다(2호선 본선 갈래는 "대림" 같은 역 이름이 오지만 본선 선택으로 정상 동작 — 테스트 `branchDestinationRoundTrips`).
- 환승 = 같은 정규화 이름 + ≤300m + 다른 lineId. 역 마스터 696행 통째 메모리(`SubwayLineStationIndex`).

### 함정
- 신정지선 4역이 LOOP 로 나옴 ← 순환 노선 여부만 보고 LOOP 판정 / 가드=Tail.lapped + `destinationPicksBranchOrMainLine`
- 테스트 UnfinishedStubbingException ← given(...) 인자 안에서 mock 생성(기존 주석에 있던 함정) / 리스트 먼저 만들기
- 모바일 `AlightRequest` 엔 표시 라벨("잠실 방면")만 있다 → 원문 direction 필드 추가 필요(HANDOFF 🔴)

### 좌표
- `application/subway/service/StationsAheadCalculator` / `SubwayStationsAheadService` / `SubwayLineStationIndex`
- `application/arrival/dto/SubwayStationsAheadResponse`, `SubwayStationController#getStationsAhead`
- 시나리오 테스트 = 운영 fixture(`SubwayPatternFixtures`)

### 검증
- `./gradlew test --tests 'watoo.grd.nextroute.application.subway.*' --tests 'watoo.grd.nextroute.application.arrival.*' --tests 'watoo.grd.nextroute.infrastructure.adapter.in.api.subway.*' --tests 'watoo.grd.nextroute.infrastructure.adapter.out.api.otp.*'` → 268 / 실패 0.
- 새 테스트: StationsAheadCalculatorTest 17(강남 LOOP 42역, 성수로 안 자름, 신도림/강동/구로/가좌 분기점 빈 목록+갈래, 까치산→지선, 마천 TERMINAL, 의정부 급행 무시→구로, 9호선, 6호선, 공항철도), SubwayStationsAheadServiceTest 6, SubwayLineStationIndexTest 2, 컨트롤러 +3.
- 전체 테스트 미실행(스크래치 clone DB env 없음).

### 배포
- 마이그레이션 없음. api 이미지 교체. 첫 호출 때 노선 패턴을 OTP 에서 받는다(노선당 1~2.5s, 이후 24h 캐시).
## #276 지하철 도착 조회 line_id 엄격화 (2026-10-07, fix/subway-arrival-line-strict)

### 확정
- `resolveStation(lat, lon, lineId)`: lineId 있으면 **그 노선 역만** 찾는다(`findNearbyOnLine`, SQL 에 `line_id = :lineId`). 50m→300m 단계 확대 유지, 끝까지 없으면 empty. 다른 노선 대체 없음. lineId 없으면 최근접 그대로.
- 노선 조건을 SQL 에 넣은 이유=구현 단순. LIMIT 5 밀림은 운영 실측 0건(300m 안 6행 이상 역 없음) — 위험 근거 아님.

### 함정
- 다른 노선 열차 반환 ← 첫 후보 반경에서 멈춤 + `selectStation` 의 `orElse(nearby.get(0))` + `size()>=2` 조건(후보 1개면 노선 확인 생략). 강남 2호선 승강장 좌표: 50m 안=2호선(0m)만, 신분당선 1077000687 은 146m.

### 좌표
- `SubwayArrivalQueryService.resolveStation` / `SubwayStationRepository.findNearbyOnLine`
- 호출자: `/api/arrivals/subway`, `JourneyTransitIdEnricher.resolveSubwayStation`(후단에서 lineId 재필터 → 영향 없음)

### 검증
- `./gradlew test --tests '*SubwayArrivalQueryServiceTest' --tests '*JourneyTransitIdEnricherTest'` → 10/0, 3/0. 옛 service 로 돌리면 7 실패(가드 유효).
- 운영 DB SELECT(읽기 전용): 1077 필터 반경 50·100 없음, 150 에서 강남 1077 146m 잡힘.

### 배포
- 마이그레이션 없음. 이미지 교체만.

## 2026-10-07 운영 반영: 재탐색 보드 ON + 노선 정류장 표 첫 적재
### 배포
- 13:05 보드 ON: VPS compose `x-app-environment` 앵커에 `JOURNEY_REROUTE_BOARD_ENABLED: "true"`(api·worker 만 렌더). 백업 `compose.yaml.bak-20261007-130424-pre-board`. 끄기 = 그 줄 삭제 + api·worker 재생성
- 13:13 #281 배포: 이미지 `nextroute:20261007-131332-ab819324`, jar md5 99f8281f, 이전 jar `app.jar.bak-20261007-pre-catalog-retry`. 마이그레이션 없음
### 검증
- 첫 적재 완료 14:26: `routes=21402 loaded=21358 empty=11 failed=33 pruned=0 aborted=false elapsed=4348s`(72분, 플랜 추정 2시간보다 빠름). 표 TAGO 19,191노선 954,241행 + GG 2,167노선 148,716행
- worker `TagoCityCodeLookup refreshed routes=19191 prefixes=49`
### 함정
- failed 33 = 저장 실패 17(전부 청양군 cityCode 34350 `CNB2960…`) + 원천 실패 16(debug 로그라 원인 미기록). 청양은 **TAGO 원천이 깨져 있다**: CNB296000146 82정류장 중 23개 nodeord 가 두 정류장에 겹치고 nodeid 가 노선 id 모양(CNB296000029). `uq_regional_bus_route_stop(provider, route_id, seq)` 가 그 노선만 거절 → 의도대로. 겹친 순번은 실시간 매칭이 원래 불가능하므로 고치지 않음
- 원천 실패는 `log.debug` 라 운영에서 원인이 안 보인다. 다음에 원인이 필요하면 WARN 으로 올릴 것
- api 의 `TagoCityCodeLookup` 은 부팅 후 6시간 주기라 첫 적재 직후엔 여전히 `routes=0`(접두사 표 폴백). api 재시작 또는 19:14 자동 갱신 후 노선 단위 도시코드 적용
- 낮 시간 적재: 도착 조회와 키 동시세션 30을 공유. 이번 회차엔 ERROR 0

## standalone-track PR-2 — 노선 패턴 조회 + TRACK 시작 (2026-10-07, L 의 2/5, #265)
### 확정
- API(전부 `/api/journeys/track/**` = 기존 `/api/journeys/**` 인증 규칙 상속, SecurityConfig 무변경): `GET stops/near?lat&lon&mode`, `GET stops/{gtfsStopId}/patterns`, `GET routes/{routeGtfsId}/stops?from&pattern`, `POST /api/journeys/track?replace`. **replace 는 PLAN 의 본문이 아니라 쿼리 파라미터** — 일반 시작(`POST /api/journeys?replace`)과 같은 관례
- 플래그 `journey.track.enabled`(env `JOURNEY_TRACK_ENABLED`, 기본 false) 하나가 조회·시작 전부를 404 로 막는다
- 방향 = 정류장 패턴을 **가지**로 줄인 것(`TrackPatternSelector`): 승차 첫 등장 다음 ~ 승차 재등장 직전(순환) 또는 패턴 끝 = "한 바퀴". 같은 노선에서 남은 구간이 다른 가지의 앞부분이면 제거(단축·회차), 같으면 trip 많은 쪽. 라벨 = headsign → (순환 지하철) longName 라벨(내선·외선) → "{종점} 방면"
- 시작 패턴 선택: 코드 힌트가 유효하면 그것, 아니면 승차 뒤 한 바퀴 안에 하차가 있는 패턴 중 구간 최단 → trip 많은 순. **순환선은 승차 "앞" 역도 한 바퀴 안이면 갈 수 있다**(정상)
- 소요 = 오늘(없으면 어제) `tripsForDate` 중 첫 출발이 지금 이전인 가장 늦은 trip(없으면 가장 이른 것)의 `stoptimesForDate` 로 승차 출발~하차 도착. 정차 시각이 패턴과 개수·id 가 안 맞거나 ≤0 이면 422 `TRACK_SCHEDULE_UNAVAILABLE`(근사 안 함)
- 구간 → `RoutingLeg`(`TrackLegFactory`, `OtpPlanConnectionMapper.toLeg` 와 같은 칸) → `LegacyRouteResponseMapper.toSubPath`(public 으로 열었다) → 1구간 `PathResult`(payment -1, `lastEndStation`=하차) → compile → enrich → `startTx(…, TRACK)`
- 형상: patternGeometry 를 정류장마다 "앞 정류장 자리 이후 600점 안 최근접"으로 잘라 재인코딩(SHAPE_OK). 못 자르면 MISSING(앱 직선 폴백)
- OTP permit 은 `TrackPatternSource.withPermit` 가 요청 하나에 하나(마감 = `route.search.otp-ms`, role api). 포화·호출 실패 = 503 `TRACK_PATTERN_UNAVAILABLE`
- 캐시: 정류장 패턴·패턴 상세만 프로세스 메모리 10분, 키 = graphVersion|id. 낡은 코드(null)는 캐시 안 함. trip·정차 시각은 캐시 안 함
- 동시 시작 409 번역은 `JourneyStartConflictTranslator` 로 추출(일반·추적 공용). `JourneyStartService` 의 `JourneyQueryService` 의존이 이것으로 바뀜
- 새 오류 코드: `TRACK_STOPS_INVALID`(422), `TRACK_SCHEDULE_UNAVAILABLE`(422), `TRACK_PATTERN_UNAVAILABLE`(503)
- 지표 `track.start{mode,result}` — result = started/conflict/compile-failed/rejected/오류코드
- 프레임워크 신규 없음(캐시는 ConcurrentHashMap — caffeine 미도입, 키 수·TTL 단순해 직접 구현이 싸다)
### 함정
- 실측(운영 OTP, 2026-10-07): 지하철역은 부모 station 없는 일반 stop(`1:RS_ACC1_S-1-0222`) → `stop(id){patterns}` 로 충분. 2호선 강남 패턴 **49개**, 패턴 하나가 여러 바퀴(같은 역 최대 12회), 패턴당 trip 1~5. `PatternsAtStop`(trips{gtfsId} 포함) 2호선 강남 약 370KB·0.5~2.6초
- headsign 은 버스·지하철 전부 null, directionId -1. 서울 버스 패턴은 왕복 한 줄(기점→회차→기점)이라 "{종점} 방면" 의 종점 = 기점(차고지) 이름이 나온다(예: 640 강남역9번출구 → "신월동우성상가 방면")
- `pattern(id)`·`stop(id)`·`trip(id)` 없는 id 는 오류가 아니라 `data.x = null`
- `tripsForDate(serviceDate:)`·`stoptimesForDate(serviceDate:)` 는 "2026-10-07" 형식 문자열로 동작
- `TrackJourneyStartServiceTest` 첫 판에서 "승차 앞 역 = 거꾸로 = 422" 를 기대했는데 2호선은 순환이라 닿는 게 맞았다 — 시험을 "노선에 없는 역"으로 고침. 비순환 역순 422 는 `TrackPatternSelectorTest.reversedOrder`
### 검증(라이브 프로브, 운영 OTP 터널, 커밋 안 함)
- 강남(2호선) 49패턴 → 방향 2개(내선·외선, 각 남은 42역). 강남역9번출구 버스 10패턴 → 8노선. 경기 GGB206000036 → 3노선(마을버스 `BR_3100_` 포함)
- 강남→삼성: 외선 3구간 330초, 내선 40구간 5160초(둘 다 SHAPE_OK, 오늘 trip 5개)
### 좌표
- `TrackPatternSelector`(가지·선택), `TrackLegFactory`(구간·형상), `TrackPatternSource`(permit·캐시), `TrackSelectionQueryService`, `TrackJourneyStartService`, `JourneyTrackController`, `OtpTransitPatternAdapter` + `otp/trackPatterns.graphql`(operationName 으로 5개 쿼리)
- 픽스처 `src/test/resources/otp/track/`(2호선 내선 ::10 — 강남 31·74 번째 두 번)
### 미결
- 하차 후보에서 dropoff NONE 정차(통과역)를 빼지 않는다 — 정류장 패턴 쿼리에 pickup/dropoff 가 없다(#286 은 stoptimes 로 거른다). 필요하면 PR-5 전후로
- M 버스 빨강 덮어쓰기(`LegacyRouteResponseMapper.displayColor`)는 방향 목록 색에 미적용(시작 후 lanes 색엔 적용됨)
- #286 이 고치는 "OTP 방향 라벨 반대 노선 4개(KJ-2·02-2·09-*·AP-1)"는 같은 `labelFrom` 을 타므로 그 수정이 들어오면 TRACK 도 같이 고쳐진다
### 배포
- 마이그레이션 없음. 플래그 off 면 동작 동일(404). 켤 때 api 에 `JOURNEY_TRACK_ENABLED=true`
### 검증
- 신규 시험: `OtpTransitPatternAdapterTest` 7, `TrackPatternSelectorTest` 10, `TrackLegFactoryTest` 7, `TrackLegChainTest` 4(2호선 wayCode 0·서울/경기/TAGO 버스 id), `TrackJourneyStartServiceTest` 10, `TrackSelectionQueryServiceTest` 5, `JourneyTrackControllerTest` 5(익명 401 4경로 포함), `TrackJourneyStartIT` 1(일회용 postgis — 시작 → active 1구간·kind=TRACK·보드 예약 없음) — 전부 통과
- 전체 `./gradlew test --continue`: 2465 중 실패 11 = **기존 무관 10**(JourneyEvaluationIT 7·JourneyEvalReadPathIT 2·JourneyTransferAssessmentIT 1, PR-1 때와 동일) + `JourneyNotificationSchemaIT` 1(전체 실행 중 testcontainers `TransactionException`, 단독 재실행 통과 = **간헐, 이 PR 무관** — 손댄 파일 없음)

## #292 순환 본선 갈래 이름 (2026-10-07, fix/subway-loop-branch-name, S)

### 확정
- `branches[]` 에 `kind`(LOOP|TERMINAL) 추가. 한 바퀴 도는 꼬리(lapped)의 갈래는 `destinationName="순환"`(`StationsAheadCalculator.LOOP_DESTINATION`) — 이전엔 한 바퀴 끝 역(신도림 내선이면 "대림")이라 앱 "대림 방면"이 반대 방향으로 읽혔다.
- `destination=순환` 은 순환 노선에서 본선 선택으로 읽는다(명시 분기). 갈래 값 왕복 테스트 유지.
- 같은 모양: 신도림 내선(순환/까치산), 성수 내선(순환/신설동).

### 검증
- `./gradlew test --tests '*StationsAhead*' --tests '*SubwayStationController*'` → 계산기 19/0(+2), 서비스 6/0, 컨트롤러 6/0.

### 배포
- 마이그레이션 없음. 응답 필드 추가(`kind`) — 하위 호환.
## #291 지하철 도착·남은 역 지연 대비 (2026-10-07, perf/subway-ahead-latency, M)

### 확정
- 신고 지연(신분당선 도착 27s, stations-ahead 40s+)은 **서버 밖**: 그 시간대 Prometheus `http_server_requests_seconds_max` 두 API 최대 0.5s, VPS CPU ~6%, nginx 에 499 없음(닿기 전 막힘), 같은 시각 SSH timeout, 외부 측정 첫 요청 `tcp=1.14s`(SYN 재전송). 지금 외부 0.19~0.34s.
- 그래도 구조 보강: 패턴 적재 전체 예산 5초(남은 예산을 호출별 read timeout 으로), 부팅 워밍업(별도 데몬 스레드, `!worker`), 역 연결 결과 캐시(원본 목록 동일 객체일 때만).
- 도착 API(#277)는 Redis snapshot 만 읽는다(내부 22ms) — 변경 없음.

### 함정
- 0 이하 read timeout 을 넘기면 HTTP 클라이언트가 무한 대기로 읽는다 → 예산 소진 시 호출 자체를 안 함
- Java 17 이라 가상 스레드 없음 → 데몬 Thread
- 워밍업을 ApplicationReadyEvent 스레드에서 돌리면 준비 완료가 늦어져 deploy.sh healthcheck(180s) 위협

### 검증
- subway·arrival·api.subway·otp 패키지 285 / 실패 0. 신규: 예산 전달·소진·낡은 값 3, 워밍업 3, 연결 캐시 1.

### 배포
- 마이그레이션 없음. api 이미지 교체. 배포 후 `지하철 패턴 워밍업 완료 keys=38` 로그 + api 힙 증가분 확인.
## standalone-track PR-3 — 지하철 열차 후보·선택 (2026-10-07, L 의 3/5, #265)
### 확정
- API(기존 `JourneySubwayBindingController`, `/api/journeys/{id}/subway/…`): `GET train-candidates` → `[{trainNo, destination, express, trainType, arrivalSeconds, observedAt}]`, `POST train-selection {trainNo, routeRevision}` → `SubwayBindingView`. 플래그 `journey.subway.selection.enabled`(env `JOURNEY_SUBWAY_SELECTION_ENABLED`, 기본 false, 꺼지면 404). **일반 여정에도 적용**
- "대기 단계" = 세션 `currentLegIndex` 단계가 WAIT_SUBWAY 이고 다음이 RIDE_SUBWAY. 아니면 409 `NOT_WAITING_FOR_SUBWAY`
- 후보 = `WaitSubwayArrivalPredictor` 와 같은 원천(역별 실시간 `arrivalsByStation(routeId, startStopId, startName, wayCode)`) 앞 2대. 방향(`wayCode`, 대기 단계 선택 노선 후보) 모르면 빈 목록
- 선택 검증은 후보 2대가 아니라 **지금 도착 목록 전체**에서 trainNo 를 찾는다(앞차가 들어와 순위가 밀린 열차를 거절하지 않게). 없으면 422 `TRAIN_NOT_IN_CANDIDATES`. routeRevision 다르면 409 `STALE_ROUTE_REVISION`
- 고정: 식별 4칸 = trainNo · `ServiceDateResolver.resolve(now)` · 대기 단계 routeId(lineId) · `SubwayDirectionNormalizer.fromWayCode(lineId, wayCode)`(매처와 같은 값 공간). 없으면 `SubwayLegBinding.selected`(CONFIRMED·USER_SELECTED), 있으면 잠금(`findForUpdate`) 뒤 `selectByUser`(확정도 덮음, 이동 순번 유지, version++). 감사 `USER_SELECTED`(DECISION, chosen_* 4칸) 먼저
- 링 게이트: `findRingTargets` 에 `(source IS DISTINCT FROM 'USER_SELECTED' OR last_movement_seq IS NOT NULL)`, 조합기(`JourneySubwayGuidanceComposer`)도 `isSelectedBeforeMovement` 면 pending 모양(상태 CONFIRMED, 진행 값 비움)
- 이동 관측: 기존 `submit` 이 이미 이동 순번을 올린다 — USER_SELECTED 면 `boardedDetectedAt` 만 추가로 채움. 상태 그대로, 매처(`findMatchable` = PENDING/PROVISIONAL)는 안 봄
- sweep(`abandonStaleWithoutMovement`)에 `NOT EXISTS (journey_leg.state = 'PENDING')` — 타기 전 리셋 행을 6분 뒤 접지 않음 → 이후 이동 관측으로 M7 복귀
- `inheritTo`: USER_SELECTED 는 출처 유지, 그 밖은 INHERITED_ON_REROUTE(기존). 감사 이벤트 타입은 그대로 INHERITED_ON_REROUTE
- 지표 `subway.selection{result}` = selected/not-in-candidates/not-waiting/stale/busy
- 동시에 이동 관측이 행을 먼저 만들면(uk_subway_leg_binding_leg) 409 `SUBWAY_BINDING_BUSY`(재시도하면 그 행 위에 얹힘)
### 함정
- **`NOT (b.source = 'USER_SELECTED' AND …)` 는 source 가 null 인 행(정차 관측이 먼저 연 행)을 링에서 떨어뜨린다**(NULL 3값 논리). `IS DISTINCT FROM` 으로 씀 — `SubwayTrainSelectionIT.nullSourceStaysInRing` 이 고정
- `SubwayBindingView` 는 "열차번호를 담지 않는다"가 원칙이지만 후보 응답은 선택 키라 trainNo 를 싣는다(별도 DTO `SubwayTrainCandidate`)
- record 칸 이름 `wait` 은 `Object.wait()` 와 충돌해 컴파일 실패 → `waitLeg`
- 일회용 DB 는 역 표가 비어 보강이 대기 단계 `start_stop_id` 를 못 채운다 — IT 에서 jdbc 로 채움
### 좌표
- `SubwayTrainSelectionService`(+Tx), `SubwayLegBinding.selected/selectByUser/markBoardedDetected/isSelectedBeforeMovement/inheritTo`, `SubwayLegBindingRepository.findRingTargets/abandonStaleWithoutMovement`, `SubwayBoardingEvidenceService.advanceSequence`, `JourneySubwayGuidanceComposer.compose`
### 미결
- 계승은 엔티티 단위 시험(`SubwayLegBindingSelectionTest.inheritKeepsUserSelected`)으로 고정 — 재탐색 수락 전체 IT 는 없음(수락 경로는 `inheritTo` 만 부른다)
### 배포
- **V60 VPS 수동배치 필요**(source·event_type CHECK 확장). 제약만 넓히므로 앱 배포 전·후 아무 때나 되지만, 플래그를 켜기 전엔 반드시 적용
- 함정 추가: 기존 `SubwayBindingMatchIT.sweepAbandonsPendingWithoutMovement` 가 막 시작한 여정(타는 단계 PENDING)의 행을 접는 것을 기대했다 → 새 규칙과 충돌. 최근 결정 기준으로 옛 시험을 고침(타는 단계 ACTIVE 로 둔 뒤 접힘 확인)
### 검증
- 신규: `SubwayTrainSelectionIT` 6(일회용 postgis — 선택 4칸·감사, 이동 전 링 제외/이동 후 포함, 매처 제외, 리셋 후 sweep 생존→M7 복귀, 타는 중이면 접힘, null source 링 유지), `SubwayLegBindingSelectionTest` 4, `SubwayTrainSelectionServiceTest` 8, `JourneySubwaySelectionControllerTest` 4 — 전부 통과
- 변이 확인: 링 게이트·sweep NOT EXISTS 를 빼면 `SubwayTrainSelectionIT` 2건 실패 → 원복
- 전체 `./gradlew test --continue`: 2555 중 실패 12 = 기존 무관 10(JourneyEvaluationIT 7·JourneyEvalReadPathIT 2·JourneyTransferAssessmentIT 1) + `JourneyNotificationSchemaIT` 간헐 1(단독 20/20) + `SubwayBindingMatchIT` 1(위 함정, 수정 후 13/13)

## ~~standalone-track PR-4 — 버스 위치 + 승차 시각 대조 (2026-10-07, L 의 4/5, #265)~~ 취소: 아래 "PR-4 revert" 참고
### 확정 (PLAN 과 다르게 간 것 포함)
- **대상 목록은 DB 가 소유**(PLAN 의 Redis 집합·AFTER_COMMIT 이벤트 대신): worker tick 이 `kind=TRACK`·ACTIVE 여정 중 (a) 현재 단계 WAIT_BUS 또는 (b) RIDE_BUS 이고 `vehicle_matched_at IS NULL` 을 읽는다. 시작·전이·종료마다 집합을 고칠 필요가 없고 빠뜨릴 길이 없다. 명령 트랜잭션·재전송 경로를 건드리지 않는다(PLAN 의 "apply 안에서 부르지 않는다"를 구조로 만족). 일반 여정은 대상 아님
- **판정 종료 표시 = `vehicle_matched_at`**: 고정이면 차량 칼럼 3개 + 시각, 못 찾음·포기·애매·사용자 해제면 차량 없이 시각만. 차 있으면 다시 판정 안 함 → 중복 tick·재전송 방어가 SQL 조건(`WHERE vehicle_matched_at IS NULL`)
- 차량 칼럼 4개(`vehicle_id` 포함)는 엔티티에서 `updatable = false` — 엔티티 저장(@DynamicUpdate 없음)이 worker 쓰기를 덮지 못한다. 쓰기는 `assignVehicle`/`finishVehicleUnmatched`/`resetVehicle` 부분 UPDATE 뿐. 복제(`copyTo`)에는 실림
- V61 에 PLAN 외 `journey_leg_vehicle_identity_check`(id·종류·출처 함께) 추가 — 운영 `vehicle_id` 비-null 0/972 행 확인 후
- 승차 시각 t0 = `journey_event` 에서 그 탑승 단계로 넘어간 APPLIED `CLIENT_LEG_TRANSITION_DETECTED` 의 `client_occurred_at`(서버 수신과 120초 넘게 벌어지면 `server_received_at`), 없으면 `activatedAt`
- 대조 시점 = t0 + 창 60 + 근접 간격 15 (창 안 늦게 떠난 두 번째 차를 본 뒤 고른다). 0대면 +30초 뒤 포기, 예산 소진이면 바로 포기
- 관찰: 기다리는 동안 노선 위치 60초, 승차 2정거장 안 접근 차 있으면 15초, 탄 뒤 대조 대기 중 15초. 노선 단위로 여정끼리 공유. 출발 = 직전 관측에서 안 지났고(앞 3정거장 안) 지금 지남(`Vehicle.isPast`: 순번 초과 또는 같은 순번에서 떠남 — 서울 stopFlag 0, 경기 stateCd 2, TAGO 상태 없음). 처음 본 차는 사건 아님
- 승차 순번: 서울 = 노선 후보 `boardingSeq`(+ `routeId`), 경기·TAGO = #267 표에서 승차 정류장(`sourceStartStopId`) 첫 등장 `seq`. TAGO 도시코드 = `TagoCityCodeLookup.cityCodeOfRoute`
- 예산: `BusApiQuotaPort.tryAcquire("track-bus-pos-{seoul|gyeonggi|tago}", journey.track.bus.position-budget.*)`(기본 2000/3000/20000). 서울은 이에 더해 기존 검색용 위치 경로(검색 몫 quota·차단기·캐시)를 그대로 탄다
- 위치 캐시(PR-5 용): 관찰이 받은 성공 관측을 Redis `track:bus:pos:{provider}:{routeId}` TTL 2분에 쓴다(`BusVehiclePositionCachePort`)
- 해제 API `POST /api/journeys/{id}/bus/vehicle/reset` → 204(지금 단계가 RIDE_BUS 아니면 409 `NOT_RIDING_BUS`). 다시 찾지 않음
- 스케줄러 `TrackBusVehicleWatchScheduler` = worker 프로필 + `journey.track.bus.enabled`(env `JOURNEY_TRACK_BUS_ENABLED`, 기본 false)
- 지표 `bus.position{provider,result}`(ok/limited/blocked/error/budget), `bus.match{provider,result}`(matched/ambiguous/none/budget/user-reset)
- 신규 의존성 없음
### 함정
- 실측(2026-10-07): 경기 `getBusLocationListv2` → `msgBody.busLocationList[]`(vehId·plateNo·stationSeq·stateCd, 숫자형). TAGO `getRouteAcctoBusLcList` → `items.item[]`(vehicleno·nodeord·nodeid·gpslati/long), 상태 칸 없음. 픽스처 `src/test/resources/bus/position/`
- 관찰 상태(직전 관측·출발 사건)는 worker 메모리 — worker 재시작 시 그 사이 탑승은 차량 없이 추적(수용)
- `TrackBusVehicleWatchServiceTest` 첫 판은 조회 3번을 기대했으나 탄 뒤 대조 대기 중에도 15초마다 본다(5번) — 의도대로
- `Snapshot.isOk()` 가 JSON 에 `ok` 로 실려 역직렬화를 깨지 않게 `@JsonIgnore`
- `journey_leg` 에 칼럼을 매핑했으므로 V59 를 나열하는 `@Sql` IT 13개에 V61 추가(PR-1 함정과 같은 작업)
### 좌표
- `TrackBusVehicleWatchService`(tick·collectTargets·poll·decide·boardedAt), `BusVehicleMatcher`, `BusDepartureDetector`, `BusPositionPort`+`BusPositionAdapter`+`RegionalBusLocationClient`, `RedisBusVehiclePositionCache`, `JourneyLegRepository.assignVehicle/finishVehicleUnmatched/resetVehicle`, `JourneyEventRepository.findAppliedTransitionInto`, `TrackBusVehicleResetService`, `JourneyBusVehicleController`
### 미결
- 자동 해제(고정 차량이 지나갔는데 사용자 이동 없음)는 후속(PLAN 대로)
- 서울 위치 조회는 검색 몫 quota(5000)도 함께 깎는다 — TRACK 사용량이 늘면 분리 검토
### 배포
- **V61 VPS 수동배치 필요** — `ddl-auto: validate` 라 앱 배포 **전에**. 켤 때 worker 에 `JOURNEY_TRACK_BUS_ENABLED=true`(api 에는 무의미 — worker 프로필 전용)
### 검증
- 신규: `BusVehicleMatcherTest` 5, `BusDepartureDetectorTest` 5, `RegionalBusLocationClientTest` 5(경기·TAGO 실측 픽스처 + 서울 매핑), `TrackBusVehicleWatchServiceTest` 8(관찰→고정, 유휴 60초, 애매, 0대 포기, 예산 포기, 판정 끝난 단계 제외, 기기 시계 의심, 서울 순번), `JourneyLegVehicleIT` 5(엔티티 저장이 차량 칼럼을 안 지움·판정 종료 고정·해제·제약), `RedisBusVehiclePositionCacheTest` 2, `JourneyBusVehicleControllerTest` 1 — 전부 통과
- 변이 확인: `vehicleId` 의 `updatable = false` 를 빼면 `JourneyLegVehicleIT.entitySaveKeepsVehicle` 실패 → 원복
- 전체 `./gradlew test --continue`: 2595 중 실패 12 = 기존 무관 10 + `RerouteRemainingPathOtpIT.otpGraphQlErrorIsEmptyWithoutOdsayFallback` 1(**변경 stash 한 main 에서도 실패** — 기존 무관, PR-3 실행 땐 통과했으니 시각 의존 간헐로 보임) + `JourneyLegVehicleIT` 1(전체 실행 중 testcontainers `TransactionException`, 단독 5/5 — `JourneyNotificationSchemaIT` 와 같은 간헐)

## #299 역 도착 응답 departed (2026-10-07, feat/subway-departed-trains, M)

### 확정
- 원천 = 위치 수집기(M7) 출발 사건 `SubwayTrainLogPort.findDepartures`(Redis `subway:dep:{date}:{line}:{dir}:{station}` ZSET, TTL 15m). 이슈의 "새 버퍼 필요" 전제는 틀림. 탑승 판정과 같은 조회 → trainNo 가 #293 과 맞음.
- 창 10분·최대 3대·trainNo 중복 제거(최신 사건). departedAt = event.estimate()(구간 가운데, KST offset). 행선지 = terminalId→statn_nm(요청 안 Map 캐시). trainType NORMAL/EXPRESS/SPECIAL → 일반/급행/특급. currentStationName = lastSeen 이 사건 from 이후일 때만.
- 방향 = 곧 오는 열차 ∪ 떠난 열차. label 은 곧 오는 열차 없으면 떠난 열차 행선지.
- 로그 조회 예외 → departed 빈 목록 + warn, trains 영향 없음.
- 서비스일 경계 04:00 — now 와 now−10m 의 서비스일이 다르면 둘 다 조회.

### 함정
- 운영 Redis 에 출발 사건이 있는 노선 = 1001~1009·1032·1065·1077·1092(2026-10-07 실측). 설정엔 1063·1067·1075 도 있지만 사건 0 → 위치 원천이 안 줌. 1081·1093·1094 는 설정에도 없음.

### 검증
- subway·arrival·api.subway·otp 패키지 292 / 실패 0. 신규 5(최근 순 3대·창, 행선지·종류·위치, 로그 실패, 서비스일 경계, trainType) + 컨트롤러 JSON.

### 배포
- 마이그레이션 없음. api 이미지 교체.

## standalone-track PR-4 revert — 단독 버스 차량 시각 대조 걷어냄 (2026-10-07, #265 결정 5차)
### 확정
- **#265 결정 5차(2026-10-07 13:47 KST): 단독 버스 추적은 차량을 특정하지 않는다.** 이유 = ①시각 대조는 "출발과 거의 동시에 탑승을 누른다"가 전제인데 실제로는 타고 한참 뒤 앱을 연다 → 엉뚱한 차 고정 ②같은 노선이면 어느 차든 진행이 같고 알릴 것은 "어디서 내리나"뿐 ③탑승 중 위치는 앱 GPS 를 노선 선에 투영(네이티브 버스 엔진 M5/M6)
- PLAN v2(PR #285)가 같은 분(13:47)에 올라와 결정 5차를 반영하지 못했고, PR-4(#296)는 그 PLAN 대로 구현·머지·배포(플래그 off)됐다 → 이 PR 로 코드 전부 revert(관찰·대조·위치 어댑터·Redis 위치 캐시·해제 API·`JourneyLeg` 차량 칼럼 매핑·설정)
- **V61 파일은 남긴다** — 운영이 이미 v61(2026-10-07 16:11 배포)이라 파일을 지우면 Flyway validate 가 "적용됐는데 로컬에 없음"으로 기동을 막는다. 칼럼 3개(`vehicle_id_type`·`vehicle_source`·`vehicle_matched_at`)는 nullable 이고 엔티티가 매핑하지 않아 아무도 안 쓴다. 정리가 필요하면 V62 drop 으로 따로
- 운영 영향 없음: 플래그 `JOURNEY_TRACK_BUS_ENABLED` 는 설정된 적 없다(관찰 스케줄러가 한 번도 뜨지 않음, `journey_leg.vehicle_id` 비-null 0)
### 함정
- 구현 전에 PLAN 만 보고 issue 최신 댓글(결정)을 다시 확인하지 않았다 — **PLAN 과 issue 결정이 같은 시각에 올라오면 issue 결정이 우선**인지 착수 전에 대조할 것
### 배포
- 마이그레이션 변화 없음(V61 유지). 다음 앱 배포에 실려 가면 끝
## standalone-track HANDOFF + 앱 설계 대조 (2026-10-07, #265)
### 확정
- PR-5 의 `busGuidance` 는 **넣지 않는다**(결정 5차: 단독 버스는 차량을 특정하지 않고 앱 엔진이 진행을 낸다. 남는 값 = 대기 예측 초·앱 `remainingStops` 뿐인데 둘 다 앱이 이미 가진다). 브랜치 `feat/standalone-track-pr5-bus-guidance` 의 wip 커밋 af2671a8 은 참고용으로만 남김(미푸시)
- HANDOFF(`docs/plan/standalone-track/HANDOFF.md`)는 결정 5차 기준. 버스 차량·해제·버스 안내 절 삭제
- nextroute-mobile `docs/plan/home-ride-tracking/DESIGN.md` 와 대조한 어긋남 5개를 HANDOFF §5 에 적음: ①버스 시작 id 공간(앱 = 서울 routeId·stopId·seq, 서버 = OTP GTFS) ②지하철 시작 id 공간(앱 = 서울 stationId·lineId, #283 stations-ahead) ③열차 선택 시점(앱 = 시작할 때, 서버 PR-3 = 시작한 여정의 대기 단계 안) ④후보(앱 = 방금 떠난 3대+곧 오는 2대, 서버 = 곧 오는 2대) ⑤탑승 후 시작(앱 `[탔어요]`, 서버 = 대기 단계부터)
### 좌표
- 떠난 열차 원천 후보 = `SubwayTrainLogPort.findDepartures`(M7 열차 로그). 지하철 서울 id ↔ GTFS = #283 `SubwayPatternStationMatcher`. 서울 버스 ↔ GTFS = `OtpSeoulIdentityResolver` 규칙의 역(`1:BR_TAGO_SEB{9}`·`1:BS_TAGO_SEB{9}`, 공항버스 `_U/_D` 접미사 주의)
### 미결
- 위 5개를 맞추는 서버 PR(시작 입력 확장 + 시작 시 열차 확정 + 떠난 열차 후보 + 탑승 후 시작) — 범위가 PLAN 밖이라 티어 판단 필요
- PR-2 조회 3개 API 는 앱이 기존 API 를 쓰면 정리 대상
- ~~HANDOFF §5 의 "맞추는 방향(서버)" 열(시작이 앱 id 수용 = B안)~~ 취소: 같은 시각 올라온 issue #298·#299 를 못 보고 썼다. 사용자 결정(2026-10-07) = **#298 A안**(앱이 쓰는 응답에 GTFS id 싣기). §5 를 이슈 기준으로 고치고, "시작 직후 곧 오는 열차 trainNo 로 바로 선택 가능 / 떠난 열차는 422(#299 에서 같이)"를 답으로 적음
## gtfs-id-exposure — 역·정류장 응답에 GTFS id (2026-10-07, M, #298 A안)
### 확정
- 시작 API(#290) 계약은 그대로. 앱이 쓰는 응답에 칸만 추가: `NearbyBusStopResult.gtfsStopId`, `StopRouteResult.routeGtfsId`, `RouteStopsResult.routeGtfsId`·`RouteStop.gtfsStopId`, `NearbySubwayStationResult.gtfsStopId`, `SubwayStationsAheadResponse.boardGtfsStopId`·`Station.gtfsStopId`·`Station.routeGtfsId`
- 버스 = 규칙(`OtpGtfsIds`: 9자리 숫자 → `1:BS_TAGO_SEB{id}`·`1:BR_TAGO_SEB{id}`, 아니면 null). 공항버스 `_U/_D` 는 `TrackPatternSelector.routeMatches` 가 흡수(방향은 승차 뒤 하차 순서가 가름)
- 지하철 = #283 대응 재사용: OTP 패턴 쿼리에 `gtfsId` 추가 → `RailPattern.Stop.gtfsId` → `StationsAheadCalculator.Stop.gtfsStopId`. `Station.routeGtfsId` = 대표 꼬리(`representative`) 패턴의 노선 — 남은 역 목록은 한 대표 패턴의 공통 앞부분이라 목록 안 역은 모두 같은 노선이다
- 근처 지하철역 = `SubwayGtfsStopIndex`(캐시만: `SubwayLinePatternService.cachedPatterns`, OTP 호출 0). 기동 워밍업 전엔 null
- 서울만(이 API 들은 서울 데이터). 경기·지방은 정류장이 앱 흐름에 들어올 때 같은 방식으로
- 생성자 호환: `RailPattern.Stop`·`StationsAheadCalculator.Stop`·`Ahead` 는 옛 생성자를 남겼다(시험·픽스처 무변경)
- PLAN 의 "HANDOFF 갱신"은 HANDOFF 가 아직 main 에 없어(#297 미머지) **#297 브랜치에서** 한다
### 검증
- 라이브(운영 OTP 터널, 읽기만, 커밋 안 함): 2호선 내선 패턴 25개, 정차 130/130 에 gtfsId. 강남(`1:RS_ACC1_S-1-0222`) → 4역 뒤 gtfsId 로 `TrackPatternSelector.select` = `…02-1I::13`. 서울 버스 규칙 id 4개 전부 `stop(id)` 존재(패턴 3~11), 640 노선 `1:BR_TAGO_SEB100100093` 이 그 정류장 패턴에 있음
- 신규·갱신 시험: `OtpGtfsIdsTest` 2, `StopSelectionServiceTest.carriesGtfsIds`, `GetNearbyBusStopsUseCaseTest.nearbySubwayCarriesGtfsStopId`, `SubwayGtfsStopIndexTest` 2, `SubwayStationsAheadServiceTest`(board·역 gtfs id·routeGtfsId), `OtpSubwayRoutePatternAdapterTest`(gtfsId 있음/없음), `SubwayStationControllerTest`(JSON 경로), `TrackPatternSelectorTest.airportBusSuffix`
### 배포
- 마이그레이션 없음. 응답 칸 추가만(기존 앱 무영향)
- 전체 `./gradlew test --continue`: 2602 중 실패 12 = 기존 무관 10 + `RerouteRemainingPathOtpIT` 1(main 에서도) + `JourneyLegVehicleIT` 1(#296 의 IT, revert #300 미머지라 아직 main 에 있음 — 전체 실행 간헐, 단독 통과). 이 PR 새 실패 0

## 지하철 열차 선택 — 방금 떠난 열차도 받기 (2026-10-07, S, #299 후속)
### 확정
- #301 은 역 도착 응답에 `departed` 만 더했고 `train-selection`(#293)은 실시간 도착 목록만 검증해 떠난 열차가 422 였다. 선택 검증에 "같은 역(대기 단계 `startStopId`)·방향(`fromWayCode`)을 10분 안에 떠난 열차"를 더함 — 원천 = `SubwayTrainLogPort.findDepartures`(#301 과 같은 조회·같은 10분 창). 운행일 = 그 출발 사건이 나온 운행일(04:00 경계에서 지금 운행일과 다를 수 있다)
- 출발 로그 실패 = 422(앱이 목록을 다시 받는다). 지표 `subway.selection{result=selected-departed}`
### 검증
- `SubwayTrainSelectionServiceTest` 10/10(신규 2: 떠난 열차 → 전날 운행일로 확정 / 어디에도 없음·로그 실패 422), `SubwayTrainSelectionIT` 6/6, `JourneySubwaySelectionControllerTest` 4/4, `SubwayStationArrivalQueryServiceTest` 14/14
### 배포
- 마이그레이션 없음. api 교체

## 지하철 열차 고르면 바로 링 (2026-10-07, M, #304)
### 확정
- 링 대상 질의(`findRingTargets`)에서 `USER_SELECTED + last_movement_seq IS NULL` 제외 조건 삭제. 안내 조립(`JourneySubwayGuidanceComposer`)의 `isSelectedBeforeMovement()` → pending 분기 삭제(링이 아직 없을 때 pending 은 그대로)
- **상류 열차 = 이슈 제안안**: 이동 관측 전 사용자 선택이면 링을 **승차역(순번 0)부터** 센다(`SubwayRingContext.startSequence`, 갱신기가 `isSelectedBeforeMovement()` 로 정함). 승차역 구간은 진행률·보간 상한(`progressTarget`) **0 고정**, 도착 예정 시각은 싣는다. `remainingStations` = 탈 정거장 수(승차역 제외 = `alightingSequence`). 새 phase 없음(`RIDE_SUBWAY` 그대로) — 앱 레이아웃 추가 불필요
- 승차역 도착(arvlCd=1 / 위치 ARRIVED)·출발 → 바로 다음 역 구간 0. 승차역에서 1.00 을 찍지 않는다(`anchored` 에서 넘김)
- 방금 떠난 열차: 승차역 도착 목록에 없으면 위치 폴백이 승차역 뒤 역을 가리켜 그 역으로 바로 건너뛴다(기존 `fromPosition` 규칙)
- 직전 링이 있으면 시작점은 안 쓴다 — 이동 관측이 나중에 와도 링은 이어진다. Redis 유실 뒤 이동 관측이 있으면 예전대로 1 부터
- **첫 링 = 다음 tick(5초)**. api 에서 링을 직접 돌리지 않음: 링·push 플래그가 worker 전용 env 라 api 에선 push 가 안 나가고, api 가 먼저 링을 저장하면 worker 의 `enqueueIfChanged(previous, state)` 가 "변화 없음"으로 첫 push 를 놓친다. 배치 상한 100 > 진행 중 여정 수라 새 행은 다음 tick 에 잡힌다
- `SubwayRingPushRelayTxService` 는 `isBound()` 만 본다 — 수정 불필요 확인
### 함정
- 갱신기 `processOne` 에 `target < 1 → return` 가드가 있어 승차역(0) 링이 저장되지 않았다 ← 계산기 단위 시험은 통과, `SubwayRingIT` 신규 케이스가 잡음 / 가드 = `target < 0`(체인 못 씀만 거름)
- 계산기의 `Math.max(target, 1)` 두 곳(`targetSequence`·`anchored`)도 0 을 1 로 올려 버린다 — 0 허용으로 바꿈
### 좌표
- `SubwayRingCalculator`(`BOARDING_SEQUENCE`·`capOf`·`remainingOf`), `SubwayRingUpdateService.processOne`, `SubwayRingContext`(5인자 생성자 = 시작 1, 기존 호출 무변경)
### 검증
- `SubwayRingCalculatorTest`(신규 Nested 6), `JourneySubwayGuidanceComposerTest`(신규 2), `SubwayRingIT` 14/14(신규 1: 선택 직후 첫 링 = 승차역·남은 2·진행 0·ETA 있음), `SubwayTrainSelectionIT` 6/6(선택 즉시 링 대상으로 기대 뒤집음), `SubwayRideScenarioIT` 9/9, `SubwayBoardingEvidenceServiceTest`·`SubwayRingPush*Test` 통과
- 일회용 DB(r304-pg, 개발 DB schema-only V58 + flyway 이력 → V59·V60 자동) + redis + 더미 env 5개(GONG_API·KAKAO_REST_API_KEY·SEOUL_BUS_USAGE·SEOUL_SUBWAY_MASTER·SEOUL_SUBWAY_ROUTE)
### 배포
- 마이그레이션 없음. api(조립)·worker(링 대상·계산) 둘 다 교체
- 전체 `./gradlew test --continue`: 2587 중 실패 12 = 기존 무관 11(`JourneyEvalReadPathIT` 2·`JourneyEvaluationIT` 7·`JourneyTransferAssessmentIT` 1·`RerouteRemainingPathOtpIT` 1) + `JourneyNotificationSchemaIT` 1(전체 실행 중 "Unable to rollback against JDBC Connection", 단독 20/20 통과 = 간헐·기존 무관). 이 PR 새 실패 0

## 지하철 링 버전 + 전역 진행률 되감김 (2026-10-07, M, #307·#306)
### 확정
- #306 조사: 서버 링은 뒤로 가지 않았다 — `subway_ring_push_outbox`(토큰 없어 SKIPPED여도 계산된 화면 그대로 남음) journey 158 프레임 = 방배→사당→낙성대(20:53:13~20:55:59)→서울대입구→봉천. 앱의 20:54:18 `사당` 은 옛 스냅샷이 늦게 덮은 것으로 추정. 원인 증거·결론은 이슈 댓글
- `guidanceVersion` = 링 화면 마지막 변경 시각(epoch millis). 카운터 기각(링 키가 고정 세대마다 바뀌어 0 으로 돌아감). 변경 판정 = `SubwayRingPushDecider.decide().isPresent()`(push 와 같은 기준). 같은 millis 는 +1. Redis 옛 값(칸 없음)=0 → 다음 tick 에 부여
- 스냅샷 최상위 `guidanceVersion` 은 레코드 칸이 아니라 `@JsonProperty` 파생 메서드(`subwayGuidance.version`) — `withEta` 재생성에서 떨어질 위험 없음, 기존 생성자 무변경
- `afterGuidanceVersion` 은 기존 두 값과 같은 `<=`. 셋 다 null 이면 200. 링 없으면 0
- ETA 는 버전에 안 넣음(조회 시점 계산값). 첫 링을 선택 응답에 싣는 안도 안 함(#304: api 가 먼저 만들면 첫 push 유실)
- 진행률 버그: `fromArrival` 의 `PREV_DEPARTED/ENTERING/ARRIVED` 가 같은 구간에서 반복될 때마다 0 재앵커 → 앱 링 15~30초마다 0 으로 되감김(journey 158 outbox: 낙성대 구간 4프레임 연속 0.0000, base 만 이동). 같은 구간이면 `reanchored`, 초 없으면 `freeze`, 첫 관측만 0
### 함정
- 앱 보간 공식 확인(`nextroute-mobile src/features/live-activity/navigation-live-activity.tsx:126-137`): base 시점 진행률 → ETA 시점 progressTarget 직선. 그래서 서버가 base 진행률을 0 으로 다시 주면 그대로 되감긴다
### 좌표
- `SubwayRingCalculator.fromPreviousStation`, `SubwayRingUpdateService.guidanceVersionOf`, `SubwayRingState.withGuidanceVersion`, `JourneySnapshot.guidanceVersion()`, `JourneyController.unchanged`
### 검증
- 회귀 시험 3건(`SubwayRingCalculatorTest$RepeatedPreviousStation`)은 수정 전 코드에서 실패·수정 후 통과 확인
- `SubwayRing*`·`JourneyControllerTest`·`JourneySubwayGuidance*`·`JourneySnapshot*`·`SubwayRideScenarioIT`·`SubwayTrainSelectionIT` 180/180, `RedisSubwayRingAdapterIT` 7/7(버전 왕복 + 칸 없는 옛 값 = 0)
### 배포
- 마이그레이션 없음. worker(버전 저장·계산기) → api(스냅샷·파라미터) 순
- 전체 `./gradlew test --continue`: 2596 중 실패 12 = 기존 무관(JourneyEvalReadPathIT 2·JourneyEvaluationIT 7·JourneyTransferAssessmentIT 1·RerouteRemainingPathOtpIT 1·간헐 JourneyNotificationSchemaIT 1) — #304 때와 같은 구성, 새 실패 0

## #313 track 응답에 실시간 조회 id (2026-10-07, feat/track-realtime-ids, M)

### 확정
- Stop += distanceMeters·arrivalStopId·arsId·arrivalStationId·lineId·lineName, Direction += arrivalRouteId. 못 풀면 null.
- 버스: `OtpSeoulIdentityResolver.busStopArrivalId/busRouteArrivalId`(신규 공개, 기존 메서드 동작 불변) — SEB=DB 존재 확인, GGB=9자리, 그 밖 TAGO 접두사 포함(`ICB121000541`). 버스 도착 API 가 id 모양으로 원천 선택.
- 지하철: StopsNear 에 `routes{shortName}` 추가(운영 확인: 강남(2호선)→[서울2호선×2], 방향별 route 라 중복 → 중복 제거). shortName→lineId = `SubwayRoutePatternAssembler.lineIdOf`(명시표), 역 = `SubwayPatternStationMatcher`. 호선마다 한 줄.
- `TransitPatternPort.stopsNear` 반환 = `List<NearStop(stop, distanceMeters, routeShortNames)>`.

### 함정
- 같은 물리 정류장이 SEB·GGB·ICB 로 여러 번 온다(fixture stops-near-gangnam) — 기존 동작, 이번엔 안 합침
- 스크래치 clone 에서 journey 패키지 IT 248 실패 = DB 인증(`${POSTGRES_USER}`) 컨텍스트 로드 실패, 기존 무관

### 검증
- 단위: journey·route·subway·api.journey·otp 패키지 1430 / 실패 0(IT 제외). 신규 TrackRealtimeIdResolverTest 6, TrackSelectionQueryServiceTest +2, OtpTransitPatternAdapterTest stopsNear(routes·distance), JourneyTrackControllerTest JSON.

### 배포
- 마이그레이션 없음. api 이미지. 플래그 JOURNEY_TRACK_ENABLED 는 off 그대로(켜는 건 별도 결정).
## 단독 추적 하차 선택 노선 선 API (2026-10-07, M, #312)
### 확정
- `GET /api/journeys/track/routes/{routeGtfsId}/shape?from&to?&pattern?` → `{points:[{x,y}], quality: SHAPE_OK|STOP_LINE}`. 대안(alightStops 에 형상 싣기)은 안 함 — 기존 응답 계약을 안 건드림
- slice 는 `TrackLegFactory.geometry` 그대로 재사용 → 시작 후 안내 선과 같은 점열(테스트로 고정)
- 패턴 고르기: `to` 있으면 시작과 같은 `TrackPatternSelector.select`(가장 짧은 구간 패턴), 없으면 하차 후보 조회와 같은 `branchFor` + 한 바퀴 끝(= alightStops 마지막 정류장)
- 형상 못 자르면 승차~하차 정류장 직선 + `STOP_LINE`. `LegGeometry` 의 "가짜 직선 금지"는 안내 진행 판정용 규칙이고, 이 API 는 품질을 명시하므로 이슈 요구대로 직선을 준다
- 상세 정류장 수 ≠ 정류장 패턴 정류장 수면 `TRACK_PATTERN_UNAVAILABLE`(시작과 같은 거절, 그래프 섞임 방지)
### 좌표
- `TrackSelectionQueryService.shape`/`shapeOf`, `TrackViews.Shape`·`ShapeQuality`, `JourneyTrackController.shape`
### 검증
- `TrackSelectionQueryServiceTest` 9/9(신규 4: 한 바퀴 끝·to slice·STOP_LINE·422/패턴 불일치), `JourneyTrackControllerTest` 6/6(신규 1 + 인증 1줄), `TrackLegFactoryTest` 7/7, `TrackJourneyStartServiceTest` 10/10
- journey 패키지 단위 테스트 전부 통과. `*IT` 253 실패 = 스크래치 clone 에 DB 자격증명 없음(`password authentication failed for user "${POSTGRES_USER}"`) — 환경, 이 변경 무관(DB 미접촉)
### 배포
- 마이그레이션 없음. api 만 교체. 플래그 `journey.track.enabled` 그대로(꺼지면 404)
## 지하철 링 ETA = 앞 열차 실측 구간시간 (2026-10-07, M, 해결안 3)
### 확정
- **원칙 변경(사용자 결정)**: "정적 값·중위값으로 채우지 않는다" → "오늘 실제로 지나간 열차의 실측은 쓴다". 시간표·정적 소요시간은 여전히 안 씀
- 근거 실측(Redis `realtime:subway:snapshot` 15초 표본, 22:38~23:40 전 노선 63만 행, "실제 도착" = 같은 원천의 첫 도착(1, 없으면 0) 관측): 전역 출발(3) 뒤 도착까지 API `barvlDt` 치우침 +54초·오차 55초·30초 초과 80% vs 최근 앞 열차 3대 중앙값 치우침 0·오차 15초·24%, 커버리지 82%. 노선별 1호선 85→13, 2호선 48→8, 4호선 70→15, 9호선 62→9, 5·7호선 38→21초, 공항·GTX-A 57~70초(약함). 신분당·공항·GTX-A 는 API 초 자체가 없음
- 기각한 해결안 1·2("처음 본 시각 + 초"): 12분 2호선 표본에서 전체 오차 38→43초로 악화 — 전역 정차(5) 중엔 ETA 가 밀리는 게 맞고, 2역 이상 전(99)은 오히려 나빠짐. Codex 리뷰(여정별 기록 기각·위치 원천 섞기 위험·ETA 보존·유효기간 전달·ETA 경과 규칙) 반영
- 수집기(worker)가 열차 단위로 기록: 그 역 행 `arvlCd=3` 처음 본 `recptnDt` = 출발, `1`·`0` = 도착 → 소요시간 표본(0<d≤900초) 최신 3개. Redis `subway:seg:dep:*`(hash, 30분) / `subway:seg:dur:*`(list, 3시간), 각 Lua 원자
- 링: `3` + 표본 → 예정 = 출발 처음 본 시각 + 중앙값, 앵커 기준 = 출발 시각, 같은 예정이면 링 유지(push·버전 안 흔들림), 예정 경과 → 0.90 hold(ETA null, NORMAL), 유효기간 = 예정 + 120초. 표본 없음 → API 초 안 쓰고 기존 STALE 폴백. `4·5` → 진행 hold(ETA null, 상한 = 지금 값, NORMAL). `99`·`0/1/2` 그대로
- 위치 API 역할 불변(도착 행 없을 때 구간 전환 폴백)
### 함정
- hash 마지막 필드를 HDEL 하면 키가 사라져 TTL -2 — 시험은 도착 전에 TTL 확인
- 기존 시험 "전역 진입·도착에도 진행이 오른다"는 새 규칙(정차 중 hold)과 반대라 교체
- hash TTL 은 키 전체라 다른 열차가 연장 → 도착 놓친 열차의 옛 출발 기록이 남는다 → 15분 넘은 값은 새 출발이 덮고, 900초 초과 소요시간은 버림
### 좌표
- `SubwaySegmentTimePort`·`RedisSubwaySegmentTimeAdapter`·`SubwaySegmentTimeRecorder`(신규), `RealtimeSubwayCollector.collect`, `SubwayRingSignalReader.measured`, `SubwayRingCalculator.fromPreviousStation/fromMeasured/hold`, `SubwayRingObservation.measured`
### 검증
- `SubwayRingCalculatorTest` 37(신규 `MeasuredSegment` 6 + 정차 hold), `SubwayRingSignalReaderTest` 4, `SubwaySegmentTimeRecorderTest` 2, `RedisSubwaySegmentTimeAdapterIT` 4, `SubwayRingIT`(실측 예정 케이스), Subway* 전체 549/549
### 배포
- 마이그레이션 없음. worker 만(수집기·링). 표본은 배포 후 앞 열차가 지나가며 쌓인다(처음 수 분 STALE 폴백 많음). 끄기 = `realtime.subway.segment-time.enabled=false`(기록 중단 → 표본 만료 후 STALE 폴백)
- 전체 `./gradlew test --continue`: 2613 중 실패 12 = 기존 무관(JourneyEvalReadPathIT 2·JourneyEvaluationIT 7·JourneyTransferAssessmentIT 1·RerouteRemainingPathOtpIT 1·간헐 JourneyNotificationSchemaIT 1), 새 실패 0
- 🔴 배포 직후 함정(2026-10-08 00:14): 첫 표본이 전부 15초 ← 재시작 첫 회차에 이미 출발해 있던 열차가 "처음 본 출발"로 잡힘(수집 주기만큼). 매일 05:30 수집 재개 때도 같음 / 가드 = 출발은 직전 회차 코드가 4·5·99 일 때만(전이를 봤을 때만) 기록, 직전 상태는 기록기 메모리(worker 단일). 잘못 쌓인 `subway:seg:*` 는 재배포 직후 삭제
---

## 2026-10-07 regional-ride-gtfs-stop-count — 경기·TAGO 탑승 위치를 앱의 계획 정류장 목록으로 센다 (S, #264 PR-4 후속)

### 확정
- 앱의 남은 정류장 수는 **검색 스냅샷의 GTFS 정류장 목록**(`SubPathResult.stations`, 지오메트리 `snapshotBusStops` 와 같은 원천)으로 센다. 서버도 같은 목록에서 거슬러 올라가 지금 정류장 id 를 찾고, 그 id 를 #267 표에서 찾는다(`JourneyPlannedBusStopReader` → `RegionalRideLocator.walkBackOnPlanned`).
- 계획 목록의 첫·끝이 표의 승차·하차 정류장과 같을 때만 쓴다. 못 읽거나 안 맞으면 예전 규칙(표에서 `waypoint=true` 만 빼고 세기)으로 물러난다 — 회귀 없음.
- 기점(`UpcomingStopWindowResolver.scanRegional`)은 앱 목록에 있는 정류장만 연다(`Position.isAlightable`). 앱이 모르는 정류장은 수락 때 자를 위치가 없다.
- 수락 자르기(`RideTruncationResolver.resolveRegional`)는 목표·현재가 앱 목록에 없으면 `stop-unjoined`.

### 함정
- TAGO 에는 가상점 표시가 없는데 **GTFS 에 없는 실제 정류장**이 있다(대전 1001 둔곡교차로 — 도착 정보가 나오는 진짜 정류장, 2026-10-07 실측). 예전 규칙은 이 칸을 한 정거장으로 세어, 그 칸이 현재~하차 사이에 있으면 위치가 하차 쪽으로 밀렸다. 표본(#264 2026-10-06 댓글) TAGO 패턴 13개 중 7개에 1~3개.
- 이 칸이 승차~현재 사이에 있으면 예전 규칙도 맞는다(거슬러 오르는 구간 밖). 시험 픽스처는 현재~하차 사이에 둬야 결함이 재현된다.

### 좌표
- `application/journey/service/JourneyPlannedBusStopReader.java`(신규), `RegionalRideLocator.java`(`plannedStops`·`walkBackOnPlanned`·`Position.planned/isAlightable/plannedIndexOf`), `UpcomingStopWindowResolver.java:195`, `RideTruncationResolver.java:154`

### 검증
- `RegionalRideLocatorTest` 7/7(신규 4), `RideTruncationResolverTest` 14/14(신규 1), `UpcomingStopWindowResolverTest` 12/12, `JourneyPlannedBusStopReaderTest` 3/3(신규)
- 전체 `./gradlew build`(일회용 postgis·redis): 2,411건 중 실패 10 — 전부 기존 무관 클래스(`JourneyEvalReadPathIT`·`JourneyEvaluationIT`·`JourneyTransferAssessmentIT`, #268 때 main 에서 동일 실패 확인)
## 2026-10-07 경기 GBIS 그래프 운영 적용 (#275 후속)

### 배포
- ✅ compose.yaml 4곳 + compose.otp-national.yaml 1곳 graphVersion 치환(diff 로 OTP 5줄만 바뀐 것 확인) → otp 재생성 12:56:56→12:57:53 healthy(새 graph.obj 마운트 확인, RSS 3.76GiB/4.39GiB) → api·worker 재생성. 백업 `*.bak-20261007-gbis`. 마이그레이션 없음.
- 동시 배포: 12:59 다른 세션이 api·worker 를 `nextroute:20261007-125927-21371b27`(#275 머지 커밋 포함) 로 재배포 + `REGIONAL_BUS_CATALOG_ENABLED`. 그 세션의 compose 백업(12:53:08)이 이미 내 치환을 담고 있어 덮어쓰기 없음 — 최종 compose 에 둘 다 존재.
- `형상 마킹 ACTIVE` 기동 로그 문구는 현재 main 에 없음 → 확인은 컨테이너 env(`ROUTING_OTP_GRAPH_VERSION`)·`/otp/coverage-index.json` graphVersion 일치 + 실검색으로 대체.
- 롤백: 백업 두 파일 복원 → otp(두 파일로) → api·worker 재생성. 이전 그래프 디렉터리 `...-shaped-g035004114e7b/` 보존.

### 검증
- 운영 OTP(터널): 88·G7426·900·M6410 등 경기 버스 leg 16개 legGeometry GBIS 선 30m 내 100%, 최대 1m. otp·api ERROR 0(10분).
- 앱 `/api/route/search`(운정→신논현, 200, 1.15s): G7426·마을 077 subPath polyline GBIS 선 30m 내 100%, 최대 1m.

## 2026-10-09 subway-arrivals-board PR-1 (#317 열차 위치)

플랜 `docs/plan/subway-arrivals-board/PLAN.md` §3. 브랜치 feat/subway-arrivals-train-position (스크래치 clone).

### 확정
- 위치 API `trainSttus=3`(전역출발) = 그 행 `statnId` 역의 **바로 전역**을 떠나 statnId 로 가는 중(사용자 확정). 역 도착 응답은 (prev(X), DEPARTED) 로 그린다. M7 트래커·링은 여전히 이 값을 근거로 안 쓴다(별건)
- 열차번호 정규화 불필요 — 도착 btrainNo ↔ 위치 trainNo 16/16(2026-08-15 항목). 플랜의 "실측 선행" 단계 삭제
- 위치 신선도 = `SubwayPositionProperties.freshnessWindow`(5분) 재사용. 180초는 `receivedAt` 이 "원천 시각이 바뀐 마지막 시각"이라 장시간 정차 열차를 놓친다
- 반대 방향 제거 조건 = 반대 방향 관측이 신선 **그리고** (이 방향 관측 없음 또는 더 오래됨)
- 플랜 편차: `SubwayResolvedPatternCache` 를 따로 빼지 않았다. `SubwayLineOrder` 가 `SubwayStationsAheadService.resolvedPatterns`(같은 패키지)를 직접 써서 캐시를 공유한다 — 추출하면 stations-ahead 테스트 생성자까지 바뀌는데 얻는 게 없다

### 함정
- 급행 건너뜀 판정 첫 구현이 틀렸다: "X 쪽으로 가다 다른 후보를 먼저 만나면 탈락"을 candidate 가 **없는** 패턴(급행)에도 적용 → 완행 직전 역까지 탈락해 늘 모름 ← 가드: candidate 가 그 패턴에 실제로 나올 때만 판정(`SubwayLineOrderTest.expressSkipDoesNotMakeItAmbiguous`)
- 스크래치 clone 에서 `@SpringBootTest` IT 는 DB 가 필요하다 → 새 Redis IT 는 컨텍스트 없이 어댑터만 testcontainers Redis 에 붙였다(`RedisSubwayTrainLogAdapterLastSeenAllIT`)

### 좌표
- 위치 해석 `application/arrival/service/SubwayTrainPositionResolver.java`
- 노선 순서 `application/subway/service/SubwayLineOrder.java` (cachedPatterns 만, 미스 시 단일 스레드 백그라운드 적재 1회)
- 일괄 조회 `SubwayTrainLogPort.lastSeenAll`(기본 구현=한 대씩) / Redis HMGET 오버라이드
- 스냅샷 필드 `SubwayRealtimeTrain.prevStationId`(statnFid)·`currentStationName`(arvlMsg3) — `RealtimeSubwayCollector.toTrain`, `SubwayRealtimeArrivalReader.toTrain`

### 배포
- api + worker(스냅샷 필드는 worker 수집기가 채움). 순서 무관 — 스냅샷 JSON 은 NON_NULL·필드 추가만. 마이그레이션 없음

### 검증
- `./gradlew compileJava` 통과
- 집중: `./gradlew test --tests '*SubwayLineOrderTest' --tests '*SubwayTrainPositionResolverTest' --tests '*SubwayStationArrivalQueryServiceTest' --tests '*SubwayStationsAheadServiceTest' --tests '*SubwayStationControllerTest' --tests '*SubwayRealtimeArrivalReaderTest' --tests '*RedisSubwayTrainLogAdapterLastSeenAllIT'` → 52/0
- 전체 단위: `./gradlew test --tests '*Test'` → **2124/0**. 컨텍스트 IT(`*IT`)는 스크래치 clone 에 DB 가 없어 미실행(기존 무관)
- 실호출은 배포 후(2호선 강남·수원 하행·수인분당 1역) — 미실행

## 2026-10-09 subway-arrivals-board PR-2 (#317 노선도 역 목록 + #318 초 단위)

플랜 `docs/plan/subway-arrivals-board/PLAN.md` §4 (플랜 파일은 PR-1 #322 에 있음). 브랜치 feat/subway-arrivals-line-strip.

### 확정
- **GTFS 계획 시각은 초 단위다**(분 반올림 아님) — 운영 fixture 실측: 9호선 정차 25초, 신논현→사평 115초, 5호선 강동 정차 40초. 플랜 §8-2 "거짓 정밀도" 우려 해소
- 오는 쪽 역(behind) = 같은 방향 패턴에서 승차역 **앞** 머리를 가까운 역부터 뒤집어 남은 역과 같은 규칙(부분수열 → 소수 패턴 → 공통 접두)으로 자른다. 끝 사유 FULL/ORIGIN/BRANCH/UNKNOWN
- 도착 응답 경로는 `cachedPatterns` 만 — 비면 `SubwayLinePatternService.loadLater`(단일 스레드·키당 1회) 후 빈 노선도
- 플랜 편차: `LineStrip` 에 `aheadKeys`·`nextOf` 를 넣지 않았다. PR-3 는 PR-1 의 `SubwayLineOrder.nextOf` 로 충분하다
- 플랜 편차: PR-2 는 PR-1 에 의존하지 않게 만들었다(독립 브랜치, 스택 금지). PR-1 `SubwayLineOrder` 의 자체 지연 적재기와 `loadLater` 가 겹친다 → PR-3 에서 `loadLater` 하나로 합친다

### 좌표
- 계산 `StationsAheadCalculator.behind`/`heads`/`seconds`, `Ahead` 에 초 3개
- 서비스 `SubwayStationsAheadService.strip`(implements `GetSubwayLineStripUseCase`)
- 응답 `SubwayStationArrivalsResponse.Direction` 4필드 + `StripStation`/`BehindEnd`/`LineStrip`, `SubwayStationsAheadResponse.Station` 초 3개

### 검증
- 운영 fixture 프로브(삭제함): 강남 내선 종합운동장·삼성·선릉·역삼(FULL, 450/330/180/60초), 수원 하행 당정·의왕·성균관대·화서(급행 패턴에도 FULL), 가산디지털단지 상행 금천구청·독산(BRANCH), 김포공항 9호선 상행 개화(ORIGIN), 방화 하행 없음(ORIGIN)
- 전체 단위 `./gradlew test --tests '*Test'` → **2120/0**. 컨텍스트 IT 미실행(스크래치 clone DB 없음, 기존 무관)

### 배포
- api 만. 마이그레이션 없음

## 2026-10-09 subway-arrivals-board PR-3 (#317 열차별 구간 앵커 + 지난 열차 제거)

플랜 §5. 브랜치 feat/subway-arrivals-train-segment = PR-1(#322) + PR-2(#323) merge 위 커밋. **draft — #322·#323 머지 후 main 위로 rebase**(스택 금지 규칙상 그 전엔 머지하지 않는다).

### 확정
- 구간은 출발한 열차(status=DEPARTED)에만. to = `SubwayLineOrder.nextOf(from)`
- departedAt = #316 구간 실측의 "전역 출발 첫 관측"(`SubwaySegmentTimePort.departedAt`, 키 = 도착하는 역) → 없으면 **POSITION 원천일 때만** 위치 API 상태가 바뀐 시각. ARRIVAL 확인 시각은 상태가 그대로여도 갱신돼 출발 시각이 아니다 → 구간 없음
- expectedAt = departedAt + 최근 3대 실측 중앙값(MEASURED) → 없으면 + GTFS 계획 초(SCHEDULED, `SubwayLineOrder.runSeconds` = 두 역이 붙은 최다 trip 패턴). 링 원칙(오늘 실측 우선, 정적 값은 표본 없을 때만·출처 표시)과 같다
- 지난 열차 = (위치 API 가 조회역 출발 & 도착 행보다 최신) 또는 (현재 역 ∈ 하류 3역 & ∉ 상류 4역·조회역). 판정 예외는 "안 지났다"
- PR-2 노트의 "loadLater 하나로 합친다" 수행: `SubwayLineOrder` 의 자체 적재 스레드를 지우고 `SubwayLinePatternService.loadLater` 위임

### 좌표
- `application/arrival/service/SubwayTrainSegmentResolver.java`
- `SubwayLineOrder.runSeconds`
- `SubwayStationArrivalsResponse.Train.segment` / `Segment` / `SegmentSource`, `SubwayTrainPositionResolver.Placed.segment`

### 검증
- 집중: SegmentResolver 9, LineOrder 7, 도착 서비스 21(+2: 앵커·위치 출발 제거), 컨트롤러 6(segment JSON), PositionResolver 8
- 전체 단위 `./gradlew test --tests '*Test'` (PR-1+PR-2+PR-3 합본) → **2150/0**. 컨텍스트 IT 미실행(스크래치 clone DB 없음, 기존 무관)

## 2026-10-09 subway-arrivals-board PR-4 (#317 떠난 열차 대체 + 선택 허용)

플랜 `docs/plan/subway-arrivals-board/PLAN.md` §6 (플랜 파일은 PR-1 #322). 브랜치 feat/subway-arrivals-departed-fallback.

### 확정
- **사용자 결정: 화면에 보인 떠난 열차는 원천과 무관하게 전부 고를 수 있어야 한다.** 그래서 출발 조회 창구를 `SubwayDepartureLookup` 하나로 모았다 — 역 도착 응답 `departed()` 와 열차 선택 `departedServiceDate` 가 둘 다 이것만 쓴다. 같은 열차가 두 원천에 있으면 POSITION
- M7 자동 판정(`SubwayBindingMatchService`)은 이 창구를 쓰지 않는다 — 품질이 다른 후보를 자동 판정에 섞지 않는다. 도착 API 기록은 별도 키 `subway:adep:*`
- 도착 API 출발 = 직전 회차 출발 전 → 이번 출발(2) 이면 그 행 recptnDt, 직전 진입·도착(0·1) → 이번에 행이 사라짐이면 (직전 관측 + 이번 수집)/2. 재시작 첫 회차 무기록, 대상 노선 행 수가 직전 절반 미만이면 회차 건너뜀
- 대상 노선 기본 `1063,1067,1075,1081,1093,1094`(위치 출발 사건 없는 노선, #299 실측). 끄기 env `REALTIME_SUBWAY_ARRIVAL_DEPARTURE_ENABLED=false`
- 플랜 편차: `SubwaySegmentTimeRecorder.previousCodes` 와 맵을 공유하지 않고 기록기가 대상 노선 행만 따로 든다(6개 노선이라 작다, 두 기록기의 키·보관 값이 다르다)
- 플랜 편차: PR-1 에 의존하지 않게 만들었다. ARRIVAL 출처 떠난 열차의 `currentStationName` 은 기존 위치 로그 조회(그 노선들엔 위치 API 가 없어 null). PR-1 의 스냅샷 `arvlMsg3` 로 채우는 건 PR-3 이후

### 좌표
- 기록기 `application/subway/service/SubwayArrivalDepartureRecorder.java` ← `RealtimeSubwayCollector`(구간 실측 기록 옆, 같은 try/catch 격리)
- 포트 `SubwayArrivalDepartureLogPort` / Redis `RedisSubwayArrivalDepartureLogAdapter`(ZSet, TTL 30분)
- 창구 `application/subway/service/SubwayDepartureLookup.java`
- 계약 `SubwayStationArrivalsResponse.Departed.source`(POSITION|ARRIVAL)

### 검증
- 집중: 기록기 5, Redis IT 1(컨텍스트 없이 testcontainers), 도착 서비스 +2(대체·원천 우선), 선택 서비스 +1(**ARRIVAL 출처 선택 → bind, 전엔 422**)
- 전체 단위 `./gradlew test --tests '*Test'` → **2114/0**. 컨텍스트 IT 미실행(스크래치 clone DB 없음, 기존 무관)

### 배포
- api + worker(기록기는 worker 수집기). 마이그레이션 없음. worker 배포 전에는 대체 기록이 없어 응답이 지금과 같다

## 2026-10-09 단독 추적 근처 정류장 직선거리화 (S, fix/track-stops-near-bbox)

### 함정
- 근처 정류장이 0개·일부만 옴 ← OTP `stopsByRadius` 의 distance 는 직선이 아니라 **도보 탐색 거리**. 좌표가 보행망에 못 붙으면(사당역 사거리 원점) 300m 안 0개, 100m 옮기면 16개. `stopsByBbox` 는 같은 곳 23개 / 가드: 사각형 조회 + 어댑터 직선거리 필터·정렬·limit
- `stopsByRadius` 결과는 거리순이 아님(강남·서울역·주엽 실측 sorted=False) → `first` 로 자르면 가까운 정류장이 잘릴 수 있었다

### 미결
- 🔴 정류장 고르면 노선 일부만 나옴 ← 같은 실제 정류장이 피드별 stop 으로 쪼개짐(신분당선강남역 SEB 20노선 / GGB 35 / ICB 1). 수도권 SEB·GGB·ICB 는 뒤 숫자 같고 거리 p50 22m·p99 127m(3,983묶음). 지방은 뒤 숫자 겹쳐도 딴 정류장(ADB/TSB 354000001 수십 km) → 숫자만으로 묶으면 안 됨. 별건 M

### 검증
- `./gradlew test --tests '*OtpTransitPatternAdapterTest' --tests '*TrackSelection*'` → 어댑터 9/0, 선택 조회 11/0

## 지하철 링 — 이미 구간을 지난 열차를 하차역 도착으로 마무리 (2026-10-09, S, #311)
### 확정
- #311 원인 = "곧 오는 열차" 일반 문제 아님. journey 166(강남→방배 3정거장)이 고른 8484 는 22:43 강남 출발 → **22:49:09 하차역 방배 진입** → 22:51:21 사당, 선택은 22:51:59. "방금 떠난 열차" 10분 창(#303)으로 선택 통과, 링은 승차역~하차역 체인 안에서만 열차를 찾아 끝까지 못 찾음(STALE·observedAt null). 같은 시각 167·169 는 정상(NORMAL 32/37·15/18). 근거 = 2026-10-07 전 노선 도착 스냅샷 표본 + push outbox
- **사용자 결정: 떠난 열차는 계속 고를 수 있어야 한다 — 선택을 422 로 막지 않는다**(처음 낸 422 `TRAIN_PASSED_ALIGHTING` 안은 ~~취소~~)
- 대신 링: 위치가 체인 밖일 때 위치 로그 역별 도착 이력(최근 30분)에 체인 역 도착이 있고 그 도착이 지금 관측보다 이르면 "구간을 지났다"(`Source.PASSED`) → 하차역 도착(1.00, SUBWAY_ALIGHT, **NORMAL**)으로 마무리. 이력 없으면(아직 승차역 전) 판단 안 함. PASSED 는 신선도 검사 제외(위치 recptnDt 지연 최대 150초 > 창 120초)
- "아직 못 찾음" vs "관측 끊김" 구분은 새 enum 없이 기존 `observedAt == null` — Live Activity props 는 앱 엄격 디코딩이라 enum 추가는 구버전 앱을 깰 수 있다
### 좌표
- `SubwayRingSignalReader.passedChain`, `SubwayRingObservation.passed/Source.PASSED`, `SubwayRingCalculator` switch·`isStale`
### 검증
- `SubwayRingCalculatorTest`(신규: 승차역 시작이어도 PASSED → 하차역 1.00 NORMAL·이후 유지·낡은 관측이어도), `SubwayRingSignalReaderTest`(신규 2: 이력 있음 → PASSED / 이력 없음 → null). 관련 73/73
- 전체 2638 중 실패 12 = 기존 무관 동일 구성, 새 실패 0
### 배포
- 마이그레이션 없음. worker 만(링)
## 2026-10-10 정류장 노선 전체 표기 + 근처 목록 합치기 (M, feat/track-stop-sibling-routes)

### 확정
- 같은 정류장 판정: 수도권 3피드(SEB/GGB/ICB) 의 같은 뒤 숫자 + 대표에서 300m 이내. 지방 피드는 대상 아님(ADB/TSB 354000001 은 수십 km 떨어진 딴 정류장). 실측 수도권 3,983묶음 p50 22m·p99 127m, 1건(104000010) 1,346m → 300m 상한
- 사용자 결정(2026-10-10): 근처 목록도 한 줄로 합친다, 대표 SEB → GGB → ICB(지도 스냅샷=서울 DB·서울 도착 API 와 같은 정류장·이름. 경기엔 옛 이름 있음: 당고개역/불암산역). 지도 전국 교체는 #326
- 계약: `TrackViews.Direction` + `boardStopGtfsId`, `arrivalStopId`(그 노선 기준 도착 조회 id). 경기 노선은 경기 stop id 로 물어야 도착이 나온다
- 하위 호환: alight/shape/start 의 `from` 이 그 노선을 안 지나면 `TrackPatternSource.boardingStop` 이 형제 stop 으로 바꾼다
- 근처 합치기는 근처 결과 안에서만(OTP 추가 호출 없음). 노선 합치기는 `StopsByIds` 1회 + 형제 patternsAtStop(캐시 10분)

### 함정
- OTP `stops(ids:)` 는 없는 id 를 배열 안 null 로 준다 → 파서가 건너뜀
- 정류장이 종점인 패턴은 그 정류장에서 못 탄다 → 신분당선강남역 노선 이름 56 중 `5100예약` 이 빠져 55. 정상
- 같은 노선·방향 중복 제거는 형제 사이에서만. 한 정류장 안의 가지끼리는 지우지 않는다(기존 동작 유지)

### 좌표
- `application/journey/service/TrackStopSiblings.java` 규칙
- `TrackPatternSource.siblings/boardingStop`, `TrackSelectionQueryService.siblingGroups/directions`
- 인계 `docs/plan/track-stop-sibling-routes/HANDOFF.md`

### 미결
- #321(근처 직선거리)과 같은 파일(어댑터·graphql·어댑터 테스트)을 건드린다 → 먼저 머지된 쪽 위로 rebase

### 배포
- api 만 교체. 마이그레이션·플래그 없음

### 검증
- `./gradlew test --tests '*Track*' --tests '*OtpTransitPatternAdapterTest'` (일회용 st-pg:55432·st-redis:56379 + 기본값 없는 env 더미) → Track* 단위 전부 0 실패(선택 조회 19/0, 시작 11/0, 형제 4/0, 컨트롤러 6/0, 어댑터 9/0), TrackJourneyStartIT 1/0

## 2026-10-10 지도 정류장 전국 타일 (#326, L, feat/map-bus-stop-tiles)

### 확정
- 사용자 결정: 로딩 적은 쪽 = 0.1° 타일, 마커 키 gtfsStopId, 인증은 범위 밖(로그인 제거 진행 중). v1 `/snapshot` 유지
- 실측: 버스 stop 210,029 → 합치기 후 205,947. 0.1° 타일 1,158개(gzip p50 1.5kB, 최대 31kB). 전국 한 파일은 gzip 2.8MB
- Opus 리뷰(수정 후 승인) 반영: graphVersion 감지 대신 1분 점검(없으면 / 24시간 지나면 빌드), 게이트 acquire 2초 1회(role `map-build`) + 호출 새 10초 마감, 이상치 가드(<150,000 또는 이전의 80% 미만 → 게시 안 함), manifest no-cache + 타일 `?v=` immutable, 반올림 후 key, arsId 는 `OtpGtfsIds.seoulBusStop` 정방향으로 맵
- 숫자는 `BigDecimal`(정수에서 생성, 뒤 0 제거, 지수 표기 방지)로 직렬화한다. JDK Double.toString 변화로 해시가 통째로 바뀌는 것을 막는다

### 함정
- `OtpConcurrencyGate.tryAcquire` 는 즉시 반환이 아니라 deadline 까지 대기한다(Redis 구현 :73-104) → 재시도 루프를 얹으면 몇 분씩 늘어진다
- graphVersion 은 env 정적값 → 런타임 변경 감지 코드는 영원히 안 돈다
- key 를 `/0.1` 로 계산하면 경계 300여 칸이 틀린다 → 서버·클라 모두 `*10` 후 floor
- 공용 스케줄러 풀은 3 → 수십 초 빌드는 전용 스레드에서

### 좌표
- `application/map/service/BusStopTiles.java`(규칙), `NationalBusStopTileService.java`(빌드·점검), `dto/BusStopTileSnapshot.java`
- `MapBusStopController` v2 두 엔드포인트, 인계 `docs/plan/map-bus-stop-tiles/HANDOFF.md`

### 미결
- 지방 피드끼리 같은 위치 중복(TSB 시외 vs 시내, 15m 안 6,956쌍)은 이번 범위 밖이라 마커가 겹친다. 후속 이슈

### 배포
- api 만 교체한다. 마이그레이션·플래그 없음. 부팅 후 `[MapTiles] 버스정류장 타일 빌드` 로그와 힙을 확인한다

### 검증
- 일회용 st-pg/st-redis + 더미 env 로 `./gradlew test --tests '*TrackJourneyStartIT' --tests '*Track*' --tests '*MapBusStop*' --tests '*BusStopTile*' --tests '*BusStopSnapshot*'` → 17 스위트 전부 0 실패
  - BusStopTilesTest 6/0, NationalBusStopTileServiceTest 7/0, MapBusStopControllerTest 10/0, TrackJourneyStartIT 1/0
- `OtpTransitPatternAdapterTest` 12/0

## 2026-10-10 지도 스냅샷·타일 gzip 미적용 수정 (S, fix/map-snapshot-weak-etag)

### 함정
- `server.compression` 이 켜져 있어도 v1 스냅샷·v2 타일이 gzip 되지 않았다(운영 `Content-Length: 872091`, 서울 타일 57,984). 원인은 Tomcat 이 **강한 ETag** 가 붙은 응답을 압축하지 않기 때문이다
  - #160 HANDOFF 의 "gzip 233kB" 는 운영에서 한 번도 일어나지 않았다
  - nginx 도 `gzip_types` 가 주석이라 json 을 압축하지 않는다
- 가드: `MapBusStopController.quote` 가 약한 ETag `W/"..."` 를 낸다. `matches` 는 W/ 를 벗겨 비교하므로 기존 클라(강한 형식으로 보냄)도 304 를 받는다
- ~~Opus 리뷰 14번 "Tomcat 은 압축 시 강한 ETag 를 W/ 로 바꾼다"~~ 틀림: Tomcat 은 바꾸지 않고 압축을 건너뛴다

### 검증
- `MapBusStopControllerTest` 10/0. 배포 후 운영에서 `Content-Encoding: gzip` 을 확인한다(아래 배포 기록)

## 2026-10-10 배포 기록 — #321·#327·#330·#331 (api)

### 배포
- 00:58 KST `3044abed`(#321·#327) → 01:15 `cb4ba30b`(#330) → 01:20 `7c336de8`(#331). 최종 api 이미지 `nextroute:20261010-012003-7c336de8`, 이전 `20261010-011539-cb4ba30b`(`./deploy.sh rollback-api`)
- jar 백업: `app.jar.bak-20261010-pre-stop-siblings`, `-pre-map-tiles`, `-pre-weak-etag`. worker 는 바꾸지 않음(`927af672`). 마이그레이션 없음(jar 안 61개 동일)

### 검증
- 타일 빌드 로그 `tiles=1158 stops=205947 ms≈9.4s` 2회, 두 번째도 같은 version `899817f2ea7c`(해시 안정). ERROR 0
- 운영 응답(gzip): 스냅샷 v1 872kB → 233kB, manifest 30kB → 12.7kB, 타일 `374_1270` 58kB → 13kB. 304: W/·강한 형식·v1 구 클라 모두 304. 없는 key 404, 형식 오류 400
- `374_1270` 타일에 신분당선강남역은 `1:BS_TAGO_SEB121000009` 한 점(ARS 포함)
- api 힙 최대 282MB / 805MB(빌드 2회 포함 25분)

## 2026-10-10 로그인 제거 → 기기 익명 계정 (L, feat/device-anonymous-auth)
플랜=docs/plan/device-anonymous-auth/PLAN.md (Opus 5.5 리뷰 12건 반영), 모바일 인계=HANDOFF.md

### 확정
- 독립 앱 로그인 제거(사용자 결정 2026-10-09), 앱인토스는 토스 로그인 유지. 신원 입구만 `POST /api/auth/device` 로 추가하고 users·JWT·user_id 테이블·컨트롤러는 그대로.
- V17 에서 `users.device_id` 를 지우고 토스 키로 통일했던 결정의 **반전**. 이번엔 users 칼럼이 아니라 V33 다중 신원 구조에 `auth_identity.provider='DEVICE'` 로 넣는다 — 기존 애플 계정에 기기를 붙일 수 있고, 탈퇴(신원 물리삭제)·세대 번호 무효화가 그대로 먹는다.
- `provider_subject = hex(sha256(비밀값))`. 256비트 난수라 salt 불필요. 원문은 저장·로그·예외 메시지 어디에도 없다(테스트로 고정).
- 무효 Bearer 는 필터가 익명으로 떨어뜨린다 → 새 가입 경로. 의도된 동작(HANDOFF 에 "reissue 일시 실패 시 익명 호출 금지" 명시).
- 연결(Bearer + 처음 보는 해시)은 **APPLE/KAKAO 신원이 있는 계정만, DEVICE 3대까지**. 토스 전용·기기 전용 계정은 403. 이유: 훔친 access(1h)로 공격자 비밀값을 영구히 붙이는 경로라서(로그아웃은 DEVICE 를 안 지운다). 구버전 이행용 한시 통로 — 애플·카카오 로그인 삭제 때 같이 삭제.
- 3대 상한은 count 후 insert 라 동시 연결 두 건이 살짝 넘길 수 있다 — 한시 통로의 남용 상한이라 감수.
- 가입 한도: 새 user 생성 분기만, `X-Real-IP`(nginx 가 `$remote_addr` 로 덮음, 앱 포트 127.0.0.1) → 없으면 getRemoteAddr, **XFF 무시**. IPv6 는 /64. 기본 60/h(이통사 CGNAT). **fail-open**(버스 쿼터는 유료라 fail-closed 지만 여기서 막으면 Redis 장애 동안 신규 설치자 전원이 앱을 못 씀) + ERROR 로그 + `auth.device.signup.limit{result=limited|redis_error}` 카운터.
- `/api/me` 의 `identities` 에서 DEVICE 제외(구버전 앱 엄격 enum 디코딩·기기별 중복 노출 방지). SessionService.me 에서 거른다.
- 응답의 `provider` 는 연결 경로에서도 `DEVICE`.
- 범위 밖: 여정 생성 남용 상한(계정 하나로도 무제한 → 가입 한도로는 못 막음, 별건), 애플·카카오 코드 삭제.

### 함정
- 가입 경합 재조회를 같은 트랜잭션에서 하면 안 된다: 유니크 위반 순간 트랜잭션이 rollback-only 가 되어 재조회에 성공해도 커밋에서 터진다 ← 리포지토리 `saveAndFlush` 가 바깥 트랜잭션에 참여한 채 예외를 던짐 / 가드: `DeviceLoginService` 는 메서드에 `@Transactional` 을 안 걸고 본 작업·재조회를 각각 `TransactionTemplate` 으로 연다.
- 플랜 초안의 V56 은 낡은 작업트리(V55 에서 멈춘 브랜치)를 보고 쓴 번호였다 ← main 은 V61 / 가드: 마이그레이션 번호는 `git ls-tree origin/main src/main/resources/db/migration/` 로 확인.
- `WithdrawalDeletionBoundaryIT` 로그의 `drop table ... cascade` 는 Testcontainers 의 create-drop 이라 로컬 DB 와 무관(놀라지 말 것).

### 좌표
- 서비스 분기: `application/auth/service/DeviceLoginService.java` (enterAnonymously / enterWithAccount / requireLinkable / resolveRace)
- 한도: `infrastructure/adapter/out/cache/auth/RedisDeviceSignupLimitAdapter.java` (`ipKey` 는 콜론 있는 값만 InetAddress 파싱 — DNS 조회 회피)
- 입구: `infrastructure/adapter/in/api/auth/DeviceAuthController.java`, 설정 `application.yaml` `auth.device.signup-limit-per-hour`
- 스키마: `db/migration/V62__add_device_auth_identity.sql`

### 미결
- (추정·미검증) 기존 `OAuthLoginService.login` 은 `@Transactional` 안에서 `signUp` 의 유니크 위반을 잡고 `resolveRace`(REQUIRES_NEW)로 재조회한다. 바깥 트랜잭션은 이미 rollback-only 라 커밋 시 `UnexpectedRollbackException` 이 날 가능성 — 애플 로그인 더블탭 경합에서만 드러남. 이 PR 범위 밖, 애플·카카오 코드 삭제 예정이라 우선순위 낮음.
- 이슈 #320(track 익명 401): 앱 사용자 전원이 토큰을 갖게 되므로 "로그인 필요 유지"로 종결 제안.

### 배포
- **V62 VPS 수동배치 필요**(V18+ convention). 빠뜨리면 기기 가입이 CHECK 제약에 막혀 500.
- env `AUTH_DEVICE_SIGNUP_LIMIT_PER_HOUR` 선택(기본 60). 다른 env 변경 없음.
- api 만 교체로 충분(worker 무관 경로). 배포 후 VPS 에서 curl `/api/auth/device` 발급 → `/api/me` 200 확인.

### 검증
- 환경: 스크래치 clone + 일회용 postgis(`da-pg`, 로컬 개발 DB schema-only + flyway 이력 V58 복원) + redis(`da-redis`). V59~V62 가 Flyway 로 실제 적용됨(`flyway_schema_history` 62 success=t).
- `./gradlew compileJava` OK.
- `./gradlew test --tests '*DeviceLoginServiceTest' --tests '*RedisDeviceSignupLimitAdapterTest' --tests '*DeviceAuthControllerTest' --tests '*SessionServiceTest' --tests '*OAuthLoginServiceTest'` → 56/56 통과(Device 서비스 15, 한도 7, 컨트롤러 6, Session 13, OAuth 15).
- `./gradlew test --tests '*AuthIdentityConstraintIT' --tests '*DeviceLoginIT' --tests '*TokenGenerationIT' --tests '*WithdrawalDeletionBoundaryIT'` → 35/35 통과(제약 13, DeviceLoginIT 3, 세대 12, 탈퇴 경계 7).
- 전체 `./gradlew test --continue` → 2729 중 13 실패, 전부 이번 변경과 무관:
  - 기존 무관(메모리 기록과 동일): JourneyEvalReadPathIT · JourneyEvaluationIT · JourneyTransferAssessmentIT · BusAlertWakeupBurstIT
  - 기존 무관(main `origin/main` 1cfac30a 에서 단독 재실행해도 동일 실패): RerouteRemainingPathOtpIT 1건(OTP 요청 2회 vs 기대 1회)
  - 전체 실행에서만 나는 간헐: JourneyNotificationSchemaIT(`Unable to rollback against JDBC Connection`) — 이 브랜치에서 단독 실행 20/20 통과
## 2026-10-10 단독 추적 하차 후보에서 하차 금지 정류장 제외 (#265 잔여, fix/track-alight-skip-no-dropoff)

### 확정
- 고칠 대상은 "통과역"(승하차 둘 다 NONE)이 아니라 **하차 금지(drop_off NONE)** 다. 전국 GTFS(national-nojeju-202503) 실측: NONE/NONE 53행(44 trip, 시외 ACC3 뿐), 첫 정차가 아닌 하차 금지 33,485행·11,021 trip(TAGO 22,746·시외 ACC3 10,339·ACC5 230·PORT 144)
- 운영 OTP 실측(2026-10-10): 부산 공항리무진 `1:BR_TAGO_BSB5202029000_D::01` 앞 17곳 SCHEDULED/NONE(하차 금지), 국제선·국내선 NONE/SCHEDULED(승차 금지) → 픽스처 `otp/track/trip-stoptimes-bus-airport.json`
- OTP 패턴(StopPattern)은 정류장별 pickup/dropoff 까지 같은 trip 만 묶는다 → trip 하나의 정차 시각이 패턴 전체 답. `TrackPatternSource.stopAccess` 가 패턴 단위 캐시(그래프 버전 키, 10분). 첫 조회만 OTP +2회(TripsForDate·TripStopTimes)
- 계약: 하차 후보 `index` 는 그대로(빈 번호 생김 = "몇 정거장 뒤" 의미 유지). 승차 금지 정류장에서 하차 후보 조회·시작 = 422 `TRACK_STOPS_INVALID`. 승하차 가능 여부를 모르면(오늘·어제 운행 없음, 개수 불일치) 거르지 않는다 — 시작이 시간표 없음으로 막음

### 미결
- 방향(노선) 목록에는 승차 금지 정류장 노선이 아직 뜬다(정류장 패턴마다 trip 조회가 필요해 비용 큼). 누르면 하차 후보가 422. 필요하면 별건
- 노선 선(shape) `to` 없을 때 끝 = 한 바퀴 끝(하차 가능한 마지막이 아님). 영향 작아 그대로

### 배포
- api 만. 마이그레이션·플래그 없음

### 검증
- `./gradlew test --tests '*OtpTransitPatternAdapterTest' --tests '*TrackSelectionQueryServiceTest' --tests '*TrackJourneyStartServiceTest' --tests '*TrackLegFactoryTest' --tests '*TrackLegChainTest' --tests '*TrackPatternSelectorTest'` → 12/22/12/7/4/11, 실패 0
- 서비스 수정만 되돌리고 같은 시험 → 34 중 4 실패(새 시험 4건이 버그를 잡음 확인)
## 2026-10-10 경기·지방 단독 추적 끝까지 지원 (#333, fix/track-regional-support, PR 하나 — 사용자 결정)

### 확정
- 선택·시작은 이미 전국 GTFS. 깨지던 곳은 시작 뒤 서울 전용 부품이었다(조사 에이전트 표 → 핵심 2건 코드로 직접 확인)
- 지하철 노선 id: `SubwayLineIdMapper.resolveOdsay` 가 이름 속 숫자만 뽑아 OTP `부산1호선`·`대구3호선`·`광주1호선`·`대전1호선`·`인천1/2호선`, 철도 `[일반]1경부서울부산`·`[고속]1호남…` 을 서울 1001~1003 으로 바꿨다(운영 OTP 노선명 99개 실측). 규칙 = NAMED_LINES → `SubwayRoutePatternAssembler.LINE_IDS` → `(서울|수도권)?N호선` 만 숫자. 이름이 있는데 모르면 empty(원천 id 숫자 폴백도 안 함). 신림선 1094·우이신설경전철 1092 복구. **일반 길찾기 여정에도 적용**
- 지하철 탑승 확인 게이트 = 서울 노선 id 유무만(`JourneyLeg.hasSubwayRealtimeLine`). 승차역 id 는 보지 않는다 — 서울 노선이어도 역 이름 매칭이 애매하면 비고, 그때도 사용자 열차 선택으로 링이 돈다(처음엔 역 id 까지 요구했다가 `JourneySubwayGuidanceIT` 5·`SubwayBindingConcurrencyIT` 2 실패로 발견)
- 계약: `SubwayBindingView.realtimeSupported`(신규, 기본 true). false 면 판정 행을 만들지 않고 status 는 의미 없는 PENDING. 지하철 안내(subwayGuidance)는 null
- 버스 번호 폴백: 승차 정류장이 서울 밖 GTFS(`\d+:BS_` 이면서 `TAGO_SEB` 아님)면 번호로 서울 노선을 찾지 않는다(enricher 에서 routeName=null 로 넘김). ODsay 숫자 id·모르는 모양은 기존대로
- 대기 ETA: 서울 routeId 가 없고 원천 노선 id 가 GBIS(`^2\d{8}$`)·TAGO(`^[A-Z]{3}…`) 모양일 때만 `RegionalArrivalLookup.nextArrival`(가장 이른 도착). 경기=APPROXIMATE(분 단위), TAGO=GOOD. 할당량·캐시는 재탐색과 공유(`reroute-gg-arrival`/`reroute-tago-arrival`)
- 선택 화면: TAGO 노선 도착 id 는 도시코드를 알 때만. 근처 묶음 대표(서울)가 서울 DB 에 없으면 형제의 도착 id

### 함정
- 대기 ETA 지역 판정을 "routeId 없음 + sourceRouteId 있음"으로 넓게 잡으면 서울 DB 에 없는 서울 노선·ODsay 내부 id 까지 GBIS 로 묻는다 / 가드: id 모양으로만 판정
- `RerouteRemainingPathOtpIT.otpGraphQlErrorIsEmptyWithoutOdsayFallback` 은 **main 에서도 클래스 실행 시 실패**(2026-10-10 2회 연속, 단독 실행은 통과). 처음 한 번 main 에서 통과해 이 PR 회귀로 오인 → 커밋별 bisect 로 기존 무관 확인

### 미결
- `TransferArrivalEnricher`·`RouteSearchTravelTimeEstimator` 의 번호 폴백(검색 경로)은 그대로. 지방 정류장은 서울 stopId 가 비어 순번 조회까지 안 가 영향 없음
- 방향 목록의 TAGO 노선·정류장 접두사 불일치(정류장 BSB·노선 다른 접두사)면 도착 API 가 빈 결과 — id 는 그대로 나간다
- 지방 지하철 실시간 원천 없음(#245)

### 배포
- api·worker 둘 다(대기 ETA=평가기 worker, 탑승 확인·선택=api). 마이그레이션·플래그 없음

### 검증
- 일회용 st-pg(:55432, V61→V62 flyway 적용)·st-redis(:56379) + api/auth/oauth/toss env(fcm 제외) → `./gradlew test --continue` 전체 2780 중 실패 13 = 기존 무관 11(JourneyEvaluationIT 7·JourneyEvalReadPathIT 2·JourneyTransferAssessmentIT 1·RerouteRemainingPathOtpIT 1 — main 에서 같은 실패 확인) + JourneyNotificationSchemaIT 1(간헐, 단독 20/20) + RerouteRemainingPathOtpLiveIT 1(main 에서도 실패, 실 그래프 필요)
- 변경 관련: SubwayLineIdMapperTest 8/0, SubwayBoardingEvidenceServiceTest 21/0, JourneySubwayGuidanceComposerTest 3/0, JourneyTransitIdEnricherTest 6/0, WaitLegArrivalPredictorTest 16/0, RegionalArrivalLookupTest 6/0, OtpSeoulIdentityResolverTest 20/0, TrackRealtimeIdResolverTest 6/0, TrackSelectionQueryServiceTest 20/0, SubwayBinding*IT 37/0, JourneySubwayGuidanceIT 9/0, SubwayRideScenarioIT 9/0, SubwayTrainSelectionIT 6/0, SubwayRingIT 16/0



## 2026-10-10 guidance-stop-info PR-1 (#266 정류장 번호·방면 + 컬럼)

플랜 `docs/plan/guidance-stop-info/PLAN.md` §3 + §9(Opus 리뷰 반영). 브랜치 feat/guidance-stop-info.

### 확정
- OTP 전환 뒤 `SubPathResult.startArsID/startExitNo` 는 늘 null(OtpPlanConnectionMapper 미채움, LegacyRouteResponseMapper:165 "가짜값 금지"). OTP GTFS stop `code` 도 서울·경기·부산 전부 null(운영 OTP 실측) → 번호·출구는 OTP 밖 원천
- 저장 시점 = 여정 생성 1회. 방면은 Compiler `emitTransit` 이 draft 에 직접(호출 3곳 start·revision·track 무수정), 서울 정류장 번호는 `JourneyGuidanceInfoEnricher`(TransitIdEnricher 다음, 3곳에서 호출)
- 재탐색 수락 경로: 새 단계 = `RerouteAcceptTxService` builder, 앞 구간 = `JourneyLeg.copyToRevision`(안내 값 복사), 자르기 = `copyToRevisionTruncated` 가 `Truncation.guidance`(새 draft) 의 **하차 쪽만** 덮음. 옛 8인자 Truncation 은 하차 쪽을 비운다(옛 하차점 값 금지)
- 서울 번호는 TransitIdEnricher 가 bus_stop 존재를 확인한 내부 ID(`startStopId/endStopId`)로 `findByStopIdIn` — 접두사 규칙 재구현 안 함. 운영 bus_stop 12,986 중 ars 12,851(99%)
- 방면 = 정차 목록 두 번째 역 `displayName`. 목록이 2점(OTP stopCalls 폴백·1정거장)이면 null
- `fast_alight` 는 코드베이스 패턴대로 `String` + `jsonb` + `@ColumnTransformer(write="?::jsonb")`. 응답 해석 실패는 null(여정 조회 보호)
- 지오메트리 `StopGeometry.stopNo`: 서울 노선표 경로(`findStopsByRouteId` projection 에 `bs.arsId`)만. 검색 결과 경로는 id 혼재라 null

### 함정
- 위치 인자 record 확장 → 옛 arity 보조 생성자(`JourneyLegDraft`·`JourneyLegView`·`StopGeometry`)로 호출부 파급 차단. `RouteStopProjection` 에 메서드 추가 → 테스트 익명 구현 4곳에 `getArsId()` 추가

### 검증
- 신규: JourneyGuidanceInfoEnricherTest 4, JourneyLegGuidanceCopyTest 3(복제·자르기·옛 Truncation), JourneyCompilerTest DirectionName 3, JourneyQueryServiceFastAlightTest 2, JourneyControllerTest·GeometryControllerTest JSON 이름·null
- 전체 단위 `./gradlew test --tests '*Test'` → **2240/0**. 컨텍스트 IT 미실행(스크래치 clone DB 없음, 기존 무관). `RerouteAcceptTxService` 는 단위 테스트 파일이 없어 builder 한 줄은 IT(`RerouteAcceptIT`) 몫 — 미실행

### 배포
- **V63 VPS 수동배치 필요**(`V63__journey_leg_guidance_info.sql`, journey_leg 컬럼 6개 추가, 전부 nullable). api·worker. 진행 중 여정은 null, 새 여정부터

## 2026-10-10 guidance-stop-info PR-2 (#266 경기·지방 정류장 번호)

플랜 `docs/plan/guidance-stop-info/PLAN.md` §4 + §9. 브랜치 feat/guidance-regional-stop-no (PR-1 #337 머지 후 fresh main).

### 확정
- 원천 실측(2026-10-10): GBIS `getBusRouteStationListv2` `mobileNo` 145 중 134 채움(앞 공백 있음), TAGO `getRouteAcctoThrghSttnList` `nodeno` 25개 도시 표본 중 22 채움(충남 34310·34340, 영동 37350 없음)
- 저장 = `regional_bus_route_stop.stop_no`(V64). 파서는 `text()`(trim, 빈 값 null) — TAGO `nodeno` 는 JSON 숫자라 `asText` 로 문자열화
- 조회 = 출발 원천 ID → 정류장 표 ID 정규화(`JourneyGuidanceInfoEnricher.regionalStopId`): `…BS_TAGO_` 접두 제거 → `SEB`=서울이라 null, `GGB`=떼고 GBIS stationId, 나머지=TAGO nodeid 그대로. 서울(bus_stop) 값이 있으면 그게 우선
- 같은 정류장이 노선마다 여러 행 — 번호가 엇갈리면 null(지어내지 않음)
- 적재: worker 부팅 시 "표는 있는데 stop_no 가 하나도 없으면" 1회 재적재(`needsStopNumbers`). V64 배포 직후 자동으로 돈다(`collector.regional-bus-catalog.load-on-empty` 가 켜져 있을 때)

### 미결
- TAGO 군 단위(`TSB…`) 7자리 `nodeno`(예 `3370262`)가 표지판 번호인지 미확인 — 지금은 그대로 싣는다
- TAGO 번호가 원래 앞자리 0 을 가진 도시면 JSON 숫자에서 사라졌을 수 있다(원천이 숫자로 줘서 복원 불가)

### 검증
- 신규: JourneyGuidanceInfoEnricherTest +2(경기 두 ID 모양·TAGO, 엇갈림 null·서울 원천 제외), RegionalBusRouteSourceAdapterTest +1(mobileNo trim·nodeno 숫자·없음), JdbcRegionalBusRouteStopStoreIT +1(저장·조회·엇갈림·hasStopNumbers, testcontainers postgres 로 V56+V64 실행)
- 전체 단위 + 저장소 IT: **2270/0**

### 배포
- **V64 VPS 수동배치 필요**(`V64__regional_bus_route_stop_stop_no.sql`). worker 재시작 시 정류장 표 1회 재적재(약 2만 노선, 키 한도 주의 — #281 실측 적재 ~1시간) → 그 뒤 api
## 2026-10-10 9호선·공항철도 역 도착 열차 0대 (S, fix/subway-arrivals-reversed-lines)

### 함정
- 증상: `/api/subway/stations/{id}/arrivals` 9호선 0/38역, 공항철도 6/14역에서 trains 가 비어 옴(수집 스냅샷엔 9호선 142대 정상). ← 위치 API `updnLine` 의 0/1 이 두 노선만 도착 API 와 반대다. 같은 열차가 도착 `하행 → 개화`, 위치 hash `상행|9111` 로 남는다 → `SubwayTrainPositionResolver.place` 가 "반대 방향으로 더 최근에 본 열차"(reversed)로 보고 전부 뺀다
- 실측(운영 스냅샷 vs `subway:pos:<date>:<line>` hash, 열차번호 대조): 1009 반대 26/26, 1065 반대 24/24, 나머지 15개 노선은 일치(2호선 반대 2건은 회차 중)
- 가드: `SubwayDirectionNormalizer.INVERTED_UPDN_LINES = {1009, 1065}` 로 뒤집는다. 호출처는 위치 어댑터 한 곳이다
- 배포 직후에는 그날 hash 에 옛 표기 필드가 남는다. 신선도 창(5분)이 지나면 무시되므로 따로 지우지 않는다

### 미결
- 신분당(1077) 12대가 위치 hash 에 양방향으로 다 있다("both"). 도착 커버는 16/16 이라 영향은 없지만 원인은 미조사
- 2호선 성수지선 4역, 1092 신설동·북한산우이는 별건

### 검증
- `./gradlew test --tests '*Subway*'` (st-pg/st-redis + 더미 env) → 568/0. `SubwayDirectionNormalizerTest.invertedLines` 추가


## 2026-10-10 guidance-stop-info PR-3 (#266 지하철 출구)

플랜 §5 + §9. 브랜치 feat/guidance-subway-exit (PR-2 #338 머지 후 main).

### 확정
- 원천 = OSM `railway=subway_entrance` + `ref`. 로컬 `south-korea-260929-nojeju.osm.pbf` 에서 `tools/subway-entrances/build_entrances.sh` 로 `src/main/resources/subway/entrances.csv` 생성(ref 있는 4,462개, 이름표 4,054). 역 마스터 696 중 692역이 350m 안 출구 보유
- OTP 그래프 출입구 기능 미사용 — 운영 포크는 `step.feature{Entrance}` 지원하나 실측 도보 단계에 안 나옴(정류장이 최근접 도로로 붙어 출입구 노드 미경유 추정)
- 선택 = 역 반경 350m 안 출입구 중 목표(하차: 다음 걷기 끝 / 승차: 앞 걷기 시작)에 최근접. 이름표에 역 이름(displayName) 든 출구가 있으면 그것만(큰 환승역 타 역 출구 혼입 방지)
- 단계 종류 규칙: 하차 출구는 다음이 걷기이고 그 다음이 WAIT_SUBWAY 가 아닐 때만, 승차 출구는 앞이 걷기이고 그 앞이 RIDE_SUBWAY 가 아닐 때만. 같은 정류장 환승(걷기 없음)·끝이 역이면 null
- 플랜 편차: 목표점을 "걷기 polyline 100~150m 지점"(리뷰 🟡7b)으로 하지 않고 걷기 끝/시작 좌표 — draft 에 도보 polyline 이 없다. 오차 가능성은 HANDOFF 에 적음
- 출입구 목록은 첫 사용 시 1회 적재(메모리 4천여 행), 적재 실패 시 출구 없이 진행

### 검증
- 신규: SubwayEntranceIndexTest 4(최근접·이름표 우선·반경·동봉 CSV 강남 10번), JourneyGuidanceInfoEnricherTest +2(승·하차 출구, 환승·끝 역 null)
- 전체 단위 `./gradlew test --tests '*Test'` → **2269/0**

### 배포
- 리소스만(api·worker). 마이그레이션 없음. OSM 출처 표기 앱 요청(HANDOFF)

## 2026-10-10 guidance-stop-info PR-4 (#266 빠른 하차)

플랜 §6 + §9. 브랜치 feat/guidance-fast-alight (PR-3 #340 머지 후 main).

### 확정
- 원천 = 서울교통공사_빠른하차정보 `B553766/inout/getFstExit`(data.go.kr 15143840, 사용자 활용신청 완료, 기존 data.go.kr 키). 전체 2,358행·crtrYmd 20241231·1~8호선 276역. `stinNm` 필터 무시됨 → 통째 받아 `tools/subway-fast-alight/fetch_fast_alight.py` 로 `src/main/resources/subway/fast-alight.csv`(중복 제거 2,111행, `NA-NA` 제거, `"3-2,3-3 사이"` → `3-2~3-3`)
- 방향 = **하차 직전 역이 아닌 `drtnInfo`**(리뷰 🟡5, `nextOf` 의존 제거). 한 방향뿐(종착)은 그것, 직전 역을 모르거나 남은 방향이 둘(갈래역)이면 없음. 2호선도 원천은 상행/하행이라 이 규칙이 필수
- 하차 직전 역 이름은 Compiler 가 draft(`alightPrevName`, 저장 안 함)에 담는다 — 정차 목록 [n-2]. 호선 = 짝 대기 단계 후보의 내부 line_id(1001~1008)
- 이름 정규화 = `SubwayPatternStationMatcher.displayName`(괄호·끝 "역" 제거: `서울역`→`서울`)
- 성수지선(용답 방향)은 원천에 없음 → null(실측 성수 행의 방향 = 뚝섬·건대입구)

### 검증
- 신규: SubwayFastAlightIndexTest 5(방향 선택·종착·모름/갈래/호선 밖·동봉 CSV·line_id), JourneyGuidanceInfoEnricherTest +1(대기 후보 line_id·방향·JSON 왕복), JourneyCompilerTest +1(alightPrevName)
- 전체 단위 → **2277/0**

### 배포
- 리소스만(api·worker). 마이그레이션 없음

## 2026-10-10 배포 기록 — #339 9호선·공항철도 방향 반전 (worker 핫픽스)

### 배포
- 16:13 KST worker 만 교체: `nextroute:20261010-161341-927af672-hotfix-subwaydir` = 운영 worker 리비전 `927af672` + #339 수정 2파일(cherry-pick, main 아님). 이전 `20261010-005035-927af672`(`./deploy.sh rollback-worker`)
- api 는 그대로 `26fc4554`(다른 세션 배포). `app.jar` 는 worker 빌드에 잠깐 쓰고 api jar 로 되돌렸다(백업 `app.jar.bak-20261010-api-26fc4554`)
- main 의 #329·#336·#337·#338(V63·V64)은 이 배포에 포함하지 않았다(사용자 결정). 다음 main 배포에는 #339 가 자연히 들어간다
- DB 에 V62 가 이미 있지만 worker jar 는 V61 까지다 → Flyway 11 기본 `ignoreMigrationPatterns=*:future` 로 부팅 정상(ERROR 0)

### 검증
- 배포 5.5분 뒤 역 도착: 9호선 0/38 → 38/38, 공항철도 6/14 → 14/14, 대조 5호선 56/56
- 마곡나루 상행 = '중앙보훈병원 방면', 하행 = '김포공항 방면'(전에는 상행 '개화 방면'으로 뒤집혀 있었다)
## 2026-10-10 지하철 링 Live Activity push 에 stale-date (#309, feat/ring-push-stale-date, S)

### 확정
- `aps.stale-date`(epoch 초) = 봉투 `props.progressValidUntil`(밀리초) ÷ 1000 내림. `update` 에만, 값이 없으면 키를 뺀다
- 대기열 행(`subway_ring_push_outbox.payload`)이 이미 화면 전체를 들고 있어 DB 칸·포트 시그니처를 늘리지 않았다. 꺼내는 규칙은 봉투 모양을 소유한 `SubwayRingPushPayloadFactory.staleDateSeconds`(정적), 어댑터가 부른다
- 종료 프레임은 SQL 이 만들지만 `end` 라 해당 없음

### 배포
- worker 만(발송=worker relay). 마이그레이션·플래그 없음

### 검증
- `FcmLiveActivityPushAdapterTest` 14/0(신규 3: update 초 단위·값 없으면 키 없음·end 없음), `SubwayRingPushPayloadFactoryTest` 5/0(신규 1: 봉투 왕복)
## 2026-10-10 지하철 링 하차역 도착 예정 estimatedAlightingAt (#310, feat/ring-estimated-alighting, M)

### 확정
- 사용자 결정(2026-10-10): 남은 구간은 **시간표만**. `estimatedAlightingAt` = 다음 역 도착 예정 + GTFS 계획 초(다음 역 도착 → 하차역 도착, 사이 정차 포함). 지연은 다음 역 예정에만 반영된다. 다음 역 = 하차역이면 그 예정 그대로. 다음 역 예정이 없거나(멈춘 링) 계획을 모르면 null
- 계획 초 = `SubwayLineOrder.arrivalGapSeconds`(#317 과 같은 OTP 패턴 캐시, OTP 를 부르지 않음 — 캐시 없으면 뒤에서 적재하고 이번엔 null). 두 역을 이 순서로 지나는 패턴 중 trip 최다(완행). 순환 패턴은 출발역을 다시 만나면 끊는다
- 계산 자리 = `SubwayRingUpdateService`(계산기는 순수 함수라 노선·방향을 모름). 방향·노선은 고정 열차(`TrainIdentity`)의 것
- 사용자 결정: push `stale-date` = min(progressValidUntil, 하차 예정 − 60초)(앱 규칙과 같음)
- 계약: push props `estimatedAlightingAt`(epoch 밀리초, 없으면 null), 스냅샷 `subwayGuidance.estimatedAlightingAt`(Instant). push 지문에 하차 예정 30초 눈금 추가 → 바뀌면 ANCHOR push·링 버전 상승
- `SubwayRingState`·`SubwayGuidance` 레코드 끝에 칸 추가 + 옛 모양 생성자 유지. Redis 옛 값은 null 로 읽힌다(`RedisSubwayRingAdapterIT` 7/0)

### 함정
- `RerouteProposalStoreIT` 23·`JourneySchemaIT` 1·`RerouteProposalExpiryIT` 1 실패는 **main 에서도 난다** — #337 의 V63(`journey_leg.direction_name` 등)이 `@Sql` 로 표를 만드는 IT 스크립트에 반영 안 됨. 이 PR 무관(기존 무관), 별건으로 고칠 것

### 배포
- worker(링 갱신·push) + api(스냅샷 subwayGuidance). 마이그레이션·플래그 없음

### 검증
- 단위: SubwayLineOrderTest 9/0(신규 2), SubwayRingPushDeciderTest 13/0(신규 1), SubwayRingPushPayloadFactoryTest 6/0(신규 1), FcmLiveActivityPushAdapterTest 14/0, SubwayRingCalculatorTest 38/0, JourneyControllerTest 36/0
- 일회용 st-pg/st-redis 전체: 2824 중 실패 38 = 기존 무관 13(이전 기록과 동일 구성) + V63 IT 스크립트 25(main 에서 같은 실패 확인). 링 IT: SubwayRingIT 16/0, SubwayRingPushIT 25/0, RedisSubwayRingAdapterIT 7/0, SubwayRideScenarioIT 9/0, JourneySubwayGuidanceIT 9/0
## 2026-10-10 링 push stale-date 를 앱 규칙에 맞춤 (#309·#310 후속, fix/ring-stale-date-match-app, S)

### 확정
- ~~#310 의 stale-date = min(progressValidUntil, 하차 − 60초)~~ 취소: 앱(`nextroute-mobile` `JourneyGuidanceLiveActivity.swift` redrawAt)과 달랐다. 새 규칙 = 후보 {다음 역 도착 예정, progressValidUntil, 하차 − 60초} 중 **보내는 시각 이후**인 것의 최솟값, 링 `quality=STALE` 이면 없음
- 사용자 영향(어긋났을 때): ① 다음 역 예정이 빠지면 늦는 열차에서 링이 100%("도착")까지 찼다가 다음 push 에 되돌아감 ② 지난 시각을 주면 하차 직전 push 가 받자마자 낡은 화면 ③ STALE 은 영향 작음(통일)
- "보내는 시각" = 어댑터 `System.currentTimeMillis()`(발송 시점). `message(..., nowMillis)` 오버로드로 시험 고정
- 앱은 이미 props `estimatedAlightingAt`(optional)·자체 staleDate 를 구현해 둠 → 앱 변경 없음. 스냅샷 `subwayGuidance.estimatedAlightingAt` 은 앱 모델에 칸이 없어 아직 안 읽음(선택)

### 검증
- SubwayRingPushPayloadFactoryTest 10/0(StaleDate 5: 다음 역 먼저·지난 후보 건너뜀·하차 1분 전·지난 하차 1분 전·STALE), FcmLiveActivityPushAdapterTest 14/0, SubwayRingPushIT 25/0, SubwayRingIT 16/0, SubwayRideScenarioIT 9/0 (일회용 st-pg/st-redis)
## 2026-10-10 @Sql IT 스크립트에 V63 추가 (fix/it-sql-v63-columns, S)

### 함정
- 마이그레이션을 나열해 표를 만드는 `@Sql` IT 14곳(V35~V59 목록)에 #337 의 V63(`journey_leg` 안내 칸 6개)이 빠져 엔티티 INSERT/SELECT 가 `column "direction_name" does not exist` 로 죽었다(RerouteProposalStoreIT 23·JourneySchemaIT 1·RerouteProposalExpiryIT 1) / 가드: 엔티티에 칸을 더하는 마이그레이션은 `grep -rl "V35__" src/test/java` 목록에도 한 줄 추가. V60·V61·V62 는 그 IT 들이 쓰는 엔티티 칸이 아니라 넣지 않았다

### 검증
- 일회용 st-pg/st-redis 전체 2833 중 실패 13 = 기존 무관 그대로(JourneyEvaluationIT 7·JourneyEvalReadPathIT 2·JourneyTransferAssessmentIT 1·RerouteRemainingPathOtpIT 1·RerouteRemainingPathOtpLiveIT 1·JourneyNotificationSchemaIT 간헐 1). V63 관련 25건 → 0

## 2026-10-10 코레일 구간 인접 판정 — 노선 순서(역 ID) 폴백 (fix/subway-adjacency-korail-sections, S)

### 확정
- `SubwayStationAdjacency` 이름 표(subway_segment)는 **서울교통공사 구간 276역뿐**. 1호선 경부·경인·경원, 3호선 일산선, 4호선 과천·안산선은 늘 "인접 아님" → 위치 출발 사건 0. 10-10 Redis `subway:dep:*` 실측: 1001=124~133(서울역~청량리)만, 1003=319~352, 1004=409~434. 수원 `departed[]` 항상 빔, 같은 원인으로 M7 판정·떠난 열차 선택도 그 구간 불가
- 폴백: 이름 표에 없으면 `SubwayLineOrder.nextOf/prevOf`(GTFS 패턴, 역 ID, 그 열차 방향)로 바로 앞뒤인지 본다. 답이 하나일 때만 답하므로 분기역(구로·병점)은 여전히 false — 순간이동을 출발로 읽지 않는 원칙 유지
- 코레일 구간 위치 원천은 출발(2) 상태를 거의 안 준다(10-10 실측 korail ARRIVED 448 / ENTERING 11 / DEPARTED 0) → 출발 사건은 movedStation(역 바뀜) 경로로만 생긴다
- worker 에서 패턴 캐시가 비면 첫 호출은 false + loadLater(OTP 1회 적재). 이후부터 판정

### 좌표
- SubwayStationAdjacency.isAdjacentInOrder / SubwayTrainTransitionTracker.isSameTrainTrack·movedStation

### 함정
- 스크래치 clone 컨텍스트 IT: env 더미는 api.env 키만. oauth.env 더미 → `oauth.kakao.app-id` Long 바인딩 실패, toss.env 더미 → mTLS keystore 파일 없음으로 부팅 사망. DB/Redis 는 로컬 st-pg(55432)·st-redis(56379)

### 검증
- 단위: `--tests '*SubwayStationAdjacencyTest' --tests '*SubwayTrainTransitionTrackerTest'` → 24/0
- 컨텍스트 IT(st-pg/st-redis): `SubwayRideScenarioIT`·`NextrouteApplicationTests`·`SubwayRingIT` → 26/0
## 2026-10-10 #317 이 역 전에 종착하는 열차 제외 (fix/subway-arrivals-terminates-before, S)

### 확정
- 수원 `병점행 · 진위` 의 정체 = 반대 방향이 아니라 **같은 방향인데 조회역 상류(병점)에서 종착**. 도착 API 가 수원 목록에 싣는다(10-10 17시 7회 연속 실측). PR-1 의 방향 필터로는 못 잡는다
- 판정: 조회역에서 상류로 걸으며 **열차 현재 역을 만나기 전에** 종착역 이름이 나오면 제외. 위치가 경계인 이유 = 순환선(2호선 강남 내선 성수행은 성수를 지나 한 바퀴 오는 열차 — 위치까지만 걸으면 성수를 안 만남)
- 위치·종착 이름 모름, 이름 불일치, 판정 예외 → 남긴다(잘못 빼는 쪽이 더 나쁨). 상류 최대 20역, 갈래 합류점에서 prevOf 가 empty 라 거기서 멈춤(병점 상류는 세마/서동탄 갈래라 병점까지만)

### 좌표
- SubwayTrainSegmentResolver.terminatesBefore / SubwayStationArrivalQueryService.getArrivals 필터

### 검증
- `./gradlew test --tests '*SubwayTrainSegmentResolverTest' --tests '*SubwayStationArrivalQueryServiceTest'` → 34/0 (terminatesBeforeBoard, dropsTrainTerminatingBeforeStation)
## 2026-10-10 #317 구간 앵커 도착 예정 = GTFS 계획 초로 통일 (fix/subway-segment-scheduled-only, S)

### 확정
- 사용자 결정: `segment.expectedAt` 은 실측 말고 **계획 시간으로 통일**. `source` 는 늘 `SCHEDULED`, enum `MEASURED` 는 계약 호환용으로만 남김
- 링 ETA(SubwayRingSignalReader)의 실측 사용은 그대로 — 이번 변경은 역 도착 응답 `segment` 만

### 함정
- MEASURED 16~25초(이대→아현 16s, 역삼→강남 21s, 계획 90s) ← SubwaySegmentTimeRecorder 가 arvlCd 3→0(진입)을 도착으로 잼, 진입 신호가 역 닿기 한참 전에 옴 / 가드: SubwayTrainSegmentResolverTest.scheduledEvenWithMeasuredSamples
- 실측 출처: 2026-10-10 17:09~17:18 운행 시간대 10역 6회 폴링, #317 코멘트

### 검증
- `./gradlew test --tests '*SubwayTrainSegmentResolverTest' --tests '*SubwayStationArrivalQueryServiceTest' --tests '*SubwayStationControllerTest'` → 37/0
- 전체 `./gradlew test`(3 브랜치 합본, st-pg/st-redis) → 2838 중 실패 12. 기존 무관(main 동일): JourneyEvaluationIT 7·JourneyEvalReadPathIT 2·JourneyTransferAssessmentIT 1·RerouteRemainingPathOtpIT 1. JourneyNotificationSchemaIT 1 = 'Unable to rollback against JDBC Connection' 플래키, 단독 재실행 20/0

## 2026-10-10 #317 후속 3건 배포 (#350·#351·#352, main b802d2e6)

### 배포
- 18:16 KST. worker 먼저 → api. 마이그레이션 없음(jar 안 64개, 기존 VPS jar 와 목록 동일)
- worker `nextroute:20261010-181556-b802d2e6`, api `nextroute:20261010-181637-b802d2e6`
- 이전: 둘 다 `…-922953a4`(api `20261010-170022`, worker `20261010-165940`) → `./deploy.sh rollback-api|rollback-worker`
- jar 백업 `app.jar.bak-20261010-pre-317-followup`, 새 jar sha256 `bcbddf409df520c6…` 로컬·VPS 일치
- 절차: 스크래치 clone(main b802d2e6) `./gradlew bootJar` → jar 안 `isAdjacentInOrder`·`terminatesBefore` 확인 → 백업 → scp → `DEPLOY_REVISION=b802d2e6 ./deploy.sh worker` → `./deploy.sh api`. `docker inspect` 로 두 컨테이너 새 태그 확인
- 배포 전 확인: 운영 리비전 922953a4 가 b802d2e6 의 조상(사이 커밋 = 이번 3건 + 문서·테스트뿐)

### 검증
- 배포 후 30분 ERROR 0(api·worker)
- segment: 8역 응답 전부 `SCHEDULED`, 60~150s(이대→아현 16s → 60s, 강남→교대 90s)
- 코레일 출발 기록: 배포 6분 만에 `subway:dep:2026-10-10:1001:*:1001080*` 키 29개(배포 전 0)
- 수원 `departed[]`: 18:23 상행 `0474 광운대행 @화서 source=POSITION`(배포 전 늘 빔)

### 미결
- 수원 상행 병점행 제외 실사례 미확인(18:17~18:21 남쪽발 병점행 없음). 확인: `curl -s https://api.refit-100.site/api/subway/stations/1001080155/arrivals | jq '.directions[] | select(.direction=="상행") | .trains[] | select(.destinationName=="병점")'` 가 비면 OK

## 2026-10-10 배포 기록 — #328 기기 익명 계정 (api)

### 배포
- 15:29 KST api 교체 `26fc4554` → 이미지 `nextroute:20261010-152947-26fc4554`, 이전 `20261010-012003-7c336de8`(`./deploy.sh rollback-api`). worker 는 바꾸지 않음(`927af672`)
- jar 백업 `app.jar.bak-20261010-pre-device-auth`. 배포 전 `auth_identity` 덤프 `/root/backups/auth_identity-20261010-pre-v62.sql`(TOSS 93·APPLE 1·KAKAO 1)
- V62 는 jar 안에 포함(마이그레이션 62개) → api 부팅 시 Flyway 가 적용(`Successfully applied 1 migration ... now at version v62`, 0.024s). 별도 수동 SQL 없음
- 롤백 시 주의: 이전 이미지는 V62 를 모른다 — Flyway 기본값(`*:future` 무시)이라 부팅은 되고, CHECK 에 DEVICE 가 남아 있어도 구 코드 동작엔 영향 없음

### 검증
- 운영 DB: `auth_identity_provider_check` 에 DEVICE, `uq_auth_identity_single_app_provider`·`uq_auth_identity_user_provider` 조건에 DEVICE 제외 확인
- 공개 도메인 실호출: `/api/auth/device` 발급(provider=DEVICE) → `/api/me` `identities=[]`·이름/이메일 null → `/api/route/fav` 200 → 같은 비밀값 재진입 같은 userId → `/api/auth/reissue` 200 → 형식 위반 400 `INVALID_DEVICE_SECRET` → `DELETE /api/auth/me` 204 → 옛 토큰 401
- 시험 계정(userId 96)은 탈퇴로 정리됨(auth_identity 0행, users.deleted_at 채움). 배포 후 api ERROR 0
- 남은 것: 앱 출시(`docs/plan/device-anonymous-auth/APP-API-CHANGES.md`)

## 2026-10-10 #357 push 프레임 링 되감김 — 서버 화면 단조 (fix/ring-push-monotonic)

### 확정
- 역 순번은 원래 단조였다(`targetSequence` 가 직전 상태에서만 전진, 낮은 역 관측 무시). 되감김은 **같은 구간 안 진행률**뿐
- 원인: `segmentProgress` 는 `progressBaseAt` 시점 값이고 앱은 NORMAL+예정 있을 때 상한까지 보간한다. `freeze`·`hold`·위치 폴백 freeze 가 **저장 base** 로 멈추고, `reanchored` 는 `observedAt`(원천 지연) 기준이라 지금 화면보다 낮아진다. 앱 가드(`JourneyGuidanceLiveActivity.swift` shownSubwayProgress)는 push 프레임에 안 걸린다
- 수정: `SubwayRingCalculator.next()` 마지막 `notBehindScreen` — 같은 고정 세대(validPrevious)·같은 nextStationSequence 에서 새 상태의 now 화면값 < 직전 now 화면값이면 `withAnchor(직전 화면값, now)`(상한 ≥ 값). 식은 앱 `shownSubwayProgress` 와 동일(`SubwayRingState.shownProgressAt`, 소수 4자리 내림)
- 의도된 계약 변경: "멈추면 마지막 저장 base" → "멈추면 지금 화면 값". STALE 링은 보간이 없어 base=화면이라 한 번 올린 뒤 그대로(push 지문도 안정)
### 함정
- 기존 테스트 6개가 저장 base 로 멈추는 동작(= 되감김)을 고정하고 있었다 → 기대값을 화면값으로 수정(enrouteWithoutSecondsFreezes·noObservationFreezes·staleObservationFreezes·frozenProgressNeverGrows·dwellHoldsInPlace·freezesWithoutSeconds·positionBeforeNextStationFreezes)
- 스크래치 IT DB: 일회용 `rp-pg`(:55434)·`rp-redis`(:56381). pg_isready 는 init 중에도 통과 → "ready to accept connections" 2회 뜬 뒤 복원해야 init 스크립트와 안 겹친다
### 미결
- Redis 링 유실(TTL 30분) 시 직전 상태 없이 승차 구간부터 재시작 — 별건
### 검증
- `./gradlew test --tests '*SubwayRing*' --tests '*SubwayGuidance*' --tests '*JourneySubwayGuidance*' --tests '*SubwayRideScenario*'` → 163/163 (신규 NeverBehindScreen 4)
- 전체 `./gradlew test --continue` → 2877 중 12 실패, 전부 기존 무관(JourneyEvaluationIT 7·JourneyEvalReadPathIT 2·JourneyTransferAssessmentIT 1·RerouteRemainingPathOtpIT 1·JourneyNotificationSchemaIT 1 flaky)
### 배포
- 마이그레이션 없음. worker 만 교체(링 갱신기). api 는 스냅샷이 Redis 값을 읽으므로 변경 불필요

## 2026-10-10 지하철 탑승 증거·자동 판정기 제거 (chore/remove-subway-boarding-evidence, 앱 nextroute-mobile#15)

### 확정
- 앱 origin/main 호출 0 확인: `boarding-evidence`·`binding/reset`·`not-boarded`. 앱이 쓰는 건 `train-selection`·`train-candidates`·`trackability` 뿐
- 판정기는 `last_movement_seq IS NOT NULL` 행만 봐서 evidence 0건이면 입력 0 — 죽은 코드였다
- DB 무변경: 컬럼·enum(`MOTION_*`·`PROVISIONAL`)·`subway_binding_event` 행 유지. 링·계승은 기존 PROVISIONAL 행을 계속 다룬다
- 설정 `journey.subway.binding` 은 `lock-timeout`·`algorithm-version` 만 남김. VPS env `JOURNEY_SUBWAY_BINDING_ENABLED=true` 는 무해한 잔여(읽는 곳 없음)
### 함정
- 조사 에이전트가 "컨트롤러 = evidence 3개 엔드포인트"라고 보고했지만 같은 컨트롤러에 선택 API 3개가 더 있었다 → 통째 삭제했다가 복원. 삭제 전 파일의 매핑 전부를 직접 볼 것
- 엔티티 메서드를 정규식/javadoc 경계로 자르다 `inheritTo` 까지 날아갔다(컴파일이 잡음) → 정확한 시작·끝 문자열로 잘라야 한다
### 좌표
- infrastructure/adapter/in/api/journey/JourneySubwayBindingController.java — 선택 API 3개만 남음
- application/journey/service/SubwayBindingLifecycleService.java inherit() — 근거 재발행 제거
### 검증
- `./gradlew test --tests '*Subway*' --tests '*JourneySubwayGuidance*'` → 546/546 (SubwayRideScenarioIT 4·ConcurrencyIT 1·TrainSelectionIT 9·RerouteInheritanceIT 4 실제 실행 확인)
- 전체 `./gradlew test --continue` → 2790 중 12 실패, 전부 기존 무관(JourneyEvaluationIT 7·JourneyEvalReadPathIT 2·JourneyTransferAssessmentIT 1·RerouteRemainingPathOtpIT 1·JourneyNotificationSchemaIT 1 flaky)
- `git grep 'boarding-evidence|SubwayBindingMatch|SubwayTrainMatcher|SubwayBoardingEvidence|SubwayBindingUseCase|findMatchable'` → 0
### 배포
- 마이그레이션 없음. api(엔드포인트 제거)+worker(판정 스케줄러 제거) 교체. 순서 무관

## 2026-10-10 지하철 링 TYPICAL PR-1 — 예정 출처·단계·근거 시각 계약 (feat/ring-eta-source-contract)

### 확정
- 플랜 = `docs/plan/subway-ring-typical-progress/PLAN.md` v2(Opus 리뷰 REVIEW.md 반영). 이 PR 은 **화면 동작 불변**, 칸·지문만
- `SubwayRingState` +5칸: `etaSource`·`alightingEtaSource`(외부), `stage`·`sourceObservedAt`·`sourceSeenAt`(내부). 옛 22인자 생성자 유지, with… 전부 통과(시험 고정)
- `sourceSeenAt` = 갱신기가 결정: 관측 원천 시각이 직전 링 `sourceObservedAt` 과 같으면 직전 `sourceSeenAt` 이어쓰기, 다르면 now. `collectedAt` 은 매 수집마다 새로 찍혀(`RealtimeSubwayCollector:100`) 이 용도로 못 씀(리뷰 R1)
- 계산기 출처: `reanchored`→LIVE, `fromMeasured`→MEASURED, 예정 null 이면 출처 null(`stamped` 가 정규화). 단계: 정차 hold DWELL, 진입 APPROACH, 도착 ARRIVED(승차역 도착은 다음 구간 RUN — `arrivedAt` 가드), LATE, 그 외 RUN, freeze 는 직전 단계 유지
- 하차 예정 출처: 다음 역이 하차역이면 etaSource, 아니면 TYPICAL(남은 구간 GTFS), 하차 예정 null 이면 null
- Context `typical` = 목표 구간만 `SubwayLineOrder.segmentTimes`(정차=같은 패턴 직전 역 도착→출발, 0 이하 null; 운행=출발→도착). 계산기는 아직 안 읽음
- 설정 5개(typical-factor 1.2·default-dwell 30s·stale-after 60s·stale-source-max-age 240s·late-grace 30s) 추가만, 미사용
- 지문에 etaSource·alightingEtaSource 추가 → **배포 직후 진행 중 링마다 ANCHOR push 1건·guidanceVersion 1회 상승(일회성, 허용 — 리뷰 Y4)**
### 함정
- `anchored()` 는 승차역(0) 진행률 1 이면 `advanced()` 로 다음 구간을 만든다 → 호출부가 ARRIVED 를 덮어쓰면 새 구간에 ARRIVED 가 붙어 PR-3 의 단계 역행 금지가 그 구간을 막는다. `arrivedAt(target, …)` 로 순번이 같을 때만 붙임
### 검증
- `./gradlew test --tests '*SubwayRing*' --tests '*SubwayGuidance*' --tests '*JourneySubwayGuidance*' --tests '*SubwayRideScenario*' --tests '*SubwayLineOrder*'` 통과(신규: SubwayRingStateSourceTest 3·Calculator SourceAndStage 5·RedisSubwayRingAdapterIT +2·SubwayLineOrderTest +2·SubwayRingIT +1·Decider +1·Payload +1)
- 전체 `./gradlew test --continue` → 2805 중 12 실패, 전부 기존 무관(JourneyEvaluationIT 7·JourneyEvalReadPathIT 2·JourneyTransferAssessmentIT 1·RerouteRemainingPathOtpIT 1·JourneyNotificationSchemaIT 1)
### 배포
- 마이그레이션 없음. api(스냅샷 칸)+worker(링·push) 교체. 롤백 안전: 새 칸은 옛 코드가 무시(Boot 기본 매퍼), 새 enum 은 배포 후 값 추가 금지
