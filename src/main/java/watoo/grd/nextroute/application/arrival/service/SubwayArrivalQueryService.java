package watoo.grd.nextroute.application.arrival.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.arrival.dto.SubwayArrivalResponse;
import watoo.grd.nextroute.application.arrival.port.in.GetSubwayArrivalUseCase;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalRaw;
import watoo.grd.nextroute.domain.subway.service.SubwayDataService;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SubwayArrivalQueryService implements GetSubwayArrivalUseCase {

    private final SubwayDataService subwayDataService;
    private static final int WINDOW_MINUTES = 5;

    @Override
    public List<SubwayArrivalResponse> getArrivals(String stationId) {
        LocalDateTime from = LocalDateTime.now().minusMinutes(WINDOW_MINUTES);
        List<SubwayArrivalRaw> raw = subwayDataService.findLatestArrivalsByStationId(stationId, from);

        // deduplicate by (direction, trainNo) — keep the most recently collected
        Map<String, SubwayArrivalRaw> deduped = new LinkedHashMap<>();
        raw.stream()
                .sorted(Comparator.comparing(SubwayArrivalRaw::getCollectedAt).reversed())
                .forEach(r -> {
                    String key = r.getDirection() + ":" + r.getTrainNo();
                    deduped.putIfAbsent(key, r);
                });

        return deduped.values().stream()
                .sorted(Comparator.comparingInt(r ->
                        Optional.ofNullable(r.getArrivalSeconds()).orElse(Integer.MAX_VALUE)))
                .map(r -> SubwayArrivalResponse.builder()
                        .lineId(r.getLineId())
                        .direction(r.getDirection())
                        .arrivalSeconds(r.getArrivalSeconds())
                        .currentMessage(r.getCurrentMessage())
                        .destinationName(r.getDestinationName())
                        .trainType(r.getTrainType())
                        .arrivalCode(r.getArrivalCode())
                        .build())
                .toList();
    }
}
