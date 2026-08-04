# 지하철 오프라인 보호 시각 (M)

## 뭘 하나

지하철 주행 중 앱이 "이 단계가 아직 N초 남았다"고 보고할 때마다 보호 시각을 다시 세운다. 상한은 단계 시작 + 60분이다. 계산식은 새 `SubwayOfflineProtectionPolicy` 한 곳으로 모았다.

## 왜

보호 시각을 세우고 지우는 일은 이미 단계 전이(명령) 경로에 있었다. 문제는 그 값이 **검색이 준 계획 소요시간**에서 나온다는 것이다. 지하철이 실제로 더 걸리면 계획 기준 보호 시각이 먼저 지나가고, 앞선 PR(#109)의 만료 배치가 그 여정을 접는다. 앱은 멀쩡히 터널 안에서 돌고 있는데 여정이 끝나 버린다.

## 규칙

```
보호 시각 = min(예상 종료 + 5분, 단계 시작 + 60분)
```

예상 종료는 아는 것 중 가장 신선한 값을 쓴다. **방금 받은 관측**(`observedAt + remainingDurationSeconds`) → 저장된 예측값 → 검색이 준 계획 소요시간.

상한이 무한 연장을 막는다. 앱이 계속 "더 걸린다"고 보고해도 단계 시작 기준 60분을 넘지 않으므로, 지하철에서 앱을 강제 종료한 경우에도 최대 60분 뒤에는 만료 대상이 된다(SC-12 3항).

## 핵심 결정

- **계산을 정책 하나로 모았다.** 전이 경로와 진행도 경로가 같은 값을 계산하는데, 식을 양쪽에 각각 두면 언젠가 갈라진다. `JourneyCommandService` 에 있던 private 메서드를 `SubwayOfflineProtectionPolicy` 로 옮기고 두 경로가 같은 빈을 부른다
- **반영된 관측일 때만 움직인다.** 재전송(DUPLICATE)이나 뒤늦게 온 관측(STALE)으로는 보호 시각이 바뀌지 않는다
- **보고한 단계가 지금 지나는 단계일 때만 본다.** progress 는 다음 단계를 미리 가리킬 수 있다
- **`predictedEndAt` 을 새로 저장하지 않는다.** Phase 3 이 ETA 를 그때그때 계산하기로 정했다. 보호 시각은 진행도가 보낸 남은 시간으로 바로 계산한다
- **여정 행을 다시 읽은 뒤에 보호 시각을 건드린다.** progress 경로의 조건부 UPDATE 들이 영속성 컨텍스트를 비우므로, 그 전에 바꾼 값은 사라진다

## 건드는 곳

```
application/journey/service/SubwayOfflineProtectionPolicy.java   (신규)
application/journey/service/JourneyCommandService.java           (private 계산 → 정책 위임)
application/journey/service/JourneyProgressService.java           (관측 기반 갱신 추가)
```

마이그레이션 없음(컬럼은 V37). 새 설정값 없음(`subwayProtectionBufferSeconds`·`subwayProtectionCapSeconds` 는 이미 있다).

## 검증법

- `SubwayOfflineProtectionPolicyTest`(7) — 계획 소요시간 대체, 관측 우선, **60분 상한**, 지하철 아닌 단계에서 지움, 단계 모를 때 지움, 시작 시각 없을 때 지금 기준, 계획값조차 없을 때 여유 5분만
- `JourneySubwayProtectionIT`(7) — 진입 시 설정, 관측으로 뒤로 이동, 상한 초과 불가, STALE·DUPLICATE 는 안 움직임, 지하철 이탈 시 제거되고 이후 진행도가 되살리지 않음, 남은 시간 없이 와도 유지
- 회귀: 전체 991개 통과(기존 `JourneyEventIT` 의 진입·이탈 시험 포함)

## 리스크

앱이 남은 시간을 과소 보고하면 보호 시각이 앞으로 당겨져 실제보다 일찍 만료될 수 있다. 여유 5분이 그 완충이고, 만료 자체는 lease 3분 + 유예 2분을 함께 봐야 하므로 즉시 종료로 이어지지는 않는다.
