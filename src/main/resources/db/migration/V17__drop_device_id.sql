-- 신원을 토스 사용자 식별키로 단일화: device_id 영구 제거, toss_user_key NOT NULL.
-- 레거시 기기전용(토스 미연동) 사용자는 더 이상 식별 불가하므로 종속 데이터와 함께 정리한다.
-- (토스 로그인은 신규 기능 → 운영 DB에는 미연동 사용자가 없어 영향 없음)

DELETE FROM favorite_route
 WHERE user_id IN (SELECT id FROM users WHERE toss_user_key IS NULL);

UPDATE route_search_log
   SET user_id = NULL
 WHERE user_id IN (SELECT id FROM users WHERE toss_user_key IS NULL);

DELETE FROM users WHERE toss_user_key IS NULL;

-- 기존 device_id unique 제약은 컬럼 DROP 시 함께 제거됨
ALTER TABLE users DROP COLUMN device_id;
ALTER TABLE users ALTER COLUMN toss_user_key SET NOT NULL;
