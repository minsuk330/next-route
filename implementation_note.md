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

## 2026-08-13 journey-boarding-time-estimator (Phase 6 ④ PR-1)

### 확정
- `BoardingOutcome.blocked()` 정적 팩터리는 **불가능** — record 접근자 `blocked()` 와 이름이 겹쳐 컴파일 에러
  (`invalid accessor method in record`). 팩터리를 `ofBlocked()` 로 둔다. 접근자는 계약대로 `blocked()` 유지
- 기존 시험 `TransferTargetArrivalPredictorTest` 는 **생성자 배선 2줄만** 바뀐다(단언·시나리오 전부 그대로).
  예측기가 협력자를 `PredictTransferUseCase` → `BusBoardingTimeEstimator` 로 바꿔 받으므로 무수정은 물리적으로 불가.
  대신 mock 은 여전히 `PredictTransferUseCase`/`SubwayRealtimeArrivalReader` 라 검증 대상은 동일하다
- 지하철 예측기는 `routeId`·`stopId` null 가드를 **DB 읽기 앞에** 그대로 남긴다 — 위임하면서 뒤로 옮기면
  미해석 단계에서 없던 `routeOptionRepository.findById` 가 나간다

### 함정
- 추정기에서 `boardable` 로 걸러내면 `MISSED` 감지가 죽는다 ← 소비자마다 뜻이 반대 /
  가드: `unboardablePredictionIsPassedThrough` (예측기), `doesNotFilterUnboardable` (추정기)

### 좌표
- application/journey/port/out/BoardingTimeEstimator.java — ④가 쓸 승차 추정 포트
- application/journey/service/BusBoardingTimeEstimator.java:44 — 판정 본체(기존 예측기에서 그대로 옮김)
- application/journey/service/BusTransferTargetArrivalPredictor.java:60 — DB 읽기 + 위임만 남은 껍데기

### 검증
- `./gradlew test --tests '*TransferTargetArrivalPredictorTest' --tests '*BoardingTimeEstimatorTest'` 통과
- `./gradlew build` 전체 통과

## 2026-08-13 journey-reroute-remaining-search (Phase 6 ④ PR-2)

### 확정
- **composer 를 두 걸음으로 쪼갰다**(`prepare` → `compose`). PLAN 은 `compose` 하나였지만, 그러면
  `same-as-current` 판정에 필요한 `routeId` 가 보강 뒤에야 나와 **버릴 후보에도 유료 예측이 나간다.**
  `prepare` 는 외부 호출 없이 쪼개기+보강만 하고, 공급자가 그 결과로 걸러낸 뒤 `compose` 를 부른다
- **후보 단계 번호는 상대(0부터)로 둔다.** PLAN §3.8-3 은 `fromLegIndex` 오프셋을 더하라고 했지만
  ETA 오버로드가 하한을 인자로 받으므로 불필요하고, 초안의 `pairedLegIndex` 참조가 어긋난다.
  실제 번호로 옮기는 일은 수락(⑤)의 몫
- 걷기만 경로의 `source` 는 **`STATIC`** — 예측이 없으니 REALTIME/ML 어느 쪽도 아니다
  (`RerouteCandidateDisplay` javadoc 이 허용하는 값이다)
- 캐시 fail-open 을 **어댑터와 서비스 두 곳**에 둔다. 어댑터 계약만 믿으면 포트 구현이 바뀔 때
  "Redis 장애 = 제안 없음"이 조용히 생긴다

### 함정
- 기준선을 못 재도 **빈손이 아니다**(D3) ← ③의 `baseline-unboardable` 습관 / 가드:
  `withoutBaselineOnlyEarliestSurvives`
- `more-transfers` 관문은 **기준선이 있을 때만** 적용. 없으면 견줄 대상이 없다

### 좌표
- application/journey/service/RemainingPathCandidateProvider.java:120 — collect 순서(검색→걸러내기→기준선)
- application/journey/service/RemainingPathEtaComposer.java:70 — prepare(외부 호출 없음)
- application/journey/service/RemainingRouteSearchService.java:57 — 캐시→SingleFlight→ODsay
- infrastructure/adapter/out/cache/journey/RedisRemainingRouteCacheAdapter.java:31 — 키·TTL 계약

### 배포
- 마이그레이션 없음. `journey.reroute.enabled` 는 계속 false. 새 설정 `journey.reroute.remaining-path.*`

### 검증
- 단위 5종 + `RerouteRemainingPathIT`(4건) 통과, `./gradlew build` 전체 통과
