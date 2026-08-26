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
import watoo.grd.nextroute.domain.subway.repository.SubwayStationRepository;

import java.util.*;

@Service
@RequiredArgsConstructor
public class SubwayArrivalQueryService implements GetSubwayArrivalUseCase {

    /** 좌표가 정확히 맞을 때의 반경. 대부분 여기서 끝난다. */
    static final double INITIAL_RADIUS = 50.0;

    /** 후보가 없을 때 넓히는 단위. */
    static final double RADIUS_STEP = 50.0;

    /**
     * 더 넓히지 않는 한계.
     *
     * <p>실측에서 50m 로는 잡히지 않고 300m 에서 잡히는 역이 있었다. 역 좌표가 역사 중심인데
     * 경로 좌표는 승강장·출구를 가리키는 등 두 좌표의 기준이 다른 자리다.
     *
     * <p>여기서 더 넓히지 않는 이유는 <b>도보 거리</b>다. 300m 를 넘어가면 "근처 역"이 아니라
     * 다른 역일 가능성이 높아지고, 그때 내려 준 도착정보는 사용자가 틀린 줄도 모른다.
     */
    static final double MAX_RADIUS = 300.0;

    private static final int CANDIDATE_LIMIT = 5;

    private final SubwayRealtimeArrivalReader arrivalReader;
    private final SubwayStationRepository subwayStationRepository;

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
     *
     * <p><b>후보가 나올 때까지 반경을 넓힌다.</b> 고정 50m 로는 역이 통째로 안 잡히는 자리가
     * 있었다 — 역 좌표와 경로 좌표의 기준이 서로 달라서다. 그때 빈 목록을 내려 주면 사용자는
     * 눈앞의 역을 두고 "도착정보 없음"을 본다.
     *
     * <p>처음부터 넓게 찾지 않고 <b>단계로</b> 넓히는 이유는, 좁은 반경에서 답이 나오는 흔한
     * 경우의 판단 근거를 그대로 두기 위해서다. 50m 안에 후보가 있으면 이 변경 전과 똑같이
     * 동작한다. 넓힌 반경은 좁은 반경이 침묵할 때만 쓰인다.
     */
    public Optional<ResolvedStation> resolveStation(double lat, double lon, String lineId) {
        for (double radius = INITIAL_RADIUS; radius <= MAX_RADIUS; radius += RADIUS_STEP) {
            List<NearbySubwayStationProjection> nearby =
                    subwayStationRepository.findNearby(lat, lon, radius, CANDIDATE_LIMIT);
            if (nearby.isEmpty()) {
                continue;
            }
            NearbySubwayStationProjection target = selectStation(nearby, lineId);
            return Optional.of(new ResolvedStation(
                    target.getStationId(), target.getStationName(), target.getLineId()));
        }
        return Optional.empty();
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
