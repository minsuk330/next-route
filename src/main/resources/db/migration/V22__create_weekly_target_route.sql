-- 주간 노선 로테이션: 수집 대상 30개를 DB에서 동적 관리.
-- route_name 은 BusRoute.routeName(=표시 노선번호, ridership RTE_NM)과 매칭되는 수집 키다.
-- 시드는 기존 application.yaml target-route-names 30개(=현재 top1-30, bucket 0).

CREATE TABLE weekly_target_route (
    id              BIGSERIAL PRIMARY KEY,
    route_no        VARCHAR(32),
    route_name      VARCHAR(64) NOT NULL,
    rank_position   INT         NOT NULL,
    bucket          INT         NOT NULL,
    ridership_month VARCHAR(6),
    active          BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP,
    updated_at      TIMESTAMP,
    deleted_at      TIMESTAMP
);

CREATE INDEX idx_weekly_target_route_active
    ON weekly_target_route (active)
    WHERE deleted_at IS NULL;

-- 로테이션 상태(단일 행). current_bucket 기준으로 다음 offset 을 계산한다.
CREATE TABLE route_rotation_state (
    id              BIGSERIAL PRIMARY KEY,
    current_bucket  INT         NOT NULL,
    ridership_month VARCHAR(6)  NOT NULL,
    total_buckets   INT,
    rotated_at      TIMESTAMP,
    created_at      TIMESTAMP,
    updated_at      TIMESTAMP,
    deleted_at      TIMESTAMP
);

INSERT INTO route_rotation_state (current_bucket, ridership_month, rotated_at, created_at, updated_at)
VALUES (0, '202603', now(), now(), now());

INSERT INTO weekly_target_route (route_name, rank_position, bucket, ridership_month, active, created_at, updated_at) VALUES
    ('143', 1, 0, '202603', TRUE, now(), now()),
    ('272', 2, 0, '202603', TRUE, now(), now()),
    ('160', 3, 0, '202603', TRUE, now(), now()),
    ('271', 4, 0, '202603', TRUE, now(), now()),
    ('130', 5, 0, '202603', TRUE, now(), now()),
    ('273', 6, 0, '202603', TRUE, now(), now()),
    ('163', 7, 0, '202603', TRUE, now(), now()),
    ('150', 8, 0, '202603', TRUE, now(), now()),
    ('152', 9, 0, '202603', TRUE, now(), now()),
    ('147', 10, 0, '202603', TRUE, now(), now()),
    ('360', 11, 0, '202603', TRUE, now(), now()),
    ('340', 12, 0, '202603', TRUE, now(), now()),
    ('142', 13, 0, '202603', TRUE, now(), now()),
    ('140', 14, 0, '202603', TRUE, now(), now()),
    ('146', 15, 0, '202603', TRUE, now(), now()),
    ('5531', 16, 0, '202603', TRUE, now(), now()),
    ('202', 17, 0, '202603', TRUE, now(), now()),
    ('120', 18, 0, '202603', TRUE, now(), now()),
    ('420', 19, 0, '202603', TRUE, now(), now()),
    ('421', 20, 0, '202603', TRUE, now(), now()),
    ('641', 21, 0, '202603', TRUE, now(), now()),
    ('동작01', 22, 0, '202603', TRUE, now(), now()),
    ('303', 23, 0, '202603', TRUE, now(), now()),
    ('262', 24, 0, '202603', TRUE, now(), now()),
    ('144', 25, 0, '202603', TRUE, now(), now()),
    ('301', 26, 0, '202603', TRUE, now(), now()),
    ('5524', 27, 0, '202603', TRUE, now(), now()),
    ('370', 28, 0, '202603', TRUE, now(), now()),
    ('4318', 29, 0, '202603', TRUE, now(), now()),
    ('720', 30, 0, '202603', TRUE, now(), now());
