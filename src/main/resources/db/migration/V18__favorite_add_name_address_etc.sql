-- 즐겨찾기: 사용자 정의 이름 + 주소 추가, ETC 타입 허용, HOME/WORK 유저당 1개 제약
ALTER TABLE favorite_route
    ADD COLUMN name    varchar(255) NOT NULL,
    ADD COLUMN address varchar(255) NOT NULL;

ALTER TABLE favorite_route DROP CONSTRAINT favorite_route_type_check;
ALTER TABLE favorite_route ADD CONSTRAINT favorite_route_type_check
    CHECK (type IN ('HOME', 'WORK', 'ETC'));

-- HOME/WORK 는 유저당 active 1개 (소프트삭제 고려, ETC 제외)
CREATE UNIQUE INDEX uq_favorite_home_work_active
    ON favorite_route (user_id, type)
    WHERE type IN ('HOME', 'WORK') AND deleted_at IS NULL;
