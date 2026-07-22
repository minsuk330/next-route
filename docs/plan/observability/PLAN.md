# PLAN — 관측 스택 (Grafana + Loki + Alloy + Prometheus)

- 스펙: `docs/superpowers/specs/2026-07-22-observability-stack-design.md` (gitignored, 로컬)
- 티어: L
- 브랜치: `feat/observability-stack` (fresh main 기준)
- 참조 메모리: `reference-vps-deploy` (VPS 배포 convention)

## 목표

VPS에 셀프호스팅 관측 스택 구축. 로그(Loki) + 메트릭(Prometheus) → Grafana 대시보드,
`grafana.refit-100.site` HTTPS 접근. 설정 전체를 repo `ops/observability/`에 코드로 관리.

## 전제 (조사 완료된 사실)

- VPS: ssh `vps`, compose 프로젝트 `/root/apps/nextroute` → docker network 이름 `nextroute_default`
- 컨테이너: nextroute-app-1(8080 공개), nextroute-postgres-1, nextroute-redis-1, nextroute-nextroute-ml-serve-1
- 호스트 nginx 80/443, certbot 운용 중 (`api.refit-100.site`)
- RAM 여유 ~5.8Gi, 디스크 여유 27G
- Spring Boot 3.5.11, `spring-boot-starter-actuator` 이미 의존성에 있음 (`build.gradle:32`)
- `SecurityConfig.java:47` `anyRequest().permitAll()` — 그래서 actuator는 별도 management 포트(8081, 미공개)로 격리
- app 콘솔 로그가 docker json-file로 감 → Alloy가 이걸 수집. 파일 로그(`/logs`)는 텍스트 유지

## Phase 1 — repo PR (`feat/observability-stack`)

### 1-1. build.gradle

- [ ] `runtimeOnly 'io.micrometer:micrometer-registry-prometheus'` 추가 (dependencies 블록)

### 1-2. src/main/resources/application-prod.yaml

- [ ] 추가 (기존 키와 병합 주의):

```yaml
management:
  server:
    port: 8081
  endpoints:
    web:
      exposure:
        include: prometheus,health,info

logging:
  structured:
    format:
      console: logstash
```

- 주의: `management.server.port` 분리 시 `/actuator/*`는 8080에서 사라짐 — `:8080/actuator` 또는 `/actuator` 참조를 **repo뿐 아니라 VPS의 nginx conf, systemd/cron, 배포 스크립트, compose healthcheck까지** grep 후 있으면 8081로 갱신 (codex 지적: grep 범위 확대)
- 주의: `logging.structured.format.console`은 콘솔만 JSON화. `LOGGING_FILE_NAME` 파일 로그는 영향 없음(텍스트 유지) — 로컬 확인
- dev/로컬 프로필 변경 금지

### 1-3. ops/observability/ 디렉토리 신설

구조:

```
ops/observability/
├── compose.yaml
├── loki/loki-config.yaml
├── alloy/config.alloy
├── prometheus/prometheus.yml
├── grafana/provisioning/datasources/datasources.yaml
├── grafana/provisioning/dashboards/dashboards.yaml
├── grafana/dashboards/logs.json
├── grafana/dashboards/spring.json
├── grafana/dashboards/node.json
├── nginx/grafana.refit-100.site.conf   # VPS /etc/nginx/sites-available/ 복사용
├── env/grafana.env.example             # 실제 env/grafana.env는 gitignore
└── README.md                           # 배포 절차 (Phase 2 요약)
```

- [ ] `.gitignore`에 `ops/observability/env/grafana.env` 추가

#### compose.yaml (핵심 — 구현 시 이미지 최신 안정 태그로 고정할 것)

```yaml
services:
  loki:
    image: grafana/loki:3.5.0          # 구현 시 최신 3.x 확인
    command: -config.file=/etc/loki/loki-config.yaml
    volumes:
      - ./loki/loki-config.yaml:/etc/loki/loki-config.yaml:ro
      - loki-data:/loki
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:3100/ready"]
      interval: 15s
      timeout: 5s
      retries: 5
    restart: always
    logging: &logrotate
      driver: json-file
      options: { max-size: "10m", max-file: "5" }

  alloy:
    image: grafana/alloy:v1.9.0        # 구현 시 최신 확인
    command: run /etc/alloy/config.alloy --storage.path=/var/lib/alloy/data
    volumes:
      - ./alloy/config.alloy:/etc/alloy/config.alloy:ro
      - /var/run/docker.sock:/var/run/docker.sock:ro
      - alloy-data:/var/lib/alloy/data
    depends_on: [loki]
    restart: always
    logging: *logrotate

  prometheus:
    image: prom/prometheus:v3.4.0      # 구현 시 최신 확인
    command:
      - --config.file=/etc/prometheus/prometheus.yml
      - --storage.tsdb.retention.time=30d
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - prom-data:/prometheus
    networks: [default, nextroute]
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:9090/-/ready"]
      interval: 15s
      timeout: 5s
      retries: 5
    restart: always
    logging: *logrotate

  node-exporter:
    image: prom/node-exporter:v1.9.1
    command: [--path.rootfs=/host]
    pid: host
    volumes:
      - /:/host:ro,rslave
    restart: always
    logging: *logrotate

  grafana:
    image: grafana/grafana:12.0.0      # 구현 시 최신 확인
    ports:
      - "127.0.0.1:3000:3000"          # loopback만 — nginx 경유 전용
    env_file: [env/grafana.env]
    volumes:
      - ./grafana/provisioning:/etc/grafana/provisioning:ro
      - ./grafana/dashboards:/var/lib/grafana/dashboards:ro
      - grafana-data:/var/lib/grafana
    depends_on:
      loki: { condition: service_healthy }
      prometheus: { condition: service_healthy }
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://localhost:3000/api/health"]
      interval: 15s
      timeout: 5s
      retries: 5
    restart: always
    logging: *logrotate

networks:
  nextroute:
    external: true
    name: nextroute_default

volumes:
  loki-data:
  prom-data:
  grafana-data:
  alloy-data:
```

- 포트 정책: grafana만 127.0.0.1:3000, 나머지 전부 미공개
- alloy는 docker.sock만으로 로그 수집 (loki.source.docker는 Docker API 사용, containers 디렉토리 마운트 불필요)

#### alloy/config.alloy

```alloy
discovery.docker "containers" {
  host = "unix:///var/run/docker.sock"
}

discovery.relabel "containers" {
  targets = discovery.docker.containers.targets
  rule {
    source_labels = ["__meta_docker_container_label_com_docker_compose_service"]
    target_label  = "app"
  }
  rule {
    target_label = "env"
    replacement  = "prod"
  }
}

loki.source.docker "docker_logs" {
  host       = "unix:///var/run/docker.sock"
  targets    = discovery.relabel.containers.output
  labels     = {}   // 필수 argument (Alloy reference). relabel 라벨은 targets로 유입, 여기선 정적 추가 없으면 빈 맵
  forward_to = [loki.process.extract_level.receiver]
}

loki.process "extract_level" {
  stage.json {
    expressions = { level = "level" }
  }
  stage.labels {
    values = { level = "" }
  }
  forward_to = [loki.write.default.receiver]
}

loki.write "default" {
  endpoint {
    url = "http://loki:3100/loki/api/v1/push"
  }
}
```

- 라벨은 `app`(compose service), `env`, `level`(app JSON만 추출됨)만. 그 외 라벨 승격 금지
- 비-JSON 라인(postgres/redis/ml)은 stage.json 실패해도 라인 자체는 그대로 적재됨 — 정상
- 구현 시 Alloy 공식 문서로 문법 검증 (`alloy fmt` 또는 컨테이너 기동 로그로 확인)

#### loki/loki-config.yaml (Loki 3.x 단일 바이너리, filesystem, retention 30d)

```yaml
auth_enabled: false

server:
  http_listen_port: 3100

common:
  path_prefix: /loki
  replication_factor: 1
  ring:
    kvstore:
      store: inmemory
  storage:
    filesystem:
      chunks_directory: /loki/chunks
      rules_directory: /loki/rules

schema_config:
  configs:
    - from: 2026-01-01
      store: tsdb
      object_store: filesystem
      schema: v13
      index:
        prefix: index_
        period: 24h

compactor:
  working_directory: /loki/compactor
  retention_enabled: true
  delete_request_store: filesystem

limits_config:
  retention_period: 720h   # 30d
```

- **retention_enabled + retention_period 필수** (기본 off → 무한 증가)
- 구현 시 Loki 3.x 문서로 키 이름 검증 후 기동 로그에서 config 에러 없는지 확인

#### prometheus/prometheus.yml

```yaml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: prometheus
    static_configs: [{ targets: ["localhost:9090"] }]

  - job_name: node
    static_configs: [{ targets: ["node-exporter:9100"] }]

  - job_name: nextroute-app
    metrics_path: /actuator/prometheus
    static_configs: [{ targets: ["app:8081"] }]
```

- `app:8081`은 nextroute_default 네트워크 경유 (prometheus 서비스만 양쪽 네트워크 소속)

#### grafana provisioning

- [ ] `datasources.yaml`: Loki(`http://loki:3100`), Prometheus(`http://prometheus:9090`), Prometheus를 default로
- [ ] `dashboards.yaml`: `/var/lib/grafana/dashboards` 폴더 프로바이더
- [ ] 대시보드 JSON 3개 커밋:
  - `node.json`: community "Node Exporter Full" (ID 1860) 다운로드, datasource 변수를 프로비저닝된 Prometheus uid로 치환
  - `spring.json`: community "JVM (Micrometer)" (ID 4701) 기반 + HTTP 패널(요청률 `http_server_requests_seconds_count`, p95 `histogram_quantile`, 5xx율) 추가
  - `logs.json`: 자작 — 변수 `app`, `level`; 패널: 로그 스트림(logs panel), 레벨별 로그량 시계열(`sum by (level) (count_over_time({env="prod"}[$__interval]))`), ERROR 최근 목록

#### env/grafana.env.example

```
GF_SECURITY_ADMIN_USER=admin
GF_SECURITY_ADMIN_PASSWORD=CHANGE_ME
GF_USERS_ALLOW_SIGN_UP=false
GF_AUTH_ANONYMOUS_ENABLED=false
GF_SERVER_ROOT_URL=https://grafana.refit-100.site
```

#### nginx/grafana.refit-100.site.conf

```nginx
server {
    server_name grafana.refit-100.site;
    listen 80;

    location / {
        proxy_pass http://127.0.0.1:3000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        # Grafana live (websocket)
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }
}
```

- 443은 certbot이 자동 추가 (`certbot --nginx -d grafana.refit-100.site`)

### 1-4. 검증 (Phase 1)

- [ ] `./gradlew compileJava` 통과
- [ ] `./gradlew test` 통과 (관련 실패 없어야 — 기존 무관 실패는 기록)
- [ ] 로컬에서 `SPRING_PROFILES_ACTIVE=prod`로 부팅 불가(외부 의존) 시:
      `./gradlew bootRun --args='--logging.structured.format.console=logstash --management.server.port=8081'` 등 dev 기반으로
      콘솔 JSON 출력 + `curl localhost:8081/actuator/prometheus` 200 확인
- [ ] `docker compose -f ops/observability/compose.yaml config` 문법 통과
- [ ] 플랜 파일(`docs/plan/observability/`) 포함 커밋 → PR 생성 (base main)

## Phase 2 — VPS 배포 (PR 머지 후)

- [ ] **(사용자 수동)** DNS A레코드 `grafana.refit-100.site` → VPS IP
- [ ] repo `ops/observability/` → VPS `/root/apps/observability/` 복사 (rsync 또는 git pull 후 cp)
- [ ] VPS에서 `env/grafana.env` 생성 (강한 admin 비밀번호, example 복사 후 수정)
- [ ] `docker compose up -d` (observability) → 5개 컨테이너 healthy/기동 확인
- [ ] 내부 검증:
  - `curl -s localhost:3000/api/health` (VPS 로컬)
  - Loki: `docker exec` 또는 내부 curl로 `/loki/api/v1/labels`에 `app`, `env` 존재
  - Prometheus targets: node up 확인 (app은 재배포 전 down 정상)
- [ ] nginx: `nginx/grafana.refit-100.site.conf` → `/etc/nginx/sites-available/` + symlink → `nginx -t` → reload
- [ ] `certbot --nginx -d grafana.refit-100.site` → **생성된 nginx 설정에서 443 server block + HTTP→HTTPS redirect 실제 적용 확인** (codex: certbot 자동 추가에 맹신 금지) → HTTPS 접속 확인
- [ ] app 재배포 (기존 로컬빌드 convention — `reference-vps-deploy` 메모리 참조) → 8081 actuator 활성
- [ ] 주의: 재배포 전 VPS compose/스크립트에서 `:8080/actuator` 참조 grep — 있으면 8081로 수정

## 최종 검증 체크리스트

- [ ] Grafana `{env="prod"}` 쿼리로 4개 컨테이너(app/postgres/redis/nextroute-ml) 로그 확인
- [ ] `{app="app", level="ERROR"}` 라벨 필터 + `| json` 필드 검색 동작
- [ ] Prometheus targets 전부 up (nextroute-app 포함)
- [ ] 대시보드 3개 데이터 표시
- [ ] 외부(로컬 맥)에서 `curl http://<VPS_IP>:3000 / :3100 / :9090 / :8081` 전부 접속 실패 확인
- [ ] `https://grafana.refit-100.site` 로그인 성공, HTTP→HTTPS 리다이렉트
- [ ] `docker compose restart grafana` 후 대시보드/데이터소스 유지
- [ ] `free -h`로 메모리 여유 확인 (스택 ~700M 예산 내)

## 리스크 / 롤백 (codex 리뷰 반영)

- app 콘솔 JSON 전환으로 `docker logs` 직접 가독성 하락 → Grafana가 대체. 문제 시 prod yaml에서 `logging.structured` 제거만으로 롤백
- management 포트 분리로 기존 8080 healthcheck 깨질 수 있음 → Phase 1-2/Phase 2 grep 단계(범위 확대)에서 선제 확인
- **Alloy docker.sock 접근 = 호스트 Docker 관리자 권한 동급** — `:ro`는 API를 읽기전용으로 제한하지 않음. 단일 호스트 내부망 전용·포트 미공개로 수용. 향후 강화 시 docker-socket-proxy 도입 (스코프 외 백로그)
- **신뢰 경계 명시**: Loki(`auth_enabled:false`)·Prometheus는 무인증 — 관측 compose default 네트워크에 Alloy/Grafana 외 컨테이너 추가 금지. app actuator 8081은 `nextroute_default` 네트워크 내부에 노출됨(다른 컨테이너 침해 시 접근 가능) — 단일 신뢰 도메인으로 수용
- **디스크: retention 30d는 용량 상한이 아님** — 27G 여유에서 로그량 급증 시 초과 가능. 운영 기준으로 `df` 경보 또는 최소 여유공간 모니터링 추가 (초기 운영 체크에 포함). 메모리도 ~700M는 낙관치 — `free -h`/OOM 관찰
- **로그 유실 창**: docker json-file `max-size:10m/max-file:5` = 컨테이너당 ~50MB만 원본 보존. Alloy/Loki 장애 지속 시 회전분 복구 불가 (수용, 실시간 관측이 목적)
- Loki/Alloy 설정 키는 버전에 따라 다름 — 구현 세션에서 공식 문서 대조 필수 (특히 `loki.source.docker` labels, retention 키)
