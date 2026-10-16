# 발송(relay) 용량 실험 — 알림 양을 늘려 한계점과 그때의 병목 찾기

티어: M. 운영 코드 변경 없음(테스트 하네스와 측정 문서만). 근거 코드는 모두 `origin/main` 기준.

## 목표와 가설

지금까지 발송 실험(#446, #451, `load/ladder/NOTIFICATION-SLO.md`)은 "폭주 한 번(알림 약 700~860건)을 장애 조건에서 버티나"만 봤다.
알림 **양**을 계단식으로 올려 발송이 SLO를 깨는 지점(knee)과 그때의 병목을 찾는다.

- H1 (FCM 2~5초): 처리량 ≈ 워커 수 ÷ FCM 왕복. 워커 128이 먼저 바닥난다(동기 발송이 병목).
- H2 (FCM 60ms): 워커는 남는다. 워커가 DB에 머무는 비중이 커져 **커넥션 풀(20) 대기, claim 경합, DB CPU** 중 하나가 먼저 닿는다.
- 확인할 부수 지표: heartbeat 한 주기 시간(처리 중 행 수에 비례), 빈 claim 횟수, JVM 스레드와 CPU.

## 설계 결정

1. **평가기를 거치지 않고 아웃박스에 직접 적재(open-loop).** 지하철 API와 평가기 상한(200건/초)이 결과에 섞이지 않게 하고, 들어오는 속도를 실험자가 정한다. 처리 완료를 기다렸다 넣는 closed-loop는 한계를 숨기므로(coordinated omission) 쓰지 않는다. 적재 시각은 벽시계 일정표를 따르고, 늦어지면 따라잡되 실제 적재 시각을 기록한다.
2. **발송 경로는 운영 코드 그대로**: PULL 128, claim 2건, heartbeat 10초, 회수 45초, 지수 재시도, 시도 번호. 바꾸는 것은 가짜 발송처 지연뿐.
3. **가짜 FCM = 기존 `LoadTestNotificationSender`**(프로세스 안 sleep). 실제 HTTP 클라이언트 풀과 HTTP/2 제한은 재지 않는다(한계로 기록).
4. **worker JVM 한 개만 띄운다**(api 없음). 평가기와 수집기가 일하지 않게 세션 `next_evaluation_at = NULL`, 지하철 수집과 감시 플래그는 끈다.
5. 노트북(M3 Pro 11코어) 측정, 칸마다 3회. 숫자는 노트북 조건으로만 쓴다.

## 근거 코드 좌표 (origin/main)

- 테이블: `src/main/resources/db/migration/V40__create_journey_notification.sql:15-110`, 우선순위 칼럼 `V70`. `delivery_key` 전체 유니크(:83).
- 발송 직전 확인 `JourneyNotificationOutboxStateService.loadTask` (:139~): 만료(:148), 세션 ACTIVE(:155), route_revision 일치(:160), `guidance_lease_until > now`(:164), command_version 일치(:170). TRANSFER_TARGET_APPROACHING + TRANSFER 조합만 `transfer_connection` 필요(:204-226) → 이 조합은 쓰지 않는다.
- 활성 push 토큰이 없으면 `no-active-token` 영구 실패(lost) → 토큰 행 필수.
- 가짜 발송처: `infrastructure/adapter/out/notification/LoadTestNotificationSender.java` (`@Profile("loadtest")` + `journey.notification.sender=loadtest`, prod 부팅 거부 :143-147). 지연 `loadtest.push.mean-ms`(lognormal) 또는 `loadtest.push.latency-min-ms`/`latency-max-ms`(균등). 수신 TSV `loadtest.push.log-path`(:237).
- 깨우기: `JourneyRelayWaker`는 JVM 안 신호뿐. 직접 INSERT 는 빈 claim 뒤 `awaitChange(seen, sweepIntervalMs=1000)`로 최대 약 1초 뒤 집힌다(`JourneyNotificationRelayLoop.pullLoop` :152-166). → delivery-lag 하한 약 1초, 결과에 명시.
- 기존 직접 적재 SQL: `JourneyNotificationRelayIT.java:518-533` `enqueue()` (journey_session 에서 INSERT ... SELECT, `delivery_key = uuid || ':PUSH'`, TRANSFER_RISK_AT_RISK).
- 기존 시드 패턴: `SubwayLadderFixture.java` users :240, user_push_token :254-258, journey_session :267-277(lease `now()+3h`, 표지 `destination_summary={"ladder":...}`), 정리 `cleanUp()` :349.
- 기존 실행기와 수집: `load/ladder/run_subway_ladder.sh` (worker 인자 :61-83, promsnap :123-126, cpu :143-150, pgss reset/dump :151/:231-239, queue tsv :172-180).
- `created_at` 은 KST 벽시계(`ClockConfig.java:16`) → 생성기는 `now() at time zone 'Asia/Seoul'` 로 쓴다.
- Hikari `maximum-pool-size: ${DB_POOL_MAX_SIZE:20}` (application.yaml:37).

## 산출물과 변경 파일 (PR-1: 하네스, 운영 코드 무변경)

브랜치: fresh `main` 에서 `test/relay-capacity-harness`.

1. `src/test/java/watoo/grd/nextroute/journey/load/RelayCapacitySeedIT.java` (신규)
   - `@EnabledIfEnvironmentVariable(named="RELAY_SEED", matches="true")`, 기존 `SubwayLadderSeedIT` 와 같은 실행 방식(gradle).
   - `RELAY_SESSIONS`(기본 2,000) 개의 users + 활성 `user_push_token` + `journey_session`(ACTIVE, lease `now()+6h`, `next_evaluation_at NULL`, 표지 `destination_summary={"ladder":"LOAD-RELAY"}`) 삽입. `RELAY_CLEAN=true` 면 표지로 찾은 세션의 `journey_notification` → 세션 → 토큰 → 사용자 순 삭제.
   - 세션 id 범위를 `RELAY_OUT`(TSV)로 기록.
2. `load/ladder/relay_load_gen.py` (신규, 표준 라이브러리만)
   - 인자: `--steps "10:180,25:180,50:180,..."`(건/초:초), `--tick-ms 200`, `--ttl-mix "180:0.2,600:0.8"`, `--pg-container`, `--out`.
   - `docker exec -i <pg> psql` 프로세스 하나를 열어 두고 tick 마다 `INSERT ... SELECT` 한 문장으로 `rate*tick` 건 적재. 세션은 시드 범위에서 순환 선택, `delivery_key = gen_random_uuid() || ':PUSH'`, `checkpoint = TRANSFER_RISK_AT_RISK`, `scope_type = TRANSFER`, `scope_id = 0`, `channel = PUSH`, `status = PENDING`, `scheduled_at = now()`, `expires_at = now() + ttl`, `created_at/updated_at = now() at time zone 'Asia/Seoul'`, `payload = {"firstSeenAt": now 문자열}`(e2e 지표용), route_revision/command_version/user_id 는 세션에서 복사.
   - 일정표 대비 실제 적재 시각과 건수를 `gen-<tag>.tsv` 로 기록(open-loop 검증).
3. `load/ladder/run_relay_capacity.sh` (신규)
   - 재사용 컨테이너 `st-pg`(:55432), `st-redis`(:56379). 새 컨테이너 만들지 않음.
   - worker JVM 하나: 프로필 `dev,worker,loadtest`, `journey.notification.relay.enabled=true`, `journey.notification.sender=loadtest`, 지연 env(`PUSH_MEAN_MS` 또는 `PUSH_MIN_MS`/`PUSH_MAX_MS`), `PULL_WORKERS`(기본 128), `DB_POOL_MAX_SIZE`(기본 20), 평가기·지하철 수집·감시 끔(구현 때 플래그명 확인해 노트에 기록), prometheus 노출.
   - 단계마다: prom 스냅숏(시작/끝), `pg_stat_statements_reset()` 후 끝에 상위 20 덤프, 5초마다 queue tsv(PENDING 만기·PENDING·PROCESSING·SENT·CANCELED·DEAD), 15초마다 cpu(JVM·Postgres 컨테이너).
   - 끝나면 `relay_capacity.py` 호출, 시드 정리 옵션.
4. `load/ladder/relay_capacity.py` (신규) — 단계별 표:
   - 들어온 속도(gen tsv), 초당 SENT, 대기열 기울기(PENDING 만기 증가율), delivery-lag p50/p95/p99(DB `sent_at - created_at`), lost(만료 CANCELED·DEAD·FAILED), 중복(가짜 발송처 duplicate 카운터), hikari acquire p95·pending 최대, claim 쿼리 평균과 DB 시간 몫, Postgres CPU, JVM CPU·스레드·GC 멈춤, heartbeat 주기 p95.
   - knee 판정: (a) 초당 SENT < 들어온 속도 × 0.95 이고 대기열이 단계 내내 증가, (b) delivery-lag p95 > 32초(긴급 SLO), (c) lost > 0 중 첫 번째로 걸린 단계.
5. `load/ladder/README.md` 에 실행법 한 절 추가.
6. 이 플랜 파일(`docs/plan/relay-capacity/PLAN.md`, `SUMMARY.md`).
7. `implementation_note.md` 에 발견 사항 append.

## 측정 칸 (PR-2: 결과 문서)

| 칸 | FCM 지연 | 워커 | 풀 | 속도 계단(건/초, 각 180초) |
|---|---|---|---|---|
| A | 60ms lognormal | 128 | 20 | 10, 25, 50, 100, 200, 400, 800 (knee 다음 단계에서 중단) |
| B | 2~5초 균등 | 128 | 20 | 5, 10, 20, 40, 80 |
| B' | 2~5초 균등 | 32 | 20 | 2, 5, 10, 20 (H1 비례 확인용) |
| A' | 60ms | 128 | 40 | A 의 knee 전후 두 단계만 (H2 가 풀이면 확인) |

- 칸마다 3회, 회차 사이 시드 정리 후 재시드. 워밍업 60초(10건/초).
- 결과 문서: `load/ladder/RELAY-CAPACITY.md` — 결론 첫 줄, 칸별 knee 와 병목, 3회 편차, 한계(가짜 FCM 이 프로세스 안, 노트북, 깨우기 하한 1초).

## 작업 순서

- [ ] fresh main 에서 브랜치, 플랜 파일 포함
- [ ] 1 시드 IT → 로컬 `st-pg` 에 2,000 세션 시드·정리 확인
- [ ] 2 생성기 → 10건/초 60초 스모크: gen tsv 일정표 오차, 적재 건수 = SENT 건수, lost 0
- [ ] 3 실행기 + 4 분석기 → 스모크 1단계로 표 한 줄 생성 확인
- [ ] 5 README, 노트 → PR-1 (테스트 결과 첨부) → 머지
- [ ] 칸 A, B, B' 3회씩 측정 → 필요하면 A' → `RELAY-CAPACITY.md` → PR-2
- [ ] 결론이 "동기 발송이 병목"이면 비동기 발송 구조 처방을 별도 이슈로 제안(이 플랜 범위 밖)

## 검증

- `./gradlew compileTestJava` 성공
- `RELAY_SEED=true ./gradlew test --tests '*RelayCapacitySeedIT'` 로 시드·정리 왕복
- 스모크: 10건/초 × 60초 → 적재 600 ≈ SENT 600, lost 0, 중복 0, gen tsv 일정 오차 p95 < 200ms
- 기존 테스트 영향 없음(운영 코드 무변경, 신규 IT 는 env 없으면 건너뜀)

## 리스크

- 가짜 발송처의 `alerting.per_revision_over1` 게이지는 한 세션에 알림을 여러 번 넣으므로 의미가 없다 → 분석에서 제외하고 문서에 명시.
- 대량 적재로 `journey_notification` 이 커져 claim 인덱스가 부풀 수 있음 → 회차마다 정리, 행 수를 결과에 기록.
- 생성기 자체가 노트북 CPU 를 먹음 → 생성기·Postgres·JVM CPU 를 따로 기록.
