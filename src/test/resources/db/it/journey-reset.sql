-- 여정 표를 지운다. 마이그레이션 파일을 @Sql 로 다시 실행하기 전에 돈다.
--
-- 테스트 클래스가 컨테이너를 공유하기 때문에 필요하다. @Sql(BEFORE_TEST_CLASS) 는 클래스마다
-- 돌고, 앞선 클래스가 만들어 둔 표가 남아 있으면 CREATE TABLE 이 "이미 있다"로 실패한다.
--
-- users 는 지우지 않는다. 컨테이너 init script(journey-it-schema.sql)가 만든 FK 대상이고,
-- 여정 표를 지울 때 CASCADE 로 참조만 함께 사라지면 된다.

DROP TABLE IF EXISTS reroute_proposal CASCADE;
DROP TABLE IF EXISTS journey_telemetry_event CASCADE;
DROP TABLE IF EXISTS journey_notification CASCADE;
DROP TABLE IF EXISTS transfer_connection CASCADE;
DROP TABLE IF EXISTS journey_event CASCADE;
DROP TABLE IF EXISTS journey_revision CASCADE;
DROP TABLE IF EXISTS journey_leg_route_option CASCADE;
DROP TABLE IF EXISTS journey_leg CASCADE;
DROP TABLE IF EXISTS journey_session CASCADE;
DROP TABLE IF EXISTS user_push_token CASCADE;
