package watoo.grd.nextroute.application.arrival.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.arrival.dto.SubwayArrivalResponse;
import watoo.grd.nextroute.application.arrival.port.in.GetSubwayArrivalUseCase;
import watoo.grd.nextroute.application.subway.dto.SubwayArrivalCode;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;
import watoo.grd.nextroute.application.subway.service.SubwayRealtimeArrivalReader;
import watoo.grd.nextroute.application.subway.service.SubwayStationsAway;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
import watoo.grd.nextroute.domain.subway.repository.NearbySubwayStationProjection;
import watoo.grd.nextroute.domain.subway.service.SubwayDataService;

import java.util.*;

@Service
@RequiredArgsConstructor
public class SubwayArrivalQueryService implements GetSubwayArrivalUseCase {

    private final SubwayRealtimeArrivalReader arrivalReader;
    private final SubwayDataService subwayDataService;

    @Override
    public List<SubwayArrivalResponse> getArrivals(double lat, double lon, Integer wayCode, String lineId) {
        Optional<ResolvedStation> target = resolveStation(lat, lon, lineId);
        if (target.isEmpty()) return List.of();

        return fromSnapshot(target.get().lineId(), target.get().stationId(), wayCode);
    }

    /**
     * 좌표와 내부 line_id를 이용해 실시간 조회 대상 역을 확정한다.
     *
     * <p>기존 도착 조회와 여정 시작이 같은 역 선택 규칙을 사용하도록 공개한 재사용 진입점이다.
     */
    public Optional<ResolvedStation> resolveStation(double lat, double lon, String lineId) {
        List<NearbySubwayStationProjection> nearby =
                subwayDataService.findNearbyStations(lat, lon, 50, 5);
        if (nearby.isEmpty()) {
            return Optional.empty();
        }
        NearbySubwayStationProjection target = selectStation(nearby, lineId);
        return Optional.of(new ResolvedStation(
                target.getStationId(), target.getStationName(), target.getLineId()));
    }

    /**
     * 후보가 2개 이상이고 line_id가 주어지면 해당 호선으로 1개만 추출.
     * 미지정/미일치 시 최근접(첫번째) 역 사용.
     */
    private NearbySubwayStationProjection selectStation(
            List<NearbySubwayStationProjection> nearby, String lineId) {
        if (nearby.size() >= 2 && lineId != null && !lineId.isBlank()) {
            return nearby.stream()
                    .filter(s -> lineId.equals(s.getLineId()))
                    .findFirst()
                    .orElse(nearby.get(0));
        }
        return nearby.get(0);
    }

    private List<SubwayArrivalResponse> fromSnapshot(
            String lineId, String stationId, Integer wayCode) {

        return arrivalReader.arrivals(lineId, stationId, wayCode).stream()
                .map(arrival -> SubwayArrivalResponse.builder()
                        .lineId(arrival.train().getLineId())
                        .direction(arrival.train().getDirection())
                        .arrivalSeconds(arrival.arrivalSeconds())
                        .stationsAway(stationsAway(arrival.train()))
                        .currentMessage(arrival.train().getCurrentMessage())
                        .destinationName(arrival.train().getDestinationName())
                        .trainType(arrival.train().getTrainType())
                        .arrivalCode(arrival.train().getArrivalCode())
                        .build())
                .toList();
    }

    /**
     * 남은 초가 없는 열차에 <b>거리라도</b> 붙인다.
     *
     * <p>원천은 절반 가까운 열차에 시간을 주지 않고 "전역 도착", "[3]번째 전역" 처럼 거리로만
     * 답한다. 그 열차를 초가 없다는 이유로 감추면 사용자는 오고 있는 열차를 못 본다.
     */
    private Integer stationsAway(SubwayRealtimeTrain train) {
        return SubwayStationsAway.resolve(
                SubwayArrivalCode.from(train.getArrivalCode()), train.getCurrentMessage());
    }

    public record ResolvedStation(String stationId, String stationName, String lineId) {
    }
}
