# 사용 통계 대시보드 (M, slug `usage-stats`)

## 목표
운영자(나)만 보는 Grafana "사용 통계" 대시보드. 일별 활성 사용자(DAU)·주간(WAU)·신규 설치·여정 시작/완료/취소·여정 사용자·경로 검색·앱 빌드 분포.

## 사용자 결정 (2026-11-03)
- 위치 = 기존 Grafana(grafana.refit-100.site). 서버 관리자 페이지·아티팩트 아님
- DAU 를 정확히 세기 위해 서버가 사용자별 하루 1행을 남긴다(api 배포 필요)

## 사실 (조사)
- 접속 로그(nginx)엔 사용자 ID 가 없고 모바일 IP 는 바뀐다 → IP 로 사람 수 못 셈
- `auth_identity.last_login_at` 은 기기 계정 생성 때만 찍힘 → 활성 지표로 못 씀
- 인증: `common/security/JwtAuthFilter.java:33`(빈 아님, `SecurityConfig.java:71-72` 에서 new). principal = userId(Long)
- 탈퇴: `application/auth/service/WithdrawService.java:100`, 물리 삭제 :113-133, 경계 IT `src/test/java/.../auth/WithdrawalDeletionBoundaryIT.java`(~:66 PHYSICAL/SOFT 목록 + FK 그래프 검사)
- ON CONFLICT 예: `domain/journey/repository/JourneyTelemetryEventRepository.java:37`
- 마지막 마이그레이션 V73 → 새 파일 V74 (Flyway 자동, jar 포함)
- VPS: postgres 는 `nextroute_default`, grafana 는 `observability_default` 만. prometheus 는 이미 external `nextroute` 네트워크에 붙어 있음(관측 compose `networks.nextroute`)
- VPS 관측 compose 는 git 아님·repo 와 drift → 블록 삽입만, 통째로 덮지 않음

## PR-1 (앱): 일별 활성 기록

### 스키마 `src/main/resources/db/migration/V74__user_daily_activity.sql`
```sql
CREATE TABLE user_daily_activity (
    activity_date  date        NOT NULL,          -- KST 날짜
    user_id        bigint      NOT NULL REFERENCES users(id),
    first_seen_at  timestamptz NOT NULL,
    app_build      varchar(32),                   -- User-Agent 첫 토큰 'app/7' · 'Dev/6' (없으면 null)
    PRIMARY KEY (activity_date, user_id)
);
CREATE INDEX idx_user_daily_activity_user ON user_daily_activity (user_id);
```

### 코드
- `domain/user/repository/UserDailyActivityRepository`(또는 JdbcTemplate 어댑터): `INSERT … ON CONFLICT (activity_date, user_id) DO NOTHING`, `deleteByUserId(long)` (@Modifying bulk)
- `application/user/service/DailyActivityRecorder`
  - `void touch(long userId, String userAgent)`: KST 오늘 + userId 를 메모리 집합(날짜 바뀌면 비움, 상한 50,000)으로 거른 뒤 처음일 때만 비동기 INSERT
  - 전용 executor(코어 1, 큐 1,000, 넘치면 버림 + WARN 카운트) — 요청 스레드를 막지 않는다. `searchHistoryExecutor` 는 AbortPolicy 라 쓰지 않음
  - 실패는 WARN 로그만(요청 영향 0). 실패한 건은 메모리 집합에서 빼서 다음 요청 때 다시 시도
  - `Clock` 주입(시험용)
  - app_build: UA 첫 토큰이 `[A-Za-z]+/\d+` 일 때만, 32자 자름
- `common/security/DailyActivityFilter`(OncePerRequestFilter, 빈 아님): SecurityContext 에 userId 가 있으면 `recorder.touch`. `SecurityConfig` 에서 `addFilterAfter(…, JwtAuthFilter.class)`. recorder 는 `@Profile("!worker")` 빈 → SecurityConfig 는 `ObjectProvider` 로 받아 없으면 필터 안 붙임
- 플래그 `usage.daily-activity.enabled`(기본 true) — 끄면 필터 미등록
- 탈퇴: `WithdrawService` 물리 버킷에 `userDailyActivityRepository.deleteByUserId(userId)` 추가(auth_identity 앞), `WithdrawalDeletionBoundaryIT` PHYSICAL 목록에 추가

### 시험
- 단위 `DailyActivityRecorderTest`: 같은 날 두 번 → INSERT 1회, 날짜 바뀌면 다시 1회, 저장 실패 → 예외 안 던짐 + 다음 호출 때 재시도, UA 파싱(app/7, Dev/6, 브라우저 UA → null)
- 단위 `DailyActivityFilterTest`: 인증 없음 → touch 안 함, 인증 있음 → touch(userId, UA), recorder 예외여도 체인 계속
- IT `UserDailyActivityRepositoryIT`(Postgres 컨테이너): 중복 INSERT 무시, deleteByUserId
- `WithdrawalDeletionBoundaryIT` 통과
- 명령: `./gradlew test --tests '*DailyActivity*' --tests '*UserDailyActivityRepositoryIT' --tests '*WithdrawalDeletionBoundaryIT'` 그 뒤 `./gradlew test`

### 배포
- api·worker 이미지 교체(V74 Flyway 자동). 배포 직전 실여정 테스트 중인지 사용자 확인
- 확인: 앱 요청 뒤 `select count(*) from user_daily_activity where activity_date = current_date`(읽기 전용) > 0, ERROR 0

## PR-2 (관측): Grafana 대시보드

### DB 읽기 전용 계정 (VPS 수동, 스크립트는 저장소에)
- `ops/observability/postgres/grafana_ro.sql`(psql 변수 `:'pw'` 로 비밀번호 주입, 파일엔 비밀번호 없음):
  - `CREATE ROLE grafana_ro LOGIN PASSWORD :'pw' CONNECTION LIMIT 3;`
  - `ALTER ROLE grafana_ro SET default_transaction_read_only = on; SET statement_timeout = '10s';`
  - `GRANT CONNECT` + `USAGE ON SCHEMA public` + `GRANT SELECT` 만: auth_identity, journey_session, user_daily_activity (구현 중 축소: 검색 수는 아래 Prometheus 로)
- 비밀번호는 VPS 에서 `openssl rand -hex 24` 로 만들어 `env/grafana.env` 의 `GRAFANA_PG_PASSWORD` 에(출력 안 함, 600 유지)

### 저장소 파일
- `ops/observability/grafana/provisioning/datasources/datasources.yaml`: PostgreSQL 데이터소스 `uid: nextroute-pg`, `url: postgres:5432`, db=운영 DB 이름, user `grafana_ro`, `secureJsonData.password: $GRAFANA_PG_PASSWORD`, `sslmode: disable`, `timezone: Asia/Seoul`
- `ops/observability/compose.yaml`: grafana 에 `networks: [default, nextroute]`
- `ops/observability/grafana/dashboards/usage.json` "사용 통계"(기본 범위 30일, KST):
  1. 오늘 DAU · 7일 WAU · 30일 MAU (stat) ← user_daily_activity
  2. 일별 DAU (time series) ← user_daily_activity
  3. 일별 신규 계정 provider 별 ← auth_identity.created_at (deleted_at null 무관, 생성 사건)
  4. 일별 여정 시작 + 상태별(COMPLETED/CANCELED/EXPIRED/ACTIVE) 누적 막대 ← journey_session.created_at, kind 별 분리 가능
  5. 일별 여정 사용자 수(distinct user_id) ← journey_session
  6. 여정 완료율(기간) = COMPLETED / (시작 − ACTIVE)
  7. 일별 경로 검색 요청 ← Prometheus `http_server_requests_seconds_count{uri="/api/route/search"}` (구현 중 변경: 운영 `route_search_log` 는 14일 0행 — 쓰이지 않음, `route_search_history` 는 좌표쌍당 1행 upsert 라 횟수가 아님)
  8. 앱 빌드 분포(기간, 사용자 수) ← user_daily_activity.app_build
  9. 재방문: 첫 활동일 다음 7일 안에 다시 온 사용자 비율(코호트 표, 주 단위)
  10. 사용자별 표(최근 30일): md5(user_id) 앞 6자, 첫 활동일, 활동 일수, 여정 수, 검색 수 — 목적지·좌표·이름은 싣지 않음
- 대시보드 provisioning 은 기존 `dashboards.yaml` 폴더 그대로(파일 추가만)

### 배포
- 백업 `/root/apps/observability-backups/…-pre-usage-stats.tgz`(compose·env·provisioning·dashboards)
- grafana_ro 생성 → env 추가 → compose grafana 블록에 networks 2줄 삽입(diff 로 추가 줄만 확인) → `docker compose config -q` → datasources.yaml·usage.json scp → `up -d --no-deps --force-recreate grafana`
- 확인: provisioning 로그 오류 없음, 데이터소스 health OK, 대시보드 패널 10개 값 렌더(PR-1 배포 전이면 1·2·8·9·10 은 빈 값이 정상)
- 되돌리기: 백업 tgz 복원 + grafana 재생성, `DROP ROLE grafana_ro`

## 순서
- [ ] PR-1 구현·시험·머지 → (사용자 확인) api·worker 배포
- [ ] PR-2 구현(대시보드 쿼리는 운영 읽기 전용으로 미리 실행해 검증) → 머지 → VPS 반영
- [ ] 노트 `### 검증`·`### 배포` 기록

## 범위 밖
- 화면별 체류·클릭 같은 앱 이벤트 분석(앱 작업)
- 익명(비로그인) 사용자의 DAU — 지금 앱은 첫 실행 때 기기 계정을 만드니 사실상 전원 인증

## 구현 중 확인 (2026-11-03)
- 운영 naive timestamp 칸(created_at 등)은 KST 벽시계, timestamptz 칸(started_at·ended_at)은 UTC. 쿼리는 naive 칸을 `AT TIME ZONE 'Asia/Seoul'` 로 timestamptz 화
