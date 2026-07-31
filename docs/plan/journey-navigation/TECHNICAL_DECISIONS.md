# Journey Navigation 기술 결정

- 상태: **LOCKED**
- 확정일: 2026-07-27
- 요구사항: [REQUIREMENTS.md](./REQUIREMENTS.md)
- 백엔드 계획: [PLAN.md](./PLAN.md)
- 모바일 계획: [MOBILE_PLAN.md](./MOBILE_PLAN.md)

## 1. 앱 강제 종료와 Journey lease

### 결정

- 정상 foreground·background 상태에서는 Journey를 계속 진행한다.
- 모바일은 위치 이벤트 또는 heartbeat로 `guidanceLeaseUntil`을 연장한다.
- 기본 lease는 마지막 정상 신호부터 3분이며 설정값으로 관리한다.
- lease가 만료되면 서버가 Journey를 `EXPIRED`로 종료하고 평가·알림을 중단한다.
- `EXPIRED` Journey는 앱을 다시 실행해도 복원하지 않는다.
- 앱 내 종료 버튼은 lease를 기다리지 않고 즉시 `CANCELED`로 변경한다.

### 플랫폼 한계

iOS·Android는 사용자의 강제 종료 사실을 서버에 즉시·확실하게 전달하지 않는다. 따라서 “강제 종료 즉시 종료”는 heartbeat 중단을 감지하는 lease 만료로 근사한다.

서버는 강제 종료, 프로세스 crash, 장시간 네트워크 단절을 구분할 수 없다. 그러므로 3분 동안 신호가 없으면 원인과 관계없이 `EXPIRED` 처리한다. 앱이 lease 만료 전에 재접속하면 기존 ACTIVE Journey로 수렴할 수 있다.

heartbeat는 상태 전이 event가 아니며 `commandVersion`을 증가시키지 않는다. foreground와 Android foreground service는 기본 30초마다 보내고, 서버는 인증된 Journey 소유 user의 요청일 때만 lease를 연장한다. iOS background에서는 임의 주기 timer를 신뢰하지 않고 Core Location callback을 heartbeat로 사용한다. DB hot row를 줄이기 위해 실제 lease 갱신은 Journey별 최대 60초에 한 번으로 합친다.

### 종료 규칙

| 원인 | 결과 | endReason |
|---|---|---|
| 사용자 앱 내 종료 | 즉시 `CANCELED` | `USER_CANCELED` |
| 목적지 도착 | `COMPLETED` | `DESTINATION_REACHED` |
| heartbeat 3분 미수신 | `EXPIRED` | `CLIENT_LEASE_EXPIRED` |
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
- high priority는 하차·환승 위험처럼 즉시 사용자 행동이 필요한 알림에만 사용한다.
- FCM에 이미 전달된 알림을 lease 만료 후 회수할 수는 없으므로 짧은 TTL로 늦은 알림을 최소화한다.

## 3. Version 모델

빈번한 ETA 갱신이 사용자 command의 낙관적 락과 충돌하지 않도록 세 version을 분리한다.

| 필드 | 증가 조건 | 용도 |
|---|---|---|
| `commandVersion` | 탑승·하차·leg 전이·종료 등 canonical 상태 변경 | `baseCommandVersion` 낙관적 락 |
| `evaluationVersion` | ETA·남은 정류장·환승 상태·next action 갱신 | snapshot 최신성·push 재조회 판단 |
| `routeRevision` | 재탐색 제안 수락으로 남은 경로 교체 | 이전 경로 이벤트·알림·비동기 결과 fencing |

규칙:

- ETA polling만으로 `commandVersion`을 증가시키지 않는다.
- command 응답은 세 version을 모두 반환한다.
- 클라이언트 event는 `baseCommandVersion`과 `routeRevision`을 보낸다.
- snapshot은 `(commandVersion, evaluationVersion, routeRevision)`을 기준으로 병합한다.
- 이전 `routeRevision`의 evaluator 결과와 알림은 폐기한다.

## 4. Journey 상태 전이

1차 상태 전이는 다음 표를 기준으로 구현한다.

| 이벤트 | 사전 상태 | 상태 변화 | commandVersion |
|---|---|---|---|
| `JOURNEY_STARTED` | ACTIVE Journey 없음 | Journey ACTIVE, 첫 leg ACTIVE | 1로 생성 |
| `USER_CONFIRMED_BOARDING` | WAIT_BUS ACTIVE | WAIT_BUS 완료, RIDE_BUS 활성화 | +1 |
| `USER_CONFIRMED_ALIGHTING` | RIDE_BUS ACTIVE | RIDE_BUS 완료, 다음 leg 활성화 | +1 |
| `USER_SELECTED_VEHICLE` | WAIT_BUS 또는 RIDE_BUS | vehicle 변경 | +1 |
| `REROUTE_ACCEPTED` | Journey ACTIVE | 완료 leg 보존, 남은 leg 교체 | +1, routeRevision +1 |
| `DESTINATION_REACHED` | 마지막 leg 완료 | Journey COMPLETED | +1 |
| `JOURNEY_CANCELED` | Journey ACTIVE | Journey CANCELED | +1 |
| `LEASE_EXPIRED` | Journey ACTIVE | Journey EXPIRED | +1 |

허용되지 않은 상태의 event는 `409 INVALID_TRANSITION`으로 거절하고 최신 snapshot을 반환한다.

예측 계산은 Journey 상태 전이와 분리한다. ETA·환승 위험도·남은 정류장 갱신은 `evaluationVersion`만 증가시킨다.

## 5. 트랜잭션 경계

### 5.1 사용자·서버 command

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

같은 `eventId`의 재전송은 최초 결과를 그대로 반환한다.

heartbeat는 이 command transaction과 분리한다. 인증된 Journey 소유 user에 대해 `status=ACTIVE` 조건부 update로 `lastHeartbeatAt`과 `guidanceLeaseUntil`만 갱신한다. 1차는 다중 기기 구분을 지원하지 않는다.

### 5.2 ETA·환승 평가

외부 조회와 계산은 DB 트랜잭션 밖에서 실행한다.

```text
Journey snapshot 조회
→ Redis realtime snapshot·ML 결과 조회
→ 트랜잭션 밖에서 ETA·위험도 계산
→ 짧은 트랜잭션 시작
→ ACTIVE·routeRevision 재검증
→ evaluation snapshot 저장
→ evaluationVersion 증가
→ checkpoint가 바뀐 경우 outbox 생성
→ COMMIT
```

### 5.3 재탐색

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

### 5.4 Push 발송

FCM 호출은 DB 트랜잭션 밖의 relay worker가 수행한다. 상태 변경 트랜잭션은 outbox까지만 기록하며 FCM 성공을 기다리지 않는다.

## 6. 구현 순서

1. OpenAPI·상태 전이·version 계약 고정
2. Journey schema와 command 트랜잭션
3. 포그라운드 Journey 상태머신·polling
4. ETA·환승 위험·하차 checkpoint evaluator
5. 모바일 background location·heartbeat와 lease 만료
6. notification outbox와 FCM 단일 sender
7. 부분 재탐색·JourneyRevision
8. 장애·동시성·실기기 검증

## 7. 비범위

- 강제 종료를 OS callback만으로 즉시 확정
- lease 만료 후 Journey 복원
- 직접 APNs provider 연동
- silent push만으로 Journey 상태 진행
- iOS background의 JavaScript timer만으로 lease 유지
- 외부 API·ML·FCM 호출을 포함하는 장시간 DB 트랜잭션
