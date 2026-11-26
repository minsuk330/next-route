# 관측 북극성 — 정시 알림·주간 점검·퍼널 (L, slug `observability-north-star`)

## 목표
관측 데이터를 "대시보드가 있다"에서 "서비스 약속(제때 알림)을 지키는지 매주 판단하고, 깨지면 바로 안다"로 옮긴다.

- 북극성 지표 = **여정 알림 정시율**
- 실시간은 Slack 알림, 사람은 주간 점검(월요일 자동 요약)
- 알림 파이프라인(Grafana → Slack + alert-analyzer)이 장애를 제대로 잡는지 채점·훈련
- 사용 퍼널(검색 → 여정 → 알림 → 완료)

## 사용자 결정 (2026-11-26, 인터뷰)

### 목적
- 보는 사람: 운영·튜닝(실시간 장애 대응 포함) 주, 사용 분석 함께, 포폴은 부산물
- 우선순위: 약속(정시 알림) > 느린가 > 살아 있나 > 사용량 > 버틸 수 있나
- 주기: 실시간 = Slack 알림. 사람 = 주간(또는 일정 간격) 점검

### 북극성: 여정 알림 정시율
- 대상: `journey_notification` 중 `channel='PUSH'`(FCM). checkpoint 별 + 합계
- 정시: `sent_at <= created_at(KST naive) + payload.holdMs + 60s`
  - `scheduled_at` 은 재시도 backoff 로 덮이므로 기준으로 쓰지 않는다
- 분모: SENT + DEAD/FAILED + 만료 CANCELED. SENT 외는 "늦음"
- 표시: `n/m` 건수. 주 20건 이상일 때만 % 병기
- 실시간 알림: 늦음/유실 **1건이라도** → warning
- 측정 지점 = FCM 수락까지. 기기 수신 확인은 범위 밖(issue)
- 보조 지표: 잠금화면 갱신(`subway_ring_push_outbox`) 지연. 정시 = `sent_at <= created_at + 60s`.
  알림 = 1h 발송 ≥10건 && 정시율 <95% → warning (2주 데이터 보고 조정)
- 제외: 토스 경로(`bus_alert_notification`, `route_arrival_alert_notification`) — 데이터 없음
- 계산: Grafana Postgres 데이터소스(`nextroute-pg`, `grafana_ro`) SQL. 앱 코드 변경 없음.
  메트릭 보강(checkpoint 태그, ring SLO bucket)은 issue

### 느린가
- 경로 검색 p95 < 2.5s, 그 외 API p95 < 500ms
- 주간 요약에만. 실시간 알림 없음

### 살아 있나 / 버틸 수 있나
- 기존 규칙 5개 유지
- **디스크 알림 추가**: 85% warning, 95% critical
- worker2 = 이중화(유실 방지). 장애 시나리오로 검증 완료. 유지, 메모리 부족 시에만 내림
- OTP 힙은 의도적으로 빠듯 — 알림 추가 안 함

### 알림 파이프라인 검증
- 사후 채점: Slack 알림 메시지에 이모지 — ✅ 진짜 장애 / ❌ 오탐 / 🎯 가설 맞음 / 🤷 가설 틀림
  - Slack 앱에 `reactions:read` 추가 + 재설치(사용자 작업). token 바뀌면 VPS env 교체
- 정기 훈련: 매월 첫 주. 가짜 5xx / 가짜 OTP 호출 실패 / ERROR 로그 급증 → 도착 시간·가설 적중 기록
  - 사용자 영향 있는 장애(OTP 중지, 실제 5xx)는 실제로 내지 않는다

### 주간 점검
- 월요일 09:00 KST, 알림과 같은 Slack 채널, 제목 `[주간]`
- 내용: 정시율(n/m), 잠금화면 갱신 지연, 목표 초과 엔드포인트, 퍼널, 채점 결과, 열린 `observability-weekly` issue 수
- 실행: alert-analyzer 이미지의 스크립트를 VPS crontab 이 실행(분석기 서버 안 주기 작업 아님 — 재시작 시 누락/중복 방지)
- 고칠 것 → GitHub issue, 라벨 `observability-weekly`

### 퍼널
- 새 테이블 `route_search_log` — 검색마다 1행(append-only)
  - 좌표 저장 안 함(위치정보). 90일 후 삭제
  - `user_id` 항상 채움(기기 계정도 `auth_identity.DEVICE` → `users` 행 존재). 미인증 요청만 null
- 검색 응답에 `searchId`, 앱이 여정 시작 요청에 다시 보냄 → `journey_session.search_id`(nullable)
- 서버 먼저(searchId 없어도 동작) → 모바일 PR
- 대시보드에 search_id 연결 비율 표시

### 운영 규칙
- 설정 기준 = repo main. VPS 는 rsync 로만 반영. VPS 직접 수정은 같은 날 PR 로 되돌려 넣는다

## 사실 (조사, 2026-11-26)

### 알림 경로
| 경로 | 테이블 | 메트릭 | 7일 |
|---|---|---|---|
| 여정 알림(FCM) | `journey_notification`(V40, V70) | `journey.notification.delivery-lag`(tag `channel`만, hold 포함), `e2e`(위험 알림만) | SENT 7 |
| 잠금화면 갱신(FCM→APNs) | `subway_ring_push_outbox`(V47) | `journey.subway.ring.push.latency`(bucket 없음) | SENT 163 |
| 버스 도착(토스) | `bus_alert_notification` | 없음 | 전체 2건 |
| 경로 도착(토스) | `route_arrival_alert_notification` | 없음 | 0건 |

- 30일 여정 알림 22/22 정시(0.4~3.8s). ring 7일 p95 2.25s
- `journey_telemetry_event` 7일 0건 → 기기 수신 확인 데이터 없음

### 퍼널 (7일)
- 검색 ≈139(Prometheus `route_search_duration_seconds_count`) → 여정 시작 31 → 완료 6
- `route_search_history` 는 (user, 출발/도착 좌표) upsert → 이벤트 로그로 못 씀
- 여정 → 알림 → 완료는 `journey_id` 로 정확히 조인됨

### 관측 스택
- `grafana_ro` 권한: `auth_identity, journey_session, user_daily_activity` 만
  (`ops/observability/postgres/grafana_ro.sql`) → `journey_notification`, `subway_ring_push_outbox`,
  `route_search_log` GRANT 필요
- alert-analyzer: Slack bot token(`chat:write`, `channels:history`, `groups:history`), Prometheus·Loki 클라이언트 있음,
  예약 실행 없음, 상태 메모리만
- 마지막 마이그레이션 V78 → 새 파일 V79~
- VPS 메모리: RAM 7.9G, available ~875M, swap 1.3G 사용. 새 상주 프로세스 추가 금지(cron 1회 실행만)

### VPS ↔ main 드리프트 (PR-1 대상)
- `prometheus.yml`: VPS 에 `worker2:8081`, `otp:9404`(5s) job
- `compose.yaml`: Tempo `profiles: ["traces"]`(꺼짐) + grafana `depends_on: tempo` 제거, Loki `GOMEMLIMIT 512MiB`·`mem_limit 768m`·`oom_score_adj 500`,
  Prometheus `127.0.0.1:9090` publish
- VPS 전용 파일: `grafana/dashboards/otp.json`, `otp/compose.otp-national.yaml`, `otp/compose.otp-test.yaml`
- 앱 compose(VPS): OTP 전국 graph override `-Xmx3500m`, `MANAGEMENT_OTLP_TRACING_EXPORT_ENABLED=false`
- Tempo 꺼져 있는데 Tempo 데이터소스·Loki TraceID derived field 남아 있음

## PR 순서

### PR-1 (ops): 드리프트 반영 + 디스크 알림
- VPS 설정을 main 에 반영(위 목록). `.bak` 파일은 제외
- `rules.yaml` 에 디스크 규칙: `1 - avail/size`(rootfs) 85% warning / 95% critical, for 10m
- Tempo 꺼진 상태 명시(README), 데이터소스는 유지하되 주석
- 검증: VPS 와 diff 0(env 제외), `promtool check config`, Grafana provisioning reload 후 규칙 6개

### PR-2 (ops): 북극성 대시보드 + 알림
- `grafana_ro.sql` 에 GRANT 추가 (VPS 에서 수동 실행 명령 포함)
- 대시보드 `north-star.json`: 여정 알림 checkpoint 별 n/m, 지연 분포, 유실 목록 / ring 정시율·p95
- 알림 규칙(Postgres 데이터소스): 여정 늦음·유실 ≥1 warning / ring 1h ≥10건 && <95% warning
- 검증: 30일 수치가 조사값(22/22)과 일치, 규칙 테스트 발화(Grafana test)

### PR-3 (alert-analyzer): 주간 요약
- `analyzer/weekly.py` 같은 1회 실행 엔트리포인트. Prometheus·Postgres(grafana_ro) 조회 → Slack `[주간]`
- 내용은 "주간 점검" 절. 계산 로직 단위 테스트(고정 입력 → 고정 문구)
- VPS crontab `CRON_TZ=Asia/Seoul`, `0 9 * * 1` `docker compose run --rm alert-analyzer python -m analyzer.weekly`
- 검증: dry-run 출력 확인 후 1회 실발송

### PR-4 (alert-analyzer): 이모지 채점
- 선행: 사용자가 `reactions:read` 추가 + 재설치
- 지난 7일 알림 메시지(`fp:` 줄로 식별)의 reaction 집계 → 주간 요약에 오탐률·가설 적중률·미채점 수
- 매월 첫 주 요약에 훈련 체크리스트 3개 첨부

### PR-5 (app): 검색 로그 + search_id
- `V79__route_search_log.sql`: `id`(uuid), `user_id`(nullable FK), `searched_at`, `provider`, `result_status`, `latency_ms`. 좌표 없음
- `V80__journey_session_search_id.sql`: `journey_session.search_id uuid NULL`
- 검색 응답에 `searchId`. 여정 시작 요청 `searchId` 선택 필드
- 기록은 비동기·실패 무시(요청 영향 0). 90일 파기 스케줄러(worker)
- 탈퇴: 물리 삭제 목록에 `route_search_log` 추가 (`WithdrawalDeletionBoundaryIT`)
- 대시보드 퍼널 패널 + 연결 비율
- 후속: `nextroute-mobile` PR — 여정 시작에 `searchId` 전달

### PR-6: issue 등록
- 메트릭 보강(delivery-lag checkpoint 태그, ring latency SLO bucket)
- 기기 수신 확인(`journey_telemetry_event` 0건)
- 라벨 `observability-weekly` 생성

## 범위 밖
- 외부 uptime 체크(첫 리뷰 항목) — 이번 인터뷰에서 다루지 않음, 필요 시 별건
- postgres/redis exporter, Tempo 재가동(메모리 여유 없음)
- OTP 힙 알림(의도적 유지)
