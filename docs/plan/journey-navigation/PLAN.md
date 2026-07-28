# Journey Navigation 백엔드 구현 계획

- 기준 요구사항: [REQUIREMENTS.md](./REQUIREMENTS.md)
- 확정 기술 결정: [TECHNICAL_DECISIONS.md](./TECHNICAL_DECISIONS.md)
- 최초 작성일: 2026-07-22
- 1차 범위 반영일: 2026-07-27
- 기술 결정 반영일: 2026-07-27
- 대상 레포: Spring Boot 백엔드 `nextroute`
- 모바일 경계: 본 계획은 API·이벤트·푸시 계약까지 포함하고, React Native 화면·네이티브 백그라운드 위치 코드는 별도 `nextroute-app`에서 구현한다.

## 1. 목표

기존 경로 검색·실시간 버스 캐시·ML 도착 예측·알림 outbox 위에 확정된 여섯 가지 기능을 추가한다.

1. 복구 가능한 `JourneySession`
2. Leg·TransferConnection 기반 여정 상태머신
3. 현재 교통수단 ETA와 다음 버스 ETA를 합성한 환승 위험도 및 하차 알림
4. 환승 정류장 도착·임박 시 남은 경로 부분 재탐색과 변경 제안
5. 정상 background에서 유지되고 3분 lease 만료 시 종료되는 FCM 여정 진행
6. 이벤트 멱등성·세 version 충돌·알림 중복 방지

## 2. 현재 재사용 자산

| 자산 | 현재 위치 | Journey에서의 용도 |
|---|---|---|
| 경로 검색·leg DTO | `application/route` | Journey 시작 시 불변 route snapshot 생성 |
| 정류장 구간별 버스 폴리라인 | `application/bus`, `bus_route_segment_polyline` | 현재 버스 leg와 남은 section 지도 표시 |
| 환승 지점 버스 예측 | `TransferArrivalEnricher`, `SingleTransferPredictor` | 다음 버스 ETA·탑승 가능 후보 |
| 버스 실시간 캐시 | `CachedSearchTimeBusAdapter`, Redis bus cache | Journey 평가 시 공유 스냅샷 |
| ML 버스 도착 예측 | `MlFeatureVectorBuilder`, `MlArrivalPredictorPort` | 현재 차량·다음 차량의 target stop ETA |
| 지하철 실시간 스냅샷 | `application/subway`, Redis subway cache | 지하철 leg ETA·데이터 신선도 |
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
  ├─ REST command/event ───────────┐
  ├─ REST adaptive polling ◀────────┐  │
  └─ FCM ◀──── Journey notification │  │
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
- 클라이언트는 사용자 위치 기반 임시 상태를 먼저 표시할 수 있지만, 이벤트 전송 후 서버의 세 version으로 수렴한다.
- Redis는 실시간 스냅샷·due index·공유 관심 노선 인덱스를 담지만 Journey canonical state를 단독 소유하지 않는다.
- 푸시는 상태 자체가 아니라 새 version과 사용자 행동을 알리는 수단이다. push 수신 후 REST로 최신 snapshot을 조회한다.

### 통신

- 포그라운드: 서버가 `recommendedPollSeconds` 제공, 기본 15~30초, 승·하차·환승 임박 5~10초
- 백그라운드: 상시 연결 없이 FCM + 네이티브 위치·heartbeat 세션
- 모바일 heartbeat가 기본 3분의 `guidanceLeaseUntil`을 연장하며, 만료 시 Journey를 `EXPIRED`로 종료한다.
- 앱 resume·push tap: 무조건 REST 스냅샷 재조회
- SSE·WebSocket: 비범위

## 4. 도메인·저장 모델

### 4.1 `JourneySession`

```text
id(UUID), userId
status(ACTIVE|COMPLETED|CANCELED|EXPIRED)
commandVersion, evaluationVersion, routeRevision, currentLegIndex
startedAt, lastHeartbeatAt, guidanceLeaseUntil, nextEvaluationAt, endedAt, endReason
routeSnapshot(JSONB), destinationSummary(JSONB)
```

- 내부 `userId`당 ACTIVE Journey는 하나만 허용한다. DB partial unique index로 보장한다.
- 새 Journey 시작 시 기존 ACTIVE Journey가 있으면 자동 종료하지 않고 `409 ACTIVE_JOURNEY_EXISTS`와 현재 스냅샷을 반환한다. 사용자의 명시적 교체 확인 후에만 supersede한다.
- 같은 사용자가 어디서 로그인하든 lease가 유효한 ACTIVE Journey를 조회·조작할 수 있다. 안내 실행 기기를 구분하지 않는다.
- `routeSnapshot`은 Journey 시작 시점의 선택 경로를 불변 저장한다. 재탐색은 기존 JSON을 덮어쓰지 않고 revision을 추가한다.
- 앱 내 종료는 즉시 `CANCELED`다. heartbeat가 3분 동안 없으면 원인과 관계없이 `EXPIRED/CLIENT_LEASE_EXPIRED`로 종료하고 평가·pending 알림을 중단한다.

### 4.2 `JourneyLeg`

```text
id, journeyId, routeRevision, legIndex
type(WALK|WAIT_BUS|RIDE_BUS|WAIT_SUBWAY|RIDE_SUBWAY|TRANSFER_WALK)
state(PENDING|ACTIVE|CONFIRMATION_REQUIRED|COMPLETED|SKIPPED|UNKNOWN)
start/end stop·station identity, routeId, vehicleId
plannedDuration, predictedEndAt, remainingStops
evidenceSource, evidenceObservedAt, evidenceQuality
```

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

- `USER_CONFIRMED_BOARDING`
- `USER_CONFIRMED_ALIGHTING`
- `USER_SELECTED_VEHICLE`
- `USER_CHANGED_VEHICLE`
- `STOP_PROXIMITY_DETECTED`
- `APP_RESUMED`
- `REROUTE_ACCEPTED`
- `JOURNEY_CANCELED`
- `LEASE_EXPIRED` (server event)

`eventId`로 중복 재전송을 흡수하고 `baseCommandVersion`이 다르면 409와 최신 스냅샷을 반환한다. ETA 평가에 따른 `evaluationVersion` 증가는 command 충돌을 만들지 않는다. 이전 `routeRevision`을 가리키는 이벤트는 적용하지 않는다.

heartbeat는 JourneyEvent가 아니며 `commandVersion`을 증가시키지 않는다. 인증된 사용자의 ACTIVE Journey만 조건부 갱신하고, 위치 이벤트도 heartbeat로 인정한다. foreground·Android foreground service의 기본 전송 간격은 30초다. iOS background는 임의 timer 대신 Core Location callback을 사용한다. PostgreSQL lease write는 Journey별 최대 60초에 한 번으로 합친다.

### 4.5 알림

- `user_push_token`: user, platform, provider(FCM), token, environment, enabled, lastSeenAt — token 자체가 unique. 기기 테이블 없이 사용자당 N개 허용
- `journey_notification`: journey, routeRevision, transfer/leg, type, channel, dedupKey, status, scheduledAt, expiresAt, payload, attempts
- 새 `NotificationSender` port와 Firebase Admin SDK 기반 `FcmNotificationSender` 하나를 구현한다. iOS도 FCM token을 사용하며 실제 전달은 APNs를 거친다.
- Journey 알림은 기존 `bus_alert_notification`에 외래키로 연결하지 않고 별도 outbox로 시작한다. relay·claim·retry 패턴만 공유하고 기존 Toss 알림 회귀 위험을 최소화한다.

### 4.6 Version·트랜잭션 경계

- `commandVersion`: 탑승·하차·leg 전이·종료 등 canonical 상태 변경 시 증가하며 `baseCommandVersion` 낙관적 락에 사용한다.
- `evaluationVersion`: ETA·남은 정류장·환승 위험도·next action 갱신 시 증가한다.
- `routeRevision`: 사용자가 재탐색 제안을 수락해 남은 경로를 교체할 때 증가한다.
- command 트랜잭션은 event 중복·version·상태 전이를 검증하고 Journey·Leg·Event·notification outbox를 함께 커밋한다.
- Redis·ODsay·ML·FCM 호출은 DB 트랜잭션 밖에서 실행한다.
- evaluator는 외부 계산 후 짧은 트랜잭션에서 ACTIVE·routeRevision을 다시 확인하고 evaluation snapshot·outbox를 저장한다.

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
POST   /api/journeys/{journeyId}/events
POST   /api/journeys/{journeyId}/heartbeat
POST   /api/journeys/{journeyId}/reroute-proposals
GET    /api/journeys/{journeyId}/reroute-proposals/active
POST   /api/journeys/{journeyId}/reroute-proposals/{proposalId}/accept
POST   /api/journeys/{journeyId}/complete
DELETE /api/journeys/{journeyId}
```

Journey 스냅샷 최소 필드:

```json
{
  "journeyId": "uuid",
  "commandVersion": 13,
  "evaluationVersion": 41,
  "routeRevision": 2,
  "status": "ACTIVE",
  "guidanceLeaseUntil": "2026-07-22T10:43:01+09:00",
  "serverTime": "2026-07-22T10:40:01+09:00",
  "currentLeg": {},
  "legs": [],
  "alighting": {
    "remainingStops": 3,
    "predictedAlightingAt": "2026-07-22T10:39:30+09:00",
    "checkpoint": "THREE_STOPS_BEFORE"
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

재탐색 제안 최소 필드:

```json
{
  "proposalId": "uuid",
  "baseCommandVersion": 13,
  "baseRouteRevision": 2,
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
  "journeyId": "uuid",
  "routeRevision": 2,
  "commandVersion": 14,
  "evaluationVersion": 42,
  "alertId": "uuid",
  "type": "TRANSFER_AT_RISK",
  "deepLink": "nextroute://journeys/uuid"
}
```

## 6. 환승 평가 설계

### 6.1 현재 leg ETA

- 버스 탑승 중: 선택된 `vehicleId`의 현재 스냅샷과 하차 정류장 `targetSeq`를 기존 ML feature builder에 넣어 하차 ETA를 구한다.
- 지하철 탑승 중: 실시간 열차 스냅샷과 탑승 확인 시각으로 가장 일관된 열차를 매칭한다. 단일 열차로 확정하지 못하면 정적 구간 시간을 사용하고 quality를 `APPROXIMATE`로 내린다.
- 도보 중: TMAP 도보 구간 소요시간과 클라이언트 `STOP_PROXIMITY_DETECTED`를 결합한다. 개인화 도보 속도는 1차에 학습하지 않는다.

### 6.2 다음 버스 ETA

- 기존 `SingleTransferPredictor`의 REALTIME → MODEL fallback 규칙을 재사용한다.
- 첫 번째 버스만 반환하지 않고 최소 2대의 후보를 보존해 T9·R2를 지원한다.
- 평가 tick 내에서 `(stopId, routeId, targetSeq)`를 그룹화해 실시간 조회를 공유한다.

### 6.3 성공 확률·상태

- `currentLegArrivalAt`, `nextBusArrivalAt`, `transferWalkingTime`의 오차 분포를 결합해 `P(next bus arrival >= userReadyAt)`를 계산한다.
- 모델·소스·예측 horizon 별 검증 residual을 캘리브레이션 아티팩트로 버전 관리한다.
- 데이터가 부족하면 probability를 조작해 만들지 않고 `UNKNOWN`을 반환한다.
- 상태 임계와 hysteresis는 설정으로 두고 운영 데이터로 튀닝한다. 이전 상태 복귀 임계를 더 높게 잡아 flapping을 방지한다.

### 6.4 평가 트리거

- 클라이언트 이벤트 적용 직후
- 버스·지하철 실시간 스냅샷 갱신 후 다음 due tick
- leg 전이 직후
- 승·하차·환승 임박 시 adaptive tick
- 앱 resume 시 즉시
- 환승 정류장 도착·임박 또는 환승 위험도 하락 시 부분 재탐색 필요성 평가

매초 전체 Journey를 스캔하지 않는다. 초기 baseline은 `next_evaluation_at` 인덱스 + 배치 `SKIP LOCKED` claim으로 구현한다. 10,000 활성 Journey 부하 검증에서 DB scan·claim이 병목으로 확인되면 Redis ZSET due index를 추가하고 PostgreSQL을 canonical fallback으로 유지한다.

### 6.5 하차 알림 판정

- 현재 차량의 `targetSeq`와 하차 정류장 seq로 남은 정류장 수를 계산한다.
- 현재 차량부터 하차 정류장까지 구간 ETA를 합성해 `predictedAlightingAt`을 계산한다.
- 기본 checkpoint는 `THREE_STOPS_BEFORE`, `ONE_STOP_BEFORE`, `ALIGHT_NOW`로 시작하고 설정으로 조정한다.
- 시간 기반·정류장 수 기반 판정이 충돌하면 사용자가 준비할 시간을 더 확보하는 쪽을 선택한다.
- 차량 실시간 데이터가 stale이면 확정적 `ALIGHT_NOW` 전이를 보류하고 사용자 확인형 안내로 낮춘다.

### 6.6 환승 시점 부분 재탐색

재탐색의 시작점은 최초 출발지가 아니라 현재 위치 또는 다음 환승 지점이며, 후보 경로의 평가 기준 시각은 서버가 계산한 `userReadyAt`이다. 완료된 leg는 그대로 보존한다.

현재 ODsay 경로 검색 포트는 좌표 기반이며 미래 출발 시각을 입력받지 않는다. 따라서 ODsay의 경로 구조는 후보 생성에 사용하고, 후보의 버스 대기·승차·환승 ETA는 `userReadyAt` 기준 실시간 데이터와 ML 예측으로 다시 계산한다. 기존 사용자 경로 검색 서비스를 그대로 호출하지 않고, 검색 이력 기록 등 부수효과가 없는 `RemainingRouteSearchService`를 별도로 둔다.

트리거:

- 환승 정류장 도착 또는 설정된 임박 시간 진입
- 환승 상태가 `AT_RISK` 또는 `MISSED`로 하락
- 선택 차량 소실·통과로 기존 환승 후보가 무효화

비교 정책:

- 기존 route revision의 남은 ETA를 같은 실시간 스냅샷으로 다시 계산한다.
- 대안은 목적지 ETA를 최우선으로 비교하고 환승 횟수·도보시간·데이터 신선도를 함께 반환한다.
- `minimumEtaGainSeconds`보다 빠른 대안만 제안한다.
- `(journeyId, routeRevision, transferId, searchBucket)` 단위 single-flight와 cooldown으로 반복 외부 검색을 막는다.
- 제안은 `baseCommandVersion`, `baseRouteRevision`, 입력 스냅샷 시각, 만료 시각을 가진다.
- 수락 시 `commandVersion`을 재검증하고 새 `JourneyRevision`을 활성화한다. stale proposal은 `409`와 최신 스냅샷을 반환한다.

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
- 다음 사용 가능한 Flyway 버전으로 `journey_session`, `journey_leg`, `transfer_connection`, `journey_event`, `journey_revision`, `reroute_proposal` 생성
- `domain/journey` entity·repository와 `application/journey` 포트 생성
- 선택 path를 JourneyLeg·TransferConnection으로 변환하는 compiler 구현
- 동일 user ACTIVE partial unique 제약, active-exists 409, 명시적 supersede 처리
- `last_heartbeat_at`, `guidance_lease_until`, `end_reason`과 세 version column 반영

**검증**

- Flyway·Testcontainers PostgreSQL 스키마 검증
- 경로 snapshot 만료·조작된 pathKey·다중 환승 compile 테스트
- 동시 Journey 시작 시 하나만 ACTIVE임을 통합 테스트

### Phase 2. Journey state machine·API

**작업**

- `JourneyStateMachine`, `JourneyCommandService`, `JourneyQueryService`
- Journey start·active·snapshot·complete·cancel API
- 멱등 Journey event API와 `baseCommandVersion` conflict 409 계약
- B1·B2·B3 전이, 다중 leg 진행, routeRevision fencing
- 스냅샷에 세 version, `guidanceLeaseUntil`, `serverTime`, 절대 ETA, freshness, `recommendedPollSeconds` 포함
- 로컬 outbox 재전송을 위한 중복 event 동일 응답

**검증**

- 모든 허용·금지 상태 전이 단위 테스트
- 중복·역순·이전 revision 이벤트 통합 테스트
- 서버 재시작 후 lease가 유효한 ACTIVE Journey 복원 테스트

### Phase 3. 현재 leg ETA·환승 위험 엔진

**작업**

- `CurrentLegArrivalPredictor` port와 BUS·SUBWAY·WALK adapter
- 현재 bus `vehicleId → targetSeq` ETA 계산을 기존 ML builder에서 재사용
- `NextBusCandidatePredictor`로 기존 단일 환승 예측을 2대 후보로 확장
- `TransferReadinessCalculator`: 하차 버퍼 + TMAP 도보 시간 → `userReadyAt`
- `TransferProbabilityEstimator`: 버전된 residual calibration 기반 성공 확률
- `TransferRiskPolicy`: 5단계 + hysteresis + 데이터 품질 가드
- 현재·환승·전체 ETA 합성과 next action 생성

**검증**

- S1·S2·S3·S4·S6 타임라인 fixture
- 버스 지연·버스 놓침·GPS 미사용·지하철 approximate·stale 데이터 테스트
- 상태 경계값·hysteresis·probability calibration 회귀 테스트
- 기존 `/api/transfer/predict`·경로 검색 회귀 테스트

### Phase 4. Due evaluator·공유 실시간 평가

**작업**

- `nextEvaluationAt` 배치 claim scheduler·worker 분리
- 평가 tick 내 stop·route·vehicle 조회 dedupe
- 승·하차 임박에 따른 adaptive interval
- 실시간 데이터 누락·차량 사라짐 grace·UNKNOWN 정책
- heartbeat API와 소유자 검증, 기본 3분 lease 연장
- heartbeat는 commandVersion을 변경하지 않고 foreground·Android는 30초 수신, iOS background는 Core Location callback, 서버는 60초 write coalescing 적용
- 만료 Journey claim scheduler와 `EXPIRED/CLIENT_LEASE_EXPIRED` 전이
- lease 만료 시 due task·pending notification 취소
- Journey evaluator 전용 scheduler pool·deadline·bulkhead·feature flag
- 10,000 ACTIVE Journey 실험 하네스

**통과 기준**

- 외부 API 호출이 Journey 수가 아니라 unique 관심 노선·정류장 수에 근접
- 중복 claim 0, 평가 backlog·p95·DB pool 사용량 기록
- 정상 background heartbeat 중 ACTIVE 유지, 3분 미수신 후 평가·알림 중단
- baseline이 목표를 넘으면 Redis ZSET due index를 추가하고 before/after 보존

### Phase 5. Journey 푸시·알림

**작업**

- 다음 사용 가능한 Flyway 버전으로 `user_push_token`, `journey_notification` 생성
- token register·rotate·disable API
- `NotificationSender` port와 Firebase Admin SDK 기반 단일 `FcmNotificationSender`
- Android·iOS FCM token과 iOS Firebase project의 APNs 인증키 구성
- Journey notification outbox enqueue·claim·reclaim·backoff·dead 처리
- outbox `expiresAt` 기반 FCM TTL과 scope별 collapse key 적용
- N1~N8 checkpoint policy·hysteresis·expiry
- 하차 3정거장 전·1정거장 전·도착 임박 checkpoint와 남은 시간 계산
- 발송 직전 Journey ACTIVE·lease·routeRevision·세 version 재검증
- invalid/unregistered token 영구 실패 분류·비활성화

**검증**

- 같은 checkpoint 중복 enqueue 0
- 재탐색 후 이전 revision 알림 발송 0
- transient·permanent·token invalid·provider timeout 통합 테스트
- Android·iOS 실기기 foreground/background FCM 발송·deep link 검증
- lease 만료 이후 신규 enqueue·발송 0 검증

### Phase 6. 환승 시점 부분 재탐색 제안

**작업**

- 환승 정류장 도착·임박, `AT_RISK`, `MISSED` 트리거 판정
- 현재 위치·환승 지점과 `userReadyAt` 기준 남은 경로 검색
- 좌표 기반 후보 경로를 `userReadyAt` 기준 실시간 ETA로 다시 평가하는 `RemainingRouteSearchService`
- 기존 경로·다음 동일 노선 버스·대체 경로의 목적지 ETA 비교
- 최소 개선폭·cooldown·single-flight 적용
- `RerouteProposal` 생성·만료·조회·수락
- 수락 시 새 `JourneyRevision` 생성, 완료 leg 보존, 남은 leg 교체
- 수락 transaction에서 `baseCommandVersion`·`routeRevision`·proposal 만료를 재검증
- revision 수락 시 이전 due task·pending notification 취소
- 자동 적용 없이 next action·push로 제안

**검증**

- 도착·임박·위험도 하락·버스 무정차·선택 차량 소실 fixture
- 개선폭 미달·동일 대안 반복·만료 proposal·동시 수락 테스트
- 완료 leg 불변 및 남은 leg만 교체되는지 검증
- 재탐색과 하차 알림 경쟁 상황에서 이전 revision 불변식 검증

### Phase 7. 복구·개인정보·필수 운영 계측

**작업**

- ACTIVE Journey bootstrap·due index rebuild·고착 PROCESSING reclaim
- Journey·event·notification retention job
- 정밀 좌표 미저장·로그 마스킹·push token 마스킹 검증
- O1~O8·O10 metrics·structured log·dashboard
- 실제 탑승·환승 결과 feedback으로 ETA·probability 후검증

**통과 기준**

- 서버·Redis 재시작 후 유효 lease Journey 복원·알림 재개
- `EXPIRED` Journey 비복원과 종료 이후 평가·알림 중단
- 중복 알림률, false alert rate, ETA MAE, 상태 수렴 시간을 확인할 수 있음

### Phase 8. 점진 배포

1. `journey.navigation.enabled=false` 코드·DB 배포
2. 내부 계정 shadow evaluation: 알림 없이 상태·ETA만 기록
3. 수동 탑승·하차 + 포그라운드 snapshot 활성화
4. 내부 계정 Android·iOS FCM
5. 일부 사용자 canary
6. 실시간 평가·알림 SLO 통과 후 전체 활성화

즉시 중단 기준:

- 이전 routeRevision 알림 발생
- 사용자 표시 중복 알림 발생
- stale 데이터를 신선한 것으로 판정
- 기존 경로 검색·버스 알림 p95 회귀
- provider API 호출이 ACTIVE Journey 수에 비례해 증가

## 8. 필수 불변식

1. Journey의 ACTIVE current leg는 revision당 최대 하나다.
2. `commandVersion`은 canonical 상태 변경마다, `evaluationVersion`은 평가 snapshot 변경마다 단조 증가한다.
3. 이전 routeRevision 이벤트는 현재 상태를 변경하지 못한다.
4. 표시 상 같은 checkpoint 알림은 channel별 최대 한 번이다.
5. stale·unknown 데이터는 자동 확정 상태 전이의 단독 근거가 될 수 없다.
6. 외부 실시간 API 호출은 사용자 수가 아니라 unique route·stop·vehicle 관심에 따라 증가해야 한다.
7. 푸시 누락은 Journey 상태 손실로 이어지지 않아야 한다.
8. 새 경로 제안은 사용자 수락 전에 현재 revision을 덮어쓰지 않는다.
9. 정밀 위치 궤적은 기본 로그·메트릭·Journey event payload에 저장하지 않는다.
10. 부분 재탐색은 완료된 leg를 변경하지 않으며 사용자 수락 전 현재 route revision을 교체하지 않는다.
11. lease가 만료된 Journey는 평가·알림·복원 대상이 아니다.
12. ODsay·ML·FCM 호출은 Journey 상태 변경 DB 트랜잭션 안에서 실행하지 않는다.

## 9. 완료 조건

- [ ] REQUIREMENTS의 S1~S4·S6, J1~J9, B1~B3, T1~T9·T11, E1~E7·E9, A1~A5, N1~N8, G1~G6, R1~R8, C1~C6 통과
- [ ] X1·X2·X3·X4·X5·X10·X11 재현 테스트 통과
- [ ] O1·O2·O3·O4·O6·O7·O8·O10 계측 가능
- [ ] Android·iOS 실기기에서 foreground/background Journey 유지와 FCM push deep link 통과
- [ ] heartbeat 3분 미수신 시 `EXPIRED`, 평가·알림 중단, 재실행 비복원
- [ ] 인위 장애: Redis 재시작, provider timeout, ML down, 푸시 transient/permanent failure, 이벤트 역순·중복
- [ ] 10,000 ACTIVE Journey 부하 결과와 before/after 보고서 보존
- [ ] 기존 경로 검색·버스 알림·환승 예측 회귀 없음
- [ ] feature flag rollback 검증
