# Journey Navigation 모바일 구현 계획

- 기준 요구사항: [REQUIREMENTS.md](./REQUIREMENTS.md)
- 백엔드 계약: [PLAN.md](./PLAN.md)
- 확정 기술 결정: [TECHNICAL_DECISIONS.md](./TECHNICAL_DECISIONS.md)
- 작성일: 2026-07-27
- 이용 행태 기반 범위 축소 반영일: 2026-07-31
- 선택 노선·GPS 기반 진행 모델 반영일: 2026-08-01
- GPS 표시 튐 수용·상태 판정 단순화 반영일: 2026-08-03
- 네이티브 백그라운드 하차 알림 계약 반영일: 2026-08-03
- 지하철 실시간 ETA·하차 알림 제외 반영일: 2026-08-03
- GPS 1초 수집·progress 15초/5초 업로드 반영일: 2026-08-03
- 전체 선택 경로 지도 표시 반영일: 2026-08-03
- local checkpoint 계측·progress 멱등 계약 반영일: 2026-08-04
- 대상 레포: React Native `nextroute-app`

## 1. 책임 경계

모바일은 사용자 위치 수집, 선택 버스 노선상 진행 판정, 버스 하차 checkpoint·local notification, 화면 표시, 로컬 카운트다운, 백그라운드 실행, heartbeat, FCM 수신을 담당한다. 서버는 canonical Journey 상태·세 version·환승 판정·재탐색·FCM을 담당한다.

- 포그라운드: `recommendedPollSeconds`에 따른 REST polling
- 백그라운드: 네이티브 위치·heartbeat 세션 + FCM
- resume·push tap: 최신 Journey snapshot REST 조회
- resume: heartbeat 전송 → 최신 snapshot 조회 → best-effort telemetry 기록. 이 흐름 자체는 Journey command가 아니며, GPS로 실제 leg 경계를 확인한 경우에만 별도 전이 command 전송
- 오프라인 command: `eventId`를 가진 로컬 outbox에 저장 후 재전송
- 초 단위 남은 시간: 서버의 절대 ETA와 `serverTime`을 기준으로 로컬 계산
- 네이티브 GPS 요청 주기는 ACTIVE Journey 동안 1초. 실제 callback 간격은 OS 정책에 따라 늦어질 수 있으므로 위치와 함께 수신 시각을 사용
- progress마다 command `eventId`와 별개인 `progressId`를 만들고 재시도 동안 유지한다. 업로드는 기본 15초, `RIDE_BUS && remainingStops <= 2`이면 5초. 정상 progress 요청이 heartbeat를 겸하고, progress를 만들지 못하면 최대 30초 heartbeat로 3분 `guidanceLeaseUntil` 연장
- `RIDE_SUBWAY` 진입 snapshot의 `subwayOfflineProtectionUntil`을 로컬에 보존한다. GPS callback이 끊겨 heartbeat를 보내지 못해도 보호 시각 전에는 로컬 Journey를 임의 종료하지 않는다.
- 하차 알림: native background location callback에서 GPS progress를 계산해 local notification 발생
- 환승 위험·재탐색 제안: 서버 FCM 수신 후 REST로 최신 snapshot 조회

### 1.1 네이티브 실행 계약

- React Native JavaScript timer·background task는 백그라운드 하차 판정의 실행 기반으로 사용하지 않는다.
- iOS는 Swift `Core Location`, Android는 Kotlin foreground service(`location`)가 GPS 수집·`confirmedProgress` 계산·하차 checkpoint 판정을 소유한다.
- 네이티브 위치 엔진은 하차 조건을 충족하면 RN bridge나 서버 응답을 기다리지 않고 OS local notification을 직접 표시한다.
- RN은 포그라운드 복귀 시 네이티브 저장소의 최신 `confirmedProgress`·발생한 checkpoint·local dedup 상태를 읽어 Journey store와 동기화한다.
- 사용자가 앱을 강제 종료하면 제품 정책에 따라 네이티브 위치 세션과 이후 local notification을 종료한다. 서버 Journey는 일반 lease·grace와 지하철 보호 시각이 모두 만료되면 `EXPIRED` 처리하므로, 지하철 leg에서는 강제 종료 후에도 보호 시각까지 ACTIVE로 남을 수 있다.
- 네트워크·FCM·서버 장애 중에도 네이티브에 geometry와 활성 Journey가 남아 있으면 버스 하차 local notification은 동작해야 한다.

## 2. 화면·상태

### 2.1 여정 시작

- 경로 검색 응답의 `searchSessionId`, `pathKey`로 Journey 시작
- 이미 ACTIVE Journey가 있으면 기존 여정 복원 또는 명시적 교체 선택
- 클라이언트가 전체 route JSON을 Journey 생성 요청으로 보내지 않음

### 2.2 네비게이션 화면

- 기본 Journey 화면은 지도에 전체 선택 경로를 표시하고 현재 위치·현재 leg를 강조
- 현재 leg, 다음 행동, 목적지 ETA, 남은 정류장을 지도와 함께 표시
- 환승 상태와 `transferSlack`
- 버스 하차까지 남은 정류장·시간
- 전체·남은 leg는 요약 목록으로 표시
- 전체 route geometry는 routeRevision 동안 재사용하고 GPS마다 다시 조회·생성하지 않음
- 1초 GPS callback에서는 현재 위치 마커를 갱신하고, leg 전이 시에만 현재 leg 강조 style을 변경
- freshness·`UNKNOWN`·GPS 정확도 저하 상태
- 사용자 조작 버튼은 Journey 취소·완료만 제공

### 2.3 재탐색 제안

- 기존 도착 ETA와 대안 도착 ETA 및 절약 시간 표시
- 같은 승·하차 구간의 다른 버스 노선 제안은 `ROUTE_OPTION`, 다른 환승 구조는 `REMAINING_PATH`로 구분해 표시
- routeOption 제안에는 기존·대안 버스 번호, 각 도착 ETA, 목적지 ETA 차이를 표시
- 환승 횟수·도보시간·제안 만료 표시
- 수락 시 현재 `baseCommandVersion`과 `routeRevision`으로 요청
- `409`이면 최신 snapshot을 조회하고 만료된 제안 UI 제거

## 3. 로컬 Journey store

```text
journeyId, commandVersion, evaluationVersion, routeRevision, status
guidanceLeaseUntil
subwayOfflineProtectionUntil
serverTimeOffset
snapshot
pendingEvents[eventId]
lastDisplayedAlertId
pollingState
foregroundInterval
journeyOpenTrigger
routeGeometryByRevision[routeRevision]
busProgressByLeg[legId]
lastLocalAlightingCheckpointByLeg[legId]
displayLocation
confirmedProgressByLeg[legId]
```

- command 결과는 `commandVersion`, 평가 snapshot은 `evaluationVersion`이 더 높은 경우에만 적용한다.
- push payload를 Journey 상태로 직접 적용하지 않는다.
- push 선택 시 `journeyId + alertId`만 navigation context로 사용하고 최신 snapshot을 조회한 뒤 대상 leg·transfer를 표시한다.
- 이전 routeRevision의 응답·알림·비동기 작업은 폐기한다.
- 모든 이동 leg는 geometry segment의 거리 비율로 배분된 `plannedDurationSeconds`와 GPS segment progress를 이용해 남은 시간을 같은 방식으로 계산한다. 버스만 `sectionId`·남은 정류장 정보를 추가로 사용한다. `WAIT_BUS`·`WAIT_SUBWAY`는 서버의 실시간 대기 ETA를 사용한다.
- 로그아웃 시 위치 세션과 polling을 종료하고 민감한 로컬 상태를 정리한다.
- 서버가 `EXPIRED`로 반환한 Journey와 pending event는 정리하고 복원하지 않는다.

## 4. 구현 단계

### Phase M1. API 계약·store

- Journey API client와 DTO
- 인증 token 연결
- Journey store와 세 version merge policy
- 시작·복원·완료·취소 flow
- resume 시 heartbeat·snapshot refresh·telemetry를 수행하되 `APP_RESUMED` JourneyEvent는 생성하지 않음
- Journey 시작 직후 전체 navigation geometry를 조회해 `routeRevision` 키로 로컬 저장

### Phase M2. 포그라운드 내비게이션

- Naver Map에 전체 선택 경로의 모든 leg polyline 표시
- 현재 leg는 강조 style, 완료·향후 leg는 구분 가능한 비강조 style로 표시
- `displayLocation`의 최신 GPS를 1초 callback에 맞춰 반영하며 일시적 튐을 수용
- GPS callback마다 전체 polyline을 재생성하지 않고 위치 marker만 갱신; leg 전이·routeRevision 변경 시 style·geometry 갱신
- 절대 ETA 기반 로컬 카운트다운
- adaptive polling과 앱 focus 즉시 refresh
- Journey 전체 navigation geometry API에서 모든 leg의 points와 버스 `routeId`·정류장 순서·section별 points·quality 로드
- 각 leg의 ODsay 전체 시간과 segment별 거리 비례 `plannedDurationSeconds`를 로드해 버스·지하철·도보 공통 남은 시간 계산
- 상행·하행·차량 heading 분류 없이 Journey의 section 순서를 이동 방향으로 사용
- `confirmedProgress`는 현재 section·다음 section에서 계산하고, 탑승은 속도 gate, leg 전이·`ALIGHT_NOW`는 최소 연속 sample로만 확정
- leg 경계 전이만 `CLIENT_LEG_TRANSITION_DETECTED`로 전송

### Phase M3. 오프라인·동시성

- UUID `eventId` 로컬 outbox
- 네트워크 복구 시 순서대로 재전송
- duplicate 성공 처리와 `409` 최신 snapshot 수렴
- 사용자 event에 `baseCommandVersion`·`routeRevision` 포함
- routeRevision 변경 시 이전 화면 작업 취소

### Phase M4. 백그라운드·푸시

- Android foreground location service와 필수 상시 알림
- iOS background location mode와 native Core Location callback 기반 progress·heartbeat
- Swift/Kotlin 네이티브 위치 엔진에서 GPS projection·하차 checkpoint·local dedup·local notification을 완료하고 RN JavaScript 실행 여부에 의존하지 않음
- 네이티브 저장소에 최신 `confirmedProgress`·발생 checkpoint를 원자적으로 보존하고 RN resume 시 bridge로 동기화
- Android·iOS FCM token 등록·갱신·비활성화
- 정상 background에서도 네이티브 위치 엔진은 1초 GPS 요청을 유지하되 OS가 제공한 실제 callback 주기로 처리
- progress 요약은 기본 15초, 버스 하차 정류장 2정거장 이내에는 5초로 업로드하고 정상 progress가 heartbeat를 겸함
- leg 전이 command와 하차 local notification은 15초/5초 업로드 timer를 기다리지 않고 네이티브 판정 직후 처리
- background에서 버스 하차 checkpoint를 local notification으로 표시
- background에서 환승 위험·재탐색 제안 FCM 알림 수신
- foreground에서는 local checkpoint·FCM 수신 모두 앱 내부 Journey 배너로만 표시하고 OS notification presentation은 억제
- background·잠금화면에서는 local notification 또는 FCM을 OS 알림으로 표시
- push deep link로 Journey 진입 후 최신 snapshot 조회
- push가 가리킨 version이 오래됐어도 payload 상태를 적용하지 않고 최신 next action으로 이동
- 일반 lease 또는 지하철 보호 lease가 유효한 process 재생성·재실행은 ACTIVE Journey 복원
- `RIDE_SUBWAY`에서는 일반 lease가 지났더라도 `subwayOfflineProtectionUntil`이 유효하면 최신 snapshot으로 ACTIVE Journey 복원
- `EXPIRED` 응답이면 네비게이션 화면·위치·polling·로컬 outbox 종료

### Phase M5. 하차 안내

- `bus_route_segment_polyline`을 section 순서로 로드하고 GPS를 현재 section 이후 범위에서만 투영
- `REVIEW|FAILED` section은 단독 진행 근거로 쓰지 않고 다음 `OK` section 재매칭·정류장 geofence로 gap 처리
- 남은 정류장·남은 시간 표시
- 기본 하차 준비·이번 정류장 두 단계 알림 표시
- 3정거장 전·1정거장 전 세분화는 운영 계측 후 설정으로 확장
- 채널 없는 `logicalCheckpointKey = journeyId:routeRevision:scopeType:legIndex:checkpoint`를 기준으로 OS 알림·앱 배너 중복 표시를 차단하고, `deliveryKey = logicalCheckpointKey:LOCAL`로 local 처리 재시도를 흡수
- GPS 정확도 저하·터널에서도 화면 마커 튐은 허용하되, 단일 sample로 leg 전이·`ALIGHT_NOW`를 확정하지 않음
- 지하철 leg도 ODsay 정적 leg 시간 × GPS geometry progress로 남은 시간을 계산하고, GPS가 끊기면 마지막 절대 ETA countdown과 `APPROXIMATE`·마지막 갱신 시각을 표시
- `WAIT_BUS`는 서버의 실시간 버스→ML ETA, `WAIT_SUBWAY`는 실시간 지하철 도착 ETA를 표시
- 지하철 실시간 도착정보가 없으면 ODsay 정적 대기시간을 표시하지 않고 `UNKNOWN`으로 표시
- 지하철 leg에는 local·FCM 하차 알림을 모두 보내지 않음
- 시간 경과만으로 지하철 leg를 완료하지 않고, 하차 후 GPS가 복구되어 목적역·환승역 geofence가 확인되면 `CLIENT_LEG_TRANSITION_DETECTED` 전송
- GPS가 불분명한 동안에는 지하철 leg를 유지하고 RN resume·GPS 복구 시 최신 snapshot과 위치로 정정

### Phase M6. 부분 재탐색

- 환승 임박만으로 제안을 요청하지 않고 `AT_RISK`·`MISSED` 또는 선택 경로 무효화 시 제안 배너
- 현재 경로와 대안의 ETA·환승·도보 비교
- 같은 구간의 다른 routeOption을 먼저 표시하고, 유효한 option이 없을 때만 다른 경로 후보 표시
- `baseCommandVersion`으로 제안 수락하고 새 routeRevision 적용
- 새 revision 적용 시 지도 polyline·leg·알림 상태 원자적 교체

### Phase M7. 이용행태 계측

- Journey foreground 진입·이탈 구간을 누적해 전체 여정 대비 foreground 체류시간 계산
- 화면 진입 원인을 `DIRECT|RESUME|PUSH`로 구분
- push 선택, 환승 의사결정 구간 화면 진입, 이후 leg 전이·재탐색 수락 시간을 best-effort batch 전송
- local checkpoint마다 `TRIGGERED|PRESENTED|DEDUP_SUPPRESSED|OPENED`를 기록하고 재시도 동안 유지하는 고유 `telemetryId`, `routeRevision`, `legIndex`, `checkpoint`, `checkpointOccurrenceId`, `surface(IN_APP_BANNER|OS_NOTIFICATION)`, `triggeredAt`, `occurredAt`을 best-effort batch 전송
- 전송 실패 시 같은 `telemetryId`로 batch를 재시도해 서버 집계가 표시 중복으로 오판하지 않게 함
- `telemetryId`·`checkpointOccurrenceId`는 telemetry dedup·상관관계에만 사용하며 metric tag로 사용하지 않고, 정밀 좌표는 checkpoint telemetry에도 포함하지 않음
- 네이티브 표시 coordinator가 OS 알림과 앱 배너의 logical checkpoint dedup을 단일 소유하고, telemetry 전송 실패·오프라인 여부와 무관하게 중복 표시를 먼저 차단
- telemetry 실패는 Journey command·polling·알림 처리를 막지 않음
- 정밀 좌표·전체 위치 궤적은 telemetry에 포함하지 않음
- `COMPLETED|CANCELED|EXPIRED` 또는 로그아웃 시 마지막 GPS sample·route geometry·pending event·local alert dedup 상태 삭제
- 계정 삭제 시 FCM token 삭제 API를 우선 호출하고 로컬 Journey·인증 상태를 즉시 제거

## 5. 모바일 완료 조건

- Android·iOS에서 포그라운드 polling과 로컬 카운트다운 동작
- Journey 시작 후 전체 선택 경로 geometry가 로컬 저장되고 지도에 모든 leg가 표시됨
- 현재 위치는 1초 GPS stream으로 갱신되고 현재 leg만 강조되며 전체 polyline은 매 sample 재생성되지 않음
- Android·iOS FCM 실기기에서 foreground는 앱 내부 배너만 표시되고 OS 알림은 표시되지 않음
- Android·iOS FCM 실기기에서 background·잠금화면은 OS 알림과 push deep link가 동작함
- Android·iOS 실기기 background에서 FCM 없이도 GPS 기반 버스 하차 local notification 수신
- RN JavaScript가 suspend된 상태에서도 네이티브 위치 callback만으로 하차 local notification 수신
- RN resume 시 네이티브 `confirmedProgress`·checkpoint 이력이 중복 알림 없이 Journey store에 반영
- ACTIVE Journey 동안 GPS 요청 주기가 1초로 설정되고, progress 요청이 평상시 15초·하차 2정거장 이내 5초로 전환됨
- 화면 off·background에서 lease 연장과 push tap Journey 복원
- 정상 background callback이 3분 lease 뒤 도착해도 2분 grace 안이면 Journey 유지
- lease 만료 전 네트워크 복구 시 이벤트가 중복 상태 전이 없이 재전송
- 일반 leg에서 heartbeat 3분 lease와 2분 grace 미수신 후 `EXPIRED` 처리되고 재실행해도 Journey가 복원되지 않음
- `RIDE_SUBWAY`에서 GPS·callback이 5분 이상 끊겨도 서버의 보호 시각 전에는 Journey가 유지되고, 보호 시각 만료 후 미수신이면 `EXPIRED` 처리됨
- 하차 checkpoint가 화면과 알림에서 중복 표시되지 않음
- GPS 마커가 일시적으로 튀어도 단일 sample만으로 leg 전이·`ALIGHT_NOW`가 발생하지 않음
- 상행·하행·차량 heading 분류 없이 Journey section 순서로 진행 방향을 유지
- 동일 노선의 후속 차량 탑승 시 차량 재선택 없이 GPS progress로 계속 진행
- 재탐색 수락 후 이전 revision의 지도·알림·비동기 응답이 노출되지 않음
- 위치 권한 거부·축소·회수 시 GPS 기반 진행·하차 알림을 중단하고 제한 상태를 명시
- 포그라운드 체류 비율·화면 진입 원인·push 및 local checkpoint 선택·alert-to-action telemetry가 중복 없이 집계됨
- local checkpoint의 표시·dedup 억제·선택·표시 지연을 surface별로 확인할 수 있고, 중복 표시 telemetry fixture가 해당 플랫폼 하차 알림 feature flag 중단 신호로 집계됨
- terminal 상태·로그아웃·계정 삭제 후 정밀 위치 sample·geometry·pending event가 로컬에 남지 않음
