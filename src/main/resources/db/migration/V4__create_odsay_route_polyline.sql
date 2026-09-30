-- ODsay 지하철 노선 전체 폴리라인 캐시
CREATE TABLE odsay_route_polyline (
    id                 BIGSERIAL PRIMARY KEY,
    odsay_route_id     VARCHAR(50)  NOT NULL,
    lane_class         INT          NOT NULL,
    lane_type          INT,
    point_count        INT          NOT NULL,
    source_map_object  VARCHAR(255) NOT NULL,
    polyline           JSONB        NOT NULL,
    fetched_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_odsay_route_polyline_route UNIQUE (odsay_route_id, lane_class)
);

CREATE INDEX idx_odsay_route_polyline_route
    ON odsay_route_polyline (odsay_route_id, lane_class);

-- 주요 호선 warm-up seed (선택 사항)
CREATE TABLE odsay_route_line_seed (
    id             BIGSERIAL    PRIMARY KEY,
    odsay_route_id VARCHAR(50)  NOT NULL,
    lane_class     INT          NOT NULL DEFAULT 2,
    lane_type      INT,
    line_id        VARCHAR(50),
    line_name      VARCHAR(100) NOT NULL,
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_odsay_route_line_seed UNIQUE (odsay_route_id, lane_class)
);

-- 검색 miss 발생 시 등록되는 lazy collection job
CREATE TABLE odsay_route_polyline_collection_job (
    id                    BIGSERIAL    PRIMARY KEY,
    odsay_route_id        VARCHAR(50)  NOT NULL,
    lane_class            INT          NOT NULL,
    status                VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    requested_count       INT          NOT NULL DEFAULT 1,
    last_map_obj_fragment VARCHAR(255),
    last_error            TEXT,
    requested_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    started_at            TIMESTAMPTZ,
    finished_at           TIMESTAMPTZ,
    CONSTRAINT uk_odsay_route_polyline_collection_job UNIQUE (odsay_route_id, lane_class)
);

CREATE INDEX idx_odsay_route_polyline_collection_job_status
    ON odsay_route_polyline_collection_job (status, requested_at);
