# NextRoute Observability Stack

Grafana Alloy + Loki + Prometheus + Grafana + node-exporter. app 배포와 분리된 별도 compose 프로젝트.

## 구성

| 서비스 | 역할 | 노출 |
|---|---|---|
| Alloy | docker.sock으로 컨테이너 로그 수집 → Loki push | 미공개 |
| Loki | 로그 저장 (retention 30일) | 미공개 |
| Prometheus | 메트릭 스크랩 (app actuator + node) | 미공개 |
| node-exporter | 호스트 CPU/mem/disk/net | 미공개 |
| Grafana | 대시보드 | 127.0.0.1:3000 (nginx 경유만) |

로그 라벨은 `app`/`env`/`level`만 (저카디널리티). traceId·userId·path 등은 JSON 필드로만 → LogQL `| json` 검색.

## VPS 배포 절차

```bash
# 1. DNS: grafana.refit-100.site A레코드 → VPS IP (선행)

# 2. 설정 복사
rsync -a ops/observability/ vps:/root/apps/observability/

# 3. env 생성 (강한 admin 비밀번호)
ssh vps 'cd /root/apps/observability && cp env/grafana.env.example env/grafana.env && vi env/grafana.env'

# 4. 네트워크 이름 확인 (compose.yaml의 external name과 일치해야 함)
ssh vps 'docker network ls | grep nextroute'

# 5. 기동
ssh vps 'cd /root/apps/observability && docker compose up -d'
ssh vps 'cd /root/apps/observability && docker compose ps'   # 5개 healthy/running

# 6. nginx + certbot
ssh vps 'cp /root/apps/observability/nginx/grafana.refit-100.site.conf /etc/nginx/sites-available/ \
  && ln -sf /etc/nginx/sites-available/grafana.refit-100.site.conf /etc/nginx/sites-enabled/ \
  && nginx -t && systemctl reload nginx'
ssh vps 'certbot --nginx -d grafana.refit-100.site'
# certbot 후 생성된 설정에서 443 + HTTP→HTTPS redirect 확인

# 7. app 재배포 (management 포트 8081 + JSON 로깅 활성) — 로컬빌드 convention
```

## 장애 알림 (Grafana → Slack)

`grafana/provisioning/alerting/` 3파일. AI 분석 없이 Grafana 가 Slack 으로 바로 보낸다.

| 규칙 | 조건 | 근거(2026-10-26 실측) |
|---|---|---|
| 메모리 부족 | 남은 메모리 < 300MiB, 5분 | 14일 최저 255MiB. 바닥나면 OOM killer 가 OTP 부터 죽인다 |
| 컨테이너 다운 | `up{job=~"nextroute-app\|otp\|node"}` = 0, 3분 | 배포 재시작은 최대 2분 → 안 울림 |
| api 5xx 발생 | 5xx ≥ 3건 / 5분 | 7일 최대 5건(1회) |
| ERROR 로그 급증 | 앱별 ERROR ≥ 5건 / 5분 | 평시 하루 1건, 외부 API 503 때 21건/1분 |
| OTP 호출 실패 | OTP 호출 실패 ≥ 3건 / 10분 | 살아 있는데 응답 못 주는 경우. JVM 사망은 '컨테이너 다운' |
| 디스크 사용률 높음 / 거의 가득 | rootfs > 85% 10분(warning) / > 95% 5분(critical) | 2026-11-26 이미지·build cache 누적으로 90% 도달(알림 없었음). 관측 데이터는 30일 보존으로 정상 상태 |
| 여정 알림 늦음·유실 | 최근 1시간 예정 시각 기준 늦음(예정+60초 초과)·유실(DEAD·FAILED·만료 취소)·지연 중 ≥ 1건 | 주 수 건이라 비율 대신 한 건이라도(2026-11-26). 30일 22/22 정시 |
| 잠금화면 갱신 정시율 낮음 | 최근 1시간 10건 이상 && 정시율 < 95% | 30일 651/651, p95 2.25초 |

- 묶기: 같은 규칙은 한 메시지, 계속 울리면 1시간마다. 해소되면 RESOLVED 메시지
- 웹훅 URL: `env/grafana.env` 의 `SLACK_ALERT_WEBHOOK_URL` (provisioning 이 `$VAR` 치환)
- 반영: `rsync` 후 `docker compose up -d --no-deps --force-recreate grafana` (env 바뀌면 재생성해야 읽음). 규칙만 바꿨으면 재생성 대신 `POST /api/admin/provisioning/alerting/reload`
- 규칙 삭제: 파일에서 빼도 DB 에 남는다 → `deleteRules: [{orgId: 1, uid: ...}]` 로 지우고 reload
- 템플릿에 `$values`/`$labels` 금지 — provisioning 의 env 치환에 먹힌다. `.Values`/`.Labels` 사용
- 한계: VPS 자체가 죽으면 Grafana 도 같이 죽어 알림이 안 간다(외부 감시 별건)

## 배포 전 grep (management 포트 8081 격리 영향)

app actuator가 8080 → 8081로 이동. VPS에서 `:8080/actuator` 또는 `/actuator` 참조 확인:

```bash
ssh vps 'grep -rn "actuator\|:8080" /etc/nginx /root/apps/nextroute /etc/systemd/system 2>/dev/null | grep -v ":8080:8080"'
```

## 운영 주의

- **디스크**: retention 30일은 용량 상한이 아님. 로그 급증 시 초과 가능 → node 대시보드 Disk gauge 감시 (75% 경고/90% 위험)
- **메모리**: 스택 ~700MB 예산. `free -h`로 여유 확인, OOM 관찰
- **로그 유실 창**: docker json-file max-size 10m/max-file 5 = 컨테이너당 ~50MB만 원본. Alloy/Loki 장애 지속 시 회전분 복구 불가
- **docker.sock**: Alloy가 접근 = Docker 관리자 권한 동급. 단일 호스트 내부망 전용으로 수용. 강화 시 docker-socket-proxy (백로그)
- **신뢰 경계**: Loki(`auth_enabled:false`)·Prometheus 무인증. 관측 default 네트워크에 Alloy/Grafana/alert-analyzer 외 컨테이너 추가 금지. alert-analyzer 는 예외로 둔다 — 무인증 Loki/Prometheus 를 읽어야 하고, 공개 포트가 없어 외부에서 들어오는 길이 없는 1st-party 서비스다

## 검증 (배포 후)

- Grafana `{env="prod"}` 쿼리 → 4개 컨테이너 로그
- `{app="app", level="ERROR"} | json` 필드 검색
- Prometheus targets 전부 up (`/targets`)
- 외부에서 3000/3100/9090/8081 직접 접근 실패
- `https://grafana.refit-100.site` 로그인
- `docker compose restart grafana` 후 대시보드/데이터소스 유지

## 장애 알림 AI 분석기 (alert-analyzer)

Grafana 알림이 울리면 그 시각의 로그·메트릭을 모아 원인 가설을 만드는 서비스. 코드 `alert-analyzer/`, 플랜 `docs/plan/alert-analyzer/PLAN.md`.

- **운영 라이브(2026-11-03, `ANALYZER_DRY_RUN=false`)**: Claude(`claude-sonnet-5-5`)에 마스킹된 맥락을 보내 원인 가설을 받고, 근거 id 를 검증한 뒤 알림의 Slack 스레드에 답글을 단다(스레드를 못 찾으면 별도 메시지). 스레드는 Slack 연동 `text` 끝의 `fp: <fingerprint>` 줄로 찾는다(`contact-points.yaml`). `ANALYZER_DRY_RUN=true` 로 되돌리면 맥락만 수집해 로그(`{app="alert-analyzer"}`)로 남긴다
- 실패해도 알림은 그대로: LLM 오류·거절·시간 초과는 스레드에 "AI 원인 분석 실패" 한 줄, Slack 오류는 로그만. 하루 상한(`ANALYZER_DAILY_LIMIT`, 기본 30) 넘으면 하루 한 번 안내
- 알림 경로와 분리: Grafana 가 Slack 과 분석기에 따로 보낸다. 분석기가 죽어도 Slack 알림은 간다
- 수집: 로거별 WARN/ERROR 정확 집계(창 vs 직전 60분) → NEW/SPIKE 로거만 작은 표본, Prometheus 스냅샷, 재시작(`process_start_time_seconds`)
- 마스킹: 밖으로 나가는 모든 문자열. **운영 로그에 서울 지하철 API 키가 경로째 찍혀 있다**(`SeoulSubwayApiAdapter` 의 `uri.getPath()`) — 경로 세그먼트 규칙이 지운다
- 자원: `mem_limit 192m` + `oom_score_adj 500`(호스트가 바닥나면 OTP 대신 이게 먼저 죽게), 포트 공개 없음. idle RSS 약 88MiB(anthropic SDK 포함, PR-1 은 45MiB)
- env: `env/alert-analyzer.env` (`env/alert-analyzer.env.example` 참고). `ANALYZER_WEBHOOK_TOKEN` 은 `env/grafana.env` 에도 같은 값으로 넣는다(contact point `slack-alerts` 의 webhook 연동이 참조, resolve 는 안 보냄). `ANALYZER_DRY_RUN=false` 는 `ANTHROPIC_API_KEY`·`SLACK_BOT_TOKEN`·`SLACK_CHANNEL_ID` 가 없으면 기동을 거부한다. Slack 봇 권한 `chat:write`, `channels:history`(비공개 채널이면 `groups:history`), 채널에 봇 초대 필요
- 끄기: `contact-points.yaml` 에서 `alert-analyzer` 연동을 지우고 grafana 재생성(또는 provisioning reload). Slack 알림은 영향 없음
- 반영: 이미지는 main push 때 GHCR(`nextroute-alert-analyzer`). VPS 는 compose 의 서비스 블록만 추가(파일 통째 덮기 금지) 후 `docker compose up -d --no-deps alert-analyzer`
- 평가: `cd alert-analyzer && ANTHROPIC_API_KEY=… uv run python -m eval.run_eval` 은 예상 비용만 출력, `--yes` 를 붙여야 실제 호출(6건 약 $0.25 이하). 규칙 비교선 대비 적중·근거 인용·없는 근거 수를 낸다
- 평가용 fixture 캡처: `docker exec alert-analyzer python -m scripts.capture_fixture --at <ISO+09:00> --name <id> --alertname <이름>` → 커밋 전 `python3 scripts/check_no_secrets.py --env '/root/apps/*/env/*.env' <파일>`

## 사용 통계 대시보드 (docs/plan/usage-stats)

`grafana/dashboards/usage.json` "사용 통계" — DAU·WAU·MAU, 신규 설치(DEVICE 계정), 여정 시작·상태·사용자, 경로 검색 요청(Prometheus), 앱 빌드, 재방문 코호트, 사용자별(md5 6자) 표.

- 원천: `user_daily_activity`(V74, 사용자당 하루 1행), `auth_identity`, `journey_session`, Prometheus `/api/route/search`
- DB 접근: 데이터소스 `nextroute-pg` → `postgres:5432`(grafana 가 `nextroute` 네트워크에 붙어야 함). 계정 `grafana_ro` 는 위 3표 SELECT 만, 읽기 전용 트랜잭션, 쿼리 10초, 연결 3
- 계정 생성: `postgres/grafana_ro.sql` 머리말 명령. 비밀번호는 `env/grafana.env` 의 `GRAFANA_PG_PASSWORD`
- 시간대: 운영 naive timestamp(created_at)는 KST 벽시계라 쿼리에서 `AT TIME ZONE 'Asia/Seoul'` 로 바꾼다
- DAU 는 V74 배포일부터 쌓인다(과거 소급 불가)
- 토스(앱인토스) 사용자는 모든 패널에서 뺀다: 토스 계정(`auth_identity.provider='TOSS'`)이 있는 user_id 제외. 신규 설치 = 새 DEVICE 계정. 검색 요청(Prometheus)만 사용자를 몰라 못 뺀다
- 대시보드 JSON 은 `grafana/usage_dashboard.py` 로 생성한다(손으로 고치지 말고 생성기 수정 후 재생성)

## Tempo (2026-11-04 운영에서 내림)
운영 VPS 는 메모리 절약(112MB)으로 Tempo 를 내렸다: `observability/compose.yaml` 의 tempo 에 `profiles: ["traces"]`, grafana `depends_on` 에서 제거, 앱 env `MANAGEMENT_OTLP_TRACING_EXPORT_ENABLED=false`(트레이싱은 켜 둬 로그의 `traceId` 는 남는다). 2026-11-26 부터 저장소 compose 도 같은 상태(설정 기준 = 저장소, VPS 는 rsync 로만 반영). 로컬에서 트레이스가 필요하면 `--profile traces` 로 켠다. 다시 켜기 = `docker compose --profile traces up -d tempo` + 앱 env 줄 삭제 후 api·worker 재생성. Loki 의 TraceID 파생 링크는 내려 있는 동안 죽은 링크다.
