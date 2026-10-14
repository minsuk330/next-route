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
