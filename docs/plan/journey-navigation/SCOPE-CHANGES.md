# 범위 변경 기록

REQUIREMENTS.md §8 규칙에 따라 LOCKED 요구사항의 변경을 기록한다. 변경마다 이유·영향 ID·인수 기준 변경을 남긴다.

---

## SC-1 — 다중 기기 개념 제거 (2026-07-28)

### 결정

`deviceId` · `activeDeviceId` · 기기 세션 · device handoff 를 **1차 범위에서 제거**한다. Journey 는 `userId` 단위로만 식별하고, 어느 기기가 안내 중인지 추적하지 않는다.

### 이유

사용자가 여러 기기로 하나의 여정을 관리하지 않는다. 기기 추적을 위해 필요한 것들 — 앱 설치 식별자(`installId`), `user_device` 테이블, JWT `did` claim, `@CurrentDevice` resolver, handoff API — 이 전부 쓰이지 않을 기능을 위한 비용이 된다.

### 영향 받는 ID

| 문서 | 항목 | 변경 |
|---|---|---|
| REQUIREMENTS §2 | 적용 원칙 2줄 | `deviceId` 관련 원칙 삭제 |
| REQUIREMENTS §4.9 | **C4** 활성 안내 기기가 아닌 기기의 상태 변경 차단 | **삭제** |
| PLAN §2 | 인증 선행 계약 | 기기 등록·`deviceId` 판별 항목 삭제 |
| PLAN §4.1 | `JourneySession.activeDeviceId`, `startedByDeviceId` | **컬럼 삭제** |
| PLAN §4.1 | 다른 기기 조회·handoff 규칙 2줄 | 삭제 |
| PLAN §4.4 | `JourneyEvent.actorDeviceId` | **컬럼 삭제** |
| PLAN §4.4 | heartbeat "인증된 activeDevice의" | "인증된 사용자의" 로 완화 |
| PLAN §4.5 | `device_push_token` 의 `device` 필드 | `user_push_token(user, token, ...)` 으로 변경 |
| PLAN §5.1 | 최초 `activeDeviceId` 기록 | 삭제 |
| PLAN §5.2 | `POST /api/journeys/{id}/device-handoff` | **엔드포인트 삭제** |
| PLAN §5.3 | `PUT/DELETE /api/me/device/push-token` | `/api/me/push-token` 으로 변경 |
| PLAN §7 Phase 1 | device handoff 처리 | 삭제 |
| PLAN §7 Phase 2 | 비활성 기기 이벤트·handoff 경쟁 테스트 | 삭제 |
| Phase 1 PR | `journey-auth-device` (기기 세션) | **PR 전체 삭제**. Phase 1 은 4개 → 3개 PR |

### 인수 기준 변경

- **삭제**: C4 관련 테스트("비활성 기기의 상태 변경이 거절된다")
- **유지**: C2 `baseCommandVersion` 낙관적 락. 같은 사용자가 두 곳에서 동시에 조작해도 lost update 는 여전히 막는다
- **완화**: 여정 푸시는 사용자의 등록된 모든 FCM token 으로 발송한다. "안내 실행 기기에만 발송"은 더 이상 인수 기준이 아니다

### 수용하는 리스크

| 리스크 | 판단 |
|---|---|
| 사용자가 두 기기에 로그인하면 여정 푸시가 양쪽에 간다 | 수용. 단일 기기 사용 전제 |
| 두 기기가 상충하는 상태 이벤트를 보내면 어느 쪽이 맞는지 서버가 모른다 | 수용. `baseCommandVersion` 충돌(409)로 lost update 만 방어 |
| 기기를 교체하면 이전 폰의 FCM token 이 남는다 | Phase 5 에서 provider 의 `UNREGISTERED` 응답으로 비활성화. 기기 테이블 없이도 처리 가능 |

### 되돌리는 비용

나중에 다중 기기가 필요해지면 `user_device` 테이블 + JWT `did` claim + `activeDeviceId` 컬럼을 추가하는 마이그레이션 한 번이면 된다. 지금 만들어 두는 것과 비교해 구조적 손해가 크지 않다.

---

## SC-2 — 간헐적 확인 이용 행태에 맞춘 1차 범위 축소 (2026-07-31)

### 결정

대중교통 내비게이션을 상시 지도 화면이 아니라 **백그라운드 Journey + 행동 시점 알림 + 필요할 때 상세 확인**으로 제공한다.

1. 당시에는 지도·전체 폴리라인을 사용자가 펼치는 상세 화면으로 내리기로 했다. 이 결정은 SC-8에서 Journey 시작 시 전체 경로를 지도에 기본 표시하는 것으로 교체한다.
2. 당시에는 위치 수집 주기를 leg·임박도에 따라 적응적으로 조정하기로 했다. 이 결정은 SC-7에서 ACTIVE Journey 1초 GPS 요청으로 교체한다.
3. 기본 하차 알림을 `하차 준비`와 `이번 정류장` 두 단계로 줄인다. 3정거장 전·1정거장 전 분리는 운영 계측 후 확장한다.
4. 환승 정류장 도착·임박만으로 외부 재탐색을 호출하지 않는다. `AT_RISK`·`MISSED`, 환승할 다음 버스 후보 소실·통과처럼 기존 경로가 악화되거나 무효화된 경우만 호출한다.

### 이유

공개 승객 조사와 앱 이용 연구에서는 사용자가 출발 전에 경로를 선택한 뒤, 이동 중에는 정류장·환승·하차 등 의사결정 시점에 앱을 다시 확인하는 패턴이 우세했다. 실시간 정보 검색도 정상 운행 중 상시 발생하기보다 지연·운행 장애 등 불확실성이 커질 때 증가했다.

### 영향 받는 항목

| 문서 | 항목 | 변경 |
|---|---|---|
| REQUIREMENTS §0 | 기능 4 | 도착·임박 기반에서 위험·경로 무효화 기반 재탐색으로 축소 |
| REQUIREMENTS §2 | 적용 원칙 | 지도 상세화·leg별 적응형 위치 수집 추가 |
| REQUIREMENTS §4.6 | **A3** | 3단계 하차 checkpoint를 2단계로 축소 |
| REQUIREMENTS §4.7 | **G2** | 상시 고빈도 위치가 아닌 leg별 적응형 위치·heartbeat로 명확화 |
| REQUIREMENTS §4.8 | **R1~R2** | 도착·임박은 eligibility, 위험·무효화는 외부 검색 trigger로 분리 |
| PLAN §2·§6·§7 | 폴리라인·하차·재탐색 | 상세 지도, 2단계 알림, 조건부 검색으로 축소 |
| MOBILE_PLAN §2·§4 | 화면·위치·알림·재탐색 | glance-first UI와 적응형 처리로 축소 |

### 인수 기준 변경

- 지도 기본 표시 여부는 SC-8을 따른다. 전체 polyline은 GPS마다 재생성하지 않고 위치 marker만 갱신한다.
- 위치 수집의 배터리 소비와 section 진행 판정 품질은 계속 계측하되, 요청 주기는 SC-7의 1초 계약을 따른다. 서버 차량 위치 기반 하차 안내는 SC-4에서 폐기했다.
- 기본 하차 알림은 한 leg에서 `PREPARE_TO_ALIGHT`, `ALIGHT_NOW` 각각 최대 한 번이다.
- 환승 정류장 도착·임박만 있고 위험도 하락·경로 무효화가 없으면 외부 경로 검색 호출은 0회다.

### 되돌리는 비용

지도 상시 표시, 추가 하차 checkpoint, 정상 환승 시 선제 재탐색은 모두 설정과 UI 우선순위를 바꾸는 확장이다. Journey 상태·version·revision·outbox 모델은 변경하지 않으므로 되돌리는 구조적 비용은 낮다.

---

## SC-3 — 백그라운드 복원·알림 진입·이용행태 계측 강화 (2026-07-31)

### 결정

간헐적으로 앱을 확인하는 이용 방식에 맞춰 다음 기능을 확대한다.

1. 3분 Journey lease 뒤 2분 grace를 두어 정상적인 iOS·Android background callback 지연이 즉시 영구 종료로 이어지지 않게 한다.
2. push 선택 시 payload 상태를 직접 적용하지 않고 최신 Journey snapshot을 조회한 뒤 알림 대상 leg·transfer의 next action을 표시한다.
3. Phase 3의 최소 ETA·checkpoint가 고정되면 FCM token·sender·outbox·deep link 수직 슬라이스를 Phase 4 부하 최적화와 병행한다.
4. 포그라운드 체류 비율, 화면 진입 원인, push 선택률, 환승 의사결정 구간 재진입률, alert-to-action 지연을 계측한다.

### 이유

백그라운드·잠금화면 이용이 기본이면 push는 단순 메시지가 아니라 Journey로 돌아오는 주 진입점이다. 또한 공개 자료만으로는 화면 상시 사용 비율을 확인하기 어려우므로 실제 서비스의 foreground/background 비율과 알림 반응을 직접 측정해야 한다. 짧은 lease 하나만으로 terminal 상태를 결정하면 정상 background callback 지연을 강제 종료로 오판할 위험도 있다.

### 영향 받는 항목

| 문서 | 항목 | 변경 |
|---|---|---|
| REQUIREMENTS §0·§2·§4.7 | **G1~G7** | grace 복구와 push 대상 context 복원을 필수화 |
| REQUIREMENTS §5 | **X11~X12** | terminal expiration 전에 grace 적용 |
| REQUIREMENTS §6 | **O11~O15** | UI 이용행태·알림 반응 계측 추가 |
| PLAN §3·§4·§7 | lease·push·운영 계측 | grace worker 조건, FCM 조기 수직 슬라이스, telemetry 구현 추가 |
| MOBILE_PLAN §3·§4 | push 복원·telemetry | 최신 snapshot 수렴과 M7 추가 |
| TECHNICAL_DECISIONS §1·§7 | lease·구현 순서 | 3분 lease + 2분 grace 및 FCM 병행 결정 |

### 인수 기준 변경

- 마지막 heartbeat 후 3분이 지났더라도 5분 이전에 정상 신호가 돌아오면 Journey는 `EXPIRED`가 되지 않는다.
- push payload의 version이 stale이어도 앱은 최신 snapshot의 next action을 표시한다.
- UI telemetry 전송 실패가 Journey 상태 전이·polling·push 처리를 실패시키지 않는다.
- 정밀 좌표 없이 foreground ratio·push open rate·transfer decision open rate·alert-to-action delay를 확인할 수 있다.

### 수용하는 리스크

강제 종료 후 서버 평가가 최대 grace 2분 더 유지될 수 있다. 플랫폼이 강제 종료를 확실히 통지하지 않는 제약과 정상 background 오종료 위험 사이에서 이를 수용한다.

---

## SC-4 — 선택 노선·GPS 기반 버스 진행 모델 (2026-08-01)

### 결정

1. 사용자가 선택한 Journey의 버스 `routeId`·정류장 순서를 이용한다고 전제한다.
2. 실제 탑승 `vehicleId`·차량 번호·운행 회차는 식별하지 않는다. 동일 노선의 후속 차량 탑승은 같은 Journey 진행으로 처리한다.
3. 승차·하차·차량 선택 확인 UI를 제거하고, 사용자 조작은 Journey 취소·완료만 남긴다.
4. 앱이 GPS를 순서 제약 `bus_route_segment_polyline`에 투영해 bus progress·남은 거리·정류장·하차 checkpoint를 판정한다.
5. 버스 하차 알림은 native background location + local notification으로 제공한다. 실시간 버스 API는 하차 판정에 사용하지 않고 환승 ETA·재탐색에만 사용한다.
6. 지하철 leg는 하차 알림을 제공하지 않는다. ETA 소스는 SC-6을 거쳐 SC-9에서 교통수단 공통 정적 geometry progress로 최종 변경한다.
7. 다른 노선 버스·자가용 이용 감지는 제품 전제 밖이며 1차 범위에서 제외한다.

### 이유

선택 경로에는 노선 `routeId`는 존재하지만 사용자가 실제로 탄 차량·운행 회차는 앱이 알 수 없다. 이를 입력받으면 Journey 시작 비용이 커지며, 동일 노선의 다음 차량을 탄 경우에도 기하학적 진행 판정에는 필요하지 않다. 따라서 차량 식별 복잡도를 제거하고 앱 GPS를 Journey 진행의 근거로 사용한다.

### 영향 받는 항목

| 문서 | 항목 | 변경 |
|---|---|---|
| REQUIREMENTS §2·§4.2 | **B1~B3** | 수동 확인·차량 선택을 GPS 기반 leg 진행으로 교체 |
| REQUIREMENTS §4.6 | **A1~A5** | 서버 차량 위치 기반을 앱 section projection·local notification으로 교체 |
| REQUIREMENTS §4.7·§5 | **G5, X1~X3** | FCM·실시간 버스·GPS 장애 경계 재정의 |
| PLAN §3~§7 | 소유권·API·ETA·Phase 2~5 | progress API, client leg transition, 하차 local notification으로 변경 |
| MOBILE_PLAN §1~§5 | GPS·백그라운드·하차 | native progress engine과 local dedup 필수화 |
| TECHNICAL_DECISIONS §4~§8 | 상태 전이·데이터 소유권 | 차량 미식별과 app/server 경계 고정 |

### 인수 기준 변경

- 탑승·하차·차량 선택 확인 없이 bus leg가 진행한다.
- 동일 노선의 후속 차량을 타도 `vehicleId` 재선택 없이 ETA·남은 정류장이 실측 progress로 재기준화된다.
- GPS drift·자기교차·왕복 구간에서도 단일 GPS sample만으로 leg가 전이되거나 `ALIGHT_NOW`가 확정되지 않는다.
- 실시간 버스 API 장애 중에도 bus progress·하차 local notification은 계속된다. 환승 ETA·재탐색만 `UNKNOWN`으로 저하된다.
- 지하철 leg에서 하차 알림이 발생하지 않는다.

### 수용하는 리스크

사용자가 실제로 다른 노선·자가용을 이용하면 앱은 이를 정확히 분류하지 않으며 안내가 부정확할 수 있다. 이는 사용자가 선택한 Journey를 따른다는 제품 원칙에 따라 수용한다. GPS 표시와 진행 판정의 구체적인 단순화 기준은 SC-5에서 확정한다.

---

## SC-5 — 상·하행 미분류와 GPS 표시 튐 수용 (2026-08-03)

### 결정

1. 버스 운영상의 상행·하행이나 실제 차량 heading을 판별하지 않는다.
2. 사용자가 선택한 Journey의 정류장·section 순서가 이동 방향을 정의한다.
3. 화면용 `displayLocation`은 최신 GPS를 즉시 반영할 수 있으며 일시적인 마커 튐을 허용한다.
4. Journey 판정용 `confirmedProgress`는 화면 위치와 분리한다. 탑승 진입에는 속도 gate를 사용하고, leg 전이와 `ALIGHT_NOW`에만 최소 연속 sample을 요구한다.
5. 1차 구현에서는 고급 GPS smoothing·road snapping·상행·하행 분류·heading matching·전 구간 jump score를 구현하지 않는다.

### 이유

사용자가 필요한 것은 선택한 경로를 어느 순서로 이동하고 있는지와 다음 행동이다. 상행·하행 명칭이나 실제 차량 방향을 별도로 맞히는 것은 제품 가치가 작고, 화면 마커의 순간적인 튐도 내비게이션 실패로 보지 않는다. 대신 잘못된 leg 전이와 하차 알림처럼 되돌리기 어려운 판단만 최소한으로 보호한다.

### 영향 받는 항목

| 문서 | 항목 | 변경 |
|---|---|---|
| REQUIREMENTS §2·§4.6·§5 | 진행 원칙·A5·X3 | 화면 마커 튐 허용, 비가역 판단만 보호 |
| PLAN §3·§6.5·§11 | 소유권·하차 판정·불변식 | `displayLocation`과 `confirmedProgress` 분리 |
| MOBILE_PLAN §2·Phase M2·M5 | 지도·progress engine | section 순서 기반 최소 판정으로 단순화 |
| TECHNICAL_DECISIONS §4·§7 | 진행 판정·데이터 소유권 | 상·하행·heading·고급 smoothing 제외 |

### 인수 기준 변경

- 지도 마커가 일시적으로 튀어도 Journey 오류로 처리하지 않는다.
- 단일 GPS sample은 leg 전이 또는 `ALIGHT_NOW`를 확정하지 못한다.
- 이동 방향은 선택 Journey의 정류장·section 순서만으로 결정한다.
- 상행·하행·실제 차량 heading 모델이 없어도 버스 내비게이션이 동작한다.

### 수용하는 리스크

지도 마커가 순간적으로 도로 밖이나 이전 위치에 표시될 수 있다. 다만 이 시각적 오차가 leg 전이·하차 알림 같은 비가역 판단으로 전파되지 않도록 화면 위치와 확정 진행도를 분리한다.

---

## SC-6 — 지하철 실시간 ETA와 하차 알림 제외 (2026-08-03)

> 탑승 중 ETA 소스는 SC-9에서 정적 geometry progress로 교체됐다. `WAIT_SUBWAY`의 실시간 지하철 도착 조회와 지하철 하차 알림 제외 결정은 유지한다.

### 결정

1. 지하철 leg ETA는 서버가 보유한 실시간 지하철 API snapshot을 우선 사용한다.
2. `lineId + 승차역 + 방향 + 목적지`로 열차 후보를 좁히고 `trainNo`·현재 위치·도착 초와 남은 정적 구간시간을 합성한다.
3. 이는 사용자가 실제 탑승한 열차를 확정하는 기능이 아니다. 가장 일관된 후보로 ETA를 재기준화하며 후보가 바뀌면 최신 ETA로 수렴한다.
4. snapshot stale·누락·후보 매칭 실패 시 정적 시간표·구간시간으로 fallback하고 quality를 `APPROXIMATE`로 표시한다.
5. 지하철 하차 local notification과 FCM은 모두 제공하지 않는다.
6. 서버의 실시간 ETA만으로 지하철 leg를 자동 완료하지 않는다. 하차 후 GPS가 복구되어 목적역·환승역 근처가 확인되면 앱이 canonical leg 전이를 보낸다.

### 이유

지하철이 포함된 S3·S4에서도 환승 버스 탑승 가능성을 계산하려면 지하철 leg 종료 ETA가 필요하다. 기존 실시간 지하철 snapshot·방향·열차 번호 자산을 재사용하면 정적 시간표만 사용하는 것보다 환승 준비 시각을 더 자주 보정할 수 있다. 반면 지하철 하차 알림은 실제 탑승 열차 확정과 안정적인 백그라운드 진행 판정이 추가로 필요하므로 1차 범위에서 제외한다.

### 인수 기준 변경

- 유효한 실시간 snapshot이 있으면 지하철 ETA의 source는 `REALTIME`이다.
- 실시간 데이터가 stale·누락되거나 후보를 고를 수 없으면 `APPROXIMATE` fallback과 마지막 갱신 시각을 표시한다.
- 실시간 데이터 장애가 Journey 전체를 실패시키지 않는다.
- 지하철 leg에서는 하차 알림이 발생하지 않는다.
- GPS 근거 없이 실시간 ETA만으로 지하철 leg가 완료되지 않는다.
- 하차 후 GPS가 복구되면 앱이 목적역·환승역 geofence를 근거로 leg를 전이한다.

### 수용하는 리스크

사용자가 실제 탑승한 열차를 확정하지 않으므로 급행·행선지 분기·열차 후보 변경 시 ETA가 크게 보정될 수 있다. 후보 근거와 freshness를 노출하고 매칭이 불안정하면 시간표 fallback으로 낮춘다.

---

## SC-7 — GPS 1초 수집과 progress 15초/5초 업로드 (2026-08-03)

### 결정

1. ACTIVE Journey 동안 네이티브 위치 엔진의 GPS 요청 주기는 foreground·background 모두 1초로 고정한다.
2. 화면 `displayLocation`과 Journey `confirmedProgress`는 같은 GPS callback stream을 사용한다.
3. 서버 progress 업로드는 기본 15초다.
4. `RIDE_BUS && remainingStops <= 2`가 되면 progress 업로드를 5초로 전환한다.
5. 정상 progress 요청은 heartbeat를 겸한다. 유효한 progress를 만들지 못한 경우에만 최대 30초 heartbeat를 사용한다.
6. leg 전이 command와 하차 local notification은 progress 업로드 주기와 분리해 판정 직후 처리한다.

### 이유

화면 위치와 로컬 하차 판정은 빠른 GPS 갱신이 필요하지만 모든 sample을 서버로 보낼 필요는 없다. 수집과 업로드를 분리하면 사용자 화면·하차 판정의 반응성을 유지하면서 네트워크·서버 요청량을 제한할 수 있다. 하차 2정거장 이내에만 업로드를 5초로 높여 환승 ETA를 더 자주 보정한다.

### 인수 기준 변경

- ACTIVE Journey에서 네이티브 GPS 요청 간격 설정값이 1초다.
- 평상시 progress 요청 간격은 15초보다 짧아지지 않는다.
- 버스 하차 정류장까지 2정거장 이하이면 다음 progress 요청부터 5초 간격을 사용한다.
- `CLIENT_LEG_TRANSITION_DETECTED`와 local 하차 알림은 15초/5초 timer를 기다리지 않는다.
- 플랫폼 callback 지연은 오류로 간주하지 않고 실제 `observedAt`으로 순서를 판정한다.

### 수용하는 리스크

백그라운드 1초 위치 요청은 배터리 사용량이 크고 OS가 요청 주기를 그대로 보장하지 않을 수 있다. 실기기에서 배터리·발열·callback 간격을 계측하되, 1차 계약의 요청 주기는 1초로 유지한다.

---

## SC-8 — Journey 전체 선택 경로 지도 표시 (2026-08-03)

### 결정

1. 사용자가 경로를 선택해 Journey를 시작하면 앱은 해당 `routeRevision`의 전체 navigation geometry를 조회한다.
2. 전체 geometry를 `(journeyId, routeRevision)` 기준으로 앱 로컬 store에 저장한다.
3. 지도에는 출발지부터 목적지까지 모든 leg를 표시한다.
4. 현재 위치 marker와 현재 leg polyline만 강조하고 완료·향후 leg는 비강조 style로 유지한다.
5. 1초 GPS callback마다 전체 polyline을 재생성하지 않고 위치 marker만 갱신한다. leg 전이 시 style, routeRevision 변경 시 전체 geometry를 갱신한다.
6. 재탐색 수락 시 새 revision 전체 geometry를 받은 후 지도·진행 엔진을 원자적으로 교체한다.

### 이유

사용자가 선택한 경로 전체를 항상 확인할 수 있어야 현재 이동 방향과 남은 경로를 직관적으로 이해할 수 있다. geometry를 Journey 시작 시 한 번 로드하고 재사용하면 전체 경로를 표시하면서도 1초 GPS 갱신마다 polyline을 다시 만드는 비용을 피할 수 있다.

### API 영향

- `GET /api/journeys/{journeyId}/navigation-geometry?routeRevision=`이 모든 leg geometry를 반환한다.
- 각 leg는 지도 표시용 `points`를 가지며, 버스 leg는 진행 판정용 section별 points·quality를 추가로 가진다.
- 응답과 로컬 cache는 `routeRevision`으로 fencing한다.

### 인수 기준 변경

- Journey 시작 직후 전체 geometry가 로컬 store에 저장된다.
- 지도에 모든 선택 leg가 끊김 없이 표시되고 현재 leg가 구분된다.
- GPS sample마다 전체 polyline 객체를 재생성하지 않는다.
- 이전 routeRevision의 geometry가 재탐색 수락 후 노출되지 않는다.

### 수용하는 리스크

전체 경로 geometry 응답과 초기 지도 렌더링 비용이 커진다. 응답을 revision 단위로 캐시하고 polyline 객체를 재사용하며, 매우 긴 경로는 leg 단위 단순화 또는 lazy render를 후속 성능 최적화로 적용한다.

---

## SC-9 — 교통수단 공통 정적 geometry progress ETA (2026-08-03)

### 결정

1. `RIDE_BUS`, `RIDE_SUBWAY`, `WALK`, `TRANSFER_WALK` 이동 leg ETA는 같은 계산기를 사용한다.
2. ODsay가 제공한 leg 전체 소요시간을 geometry segment 거리 비율로 배분한다.
3. 사용자 GPS를 현재 segment에 투영하고 현재 지점 이후에 남은 segment 배분 시간을 합산한다.
4. 유효한 GPS sample마다 `predictedEndAt = observedAt + remainingDuration`을 다시 고정한다.
5. GPS가 끊기면 마지막 `predictedEndAt`을 유지해 countdown하고 quality를 낮춘다. 새 GPS가 들어오면 위치 기준으로 다시 계산한다.
6. 이동 leg ETA에는 현재 버스 실시간 차량 위치, 지하철 실시간 열차·시간표, 개인화 도보 속도를 사용하지 않는다.
7. ODsay `plannedDuration`은 대기시간을 포함하지 않으므로 `WAIT_BUS`는 실시간 버스→ML fallback, `WAIT_SUBWAY`는 실시간 지하철 도착 조회를 사용한다.
8. `WAIT_SUBWAY` 실시간 정보가 누락·stale·매칭 불가이면 정적 대기시간을 만들지 않고 `UNKNOWN`으로 내린다.
9. 실시간 버스 API·ML은 환승할 다음 버스 ETA·`WAIT_BUS`와 재탐색 후보 평가에 사용한다.

### 계산식

```text
segmentPlannedSeconds
= legPlannedSeconds × segmentDistance / legTotalDistance

remainingDuration
= currentSegmentRemainingSeconds
 + sum(futureSegmentPlannedSeconds)

predictedEndAt
= observedAt + remainingDuration
```

### API 영향

- 모든 leg geometry를 거리 기반 segment로 정규화한다.
- 각 segment는 `geometrySegmentIndex`, `distanceMeters`, `plannedDurationSeconds`, `points`, `quality`를 가진다.
- 버스 segment만 `sectionId`·정류장 경계를 추가로 가진다.
- progress는 `geometrySegmentIndex`, `segmentProgress`, `legProgress`, `remainingDistanceMeters`, `remainingDurationSeconds`, `observedAt`, `quality`를 공통 전송한다.

### 인수 기준 변경

- 동일한 leg 시간·geometry·GPS 입력은 교통수단과 무관하게 같은 남은 시간 비율을 반환한다.
- 현재 segment 중간 진행분과 이후 segment 시간이 중복·누락 없이 합산된다.
- GPS 공백 중 절대 ETA가 평가 시각마다 뒤로 밀리지 않는다.
- GPS 복구 시 최신 위치를 기준으로 remaining duration과 ETA가 재기준화된다.
- 실시간 지하철 API 장애가 current leg ETA 계산에 영향을 주지 않는다.
- 실시간 지하철 API 장애 시 `WAIT_SUBWAY`는 정적 대기시간으로 fallback하지 않고 `UNKNOWN`이 된다.

### 수용하는 리스크

거리 비율과 실제 소요시간 비율은 정체·신호·역 정차시간 때문에 다를 수 있다. 1차에서는 일관되고 설명 가능한 계산을 우선하며, 교통수단·segment별 실측 보정은 운영 ETA 오차가 확인된 뒤 확장한다.

---

## SC-10 — 다른 버스 routeOption 우선 재평가 (2026-08-03)

### 결정

1. Journey 시작 시 ODsay `subPath.lane`의 모든 버스 노선을 `JourneyLegRouteOption`으로 보존한다.
2. routeOption은 `routeId`, 원천 ID, 노선명, `directionKey`, 승·하차 정류장 identity, `boardingSeq`, `alightingSeq`를 가진다. ODsay 기본 이동시간은 paired ride leg가 공유한다.
3. PostgreSQL snapshot을 canonical로 하고 Redis에는 Journey TTL 동안 routeOptions 조회 cache를 유지한다.
4. 재탐색 trigger가 발생하면 같은 승·하차 구간의 다른 routeId를 먼저 평가한다.
5. 실시간 조회는 `(stopId, routeId, directionKey, targetSeq)`로 묶고 각 routeId마다 다음 차량을 복수 보존한다.
6. 유효하고 최소 ETA 개선폭을 넘는 routeOption이 없을 때만 Redis의 다른 경로 구조를 평가한다. cache miss 또는 후보 무효화 때만 ODsay 남은 경로 검색을 호출한다.
7. routeOption·다른 경로 모두 사용자 수락 전 자동 적용하지 않는다. routeOption 수락도 geometry가 달라질 수 있으므로 `routeRevision`을 증가시킨다.

### 평가 순서

```text
현재 선택 routeId와 다음 차량들
→ 같은 subPath의 다른 routeOptions와 각 다음 차량들
→ Redis의 다른 경로 구조 후보
→ cache miss일 때 ODsay 남은 경로 검색
```

### 이유

같은 정류장에서 같은 하차 지점까지 가는 다른 버스 노선은 경로 구조를 다시 검색하지 않아도 비교할 수 있다. ODsay가 이미 제공한 lane 구조를 보존하고 routeId별 실시간 도착만 갱신하면 외부 경로 검색 호출량과 제안 지연을 줄일 수 있다.

### 인수 기준 변경

- ODsay lane이 여러 개인 WAIT_BUS는 모든 routeOptions를 순서대로 보존한다.
- 선택 routeId가 지연됐을 때 다른 routeId가 먼저 도착하고 목적지 ETA 개선폭을 넘으면 `ROUTE_OPTION` 제안이 생성된다.
- 유효한 routeOption 제안이 있으면 ODsay 남은 경로 검색 호출은 0회다.
- routeOptions가 없거나 모두 무효·개선폭 미달인 경우에만 `REMAINING_PATH` 평가로 넘어간다.
- 여러 Journey의 동일 `(stopId, routeId, directionKey, targetSeq)` 조회가 한 실시간 호출로 공유된다.

### 수용하는 리스크

ODsay의 lane별 기본 이동시간이 분리되지 않을 수 있어 같은 subPath의 routeOptions는 paired ride leg 기본시간을 공유한다. 실제 노선별 주행시간 차이는 운영 오차로 계측하고 필요할 때 routeId별 보정으로 확장한다.

---

## SC-11 — 포그라운드 알림은 앱 내부 배너로 통합 (2026-08-03)

### 결정

1. 앱이 포그라운드일 때 local checkpoint와 FCM 수신은 OS 알림으로 표시하지 않고 앱 내부 Journey 배너로만 표시한다.
2. 앱이 백그라운드이거나 잠금화면이면 local notification 또는 FCM을 OS 알림으로 표시한다.
3. 포그라운드 FCM handler는 payload를 canonical 상태로 사용하지 않고 최신 Journey snapshot을 조회·대조한다.
4. 동일 dedup key의 local checkpoint, FCM, REST 갱신이 겹치면 사용자에게는 배너 한 건만 표시한다.

### 이유

사용자가 Journey 화면을 보고 있는 동안 OS 알림과 앱 배너를 함께 띄우면 같은 행동 안내가 중복된다. 포그라운드에서는 현재 화면 맥락을 유지하는 앱 내부 배너가 적합하고, 앱을 볼 수 없는 백그라운드·잠금화면에서만 OS 알림이 필요하다.

### 인수 기준 변경

- Android·iOS 포그라운드에서 환승 위험·재탐색 FCM 수신 시 앱 내부 배너는 1회, OS 알림은 0회다.
- 포그라운드 버스 하차 checkpoint도 앱 내부 배너만 표시된다.
- 백그라운드·잠금화면에서는 각 알림 소유권에 따라 local notification 또는 FCM OS 알림이 표시된다.
- 알림 선택 또는 앱 복귀 후에는 최신 snapshot 기준의 next action이 표시된다.

---

## SC-12 — 지하철 GPS 공백 보호 lease (2026-08-03)

### 결정

1. 일반 leg의 Journey 만료 기준은 기존과 같이 마지막 정상 신호 기반 3분 `guidanceLeaseUntil` + 2분 `expirationGrace`다.
2. `RIDE_SUBWAY` 진입 시 서버가 `subwayOfflineProtectionUntil`을 설정한다.
3. 보호 시각은 `min(currentLegPredictedEndAt + 5분, subwayLegStartedAt + 60분)`이다. 초기 예측 ETA가 없으면 `subwayLegStartedAt + plannedDuration`을 사용한다. progress로 ETA가 보정되면 60분 상한 안에서 갱신할 수 있지만 시간 경과만으로 무한 연장하지 않는다.
4. 만료 worker는 `max(guidanceLeaseUntil + expirationGrace, subwayOfflineProtectionUntil)`이 지난 Journey만 `EXPIRED`로 전이한다.
5. 지하철 leg를 벗어나거나 Journey가 완료·취소되면 보호 시각을 제거한다.
6. 보호 시각 안에 앱이 복귀하면 heartbeat 후 최신 snapshot으로 수렴한다. 보호 시각까지 지난 `EXPIRED` Journey는 복원하지 않는다.

### 이유

지하철 터널에서는 GPS와 iOS background callback이 5분 이상 끊길 수 있다. 일반 lease만 적용하면 앱을 정상적으로 사용 중인 사용자의 Journey가 지하철 이동 도중 종료될 수 있다. JavaScript timer나 silent push는 백그라운드 실행을 보장하지 않으므로 서버가 현재 leg의 계획 종료 시각을 이용해 제한된 오프라인 구간을 보호한다.

### 인수 기준 변경

- 일반 버스·도보·대기 leg는 기존 3분 + 2분 만료 기준을 유지한다.
- `RIDE_SUBWAY`에서 GPS·heartbeat가 5분 이상 없어도 보호 시각 전에는 Journey가 ACTIVE다.
- 지하철 보호 시각과 일반 lease가 모두 지난 뒤에는 `EXPIRED/CLIENT_LEASE_EXPIRED`가 되고 평가·알림을 중단한다.
- 지하철 leg 전이 후에는 다음 일반 leg에 보호 시각이 남지 않는다.
- 서버·Redis 재시작 후에도 PostgreSQL의 보호 시각이 유효한 Journey는 복원된다.

### 수용하는 리스크

지하철에서 사용자가 앱을 강제 종료해도 서버는 이를 즉시 알 수 없어 보호 시각까지 Journey가 ACTIVE로 남을 수 있다. 다만 네이티브 위치 추적과 local notification은 강제 종료 시 중단되며, 보호는 예상 leg 종료 + 5분과 최대 60분으로 제한한다.

---

## SC-13 — 보관 기간·SLO·점진 배포 gate 확정 (2026-08-03)

### 결정

1. 정밀 GPS 좌표는 서버에 저장하지 않고 progress는 leg별 최신 요약만 유지한다.
2. 종료된 Journey·leg·revision·event와 notification 기록은 30일, 로그는 14일, 식별 가능한 UI telemetry는 30일, 비식별 집계는 1년 보관한다.
3. 모바일 위치·geometry·pending event는 terminal 상태·로그아웃 시 삭제한다. 계정 삭제 시 API·push를 즉시 차단하고 token을 제거하며 사용자 연결 데이터는 24시간 안에 물리 삭제한다.
4. 초기 서버 SLO는 command 성공률 99.5%, snapshot p95 500ms, progress p95 300ms, evaluator lag p95 10초, outbox→FCM provider p95 5초, provider 성공률 99%, 복구 수렴 p95 5초다.
5. 이전 revision 표시·checkpoint 중복·사용자 수락 없는 경로 교체는 0건이고 premature expiry는 0.5% 미만이어야 한다.
6. 실제 단말 FCM 도착시간과 ETA 정확도는 고정 서버 SLO로 두지 않는다. FCM은 provider 접수 이후 telemetry로 관찰하고 ETA는 shadow baseline 대비 회귀로 판단한다.
7. 배포는 `OFF → shadow → 내부 실사용 → 5% → 25% → 전체` 순서이며 각 단계의 최소 Journey 수·관측 기간과 중단 기준을 통과해야 한다.
8. 정상 conflict·validation 4xx는 API 실패율에서 제외하고, premature expiry는 `EXPIRED` 후 10분 이내 same-Journey resume를 proxy로 측정한다.

### 배포 gate

| 단계 | 최소 gate |
|---|---|
| Shadow | 내부 100 Journey 또는 7일, 알림·제안 표시 없음 |
| 내부 실사용 | iOS·Android 합계 50 Journey, local·FCM·지하철 보호 lease 검증 |
| 5% canary | 500 Journey 또는 7일 |
| 25% | 1,000 Journey 또는 7일, DB·외부 API 쿼터 확인 |
| 전체 | 모든 SLO·불변식 통과 |

### 중단 기준

다른 사용자 Journey 노출, 이전 revision 표시, 무단 경로 변경, 동일 checkpoint 중복 표시, 위치·token 노출, 기존 기능의 치명적 회귀는 한 건이라도 feature flag를 즉시 끈다.

API 5xx 2% 초과, evaluator lag p95 30초 초과, FCM provider 성공률 95% 미만, DB pool 85% 초과, 외부 API quota 80% 초과, 기존 API p95 20% 초과 회귀, premature expiry 0.5% 이상이 10분 지속되면 현재 단계 확대를 중단한다.

### 이유

서버가 통제할 수 없는 단말 FCM 도착시간을 SLO로 잡으면 운영 판단이 왜곡된다. 반대로 상태 fencing·중복 알림·무단 경로 교체는 사용자 안전과 직접 연결되므로 비율 목표가 아니라 0건 불변식으로 관리한다. 위치 데이터는 디버깅에 필요한 최신 progress와 짧은 운영 기록만 남기고 장기 분석은 비식별 집계로 분리한다.

### 인수 기준 변경

- retention job과 계정 삭제가 설정된 기한 안에 완료된다.
- 로그·metric·telemetry에 정밀 위치·인증 token·FCM token·전체 route JSON이 없다.
- 각 rollout 단계는 최소 sample과 관측 기간, SLO를 통과해야 다음 단계로 이동한다.
- 즉시 중단 불변식은 feature flag rollback으로 검증한다.
