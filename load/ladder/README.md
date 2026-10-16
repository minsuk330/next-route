# 부하 사다리 (#415-4)

목표 부하(동시 여정 2,000)와 그 위(6,000 / 20,000)에서 환승 위험 파이프라인의 **어느 단계가 먼저 포화되는지** 재는 도구.

| 구성 | 역할 |
|---|---|
| `fake_providers.py` | 서울 버스 도착·위치 XML, ML 서빙 JSON. 실제 응답(`route_all.xml`·`pos.xml`)을 템플릿으로 노선·정류장·시각만 바꿈. 지연 = 운영 7일 평균(버스 73ms, ML 14ms) 로그정규 + 꼬리 |
| `LadderSeedIT` (src/test) | 진행 중 여정 N 건 + 사용자·노선·정류장을 DB 에 심음(지우지 않음, `LADDER_CLEAN=true` 로 지움) |
| `tokens.py` | 심은 사용자마다 access JWT 서명(HS256, `AUTH_JWT_SECRET`) |
| `ladder.js` (k6) | riders = 여정 하나에 VU 하나, 10초마다 여정 조회(204 포함), 약 100초 뒤 단계 전이 1회 / reads = 정해진 RPS 로 주변 정류장 조회 |
| `run_ladder.sh` | 한 칸 실행: 가짜 공급자 → 심기 → 토큰 → 앱(api+worker 한 JVM) → k6 → 앱 Prometheus 수집 → 요약 |
| `summarize.py` | 단계별 p50/p95(선점 쿼리·예정→시작·큐 대기·평가 1건·커넥션 대기·알림·조회), 공급자 호출 수, CPU·힙 |

```bash
./gradlew bootJar
REPO=$PWD OUT=load/ladder/out load/ladder/run_ladder.sh 2000 off 10m 20
REPO=$PWD OUT=load/ladder/out load/ladder/run_ladder.sh 2000 on  10m 20
```

전제: 로컬 Postgres·Redis(`POSTGRES_*`·`REDIS_*`), `env/*.env` export, docker(k6 `grafana/k6:2.1.0`).

한계: 경로 검색은 OTP(단일 엔진, 운영 VPS)라 제외하고 읽기 부하로 대신한다. 앱·DB·Redis·k6 가 한 노트북에서 돌아 자원 숫자는 운영 사양과 다르다 — 단계 간 **상대적 포화 순서**를 보는 도구다. 서버 자원 숫자는 운영 동일 사양 서버에서 따로 잰다(#415 체크리스트 마지막 항목).

## 지하철 이벤트 사다리 (#427)

이벤트 기반 재평가 + 알림 발송이 지하철 여정 2,000 / 6,000 에서 어디서 먼저 깨지는지 잰다(폴링 대비 전후는 #423). 결과 = `BOTTLENECK-MAP-SUBWAY.md`.

| 구성 | 역할 |
|---|---|
| `subway_model.py` | 가상 열차 모델(시간 함수). 노선 8 × 역 60 × 두 방향 = 키 960. 3분 간격 출발, 역 사이 2분, 역마다 지연 누적. 원천 나이 약 50초(키별 ±5초) — `recptnDt` 도 그 시각 |
| `fake_providers.py` | 위 모델로 `realtimeStationArrival/ALL`(수집기)·`/0/1000/{역}`(평가기)를 같은 답으로 낸다. ALL 을 낼 때마다 지문이 바뀐 키를 `changes-*.tsv` 에 적음(원천 시각·처음 낸 시각) |
| `SubwayLadderSeedIT` + `SubwayLadderFixture` (src/test) | 지하철 여정 N + 사용자 + push 토큰 심기/지우기(`LOADST` 역). 주기 부류 A/B/C 로 준비 시각 기준 긴급 20 / 주의 30 / 여유 50% |
| `subway_sampler.py` | 1초마다 방금 판정을 저장한 여정(last_evaluated_at) 기록 + 10초마다 주기 되돌림(등급 비율 유지) |
| `subway_detection.py` | 변화 × 구독 여정 × 다음 판정 저장 → 감지 지연 A(처음 본 → 저장, 서버 SLO)·B(원천 → 저장, 참고), 평가 시점 등급별 |
| `LoadTestNotificationSender` (src/main) | `loadtest` 프로필 + `journey.notification.sender=loadtest` 에서만 뜨는 가짜 발송처(FCM 지연 흉내, 성공). 번호·업무 키 중복, 경로 버전당 울림 >1 을 센다 |

```bash
./gradlew bootJar
MODE=subway REPO=$PWD OUT=load/ladder/out load/ladder/run_ladder.sh 2000 on 10m 20
```

전제: st-pg 에 `pg_stat_statements` 적재(`ALTER SYSTEM SET shared_preload_libraries='pg_stat_statements'` + 재시작 + `create extension`). 칸 시작에 통계를 비우고(워밍업 끝), 끝나면 지우기 뒤 남은 진행 중 여정 0 을 확인한다.

### 운영 모양: api·worker 분리 JVM (`SPLIT=1`)

기본(한 JVM, `dev,worker,loadtest`, 힙 2g, Hikari 풀 20 하나)은 그대로다. `SPLIT=1` 이면 같은 bootJar 로 JVM 두 개를 띄운다 — 운영 compose 의 `api`(`prod,api`)·`worker`(`prod,worker`) 컨테이너와 같은 역할 나눔.

| JVM | 프로필 | 포트 | 하는 일 |
|---|---|---|---|
| api | `dev,api` | 8080 (k6 대상) | 여정 조회·주변 정류장. worker 프로필이 없으니 `!worker` 빈(지도 타일·워밍업)이 뜨고, 발송처 설정 없음 = noop (운영 api 와 같음) |
| worker | `dev,worker,loadtest` | 8081 | 수집기·재평가·평가기·relay·가짜 발송처(운영의 `fcm.env` 자리) |

- 각자 Hikari 풀 20(기본값, #440), 힙 각 `-Xmx1g`(한 JVM 2g 를 반씩 — 합이 같아야 비교가 된다. 운영 앱 컨테이너는 `-Xmx` 없이 컨테이너 메모리의 1/4).
- 둘 다 같은 가짜 공급자·프록시(18999)·DB·Redis 를 본다.
- 지표: `prom-start-<label>.txt`·`prom-<label>.txt` = api, 같은 이름에 `-worker` 꼬리 = worker. `summarize.py` 는 `SPLIT=1` 이면 이름+태그가 같은 줄을 더하고(카운터·히스토그램 = 두 JVM 합) "JVM 별" 절에 풀 대기·CPU·힙을 따로 찍는다.
- CPU: 두 모드 모두 `cpu-<label>.tsv`(15초마다 JVM 별 `process/system_cpu_usage` + Postgres 컨테이너 CPU)와, 측정 창 평균 사용 코어(`process_cpu_time_ns_total` 차이 ÷ `process_uptime_seconds` 차이)를 요약에 붙인다. 예전 요약의 `process_cpu_usage 0.0` 은 워밍업 끝 스냅숏이 첫 호출이라 늘 0 이던 값이다.
- 버스 사다리(`MODE` 없음)는 분리를 지원하지 않는다(`SPLIT=1` 이면 멈춤).

```bash
SPLIT=1 MODE=subway RUN_TAG=split REPO=$PWD OUT=load/ladder/out load/ladder/run_ladder.sh 6000 on 10m 20
```

## 알림 SLO 실험 (#446)

지하철 사다리(`MODE=subway`, 보통 `SPLIT=1`)에 알림 쪽 부하·장애를 얹는다. 칸이 끝나면 `slo-<label>.txt`(요약 끝에도 붙음)에
SLO 네 줄 — 제때(처음 본 → FCM 접수 p95, 등급별, 보류 따로)·한 번만·빠짐없이·낡지 않게 — 과 대기열·relay 처리량을 낸다
(`notification_slo.py`). 결과 = `NOTIFICATION-SLO.md`.

| 스위치(env) | 뜻 |
|---|---|
| `STORM_LINES=1001,1002,1075,1005` | 알림 폭주: 측정 시작 `STORM_AT_S`(120)초 뒤 그 노선들의 배차를 `STORM_SECONDS`(240)초 동안 60초 간격 가짜 열차로 바꿔(`/storm/start`) 그 노선 여정이 한 수집 주기 안에 일제히 빠듯이 된다. 끝나면 실제 열차로 돌아온다(회복 → 대체 취소) |
| `PUSH_ARGS="--loadtest.push.latency-min-ms=2000 --loadtest.push.latency-max-ms=5000"` | 가짜 FCM 왕복을 [2, 5]초 고른 분포로 |
| `PUSH_ARGS="--loadtest.push.errors=UNAVAILABLE:0.1,INTERNAL:0.05,QUOTA_EXCEEDED:0.05"` | 호출마다 코드별 비율로 실패. 영구 코드표는 FCM 발송처와 같음(`UNREGISTERED` 등은 영구, 그 밖 일시) |
| `OUTAGE_SECONDS=60 OUTAGE_AFTER_STORM_S=15 [OUTAGE_CODE=UNAVAILABLE]` | 폭주 시작 15초 뒤 60초 동안 모든 발송 실패(하네스가 `outage-<label>.ctl` 에 시각을 적고 worker 가 1초마다 읽음) |
| `CHAOS=kill-worker CHAOS_AFTER_STORM_S=25 RESTART_AFTER_S=8` | 폭주 시작 25초 뒤 worker `kill -9`, 8초 뒤 같은 인자로 재기동(`SPLIT=1` 만) |
| `CHAOS=redis-down REDIS_DOWN_S=90` | 위처럼 죽인 뒤 Redis 포트 1(닫힘)로 재기동해 90초 돌리고, 정상 종료 → 정상 인자로 재기동. 공유 st-redis 는 건드리지 않는다 |

- 기록 파일: `notifrows-*`(지우기 전 알림 행), `queue-*`(5초마다 보낼 때 된 PENDING·PENDING·PROCESSING·누적 SENT), `push-*`(가짜 발송처 접수 한 줄씩 — worker 를 죽였다 살려도 이어 써서 재기동을 넘는 중복을 센다), `events-*`(폭주·장애·kill 시각), `pids-*`(재기동한 worker 까지 칸 끝에 모두 끈다)
- `CHAOS` 칸은 worker 지표가 재기동으로 0 부터 다시 쌓인다 — `summarize.py` 는 누적이 줄었으면 끝 값을 재기동 뒤 몫으로 쓴다(#458). SLO 는 DB·접수 기록으로 본다
- 예: `SPLIT=1 MODE=subway RUN_TAG=storm STORM_LINES=1001,1002,1075,1005 runladder-env.sh 2000 on 10m 20`

## worker 여러 대 (#458)

`SPLIT=1` 에서 `WORKERS=N`(기본 1)이면 worker JVM 을 N 개 띄운다. worker i = 포트 `8080+i`, 지표 이름 `worker`(1번)·`worker2`·…,
스냅숏 `prom-<label>-worker.txt`·`-worker2.txt`…, 로그 `app-<label>-worker2.log`(재기동은 `-r` 꼬리). 결과 = `MULTI-WORKER.md`.

| 스위치(env) | 뜻 |
|---|---|
| `WORKERS=2` | worker 두 대. 2 이상이면 수집 리더 잠금(`journey.navigation.reeval.leader-lease.enabled`)을 자동으로 켠다 — 안 켜면 두 대가 다 "늘 리더"라 같이 수집한다 |
| `LEADER_LEASE=true\|false` | 위 자동값 덮기. 1대 기준선을 같은 조건으로 재려면 `LEADER_LEASE=true` |
| `CHAOS=kill-leader` | 폭주 시작 `CHAOS_AFTER_STORM_S` 뒤 **지금 리더인** worker(`journey_reeval_leader` 게이지 1)를 `kill -9`, `RESTART_AFTER_S` 뒤 재기동 |
| `CHAOS=kill-worker CHAOS_TARGET=2\|leader\|nonleader` | 대상 지정 kill(기본 1). `redis-down` 도 같은 대상 규칙 |
| `PG_CONTAINER=` (빈 값) | 하네스 psql 을 `docker exec` 대신 `POSTGRES_HOST/PORT` 주소로(원격 DB). Postgres CPU 표본(docker stats)은 빠진다 |

- 접수 기록은 worker 마다 `push-<label>-w<i>.tsv`. `notification_slo.py` 가 쉼표로 묶어 받아 합쳐 세고, 같은 행 번호가 두 파일에 나오면 "두 인스턴스 이상이 같은 행을 보냄" 으로 따로 센다
- `leader-<label>.tsv`: 1초마다 worker 별 리더 게이지·수집 성공 누적 + Redis 잠금 키(값 앞 8자·PTTL). `leader_check.py` → `leader-<label>.txt`(요약 끝에도 붙음): 리더 두 대 이상인 표본, 두 대가 7초 안에 같이 수집한 쌍, kill 뒤 다른 worker 가 리더가 되기까지·다음 수집 성공까지
- 예: `SPLIT=1 MODE=subway WORKERS=2 CHAOS=kill-leader RUN_TAG=w2-kl STORM_LINES=1001,1002,1075,1005 runladder-env.sh 2000 on 10m 20`

## 발송 용량 (#461)

relay 만 떼어 알림 **양**을 계단식으로 올린다. 평가기·지하철 API 를 거치지 않고 생성기(`relay_load_gen.py`)가
`journey_notification` 에 PENDING 행을 정해진 속도로 직접 넣는다(open-loop). 발송 경로는 운영 그대로, 가짜 발송처 지연만 바꾼다.

```bash
# env(POSTGRES_*, REDIS_*) 를 실은 셸에서, 저장소 루트, bootJar 빌드 뒤
PUSH_MEAN_MS=60 load/ladder/run_relay_capacity.sh A-r1 "10:120,25:120,50:120,100:120,200:120,400:120,800:120"
PUSH_MIN_MS=2000 PUSH_MAX_MS=5000 load/ladder/run_relay_capacity.sh B-r1 "5:120,10:120,20:120,30:120,35:120,40:120,80:120"
PUSH_MIN_MS=2000 PUSH_MAX_MS=5000 PULL_WORKERS=32 load/ladder/run_relay_capacity.sh Bp-r1 "2:120,5:120,7:120,9:120,10:120,20:120"
```

- 순서: 사다리 표지 지우기 → `RelayCapacitySeedIT` 로 여정·토큰 심기(`SESSIONS`, 기본 2,000) → worker JVM 하나(평가기·재평가·수집기·피로 정책 끔)
  → 워밍업(`WARMUP`, 기본 10건/초 60초, 단계 0) → 단계들(단계 120초, 대기열이 늘어난 단계에서 멈춤 — `STOP_ON_KNEE=0` 이면 끝까지) → 대기열이 빌 때까지(`DRAIN_MAX_S`) → 행 덤프 → 지우기 → `relay_capacity.py` 표.
- 산출물(`out/`): `gen-`(일정표 대비 실제 적재 시각) `rows-`(행별 epoch) `queue-`(5초) `cpu-`(5초, JVM·Postgres) `prom-`(10초, hikari·heartbeat·GC·스레드·중복)
  `pgss-`(단계별 상위 20 문장) `summary-`(표) `leftover-`.
- 단계 지표는 행의 `created_at` 으로 귀속한다. lag = `sent_at − created_at`(relay 몫만, 깨우기 신호가 없어 하한 약 1초).
  한계점 = (a) 초당 SENT < 들어온 속도 × 0.95 이고 대기열 기울기 > 0, (b) 잃음 > 0 중 처음 걸린 단계. 끝-끝 긴급 32초 초과는 따로 표시.
- 가짜 발송처 접수 TSV 는 기본 끔(`PUSH_LOG=1` 로 켬) — 줄마다 파일을 여닫는 synchronized 라 수백 건/초에서 하네스가 병목이 된다.
