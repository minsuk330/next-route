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

- 운영은 아직 **DRY_RUN**: 맥락만 수집해 로그(`{app="alert-analyzer"}`)로 남긴다. `ANALYZER_DRY_RUN=false` 면 Claude(`claude-sonnet-5-5`)에 마스킹된 맥락을 보내 원인 가설을 받고, 근거 id 를 검증한 뒤 알림의 Slack 스레드에 답글을 단다(스레드를 못 찾으면 별도 메시지). 켜는 것은 PR-3
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
