# M7 PR-5 — 지하철 진행 링 + Live Activity 원격 갱신

## 뭘 하나
서버가 고정된 열차의 **실시간 도착초로 시간 앵커**를 만들어 여정 스냅샷에 싣고, 앱이 그 앵커로
원형 진행 링을 로컬 보간한다. 그다음 화면이 잠겨 있어도 갱신되도록 Live Activity 푸시를 붙인다.
두 PR — 5a(링+스냅샷, 마이그레이션 없음), 5b(토큰 V46 + push outbox V47 + 발송).

## 왜
판정까지만 있어 열차를 골라 놓고도 화면에 아무것도 안 나온다. 서버가 진행 숫자를 자주 밀어넣는
구조가 아니라 앵커를 주고 앱이 보간하는 구조로 간다.

## 핵심 결정
- **APNs 직접 어댑터 없음.** 쓰고 있는 `firebase-admin:9.10.0` 에 `setLiveActivityToken()` 실재 →
  `.p8`·HTTP/2·신규 의존성 전부 불필요
- **두 원천을 역할로 나눈다.** 남은 초는 도착 API 만 주고, 구간 전환 폴백은 위치 API 가 맡는다 —
  도착은 역당 2대만 줘서 실측 미커버 3%+먼역 5%다. 위치를 빼면 그 열차는 여정 내내 링이 죽는다.
  정적 구간시간·중위값·시간표·GPS 폴백은 전부 기각(거짓 진행)
- **`barvlDt=0` 은 `arvlCd` 로 갈라 읽는다.** `99`+0 = "3정거장 전"(freeze·STALE),
  `1`+0 = 도착(1.00). 뭉뚱그리면 멀리 있는 열차를 90%로 표시한다
- **content-state 는 `{name, props}` 두 겹, `props` 는 JSON 문자열.** 단위가 섞인다 —
  `aps.timestamp`=초, `props` 시각=밀리초
- **`subwayGuidance` 는 `JourneySnapshot` 최상위** nullable 필드(leg 안이 아니다). `withEta()` 등
  복제에서 유실 금지
- **push 는 `CONFIRMED` 만.** 신호 지연은 상태가 아니라 `quality=STALE` 별도 필드 —
  `SIGNAL_STALE` 같은 값을 만들지 않는다
- **outbox 는 latest-wins + bounded retry.** 부분 unique 만으로는 `SENDING` 창을 못 막으니
  **발송 직전에 최신 sequence 와 `bindingVersion` 을 다시 대조**하고, `sequence`·`aps.timestamp` 는
  **Activity 전체에서 단조**로 둔다(ring 키별 재시작 금지)
- **Live Activity 토큰은 journey-scoped.** 축을 더하면 계승마다 재등록이 필요해 push 가 끊긴다 —
  route/binding fencing 은 발송기가 맡는다
- **서버가 보장하는 것은 앵커 단조까지.** 화면 역행 금지는 앱이 직전 표시값을 하한으로 잡는다

## 건드는 곳
`JourneySubwayRingProperties` · `SubwayRingState` · `SubwayRingCalculator` ·
`SubwayRingPort`/`RedisSubwayRingAdapter` · `SubwayRingUpdateService` + 스케줄러 ·
`SubwayGuidance` · `JourneySnapshot` · `JourneyQueryService` · `SubwayLegBindingRepository` ·
`SubwayBindingLifecycleService` · V46 `subway_live_activity_token` · V47 `subway_ring_push_outbox` ·
`JourneyLiveActivityTokenController` · `FcmLiveActivityPushAdapter`

## 리스크
- **payload 계약이 앱 코드에 박혀 있다.** 겹 구조·키·단위 중 하나만 어긋나면 조용히 무시된다 →
  계약 시험 필수
- **링 품질이 노선마다 다르다** — 실측 9호선 30%가 먼 역에서만 잡히고(급행 추정), 4호선 초
  확보율 33%(코레일 직결 추정). 나쁜 노선은 `STALE` 로 정직하게 표시하고 지표로 잰다
- **V46·V47 은 VPS 수동배치.** 누락 시 프로덕션 깨짐
- PR-5b 는 모바일 토큰 등록(`pushType: .token`, 회전 관찰, 해제)이 확인된 뒤에 켠다
- 기존 설정 1건 변경: `realtime.subway.poll-interval-ms` 10000 → 15000 (위치와 주기 통일)

## 검증법
`SubwayRingCalculatorTest` 로 링 규칙(앵커 역행 없음·경계 셋은 target·이동 중 0.90·진입 0.95·
도착만 1.00·역 변경 시 0·누락 시 freeze+STALE·정적 폴백 안 씀) 고정 + 스냅샷 최상위·복제 유지·
N+1 회귀 가드 + `{name, props}`·초/밀리초 단위·`SENDING` 중 새 행이 와도 구형은 SKIPPED·
`bindingVersion` 불일치 시 미발송 계약 시험. 전체 `build` 통과.
