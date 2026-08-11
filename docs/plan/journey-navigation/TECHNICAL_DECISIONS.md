# Journey Navigation 기술 결정

- 상태: **LOCKED**
- 확정일: 2026-07-27
- 선택 노선·GPS 기반 진행 모델 반영일: 2026-08-01
- GPS 표시 튐 수용·상태 판정 단순화 반영일: 2026-08-03
- local checkpoint 계측·progress 멱등 계약 반영일: 2026-08-04
- 요구사항: [REQUIREMENTS.md](./REQUIREMENTS.md)
- 백엔드 계획: [PLAN.md](./PLAN.md)
- 모바일 계획: [MOBILE_PLAN.md](./MOBILE_PLAN.md)

## 1. 앱 강제 종료와 Journey lease

### 결정

- 정상 foreground·background 상태에서는 Journey를 계속 진행한다.
- 모바일은 위치 이벤트 또는 heartbeat로 `guidanceLeaseUntil`을 연장한다.
- 기본 lease는 마지막 정상 신호부터 3분이며 설정값으로 관리한다.
- lease 만료 뒤 기본 2분 `expirationGrace`를 두며 설정값으로 관리한다.
- `RIDE_SUBWAY` 활성화 시 `subwayOfflineProtectionUntil = min(currentLegPredictedEndAt + 5분, subwayLegStartedAt + 60분)`을 설정한다. 아직 예측 ETA가 없으면 `subwayLegStartedAt + plannedDuration`을 사용한다.
- 만료 worker는 `max(guidanceLeaseUntil + expirationGrace, subwayOfflineProtectionUntil)`이 지난 Journey만 `EXPIRED`로 종료한다. 일반 leg의 보호 시각은 `null`이다.
- `EXPIRED` Journey는 앱을 다시 실행해도 복원하지 않는다.
- 앱 내 종료 버튼은 lease를 기다리지 않고 즉시 `CANCELED`로 변경한다.

### 플랫폼 한계

iOS·Android는 사용자의 강제 종료 사실을 서버에 즉시·확실하게 전달하지 않는다. 따라서 “강제 종료 즉시 종료”는 heartbeat 중단을 감지하는 lease 만료로 근사한다.

서버는 강제 종료, 프로세스 crash, 장시간 네트워크 단절을 구분할 수 없다. 일반 leg는 3분 동안 신호가 없으면 만료 후보로 보고, 추가 2분 grace까지 신호가 없으면 `EXPIRED` 처리한다. 지하철 터널에서는 GPS callback 공백이 정상일 수 있으므로 `RIDE_SUBWAY`의 예상 종료 + 5분까지 보호하되 leg 시작부터 최대 60분으로 제한한다.

heartbeat는 상태 전이 event가 아니며 `commandVersion`을 증가시키지 않는다. 정상 progress 요청이 heartbeat를 겸하고, progress를 만들지 못한 경우에만 최대 30초 간격의 별도 heartbeat를 사용한다. 서버는 인증된 Journey 소유 user의 요청일 때만 lease를 연장한다. iOS background에서는 임의 주기 timer를 신뢰하지 않고 Core Location callback을 heartbeat 근거로 사용한다. DB hot row를 줄이기 위해 실제 lease 갱신은 Journey별 최대 60초에 한 번으로 합친다.

지하철 보호 시각은 서버 시각과 snapshot의 정적 geometry ETA로 계산한다. 새 progress로 `predictedEndAt`이 바뀌면 최대 상한 안에서 갱신할 수 있지만, 시간 경과만으로 무한 연장하지 않는다. 지하철 leg 전이가 확정되거나 Journey가 종료되면 즉시 제거한다. 강제 종료 후에도 보호 시각까지 서버 Journey가 ACTIVE로 남을 수 있는 오차는 정상 지하철 여정의 오종료를 막기 위해 수용한다.

ACTIVE Journey의 네이티브 GPS 요청 주기는 화면 표시와 `confirmedProgress` 계산 모두 1초로 고정한다. 서버 progress 업로드는 기본 15초, `RIDE_BUS && remainingStops <= 2`에서 5초로 전환하고 정상 progress가 heartbeat를 겸한다. 플랫폼이 실제 1초 callback을 보장하지 않으므로 `observedAt`을 신뢰하며, progress가 없을 때만 최대 30초 heartbeat를 사용한다. leg 전이와 하차 local notification은 업로드 주기를 기다리지 않는다.

### 종료 규칙

| 원인 | 결과 | endReason |
|---|---|---|
| 사용자 앱 내 종료 | 즉시 `CANCELED` | `USER_CANCELED` |
| 사용자 여정 완료 | `COMPLETED` | `COMPLETED` |
| 일반 leg에서 heartbeat 3분 + grace 2분 미수신 | `EXPIRED` | `CLIENT_LEASE_EXPIRED` |
| `RIDE_SUBWAY`에서 일반 lease와 지하철 보호 시각 모두 만료 | `EXPIRED` | `CLIENT_LEASE_EXPIRED` |
| 새 Journey로 명시적 교체 | 기존 Journey `CANCELED` | `SUPERSEDED` |

## 2. Push provider

### 결정

1차에서는 Firebase Admin SDK 기반 `FcmNotificationSender` 하나만 구현한다.

```text
Nextroute server
       │
       ▼
Firebase Cloud Messaging
       ├─ Android: FCM transport
       └─ iOS: APNs transport
```

- Android와 iOS 모두 FCM registration token을 서버에 등록한다.
- iOS 전달을 위해 APNs 인증키를 Firebase project에 연결한다.
- 서버는 APNs provider API를 직접 호출하지 않는다.
- DB channel은 `PUSH`, platform은 `ANDROID | IOS`, provider는 `FCM`으로 기록한다.
- 직접 APNs adapter는 Live Activity 등 APNs 전용 기능이 필요할 때 후속 도입한다.

Push는 상태 저장소가 아니다. payload에는 `journeyId`, `commandVersion`, `evaluationVersion`, `routeRevision`, `alertId`, `type`, `deepLink`만 담고 앱은 REST로 최신 snapshot을 조회한다.

- FCM TTL은 `journey_notification.expiresAt`을 넘지 않도록 설정한다.
- 같은 알림 scope에는 collapse key를 적용해 대기 중인 이전 메시지를 교체한다.
- high priority는 환승 위험·재탐색 제안처럼 즉시 사용자 행동이 필요한 서버 알림에만 사용한다.
- FCM에 이미 전달된 알림을 lease 만료 후 회수할 수는 없으므로 짧은 TTL로 늦은 알림을 최소화한다.
- 앱이 포그라운드이면 local checkpoint와 FCM 수신을 앱 내부 배너로만 표시하고 OS notification presentation은 억제한다. 백그라운드·잠금화면에서는 local notification 또는 FCM을 OS 알림으로 표시한다.
- 포그라운드 handler도 payload의 상태를 신뢰하지 않고 최신 Journey snapshot과 대조한다. 사용자 표시 중복 방지용 `logicalCheckpointKey = journeyId:routeRevision:scopeType:scopeId:checkpoint`는 채널과 무관하게 공유하고, 채널별 처리 멱등성은 `deliveryKey = logicalCheckpointKey:channel`로 별도 관리한다. local·FCM·REST 결과가 같은 logical checkpoint를 가리키면 배너 한 건으로 합친다.

## 3. Version 모델

빈번한 ETA 갱신이 사용자 command의 낙관적 락과 충돌하지 않도록 세 version을 분리한다.

| 필드 | 증가 조건 | 용도 |
|---|---|---|
| `commandVersion` | 앱이 판정한 leg 전이·재탐색 수락·종료 등 canonical 상태 변경 | `baseCommandVersion` 낙관적 락 |
| `evaluationVersion` | ETA·남은 정류장·환승 상태·next action 갱신 | snapshot 최신성·push 재조회 판단 |
| `routeRevision` | 재탐색 제안 수락으로 남은 경로 교체 | 이전 경로 이벤트·알림·비동기 결과 fencing |

규칙:

- ETA polling만으로 `commandVersion`을 증가시키지 않는다.
- command 응답은 세 version을 모두 반환한다.
- 클라이언트 event는 `baseCommandVersion`과 `routeRevision`을 보낸다.
- snapshot은 `(commandVersion, evaluationVersion, routeRevision)`을 기준으로 병합한다.
- 이전 `routeRevision`의 evaluator 결과와 알림은 폐기한다.

## 4. Journey 상태 전이

### 진행 판정 전제

- Journey 시작 시 선택된 버스 `routeId`·정류장 순서는 알고 있다.
- 실제 탑승 `vehicleId`·차량 번호·운행 회차는 식별하지 않는다. 동일 노선의 후속 차량은 같은 Journey로 처리한다.
- 상행·하행·실제 차량 heading은 판별하지 않는다. Journey의 정류장·section 순서가 이동 방향이다.
- 앱은 최신 GPS를 화면 마커에 즉시 반영할 수 있으며 일시적 시각 튐을 수용한다. 탑승에는 속도 gate, leg 전이·`ALIGHT_NOW`에는 최소 연속 sample만 적용한다.
- 승차·하차·차량 선택 확인 UI는 없다. 사용자가 누르는 Journey 상태 버튼은 취소와 완료만 제공한다.
- 다른 노선·자가용 이용 감지는 범위 밖이다. GPS 보호 로직은 지도 마커를 부드럽게 보이게 하기 위한 것이 아니라, 단일 튐이 leg 전이·하차 알림을 잘못 확정하지 못하게 하는 용도다.
- ODsay lane이 여러 개면 첫 routeOption을 현재 경로로 선택하고 나머지는 대체 후보로 보존한다. 이는 실제 차량 선택이 아니며 `directionKey`도 상·하행 분류 대신 정류장 seq 진행 순서를 사용한다.

1차 상태 전이는 다음 표를 기준으로 구현한다.

| 이벤트 | 사전 상태 | 상태 변화 | commandVersion |
|---|---|---|---|
| `JOURNEY_STARTED` | ACTIVE Journey 없음 | Journey ACTIVE, 첫 leg ACTIVE | 1로 생성 |
| `CLIENT_LEG_TRANSITION_DETECTED` | Journey ACTIVE, 기대 leg 일치 | 현재 leg 완료, 다음 leg 활성화 | +1 |
| `REROUTE_ACCEPTED` | Journey ACTIVE | 완료 leg 보존, 남은 leg 교체 | +1, routeRevision +1 |
| `JOURNEY_COMPLETED` | Journey ACTIVE | Journey COMPLETED | +1 |
| `JOURNEY_CANCELED` | Journey ACTIVE | Journey CANCELED | +1 |
| `LEASE_EXPIRED` | Journey ACTIVE | Journey EXPIRED | +1 |

허용되지 않은 상태의 event는 `409 INVALID_TRANSITION`으로 거절하고 최신 snapshot을 반환한다.

`APP_RESUMED`는 JourneyEvent로 저장하지 않는다. resume은 heartbeat 전송, 최신 snapshot 조회, best-effort telemetry 기록으로 처리하며 `commandVersion`을 증가시키지 않는다. resume 후 GPS가 실제 leg 경계 통과를 확인한 경우에만 별도 `CLIENT_LEG_TRANSITION_DETECTED` command를 적용한다.

고빈도 GPS sample은 JourneyEvent로 저장하지 않는다. 앱은 command의 `eventId`와 구분되는 고유 `progressId`를 만들고 현재 `geometrySegmentIndex`·segment/leg 진행률·남은 거리·시간·버스의 `sectionId`·남은 정류장·관측 품질을 요약해 progress API로 전송한다. 서버는 leg의 `lastProgressId`와 `progressObservedAt`만 보존한다. 현재 `progressId` 재전송은 no-op이며 `evaluationVersion`을 증가시키지 않고, 새 ID이면서 더 최신 `observedAt`인 요청만 갱신한다. 더 오래된 관측은 상태를 바꾸지 않으며 같은 `observedAt`에 다른 ID·내용이 오면 충돌로 거절한다. progress 갱신과 ETA·환승 위험도·남은 정류장 갱신은 `evaluationVersion`만 증가시킨다. leg 경계를 통과한 순간에만 `CLIENT_LEG_TRANSITION_DETECTED`를 command로 적용한다.

화면용 `displayLocation`과 Journey용 `confirmedProgress`를 분리한다. `displayLocation`은 최신 GPS를 즉시 보여줄 수 있지만, `confirmedProgress`는 Journey의 현재 section·다음 section 범위에서 유지하고 leg 전이·`ALIGHT_NOW`에만 연속 sample을 요구한다. 노선의 상행·하행 분류, heading matching, 전 구간 jump score는 구현하지 않는다.

## 5. 하차·환승 데이터 소유권

| 판정 | 소유자 | 데이터 |
|---|---|---|
| 버스 탑승·진행·하차 checkpoint | 모바일 | 사용자 GPS + 선택 노선 section polyline |
| 버스 하차 알림 | 모바일 | native background location + local notification |
| 이동 leg 종료 ETA | 모바일·서버 | ODsay 정적 leg 시간 × 사용자 GPS geometry progress |
| `WAIT_BUS` 종료 ETA | 서버 | 실시간 버스 API → ML fallback |
| `WAIT_SUBWAY` 종료 ETA | 서버 | 실시간 지하철 도착 snapshot, 누락 시 `UNKNOWN` |
| 환승할 다음 버스 ETA·재탐색 | 서버 | 실시간 버스 API + ML 예측 |
| 지하철 leg 전이 | 모바일 | 하차 후 복구된 사용자 GPS + 목적역·환승역 geofence |

실시간 버스 API로 현재 탑승 차량을 매칭하거나 하차 checkpoint를 판정하지 않는다. 버스 하차 알림은 FCM이 아니라 앱의 local notification이 기본이며, FCM은 서버가 판정하는 환승 위험·재탐색 제안에 사용한다.

`RIDE_BUS`, `RIDE_SUBWAY`, `WALK`, `TRANSFER_WALK`는 동일한 정적 geometry progress 계산을 사용한다. ODsay leg 전체 소요시간을 segment 거리 비율로 배분하고 GPS 투영 지점 이후의 배분 시간을 합산한다. 유효한 sample마다 `predictedEndAt = observedAt + remainingDuration`을 저장하며 GPS 공백 중에는 이 절대 ETA를 유지하고 quality만 낮춘다. ODsay 시간에는 대기시간이 없으므로 `WAIT_BUS`는 실시간 버스→ML fallback, `WAIT_SUBWAY`는 실시간 지하철 도착 조회를 사용하며 지하철 실시간 정보가 없으면 `UNKNOWN`으로 내린다. 지하철 하차 후 복구된 사용자 GPS가 목적역·환승역 geofence를 확인했을 때 앱이 leg 전이를 보내며, 지하철 하차 알림은 local·FCM 모두 발송하지 않는다.

환승 상태 초기값은 `alightingBufferSeconds=30`, `COMFORTABLE >= 300초`, `CATCHABLE 120~299초`, `AT_RISK 0~119초`, `MISSED < 0초`다. 필수 ETA가 누락·stale·매칭 불가이면 `UNKNOWN`이다. 확률 캘리브레이션이 부족하면 `successProbability=null`로 두고 slack 상태는 유지하며, 초기 probability는 위험 방향 downgrade만 허용한다.

ODsay `subPath.lane`의 모든 버스 노선을 `JourneyLegRouteOption`으로 보존하고 PostgreSQL을 canonical로 둔다. 재탐색은 이 표를 DB에서 직접 읽는다(SC-15). Redis 사본은 두지 않는다 — 만료·무효화 규칙이 새로 생기는 대가에 비해 조회 한 번을 아끼는 이득이 작고, 사본이 낡으면 이미 지나간 노선을 후보로 올린다. 재탐색 시 `(stopId, routeId, directionKey, targetSeq)`로 각 routeId의 실시간 도착 후보를 공유 조회하고, 같은 승·하차 구간의 다른 routeOption을 먼저 비교한다. 개선 가능한 routeOption이 없을 때만 Redis의 다른 경로 구조를 평가하고 cache miss 때 ODsay 남은 경로 검색을 호출한다.

재탐색 초기 설정은 decision window 10분 또는 하차 2정거장 이내, 최소 ETA 개선 5분, cooldown 2분, proposal TTL 2분, 표시 후보 최대 3개다.

React Native JavaScript timer·background task는 백그라운드 하차 판정의 실행 기반으로 사용하지 않는다. iOS Swift `Core Location`과 Android Kotlin foreground location service가 GPS projection·`confirmedProgress`·checkpoint dedup·local notification을 소유한다. RN은 화면과 포그라운드 복귀 동기화를 담당한다. 네트워크·FCM·서버 장애 중에도 하차 알림은 동작하고, 사용자가 앱을 강제 종료하면 네이티브 위치 세션과 이후 local notification을 종료한다.

네이티브 엔진은 local checkpoint마다 `TRIGGERED`, `PRESENTED`, `DEDUP_SUPPRESSED`, `OPENED`를 로컬에 기록하고 RN을 통해 best-effort batch telemetry로 전송한다. 각 기록은 재시도 동안 유지하는 고유 `telemetryId`와 `routeRevision`, `legIndex`, `checkpoint`, `checkpointOccurrenceId`, `surface(IN_APP_BANNER|OS_NOTIFICATION)`, `triggeredAt`, `occurredAt`을 포함하되 정밀 좌표는 포함하지 않는다. 서버는 `telemetryId` 중복을 먼저 제거한 뒤 FCM outbox/provider 계측과 local checkpoint 표시 계측을 분리해 집계한다. 중복 표시는 단말의 logical checkpoint 불변식으로 우선 차단하고, 서로 다른 `PRESENTED` 기록으로 중복 표시가 한 건이라도 확인되면 해당 플랫폼의 하차 알림 feature flag를 비활성화하고 rollout을 중단한다. 오프라인 단말의 telemetry는 지연될 수 있으므로 서버의 즉시 탐지는 보장하지 않는다.

## 6. 트랜잭션 경계

### 6.1 사용자·서버 command

하나의 짧은 PostgreSQL 트랜잭션에서 처리한다.

```text
eventId 중복 확인
→ ACTIVE·소유 user·routeRevision 확인
→ baseCommandVersion 확인
→ 허용 상태 전이 검증
→ JourneySession·JourneyLeg 변경
→ commandVersion 증가
→ JourneyEvent 기록
→ 필요한 notification outbox 생성·취소
→ COMMIT
```

같은 `eventId`의 재전송은 최초 판정을 재생한다(`APPLIED`의 재전송은 `DUPLICATE`). type·version·payload·`clientOccurredAt`이 다른 요청이 같은 `eventId`를 재사용하면 `409 EVENT_ID_REUSED`로 거절한다.

heartbeat는 이 command transaction과 분리한다. 인증된 Journey 소유 user에 대해 `status=ACTIVE` 조건부 update로 `lastHeartbeatAt`과 `guidanceLeaseUntil`만 갱신한다. 지하철 보호 시각은 heartbeat가 아니라 leg 전이·ETA 평가에서만 설정·보정한다. 1차는 다중 기기 구분을 지원하지 않는다.

### 6.2 progress·ETA·환승 평가

외부 조회와 계산은 DB 트랜잭션 밖에서 실행한다.

```text
Journey snapshot·최신 progress 요약 조회
→ 환승 필요 시 Redis realtime snapshot·ML 결과 조회
→ 트랜잭션 밖에서 ETA·위험도 계산
→ 짧은 트랜잭션 시작
→ ACTIVE·routeRevision 재검증
→ progressId 재전송·observedAt 순서 조건부 병합
→ no-op·stale이면 상태와 evaluationVersion을 바꾸지 않고 종료
→ evaluation snapshot 저장
→ evaluationVersion 증가
→ 서버 소유 환승 checkpoint가 바뀐 경우 outbox 생성
→ COMMIT
```

### 6.3 재탐색

```text
현재 snapshot 조회
→ 트랜잭션 밖에서 ODsay 후보 검색·ML ETA 재평가
→ 만료 시간이 있는 RerouteProposal 저장
→ 사용자 수락
→ baseCommandVersion·routeRevision·만료 재검증
→ 새 JourneyRevision과 남은 leg 생성
→ 기존 revision의 pending 알림·due task 취소
→ commandVersion·routeRevision 증가
→ COMMIT
```

### 6.4 Push 발송

FCM 호출은 DB 트랜잭션 밖의 relay worker가 수행한다. 상태 변경 트랜잭션은 outbox까지만 기록하며 FCM 성공을 기다리지 않는다.

## 7. 구현 순서

1. OpenAPI·상태 전이·version 계약 고정
2. Journey schema와 command 트랜잭션
3. 모바일 GPS projection·progress·local 하차 알림
4. 현재 leg ETA·환승 위험 evaluator
5. 모바일 background location·heartbeat와 lease 만료
6. notification outbox와 FCM 단일 sender
7. 부분 재탐색·JourneyRevision
8. 장애·동시성·실기기 검증

Phase 3의 최소 ETA·checkpoint가 고정되면 FCM token·sender·outbox·deep link 수직 슬라이스는 Phase 4 부하 최적화와 병행할 수 있다. 다만 전체 사용자 발송은 evaluator 안정화 이후에 활성화한다.

## 8. 개인정보·SLO·배포 gate

### 보관 원칙

- 서버는 정밀 GPS 좌표 궤적을 저장하지 않고 leg별 최신 progress 요약만 갱신한다.
- 종료된 Journey 계열과 notification 기록은 30일, 로그는 14일, 식별 가능한 UI telemetry는 30일, 비식별 집계는 1년 보관한다.
- 모바일의 마지막 위치·geometry·pending event는 terminal 상태 또는 로그아웃 시 삭제한다.
- 계정 삭제는 API·push를 즉시 차단하고 token을 제거한 뒤 사용자 연결 데이터를 24시간 안에 물리 삭제한다.

### 서버 SLO

- command 성공률 99.5% 이상, snapshot p95 500ms 이하, progress p95 300ms 이하
- evaluator lag p95 10초 이하, outbox→FCM provider p95 5초 이하, provider 접수 성공률 99% 이상
- 복구 후 상태 수렴 p95 5초 이하, 기존 API p95 회귀 10% 이하
- 이전 revision 표시·checkpoint 중복·자동 경로 교체는 0건, premature expiry는 0.5% 미만. local checkpoint 중복은 모바일 표시 telemetry로 관찰한다.
- 단말 FCM 도착시간은 통제 불가능하므로 서버 SLO가 아니라 별도 telemetry로 관찰한다.
- API 성공률은 정상적인 conflict·validation 4xx를 실패에서 제외하고, premature expiry는 `EXPIRED` 후 10분 이내 same-Journey resume telemetry를 운영 proxy로 사용한다.

### rollout gate

`OFF → 내부 shadow 100 Journey/7일 → 내부 실사용 50 Journey → 5% 500 Journey/7일 → 25% 1,000 Journey/7일 → 전체` 순서로 확대한다. 보안·revision·무단 경로 변경 위반이나 telemetry로 확인된 동일 checkpoint 중복 표시는 한 건이라도 해당 feature flag를 끄고 rollout을 중단한다. API 5xx, evaluator lag, FCM provider, DB pool, 외부 API quota, 기존 API 회귀, premature expiry 임계가 10분 지속되면 확대를 중단한다.

## 9. 비범위

- 강제 종료를 OS callback만으로 즉시 확정
- lease 만료 후 Journey 복원
- 직접 APNs provider 연동
- silent push만으로 Journey 상태 진행
- iOS background의 JavaScript timer만으로 lease 유지
- 외부 API·ML·FCM 호출을 포함하는 장시간 DB 트랜잭션
- 실제 탑승 `vehicleId`·운행 회차 식별
- 다른 노선·자가용 이용 감지
- 승차·하차·차량 선택 확인 UI
- 지하철 하차 알림·사용자가 실제 탑승한 열차의 확정적 식별
- 고급 GPS 마커 smoothing·road snapping·상행·하행 분류
