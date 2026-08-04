# journey-heartbeat-api — 실행 계획 (M)

- 상위: [PHASE-4.md](../journey-navigation/PHASE-4.md) ①
- 근거: SC-7 5항(진행도를 못 만들 때 최대 30초 heartbeat), SC-3 1항(3분 lease + 2분 유예), SC-12 6항(보호 시각 안 복귀 시 최신 스냅샷 수렴)
- 사람용 요약: [SUMMARY.md](./SUMMARY.md)
- 작성일: 2026-08-04

## 1. 계약

```
POST /api/journeys/{journeyId}/heartbeat
Authorization: Bearer <JWT>
(본문 없음)

200 → JourneySnapshot (예측 필드 eta 는 null)
401 → 인증 없음
404 → 없는 여정 / 남의 여정 (JOURNEY_NOT_FOUND)
409 → ACTIVE 아님 (JOURNEY_NOT_ACTIVE)
```

본문이 없는 이유 — 이 요청이 전하는 정보는 "앱이 살아 있다" 하나뿐이다. 무엇을 관측했는지는 progress 의 몫이다. `routeRevision` 도 받지 않는다. lease 연장은 경로 개정본과 무관하고, 개정본을 받으면 앱이 낡은 값을 들고 있을 때 살아 있다는 신호까지 거절하게 된다.

호출 주기는 앱이 정한다. 최대 30초(SC-7 5항)이고, progress 를 만들 수 있으면 progress 가 이 역할을 겸한다.

## 2. 구현

### 2.1 `HeartbeatJourneyUseCase`

```java
JourneySnapshot heartbeat(Long userId, Long journeyId);
```

### 2.2 `JourneyHeartbeatService`

`@Transactional`. 순서는 다음과 같다.

1. `findByIdAndUserIdAndDeletedAtIsNull` — 소유자를 **쿼리 조건에** 넣는다. 꺼낸 뒤 비교하는 방식은 언젠가 빠뜨린다
2. `status != ACTIVE` → `JOURNEY_NOT_ACTIVE`
3. `extendLeaseIfStale(id, now, now + guidanceLease, now - leaseWriteCoalesce)` — progress 가 쓰는 것을 그대로 재사용. 새 쿼리를 만들지 않는다
4. 다시 읽어서 `queryService.snapshotOf(refreshed)` 반환 — 3번 쿼리가 `clearAutomatically` 라 영속성 컨텍스트가 비워졌다. 다시 읽지 않으면 연장 전 lease 가 응답에 실린다

여정 행을 잠그지 않는다. 이 경로는 상태를 읽고 판단해 쓰는 것이 아니라 조건부 UPDATE 한 번이므로, 잠금은 progress 와 부딪히는 비용만 만든다.

### 2.3 `JourneyHeartbeatController`

`@Tag(name = "[회원] 여정")`, `@AuthenticationPrincipal Long userId`. `JourneyController` 와 같은 `/api/journeys` prefix 라 `SecurityConfig` 의 `/api/journeys/**` authenticated 규칙이 그대로 적용된다.

**ETA 합성을 하지 않는다.** `JourneySnapshotEtaComposer` 를 부르지 않는다. 이유는 SUMMARY 참고. `PUT /progress` 도 같은 선택이므로 두 고빈도 경로의 모양이 일치한다.

## 3. 하지 않는 것

- 만료 판정 — ② `journey-lease-expiry-worker` 의 몫
- 지하철 보호 시각 갱신 — ③ 의 몫
- `nextEvaluationAt` 을 채우거나 평가를 부르는 일 — ④ 의 몫
- heartbeat 수신 계측(telemetry) — Phase 7 O11~O15

## 4. 검증

`JourneyHeartbeatIT`(@SpringBootTest, 로컬 Postgres)

| 확인 | 방법 |
|---|---|
| lease 가 늘어난다 | `last_heartbeat_at` 을 90초 과거로 밀고 호출 → `guidance_lease_until` 이 이전보다 뒤 |
| 쓰기가 합쳐진다 | 연장 직후 두 번 더 호출 → `guidance_lease_until` 그대로 |
| 번호가 안 오른다 | `commandVersion`·`evaluationVersion` 이 시작 시점과 같음 |
| 예측이 없다 | 응답 `eta == null`, `legs` 는 채워짐 |
| 끝난 여정 | `status='EXPIRED'` 로 바꾼 뒤 호출 → `JOURNEY_NOT_ACTIVE` |
| 남의 여정 | 다른 userId 로 호출 → `JOURNEY_NOT_FOUND` |

시계를 흔들지 않고 `last_heartbeat_at` 을 과거로 미는 방식은 `JourneyProgressIT.leaseExtendsAfterCoalesceWindow` 와 같다.

`JourneyHeartbeatControllerTest`(@WebMvcTest + SecurityConfig) — 200·401·404·409.

## 5. 함정

- **`extendLeaseIfStale` 뒤에 다시 읽어야 한다.** 안 읽으면 응답이 연장 전 lease 를 싣고, 앱은 서버가 무시했다고 판단해 더 자주 보낸다
- **여정 시작 직후 heartbeat 는 아무것도 쓰지 않는다.** 시작이 `lastHeartbeatAt` 을 채우므로 합치기 창 안이다. 정상이다. 테스트에서 lease 연장을 보려면 마지막 신호 시각을 과거로 밀어야 한다
- **`commandVersion` 을 올리지 않는다.** 낙관적 락의 기준이다
