CREATE TABLE ml_subway_delay_truth
(
    id                      BIGSERIAL PRIMARY KEY,

    service_date            DATE             NOT NULL,

    line_id                 VARCHAR(50)      NOT NULL,
    station_id              VARCHAR(50)      NOT NULL,
    station_name            VARCHAR(100),
    tago_station_id         VARCHAR(50),

    direction               VARCHAR(10)      NOT NULL,
    day_type                VARCHAR(10)      NOT NULL,

    train_no                VARCHAR(50)      NOT NULL,
    train_type              VARCHAR(50),

    destination_id          VARCHAR(50),
    destination_name        VARCHAR(100),
    end_station_name        VARCHAR(100),

    arrival_event_id        BIGINT           NOT NULL,
    timetable_id            BIGINT           NOT NULL,

    scheduled_arrival_at    TIMESTAMP        NOT NULL,
    actual_arrived_at       TIMESTAMP        NOT NULL,
    delay_seconds           INT              NOT NULL,

    event_source            VARCHAR(50)      NOT NULL,
    scheduled_time_source   VARCHAR(50),
    timetable_order_index   INT,
    event_order_index       INT,

    match_group_key         VARCHAR(200),
    match_strategy          VARCHAR(50)      NOT NULL,
    match_confidence        DOUBLE PRECISION,

    excluded_from_training  BOOLEAN          NOT NULL DEFAULT FALSE,
    exclude_reason          VARCHAR(50),

    created_at              TIMESTAMP        NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP        NOT NULL DEFAULT NOW(),
    deleted_at              TIMESTAMP
);

ALTER TABLE ml_subway_delay_truth
    OWNER TO myuser;

-- 1관측 1라벨 ML 불변식 (한 실측 도착 event는 정답 1개) — uk가 인덱스도 생성
ALTER TABLE ml_subway_delay_truth
    ADD CONSTRAINT uk_ml_delay_truth_event UNIQUE (arrival_event_id);

-- 재실행 멱등 보조 (1차 멱등 수단은 delete-and-insert by service_date)
ALTER TABLE ml_subway_delay_truth
    ADD CONSTRAINT uk_ml_delay_truth_observation
        UNIQUE (service_date, line_id, station_id, direction, train_no, scheduled_arrival_at);

CREATE INDEX idx_ml_delay_truth_service_date
    ON ml_subway_delay_truth (service_date);

CREATE INDEX idx_ml_delay_truth_station_line_dir_date
    ON ml_subway_delay_truth (station_id, line_id, direction, service_date);

CREATE INDEX idx_ml_delay_truth_timetable
    ON ml_subway_delay_truth (timetable_id);

-- 학습 쿼리용 부분 인덱스 (arrival_event_id 인덱스는 uk가 이미 생성하므로 생략)
CREATE INDEX idx_ml_delay_truth_training
    ON ml_subway_delay_truth (service_date)
    WHERE excluded_from_training = FALSE;
