package watoo.grd.nextroute.application.bus.dto;

import java.time.LocalDateTime;

/**
 * 라벨 생성 배치 전용 position projection.
 *
 * <p>bus_position_raw 40+ 컬럼 엔티티 전체를 힙에 올리지 않도록,
 * visit 도출에 필요한 8컬럼만 JPQL constructor expression으로 조회한다.
 * 쿼리에서 stop_flag='1' AND is_run_yn='1'로 정차 행만 가져온다.
 */
public record BusPositionLabelRow(
        Long id,
        String vehicleId,
        String plainNo,
        String sectionId,
        Integer sectionOrder,
        String stopFlag,
        String dataTm,
        LocalDateTime collectedAt) {
}
