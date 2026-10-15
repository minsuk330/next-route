# PLAN — #428 여정 악화 알림 정책

티어 L(푸시 계약·앱 인계). PR 2개, 각자 fresh main, 플래그 `journey.notification.fatigue.enabled`(기본 false).

## 계약
- 울림(ALERTING) / 조용한 갱신(QUIET) 두 종류. 경로 버전당 ALERTING 은 위험(AT_RISK·MISSED) + 재탐색(REROUTE_SUGGESTED) 합쳐 최대 1
- 판정: 같은 (journeyId, routeRevision) 에 상태 SENT·PENDING·SENDING 인 ALERTING 위험/재탐색 행이 있으면 QUIET. **SUPERSEDED 로 취소된 행은 세지 않음**(보드가 위험 행을 취소하고 재탐색이 대신 울리는 경우)
- QUIET 표시는 outbox payload `"delivery":"QUIET"` (마이그레이션 없음). FCM data 에 `delivery` 추가(앱은 무시해도 됨)
- 갱신 열쇠: 위험·재탐색 = `j{journeyId}r{revision}A`(경로 버전 하나), 접근 알림 = 기존 환승 단위 열쇠 유지
- 접근 알림(TRANSFER_TARGET_APPROACHING) 은 판정·열쇠 대상 아님

## PR-1 서버 정책(문구 제외)
1. `RiskAlertBudget`(순수 판단 + 조회): `isAlerted(journeyId, revision)` — notification 테이블 조회(같은 트랜잭션, 평가 소유권 CAS 뒤라 같은 여정 동시 판정 없음)
2. `TransferRiskNotifier.handle`: 기존 "악화만" 규칙 유지, enqueue 시 ALERTING/QUIET 결정. **한 평가에서 여러 환승이 악화되면 가장 심한 하나만 ALERTING**, 나머지 QUIET
3. 보드 push(`enqueueBoardPush`)·재탐색 push: 같은 예산. 첫 push 가 위험 행을 대체하면 ALERTING 1 유지
4. `FcmMessageFactory`/`FirebaseAdminMessagingClient`: QUIET → APNs `aps.interruption-level=passive`, Android `tag`=갱신 열쇠 + `channel_id=journey_quiet`. ALERTING → Android `tag` 도 넣음(같은 경로 버전 알림이 화면에서 덮이게)
5. 안전 기본값: `fatigue.android-quiet-channel-ready=false` 이면 Android 토큰에는 QUIET 를 **보내지 않고 SENT 처리 없이 SKIPPED**(울림 방지). iOS 는 바로 적용
6. 지표: `journey.notification.enqueued{delivery}`, `journey.notification.suppressed{reason=quiet|skipped_android}`

## PR-2 문구(할 일)
1. payload 재료 추가: 위험 = 다음 차 노선 이름·도착 시각(이미 있음 nextBusArrivalAt), 놓침 여부
2. 문구:
   - AT_RISK: 제목 "환승 시간이 빠듯해요", 본문 "{노선}이 {N}분 뒤 와요 — 서두르면 탈 수 있어요" (N≤0 이면 "곧 와요")
   - MISSED(대안 없음): 제목 "환승을 놓쳤어요", 본문 "다음 {노선}은 {HH:mm} — 눌러서 안내 보기" (시각 없으면 기존 문구)
   - 재탐색이 위험을 대체(보드 첫 push, 같은 평가 경로에서 MISSED/AT_RISK 직후): 제목 "환승을 놓쳤어요"/"환승이 빠듯해요", 본문 기존 행동 문구("○○에서 내려 7호선 타면 4분 빨라요")
3. 숫자는 보낸 순간 값, 만료는 지금 규칙(다음 차 도착) 그대로
4. 기존 "push 에 상태값 금지" 주석 갱신(문구에만 쓰고 data 에는 식별자만 — 원칙 유지)

## 계약 테스트
- 악화→개선→악화(같은 경로 버전) = ALERTING 1, QUIET 0 (기존 키 중복 규칙)
- AT_RISK → MISSED = ALERTING 1 + QUIET 1, 갱신 열쇠 같음
- 환승 2개 동시 악화 = ALERTING 1(심한 쪽) + QUIET 1
- 보드: 위험 행 취소 + 재탐색 ALERTING 1 → 이후 위험 악화 = QUIET
- 재탐색 수락(경로 버전 +1) → 새 악화 = ALERTING 1
- 접근 알림 = 판정과 무관, 열쇠 기존
- FCM: QUIET → passive·tag·quiet 채널 / ALERTING → tag 만 / Android 채널 미준비면 QUIET 미발송
- 플래그 off = 기존 메시지와 바이트 단위 동일(회귀)
- 문구: AT_RISK·MISSED·합침 각 1, 재료 없으면 기존 문구

## 앱 인계(HANDOFF)
- Android: 채널 `journey_quiet`(IMPORTANCE_LOW, 소리·진동 없음) 생성 → 서버 `android-quiet-channel-ready=true`
- iOS: 추가 작업 없음(passive 는 OS 처리). 포그라운드 배너는 `data.delivery=QUIET` 면 띄우지 않기(선택)

## 미결(사용자)
- 보드의 두 번째 이후 push 를 QUIET 로 바꿀지(권장: 예)

## 검증
컴파일 + 위 테스트 + 전체 `cleanTest test`(기존 무관 12) → 노트 `### 검증`
