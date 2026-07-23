# PLAN — Tempo 분산 트레이싱

- 스펙: `docs/superpowers/specs/2026-07-23-tempo-tracing-design.md` (gitignore, 로컬)
- 티어: M
- 브랜치: `feat/tempo-tracing` (fresh main 기준)
- 선행: 관측 스택 라이브(PR #79 머지). 참조 메모리 `project-observability`, `reference-vps-deploy`

## 목표

기존 관측 스택(Loki+Prometheus+Grafana)에 Tempo 트레이싱 추가 → 관측 3축 완성. 자동계측(HTTP 서버 + RestClient 발신) 100% 샘플링, 로그↔트레이스 양방향 상관연결.

## 전제 (조사 완료)

- Spring Boot 3.5.11, actuator + micrometer-prometheus 존재(build.gradle:32-33)
- app management 포트 8081(미공개), prod 콘솔 로그 logstash JSON
- `ApiClientConfig.java`: RestClient 4빈이 정적 `RestClient.builder()` 사용(§1-3 수정) — `restTemplate`(line 32)은 주입 RestTemplateBuilder라 이미 계측
- observability compose: `/root/apps/observability/`, 네트워크 external `nextroute_default`
- app→백엔드는 `nextroute_default` 경유(Prometheus가 app:8081 스크랩 패턴)

## Phase 1 — repo PR (`feat/tempo-tracing`)

### 1-1. build.gradle
- [ ] dependencies에 추가:
```gradle
implementation 'io.micrometer:micrometer-tracing-bridge-otel'
implementation 'io.opentelemetry:opentelemetry-exporter-otlp'
```
- 버전 명시 안 함(Spring Boot BOM). 빌드 후 의존성 확인

### 1-2. src/main/resources/application-prod.yaml
- [ ] 기존 `management:` 블록에 tracing/otlp 추가:
```yaml
  tracing:
    sampling:
      probability: 1.0
  otlp:
    tracing:
      endpoint: http://tempo:4317
```
- 로그 상관연결 추가설정 없음(bridge-otel MDC 자동주입). dev/로컬 변경 없음

### 1-3. ApiClientConfig.java (외부 span 활성, 핵심)
4개 RestClient 빈을 주입 `RestClient.Builder`에서 빌드. timeout/requestFactory/mTLS 유지, 빌더 출처만 교체.
- [ ] `restClient()` → `restClient(RestClient.Builder builder)`, `RestClient.builder()` → `builder` (line 45)
- [ ] `mlPredictorRestClient(MlPredictorProperties)` → `RestClient.Builder builder` 파라미터 추가 (line 56)
- [ ] `busRealtimeRestClient()` → `busRealtimeRestClient(RestClient.Builder builder)` (line 67)
- [ ] `tossRestClient(TossLoginProperties props)` → `RestClient.Builder builder` 추가, **baseUrl/requestFactory(mTLS) 유지** (line 89)
- `@Primary`/`@Bean`/timeout 전부 유지

### 1-4. ops/observability/compose.yaml
- [ ] `tempo` 서비스 추가:
```yaml
  tempo:
    image: grafana/tempo:2.7.0
    command: -config.file=/etc/tempo/tempo.yaml
    volumes:
      - ./tempo/tempo.yaml:/etc/tempo/tempo.yaml:ro
      - tempo-data:/var/tempo
    networks:
      - default
      - nextroute
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:3200/ready || exit 1"]
      interval: 15s
      timeout: 5s
      retries: 5
    restart: always
    logging: *logrotate
```
- [ ] `volumes:`에 `tempo-data:` 추가
- [ ] grafana `depends_on`에 `tempo: { condition: service_healthy }` 추가

### 1-5. ops/observability/tempo/tempo.yaml (신규)
```yaml
server:
  http_listen_port: 3200
distributor:
  receivers:
    otlp:
      protocols:
        grpc:
          endpoint: 0.0.0.0:4317
storage:
  trace:
    backend: local
    local:
      path: /var/tempo/blocks
    wal:
      path: /var/tempo/wal
compactor:
  compaction:
    block_retention: 336h
```
- 구현 시 Tempo 2.x 문서 키 검증 + docker run 기동 로그로 config 파싱 확인

### 1-6. grafana/provisioning/datasources/datasources.yaml
- [ ] Tempo 데이터소스 추가:
```yaml
  - name: Tempo
    uid: tempo
    type: tempo
    access: proxy
    url: http://tempo:3200
    jsonData:
      tracesToLogsV2:
        datasourceUid: loki
        filterByTraceID: true
```
- [ ] 기존 Loki 데이터소스에 derivedFields 추가:
```yaml
    jsonData:
      derivedFields:
        - name: TraceID
          matcherType: regex
          matcherRegex: '"traceId":"(\w+)"'
          url: '$${__value.raw}'
          datasourceUid: tempo
```
- `$${` 이중 달러(compose 치환 회피)

### 1-7. 검증 (Phase 1)
- [ ] `./gradlew compileJava` 통과
- [ ] `./gradlew dependencies --configuration runtimeClasspath | grep -iE "micrometer-tracing|opentelemetry"` 유입 확인
- [ ] `./gradlew test` — 기존 실패 10건(baseline 동일)만, 신규 실패 0
- [ ] `cd ops/observability && cp env/grafana.env.example env/grafana.env && docker compose config -q; rm env/grafana.env`
- [ ] Tempo config docker run 검증
- [ ] 플랜 파일 포함 커밋 → PR(base main)

## Phase 2 — VPS 배포 (PR 머지 후)

- [ ] `ops/observability/` → VPS `/root/apps/observability/` 재동기화(env/grafana.env 제외)
- [ ] `ssh vps 'cd /root/apps/observability && docker compose up -d'` — tempo 신규 + grafana 재생성
- [ ] tempo healthy + grafana 재기동 확인
- [ ] app 재배포: 로컬 `./gradlew bootJar -x test` → `scp build/libs/nextroute-0.0.1-SNAPSHOT.jar vps:/root/apps/nextroute/app.jar` → `ssh vps 'cd /root/apps/nextroute && ./deploy.sh'`

## 최종 검증

- [ ] `docker exec observability-tempo-1 wget -qO- localhost:3200/ready` → ready
- [ ] `docker logs nextroute-app-1 | grep -m1 traceId` → traceId/spanId 필드
- [ ] Toss mTLS 회귀 없음: `docker logs nextroute-app-1 | grep TOSS` → "mTLS SSLContext 구성 완료"
- [ ] 경로검색 API 1건 → Grafana Explore(Tempo) → **odsay/tmap/ml/버스 자식 span** (핵심 검증)
- [ ] 로그 traceId → Tempo 점프 / Tempo span → Loki 역점프
- [ ] 외부 tempo 3200/4317 접근 불가
- [ ] `free -h` 여유(Tempo ~200M)

## 리스크 / 롤백
- ApiClientConfig 미수정 → 외부 span 누락: §1-3 필수, 검증 4항목 포착
- Toss mTLS 회귀: 빌더 교체 시 baseUrl/requestFactory 유지
- 의존성 충돌: BOM 관리 회피, 트리 확인
- 롤백: app 트레이싱 의존성/설정 제거 후 재배포. Tempo 독립(빈 상태 무해)
