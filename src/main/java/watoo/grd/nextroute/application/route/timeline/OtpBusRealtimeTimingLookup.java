package watoo.grd.nextroute.application.route.timeline;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.route.config.OtpRoutingProperties;
import watoo.grd.nextroute.application.route.config.TransferArrivalProperties;
import watoo.grd.nextroute.application.route.mapping.OtpSeoulIdentityResolver;
import watoo.grd.nextroute.application.route.mapping.SeoulBusIdentity;
import watoo.grd.nextroute.application.route.mapping.SeoulTransitIdentity;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
import watoo.grd.nextroute.application.route.routing.ActualSource;
import watoo.grd.nextroute.application.route.routing.RoutingLeg;
import watoo.grd.nextroute.application.route.routing.StopOccurrence;
import watoo.grd.nextroute.application.route.service.BusTimeParser;
import watoo.grd.nextroute.application.route.service.RealtimeArrivalCalculator;
import watoo.grd.nextroute.application.route.service.TransferStopResolver;
import watoo.grd.nextroute.application.route.service.TransferStopResolver.SeqResolution;

import java.time.Instant;
import java.util.Optional;

/**
 * 버스 leg 의 실시간 승차 stop 도착을 조회해 {@link RealtimeLegTiming} 으로 돌린다(방법 B 배선 PR2).
 * composer 는 coverage 로 게이트하고, 여기서는 실제 실시간을 붙인다. 기존 실시간 인프라 재사용
 * ({@link SearchTimeBusQueryPort}·{@link RealtimeArrivalCalculator}·{@link TransferStopResolver#resolveSeq}).
 *
 * <p><b>근사 정책(PLAN P2-E step4 신뢰도 명시)</b>:
 * <ul>
 *   <li>승차 stop 실시간 도착 = 사용자 출발시각으로 쓴다. 하차 stop 예측은 이 포트가 안 주므로
 *       realtimeArrivalAt=null → composer 가 정적 ride 로 채운다(도착지연은 출발지연만 반영).</li>
 *   <li>trip-exact 매칭 없이 {@code (stopId, routeId, seq)} 의 계획시각 이후 최초 도착을 쓴다(조기버스는 무치환→정적).</li>
 *   <li>버스는 신뢰할 취소 신호가 없어 cancelled=false 고정. 조회 실패/차단/제한/버스없음은 empty→정적.</li>
 * </ul>
 * 지하철 leg 은 empty(후속 PR2b). 기능 게이트는 {@code route.transfer-arrival.enabled} 공유.
 */
@Component
@RequiredArgsConstructor
public class OtpBusRealtimeTimingLookup implements RealtimeLegTimingLookup {

    private static final long FALLBACK_BUDGET_MS = 1500;

    private final OtpSeoulIdentityResolver identityResolver;
    private final SearchTimeBusQueryPort busPort;
    private final TransferStopResolver stopResolver;
    private final TransferArrivalProperties props;
    private final OtpRoutingProperties otpProps;

    @Override
    public Optional<RealtimeLegTiming> timingFor(RoutingLeg leg, StopOccurrence boardingStop, Instant plannedBoardAt) {
        // 두 게이트 AND: 실시간 인프라 활성(ODSAY 공유) + OTP 실시간 전용 킬스위치(라우팅 활성과 분리).
        if (!props.isEnabled() || !otpProps.isRealtimeEnabled()) {
            return Optional.empty();
        }
        Optional<SeoulTransitIdentity> resolved = identityResolver.resolve(leg, boardingStop);
        if (resolved.isEmpty() || !(resolved.get() instanceof SeoulBusIdentity bus)) {
            return Optional.empty(); // 미매핑 또는 지하철(후속) → 정적
        }
        SeqResolution seq = stopResolver.resolveSeq(bus.routeId(), bus.stopId());
        if (!seq.isSingle()) {
            return Optional.empty(); // 경유순번 단일 확정 실패 → getArrInfoByStop 입력 불가
        }
        long budget = props.getDeadlineMs() > 0 ? props.getDeadlineMs() : FALLBACK_BUDGET_MS;
        BusQueryResult<BusArrivalInfo> res =
                busPort.getArrInfoByStop(bus.stopId(), bus.routeId(), seq.candidates().get(0), budget);
        if (!res.isOk() || res.data().isEmpty()) {
            return Optional.empty();
        }
        BusArrivalInfo ai = res.data().get(0);
        Optional<Instant> departure = RealtimeArrivalCalculator.earliest(ai, plannedBoardAt);
        if (departure.isEmpty()) {
            return Optional.empty();
        }
        Instant observedAt = BusTimeParser.parse(ai.dataTimestamp()).orElse(null);
        return Optional.of(new RealtimeLegTiming(
                departure.get(), null, false, ActualSource.REALTIME, observedAt));
    }
}
