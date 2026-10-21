package watoo.grd.nextroute.application.subway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationsAheadResponse;
import watoo.grd.nextroute.application.arrival.port.in.GetSubwayStationsAheadUseCase;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainObservation;
import watoo.grd.nextroute.application.subway.dto.TrainIdentity;
import watoo.grd.nextroute.application.subway.port.out.SubwayTrainLogPort;
import watoo.grd.nextroute.application.subway.service.SubwayTrainKindClassifier.Sighting;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 하차역 고르기(#283)에 탄 열차의 정차를 붙인다(subway-express PR-S3).
 *
 * <pre>
 * 완행 남은 역   {@link SubwayStationsAheadService} — 그대로({@code stopsAway} 도 완행 기준, 앱 키 호환)
 * 열차          {@code trainNo} 를 주면 {@link SubwayTrainKindJudge} 로 종류·급행 서비스·승차역 정차
 * 역마다         해석된 급행이면 {@code stopsHere}(종착 이후 false)와 서는 역만 센 {@code expressStopsAway}(D9)
 * </pre>
 *
 * <p>열차 근거는 캐시 snapshot 의 그 열차 모든 행(도착 API, 행마다 종류가 다르다)과 위치 기록(원시 {@code directAt}, 종착 역
 * id)이다. 둘 다 없어도 번호만으로 판정한다(4호선 급행 44xx 는 번호가 유일한 근거). <b>열차 정보는 덧붙이는 값</b>이다 —
 * 실패하면 열차 없이 목록만 준다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubwayStationsAheadQueryService implements GetSubwayStationsAheadUseCase {

    private final SubwayStationsAheadService stationsAheadService;
    private final SubwayTrainKindJudge kindJudge;
    private final SubwayRealtimeArrivalReader arrivalReader;
    private final SubwayTrainLogPort trainLogPort;
    private final ServiceDateResolver serviceDateResolver;
    private final Clock clock;

    @Override
    public SubwayStationsAheadResponse getStationsAhead(String stationId, String direction, String destination,
                                                        String trainNo) {
        SubwayStationsAheadResponse base = stationsAheadService.getStationsAhead(stationId, direction, destination);
        if (trainNo == null || trainNo.isBlank()) {
            return base;
        }
        try {
            return withTrain(base, trainNo.trim(), destination);
        } catch (RuntimeException e) {
            log.warn("[StationsAhead] 열차 정차 붙이기 실패 — 열차 없이 응답 station={} trainNo={}: {}",
                    stationId, trainNo, e.getMessage());
            return base;
        }
    }

    private SubwayStationsAheadResponse withTrain(SubwayStationsAheadResponse base, String trainNo,
                                                  String requestedDestination) {
        String lineId = base.lineId();
        String direction = base.direction();
        List<Sighting> sightings = new ArrayList<>();
        String destination = null;
        for (SubwayRealtimeTrain row : arrivalReader.trainRows(lineId, trainNo)) {
            sightings.add(Sighting.ofTrainType(row.getTrainType()));
            if (destination == null && direction.equals(row.getDirection())) {
                destination = row.getDestinationName();
            }
        }
        String terminalId = null;
        TrainIdentity identity = new TrainIdentity(serviceDateResolver.resolve(clock.instant()), lineId, direction,
                trainNo);
        Optional<SubwayTrainObservation> seen = lastSeen(identity);
        if (seen.isPresent()) {
            sightings.add(Sighting.ofDirectAt(seen.get().express(), seen.get().directAtRaw()));
            terminalId = seen.get().terminalId();
        }
        if (destination == null) {
            destination = terminalId != null ? terminalId : requestedDestination;
        }

        SubwayTrainKindJudge.Judgement judgement = kindJudge.judge(
                new SubwayTrainKindJudge.Train(lineId, direction, trainNo, destination, sightings),
                base.boardStationId(), null);
        SubwayStationsAheadResponse.TrainInfo train = new SubwayStationsAheadResponse.TrainInfo(
                judgement.kind(), judgement.label(), judgement.serviceId(), judgement.stopsAtBoarding());
        if (judgement.pattern() == null) {
            return base.withTrain(train, base.stations());
        }
        List<SubwayStationsAheadResponse.Station> stations = new ArrayList<>(base.stations().size());
        int expressStops = 0;
        for (SubwayStationsAheadResponse.Station station : base.stations()) {
            Boolean stops = judgement.stopsAt(station.stationId());
            Integer count = null;
            if (Boolean.TRUE.equals(stops)) {
                count = ++expressStops;
            }
            stations.add(station.withStops(stops, count));
        }
        return base.withTrain(train, stations);
    }

    /** 위치 기록은 근거 하나일 뿐이다 — 못 읽으면 없이 간다. */
    private Optional<SubwayTrainObservation> lastSeen(TrainIdentity identity) {
        try {
            return trainLogPort.lastSeen(identity);
        } catch (RuntimeException e) {
            log.debug("[StationsAhead] 위치 기록 조회 실패 {}: {}", identity.asKeySuffix(), e.getMessage());
            return Optional.empty();
        }
    }
}
