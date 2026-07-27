-- 노선별 경유 정류장은 seq가 스냅샷 내 식별자다.
DELETE FROM bus_route_stop duplicate
USING bus_route_stop keeper
WHERE duplicate.route_id = keeper.route_id
  AND duplicate.seq = keeper.seq
  AND duplicate.id > keeper.id;

ALTER TABLE bus_route_stop
    ADD CONSTRAINT uk_bus_route_stop_route_seq UNIQUE (route_id, seq);

CREATE TABLE bus_route_segment_polyline (
    id                    BIGSERIAL PRIMARY KEY,
    route_id              VARCHAR(255) NOT NULL,
    from_seq              INTEGER      NOT NULL,
    to_seq                INTEGER      NOT NULL,
    from_stop_id          VARCHAR(255) NOT NULL,
    to_stop_id            VARCHAR(255) NOT NULL,
    section_id            VARCHAR(255),
    point_count           INTEGER      NOT NULL DEFAULT 0,
    polyline              JSONB,
    geom                  geometry(LineString, 4326),
    start_fraction        DOUBLE PRECISION,
    end_fraction          DOUBLE PRECISION,
    start_error_m         DOUBLE PRECISION,
    end_error_m           DOUBLE PRECISION,
    expected_distance_m   DOUBLE PRECISION,
    actual_distance_m     DOUBLE PRECISION,
    distance_error_m      DOUBLE PRECISION,
    quality_status        VARCHAR(20)  NOT NULL,
    quality_reason        VARCHAR(255),
    matcher_version       VARCHAR(30)  NOT NULL,
    source_fetched_at     TIMESTAMPTZ  NOT NULL,
    created_at            TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_bus_route_segment_polyline_route
        FOREIGN KEY (route_id) REFERENCES bus_route (route_id),
    CONSTRAINT uk_bus_route_segment_polyline_route_seq
        UNIQUE (route_id, from_seq, to_seq),
    CONSTRAINT ck_bus_route_segment_polyline_seq
        CHECK (from_seq < to_seq),
    CONSTRAINT ck_bus_route_segment_polyline_quality
        CHECK (quality_status IN ('OK', 'REVIEW', 'FAILED')),
    CONSTRAINT ck_bus_route_segment_polyline_points
        CHECK ((quality_status = 'FAILED' AND point_count = 0)
            OR (quality_status <> 'FAILED' AND point_count >= 2)),
    CONSTRAINT ck_bus_route_segment_polyline_fraction
        CHECK ((start_fraction IS NULL AND end_fraction IS NULL)
            OR (start_fraction >= 0 AND end_fraction <= 1 AND start_fraction <= end_fraction))
);

CREATE INDEX idx_bus_route_segment_polyline_route_section
    ON bus_route_segment_polyline (route_id, section_id);

CREATE INDEX idx_bus_route_segment_polyline_geom
    ON bus_route_segment_polyline USING GIST (geom);
