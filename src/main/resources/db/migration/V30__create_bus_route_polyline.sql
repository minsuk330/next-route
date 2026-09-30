CREATE TABLE bus_route_polyline (
    id          BIGSERIAL PRIMARY KEY,
    route_id    VARCHAR(255) NOT NULL,
    point_count INT          NOT NULL,
    polyline    JSONB        NOT NULL,
    source      VARCHAR(30)  NOT NULL DEFAULT 'SEOUL_BUS',
    fetched_at  TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMP(6),
    updated_at  TIMESTAMP(6),
    deleted_at  TIMESTAMP(6),
    CONSTRAINT uk_bus_route_polyline_route UNIQUE (route_id),
    CONSTRAINT fk_bus_route_polyline_route
        FOREIGN KEY (route_id) REFERENCES bus_route (route_id)
);
