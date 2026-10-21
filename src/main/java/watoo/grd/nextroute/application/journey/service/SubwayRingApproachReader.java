package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneySubwayRingProperties;
import watoo.grd.nextroute.application.journey.dto.SubwayRingApproach;
import watoo.grd.nextroute.application.journey.dto.SubwayRingChain;
import watoo.grd.nextroute.application.journey.dto.SubwayRingQuality;
import watoo.grd.nextroute.application.journey.dto.SubwayRingState;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainObservation;
import watoo.grd.nextroute.application.subway.dto.TrainIdentity;
import watoo.grd.nextroute.application.subway.port.out.SubwayTrainLogPort;
import watoo.grd.nextroute.application.subway.service.StationsAheadCalculator;
import watoo.grd.nextroute.application.subway.service.SubwayStopPatternResolver;
import watoo.grd.nextroute.application.subway.service.SubwayLineOrder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 고른 열차가 아직 승차역 전이면 지금 어느 역에 있는지(#368). 링이 승차역 대기 구간일 때만 답한다.
 *
 * <p>근거는 위치 API 의 마지막 관측 역 하나다. 그 역을 승차역 앞쪽 역 목록({@link SubwayLineOrder#behind})에서 찾아 남은 역 수와
 * 지나올 역을 센다. 열차가 막 떠난 역도 그 역으로 말한다(앱 문구 "○○ 지나는 중").
 *
 * <p><b>추측하지 않는다.</b> 링이 STALE 이거나, 관측이 낡았거나, 그 역이 앞쪽 {@value #MAX_STATIONS} 역 안에 없으면(합류점 너머·
 * 다른 갈래 포함) 모름이다. 다만 새 관측이 잠깐 비는 동안은 같은 대기 구간의 직전 값을 이어 쓴다 — 매 tick 사라졌다 나타나면
 * push 가 깜빡인다. 링이 STALE 로 넘어가면 그때 비운다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubwayRingApproachReader {

    /** 승차역 앞으로 찾아볼 역 수. 앱은 최대 3역을 그리고 그보다 멀면 "N역 전"으로 줄인다. */
    static final int MAX_STATIONS = 10;

    private final SubwayTrainLogPort trainLogPort;
    private final SubwayLineOrder lineOrder;
    private final JourneySubwayRingProperties properties;

    /**
     * @param state    이번 tick 의 새 링
     * @param previous 직전 링(없으면 null)
     * @return 대기 구간 밖이거나 모르면 null
     */
    public SubwayRingApproach read(SubwayRingState state, SubwayRingState previous, SubwayRingChain chain,
                                   TrainIdentity train, Instant now) {
        if (train == null || !waitingForTrain(state)) {
            return null;
        }
        SubwayRingChain.Station board = chain.at(0);
        if (board == null || board.canonicalKey() == null) {
            return null;
        }
        Optional<SubwayRingApproach> fresh;
        try {
            fresh = fromPosition(train, board.canonicalKey(), chain.pattern(), now);
        } catch (RuntimeException e) {
            log.debug("[SubwayRing] 다가오는 열차 위치 실패 — journeyId={}: {}", state.journeyId(), e.getMessage());
            fresh = Optional.empty();
        }
        if (fresh.isPresent()) {
            return fresh.get().stationsUntilBoarding() > 0 ? fresh.get() : null;
        }
        return carriedOver(state, previous);
    }

    /** 승차역 구간이고, 열차가 아직 승차역에 서지 않았고, 링이 살아 있다. */
    private static boolean waitingForTrain(SubwayRingState state) {
        return state != null && state.nextStationSequence() == 0 && !state.arrivedAtNextStation()
                && state.quality() != SubwayRingQuality.STALE;
    }

    /**
     * @return 관측이 있고 낡지 않았으면 그 위치. 승차역 자체에 있으면 남은 역 0(부르는 쪽이 비운다). 앞쪽 목록에 없으면 empty
     */
    private Optional<SubwayRingApproach> fromPosition(TrainIdentity train, String boardKey,
                                                      SubwayStopPatternResolver.ResolvedPattern pattern, Instant now) {
        Optional<SubwayTrainObservation> seen = trainLogPort.lastSeen(train);
        if (seen.isEmpty() || seen.get().stationId() == null || seen.get().receivedAt() == null
                || seen.get().receivedAt().isBefore(now.minus(properties.getStaleSourceMaxAge()))) {
            return Optional.empty();
        }
        String at = seen.get().stationId();
        if (at.equals(boardKey)) {
            // 승차역에 들어오는 중이거나 서 있다 — 기존 링(진입·도착)이 맡는다.
            return Optional.of(new SubwayRingApproach(null, 0, List.of()));
        }
        List<StationsAheadCalculator.Stop> behind =
                lineOrder.behind(train.lineId(), train.direction(), boardKey, MAX_STATIONS);
        for (int i = 0; i < behind.size(); i++) {
            if (at.equals(behind.get(i).stationId())) {
                // 급행이면 열차가 서는 역만 센다(D9, subway-express PR-S4). 지금 있는 역은 지나치는 역이어도 남긴다 — 어디 있는지다
                List<StationsAheadCalculator.Stop> ahead = behind.subList(i, behind.size());
                List<String> names = new java.util.ArrayList<>(ahead.size());
                for (int j = 0; j < ahead.size(); j++) {
                    if (j == 0 || pattern == null || pattern.stopsAt(ahead.get(j).stationId())) {
                        names.add(ahead.get(j).name());
                    }
                }
                return Optional.of(new SubwayRingApproach(names.get(0), names.size(), names));
            }
        }
        return Optional.empty();
    }

    /** 새 근거가 없을 때 같은 고정·같은 대기 구간의 직전 값. */
    private static SubwayRingApproach carriedOver(SubwayRingState state, SubwayRingState previous) {
        if (previous == null || previous.approach() == null || previous.nextStationSequence() != 0
                || !previous.matches(state.routeRevision(), state.legIndex(), state.bindingVersion())) {
            return null;
        }
        return previous.approach();
    }
}
