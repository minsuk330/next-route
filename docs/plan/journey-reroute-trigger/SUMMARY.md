# 재탐색 방아쇠 (Phase 6 ①)

## 뭘 하나

"언제 재탐색을 찾아볼지"만 정한다. 문(찾아봐도 되는 구간)과 방아쇠(실제로 찾게 만드는 사건)를
나누고, cooldown 120초를 붙인다. **방아쇠가 켜져도 지표와 로그만 남고 제안은 만들지 않는다.**

## 왜

재탐색은 ODsay 를 부르고 요금이 붙는다. **하루에 몇 번 도는지 모르는 채로 외부 검색을 붙이면
요금이 먼저 온다.** 먼저 발생률을 재고 그 수를 보고 ③을 켠다.

## 핵심 결정

- **새 상태가 `UNKNOWN` 이면 절대 방아쇠가 아니다** — 실시간 API 가 흔들리면 판정이 `UNKNOWN` 으로
  떨어지는데, 그것을 "나빠졌다"로 읽으면 API 가 흔들릴 때마다 전 사용자에게 재탐색이 돈다
- **무효화는 "통과"만 인정**(예정 시각이 지났는데 못 탐, 단 지금이 `COMFORTABLE` 이면 제외) —
  "연속 N회 목록에서 사라짐"은 회차 카운터가 필요한데 ①은 마이그레이션이 없는 PR 이다. 소실
  판정은 ③으로 미룬다
- **문은 `userReadyAt` 하나로 연다**(PHASE-6 문서의 `remainingStops <= 2` 다리 기각) — `userReadyAt`
  이 항상 그 뒤라 함의되고, 값이 평가 컨텍스트에 없어 평가마다 조회가 하나 는다
- **GPS 끊김에 별도 코드가 없다** — progress 가 끊기면 `userReadyAt` 이 null 이 되어 문이 저절로
  닫힌다. 그 성질을 테스트로 못 박는다
- **cooldown 은 Redis, 실패하면 닫는다**(메모리 기각) — worker 가 둘이면 메모리는 2배로 통과해
  shadow 에서 잰 수가 ③의 실제 호출 수와 달라진다. single-flight 는 감쌀 호출이 없어 ③으로

## 건드는 곳

`dto/RerouteTriggerSignal` · `service/RerouteTriggerPolicy` · `service/RerouteTriggerEvaluator` ·
`port/out/RerouteCooldownPort` · `cache/journey/RedisRerouteCooldownAdapter` ·
`JourneyEvaluationService`(한 줄) · `JourneyRerouteMetrics` · `JourneyRerouteProperties` ·
`application.yaml`

## 리스크

- **`UNKNOWN` 의 severity 가 `CATCHABLE` 보다 크다** — 숫자만 비교하면 "모르겠다"가 방아쇠가 된다.
  Phase 5 알림이 같은 함정을 밟았다. 단위 테스트가 이 전이를 직접 단언한다
- **소유권 확인 전에 부르면** lease 를 잃은 워커가 cooldown 을 태워 이긴 워커의 방아쇠를 먹는다
- **방아쇠 예외가 밖으로 나가면** 커밋된 평가가 실패 지표로 기록돼 진짜 저장 실패를 묻는다.
  부르는 쪽에서 삼킨다
- **`journey.reroute.enabled`(②의 기능 플래그, 기본 off)를 보면** 이 PR 이 아무것도 측정하지 않는다.
  방아쇠는 별도 스위치를 쓴다

## 검증법

`./gradlew test --tests '*RerouteTrigger*'` 통과 + 평가기·알림 기존 테스트 회귀 없음.
새 상태가 `UNKNOWN` 인 전이가 방아쇠를 안 만들고, `userReadyAt` 이 없으면 문이 닫히고,
`fired` 와 `admitted` 의 차이로 cooldown 이 깎는 양이 보이면 맞다.
**머지해도 `reroute_proposal` 에 행이 0건**인 것까지가 통과 조건이다.
