# PLAN — 알림 발송 relay 내구성 (#451, L)

사람용 요약 = SUMMARY.md. 이 문서는 구현 에이전트용이다. 근거 실측은 `load/ladder/NOTIFICATION-SLO.md`(#446)에 있다.

## 범위
- 대상: 여정 알림 outbox `journey_notification` relay. 버스 토스 relay(`BusAlert*`)·링 push relay 는 손대지 않는다.
- 좌표
  - `application/journey/service/JourneyNotificationRelayService.java`: 지금은 배치 claim 200 → 고정 풀 4 → 전부 대기.
  - `application/journey/service/JourneyNotificationOutboxStateService.java`: reclaim, claimBatch, loadTask, markSent, markTransientFailure, markPermanentFailure, markSkipped.
  - `domain/journey/repository/JourneyNotificationRepository.java`: `claimBatchForProcessing`(~150-169), `reclaimStale`(~171-180).
  - `infrastructure/adapter/in/scheduler/journey/JourneyNotificationRelayLoop.java`: 깨우기와 sweep.
  - `application/journey/config/JourneyNotificationProperties.java`: 부팅 검증(~230-300). `backoffSeconds × maxAttempts ≤ minTtl`, `fcm.timeout × 2 < claimTimeout`.
  - `infrastructure/adapter/out/notification/FcmNotificationSender.java`: SDK 호출과 타임아웃.
  - `application/journey/service/JourneyNotificationEnqueuer.java`: 적재 SQL 호출. payload 에 `seenTier` 가 있다(#448).
  - `application/subway/service/RealtimeSubwayCollector.java`: 스냅숏 저장 뒤 `SubwayChangeFeed.onSnapshot`.

## PR-1 당겨 가는 워커 + heartbeat + 증서 조건부 기록
- **설정** `journey.notification.relay.*`
  - `mode: BATCH|PULL`. 기본 BATCH, env `JOURNEY_NOTIFICATION_RELAY_MODE`.
  - `pull-workers: 32`
  - `pull-claim-size: 2`
  - `heartbeat-interval-ms: 10000`
  - `claim-timeout-seconds`: 신규. 지금의 `claim-timeout-minutes: 3` 을 대체한다. 기존 키는 호환으로 읽고, 신규 값이 있으면 신규가 이긴다. 기본은 BATCH 모드와 같은 180초이고, PULL 에서 45초를 권장한다(측정 뒤 기본 전환).
- **PULL 모드**
  - 워커 스레드 N개가 `while running: ids = claimBatch(k); if empty → waker.await(sweep); else 각 id process`. 깨우기 신호는 대기 중인 워커 모두에게 broadcast 한다(기존 `JourneyRelayWaker` 확장, 신호 유실 없게 세대 카운터).
  - reclaim 은 워커가 아니라 별도 1개 스케줄(heartbeat 스레드)이 한다. 워커마다 하면 경합만 는다.
- **heartbeat**
  - 진행 중 id 집합(동시 맵)을 heartbeat 스레드가 10초마다 `UPDATE ... SET processing_started_at = :now WHERE id IN (:ids) AND status='PROCESSING' AND attempt_count = 각 증서` 로 갱신한다. 증서는 행마다 달라 `unnest` 두 배열로 매칭한다.
  - BATCH 모드에도 적용한다. 지금 배치가 3분에 붙는 문제를 같이 풀기 위해서다.
- **증서(lease token)**
  - claim RETURNING 에 `attempt_count` 를 더한다(`List<ClaimedRow(id, attempt)>`).
  - `loadTask`, `markSent`, `mark*Failure`, `markSkipped` 는 상태가 PROCESSING 이고 `attempt_count == 증서` 일 때만 쓴다. 불일치면 아무것도 안 하고 `journey.notification.stale-write{op}` 를 센다.
  - 기존 엔티티 경로(`findById` → 상태 확인 → 변경)에 증서 비교 한 줄을 더한다. 행 잠금 없이 같은 tx 의 dirty check UPDATE 라서, 회수와 경합할 때 증서 확인만으로는 원자적이지 않다. **`@Version`(낙관 잠금) 또는 조건부 UPDATE 로 원자성을 확보한다.** 어느 쪽인지는 구현 때 엔티티에 `@Version` 이 있는지 보고 정하고, 노트에 남긴다.
- **부팅 검증 추가**
  - `claimTimeout ≥ 3 × heartbeatInterval`
  - `fcm.timeout × 2 < claimTimeout`(기존)
  - `claimTimeout + 첫 백오프 < minTtl`
- **FCM 전송 계층:** 동시 32 에서 연결 재사용이 되는지(SDK HttpTransport 종류, 연결 풀 상한) 확인하고 노트에 남긴다. 막히면 `ApacheHttp2Transport` 등 SDK 가 지원하는 전송으로 바꿀지는 별건이고, 측정 근거와 함께 노트에 둔다.
- **지표**
  - `journey.notification.relay.inflight`(gauge)
  - `relay.claim.size`
  - `stale-write{op}`
  - `reclaimed`(카운터, 기존에 없으면 추가)
  - `heartbeat.duration`
- **계약 테스트**
  - 회수 뒤 재claim 된 행에 원 워커의 markSent 는 0행이고 새 워커의 markSent 는 성공(Testcontainers, 두 증서)
  - heartbeat 중인 행은 claimTimeout 이 지나도 회수되지 않음, heartbeat 가 멈추면 회수됨
  - PULL 에서 한 워커의 sender 가 막혀도(latch) 다른 워커가 나머지를 보냄
  - BATCH 모드의 SQL·순서 불변(기존 테스트 그대로 통과)
  - 부팅 검증 실패 조합

## PR-2 우선순위 (V70)
- `V70__journey_notification_priority.sql`
  - `priority smallint not null default 2`
  - 기존 claim 인덱스를 확인한 뒤, `(priority, scheduled_at) where status='PENDING' and deleted_at is null` 부분 인덱스를 추가하고 대체되는 인덱스는 지운다.
- 적재 때 등급을 정한다.
  - 처음 본 등급(`DetectionStamp.tier`)이 URGENT → 0, CAUTION → 1, 그 밖은 2.
  - 체크포인트 MISSED 는 등급과 무관하게 0 이다(다음 행동이 급함).
  - 접근(N2) 알림은 1, 보드·재탐색은 2.
  - 매핑은 한 곳(`NotificationPriority`)에 두고 표를 테스트한다.
- claim `ORDER BY` 는 플래그로 고른다.
  - `relay.claim-order: SCHEDULED|PRIORITY_FIRST`, 기본 SCHEDULED.
  - PRIORITY_FIRST 는 `ORDER BY priority, scheduled_at` 이다.
  - 굶주림 방지: `scheduled_at <= now - :agingSeconds(60)` 인 행은 priority 0 으로 취급한다(`ORDER BY CASE WHEN scheduled_at <= :aged THEN 0 ELSE priority END, scheduled_at`). 인덱스를 못 탈 수 있으니 EXPLAIN 으로 확인하고, 안 타면 aging 을 빼고 노트에 남긴다.
- 계약 테스트
  - 긴급이 여유보다 먼저 claim 됨
  - 오래 기다린 여유는 긴급과 동급
  - SCHEDULED 모드는 지금 순서 그대로
  - 매핑 표
  - 마이그레이션 적용(Flyway IT)

## PR-3 재시도 정책 + 변화 피드 분리
- `retry-policy: FIXED|EXPONENTIAL`, 기본 FIXED.
- EXPONENTIAL
  - `initial 5s`, `multiplier 2`, `max 60s`, `jitter 0.2`, `max-attempts-cap 8`
  - 다음 시도 시각이 만료를 넘으면 `expired-before-retry`(기존 의미 유지)
  - 지터는 주입 가능한 `RandomGenerator` 로 둔다(테스트 결정성).
- 부팅 검증의 `backoff × maxAttempts ≤ minTtl` 은 FIXED 일 때만 적용한다. EXPONENTIAL 은 첫 재시도 + claimTimeout < minTtl 을 본다.
- 변화 피드 분리: `RealtimeSubwayCollector` 에서 스냅숏 Redis 저장 실패를 잡아 로그·지표만 남기고 `onSnapshot` 은 그대로 부른다. 역별 캐시 채우기 실패도 같다.
- 계약 테스트
  - 수열과 지터 범위(시드 고정)
  - 만료 넘으면 취소
  - 상한 8 에서 DEAD
  - FIXED 모드는 지금과 같음
  - Redis 저장 예외에도 onSnapshot 1회

## PR-4 측정
- #446 하네스(`load/ladder`, #449)로 측정한다.
  - **칸:** ① 폭주, ③a 느린 FCM, ③b 20%, ③c 60초, ③c' 150초, ④a kill -9
  - **설정:** PULL + PRIORITY_FIRST + EXPONENTIAL + claimTimeout 45초 묶음 / 모드 하나씩 켠 칸(가능하면)
- `load/ladder/NOTIFICATION-SLO.md` 에 "처방 뒤" 절을 append 한다. 표는 같은 열로 둔다.
- 결과가 좋으면 기본값 전환 PR 을 따로 낸다(env 기본값 변경).

## 공통 규칙
- 각 PR 은 fresh main 에서 브랜치를 따고, 스택은 금지한다. `implementation_note.md` 는 append-only 다.
- 전체 테스트: `./gradlew cleanTest test --continue`(st-pg :55432, st-redis :56379). 새 컨테이너는 금지한다(Testcontainers 는 허용).
- 마이그레이션 V70 은 tracked·Flyway 자동이다. 노트 `### 배포` 에 명시한다.
- 운영 배포는 이 작업에 포함하지 않는다.

## Opus 검토 반영 (2026-10-16, 확정 — 위 본문과 어긋나면 이 절이 이긴다)
1. **상태 변경은 전부 증서 조건부 네이티브 UPDATE.**
   - `JourneyNotification` 에는 `@Version`·`@DynamicUpdate` 가 없어, 엔티티 저장이 `attempt_count` 를 낡은 값으로 덮는다. 그러면 증서가 되돌아가 울타리가 깨진다. 그래서 `@Version` 대안은 폐기한다.
   - 대상: `markSent`, `markTransientFailure`, `markPermanentFailure`, `markSkipped`, 그리고 `loadTask` 안의 cancel·expired.
   - 형식: `UPDATE … WHERE id=:id AND status='PROCESSING' AND attempt_count=:token`.
   - 계약 테스트: 두 트랜잭션 경합(Testcontainers) 1개.
2. **늦은 기록은 울릴 권리를 건드리지 않는다.**
   - 조건부 UPDATE 가 0행이면 `StaleClaimException` 을 던져 같은 트랜잭션의 권리 반납(`releaseAlertClaim`, 여정 → 알림 잠금 순서 유지)까지 롤백한다.
   - 알림 행을 먼저 잠그는 순서는 평가기와 교착을 만들므로 금지한다.
   - 계약 테스트: "낡은 기록 → 울릴 권리 그대로".
3. **FCM 요청 수명을 실제로 묶는다(PR-1).**
   - 지금 `sendAsync().get(timeout)` 은 시간이 넘어도 요청을 취소하지 않는다. `FirebaseConfig` 는 connect·read 타임아웃을 설정하지 않았고, SDK 는 5xx 를 자체 재시도한다.
   - 고칠 것:
     - `FirebaseOptions.setConnectTimeout/setReadTimeout` 을 지정한다.
     - SDK 재시도를 끄거나 상한을 둔다. 실제 API 를 확인하고 노트에 남긴다.
     - 타임아웃이 나면 future 를 `cancel(true)` 한다.
   - 부팅 검증: `httpMaxLifetime = connect + read × (1 + sdk 재시도)` 일 때 `httpMaxLifetime × 2 < claimTimeout` 이고 `httpMaxLifetime < 첫 백오프` 다. 따라서 EXPONENTIAL 의 첫 백오프 기본은 `max(5s, httpMaxLifetime + 2s)` 로 한다.
4. **DB 커넥션 압력.**
   - 깨우기는 broadcast 대신 하나만 깨운다. claim 이 꽉 찬(=k건) 워커가 다음 워커를 연쇄로 깨운다.
   - 부팅 검증: `pull-workers ≤ hikari.maximum-pool-size − evaluator.workers − 2` 를 넘으면 경고 로그를 남긴다(실패 아님 — 풀 크기가 프로필별이다).
   - heartbeat·회수는 짧은 트랜잭션 하나로 묶는다. 전용 풀은 만들지 않고 측정 뒤 판단한다.
   - PR-4 측정 항목에 `hikaricp.connections.acquire` 를 더한다.
5. **DB 시계·회수 순서·자기 차단.**
   - heartbeat·회수·claim 시각은 DB `now()` 를 쓴다(다중 인스턴스 원칙). `processing_started_at` 도 `now()` 로 남긴다.
   - 한 주기에 heartbeat 를 먼저, 회수를 나중에 돈다.
   - 회수는 "이 인스턴스의 마지막 heartbeat 성공이 `claimTimeout/3` 안" 일 때만 한다. DB 가 멈췄다 돌아온 직후 남의 행까지 쓸어 담는 것을 막는다.
   - claim·heartbeat 예외 뒤에는 1초 대기(바쁜 헛돌기 금지).
6. **우선순위 1차 기준은 체크포인트.**
   - MISSED·AT_RISK → 0, 접근(N2) → 1, 나머지(보드·재탐색·하차·관리자 테스트) → 2. `seenTier` 는 쓰지 않는다(재평가 등급이지 알림 급함이 아니다).
   - 표는 모든 enqueue 호출부를 포함한다(`AdminJourneyNotificationTestService` 포함).
   - aging 은 `created_at` 기준으로 센다. 재시도가 `scheduled_at` 을 미루므로 그걸 기준으로 하면 처음부터 다시 센다.
7. **Redis 분리 테스트는 "트리거 요청 1회" 로 본다.**
   - `SubwayChangeFeed.onSnapshot` 은 리더 잠금(`JOURNEY_REEVAL_LEADER_LEASE`)이 켜져 있으면 Redis 예외 때 리더를 놓아 아무것도 안 한다.
   - 운영 compose 에는 이 env 가 없어 꺼져 있다(2026-10-16 읽기 확인). 다중 인스턴스에서 켜면 Redis 장애 때 감지가 멈추는 한계를 노트 `### 미결` 에 적는다.
8. 작은 것
   - 시도 횟수 상한은 `attempt_count − requeue_count`(실제 발송 시도)로 센다. 회수할 때 `requeue_count + 1`.
   - 세대 카운터는 claim **전**에 읽는다.
   - PULL 종료 순서: 워커 중단 → 진행 중 기록 마무리(최대 claimTimeout/2) → heartbeat 중단.
   - aging 정렬이 인덱스를 못 타는 것은 수용한다(PENDING 이 수백 건). claim 빈도 증가는 EXPLAIN 과 함께 노트에 남긴다.
9. **범위 밖, 별건 이슈:** 회수돼 다시 나가는 행을 소리 없이 대체하는 장치(onlyAlertOnce·같은 tag). 회수를 45초로 줄이면 "느린 접수 + 회수" 로 겹칠 기회가 늘기 때문이다. #451 에 링크한다.
