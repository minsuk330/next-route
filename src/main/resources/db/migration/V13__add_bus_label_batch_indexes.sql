-- 버스 라벨 생성 배치(BusArrivalLabelGenerationService) 조회 경로 인덱스.
-- 월 20~30GB급 position raw가 쌓이면 인덱스 없이는 매일 large scan이 된다.

-- 1) candidate finalized_at 단독 범위 조회.
--    BusArrivalCandidateRawRepository.findByFinalizedAtBetween(from, to).
--    기존 idx_bus_arrival_candidate_raw_scope_finalized는 leading이 route_id라
--    finalized_at 단독 범위 조회에는 사용되지 않는다.
create index if not exists idx_bus_arrival_candidate_raw_finalized
    on bus_arrival_candidate_raw (finalized_at);

-- 2) route별 position 범위 조회.
--    BusPositionRawRepository.findByRouteIdAndCollectedAtBetween(routeId, from, to).
create index if not exists idx_bus_position_raw_route_collected
    on bus_position_raw (route_id, collected_at);
