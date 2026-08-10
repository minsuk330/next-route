# 대중교통 여정 내비게이션 필수 범위

- 상태: **LOCKED**
- 최초 확정일: 2026-07-22
- 1차 범위 재확정일: 2026-07-27
- 기술 결정 반영일: 2026-07-27
- 이용 행태 기반 범위 축소 반영일: 2026-07-31
- 선택 노선·GPS 기반 진행 모델 반영일: 2026-08-01
- GPS 표시 튐 수용·상태 판정 단순화 반영일: 2026-08-03
- 지하철 실시간 ETA·하차 알림 제외 반영일: 2026-08-03
- GPS 1초 수집·progress 15초/5초 업로드 반영일: 2026-08-03
- 전체 선택 경로 지도 표시 반영일: 2026-08-03
- 교통수단 공통 정적 geometry progress ETA 반영일: 2026-08-03
- local checkpoint 계측·progress 멱등 계약 반영일: 2026-08-04
- 확정 기술 결정: [TECHNICAL_DECISIONS.md](./TECHNICAL_DECISIONS.md)
- 대상: Nextroute 백엔드와 Android·iOS 전용 모바일 클라이언트
- 목적: 구현 계획 작성과 범위 변경 판단의 기준선

## 0. 1차 구현 범위

1차 구현은 다음 여섯 가지로 제한한다.

| 순서 | 기능 | 요구사항 연결 |
|---|---|---|
| 1 | Journey 상태머신 | J1~J9, B1~B3 |
| 2 | 실시간 ETA와 환승 가능성 계산 | T1~T9·T11, E1~E7·E9 |
| 3 | 버스 하차 안내·알림 | A1~A5, N3 |
| 4 | 환승 위험 상승·선택 경로 무효화 시 부분 경로 재탐색과 변경 제안 | R1~R8 |
| 5 | 백그라운드 진행·FCM 여정 알림·lease 기반 종료와 복원 | G1~G9 |
| 6 | 이벤트 멱등성·버전 충돌·알림 중복 방지 | C1~C6 |

운영 계측, 데이터 신선도 판정, 개인정보 보호는 위 기능을 안전하게 제공하기 위한 비기능 요구사항이며 별도 제품 기능으로 범위를 확장하지 않는다.

## 1. 제품 정의

Nextroute 내비게이션은 단순한 경로 검색이 아니라 진행 중인 `Journey`를 추적하는 기능이다.
핵심 차별점은 "버스가 몇 분 후 오는가"가 아니라 다음 질문에 답하는 것이다.

> 현재 교통수단에서 하차하고 환승 지점으로 이동했을 때, 다음 버스를 실제로 탈 수 있는가?

서비스는 사용자 환승 준비 시각과 다음 버스 도착 예측을 같이 갱신하고, 환승 가능성·여유시간·다음 행동을 안내한다.

## 2. 적용 원칙

- 0절의 **여섯 가지 1차 구현 범위**를 필수 범위로 고정한다.
- 모든 필수 기능이 인수 기준을 통과해야 1차 기능을 완료한 것으로 본다.
- 사용자가 선택한 Journey의 버스 `routeId`·정류장 순서를 이용한다고 전제한다. 실제 탑승 `vehicleId`·차량 번호·운행 회차는 식별하지 않는다. ([SCOPE-CHANGES.md](./SCOPE-CHANGES.md) SC-4)
- 동일 노선의 후속 차량을 타더라도 같은 Journey 진행으로 처리한다. 다른 노선·자가용 이용 판별은 1차 범위에서 제외한다.
- 승차·하차·차량 선택 확인 UI는 제공하지 않는다. 버스 leg 진행은 앱이 GPS를 선택 노선의 순서 제약 section polyline에 투영해 판정한다.
- 버스 운영상 상행·하행 분류나 차량 heading을 판별하지 않는다. Journey에 저장된 정류장·section 순서가 이동 방향을 정의한다. ([SCOPE-CHANGES.md](./SCOPE-CHANGES.md) SC-5)
- 지도의 현재 위치 마커가 일시적으로 튀는 것은 수용한다. 시각적 마커 보정과 Journey 진행·하차 판정을 분리하고, 되돌리기 어려운 leg 전이·`ALIGHT_NOW`에만 최소 검증을 적용한다.
- 포그라운드는 REST 적응형 폴링, 백그라운드 알림은 Firebase Admin SDK 기반 FCM 단일 sender를 사용한다. iOS 메시지는 FCM을 거쳐 APNs로 전달한다.
- 정상 background에서는 Journey를 유지하고, 모바일 heartbeat가 기본 3분 lease를 연장한다.
- lease 만료 뒤 기본 2분 grace 동안 늦은 정상 callback의 복구를 허용한다. grace까지 신호가 없으면 원인과 관계없이 Journey를 `EXPIRED`로 종료하고 평가·알림을 중단한다. ([SCOPE-CHANGES.md](./SCOPE-CHANGES.md) SC-3)
- `RIDE_SUBWAY`에서는 GPS·background callback 공백을 정상 상황으로 보고 `subwayOfflineProtectionUntil`까지 만료를 유예한다. 보호 시각은 현재 leg 예상 종료 + 5분과 지하철 leg 시작 + 60분 중 이른 값이며, 일반 만료 시각보다 뒤일 때만 적용한다. ([SCOPE-CHANGES.md](./SCOPE-CHANGES.md) SC-12)
- WebSocket과 SSE는 1차 필수 범위가 아니다. 계측 결과가 도입 임계를 넘을 때만 재검토한다.
- 정밀 위치 궤적은 기본적으로 서버에 영구 저장하지 않는다.
- Journey API는 OAuth 로그인 후 백엔드가 발급한 Nextroute access token 인증을 필수로 한다.
- Journey의 소유자는 OAuth provider와 독립된 내부 `userId`다. Kakao·Google 등의 provider subject는 `auth_identity`로 내부 사용자에 매핑한다.
- access token은 내부 `userId`에 연결한다. Journey API는 `userId`를 클라이언트 입력으로 신뢰하지 않고 인증 정보에서 판별한다.
- 다중 기기는 1차 범위가 아니다. 기기 식별·안내 기기 추적·handoff를 하지 않는다. ([SCOPE-CHANGES.md](./SCOPE-CHANGES.md) SC-1)
- 사용자가 경로를 선택해 Journey를 시작하면 앱은 해당 routeRevision의 전체 navigation geometry를 조회·로컬 저장하고 지도에 전체 선택 경로를 표시한다. 현재 위치와 현재 leg를 시각적으로 강조하며, 나머지 leg도 경로 맥락을 위해 지도에 유지한다. ([SCOPE-CHANGES.md](./SCOPE-CHANGES.md) SC-8)
- 내비게이션이 ACTIVE인 동안 네이티브 위치 엔진의 GPS 요청 주기는 화면·진행 판정 공통 1초로 고정한다. 플랫폼이 백그라운드에서 정확한 1초 callback을 보장하지는 않으므로 실제 수신 시각을 함께 사용한다.
- 서버 progress 업로드는 기본 15초이며, `RIDE_BUS`에서 하차 정류장까지 `remainingStops <= 2`가 되면 5초로 전환한다. leg 전이 command와 버스 하차 local notification은 업로드 주기를 기다리지 않고 즉시 처리한다.

## 3. 필수 여정 범위

| ID | 기능 | 인수 기준 |
|---|---|---|
| S1 | 도보 → 버스 | 도보 도착 시각과 버스 도착 예측을 비교한다. |
| S2 | 버스 → 버스 | 현재 버스 하차 ETA와 다음 버스 ETA를 연결한다. |
| S3 | 지하철 → 버스 | 지하철 하차 ETA와 환승 도보 후 버스 ETA를 연결한다. |
| S4 | 버스 → 지하철 | 버스 하차 후 지하철 승차까지의 여정 진행을 안내한다. |
| S6 | 다중 환승 | 하나의 Journey에서 복수 TransferConnection을 순서대로 평가한다. |

다음 버스가 없는 환승에도 여정 상태 추적은 적용하지만, "환승 버스 탑승 가능성"은 다음 leg가 버스인 S1·S2·S3에 적용한다.

## 4. 필수 기능

### 4.1 Journey 기본 기능

| ID | 기능 |
|---|---|
| J1 | 검색 결과에서 여정 시작 |
| J2 | 여정 종료·취소 |
| J3 | 현재 이동 단계 표시 |
| J4 | 지도에 전체 선택 경로를 표시하고 현재 위치·현재 leg를 강조 |
| J5 | 목적지까지 남은 시간 표시 |
| J6 | 다음 행동 표시 |
| J7 | 남은 정류장·역 표시 |
| J8 | ACTIVE lease 안에서 앱 process 재생성·재실행 시 활성 여정 복원 |
| J9 | 네트워크 단절 후 lease 만료 전 상태 복구 |

### 4.2 버스 leg 진행

| ID | 기능 |
|---|---|
| B1 | 선택 경로의 `routeId`·정류장 순서·section polyline을 버스 leg의 기준으로 사용 |
| B2 | 앱이 선택 Journey의 section 순서로 GPS 진행을 계산하고, 탑승은 속도 gate, leg 전이·하차는 최소 연속 sample로만 확정 |
| B3 | 실제 차량·운행 회차를 식별하지 않고 동일 노선의 후속 차량을 동일 Journey로 처리 |

앱이 서버에 보내는 값은 기본적으로 `geometrySegmentIndex`, segment·leg 진행률, 남은 거리·시간, 버스의 남은 정류장 수, 관측 시각, 품질 등급인 진행 요약이다. 버스 `sectionId`는 section 판정·하차에 필요한 보조 식별자로만 포함한다. 정밀 GPS 좌표 궤적은 영구 저장하지 않는다.

ODsay `subPath.lane`이 여러 개면 응답 순서의 첫 option을 현재 선택 routeOption으로 두고 나머지를 대체 후보로 보존한다. 이는 실제 탑승 차량을 식별한 것이 아니며 사용자에게 차량 선택 입력을 요구하지 않는다.

### 4.3 환승 예측·판단

| ID | 기능 |
|---|---|
| T1 | 이동 leg는 ODsay 정적 시간×GPS geometry progress, `WAIT_BUS`는 실시간 버스→ML fallback, `WAIT_SUBWAY`는 실시간 지하철 도착 조회로 종료 시각 추정 |
| T2 | 하차 후 환승 정류장까지 이동시간 계산 |
| T3 | 사용자의 환승 준비 완료 시각 계산 |
| T4 | 환승할 버스의 도착·출발 시각 예측 |
| T5 | 환승 여유시간 계산 |
| T6 | 환승 성공 가능성 표시 |
| T7 | 환승 상태 5단계 판정 |
| T8 | 환승 위험도 실시간 재평가 |
| T9 | ODsay `subPath.lane`의 다른 `routeId`와 각 노선의 다음 차량들을 함께 비교 |
| T11 | 서두르기·대기·재탐색 행동 추천 |

#### 환승 상태

| 코드 | 사용자 표현 | 의미 |
|---|---|---|
| `COMFORTABLE` | 여유 있음 | `transferSlack >= 300초` |
| `CATCHABLE` | 환승 가능 | `120초 <= transferSlack < 300초` |
| `AT_RISK` | 환승 주의 | `0초 <= transferSlack < 120초` |
| `MISSED` | 환승 실패 예상 | `transferSlack < 0초` |
| `UNKNOWN` | 판단 불가 | 현재 leg 또는 다음 교통수단 ETA가 누락·stale·매칭 불가 |

#### 핵심 계산값

```text
alightingBuffer = 30초
userReadyAt = currentLegArrivalAt + alightingBuffer + transferWalkingTime
transferSlack = nextBusArrivalAt - userReadyAt
```

위 값은 초기 설정값이며 운영 오차 데이터로 조정한다. `successProbability`는 예측 소스·모델 버전·예측 구간의 실제 오차 분포로 보정한다. 캘리브레이션 근거가 부족하면 확률만 `null`로 숨기고, 필수 ETA 근거가 유효하면 slack 기반 상태는 유지한다. 초기 확률 정책은 위험 방향 downgrade만 허용하고 slack 상태를 낙관적으로 upgrade하지 않는다.

### 4.4 ETA·실시간 정보

| ID | 기능 |
|---|---|
| E1 | 목적지 도착 ETA |
| E2 | 현재 leg 종료 ETA |
| E3 | 환승 정류장 도착 ETA |
| E4 | 환승 버스 도착 ETA |
| E5 | 클라이언트의 남은 시간 카운트다운 |
| E6 | 실시간 데이터 갱신 시 ETA 보정 |
| E7 | ML 버스 지연 예측 반영 |
| E9 | 마지막 갱신 시각·데이터 신선도 표시 |

서버는 절대 ETA를 내려주고, 클라이언트는 서버 시각을 보정한 로컬 카운트다운을 표시한다. 카운트다운을 위해 초당 서버 호출을 하지 않는다.

### 4.5 알림

| ID | 기능 |
|---|---|
| N1 | GPS 기반 탑승·환승 정류장 접근 local 알림 |
| N2 | 환승할 다음 버스 접근 FCM 알림 |
| N3 | 하차 임박 알림 |
| N4 | 환승 방법 안내 |
| N5 | 환승 여유시간 안내 |
| N6 | 환승 위험도 상승 알림 |
| N7 | 환승 실패 예상 알림 |
| N8 | 다음 버스 또는 대체 경로 안내 |

- ETA의 작은 변동은 상시 화면만 조용히 갱신한다.
- 사용자 행동이 필요하거나 환승 상태가 하락할 때만 주의 알림을 보낸다.
- 사용자 표시 중복 방지 키는 채널과 무관한 `logicalCheckpointKey = journeyId:routeRevision:scopeType:scopeId:checkpoint`다. 채널별 발송·처리 멱등 키는 `deliveryKey = logicalCheckpointKey:channel`로 둔다. 환승 알림의 scope는 transfer, 하차 알림의 scope는 leg다.
- N1·N3은 모바일 local notification, N2·N4~N8은 서버 FCM이 소유한다. N2의 실시간 버스 조회는 환승 대상 버스에만 적용한다.
- 앱이 포그라운드일 때는 local·FCM 알림 모두 OS 알림으로 중복 표시하지 않고 앱 내부 배너로만 표시한다. 백그라운드·잠금화면에서는 local notification 또는 FCM을 OS 알림으로 표시한다.
- 푸시 페이로드는 식별자·`commandVersion`·`evaluationVersion`·`routeRevision`·deep link만 담고, 앱이 REST로 최신 상태를 재조회한다.

### 4.6 버스 하차 안내

| ID | 기능 |
|---|---|
| A1 | 사용자 GPS를 선택 노선의 순서 제약 section polyline에 투영해 남은 거리·정류장 수 계산 |
| A2 | 현재 section 진행률과 남은 section 예상시간을 합성해 하차까지 남은 시간 계산 |
| A3 | 기본 체크포인트인 하차 준비·이번 정류장 판정 |
| A4 | `PREPARE`는 남은 시간과 무관하게 확정된 남은 정류장 수가 2 이하일 때 한 번만 발생 |
| A5 | 지도 마커의 일시적 GPS 튐은 허용하되, 단일 튐만으로 leg 전이·`ALIGHT_NOW`를 확정하지 않음 |

버스 하차 체크포인트는 앱의 native 위치 엔진이 판정하고 local notification으로 표시한다. 실시간 버스 API는 하차 판정에 사용하지 않고 환승할 다음 버스 ETA·재탐색에만 사용한다. 하차 알림은 버스 leg에만 제공한다. 버스·지하철·도보의 current leg ETA는 ODsay 정적 leg 시간을 geometry segment 거리 비율로 배분하고 사용자 GPS progress 이후의 남은 배분 시간을 합산해 계산한다. GPS가 끊기면 마지막 `predictedEndAt`을 유지하며 quality를 낮추고, GPS 복구 시 다시 계산한다. 지하철 하차 알림은 제공하지 않으며 목적역·환승역 근처 GPS가 확인되면 앱이 leg 전이를 확정한다.

Journey navigation geometry는 section별 points·`quality`를 보존해야 한다. `REVIEW|FAILED` 구간은 진행 확정의 단독 근거로 쓰지 않고, 다음 정상 section 재매칭·정류장 geofence로 보수적으로 처리한다.

### 4.7 백그라운드

| ID | 기능 |
|---|---|
| G1 | 화면이 꺼져도 여정 진행 |
| G2 | 활성 여정 중 1초 GPS 요청과 progress 15초/하차 2정거장 이내 5초 업로드·heartbeat로 guidance lease 연장 |
| G3 | Android에서 FCM 여정 알림 수신 |
| G4 | iOS에서 FCM을 거쳐 APNs로 전달되는 여정 알림 수신 |
| G5 | ACTIVE lease 동안 앱의 GPS 기반 진행·local 하차 알림과 서버의 환승·재탐색 FCM 유지 |
| G6 | 알림 선택 시 해당 여정으로 이동하고 최신 스냅샷 조회 후 알림 대상 leg·transfer 표시 |
| G7 | 정상 background callback 지연이 발생해도 grace 안에서 ACTIVE Journey 복구 |
| G8 | 포그라운드 수신은 앱 내부 배너만, 백그라운드·잠금화면 수신은 OS 알림으로 표시 |
| G9 | `RIDE_SUBWAY` GPS 공백에서는 예상 leg 종료 + 5분, 최대 leg 시작 + 60분까지 ACTIVE Journey 보호 |

백그라운드 위치 엔진과 버스 하차 local notification은 클라이언트 책임이다. 이 실행 경로는 RN JavaScript timer에 의존하지 않고 iOS Swift `Core Location`·Android Kotlin foreground location service에서 GPS progress 판정과 local notification까지 완료한다. RN은 포그라운드 복귀 시 네이티브 progress·checkpoint를 동기화한다. 서버는 좌표 궤적 대신 앱의 최신 진행 요약을 받아 Journey ETA를 보정하고, 실시간 버스 데이터는 환승 ETA·재탐색에만 사용한다. 환승 위험·재탐색 제안은 Journey notification outbox와 Firebase Admin SDK로 발송한다.

앱 resume은 상태 전이 event가 아니다. heartbeat로 lease를 연장하고 최신 snapshot을 조회하며, resume 사실은 best-effort telemetry로만 기록한다. resume 후 GPS로 실제 leg 경계가 확인된 경우에만 별도 leg 전이 command를 보낸다.

iOS·Android는 사용자의 강제 종료 사실을 서버에 즉시·확실하게 전달하지 않으므로 heartbeat 중단으로 근사한다. 일반 leg는 기본 3분 lease와 2분 grace까지 신호가 없으면 `EXPIRED` 처리한다. `RIDE_SUBWAY`는 `max(guidanceLeaseUntil + expirationGrace, subwayOfflineProtectionUntil)`이 지난 뒤에만 만료한다. 정상 신호가 돌아오면 일반 lease를 다시 연장하고, 지하철 leg를 벗어나면 보호 시각을 제거한다. `EXPIRED` Journey는 재실행 시 복원하지 않는다.

### 4.8 환승 시점 부분 재탐색

| ID | 기능 |
|---|---|
| R1 | 환승 의사결정 구간 진입 여부 판정 |
| R2 | 환승 위험도 상승·버스 놓침 감지 |
| R3 | 현재 경로의 남은 구간 ETA 재계산 |
| R4 | ODsay `subPath.lane`에서 보존한 같은 승·하차 구간의 다른 버스 `routeOptions`를 먼저 실시간 평가 |
| R5 | 유효하고 개선되는 routeOption이 없을 때만 현재 위치 또는 환승 지점 기준의 다른 경로 후보 조회 |
| R6 | 기존 경로·routeOption·다른 경로의 도착 ETA·환승 횟수·도보시간·데이터 신선도 비교 |
| R7 | 설정된 최소 도착시간 개선폭·cooldown·dedup을 통과한 대안만 제안 |
| R8 | 사용자가 제안을 수락할 때만 선택 routeOption 또는 새 `JourneyRevision` 활성화 |

재탐색은 여정 전체를 처음부터 다시 계산하지 않는다. Journey 시작 시 ODsay `subPath.lane`의 모든 버스 후보를 `routeOptions`로 snapshot에 보존한다. 위험·무효화가 발생하면 먼저 같은 승차·하차 정류장을 공유하는 다른 `routeId`와 각 노선의 다음 차량을 실시간 평가한다. 유효하고 최소 개선폭을 넘는 routeOption이 없을 때만 환승 지점에서 목적지까지의 다른 경로 후보를 조회한다. 환승 정류장 도착·임박은 평가 가능 구간에 들어왔다는 조건일 뿐, 그 사실만으로 외부 경로 검색을 호출하지 않는다. 1차에서 서버가 사용자 수락 없이 routeOption이나 선택 경로를 교체하지 않는다.

초기 설정값은 환승 준비시각 10분 이내 또는 현재 버스 하차 2정거장 이내에서 평가를 허용하고, 최소 목적지 ETA 개선폭 5분, 동일 조건 cooldown 2분, 제안 TTL 2분, 사용자 표시 후보 최대 3개로 한다. 모두 운영 설정으로 관리한다.

### 4.9 이벤트·동시성 안전성

| ID | 기능 |
|---|---|
| C1 | 클라이언트 이벤트별 고유 `eventId`를 사용한 멱등 처리 |
| C2 | `baseCommandVersion` 낙관적 락과 충돌 시 최신 스냅샷 반환 |
| C3 | 이전 `routeRevision` 이벤트의 현재 상태 변경 차단 |
| C4 | progress별 고유 `progressId`와 `observedAt` 조건부 병합으로 재전송·역순 요청의 상태 변경 차단 |
| C5 | 채널 없는 logical checkpoint 표시 dedup과 channel별 delivery 멱등성 분리 |
| C6 | `commandVersion`·`evaluationVersion`·`routeRevision` 분리와 앱 재접속 시 서버 상태 수렴 |

## 5. 필수 예외 처리

| ID | 상황 | 필수 동작 |
|---|---|---|
| X1 | 실시간 버스 정보 누락 | 하차 진행은 GPS로 계속하고, 환승 ETA·재탐색만 `UNKNOWN`으로 낮춘다. |
| X2 | 동일 노선의 예정보다 늦은 차량 탑승 | 차량·운행 회차를 묶지 않고 실측 GPS 진행으로 ETA와 leg를 재기준화한다. |
| X3 | GPS 정확도 저하·터널·도심 drift | 화면 위치 마커는 최신 GPS를 표시할 수 있지만, 단일 sample은 leg 전이·`ALIGHT_NOW`의 근거로 사용하지 않는다. GPS 복귀 시 현재 section·다음 section에서 재투영한다. |
| X4 | 앱 process 중단 후 재진입 | 일반 lease 또는 지하철 보호 lease가 유효하면 최신 snapshot으로 복원하고, `EXPIRED`면 복원하지 않는다. |
| X5 | 푸시 중복·지연·누락 | 최신 Journey의 세 version을 조회해 복구하고 사용자 표시 중복을 방지한다. |
| X10 | 휴대폰·서버 시간 차이 | 서버 시각과 절대 ETA를 기준으로 표시 시각을 보정한다. |
| X11 | 강제 종료·crash·장시간 네트워크 단절 | 일반 leg는 3분 heartbeat lease와 2분 grace, `RIDE_SUBWAY`는 추가 보호 시각까지 지난 뒤 `CLIENT_LEASE_EXPIRED`로 종료한다. |
| X12 | 정상 background callback 지연 | grace 안에서 신호가 돌아오면 Journey를 종료하지 않고 최신 snapshot으로 수렴한다. |
| X13 | 지하철 터널에서 GPS·callback 장기 공백 | 예상 지하철 leg 종료 + 5분, 최대 leg 시작 + 60분까지 ACTIVE를 유지하고 GPS 복구 시 최신 progress로 수렴한다. |

실시간 버스 정보가 없을 때의 과거 운행기록 자동 폴백은 1차 필수 범위가 아니다. 1차에서 환승 판정은 잘못된 확신보다 명시적 `UNKNOWN`을 우선한다. 버스 진행·하차 안내는 실시간 버스 API 없이 GPS로 계속한다.

## 6. 필수 운영·품질 기능

| ID | 기능 |
|---|---|
| O1 | Journey 상태 전이 기록 |
| O2 | 예측에 사용한 데이터 소스·스냅샷 시각·모델 버전 기록 |
| O3 | 알림 생성·claim·발송·재시도·실패 기록 |
| O4 | 실제 환승 결과와 예측 결과 비교 |
| O6 | 환승 성공 예측 정확도 측정 |
| O7 | 현재 leg·환승 버스·전체 ETA 오차 측정 |
| O8 | 잘못된 알림·중복 알림·지연 알림 측정 |
| O10 | 위치·Journey 데이터 보존·만료·삭제 정책 |
| O11 | 전체 여정 대비 포그라운드 체류 비율 측정 |
| O12 | 화면 진입 경로별 재진입 횟수 측정: 직접·resume·push |
| O13 | 알림 발송 대비 알림 선택률과 선택까지 걸린 시간 측정 |
| O14 | 환승 의사결정 구간의 화면 재진입률 측정 |
| O15 | 알림 이후 GPS로 판정된 leg 전이·재탐색 수락까지 걸린 시간 측정 |
| O16 | Journey API·evaluator·FCM relay·상태 수렴 SLO 측정 |
| O17 | feature flag·shadow·canary와 자동/수동 확대 중단 기준 운영 |

### 낮은 카디널리티 메트릭

- `journey.transition.total{from,to,reason}`
- `journey.evaluation.duration{outcome}`
- `journey.transfer.status{status,source}`
- `journey.state.conflict.total{event_type}`
- `journey.notification.delivery.total{type,channel=PUSH,outcome}`
- `journey.notification.delivery.lateness{type}`
- `journey.notification.open.total{type}`
- `journey.notification.open.delay{type}`
- `journey.checkpoint.presentation.total{checkpoint,surface,outcome}`
- `journey.checkpoint.presentation.delay{checkpoint,surface}`
- `journey.checkpoint.open.total{checkpoint,surface}`
- `journey.checkpoint.open.delay{checkpoint,surface}`
- `journey.ui.foreground.ratio`
- `journey.ui.open.total{trigger,leg_type}`
- `journey.transfer.decision.open.total{risk}`
- `journey.alert.action.delay{type,action}`
- `journey.data.freshness{source,state}`
- `journey.eta.error{target,source}`
- `journey.api.duration{operation,outcome}`
- `journey.evaluator.lag`
- `journey.notification.provider.duration{provider,outcome}`
- `journey.state.convergence.duration{trigger}`
- `journey.expiry.premature.total{leg_type}`

Journey UI 이용행태 telemetry는 canonical Journey 상태 전이에 사용하지 않는 best-effort 신호다. 모바일은 각 event에 재시도 동안 유지하는 고유 `telemetryId`를 부여하고, foreground 구간과 화면 진입 원인·알림 식별자·후속 행동에 더해 local checkpoint의 `TRIGGERED|PRESENTED|DEDUP_SUPPRESSED|OPENED`, `checkpointOccurrenceId`, `surface`, 발생 시각을 batch 전송한다. 서버는 `telemetryId` 중복을 제거한 뒤 이 telemetry를 집계해 O8·O13과 local checkpoint 중복 표시 gate를 계산한다. 정밀 좌표는 포함하지 않으며 Journey ID, 사용자 ID, 노선 ID, `telemetryId`, `checkpointOccurrenceId`, 좌표는 메트릭 태그로 사용하지 않는다.

### 데이터 보관·삭제

| 데이터 | 보관 기준 |
|---|---|
| 정밀 GPS 좌표 | 서버에 저장하지 않음 |
| 모바일 마지막 GPS sample·geometry·pending event | ACTIVE 동안만 보존하고 terminal 상태·로그아웃 시 삭제 |
| 서버 progress | 이력을 누적하지 않고 leg별 최신 요약만 갱신 |
| 종료된 Journey·leg·revision·event | 종료 후 30일 |
| Journey notification outbox·발송 결과 | 30일 |
| 애플리케이션 로그 | 14일 |
| 식별 가능한 UI telemetry 원본 | 30일 |
| 사용자 식별자가 제거된 집계 지표 | 1년 |
| FCM token | 로그아웃·계정 삭제·provider `UNREGISTERED` 시 삭제 또는 비활성화 |

계정 삭제 시 API 접근과 push 발송을 즉시 차단하고 FCM token을 제거한다. 사용자 연결 Journey·telemetry는 삭제 대상으로 표시해 최대 24시간 안에 물리 삭제한다. retention 기간은 운영 설정으로 관리하고 매일 정리 job을 실행한다. 로그에는 access/refresh token·FCM token·정밀 좌표·전체 경로 JSON을 남기지 않는다.

### 초기 SLO

| 대상 | 목표 |
|---|---:|
| Journey command API 성공률 | 99.5% 이상 |
| Journey snapshot API p95 | 500ms 이하 |
| progress 수신 API p95 | 300ms 이하 |
| due evaluator 지연 p95 | 10초 이하 |
| outbox 생성부터 FCM provider 접수 p95 | 5초 이하 |
| FCM provider 접수 성공률 | 99% 이상 |
| 네트워크 복구 후 서버 상태 수렴 p95 | 5초 이하 |
| 이전 routeRevision 사용자 표시 | 0건 |
| 동일 checkpoint 중복 표시 | 0건 |
| 사용자 수락 없는 경로 교체 | 0건 |
| 기존 API p95 회귀 | baseline 대비 10% 이하 |
| premature Journey expiry | 전체의 0.5% 미만 |

FCM의 실제 단말 도착시간은 앱·OS·통신사에 좌우되므로 초기 서버 SLO에서 제외하고 provider 접수시간·성공률과 push open telemetry로 별도 관찰한다. local checkpoint의 중복 방지는 네이티브 저장소의 logical checkpoint 불변식으로 우선 보장하고, 중복 표시 telemetry가 한 건이라도 서버에 도착하면 해당 플랫폼의 하차 알림 feature flag를 끄고 rollout을 중단한다. 오프라인 단말의 telemetry는 지연될 수 있으므로 서버의 즉시 탐지는 보장하지 않는다. ETA MAE·환승 예측 정확도는 shadow evaluation 결과를 baseline으로 고정한 뒤 단계별 회귀 여부를 판단한다.

API 성공률은 유효 요청 중 5xx·timeout이 아닌 응답을 성공으로 계산하며, 의도된 `409 CONFLICT`·검증 4xx는 실패 분자에서 제외한다. FCM 성공률은 발송 시점에 enabled인 유효 token 대상 provider 접수를 기준으로 하고 `UNREGISTERED`는 별도 token 정리 지표로 집계한다. premature expiry는 `EXPIRED` 응답 뒤 10분 안에 같은 Journey의 resume telemetry가 수신된 경우를 운영 proxy로 사용하며, 강제 종료 후 자발적 재실행이 섞일 수 있음을 dashboard에 명시한다.

## 7. 1차 비범위

- 지하철 → 지하철 전용 환승 최적화
- 시외버스·기차·항공
- 실제 버스 `vehicleId`·차량 번호·운행 회차 식별
- 다른 노선 버스·자가용 이용 감지·경고
- 승차·하차·차량 선택 수동 확인 UI
- 지하철 하차 알림·사용자가 실제 탑승한 열차의 확정적 식별
- 사용자별 도보 속도 학습
- 환승 시점과 무관한 상시 전체 대체 노선 비교
- 막차 위험 판단
- 과거 운행기록 기반 예상시간표
- 사용자 확인 없이 경로를 자동 교체하는 기능
- 시뮬레이션·이벤트 리플레이 시스템
- 음성 안내·진동 커스터마이징
- iOS Live Activity·Android promoted Live Update의 고급 UX
- 서버의 직접 APNs provider 연동
- lease 만료 후 Journey 복원
- WebSocket·SSE
- 사용자 위치 크라우드소싱
- 고급 GPS 마커 smoothing·road snapping·상행·하행 분류

## 8. 범위 변경 규칙

이 문서의 항목 추가·제거·우선순위 변경은 구현 계획 변경으로 간주한다. 변경 시 이유, 영향 받는 ID, 인수 기준 변경을 같이 기록한다.
