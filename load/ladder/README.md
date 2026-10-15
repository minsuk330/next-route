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
