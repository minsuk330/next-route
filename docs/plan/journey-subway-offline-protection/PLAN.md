# journey-subway-offline-protection — 실행 계획 (M)

- 상위: [PHASE-4.md](../journey-navigation/PHASE-4.md) ③
- 근거: SC-12(지하철 GPS 공백 보호 lease) 2·3·5항
- 선행: ② `journey-lease-expiry-worker` (PR #109, 머지됨)
- 사람용 요약: [SUMMARY.md](./SUMMARY.md)
- 작성일: 2026-08-04

## 1. 이미 있던 것과 없던 것

| SC-12 항목 | 상태 |
|---|---|
| 2항 `RIDE_SUBWAY` 진입 시 설정 | 있었다 — `JourneyCommandService` 의 전이 처리 |
| 3항 초기값 = `min(예상 종료 + 5분, 시작 + 60분)`, 없으면 계획 소요시간 | 있었다 |
| 3항 **progress 로 ETA 가 보정되면 상한 안에서 갱신** | **없었다 → 이 PR** |
| 5항 단계 이탈·여정 종료 시 제거 | 있었다 — 전이 처리와 `JourneySession.end` |
| 4항 만료 worker 가 보호 시각을 함께 본다 | ② 에서 완료(PR #109) |

즉 이 PR 의 실제 작업은 **갱신 경로 추가**와 **계산식을 한 곳으로 모으는 것**이다.

## 2. 구현

### 2.1 `SubwayOfflineProtectionPolicy` (신규)

```java
void apply(JourneySession journey, JourneyLeg currentLeg, Instant observedEndAt, Instant at)
```

- `currentLeg` 가 없거나 `RIDE_SUBWAY` 가 아니면 `journey.clearSubwayProtection()`
- 단계 시작 = `leg.activatedAt` (없으면 `at`)
- 예상 종료 = `observedEndAt` → `leg.predictedEndAt` → `단계 시작 + plannedDurationSec` (없으면 단계 시작)
- 보호 시각 = `min(예상 종료 + subwayProtectionBuffer, 단계 시작 + subwayProtectionCap)`

기본값은 여유 5분·상한 60분(`JourneyNavigationProperties`).

### 2.2 `JourneyCommandService`

`updateSubwayProtection` private 메서드를 지우고 정책에 위임한다. `observedEndAt` 은 `null` — 전이 시점에는 관측이 없다. **동작은 그대로다**(기존 `JourneyEventIT` 의 진입·이탈 시험이 그것을 지킨다).

### 2.3 `JourneyProgressService`

`apply` 마지막, 여정 행을 다시 읽은 뒤에 `refreshSubwayProtection` 을 부른다.

```
조건: result == APPLIED && command.legIndex() == journey.currentLegIndex
관측 종료 = remainingDurationSeconds == null ? null : observedAt + remainingDurationSeconds
정책.apply(refreshed, leg, 관측 종료, now)
```

- `APPLIED` 만 — 재전송(DUPLICATE)이나 뒤늦은 관측(STALE)으로 보호 시각이 움직이면, 지하철에서 재전송을 반복하는 앱이 보호를 늘리는 수단을 갖게 된다
- 현재 단계일 때만 — progress 는 `legIndex >= currentLegIndex` 를 허용한다
- **다시 읽은 엔티티에** 적용한다. `bumpEvaluationVersion`·`extendLeaseIfStale` 이 `clearAutomatically=true` 라 그 전에 바꾼 값은 사라진다. 이 순서를 바꾸면 조용히 저장되지 않는다

## 3. 하지 않는 것

- `journey_leg.predicted_end_at` 저장 — Phase 3 이 ETA 를 요청 시점 계산으로 정했다. 보호 시각은 진행도가 준 남은 시간으로 바로 계산한다
- 대기 단계(`WAIT_SUBWAY`) 보호 — SC-12 는 `RIDE_SUBWAY` 만 말한다. 플랫폼에서는 GPS 가 살아 있는 경우가 많고, 대기까지 보호하면 앱이 죽은 여정이 오래 살아남는다
- 지하철 실시간 도착으로 보정 — ④ 평가기의 몫

## 4. 검증

`SubwayOfflineProtectionPolicyTest` — 계산 경계만 본다.

| 입력 | 기대 |
|---|---|
| 계획 15분, 관측 없음 | 시작 + 20분 |
| 관측 종료 = 시작 + 30분 | 시작 + 35분 |
| 관측 종료 = 시작 + 2시간 | **시작 + 60분**(상한) |
| `RIDE_BUS` 단계 | null |
| 단계 없음 | null |
| `activatedAt` 없음 | `at` 기준으로 계산 |
| 계획값도 없음 | 시작 + 5분 |

`JourneySubwayProtectionIT`(@SpringBootTest) — 지하철 경로(대기 0·주행 1·도보 2)를 만들어 전이·진행도를 실제로 보낸다.

| 확인 | 기대 |
|---|---|
| 대기 → 주행 전이 | 보호 시각 설정, lease 보다 뒤 |
| 남은 30분 보고 | 보호 시각이 계획 기준보다 뒤로, 상한 이내 |
| 남은 2시간 보고 | 정확히 `activated_at + 60분` |
| 더 오래된 관측(STALE) | 보호 시각 불변 |
| 같은 `progressId` 재전송 | 보호 시각 불변 |
| 주행 → 도보 전이 | null, 이후 도보 progress 도 되살리지 않음 |
| 남은 시간 없이 보고 | 보호 유지, 상한 이내 |

## 5. 함정

- **여정 행을 다시 읽기 전에 보호 시각을 바꾸면 사라진다.** `clearAutomatically` 때문이다
- **`STALE`·`DUPLICATE` 에서 갱신하지 마라.** 보호 연장이 재전송으로 가능해진다
- **상한 기준은 `now` 가 아니라 단계 시작이다.** `now` 기준으로 잡으면 시간이 흐를수록 상한도 밀려 무한 연장이 된다
- **지하철을 벗어날 때 지우는 책임은 전이 경로에 있다.** progress 경로도 같은 정책을 부르므로 결과적으로 두 곳이 모두 지우지만, 전이가 정답 시점이다
