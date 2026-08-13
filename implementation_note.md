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

