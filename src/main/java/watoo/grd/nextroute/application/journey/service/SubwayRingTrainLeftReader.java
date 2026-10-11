package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.SubwayRingChain;
import watoo.grd.nextroute.application.journey.dto.SubwayRingState;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainObservation;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainStatus;
import watoo.grd.nextroute.application.subway.dto.TrainIdentity;
import watoo.grd.nextroute.application.subway.port.out.SubwayTrainLogPort;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 하차역 도착 뒤 우리 열차가 하차역을 <b>출발한</b> 시각(#380).
 *
 * <p>앱은 하차역 반경 GPS 가 없으면 "하차역 도착 상태가 된 지 120초"로 탑승을 끝낸다(#371). 열차가 떠났다는 사실을 서버가
 * 알려 주면 그 추정을 대신한다. 위치 로그의 하차역 출발 사건(처음 출발로 본 {@code recptnDt}) — 다음 역으로 옮겨 간 관측도
 * 추적기가 하차역 출발 사건으로 남긴다. 사건이 아직 없으면 지금 위치가 하차역 {@code trainSttus=2} 인지 본다.
 *
 * <p>한 번 채우면 같은 고정 세대 안에서 바꾸지 않는다 — 출발은 시간이 지나도 바뀌지 않는 사실이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubwayRingTrainLeftReader {

    /** 하차역 출발 사건을 찾아볼 폭. 하차역 도착 뒤 링이 사는 동안을 넉넉히 덮는다. */
    private static final Duration LOOKBACK = Duration.ofMinutes(30);

    private final SubwayTrainLogPort trainLogPort;

    /** @return 하차역 출발 시각. 하차역 도착 전이거나 아직 못 봤으면 null */
    public Instant read(SubwayRingState state, SubwayRingState previous, SubwayRingChain chain, TrainIdentity train,
                        Instant now) {
        if (state == null || chain == null || train == null) {
            return null;
        }
        if (previous != null && previous.trainLeftAlightingAt() != null
                && previous.matches(state.routeRevision(), state.legIndex(), state.bindingVersion())) {
            return previous.trainLeftAlightingAt();
        }
        if (state.nextStationSequence() != chain.alightingSequence() || !state.arrivedAtNextStation()) {
            return null;
        }
        SubwayRingChain.Station alighting = chain.at(chain.alightingSequence());
        if (alighting == null || alighting.canonicalKey() == null) {
            return null;
        }
        Instant arrivedAt = state.progressBaseAt();
        return departureEvent(train, alighting.canonicalKey(), arrivedAt, now)
                .or(() -> departedNow(train, alighting.canonicalKey(), arrivedAt))
                .orElse(null);
    }

    private Optional<Instant> departureEvent(TrainIdentity train, String alightingKey, Instant arrivedAt, Instant now) {
        try {
            return trainLogPort.findDepartures(train.serviceDate(), train.lineId(), train.direction(), alightingKey,
                            now.minus(LOOKBACK), now)
                    .stream()
                    .filter(departure -> Objects.equals(train.trainNo(), departure.trainNo()))
                    .map(departure -> departure.event().to())
                    .filter(Objects::nonNull)
                    // 하차역 도착보다 이른 출발은 같은 번호의 이전 운행이다.
                    .filter(at -> arrivedAt == null || !at.isBefore(arrivedAt))
                    .min(Instant::compareTo);
        } catch (RuntimeException e) {
            log.debug("[SubwayRing] 하차역 출발 사건 조회 실패 — {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Instant> departedNow(TrainIdentity train, String alightingKey, Instant arrivedAt) {
        Optional<SubwayTrainObservation> seen = trainLogPort.lastSeen(train);
        return seen.filter(o -> alightingKey.equals(o.stationId()) && o.status() == SubwayTrainStatus.DEPARTED
                        && o.receivedAt() != null && (arrivedAt == null || !o.receivedAt().isBefore(arrivedAt)))
                .map(SubwayTrainObservation::receivedAt);
    }
}
