package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.domain.subway.repository.NearbySubwayStationProjection;
import watoo.grd.nextroute.domain.subway.service.SubwayDataService;

import java.util.List;
import java.util.Optional;

/** ODSAY 역 이름·좌표를 내부 {@code subway_station.statn_id}로 해석한다. */
@Component
@RequiredArgsConstructor
public class SubwayStationResolver {

    private static final double SEARCH_RADIUS_METERS = 120;
    private static final int SEARCH_LIMIT = 10;

    private final SubwayDataService subwayDataService;

    /**
     * 좌표와 노선, 역 이름이 모두 일치하는 후보가 정확히 하나일 때만 확정한다.
     *
     * <p>환승역이나 동명역을 가장 가까운 한 건으로 고르면 반대 노선 역이 들어갈 수 있으므로,
     * 둘 이상이면 빈 결과를 돌려 호출자가 자동 실시간 대조를 끈다.
     */
    public Optional<String> resolve(String lineId, String stationName, Double longitude, Double latitude) {
        if (isBlank(lineId) || isBlank(stationName) || longitude == null || latitude == null) {
            return Optional.empty();
        }

        List<String> candidates = subwayDataService
                .findNearbyStations(latitude, longitude, SEARCH_RADIUS_METERS, SEARCH_LIMIT)
                .stream()
                .filter(station -> lineId.equals(station.getLineId()))
                .filter(station -> sameName(stationName, station))
                .map(NearbySubwayStationProjection::getStationId)
                .distinct()
                .toList();

        return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
    }

    private boolean sameName(String expected, NearbySubwayStationProjection station) {
        return normalize(expected).equals(normalize(station.getStationName()));
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", "")
                .replaceAll("\\(.*\\)", "")
                .replaceFirst("역$", "");
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
