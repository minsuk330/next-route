CREATE TABLE subway_arrival_event_match_issue
(
    id                     BIGSERIAL PRIMARY KEY,
    service_date           DATE         NOT NULL,
    issue_type             VARCHAR(50)  NOT NULL,
    line_id                VARCHAR(50),
    station_id             VARCHAR(50),
    station_name           VARCHAR(100),
    tago_station_id        VARCHAR(50),
    direction              VARCHAR(10),
    day_type               VARCHAR(10),
    match_group_key        VARCHAR(200),
    timetable_id           BIGINT,
    arrival_event_id       BIGINT,
    scheduled_arrival_at   TIMESTAMP,
    actual_arrived_at      TIMESTAMP,
    timetable_order_index  INT,
    event_order_index      INT,
    timetable_count        INT,
    event_count            INT,
    scheduled_time_source  VARCHAR(50),
    details                TEXT,
    created_at             TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted_at             TIMESTAMP
);

ALTER TABLE subway_arrival_event_match_issue
    OWNER TO myuser;

CREATE INDEX idx_sam_issue_service_date      ON subway_arrival_event_match_issue(service_date);
CREATE INDEX idx_sam_issue_match_group_key   ON subway_arrival_event_match_issue(match_group_key);
