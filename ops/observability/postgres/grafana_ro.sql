-- Grafana 사용 통계 대시보드 전용 읽기 계정. 비밀번호는 파일에 두지 않는다.
-- 실행(VPS): PW=$(openssl rand -hex 24) 로 만든 값을 env/grafana.env 의 GRAFANA_PG_PASSWORD 에 넣고
--   PW=... docker exec -i -e PW nextroute-postgres-1 sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -v pw="$PW"' < grafana_ro.sql
-- (PW 는 env 로만 넘긴다. 화면·셸 기록에 찍지 않는다.)
-- 되돌리기: DROP OWNED BY grafana_ro; DROP ROLE grafana_ro;

CREATE ROLE grafana_ro LOGIN PASSWORD :'pw' CONNECTION LIMIT 3;
ALTER ROLE grafana_ro SET default_transaction_read_only = on;
ALTER ROLE grafana_ro SET statement_timeout = '10s';

GRANT CONNECT ON DATABASE mydatabase TO grafana_ro;
GRANT USAGE ON SCHEMA public TO grafana_ro;
-- 대시보드가 읽는 표만. 새 패널이 다른 표를 읽으면 여기에 추가한다.
GRANT SELECT ON auth_identity, journey_session, user_daily_activity TO grafana_ro;
