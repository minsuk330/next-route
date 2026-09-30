ALTER TABLE bus_route_polyline
    ADD CONSTRAINT ck_bus_route_polyline_point_count
        CHECK (point_count >= 2);
