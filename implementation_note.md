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
