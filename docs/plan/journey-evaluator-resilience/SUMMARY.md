# 평가기 자원 격리 (Phase 4 ⑦)

"평가기" = 앱 요청 없이 주기적으로 환승 위험을 다시 계산하는 백그라운드 작업.

## 뭘 하나

세 가지를 넣는다.

- **평가기 전용 Semaphore(permit 4)** — 지금은 경로 검색 요청과 permit 8개짜리 Semaphore 하나를
  같이 쓴다. 평가기 몫을 4로 고정해 워커 스레드 수를 올려도 검색 몫이 안 줄게 한다
- **평가기 전용 시간 예산 1500ms** — 지금은 검색용 2500ms를 그대로 쓴다. 환승 2개면 5초라 배치
  상한 4초를 넘고, 밀리면 선점이 만료돼 계산 결과를 통째로 버린다
- **provider 실패 시 120초 유예** — 지금은 조회 실패하면 즉시 UNKNOWN으로 덮어쓴다. 직전 판정이
  아직 유효하면(도착 시각 안 지남 + 계산한 지 120초 안) 유지하고, 유예를 넘기면 그때 UNKNOWN

## 왜

평가기는 사용자가 기다리지 않는 백그라운드 작업인데, 사용자 검색과 같은 자원을 같은 조건으로 쓴다.
평가기가 느려지면 그 대가를 사용자 요청이 치른다.

## 핵심 결정

- **총 동시 호출량은 안 늘린다** — 공용 Semaphore permit 8은 그대로 두고 평가기 앞에 4짜리를 하나 더
  둔다. 전용 permit을 추가로 발급해 총 12로 늘리는 안은 provider 부담이 커져 기각
- **일일 호출 예산은 안 나눈다** — 같은 외부 API 한도를 쓰는 두 소비자라 쪼개면 한쪽이 남기고 다른
  쪽이 굶는다. 사용자 지적 반영해 범위에서 제외
- **Semaphore는 외부 호출 구간만 감싼다** — DB 작업까지 넣으면 provider 동시성 제한이 DB 시간까지
  잡아먹는다
- **유예 조건을 조회 낡음 규칙과 같게 맞춘다** — 조회가 버릴 값(도착 시각 지남·5분 초과)을 평가기가
  붙잡고 있으면 안 된다
- **PHASE-4 ⑦ 항목 4개 중 3개는 이미 있다** — 스레드 풀 분리·기능 플래그·기존 캐시 재사용은 ④a에서
  끝났다. 이 PR은 남은 구멍만 채운다

## 건드는 곳

`TransferAssessmentGracePolicy`(신규) · `JourneyEvaluationService` · `JourneyEvaluatorPoolConfig` ·
`JourneyEvaluatorProperties` · `JourneyEvaluationMetrics` · `PredictTransferUseCase` ·
`SingleTransferPredictor` · `BusTransferTargetArrivalPredictor` · `application.yaml` · `PHASE-4.md`.
**마이그레이션 없음.**

## 리스크

- `PredictTransferUseCase.predict`가 5인자 → 6인자가 된다. 기존 테스트가 5인자로 스텁하면 조용히
  null을 받는다(④a에서 겪은 함정). 해당 테스트를 함께 고친다
- Semaphore 빈을 worker 프로필 안에 두면 다른 프로필에서 주입 실패로 부팅이 깨진다. 컴파일은 통과한다
- 평가기는 여전히 기본 off. 머지만으로는 운영 동작이 안 바뀐다

## 검증법

`./gradlew test` 전체 통과(회귀 0). provider 실패 시 유예 안에서는 직전 판정이 유지되고 평가 번호가
안 오르는 것, 유예를 넘기면 UNKNOWN이 되는 것, permit을 못 잡으면 아무것도 저장하지 않는 것을 IT가
확인한다. 잘못된 설정 조합에서는 부팅이 실패한다.
