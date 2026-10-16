# worker 여러 대 — 하네스 준비와 H1(정합성) 노트북 확인 (#458)

**결론: worker 2대에서도 알림은 한 번만 나갔고(중복 0, 경로 버전당 울림 1), 수집은 늘 한 대만 했다. 리더를 죽이면 약 10초 뒤 다른 대가 잠금을 잡고, 약 17초 뒤 첫 수집에 성공했다(수집 공백 21초).**
발송 중인 worker 를 죽여도 그 worker 가 쥐고 있던 행은 살아 있는 쪽이 회수해 다시 보냈고, 잃은 알림은 0이었다.

비유하면 "열쇠 하나로 문을 여는 경비 두 명"이다. 열쇠(Redis 잠금 `lease:realtime-watch`)를 쥔 사람만 순찰(지하철 수집)을 돌고, 그 사람이 쓰러지면 열쇠의 유효기간(10초)이 끝난 뒤 다른 사람이 집어 든다. 편지 배달(알림 발송)은 둘이 나눠 하지만, 배달할 편지는 DB 에서 한 장씩 "내 것" 도장을 찍고 가져가므로 같은 편지를 두 번 들고 나가지 않는다.

> **이 문서가 판정하지 않는 것**: 처리량(H2)·공유 자원 병목(H3). 노트북 한 대에서 api·worker 2·Postgres·Redis·k6·프록시가 CPU 를 나눠 쓰므로(시스템 CPU 사용률 표본 평균 0.7~0.8, 최대 1.0) 처리량 숫자는 기계 경합이 가린다. 그건 이슈 #458 의 클라우드 VM 단계 몫이다.

## 무엇을 쟀나

- 날짜·시간대: 2026-10-16 11:22~12:09 KST(지하철 운행 중), 실 서울 지하철 도착 API(프록시 경유), 같은 시간대에 네 칸을 연달아
- 공통: `SPLIT=1 MODE=subway`, 여정 2,000, 10분(워밍업 3분 → 측정 창 약 432초), 폭주 `STORM_LINES=1001,1002,1075,1005 STORM_AT_S=120 STORM_SECONDS=240`, 리더 잠금 켬(`LEADER_LEASE=true` — 1대 기준선도 같은 조건), relay PULL·워커 128(main 기본값)
- 장애: 폭주 시작 25초 뒤 `kill -9`, 8초 뒤 같은 인자로 재기동
- 실행: `/Users/wa/.claude/jobs/8e8c6a78/tmp/run458.sh "w1-base:1:none:fast" "w2-none:2:none:fast" "w2-killleader:2:kill-leader:fast" "w2-killslow:2:kill-worker:slow"`(스크래치 사슬 — `runladder-env.sh` 로 `run_ladder.sh` 를 부름)

## 결과

| 칸 | worker | 장애 | 울림 SENT | 중복(행 번호·업무 키·인스턴스 사이) | 경로 버전당 울림 2번+ (DB·발송처) | 잃음 | 리더 2대 이상 표본 | 두 대 같이 수집(7초 안) | 리더 kill → 다른 대 리더 / 다음 수집 / 수집 공백 |
|---|---|---|---|---|---|---|---|---|---|
| w1-base | 1 | 없음 | 802 | 0 · 0 · — | 0 · 0 | 0 | 0 / 403 | 0 | — |
| w2-none | 2 | 없음 | 795 | 0 · 0 · 0 | 0 · 0 | 0 | 0 / 375 | 0 | — |
| w2-killleader | 2 | 리더 kill | 801 | 0 · 0 · 0 | 0 · 0 | 0 | 0 / 365 | 0 | **10s / 17s / 21s** |
| w2-killslow | 2 | 발송 중 비리더 kill + 느린 FCM(2~5초) | 818 | 0 · 0 · 0 | 0 · 0 | 0 | 0 / 346 | 0 | — (리더 그대로 수집) |

칸별 보조 숫자:

| 칸 | 인스턴스별 접수(w1/w2) | 수집 성공(worker/worker2) | 리더 없는 가장 긴 연속 | PROCESSING 최대 | 접수까지 시도 |
|---|---|---|---|---|---|
| w1-base | 1,007 / — | 28 / — | 0s | 15 | 1회 802 |
| w2-none | 499 / 484 | 27 / 0 | 0s | 7 | 1회 795 |
| w2-killleader | 561 / 432 | 17 / 9 (kill 전 worker2 가 리더) | 8s | 7 | 1회 801 |
| w2-killslow | 805 / 179 | 25 / 0 | 0s | 244 | 1회 771 · 2회 47 |

- Redis 잠금 키는 모든 표본에서 존재했다(키 없던 표본 0). 주인은 장애 없는 칸에서 1개, 리더 kill 칸에서 2개(죽은 쪽 → 이어받은 쪽)
- 발송 중 kill(w2-killslow): 죽은 worker2 가 쥐고 있던 PROCESSING 행을 heartbeat 끊김으로 회수(로그 "heartbeat 가 끊긴 행 33건 회수" 등) → 47건이 두 번째 시도에 접수. 두 인스턴스가 같은 행을 보낸 일 0, 만료 뒤 발송 0
- 짧은 확인 칸 두 개(여정 100, 4분)에서도 리더 kill → 다른 대 리더 8~9초, 다음 수집 18~19초, 공백 23~25초로 같은 범위

### 인계 시간이 왜 이 정도인가

리더가 `kill -9` 로 죽으면 잠금을 풀지 못한다. 그래서 (1) Redis 키가 TTL(10초)로 저절로 사라질 때까지 기다리고, (2) 다른 worker 의 갱신 스레드가 TTL/3(약 3.3초)마다 시도하다 잡고, (3) 그 worker 의 수집 예약(15초 고정 지연)이 다음으로 돌 때 첫 수집을 한다. 상한은 대략 10 + 3.3 + 15 ≈ 28초. 잰 값(17~19초)은 그 안이다. 정상 종료(SIGTERM)라면 `@PreDestroy` 가 잠금을 바로 풀어 (1)이 사라진다.

## 하네스에 들어간 것

| 이슈 준비물 | 한 일 |
|---|---|
| 1. worker N대 | `WORKERS=N`(SPLIT=1): worker i = 포트 8080+i, 이름 worker·worker2…. 지표 스냅숏·CPU 표본·정리(pids 파일 "번호 pid", 칸 끝에 이 칸이 띄운 pid 만 최대 30초 기다렸다 -9)·`summarize.py`(JVM 별 절이 worker 수만큼) 확장. DB·Redis 는 앱이 원래 env(`POSTGRES_*`·`REDIS_*`)로 받는다. 하네스 psql 은 `PG_CONTAINER=`(빈 값)이면 주소로 붙는다 |
| 2. 중복 합산 | 가짜 발송처 접수 기록을 worker 마다 `push-<label>-w<i>.tsv` 로 쓰고 `notification_slo.py` 가 합쳐 센다 + "같은 행이 두 파일에 나옴"을 따로 센다. Redis 공유 집계는 만들지 않았다(아래 이유) |
| 3. 장애 대상 | `CHAOS=kill-leader`(리더 게이지 1 인 worker), `CHAOS_TARGET=번호\|leader\|nonleader`. 사건 이름에 대상·역할이 붙는다(`worker-kill9:w2:leader`). Redis 장애는 기존 `CHAOS=redis-down`(그 worker 만 닫힌 포트로 재기동)을 대상 규칙과 함께 유지 — st-redis 재시작은 하지 않는다 |
| 리더 지표 | `leader-<label>.tsv`(1초마다 worker 별 리더 게이지·수집 성공 누적 + Redis 키 값 앞 8자·PTTL) → `leader_check.py` |
| 4. pg_stat_statements | 칸마다 초기화·덤프 그대로 |

- 2대 이상이면 리더 잠금을 자동으로 켠다(`journey.navigation.reeval.leader-lease.enabled` — 코드 기본값 false). 안 켜면 두 대가 다 "늘 리더"라 같이 수집·같이 변화 감지를 한다
- 재기동한 worker 의 지표는 0 부터 다시 쌓이므로 `summarize.py` 차분이 음수가 되던 것을 "줄었으면 끝 값" 으로 바꿨다

## VM 단계에 필요한 것 (아직 안 함)

- 원격 기동: 지금은 worker 가 모두 localhost. VM 에선 worker 호스트 목록(`WORKER_HOSTS`)을 받아 ssh 로 jar 를 띄우고, 지표·표본 curl 대상을 `호스트:포트` 로 바꿔야 한다(`wport`·`JVMS` 가 그 자리)
- 접수 기록 파일이 각 VM 에 생긴다 → 칸 끝에 scp 로 모아 `notification_slo.py` 에 쉼표로 넘기면 된다(합산 방식은 그대로)
- 장애 주입: `kill -9 $(wpid i)` 를 ssh 로. 리더 찾기는 게이지 curl 이라 원격에서도 그대로
- psql: `PG_CONTAINER=` + `POSTGRES_HOST`. DB VM 의 CPU 는 docker stats 대신 기계 지표(node exporter 등)로
- Redis 재시작 장애: 공유 Redis 가 실험 전용이 되는 VM 에서는 진짜 재시작을 넣을 수 있다(노트북에선 st-redis 재시작 금지라 worker 한 대만 Redis 를 못 보는 흉내로 대신)
- 운영 비밀값을 올리지 않음: 지하철 API 키만 프록시 기계에
