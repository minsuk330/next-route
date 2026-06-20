-- 정류장 선택 UI 조회 API용 인덱스.
-- bus_route_stop 역조회(정류장→노선), 노선→정류장, 지원 정류장 필터, 자동완성 prefix 검색.

-- 노선→정류장 순서 조회 (findByRouteIdOrderBySeq, findStopsByRouteId)
create index if not exists idx_bus_route_stop_route_id
    on bus_route_stop (route_id);

-- 정류장→노선 역조회 (findRoutesByStopId) + 지원 정류장 필터 커버링 (findSupportedStopIds)
create index if not exists idx_bus_route_stop_stop_id_route_id
    on bus_route_stop (stop_id, route_id);

-- 버스번호 prefix 자동완성 (findTop20ByRouteNameStartingWith)
create index if not exists idx_bus_route_route_name
    on bus_route (route_name);

-- 정류장명 prefix 자동완성 (findTop20ByStopNameStartingWith)
create index if not exists idx_bus_stop_stop_name
    on bus_stop (stop_name);
