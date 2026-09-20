package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.bus.dto.BusArrivalCandidate;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.dto.RerouteOrigin;
import watoo.grd.nextroute.application.journey.dto.RerouteOriginKind;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort;
import watoo.grd.nextroute.domain.bus.repository.RouteStopProjection;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 수락을 반영하기 <b>직전</b>, 주행 중인 버스를 어디서 자를지 다시 확인한다
 * (journey-reroute-nextstop PR-4).
 *
 * <h2>왜 저장된 값을 그대로 쓰지 않나</h2>
 * 제안이 만들어지고 사용자가 누르기까지 시간이 흐르는데, 그 사이 <b>버스는 계속 달린다</b>.
 * 제안 시점의 하차 정류장은 이미 지나갔을 수 있고, 남은 시간도 그때 값이다. 여기서 다시 묻지
 * 않으면 "이미 지나간 정류장에서 내리라"는 안내가 나가고, 그 안내를 믿은 사용자는 내리지 못한다.
 *
 * <h2>못 물어보면 거절한다(fail closed)</h2>
 * 실시간이 없으면 자르지 않는다 — 시간표로 폴백하지 않는다({@link UpcomingStopWindowResolver}
 * 와 같은 규칙, 사용자 결정). 하차는 되돌릴 수 없는 행동이라 "모르겠다"는 "괜찮다"가 될 수 없다.
 * 거절된 수락은 트랜잭션째 되돌아가므로 제안은 살아 있고 사용자는 다시 누를 수 있다.
 *
 * <h2>자를 자리는 순번 산수로 찾는다</h2>
 * 스냅샷 정류장 목록의 id 는 검색이 준 것(OTP {@code gtfsId}/ODsay id)이고 원점의 {@code stopId}
 * 는 서울 id 라 <b>직접 맞댈 수 없다</b>(PR-C 의 "번역이 반만 된다"). 대신 서울 노선 순번의 차이
 * ({@code origin.seq - boardSeq})가 곧 승차 정류장부터 센 위치다. 두 목록이 어긋나면 엉뚱한
 * 정류장을 자르므로 <b>이름 대조</b>를 함께 넘겨 합성 단계에서 확인하게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RideTruncationResolver {

    private final BusRideLocator locator;
    private final SearchTimeBusQueryPort busQueryPort;
    private final JourneyRerouteProperties properties;

    /**
     * @param ride   지금 타고 있다고 후보가 주장하는 단계(원점의 {@code legIndex} 가 가리키는 단계)
     * @param origin 중간 하차 지점(서울 좌표계)
     * @return 자를 수 없으면 사유가 담긴 실패. 사유는 그대로 지표 태그가 된다
     */
    public Result resolve(JourneyLeg ride, RerouteOrigin origin, Instant now) {
        JourneyRerouteProperties.NextStop config = properties.getNextStop();
        String mismatch = rideMismatch(ride, origin);
        if (mismatch != null) {
            return Result.failed(mismatch);
        }

        BusRideLocator.Located located = locator.locate(ride, config.getProgressMaxAgeSec(), now);
        if (located.failed()) {
            return Result.failed(located.failureReason());
        }
        BusRideLocator.Position position = located.position();
        int alightSeq = origin.seq();
        if (alightSeq <= position.currentSeq() || alightSeq >= position.alightSeq()) {
            // 누르는 사이에 지나갔거나, 애초에 이 주행 구간 밖이다.
            return Result.failed("alight-passed");
        }

        ArrivalLookup lookup = arrivalAt(origin, position, config, now);
        if (lookup.failed()) {
            return Result.failed(lookup.failureReason());
        }
        Instant alightAt = lookup.at();
        if (Duration.between(now, alightAt).getSeconds() < config.getAcceptMinLeadSec()) {
            // 문 앞으로 갈 시간이 없다. 지금 바꾸면 사용자는 이 정류장을 그냥 지나친다.
            return Result.failed("below-lead-time");
        }

        Instant boardedAt = ride.getActivatedAt();
        if (boardedAt == null) {
            // 언제 탔는지 모르면 승차→X 소요를 만들 수 없다(지어내면 화면의 "몇 분 남음"이 틀어진다).
            return Result.failed("no-boarding-time");
        }
        int rideSeconds = (int) Math.max(0, Duration.between(boardedAt, alightAt).getSeconds());
        String stopName = position.stopAtSeq(alightSeq)
                .map(RouteStopProjection::getStopName)
                .orElse(null);

        return Result.of(new Resolved(
                alightSeq - position.boardSeq(),
                rideSeconds,
                stopName,
                alightAt,
                alightSeq - position.currentSeq(),
                legFraction(position, alightSeq)));
    }

    /** 이 단계를 자를 수 있는가. 자를 수 없으면 지표 태그가 될 사유. */
    private String rideMismatch(JourneyLeg ride, RerouteOrigin origin) {
        if (ride == null || origin.kind() != RerouteOriginKind.RIDE_INTERIOR) {
            return "not-riding";
        }
        // 버스만이다(PR-C). 하차 정류장 id 가 들어갈 칸이 버스 칸이라 지하철에 쓰면 주소가 섞인다.
        if (ride.getType() != JourneyLegType.RIDE_BUS || ride.getState() != JourneyLegState.ACTIVE) {
            return "not-riding";
        }
        if (ride.getRouteId() == null || ride.getStartStopId() == null
                || ride.getEndStopId() == null) {
            return "not-riding";
        }
        if (!ride.getRouteId().equals(origin.routeId()) || origin.seq() == null) {
            // 원점이 다른 노선을 가리킨다. 이 단계의 정류장 순번으로 해석하면 엉뚱한 곳을 자른다.
            return "not-riding";
        }
        return null;
    }

    /**
     * 내 차가 그 정류장에 닿는 시각.
     *
     * <p>실패 사유를 값으로 함께 돌려준다 — {@code Optional} 하나로는 "막혀서 못 물었다"와
     * "물었지만 내 차가 없다"를 가를 수 없고, 둘은 지표에서 다른 뜻이다.
     */
    private ArrivalLookup arrivalAt(RerouteOrigin origin, BusRideLocator.Position position,
                                    JourneyRerouteProperties.NextStop config, Instant now) {
        SearchTimeBusQueryPort.BusQueryResult<BusArrivalInfo> result = busQueryPort
                .getArrInfoByStop(origin.stopId(), origin.routeId(), origin.seq(),
                        config.getArrivalBudgetMs());
        if (!result.isOk()) {
            return new ArrivalLookup(null, "arrival-blocked");
        }
        Optional<BusArrivalCandidate> mine =
                locator.pickMyBus(result.data(), null, position.currentSeq(), now);
        return mine.flatMap(locator::arrivalAt)
                .map(at -> new ArrivalLookup(at, null))
                .orElseGet(() -> new ArrivalLookup(null, "no-realtime"));
    }

    private record ArrivalLookup(Instant at, String failureReason) {

        boolean failed() {
            return at == null;
        }
    }

    /**
     * 잘린 단계 안에서 지금까지 온 비율.
     *
     * <p>앱이 보낸 값은 <b>옛 하차점 기준</b>이라 그대로 두면 "이미 대부분 왔다"가 된다. 여기서는
     * 정류장 수로 다시 잰다 — 거리 기준이 아니라 근사지만, 이 값을 읽는 쪽은 다음 진행 보고가
     * 오면 바로 덮어쓴다.
     */
    private BigDecimal legFraction(BusRideLocator.Position position, int alightSeq) {
        int total = alightSeq - position.boardSeq();
        if (total <= 0) {
            return null;
        }
        return BigDecimal.valueOf(position.currentSeq() - position.boardSeq())
                .divide(BigDecimal.valueOf(total), 6, RoundingMode.HALF_UP);
    }

    /**
     * 자를 자리와 그때까지의 값들.
     *
     * @param alightStationIndex 스냅샷 구간 정류장 목록 안 위치(승차=0 기준)
     * @param rideSeconds        승차 → 그 정류장까지의 소요(초). <b>초 단위 그대로</b> 쓴다 —
     *                           스냅샷의 구간 소요는 분 단위라 되돌리면 최대 30초가 틀어진다
     * @param stopName           그 정류장의 서울 이름. 스냅샷 정류장 목록과 대조하는 데 쓴다
     * @param alightAt           그 정류장에 닿는 시각(실시간)
     * @param remainingStops     그 정류장까지 남은 정류장 수
     * @param legFraction        잘린 단계 안에서 지금까지 온 비율. 못 재면 null
     */
    public record Resolved(int alightStationIndex, int rideSeconds, String stopName,
                           Instant alightAt, int remainingStops, BigDecimal legFraction) {

        public JourneyRevisionComposer.RideTruncation toTruncation() {
            return new JourneyRevisionComposer.RideTruncation(
                    alightStationIndex, rideSeconds, stopName);
        }
    }

    /** 결과 또는 사유. 사유는 그대로 지표 태그가 된다. */
    public record Result(Resolved resolved, String failureReason) {

        static Result of(Resolved resolved) {
            return new Result(resolved, null);
        }

        static Result failed(String reason) {
            return new Result(null, reason);
        }

        public boolean failed() {
            return resolved == null;
        }
    }
}
