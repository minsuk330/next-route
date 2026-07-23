# Tempo 분산 트레이싱

## 뭘 하나
기존 관측 스택(Loki+Prometheus+Grafana)에 Tempo 추가 → 관측 3축 완성. app 요청을 자동계측(HTTP 서버 + 외부 API 호출)해 트레이스로 수집, 100% 샘플링. 로그↔트레이스 양방향 점프.

## 왜
Traces 부재로 요청 단위 지연·외부 API 병목 파악 불가. 경로검색 fan-out(odsay/tmap/ml/버스) 추적이 핵심 대상.

## 핵심 결정
- Micrometer Tracing + OTLP → Tempo — Spring Boot 3.5 1급 지원 (기각: Zipkin, OTel Agent)
- 샘플링 1.0 — 저트래픽, 학습 목적 전량 관찰
- traceId 로그 상관연결 추가코드 0 — bridge-otel이 MDC 자동주입, 기존 logstash JSON에 자동 포함
- **ApiClientConfig 4빈 수정** — 정적 `RestClient.builder()`는 계측 커스터마이저 미적용 → 주입 빌더로 교체해야 외부 span 생성
- Tempo retention 14d — 트레이스 부피 커서 로그(30d)보다 짧게

## 건드는 곳
`build.gradle`, `application-prod.yaml`, `ApiClientConfig.java`, `ops/observability/`(compose+tempo/+datasources), VPS 배포

## 리스크
- ApiClientConfig 미수정 시 외부 span 누락 — 최종검증 항목이 포착
- Toss mTLS 회귀 — 빌더 교체 시 requestFactory 유지, 부팅 로그 확인
- 의존성 버전 충돌 — BOM 관리로 회피, 트리 확인

## 검증법
경로검색 요청 1건이 Tempo 트레이스에 odsay/tmap/ml/버스 자식 span으로 표시, 로그↔트레이스 양방향 점프 동작, Toss mTLS 부팅 정상, tempo 포트 외부 차단.
