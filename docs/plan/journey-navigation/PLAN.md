# Journey Navigation 백엔드 구현 계획

- 기준 요구사항: [REQUIREMENTS.md](./REQUIREMENTS.md)
- 확정 기술 결정: [TECHNICAL_DECISIONS.md](./TECHNICAL_DECISIONS.md)
- 최초 작성일: 2026-07-22
- 1차 범위 반영일: 2026-07-27
- 기술 결정 반영일: 2026-07-27
- 이용 행태 기반 범위 축소 반영일: 2026-07-31
- 선택 노선·GPS 기반 진행 모델 반영일: 2026-08-01
- GPS 표시 튐 수용·상태 판정 단순화 반영일: 2026-08-03
- 지하철 실시간 ETA·하차 알림 제외 반영일: 2026-08-03
- GPS 1초 수집·progress 15초/5초 업로드 반영일: 2026-08-03
- 전체 선택 경로 지도 표시 반영일: 2026-08-03
- 교통수단 공통 정적 geometry progress ETA 반영일: 2026-08-03
- local checkpoint 계측·progress 멱등 계약 반영일: 2026-08-04
- 대상 레포: Spring Boot 백엔드 `nextroute`
- 모바일 경계: 본 계획은 API·이벤트·푸시 계약까지 포함하고, React Native 화면·네이티브 백그라운드 위치 코드는 별도 `nextroute-app`에서 구현한다.
- 백그라운드 하차 판정은 RN JavaScript가 아니라 Swift `Core Location`·Kotlin foreground location service가 소유하며, local notification도 네이티브에서 직접 표시한다.

## 1. 목표

기존 경로 검색·실시간 버스 캐시·ML 도착 예측·알림 outbox 위에 확정된 여섯 가지 기능을 추가한다.

1. 복구 가능한 `JourneySession`
2. Leg·TransferConnection 기반 여정 상태머신
3. 앱 GPS 기반 버스 진행·하차 안내와 다음 버스 ETA를 합성한 환승 위험도
4. 환승 위험 상승·선택 경로 무효화 시 남은 경로 부분 재탐색과 변경 제안
5. 정상 background에서 유지되고 일반 3분 lease + 2분 grace와 지하철 보호 lease 만료 시 종료되는 FCM 여정 진행
6. 이벤트 멱등성·세 version 충돌·알림 중복 방지

## 2. 현재 재사용 자산

| 자산 | 현재 위치 | Journey에서의 용도 |
|---|---|---|
| 경로 검색·leg DTO | `application/route` | Journey 시작 시 불변 route snapshot 생성 |
| 경로·정류장 구간별 geometry | 경로 snapshot, `application/bus`, `bus_route_segment_polyline` | Journey 전체 경로 지도 표시, 버스 순서 제약 GPS projection·남은 거리 계산 |
| 환승 지점 버스 예측 | `TransferArrivalEnricher`, `SingleTransferPredictor` | 다음 버스 ETA·탑승 가능 후보 |
| 버스 실시간 캐시 | `CachedSearchTimeBusAdapter`, Redis bus cache | Journey 평가 시 공유 스냅샷 |
| ML 버스 도착 예측 | `MlFeatureVectorBuilder`, `MlArrivalPredictorPort` | 환승할 다음 버스 후보의 target stop ETA·재탐색 비교 |
| ODsay leg 시간·전체 geometry | 경로 snapshot, Journey navigation geometry | 버스·지하철·도보 공통 current leg progress·남은 시간 계산 |
| 지하철 실시간 도착 snapshot | `SubwayRealtimeCachePort`, `SubwayArrivalQueryService` | `WAIT_SUBWAY` 도착 ETA; 정적 ODsay 대기시간 fallback 없음 |
| 알림 outbox | `application/notification`, `BusAlertNotification` | Journey 알림 enqueue·claim·retry 패턴 |
| 검색 복원력 | deadline·bulkhead·single-flight·metrics | Journey 평가의 부분 실패·부하 가드 |

`TransferArrivalEnricher`는 검색 시점 스냅샷으로 유지한다. Journey 진행 중 평가는 공유 포트·계산기를 재사용하는 별도 `journey` 어플리케이션 모듈에서 수행한다. 검색 hot path와 장시간 여정 실행 hot path를 하나의 서비스로 합치지 않는다.

### 인증 선행 계약

- Kakao·Google 등 OAuth provider는 `auth_identity(user_id, provider, provider_subject)`로 내부 `User`에 매핑한다.
- `provider + provider_subject`는 unique이며 Journey와 알림은 provider subject가 아니라 내부 `user_id`를 참조한다.
- OAuth provider token은 로그인 검증에만 사용하고, 성공하면 백엔드가 Nextroute access token·refresh token을 발급한다.
- access token은 내부 `userId`에 연결한다. Journey API는 이 인증 정보의 `userId`를 소유권 기준으로 사용한다.
- 다중 기기는 1차 범위가 아니다. 기기 식별·안내 기기 추적·handoff를 하지 않는다. ([SCOPE-CHANGES.md](./SCOPE-CHANGES.md) SC-1)

## 3. 확정 아키텍처

```text
Mobile
  ├─ GPS → section progress → local 하차 알림
  ├─ REST progress/command ─────────┐
  ├─ REST adaptive polling ◀────────┐  │
  └─ FCM(환승/재탐색) ◀─ Journey notification │  │
                                      │  ▼
                              Journey API / Command Service
                                      │
                         PostgreSQL canonical state + event log
                                      │
                          Due evaluator / risk orchestration
                         ┌───────┴───────┐
                         ▼               ▼
                 Redis realtime cache   ML predictor
                         │               │
                         └──── prediction evidence ───┘
                                      │
                           Journey notification outbox
                                      │
                         Firebase Admin SDK sender
                               ├─ Android FCM
                               └─ iOS via APNs
```

### 소유권

- PostgreSQL `journey_session.command_version`, `evaluation_version`, `route_revision`이 기준 상태다.
- 클라이언트는 사용자 GPS 기반 bus progress와 하차 checkpoint의 판정 주체다. leg 경계 전이는 이벤트로 서버에 보낸 후 canonical Journey 상태와 세 version으로 수렴한다.
- 화면용 최신 GPS 마커와 Journey용 `confirmedProgress`를 분리한다. 마커의 일시적 튐은 수용하고, leg 전이·`ALIGHT_NOW`에만 최소 연속 sample을 요구한다.
- Redis는 실시간 스냅샷·due index·공유 관심 노선 인덱스를 담지만 Journey canonical state를 단독 소유하지 않는다.
- 푸시는 상태 자체가 아니라 새 version과 사용자 행동을 알리는 수단이다. push 수신 후 REST로 최신 snapshot을 조회한다.
- 실시간 버스 API는 현재 탑승 차량 식별·하차 판정에 사용하지 않고, 환승할 다음 버스 ETA·재탐색에만 사용한다.

### 통신

- 포그라운드: 서버가 `recommendedPollSeconds` 제공, 기본 15~30초, 환승 임박·위험 5~10초. GPS progress와 local 하차 판정은 REST polling 주기와 분리한다.
- GPS: ACTIVE Journey 동안 네이티브 요청 주기는 1초로 고정한다. progress 업로드는 기본 15초, `RIDE_BUS && remainingStops <= 2`에서 5초다. OS가 실제 1초 callback을 보장하지는 않으므로 `observedAt`을 기준으로 처리한다.
- 백그라운드: 상시 연결 없이 FCM + 네이티브 위치·heartbeat 세션
- 모바일 heartbeat가 기본 3분의 `guidanceLeaseUntil`을 연장한다. 일반 leg는 만료 뒤 기본 2분 grace까지 신호가 없을 때 Journey를 `EXPIRED`로 종료한다.
- `RIDE_SUBWAY`에서는 예상 leg 종료 + 5분과 leg 시작 + 60분 중 이른 시각을 `subwayOfflineProtectionUntil`로 두어 터널의 GPS·callback 공백을 보호한다.
- 앱 resume·push tap: 무조건 REST 스냅샷 재조회
- SSE·WebSocket: 비범위

## 4. 도메인·저장 모델

### 4.1 `JourneySession`

```text
id(identity bigint), userId
status(ACTIVE|COMPLETED|CANCELED|EXPIRED)
commandVersion, evaluationVersion, routeRevision, currentLegIndex
startedAt, lastHeartbeatAt, guidanceLeaseUntil, subwayOfflineProtectionUntil
nextEvaluationAt, endedAt, endReason
routeSnapshot(JSONB), destinationSummary(JSONB)
```

- 내부 `userId`당 ACTIVE Journey는 하나만 허용한다. DB partial unique index로 보장한다.
- 새 Journey 시작 시 기존 ACTIVE Journey가 있으면 자동 종료하지 않고 `409 ACTIVE_JOURNEY_EXISTS`와 현재 스냅샷을 반환한다. 사용자의 명시적 교체 확인 후에만 supersede한다.
- 같은 사용자가 어디서 로그인하든 일반 lease 또는 지하철 보호 lease가 유효한 ACTIVE Journey를 조회·조작할 수 있다. 안내 실행 기기를 구분하지 않는다.
- `routeSnapshot`은 Journey 시작 시점의 선택 경로를 불변 저장한다. 재탐색은 기존 JSON을 덮어쓰지 않고 revision을 추가한다.
- 앱 내 종료는 즉시 `CANCELED`다. 만료 기준 시각은 `max(guidanceLeaseUntil + expirationGrace, subwayOfflineProtectionUntil)`이며, 이 시각까지 신호가 없으면 원인과 관계없이 `EXPIRED/CLIENT_LEASE_EXPIRED`로 종료하고 평가·pending 알림을 중단한다. 일반 leg의 지하철 보호 시각은 `null`이다.

### 4.2 `JourneyLeg`

```text
id, journeyId, routeRevision, legIndex
type(WALK|WAIT_BUS|RIDE_BUS|WAIT_SUBWAY|RIDE_SUBWAY|TRANSFER_WALK)
state(PENDING|ACTIVE|CONFIRMATION_REQUIRED|COMPLETED|SKIPPED|UNKNOWN)
start/end stop·station identity, routeId, selectedRouteOptionId
vehicleId(null/미사용), lastProgressId, progressGeometrySegmentIndex, progressFraction, progressObservedAt
progressSectionId(null 가능·버스 보조 식별자)
plannedDuration, predictedEndAt, remainingStops
evidenceSource, evidenceObservedAt, evidenceQuality
```

`vehicleId`는 이미 존재하는 저장 모델의 호환성을 위해 남아 있을 수 있지만 1차 Journey 진행·ETA·하차 판정에서는 채우거나 참조하지 않는다. `CONFIRMATION_REQUIRED`도 승·하차 UI에 사용하지 않고 legacy 호환 값으로만 둔다.

`WAIT_BUS`는 ODsay `subPath.lane`의 모든 항목을 `JourneyLegRouteOption`으로 보존한다. 각 option은 `routeId`, 원천 route ID, 노선명, `directionKey`, 승차·하차 정류장 identity와 route별 `boardingSeq`·`alightingSeq`를 가진다. ODsay 기본 이동시간과 geometry는 paired `RIDE_BUS` leg가 공유한다. PostgreSQL snapshot이 canonical이며 Journey 시작 시 routeOptions를 Redis에도 저장한다. Redis TTL은 Journey 계획 종료시각까지의 남은 시간에 복구 여유를 더해 설정하고, cache miss는 PostgreSQL snapshot에서 복원한다.

사용자에게 차량 선택을 요구하지 않으므로 ODsay lane 순서의 첫 option을 `selectedRouteOptionId`·leg `routeId`로 저장하고 나머지는 대체 후보로 유지한다. 버스 `directionKey`는 상·하행 명칭이 아니라 `boardingSeq>alightingSeq` 진행 순서로 만들며, 지하철은 원천 `wayCode`를 사용한다.

### 4.3 `TransferConnection`

```text
id, journeyId, routeRevision, transferIndex
fromLegIndex, walkLegIndex, toLegIndex
status(COMFORTABLE|CATCHABLE|AT_RISK|MISSED|UNKNOWN)
currentLegArrivalAt, userReadyAt, nextBusArrivalAt
transferSlackSeconds, successProbability
source, confidence, calculatedAt, dataObservedAt, modelVersion
lastNotifiedStatus
```

### 4.4 `JourneyEvent`

```text
eventId(UUID unique), journeyId, baseCommandVersion
routeRevision
type, clientOccurredAt, serverReceivedAt
payload(JSONB), result(APPLIED|DUPLICATE|CONFLICT|REJECTED)
resultingCommandVersion, rejectionReason
```

필수 이벤트:

- `CLIENT_LEG_TRANSITION_DETECTED`
- `REROUTE_ACCEPTED`
- `JOURNEY_CANCELED`
- `JOURNEY_COMPLETED`
- `LEASE_EXPIRED` (server event)

`APP_RESUMED`는 canonical 상태를 바꾸는 JourneyEvent가 아니다. 앱 resume 시 heartbeat로 lease를 연장하고, REST로 최신 snapshot을 조회한 뒤, resume 사실은 best-effort UI telemetry로만 기록한다. 이후 GPS 확인으로 실제 leg 경계 통과가 확인될 때만 별도의 `CLIENT_LEG_TRANSITION_DETECTED` command를 보낸다. 이 resume flow 자체는 `commandVersion`을 증가시키지 않는다.

`eventId`로 중복 재전송을 흡수한다. 같은 `eventId`는 type·version·payload·`clientOccurredAt`까지 같은 요청에만 재사용하며, 다른 요청에 재사용하면 `409 EVENT_ID_REUSED`를 반환한다. `baseCommandVersion`이 다르면 409와 최신 스냅샷을 반환한다. ETA 평가에 따른 `evaluationVersion` 증가는 command 충돌을 만들지 않는다. 이전 `routeRevision`을 가리키는 이벤트는 적용하지 않는다.

heartbeat와 progress는 JourneyEvent가 아니며 `commandVersion`을 증가시키지 않는다. progress는 `progressId`, `geometrySegmentIndex`, `segmentProgress`, `legProgress`, `remainingDistanceMeters`, `remainingDurationSeconds`, 버스의 `sectionId`·`remainingStops`, `observedAt`, `quality`만 수신하고 정밀 GPS 좌표 궤적은 저장하지 않는다. 인증된 사용자의 ACTIVE Journey만 조건부 갱신하고 progress도 heartbeat로 인정한다. progress 전송 간격은 기본 15초, `RIDE_BUS && remainingStops <= 2`에서 5초다. 유효한 progress를 만들지 못하면 최대 30초 heartbeat를 사용하고, iOS background에서는 임의 timer 대신 Core Location callback을 이용한다. PostgreSQL lease write는 Journey별 최대 60초에 한 번으로 합친다. 만료 worker는 `max(guidanceLeaseUntil + expirationGraceSeconds, subwayOfflineProtectionUntil)`이 지난 Journey만 종료하며, grace·보호 시각 안에 도착한 정상 신호는 일반 lease를 다시 연장한다. `RIDE_SUBWAY`가 활성화되면 서버가 정적 geometry ETA로 보호 시각을 설정한다. ETA가 아직 없으면 leg 시작 시각 + ODsay `plannedDuration`을 사용하고, 이후 최대 상한 안에서 progress 기반 ETA로 갱신하며, leg를 벗어나면 제거한다. leg 전이 command와 하차 local notification은 progress 전송 timer와 분리해 즉시 처리한다.

### 4.5 알림

- `user_push_token`: user, platform, provider(FCM), token, environment, enabled, lastSeenAt — token 자체가 unique. 기기 테이블 없이 사용자당 N개 허용
- `journey_notification`: journey, routeRevision, transfer/leg, type, channel, dedupKey, status, scheduledAt, expiresAt, payload, attempts
- 새 `NotificationSender` port와 Firebase Admin SDK 기반 `FcmNotificationSender` 하나를 구현한다. iOS도 FCM token을 사용하며 실제 전달은 APNs를 거친다.
- Journey 알림은 기존 `bus_alert_notification`에 외래키로 연결하지 않고 별도 outbox로 시작한다. relay·claim·retry 패턴만 공유하고 기존 Toss 알림 회귀 위험을 최소화한다.
- 앱이 포그라운드이면 local·FCM 수신을 Journey store에 반영하고 앱 내부 배너만 표시한다. iOS foreground presentation과 Android notification 생성을 억제해 OS 알림과 배너가 동시에 뜨지 않게 한다.
- 앱이 백그라운드·잠금화면이면 local notification 또는 FCM notification+data 메시지를 OS 알림으로 표시한다. 사용자가 누르면 payload 상태를 직접 적용하지 않고 REST로 최신 snapshot을 조회한다.

### 4.6 Version·트랜잭션 경계

- `commandVersion`: 앱이 판정한 leg 전이·재탐색 수락·종료 등 canonical 상태 변경 시 증가하며 `baseCommandVersion` 낙관적 락에 사용한다.
- `evaluationVersion`: ETA·남은 정류장·환승 위험도·next action 갱신 시 증가한다.
- `routeRevision`: 사용자가 재탐색 제안을 수락해 남은 경로를 교체할 때 증가한다.
- command 트랜잭션은 event 중복·version·상태 전이를 검증하고 Journey·Leg·Event·notification outbox를 함께 커밋한다.
- Redis·ODsay·ML·FCM 호출은 DB 트랜잭션 밖에서 실행한다.
- evaluator는 외부 계산 후 짧은 트랜잭션에서 ACTIVE·routeRevision을 다시 확인하고 evaluation snapshot·outbox를 저장한다.

### 4.7 UI 이용행태 telemetry

- 포그라운드 체류 구간, Journey 화면 진입 원인(`DIRECT|RESUME|PUSH`), `alertId`, 환승 의사결정 구간 진입, 후속 사용자 행동을 best-effort로 수집한다.
- 모바일 소유 local checkpoint는 `TRIGGERED|PRESENTED|DEDUP_SUPPRESSED|OPENED`를 수집한다. 각 event는 재시도 동안 유지하는 고유 `telemetryId`와 `routeRevision`, `legIndex`, `checkpoint`, `checkpointOccurrenceId`, `surface(IN_APP_BANNER|OS_NOTIFICATION)`, `triggeredAt`, `occurredAt`을 포함하고 정밀 좌표는 포함하지 않는다.
- 사용자 표시 중복은 채널 없는 `logicalCheckpointKey`로, 채널별 전달 멱등성은 `deliveryKey = logicalCheckpointKey:channel`로 구분한다. `checkpointOccurrenceId`·Journey ID·사용자 ID는 원본 telemetry 상관관계에만 사용하고 metric tag로 사용하지 않는다.
- telemetry는 Journey command·evaluation version을 변경하지 않으며 전송 실패가 여정 진행을 막지 않는다.
- 모바일은 여러 UI event를 묶어 전송하고 서버는 `telemetryId`로 배치 재전송을 dedup한 뒤 저카디널리티 counter·histogram으로 집계한다.
- 정밀 좌표·전체 이동 궤적·push token은 telemetry payload와 metric tag에 포함하지 않는다.

## 5. API 계약

### 5.1 경로 선택 스냅샷

기존 경로 검색 응답에 `searchSessionId`, path별 `pathKey`, `expiresAt`을 추가한다. 서버는 검색 결과를 짧은 TTL Redis 스냅샷으로 저장한다. 클라이언트가 전체 경로 JSON을 수정해 Journey를 생성하지 않도록 한다.

```http
POST /api/journeys
{
  "searchSessionId": "...",
  "pathKey": "..."
}
```

`userId`는 요청 body로 받지 않는다. 서버가 access token에 연결된 사용자로 결정한다.

### 5.2 Journey command·query

```http
GET    /api/journeys/active
GET    /api/journeys/{journeyId}?afterCommandVersion=&afterEvaluationVersion=
GET    /api/journeys/{journeyId}/navigation-geometry?routeRevision=
POST   /api/journeys/{journeyId}/events
POST   /api/journeys/{journeyId}/heartbeat
PUT    /api/journeys/{journeyId}/progress
POST   /api/journeys/{journeyId}/telemetry/batch
POST   /api/journeys/{journeyId}/reroute-proposals
GET    /api/journeys/{journeyId}/reroute-proposals/active
POST   /api/journeys/{journeyId}/reroute-proposals/{proposalId}/accept
POST   /api/journeys/{journeyId}/complete
DELETE /api/journeys/{journeyId}
```

`POST /events`의 1차 모바일 허용 type은 `CLIENT_LEG_TRANSITION_DETECTED`뿐이다. payload는 `eventId`, `baseCommandVersion`, `routeRevision`, `fromLegIndex`, `toLegIndex`, `clientOccurredAt`을 가지며 `fromLegIndex`는 서버 current leg와 같고 `toLegIndex=fromLegIndex+1`이어야 한다. resume은 heartbeat·snapshot·telemetry로 처리하고 종료는 전용 complete/delete API를 사용한다.

Journey 스냅샷 최소 필드:

```json
{
  "journeyId": 1234,
  "commandVersion": 13,
  "evaluationVersion": 41,
  "routeRevision": 2,
  "status": "ACTIVE",
  "guidanceLeaseUntil": "2026-07-22T10:43:01+09:00",
  "subwayOfflineProtectionUntil": "2026-07-22T11:05:00+09:00",
  "serverTime": "2026-07-22T10:40:01+09:00",
  "currentLeg": {},
  "legs": [],
  "alighting": {
    "remainingStops": 3,
    "predictedAlightingAt": "2026-07-22T10:39:30+09:00",
    "checkpoint": "PREPARE_TO_ALIGHT",
    "source": "CLIENT_GPS",
    "quality": "GOOD"
  },
  "activeTransfer": {
    "status": "AT_RISK",
    "userReadyAt": "2026-07-22T10:42:15+09:00",
    "nextBusArrivalAt": "2026-07-22T10:43:30+09:00",
    "transferSlackSeconds": 75,
    "successProbability": 0.64
  },
  "journeyEta": "2026-07-22T11:05:00+09:00",
  "nextAction": {},
  "freshness": {},
  "recommendedPollSeconds": 10
}
```

Journey 시작 후 모바일은 현재 `routeRevision`의 전체 navigation geometry를 한 번 조회해 로컬 저장한다. 응답은 모든 leg의 지도 points와 거리 기반 segment를 포함한다. 서버는 ODsay의 leg 전체 소요시간을 segment 거리 비율로 배분해 `plannedDurationSeconds`를 내려준다. 버스 segment에는 진행·하차 판정에 필요한 `sectionId`·정류장 경계를 추가로 보존한다. 기존 `GET /api/bus/routes/{routeId}/polyline?fromSeq=&toSeq=`의 합쳐진 `points`만으로는 section 진행을 판정하지 않는다. 상행·하행 분류·차량 heading은 응답과 판정에 필요하지 않다.

```json
{
  "journeyId": 1234,
  "routeRevision": 2,
  "bounds": {},
  "legs": [
    {
      "legIndex": 3,
      "type": "RIDE_BUS",
      "routeId": "100100576",
      "plannedDurationSeconds": 1200,
      "totalDistanceMeters": 8400,
      "quality": "OK",
      "points": [],
      "segments": [
        {
          "geometrySegmentIndex": 7,
          "sectionId": "12345",
          "fromSeq": 10,
          "toSeq": 11,
          "distanceMeters": 420,
          "plannedDurationSeconds": 60,
          "quality": "OK",
          "points": []
        }
      ]
    }
  ],
  "sourceFetchedAt": "2026-07-22T00:00:00+09:00"
}
```

전체 geometry의 cache key는 `(journeyId, routeRevision)`이다. 재탐색 수락으로 revision이 바뀌면 새 전체 geometry를 받은 뒤 지도·진행 엔진을 원자적으로 교체하고 이전 revision을 폐기한다. 모든 이동 leg는 `geometrySegmentIndex`·거리·배분 시간을 공통으로 사용하고 버스만 `sectionId`·남은 정류장을 추가로 사용한다. 대기 leg geometry는 지도 표시에는 사용할 수 있지만 대기 ETA 계산에는 사용하지 않는다. `REVIEW|FAILED` section은 응답에서 누락시키지 않고 품질과 이유를 내린다. 앱은 해당 section을 지도에는 표시할 수 있지만 진행 확정의 단독 근거로 쓰지 않고, 다음 `OK` section 재매칭·정류장 geofence로 간격을 넘는다. 하차 section이 불량하면 하차 정류장 geofence와 연속 sample을 함께 만족할 때만 `ALIGHT_NOW`를 발생시킨다.

progress 요청 최소 필드:

```json
{
  "progressId": "uuid",
  "routeRevision": 2,
  "legIndex": 3,
  "geometrySegmentIndex": 7,
  "segmentProgress": 0.62,
  "legProgress": 0.71,
  "sectionId": "12345",
  "remainingDistanceMeters": 1840,
  "remainingDurationSeconds": 420,
  "remainingStops": 3,
  "observedAt": "2026-07-22T10:35:00+09:00",
  "quality": "GOOD"
}
```

`progressId`는 command의 `eventId`와 별개이며 같은 progress 요청을 재전송할 때 유지한다. 서버는 leg별 `lastProgressId`와 `progressObservedAt`만 보존하고 다음 규칙으로 조건부 병합한다.

- 현재 `lastProgressId`와 같은 요청은 no-op이며 `evaluationVersion`을 증가시키지 않는다.
- 새 `progressId`이고 `observedAt`이 현재 값보다 최신일 때만 progress를 갱신하고 `evaluationVersion`을 한 번 증가시킨다.
- 현재보다 오래된 `observedAt`은 stale로 처리해 상태를 바꾸지 않는다.
- 같은 `observedAt`에 다른 `progressId`·내용이 들어오면 `409 PROGRESS_TIMESTAMP_CONFLICT`로 거절한다.

서버는 이전 revision·leg, 선택 leg에 없는 geometry segment·버스 section도 거절한다. GPS 튐을 서버가 복잡하게 재판정하지 않고 모바일이 확정한 최신 progress만 병합한다. leg 전이는 별도 `CLIENT_LEG_TRANSITION_DETECTED` command로 처리한다. 전체 progress 이력이나 정밀 좌표는 저장하지 않는다.

재탐색 제안 최소 필드:

```json
{
  "proposalId": "uuid",
  "proposalType": "ROUTE_OPTION",
  "baseCommandVersion": 13,
  "baseRouteRevision": 2,
  "alternativeRouteOptionId": 987,
  "expiresAt": "2026-07-22T10:43:00+09:00",
  "currentJourneyEta": "2026-07-22T11:05:00+09:00",
  "alternativeJourneyEta": "2026-07-22T10:58:00+09:00",
  "etaGainSeconds": 420,
  "alternativeSummary": {
    "transferCount": 1,
    "walkingSeconds": 240
  }
}
```

### 5.3 Push token

```http
PUT    /api/me/push-token
DELETE /api/me/push-token
```

푸시 token은 access token으로 인증된 사용자에 등록한다. 같은 사용자가 여러 token을 가질 수 있고, provider가 `UNREGISTERED`를 반환하면 비활성화한다.

푸시 페이로드:

```json
{
  "journeyId": 1234,
  "routeRevision": 2,
  "commandVersion": 14,
  "evaluationVersion": 42,
  "alertId": "uuid",
  "type": "TRANSFER_AT_RISK",
  "deepLink": "nextroute://journeys/1234"
}
```

## 6. 환승 평가 설계

### 6.1 현재 leg ETA

- `RIDE_BUS`, `RIDE_SUBWAY`, `WALK`, `TRANSFER_WALK`는 같은 `StaticGeometryProgressEtaCalculator`를 사용한다.
- ODsay가 제공한 leg 전체 소요시간을 각 geometry segment의 거리 비율로 배분한다: `segmentPlannedSeconds = legPlannedSeconds × segmentDistance / legTotalDistance`.
- GPS를 현재 segment에 투영한 뒤 현재 segment의 남은 배분 시간과 이후 segment 배분 시간을 합산한다: `remainingDuration = currentSegmentRemainingSeconds + sum(futureSegmentPlannedSeconds)`.
- 유효한 GPS sample마다 `currentLegArrivalAt = observedAt + remainingDuration`으로 절대 ETA를 다시 고정한다. GPS가 끊겼을 때 `serverNow + 동일 remainingDuration`으로 ETA를 뒤로 밀지 않고 마지막 `currentLegArrivalAt`을 유지해 로컬 countdown을 계속한다.
- 현재 버스 `vehicleId`·실시간 차량 위치, 지하철 실시간 열차·시간표, 개인화 도보 속도는 current leg ETA에 사용하지 않는다.
- 지하철에서 GPS가 불분명하면 마지막 `confirmedProgress`·`currentLegArrivalAt`을 유지하고 품질을 `APPROXIMATE`로 낮춘다. 하차 후 GPS가 복구되어 목적역·환승역 geofence가 확인되면 앱이 progress·leg를 정정한다. 지하철 하차 알림은 보내지 않는다.
- ODsay `plannedDuration`은 이동 소요시간으로만 취급하고 `WAIT_BUS`·`WAIT_SUBWAY` 대기시간으로 사용하지 않는다.

### 6.2 대기 교통수단 ETA

- `WAIT_BUS`는 기존 `SingleTransferPredictor`의 REALTIME → MODEL fallback 규칙을 재사용한다.
- `WAIT_BUS.routeOptions`의 모든 `routeId`를 평가하고 각 routeId마다 최소 2대의 차량 후보를 보존해 T9·R2를 지원한다.
- 평가 tick 내에서 `(stopId, routeId, direction, targetSeq)`를 그룹화해 실시간 조회를 Journey 사이에서도 공유한다.
- `WAIT_SUBWAY`는 `SubwayRealtimeCachePort` snapshot에서 `lineId + 승차역 + 방향 + 목적지`가 일치하는 열차를 도착 초 순으로 조회한다.
- 지하철 실시간 snapshot이 stale·누락되거나 호환 후보가 없으면 정적 ODsay 대기시간을 만들지 않고 `UNKNOWN`을 반환한다.
- Journey 전체 ETA는 실시간 대기 ETA와 이동 leg의 정적 geometry progress ETA를 합성한다.

### 6.3 성공 확률·상태

- `currentLegArrivalAt`, `nextBusArrivalAt`, `transferWalkingTime`의 오차 분포를 결합해 `P(next bus arrival >= userReadyAt)`를 계산한다.
- 모델·소스·예측 horizon 별 검증 residual을 캘리브레이션 아티팩트로 버전 관리한다.
- 초기 `alightingBufferSeconds`는 30초다.
- 초기 slack 상태는 `COMFORTABLE >= 300초`, `CATCHABLE 120~299초`, `AT_RISK 0~119초`, `MISSED < 0초`다. 현재 leg 또는 다음 교통수단 필수 ETA가 누락·stale·매칭 불가이면 `UNKNOWN`이다.
- 캘리브레이션 데이터가 부족하면 probability를 `null`로 두고 유효한 slack 상태는 유지한다. probability는 초기에는 위험 방향 downgrade만 허용하고 낙관 방향 upgrade에는 사용하지 않는다.
- 상태 임계와 hysteresis는 설정으로 두고 운영 데이터로 튀닝한다. 이전 상태 복귀 임계를 더 높게 잡아 flapping을 방지한다.

### 6.4 평가 트리거

- 클라이언트 이벤트 적용 직후
- 환승 버스 실시간 스냅샷 갱신 후 다음 due tick
- leg 전이 직후
- client progress 갱신·leg 전이·환승 임박 시 adaptive tick
- 앱 resume 시 즉시
- 환승 의사결정 구간에서 위험도가 `AT_RISK`·`MISSED`로 하락하거나 선택 경로가 무효화되면 부분 재탐색 필요성 평가
- 유효한 client progress 갱신 시 현재 leg ETA·환승 준비 시각 재계산

매초 전체 Journey를 스캔하지 않는다. 초기 baseline은 `next_evaluation_at` 인덱스 + 배치 `SKIP LOCKED` claim으로 구현한다. 10,000 활성 Journey 부하 검증에서 DB scan·claim이 병목으로 확인되면 Redis ZSET due index를 추가하고 PostgreSQL을 canonical fallback으로 유지한다.

### 6.5 하차 알림 판정

- 앱은 Journey의 `routeId`·정류장 순서와 `bus_route_segment_polyline`을 미리 로드한다.
- 상행·하행·차량 heading은 판별하지 않고, Journey의 section 순서를 이동 방향으로 사용한다.
- 화면 마커는 최신 GPS를 보여줘 일시적으로 튀어도 되지만, Journey 진행은 현재 section·다음 section에서 계산한 `confirmedProgress`를 사용한다.
- 탑승 진입에는 속도 gate를 사용하고, leg 전이·`ALIGHT_NOW`는 단일 GPS sample이 아닌 최소 연속 sample로 확정한다. 전 구간 hysteresis·heading matching·jump score는 구현하지 않는다.
- 확정된 진행위치로 남은 정류장·거리·예상시간을 계산한다.
- 기본 checkpoint는 `PREPARE_TO_ALIGHT`, `ALIGHT_NOW` 두 단계로 시작한다. 3정거장 전·1정거장 전 분리는 운영 계측에서 추가 효용이 확인될 때 설정으로 확장한다.
- 시간 기반·정류장 수 기반 판정이 충돌하면 사용자가 준비할 시간을 더 확보하는 쪽을 선택한다.
- GPS 품질이 낮거나 진행이 불연속이면 화면 마커 표시는 계속할 수 있지만 leg 전이·`ALIGHT_NOW`는 보류한다.
- checkpoint dedup과 local notification은 모바일이 소유한다. 서버는 progress 요약을 ETA·환승 계산에 사용하지만 하차 FCM을 발생시키지 않는다.
- 백그라운드에서는 RN JavaScript timer·background task를 실행 기반으로 신뢰하지 않는다. Swift/Kotlin 네이티브 엔진이 GPS projection·`confirmedProgress`·checkpoint dedup을 처리하고, RN resume 시 결과를 Journey store에 동기화한다.
- 네트워크·FCM·서버 장애는 하차 local notification을 막지 않아야 한다. 사용자의 앱 강제 종료 이후에는 제품 정책에 따라 네이티브 위치 세션과 추가 local notification을 종료한다.

### 6.6 환승 시점 부분 재탐색

재탐색의 시작점은 최초 출발지가 아니라 현재 위치 또는 다음 환승 지점이며, 후보 경로의 평가 기준 시각은 서버가 계산한 `userReadyAt`이다. 완료된 leg는 그대로 보존한다.

후보 평가는 두 단계다.

1. **Route-option 대안**: Journey snapshot/Redis에서 현재 `WAIT_BUS.routeOptions`를 읽고 선택 routeId와 다른 routeId를 먼저 평가한다. 각 routeId의 실시간 도착·ML fallback과 paired ride 기본시간·남은 downstream ETA를 합성한다. 동일 승차·하차 구간이므로 외부 ODsay 경로 검색은 호출하지 않는다.
2. **다른 경로 대안**: 유효하고 `minimumEtaGainSeconds`를 넘는 routeOption이 없을 때만 Redis의 다른 경로 구조 후보를 조회한다. cache miss 또는 후보 무효화 때만 `RemainingRouteSearchService`가 ODsay를 호출한다.

현재 ODsay 경로 검색 포트는 좌표 기반이며 미래 출발 시각을 입력받지 않는다. 따라서 ODsay의 routeOption·다른 경로 구조는 후보 생성에만 사용하고, 버스 대기·승차·환승 ETA는 `userReadyAt` 기준 각 `routeId`의 실시간 데이터와 ML 예측으로 다시 계산한다. 기존 사용자 경로 검색 서비스를 그대로 호출하지 않고, 검색 이력 기록 등 부수효과가 없는 `RemainingRouteSearchService`를 별도로 둔다.

트리거:

- 환승 상태가 `AT_RISK` 또는 `MISSED`로 하락
- 환승할 다음 버스 후보 소실·통과로 기존 환승 후보가 무효화

환승 정류장 도착 또는 설정된 임박 시간 진입은 재탐색 가능 구간을 여는 조건으로만 사용한다. 위험도 하락이나 경로 무효화가 없으면 외부 경로 검색을 호출하지 않는다.

비교 정책:

- 초기 decision window는 `userReadyAt - now <= 600초` 또는 현재 버스 `remainingStops <= 2`다.
- 기존 route revision의 남은 ETA를 같은 실시간 스냅샷으로 다시 계산한다.
- 평가 우선순위는 `다른 routeOption → 다른 경로 구조`다. 다른 경로 검색은 routeOption 후보가 없거나 모두 무효·개선폭 미달일 때만 실행한다.
- routeOption과 다른 경로 모두 목적지 ETA를 최우선으로 비교하고 환승 횟수·도보시간·데이터 신선도를 함께 반환한다.
- 초기 `minimumEtaGainSeconds=300`, `rerouteCooldownSeconds=120`, proposal TTL 120초, 표시 후보 최대 3개다. 모두 설정값으로 관리한다.
- `(journeyId, routeRevision, transferId, searchBucket)` 단위 single-flight와 cooldown으로 반복 외부 검색을 막는다.
- 제안은 `baseCommandVersion`, `baseRouteRevision`, 입력 스냅샷 시각, 만료 시각을 가진다.
- routeOption 제안 수락은 해당 WAIT/RIDE pair의 `selectedRouteOptionId`를 변경하고 route geometry가 달라지므로 `routeRevision`을 증가시킨다. 다른 경로 제안 수락은 남은 leg를 새 revision으로 교체한다. 둘 다 `commandVersion`을 재검증하며 stale proposal은 `409`와 최신 스냅샷을 반환한다.

## 7. 구현 단계

### Phase 0. 계약·baseline 고정

**작업**

- 본 요구사항·계획 문서 review
- Journey OpenAPI 초안과 JSON fixture 작성
- 환승 상태·알림 문구·deep link 코드 고정
- 기존 `TransferArrival` 분기 정확도·ML MAE·알림 outbox latency baseline 보존
- 모바일과 OAuth 로그인→Nextroute token 발급, 내부 `userId` 소유권, `eventId`, `baseCommandVersion`, `evaluationVersion`, `routeRevision` 계약 합의

**통과 기준**

- 성공·stale·conflict·offline·push tap 샘플이 서버·모바일 계약 테스트에서 일치

### Phase 1. Route snapshot·Journey persistence

**작업**

- `route_search_snapshot` Redis port·adapter와 TTL 추가
- 경로 검색 응답에 `searchSessionId/pathKey/expiresAt` 추가
- V35로 `journey_session`, `journey_leg`, `journey_leg_route_option`, `transfer_connection`, `journey_event`, `journey_revision` 생성
- `domain/journey` entity·repository와 `application/journey` 포트 생성
- 선택 path를 JourneyLeg·TransferConnection으로 변환하는 compiler 구현
- 동일 user ACTIVE partial unique 제약, active-exists 409, 명시적 supersede 처리
- `last_heartbeat_at`, `guidance_lease_until`, `subway_offline_protection_until`, `end_reason`과 세 version column 반영
- 최소 Journey start·active API와 사용자 행 잠금 기반 동시성 처리

**검증**

- Flyway·Testcontainers PostgreSQL 스키마 검증
- 경로 snapshot 만료·조작된 pathKey·다중 환승 compile 테스트
- 동시 Journey 시작 시 하나만 ACTIVE임을 통합 테스트

### Phase 2. Journey state machine·API

**작업**

- `JourneyStateMachine`, `JourneyCommandService`, `JourneyQueryService`
- Journey detail snapshot·complete·cancel API와 Phase 1 start·active 응답 확장
- 멱등 Journey event API와 `baseCommandVersion` conflict 409 계약
- `CLIENT_LEG_TRANSITION_DETECTED` 전이, 다중 leg 진행, routeRevision fencing
- 승차·하차·차량 선택 command와 확인 UI 계약은 생성하지 않음
- Journey 시작 snapshot에 ODsay `subPath.lane` 전체 routeOptions와 route별 실시간 조회 identity가 보존되는지 검증
- 스냅샷에 세 version, `guidanceLeaseUntil`, `subwayOfflineProtectionUntil`, `serverTime`, 절대 ETA, freshness, `recommendedPollSeconds` 포함
- 로컬 outbox 재전송을 위한 중복 event 동일 응답

**검증**

- 모든 허용·금지 상태 전이 단위 테스트
- 중복·역순·이전 revision 이벤트 통합 테스트
- 서버 재시작 후 일반 lease 또는 지하철 보호 lease가 유효한 ACTIVE Journey 복원 테스트

### Phase 3. client progress·현재 leg ETA·환승 위험 엔진

**작업**

- `progressId` 재전송 no-op과 `observedAt` 조건부 갱신을 갖는 멱등 progress API, routeRevision·leg·section 소속 검증
- 다음 사용 가능한 Flyway migration으로 `journey_leg.last_progress_id` 추가
- Journey 전체 navigation geometry API와 leg별 points·버스 section별 points·quality·sourceFetchedAt 계약
- 이동 leg 공통 `StaticGeometryProgressEtaCalculator`, CLIENT_GEOMETRY_PROGRESS adapter, WAIT_BUS realtime→ML, WAIT_SUBWAY realtime adapter
- 현재 bus ETA는 client progress 요약 + 구간 소요시간으로 계산하고 `vehicleId`·실시간 차량 위치를 참조하지 않음
- `NextBusCandidatePredictor`로 기존 단일 환승 예측을 2대 후보로 확장
- `TransferReadinessCalculator`: 하차 버퍼 + TMAP 도보 시간 → `userReadyAt`
- `TransferProbabilityEstimator`: 버전된 residual calibration 기반 성공 확률
- `TransferRiskPolicy`: 5단계 + hysteresis + 데이터 품질 가드
- 현재·환승·전체 ETA 합성과 next action 생성

**검증**

- S1·S2·S3·S4·S6 타임라인 fixture
- 교통수단별 거리 비례 시간 배분·현재 segment 부분 진행·버스 realtime→ML 대기·지하철 realtime 대기·대기정보 누락 `UNKNOWN`·GPS 마커 튐·단일 sample leg 전이 차단·REVIEW/FAILED geometry gap·지하철 GPS 공백 `APPROXIMATE`·GPS 복귀 전이·환승 데이터 stale 테스트
- 상태 경계값·hysteresis·probability calibration 회귀 테스트
- 기존 `/api/transfer/predict`·경로 검색 회귀 테스트

### Phase 4. Due evaluator·공유 실시간 평가

**작업**

- `nextEvaluationAt` 배치 claim scheduler·worker 분리
- 환승 평가 tick 내 stop·route·vehicle 조회 dedupe
- 환승 임박·위험에 따른 adaptive interval
- 환승 실시간 데이터 누락·다음 버스 후보 소실 grace·UNKNOWN 정책
- heartbeat API와 소유자 검증, 기본 3분 lease 연장·2분 expiration grace
- `RIDE_SUBWAY` 진입 시 예상 종료 + 5분·leg 시작 + 60분 상한의 `subwayOfflineProtectionUntil` 설정·갱신·해제
- progress는 기본 15초·버스 하차 2정거장 이내 5초로 수신하고 heartbeat를 겸함. progress를 만들지 못할 때 최대 30초 heartbeat, iOS background는 Core Location callback, 서버 lease write는 60초 coalescing 적용
- 만료 Journey claim scheduler와 `EXPIRED/CLIENT_LEASE_EXPIRED` 전이
- lease 만료 시 due task·pending notification 취소
- Journey evaluator 전용 scheduler pool·deadline·bulkhead·feature flag
- 10,000 ACTIVE Journey 실험 하네스

**통과 기준**

- 외부 API 호출이 Journey 수가 아니라 unique 관심 노선·정류장 수에 근접
- 중복 claim 0, 평가 backlog·p95·DB pool 사용량 기록
- 정상 background heartbeat 중 ACTIVE 유지, 일반 leg에서 3분 lease와 2분 grace 미수신 후 평가·알림 중단
- 정상 background callback이 3분 뒤 도착하더라도 2분 grace 안이면 ACTIVE 유지
- `RIDE_SUBWAY`에서 5분 이상 GPS·callback이 없어도 보호 시각 전에는 ACTIVE 유지하고, 보호 시각과 일반 lease가 모두 만료되면 평가·알림 중단
- baseline이 목표를 넘으면 Redis ZSET due index를 추가하고 before/after 보존

### Phase 5. Journey 푸시·알림

Phase 3의 최소 ETA·checkpoint 계약이 고정되면 Phase 4의 10,000 Journey 부하 최적화를 기다리지 않고 token 등록·FCM sender·outbox·deep link 수직 슬라이스를 먼저 완성한다. 전체 사용자 발송은 Phase 4 안정화 이후에만 활성화한다.

**작업**

- 다음 사용 가능한 Flyway 버전으로 `user_push_token`, `journey_notification` 생성
- token register·rotate·disable API
- `NotificationSender` port와 Firebase Admin SDK 기반 단일 `FcmNotificationSender`
- Android·iOS FCM token과 iOS Firebase project의 APNs 인증키 구성
- Journey notification outbox enqueue·claim·reclaim·backoff·dead 처리
- outbox `expiresAt` 기반 FCM TTL과 scope별 collapse key 적용
- foreground에서는 OS notification presentation을 억제하고 앱 내부 배너만 표시하며, background·잠금화면에서는 OS 알림을 표시하도록 payload·클라이언트 handler 분리
- 서버 소유 N2·N4~N8 환승·재탐색 checkpoint policy·hysteresis·expiry
- N1 정류장 접근·N3 버스 하차 checkpoint·local notification은 모바일 소유임을 API·FCM 계약에서 분리
- 발송 직전 Journey ACTIVE·lease·routeRevision·세 version 재검증
- invalid/unregistered token 영구 실패 분류·비활성화
- push 선택 시 `journeyId + alertId`로 최신 snapshot을 조회하고 대상 leg·transfer의 next action을 표시
- push 선택 telemetry와 alert 이후 GPS leg 전이·재탐색 수락까지의 지연 집계

**검증**

- 같은 서버 checkpoint 중복 enqueue 0, 하차 유형 FCM enqueue 0
- 재탐색 후 이전 revision 알림 발송 0
- transient·permanent·token invalid·provider timeout 통합 테스트
- Android·iOS 실기기에서 foreground는 앱 내부 배너만 1회 표시되고 OS 알림은 0회인지, background·잠금화면은 OS 알림과 deep link가 동작하는지 검증
- lease 만료 이후 신규 enqueue·발송 0 검증
- 오래된 push 선택 시 payload 상태를 적용하지 않고 최신 snapshot으로 수렴

### Phase 6. 환승 시점 부분 재탐색 제안

**작업**

- 다음 사용 가능한 Flyway 버전으로 `reroute_proposal` 생성
- 환승 의사결정 구간 안에서 `AT_RISK`, `MISSED`, 선택 경로 무효화 트리거 판정
- 현재 위치·환승 지점과 `userReadyAt` 기준 남은 경로 검색
- Redis/DB의 현재 `WAIT_BUS.routeOptions`를 우선 조회하고 `(stopId, routeId, directionKey, targetSeq)` 단위 실시간 평가
- 개선 가능한 routeOption이 없을 때만 Redis 다른 경로 후보, cache miss 때만 ODsay 남은 경로 검색
- 좌표 기반 후보 경로를 `userReadyAt` 기준 실시간 ETA로 다시 평가하는 `RemainingRouteSearchService`
- 기존 경로·동일 노선 다음 차량·다른 routeId·다른 경로의 목적지 ETA 비교
- 최소 개선폭·cooldown·single-flight 적용
- `RerouteProposal` 생성·만료·조회·수락
- 수락 시 새 `JourneyRevision` 생성, 완료 leg 보존, 남은 leg 교체
- 수락 transaction에서 `baseCommandVersion`·`routeRevision`·proposal 만료를 재검증
- revision 수락 시 이전 due task·pending notification 취소
- 자동 적용 없이 next action·push로 제안

**검증**

- 도착·임박만으로 외부 검색하지 않는 조건과 위험도 하락·환승 후보 버스 무정차·소실 fixture
- 여러 `subPath.lane` routeId 실시간 평가, routeOption 개선 시 ODsay 호출 0회, routeOption 부적합 시 다른 경로 검색 전환 검증
- 개선폭 미달·동일 대안 반복·만료 proposal·동시 수락 테스트
- 완료 leg 불변 및 남은 leg만 교체되는지 검증
- 재탐색과 하차 알림 경쟁 상황에서 이전 revision 불변식 검증

### Phase 7. 복구·개인정보·필수 운영 계측

**작업**

- ACTIVE Journey bootstrap·due index rebuild·고착 PROCESSING reclaim
- 종료된 Journey·leg·revision·event와 notification outbox·발송 결과 30일 retention job
- 애플리케이션 로그 14일, 식별 가능한 UI telemetry 원본 30일, 비식별 집계 1년 보관 설정
- 계정 삭제 시 API·push 즉시 차단, FCM token 제거, 사용자 연결 Journey·telemetry 24시간 이내 물리 삭제
- 모바일 terminal 상태·로그아웃 시 마지막 GPS sample·geometry·pending event 삭제 계약
- 정밀 좌표 미저장·로그 마스킹·push token 마스킹 검증
- O1·O2·O3·O4·O6·O7·O8·O10~O17 metrics·structured log·dashboard. 서버 push delivery와 모바일 local checkpoint presentation을 별도 metric으로 집계
- SLO 분모 정의: 예상 4xx 제외 API system success, enabled token의 FCM provider 접수, `EXPIRED` 후 10분 이내 same-Journey resume를 premature expiry proxy로 집계
- 다음 사용 가능한 Flyway migration으로 30일 보관할 Journey telemetry 원본과 `telemetryId` unique 제약 추가
- 포그라운드 체류 구간·화면 진입 원인·push 선택·후속 행동과 local checkpoint `TRIGGERED|PRESENTED|DEDUP_SUPPRESSED|OPENED` best-effort batch telemetry 수집·재전송 dedup
- 실제 탑승·환승 결과 feedback으로 ETA·probability 후검증

**통과 기준**

- 서버·Redis 재시작 후 일반 lease 또는 지하철 보호 lease가 유효한 Journey 복원·알림 재개
- `EXPIRED` Journey 비복원과 종료 이후 평가·알림 중단
- push·local을 구분한 중복 알림률, false alert rate, 표시 지연, ETA MAE, 상태 수렴 시간을 확인할 수 있음
- 포그라운드 체류 비율, push·local 선택률, 환승 구간 재진입률, alert-to-action 지연을 확인할 수 있음
- 정밀 좌표·token·전체 route JSON이 로그·metric·telemetry에 노출되지 않고 retention·계정 삭제 job이 기한 안에 완료됨

### Phase 8. 점진 배포

1. `journey.navigation.enabled=false`로 코드·DB만 배포하고 migration·기존 API 회귀를 확인한다.
2. 내부 계정 shadow evaluation을 최소 100 Journey 또는 7일 수행한다. 상태·ETA만 기록하고 알림·제안은 표시하지 않는다.
3. 내부 실사용에서 iOS·Android local 하차 알림·FCM·지하철 보호 lease를 최소 50 Journey 동안 검증한다.
4. 5% canary를 최소 500 Journey 또는 7일 운영한다.
5. 25%로 확대해 최소 1,000 Journey 또는 7일 동안 외부 API 쿼터·DB 부하·SLO를 확인한다.
6. 모든 초기 SLO와 불변식을 통과한 경우에만 전체 활성화한다.

한 건이라도 관측되면 feature flag를 끄는 즉시 중단 기준. local checkpoint는 best-effort telemetry가 서버에 수신된 시점에 관측된 것으로 보며, 오프라인 중 즉시 탐지를 보장하지 않는다:

- 다른 사용자의 Journey 노출
- 이전 routeRevision 알림 발생
- 사용자 수락 없는 routeRevision 교체
- telemetry로 확인된 같은 logical checkpoint의 OS 알림·앱 배너 중복 표시
- 정밀 GPS·access/refresh token·FCM token의 로그·metric 노출
- 기존 경로 검색·버스 알림의 치명적 기능 회귀

10분 이상 지속되면 현재 단계 확대를 중단하는 기준:

- Journey API 5xx 비율 2% 초과
- due evaluator 지연 p95 30초 초과
- FCM provider 접수 성공률 95% 미만
- DB connection pool 사용률 85% 초과
- 외부 API 쿼터 사용률 80% 초과
- 기존 API p95가 baseline보다 20% 이상 악화
- premature Journey expiry 0.5% 이상
- provider API 호출량이 unique 관심 key가 아니라 ACTIVE Journey 수에 비례해 증가

## 8. 필수 불변식

1. Journey의 ACTIVE current leg는 revision당 최대 하나다.
2. `commandVersion`은 canonical 상태 변경마다, `evaluationVersion`은 평가 snapshot 변경마다 단조 증가한다.
3. 이전 routeRevision 이벤트는 현재 상태를 변경하지 못한다.
4. 표시 상 같은 checkpoint 알림은 channel별 최대 한 번이다.
5. 낮은 품질 GPS·stale·unknown 데이터는 leg 전이·알림 checkpoint의 단독 근거가 될 수 없다.
6. 외부 실시간 API 호출은 사용자 수가 아니라 unique route·stop·vehicle 관심에 따라 증가해야 한다.
7. 푸시 누락은 Journey 상태 손실로 이어지지 않아야 한다.
8. 새 경로 제안은 사용자 수락 전에 현재 revision을 덮어쓰지 않는다.
9. 정밀 위치 궤적은 기본 로그·메트릭·Journey event payload에 저장하지 않는다.
10. 부분 재탐색은 완료된 leg를 변경하지 않으며 사용자 수락 전 현재 route revision을 교체하지 않는다.
11. 일반 lease+grace와 지하철 보호 lease가 모두 만료된 Journey는 평가·알림·복원 대상이 아니다.
12. ODsay·ML·FCM 호출은 Journey 상태 변경 DB 트랜잭션 안에서 실행하지 않는다.
13. 현재 버스 leg는 `vehicleId`·운행 회차에 묶이지 않으며 동일 노선 후속 차량 탑승이 Journey를 무효화하지 않는다.
14. 지도의 GPS 마커 튐은 용인하되, 단일 GPS sample은 leg 전이·`ALIGHT_NOW`의 단독 근거가 될 수 없다.
15. 실시간 버스 API 오류가 버스 하차 progress·local notification을 중단시키지 않는다.

## 9. 완료 조건

- [ ] REQUIREMENTS의 S1~S4·S6, J1~J9, B1~B3, T1~T9·T11, E1~E7·E9, A1~A5, N1~N8, G1~G9, R1~R8, C1~C6 통과
- [ ] X1·X2·X3·X4·X5·X10·X11·X12·X13 재현 테스트 통과
- [ ] O1·O2·O3·O4·O6·O7·O8·O10~O17 계측 가능
- [ ] Android·iOS 실기기에서 foreground/background Journey 유지와 FCM push deep link 통과
- [ ] Android·iOS 실기기 background에서 FCM 없이 GPS 기반 버스 하차 local notification 통과
- [ ] RN JavaScript suspend 상태에서 네이티브 위치 callback만으로 하차 local notification 통과
- [ ] RN resume 시 네이티브 progress·checkpoint가 중복 알림 없이 Journey store로 수렴
- [ ] 동일 노선 후속 차량 시나리오에서 vehicle 선택 없이 progress·ETA 재기준화
- [ ] 일반 leg에서 heartbeat 3분 lease + 2분 grace 미수신 시 `EXPIRED`, 평가·알림 중단, 재실행 비복원
- [ ] `RIDE_SUBWAY` GPS 공백에서는 예상 종료 + 5분·최대 60분 보호가 적용되고 보호 종료 뒤 미수신 시 `EXPIRED`
- [ ] 인위 장애: Redis 재시작, provider timeout, ML down, 푸시 transient/permanent failure, 이벤트 역순·중복
- [ ] 10,000 ACTIVE Journey 부하 결과와 before/after 보고서 보존
- [ ] 기존 경로 검색·버스 알림·환승 예측 회귀 없음
- [ ] feature flag rollback 검증
- [ ] retention·계정 삭제·로그 마스킹 검증과 단계별 rollout SLO gate 통과
