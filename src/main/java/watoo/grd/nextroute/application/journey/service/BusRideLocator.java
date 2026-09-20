package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.bus.dto.BusArrivalCandidate;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.route.service.BusTimeParser;
import watoo.grd.nextroute.common.config.ClockConfig;
import watoo.grd.nextroute.domain.bus.repository.BusRouteStopRepository;
import watoo.grd.nextroute.domain.bus.repository.RouteStopProjection;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 지금 타고 있는 버스가 <b>노선의 몇 번째 정류장</b>에 있고, <b>어느 차가 내 차</b>인지
 * (journey-reroute-nextstop).
 *
 * <p>두 곳이 같은 판단을 한다 — 하차 후보 자리를 여는 쪽({@link UpcomingStopWindowResolver})과
 * 수락 직전에 그 자리가 아직 살아 있는지 다시 보는 쪽({@link RideTruncationResolver})이다. 규칙이
 * 갈리면 <b>제안할 때는 탈 수 있다고 하고 수락할 때는 못 탄다고 하는</b> 어긋남이 생기므로 한
 * 자리에 둔다.
 *
 * <h2>내 차 고르기는 한쪽으로만 느슨하다</h2>
 * 내 앞의 차를 잘못 잡으면 도착이 실제보다 <b>이르게</b> 나와 자리가 버려지거나 조금 이른 시각을
 * 말할 뿐이지만, 내 뒤의 차를 잡으면 도착이 <b>늦게</b> 나와 "아직 여유 있다"는 안내가 되고
 * 사용자는 내릴 곳을 지나친다. 그래서 {@code currentSeq} 미만은 절대 받지 않는다.
 */
@Component
@RequiredArgsConstructor
public class BusRideLocator {

    private final BusRouteStopRepository busRouteStopRepository;

    /**
     * 주행 중인 버스가 지금 어디인지.
     *
     * <p>노선의 정류장 목록에서 승·하차 순번을 찾고, 앱이 보내 준 <b>남은 정류장 수</b>를 하차
     * 순번에서 뺀다. 세 값 중 하나라도 애매하면 위치를 만들지 않는다 — 순환노선처럼 같은 정류장이
     * 한 노선에 두 번 나오면 어느 쪽인지 단정할 수 없고, 잘못 단정하면 지나간 정류장을 앞으로 올
     * 정류장으로 착각한다.
     *
     * @param progressMaxAgeSec 진행 관측이 이보다 오래됐으면 모른다고 답한다
     * @return 못 풀면 사유가 담긴 실패. 사유는 그대로 지표 태그가 된다
     */
    public Located locate(JourneyLeg ride, int progressMaxAgeSec, Instant now) {
        Integer remainingStops = ride.getRemainingStops();
        Instant observedAt = ride.getProgressObservedAt();
        if (remainingStops == null || remainingStops < 0 || observedAt == null) {
            return Located.failed("no-progress");
        }
        if (Duration.between(observedAt, now).getSeconds() > progressMaxAgeSec) {
            // 낡은 관측으로 자리를 열면 이미 지나간 정류장을 제안한다. 모르는 편이 낫다.
            return Located.failed("stale-progress");
        }

        List<RouteStopProjection> stops = busRouteStopRepository.findStopsByRouteId(ride.getRouteId());
        Integer boardSeq = uniqueSeq(stops, ride.getStartStopId(), null);
        if (boardSeq == null) {
            return Located.failed("ambiguous-stop");
        }
        Integer alightSeq = uniqueSeq(stops, ride.getEndStopId(), boardSeq);
        if (alightSeq == null) {
            return Located.failed("ambiguous-stop");
        }
        int current = alightSeq - remainingStops;
        if (current < boardSeq || current >= alightSeq) {
            // 남은 정류장 수가 이 주행 구간과 맞지 않는다. 억지로 위치를 만들지 않는다.
            return Located.failed("position-out-of-range");
        }
        return Located.of(new Position(stops, boardSeq, current, alightSeq));
    }

    /**
     * 접근 중인 차량 중 <b>내 차</b>.
     *
     * <p>한 번이라도 차량을 잡았으면 그 뒤 정류장부터는 같은 차량만 받는다 — 구간 순번은 내가
     * 지나가는 동안 밀리지만 차량 번호는 안 밀린다. 아직 못 잡았으면 구간 순번으로 고르되,
     * <b>내 뒤의 차는 절대 받지 않는다</b>(클래스 주석의 비대칭).
     */
    public Optional<BusArrivalCandidate> pickMyBus(List<BusArrivalInfo> arrivals, String vehicleId,
                                                   int currentSeq, Instant now) {
        List<BusArrivalCandidate> candidates = arrivals.stream()
                .flatMap(info -> BusArrivalCandidate.from(info,
                        LocalDateTime.ofInstant(now, ClockConfig.KST)).stream())
                .toList();
        if (vehicleId != null) {
            return candidates.stream()
                    .filter(candidate -> vehicleId.equals(candidate.vehicleId()))
                    .findFirst();
        }
        return candidates.stream()
                .filter(candidate -> candidate.sectionOrder() != null)
                .filter(candidate -> candidate.sectionOrder() == currentSeq
                        || candidate.sectionOrder() == currentSeq + 1)
                // 같은 구간이 우선이다. 다음 구간은 "방금 정류장을 지났다"를 받아 주는 여유일 뿐이다.
                .min((left, right) -> Integer.compare(left.sectionOrder(), right.sectionOrder()));
    }

    /** 관측 시각 + 남은 소요. 관측 시각을 못 읽으면 도착 시각을 만들지 않는다. */
    public Optional<Instant> arrivalAt(BusArrivalCandidate candidate) {
        if (candidate.sectionTime() == null) {
            return Optional.empty();
        }
        return BusTimeParser.parse(candidate.dataTimestamp())
                .map(base -> base.plusSeconds(candidate.sectionTime()));
    }

    /**
     * 그 노선에서 이 정류장의 순번. 없거나 두 번 이상 나오면 null.
     *
     * @param afterSeq 이 순번보다 뒤만 본다(순환노선의 재방문을 승차 순번 기준으로 가른다). null 이면 전체
     */
    private Integer uniqueSeq(List<RouteStopProjection> stops, String stopId, Integer afterSeq) {
        List<RouteStopProjection> matches = stops.stream()
                .filter(stop -> stopId.equals(stop.getStopId()) && stop.getSeq() != null)
                .filter(stop -> afterSeq == null || stop.getSeq() > afterSeq)
                .toList();
        return matches.size() == 1 ? matches.get(0).getSeq() : null;
    }

    /**
     * 주행 중인 버스가 지금 어디인지. 정류장 목록을 한 번만 읽기 위해 함께 들고 다닌다.
     *
     * @param boardSeq   탄 정류장의 순번
     * @param currentSeq 방금 지난 정류장의 순번
     * @param alightSeq  계획된 하차 정류장의 순번
     */
    public record Position(List<RouteStopProjection> stops, int boardSeq, int currentSeq,
                           int alightSeq) {

        /** 그 순번의 정류장. 목록에 없으면 비어 있다. */
        public Optional<RouteStopProjection> stopAtSeq(int seq) {
            return stops.stream()
                    .filter(stop -> stop.getSeq() != null && stop.getSeq() == seq)
                    .findFirst();
        }
    }

    /** 위치 또는 못 푼 사유. 사유는 그대로 지표 태그가 된다. */
    public record Located(Position position, String failureReason) {

        static Located of(Position position) {
            return new Located(position, null);
        }

        static Located failed(String reason) {
            return new Located(null, reason);
        }

        public boolean failed() {
            return position == null;
        }
    }
}
