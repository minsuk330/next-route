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
- **신뢰 경계**: Loki(`auth_enabled:false`)·Prometheus 무인증. 관측 default 네트워크에 Alloy/Grafana 외 컨테이너 추가 금지

## 검증 (배포 후)

- Grafana `{env="prod"}` 쿼리 → 4개 컨테이너 로그
- `{app="app", level="ERROR"} | json` 필드 검색
- Prometheus targets 전부 up (`/targets`)
- 외부에서 3000/3100/9090/8081 직접 접근 실패
- `https://grafana.refit-100.site` 로그인
- `docker compose restart grafana` 후 대시보드/데이터소스 유지
