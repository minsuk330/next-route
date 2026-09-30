-- TMAP 보행자 경로 응답 캐시
CREATE TABLE tmap_walk_cache (
    id               BIGSERIAL PRIMARY KEY,
    cache_key        VARCHAR(255) NOT NULL UNIQUE,
    start_x          DOUBLE PRECISION NOT NULL,
    start_y          DOUBLE PRECISION NOT NULL,
    end_x            DOUBLE PRECISION NOT NULL,
    end_y            DOUBLE PRECISION NOT NULL,
    search_option    SMALLINT NOT NULL,
    walk_segment     JSONB NOT NULL,
    total_distance   INT,
    total_time       INT,
    hit_count        INT NOT NULL DEFAULT 0,
    is_negative      BOOLEAN NOT NULL DEFAULT FALSE,
    fetched_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ
);

CREATE INDEX idx_tmap_walk_cache_expires ON tmap_walk_cache(expires_at);
