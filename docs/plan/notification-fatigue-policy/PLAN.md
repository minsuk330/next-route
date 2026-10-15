# PLAN — #428 여정 악화 알림 정책 (Opus 검토 반영판)

티어 L. PR 3개(각자 fresh main), 플래그 `journey.notification.fatigue.enabled`(기본 false).

## 어휘 (실제 값)
- 알림 상태: `PENDING, PROCESSING, SENT, FAILED, DEAD, CANCELED`(V40 CHECK). "대체됨"은 별도 상태가 아니라 `CANCELED` + `last_failure_reason`(`superseded`, `superseded-by-reroute`, relay 쪽 `expired`·`lease-expired`·`command-changed`·`route-changed`)
- 새 취소 사유(마이그레이션 불필요): `quiet-skipped-android`, `quiet-dominated`
- 발송 종류: payload `"delivery": "ALERTING" | "QUIET"`, FCM data `delivery`

## PR-1a 전송 계층 (V68)
1. V68: `user_push_token.capabilities text[] not null default '{}'`. 토큰 등록 API 가 선택 필드 `capabilities`(예: `["quietChannel"]`) 받음 — 없으면 빈 값(구버전 앱)
2. `FirebaseAdminMessagingClient`(플래그 on 일 때만):
   - 모든 여정 위험·재탐색 알림에 Android `tag` = 갱신 열쇠
   - QUIET: iOS `Aps.putCustomData("interruption-level","passive")`, Android `channel_id=journey_quiet`
   - QUIET + Android 토큰에 `quietChannel` 없음 → 보내지 않고 `CANCELED quiet-skipped-android`
3. 갱신 열쇠(플래그 on): 위험·재탐색 = `j{id}r{rev}A`, 접근 알림 = 기존 환승 단위
4. payload 가 null(직렬화 실패)이면 ALERTING 으로 간주 + 지표 `journey.notification.delivery_unknown`
5. 테스트: `MessageDispatcher` 경계에서 잡은 `Message` 비교 — 플래그 끔 = 지금과 동일, QUIET iOS passive, QUIET Android 능력 없음 = 미발송, 능력 있음 = quiet 채널·tag

## PR-1b 판정 (V69)
1. V69: `journey_session.alert_claim_revision int null`, `alert_claim_key varchar(255) null`(울림 권리를 가진 알림의 논리 키)
2. **울릴 권리 따기** — 세 쓰기 경로 모두 같은 원자 UPDATE, 잠금 순서 journey_session → (reroute_proposal | transfer_connection):
   ```sql
   UPDATE journey_session SET alert_claim_revision = :rev, alert_claim_key = :key
   WHERE id = :id AND (alert_claim_revision IS DISTINCT FROM :rev)
   RETURNING id
   ```
   1행 = ALERTING, 0행 = 이미 따짐 → 아래 물려받기 확인 후 아니면 QUIET
   - 경로: 평가기 `TransferRiskNotifier`(이미 `finishEvaluation` 으로 행 잠금 보유), 보드 `RerouteBoardPushTxService`(`recordPush` 앞), 재탐색 `RerouteAlertTxService`
3. **판정은 평가(또는 push) 1회당 채널 루프 밖에서 한 번**. 한 평가에서 여러 환승 악화 → 가장 심한 하나가 권리 시도, 나머지 QUIET
4. **물려받기**: 순서 = 대체(취소) 먼저 → 판정 → 삽입. 이번 트랜잭션이 취소한 행 중 논리 키가 `alert_claim_key` 이고 그 행이 PENDING/PROCESSING 이었으면, `alert_claim_key` 를 새 행 키로 옮기고 ALERTING
5. **돌려받기**: relay 가 `alert_claim_key` 행을 SENT 가 아닌 끝 상태(FAILED·DEAD·CANCELED 중 `superseded*` 제외)로 만들면 같은 트랜잭션에서 `alert_claim_revision = NULL WHERE id=? AND alert_claim_key=?`
6. QUIET 위험 알림: `supersedePendingByScope` 에서 접근 알림 취소 건너뜀. `alert_claim_key` 가 재탐색이면 QUIET 위험 알림은 `CANCELED quiet-dominated`
7. 지표: `journey.notification.enqueued{checkpoint,delivery}`, 감사 쿼리(경로 버전당 SENT ALERTING ≥ 2)

## PR-2 문구
- 재료: 다음 차 노선 이름(도착 구간에서), `nextBusArrivalAt`(있음), 대체 여부
- AT_RISK "{노선}이 {N}분 뒤 와요 — 서두르면 탈 수 있어요"(N≤0 "곧 와요") / MISSED 대안 없음 "다음 {노선}은 {HH:mm} — 눌러서 안내 보기" / 위험을 대체한 재탐색: 제목 "환승을 놓쳤어요"·"환승이 빠듯해요" + 기존 행동 문구 / 재료 없으면 기존 문구
- "push data 엔 식별자만" 원칙 유지(숫자는 문구에만, 만료로 보호)

## 계약 테스트
PR-1a: 위 5번. PR-1b: 빠듯→놓침 = 울림 1(보드 20초 지연 중 포함) / 환승 2개 동시 = 울림 1 / 보드 위험 대체 = 울림 1 / 평가기·보드 동시(두 스레드, Testcontainers) = 울림 1 / 울림 알림 expired → 권리 반납 → 다음 악화 울림 / 경로 버전+1 = 울림 1 / 채널 2개 = 판정 1 / 재탐색 울린 뒤 위험 = quiet-dominated / 접근 알림 취소 안 됨 / 회복 후 같은 상태 재악화 = 행 0(기존 함정 고정). PR-2: 문구 3종 + 재료 없음.

## 앱 인계 (HANDOFF.md)
- Android 채널 `journey_quiet`(IMPORTANCE_LOW) 생성 + 토큰 등록에 `capabilities:["quietChannel"]`
- 포그라운드 `onMessage`: `data.delivery == "QUIET"` 이면 인앱 배너 생략, 화면만 갱신(**필수**)
- iOS 추가 작업 없음(passive 는 OS)

## 배포
V68·V69 tracked·Flyway 자동. 플래그 켜기 전 앱 배포 불필요(능력 없는 Android 는 갱신을 안 받을 뿐)

## 합치기 창 (사용자 결정 2026-10-15)
첫 울림을 바로 보내지 않고 **다음 수집 결과를 받아 볼 때까지** 보류한다. 그 사이 더 나쁜 소식이나 대안이 오면 대체해서 한 번에 보낸다(대체 = 물려받기).

| 상황 | 보류 | 뜻 |
|---|---|---|
| 빠듯(AT_RISK) | 수집 **1번** | 다음 수집에서 놓침으로 바뀌거나 회복되는지 본다 |
| 놓침(MISSED), 대안 없음 | 수집 **1번** | 놓침이 한 번의 흔들림이 아닌지 확인 |
| 놓침(MISSED), 대안 계산 중 | 수집 **2번(최대 30초)** | 대안이 오면 "놓침 + 대안" 한 알림 |

- 구현: 알림 행의 발송 가능 시각 = 판정 시각 + 수집 주기 × N + 여유 2초(다음 수집의 평가가 끝날 시간). 수집 주기는 설정값(지하철 15초). 새 큐·타이머 없음 — 기존 보드 지연(`risk-merge-delay-seconds`)과 같은 방식, 그 값을 이 규칙으로 대체
- 대안 계산 끝났는데 후보 없음 → 놓침 행의 보류를 수집 1번 지점으로 당김
- **행동 시간 상한**: 보류 = min(위 값, 다음 차 도착 − 지금 − 여유 30초), 0 이하면 즉시
- 보류 중 회복(빠듯 → 여유) → 행 취소 + 권리 반납(울림 0, 의도)
- SLO: 서버 SLO(처음 본 시각 → 적재)는 보류를 뺀 값으로 판정, "적재 → 발송"(보류 포함)은 따로 보고. 지표 `journey.notification.hold{outcome=merged|released|canceled}`, 보류로 늘어난 발송 지연

계약 테스트 추가: 창 안 빠듯→놓침 = 놓침 1번 / 창 안 대안 도착 = 합친 알림 1번 / 창 동안 변화 없음 = 창 끝 1번 / 창 안 회복 = 0번 / 대안 없음 확정 = 수집 1번 지점 / 다음 차가 창보다 먼저 = 즉시
