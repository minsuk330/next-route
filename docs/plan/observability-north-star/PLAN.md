# 관측 북극성 — 정시 알림·주간 점검·퍼널 (L, slug `observability-north-star`)

## 목표
관측 데이터를 "대시보드가 있다"에서 "서비스 약속(제때 알림)을 지키는지 매주 판단하고, 깨지면 바로 안다"로 옮긴다.

- 북극성 지표 = **여정 알림 정시율**
- 실시간은 Slack 알림, 사람은 주간 점검(월요일 자동 요약)
- 알림 파이프라인(Grafana → Slack + alert-analyzer)이 장애를 제대로 잡는지 채점·훈련
- 사용 퍼널(검색 → 여정 → 알림 → 완료)

## 진행 (2026-11-26)

| 단계 | PR | 상태 |
|---|---|---|
| PR-1 VPS 드리프트 반영 + 디스크 알림 + node 디스크 패널 수정 | #630 | 운영 반영 |
| PR-1b 운영 설정 `compose.prod.yaml` 분리 + `ops/prod-sync.sh`·`prod-drift.sh`·`prod-prune.sh` | #632 | 운영 반영(드리프트 0, prune cron 일 04:00) |
| deploy.sh worker 배포·롤백 때 worker2 도 교체 | #634 | 운영 반영(16:16 배포에서 실제 동작 확인) |
| PR-2 북극성 대시보드 + 늦음·유실 알림 | #635 | 운영 반영(규칙 9개 health ok) |
| PR-3 주간 요약 `analyzer.weekly` | #636, #637 | 운영 반영(첫 발송, cron 월 09:00) |
| PR-4 이모지 채점 | – | **대기: Slack 앱 `reactions:read` 추가 + 재설치(사용자 작업)** |
| PR-5 검색 기록 `route_search_event` + `journey_session.search_session_id` | #638 | 운영 배포(`1fbfaa86`), 검색 기록 확인. 여정 연결은 실사용 여정으로 확인 필요(#643) |
| PR-6 후속 issue | #633, #639~#643 | 등록(라벨 `observability-weekly`) |

## 사용자 결정 (2026-11-26, 인터뷰)

### 목적
- 보는 사람: 운영·튜닝(실시간 장애 대응 포함) 주, 사용 분석 함께, 포폴은 부산물
- 우선순위: 약속(정시 알림) > 느린가 > 살아 있나 > 사용량 > 버틸 수 있나
- 주기: 실시간 = Slack 알림. 사람 = 주간(또는 일정 간격) 점검

### 환경 전제
- **VPS = 운영(최우선), 로컬 = 테스트.** 저장소를 VPS 에 통째로 덮지 않는다
- 운영에서만 다른 값은 `compose.prod.yaml`(앱·관측 각각)에만. VPS `.env` 의 `COMPOSE_FILE` 로 합친다(`ops/PROD.md`)
- VPS 반영은 `ops/prod-sync.sh apply`(origin/main 커밋에서만, 정해진 파일만, 백업 후). VPS 직접 수정 금지

### 북극성: 여정 알림 정시율
- 대상: `journey_notification` 중 `channel='PUSH'`(FCM). checkpoint 별 + 합계
- 정시: `sent_at <= created_at(KST naive) + payload.holdMs + 60s`
  - `scheduled_at` 은 재시도 backoff 로 덮이므로 기준으로 쓰지 않는다
- 유실: DEAD·FAILED·CANCELED(`expired`, `expired-before-retry` — 코드의 `recordLost` 와 같음). 지연 중: PENDING·PROCESSING 인데 예정+60초 지남
- 분모: 정시 + 늦음 + 유실 + 지연 중. 뜻을 잃은 취소(journey-ended, command-changed, lease-expired)는 뺀다
- 표시: `n/m` 건수. 주 20건 이상일 때만 % 병기
- 실시간 알림: 최근 1시간 늦음·유실·지연 중 **1건이라도** → warning (`nr-journey-notification-late`)
- 측정 지점 = FCM 수락까지. 기기 수신 확인은 범위 밖(#640)
- 보조 지표: 잠금화면 갱신(`subway_ring_push_outbox`) — 분모 SENT+FAILED(SKIPPED 제외), 정시 = `sent_at <= created_at + 60s`.
  알림 = 1시간 ≥10건 && 정시율 <95% → warning (`nr-ring-push-ontime`). 2주 데이터 보고 조정
- 제외: 토스 경로(`bus_alert_notification`, `route_arrival_alert_notification`) — 데이터 없음
- 계산: Grafana Postgres 데이터소스(`nextroute-pg`, `grafana_ro`) SQL. 정의는 `ops/observability/grafana/north_star_dashboard.py` 의 `JOURNEY`·`RING` 한 곳. 알림 규칙 SQL 도 같은 정의(바꾸면 둘 다). 메트릭 보강은 #639

### 느린가
- p95 500ms 목표. 주간 요약에만, 실시간 알림 없음
- **경로 검색(OTP)만 완화**(2026-11-26 사용자: 검색 지연은 감수). p95 가 5초 bucket 경계(7일 분포 ≤2.5s 55% / ≤5s 94.6% / >5s 5.4%)라 악화를 못 봐서, **5초 초과 비율 10% 초과 때만** 표시. 장소 검색 등 나머지는 완화하지 않음

### 살아 있나 / 버틸 수 있나
- 규칙: 메모리 부족, 컨테이너 다운, api 5xx, ERROR 로그 급증, OTP 호출 실패 + **디스크 85% warning / 95% critical**(추가)
- worker2 = 이중화(유실 방지). 장애 시나리오로 검증 완료. 유지, 메모리 부족 시에만 내림. 리더 락이 없는 단독 작업 3개는 #633
- OTP 힙은 의도적으로 빠듯 — 알림 추가 안 함
- 외부 uptime 체크·postgres/redis exporter·Tempo 재가동은 범위 밖

### 알림 파이프라인 검증
- 사후 채점: Slack 알림 메시지에 이모지 — ✅ 진짜 장애 / ❌ 오탐 / 🎯 가설 맞음 / 🤷 가설 틀림 (PR-4, Slack 권한 대기)
- 정기 훈련: 매월 첫 주. 가짜 5xx / 가짜 OTP 호출 실패 / ERROR 로그 급증 → 도착 시간·가설 적중 기록. 사용자 영향 있는 장애는 실제로 내지 않는다

### 주간 점검
- 월요일 09:00 KST, 알림과 같은 채널, 제목 `[주간]`. VPS crontab → `docker compose run --rm alert-analyzer python -m analyzer.weekly`
- 북극성 수치는 대시보드 `nextroute-north-star` 패널 SQL 을 Grafana `/api/ds/query`(서비스 계정 `weekly-digest`, Viewer)로 실행 → 대시보드와 숫자 일치. 분석기에 DB 접속 없음
- 내용: 정시율(n/m)·checkpoint 별, 잠금화면 갱신, 목표 초과 엔드포인트, 퍼널, 지난주 알림, 열린 `observability-weekly` issue(GITHUB_TOKEN 있을 때)
- 섹션별 실패 격리. 드리프트 건수는 VPS 에 저장소가 없어 넣지 않음(로컬 `ops/prod-drift.sh`)
- 고칠 것 → GitHub issue, 라벨 `observability-weekly`

### 퍼널
- **앱 수정 불필요**: 앱은 이미 여정 시작 요청에 `searchSessionId`(검색 응답 스냅샷 id)를 보낸다(`StartJourneyRequest`). 처음 계획한 새 `searchId`·모바일 PR 은 없앴다
- `route_search_event`(V79) — 검색마다 1행. 좌표·장소명 없음, 90일 보관(worker 04:40 KST 묶음 삭제), 탈퇴 시 물리 삭제. 익명 포함
  - 처음 이름으로 잡은 `route_search_log` 는 V1 의 옛 표(좌표·응답 JSON 155행, 미사용)라 새 이름. 옛 표 정리는 #641
- `journey_session.search_session_id`(V80) — 여정 시작 때 저장
- 사용 통계 대시보드 퍼널 패널. 주간 요약 퍼널은 데이터가 쌓인 뒤 `route_search_event` 로 전환(#643)

## 사실 (조사, 2026-11-26)

### 알림 경로
| 경로 | 테이블 | 메트릭 | 7일 |
|---|---|---|---|
| 여정 알림(FCM) | `journey_notification`(V40, V70) | `journey.notification.delivery-lag`(tag `channel`만, hold 포함), `e2e`(위험 알림만) | SENT 7 |
| 잠금화면 갱신(FCM→APNs) | `subway_ring_push_outbox`(V47) | `journey.subway.ring.push.latency`(bucket 없음) | SENT 163 |
| 버스 도착(토스) | `bus_alert_notification` | 없음 | 전체 2건 |
| 경로 도착(토스) | `route_arrival_alert_notification` | 없음 | 0건 |

- 30일 여정 알림 22/22 정시(p95 1.2s). ring 651/651(p95 2.25s). 전체 기간 유실 4건(FAILED 3 `fcm: INVALID_ARGUMENT`, DEAD 1)
- `journey_telemetry_event` 7일 0건 → 기기 수신 확인 데이터 없음

### 퍼널 (7일, 배포 전)
- 검색 ≈146(Prometheus) → 여정 시작 32 → 알림 받은 여정 5 → 완료 6

### 운영 환경
- VPS: 4 vCPU, RAM 7.9G(available ~875M, swap 1.3G 사용). 컨테이너 상한 합이 RAM 초과. OTP 힙 3.3/3.5G(일부러 빠듯)
- 디스크: 오늘 90% → 62%(build cache 8.2G, 이미지 45개, jar 백업 23개, kmorgan 컨테이너·이미지, Tempo 볼륨). 원인은 VPS 에서 이미지를 빌드하는 배포 방식 → 주 1회 `prod-prune.sh`. CI 빌드 전환은 별건
- 배포: 로컬 bootJar → `app.jar` 백업 → scp(sha256 확인) → `DEPLOY_REVISION=<sha> ./deploy.sh all`
- 첫 분리 전 VPS 앱 compose 는 손으로 고친 파일 + `observability/otp/compose.otp-national.yaml` 혼용(저장소와 138줄 차이). 분리 뒤 VPS 실제 설정과 `docker compose config` 비교 차이 0(앱 8·관측 6 서비스)
- node 대시보드 디스크 패널이 `mountpoint="/host"` 를 조회해 처음부터 비어 있었다(실제 `"/"`) — #630 에서 수정

## 범위 밖
- 외부 uptime 체크, postgres/redis exporter, Tempo 재가동(메모리 여유 없음)
- OTP 힙 알림(의도적 유지)
- 앱 이미지 CI(GHCR) 빌드 전환 — 북극성 작업 뒤 별건
