package watoo.grd.nextroute.application.bus.dto;

/**
 * 라벨 생성 배치 전용 candidate projection.
 *
 * <p>bus_arrival_candidate_raw 60+ 컬럼 엔티티 전체를 힙에 올리지 않도록,
 * 라벨 생성에 필요한 12컬럼만 JPQL constructor expression으로 조회한다.
 */
public record BusArrivalCandidateLabelRow(
        Long id,
        String lifecycleId,
        String routeId,
        String vehicleId,
        String vehicleIdentity,
        String vehicleIdentityType,
        String stopId,
        Integer seq,
        Integer arrivalOrder,
        String arrivalMsg,
        String dataTimestamp,
        Integer predictTime) {
}
