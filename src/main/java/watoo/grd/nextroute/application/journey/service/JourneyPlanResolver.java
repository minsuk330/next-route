package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.JourneyLegDraft;
import watoo.grd.nextroute.application.journey.dto.JourneyPlan;
import watoo.grd.nextroute.application.journey.dto.JourneyRouteOptionDraft;
import watoo.grd.nextroute.application.route.service.TransferStopResolver;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * ODSAY 원천 식별자가 든 여정 계획을 서버 내부 식별자가 든 실행 계획으로 바꾼다.
 *
 * <p>이 단계는 여정 저장 트랜잭션에 들어가기 전에 실행한다. 정적 데이터 조회가 사용자 행 잠금 시간을
 * 늘리지 않으며, 해석에 실패해도 수동 진행 가능한 여정 자체는 저장한다.
 */
@Component
@RequiredArgsConstructor
public class JourneyPlanResolver {

    private final TransferStopResolver busResolver;
    private final SubwayLineIdResolver subwayLineResolver;
    private final SubwayStationResolver subwayStationResolver;

    public JourneyPlan resolve(JourneyPlan plan) {
        Map<Integer, JourneyLegDraft> originalByIndex = new HashMap<>();
        plan.legs().forEach(leg -> originalByIndex.put(leg.legIndex(), leg));
        Map<Integer, JourneyLegDraft> resolvedByIndex = new HashMap<>();
        for (JourneyLegDraft leg : plan.legs()) {
            JourneyLegDraft resolved = switch (leg.type()) {
                case WAIT_BUS -> resolveBusWait(leg, originalByIndex.get(leg.pairedLegIndex()));
                case WAIT_SUBWAY -> resolveSubwayWait(leg);
                case RIDE_BUS, RIDE_SUBWAY -> resolveRide(leg, resolvedByIndex);
                case WALK, TRANSFER_WALK -> leg;
            };
            resolvedByIndex.put(resolved.legIndex(), resolved);
        }
        return new JourneyPlan(
                plan.legs().stream().map(leg -> resolvedByIndex.get(leg.legIndex())).toList(),
                plan.transfers());
    }

    private JourneyLegDraft resolveBusWait(JourneyLegDraft leg, JourneyLegDraft rideLeg) {
        String stopId = resolveBusStop(leg.sourceStartStopId());
        String alightingStopId = rideLeg == null ? null : resolveBusStop(rideLeg.sourceEndStopId());
        List<JourneyRouteOptionDraft> options = leg.routeOptions().stream()
                .map(option -> resolveBusOption(option, stopId, alightingStopId))
                .toList();
        return leg.withResolvedStops(stopId, stopId,
                busWaitEvidence(stopId, options), options);
    }

    private JourneyLegDraft resolveRide(JourneyLegDraft leg, Map<Integer, JourneyLegDraft> resolvedByIndex) {
        JourneyLegDraft waitLeg = leg.pairedLegIndex() == null
                ? null : resolvedByIndex.get(leg.pairedLegIndex());
        if (waitLeg == null) {
            return leg;
        }
        String startStopId = waitLeg.startStopId();
        String endStopId = switch (leg.type()) {
            case RIDE_BUS -> resolveBusStop(leg.sourceEndStopId());
            case RIDE_SUBWAY -> resolveSubwayRideEnd(leg, waitLeg);
            default -> null;
        };
        String evidence = leg.type() == JourneyLegType.RIDE_BUS
                ? busRideEvidence(startStopId, endStopId, waitLeg.routeOptions())
                : subwayEvidence(startStopId, endStopId, waitLeg.routeOptions());
        return leg.withResolvedStops(startStopId, endStopId, evidence, List.of());
    }

    private JourneyLegDraft resolveSubwayWait(JourneyLegDraft leg) {
        List<JourneyRouteOptionDraft> options = leg.routeOptions().stream()
                .map(this::resolveSubwayOption)
                .toList();
        String stopId = unambiguousStationId(options, leg.startName(), leg.startX(), leg.startY());
        return leg.withResolvedStops(stopId, stopId,
                subwayEvidence(stopId, stopId, options), options);
    }

    private JourneyRouteOptionDraft resolveBusOption(JourneyRouteOptionDraft option,
                                                      String boardingStopId, String alightingStopId) {
        Optional<TransferStopResolver.RouteResolution> route =
                busResolver.resolveRoute(option.sourceRouteId(), option.routeName());
        if (route.isEmpty()) {
            return option;
        }
        String routeId = route.get().routeId();
        Integer boardingSeq = uniqueSeq(routeId, boardingStopId);
        Integer alightingSeq = uniqueSeq(routeId, alightingStopId);
        return option.resolved(routeId, boardingSeq, alightingSeq);
    }

    private JourneyRouteOptionDraft resolveSubwayOption(JourneyRouteOptionDraft option) {
        return subwayLineResolver.resolve(option.routeName(), option.sourceRouteId())
                .map(lineId -> option.resolved(lineId, null, null))
                .orElse(option);
    }

    private String resolveSubwayRideEnd(JourneyLegDraft ride, JourneyLegDraft wait) {
        return unambiguousStationId(wait.routeOptions(), ride.endName(), ride.endX(), ride.endY());
    }

    private String unambiguousStationId(List<JourneyRouteOptionDraft> options, String name,
                                        Double x, Double y) {
        List<String> stationIds = options.stream()
                .map(JourneyRouteOptionDraft::routeId)
                .filter(routeId -> routeId != null && !routeId.isBlank())
                .map(lineId -> subwayStationResolver.resolve(lineId, name, x, y))
                .flatMap(Optional::stream)
                .distinct()
                .toList();
        return stationIds.size() == 1 ? stationIds.get(0) : null;
    }

    private String resolveBusStop(String sourceStopId) {
        return busResolver.resolveStop(sourceStopId)
                .map(TransferStopResolver.StopResolution::stopId)
                .orElse(null);
    }

    private Integer uniqueSeq(String routeId, String stopId) {
        if (stopId == null || stopId.isBlank()) {
            return null;
        }
        TransferStopResolver.SeqResolution seq = busResolver.resolveSeq(routeId, stopId);
        return seq.isSingle() ? seq.candidates().get(0) : null;
    }

    private String busWaitEvidence(String startStopId, List<JourneyRouteOptionDraft> options) {
        return startStopId != null
                && options.stream().anyMatch(JourneyRouteOptionDraft::hasResolvedBusBoarding)
                ? null : JourneyLeg.EVIDENCE_QUALITY_UNKNOWN;
    }

    private String busRideEvidence(String startStopId, String endStopId,
                                   List<JourneyRouteOptionDraft> options) {
        return startStopId != null && endStopId != null
                && options.stream().anyMatch(JourneyRouteOptionDraft::hasResolvedBusScope)
                ? null : JourneyLeg.EVIDENCE_QUALITY_UNKNOWN;
    }

    private String subwayEvidence(String startStopId, String endStopId,
                                  List<JourneyRouteOptionDraft> options) {
        boolean routeResolved = options.stream().anyMatch(option -> option.routeId() != null
                && !option.routeId().isBlank());
        return startStopId != null && endStopId != null && routeResolved
                ? null : JourneyLeg.EVIDENCE_QUALITY_UNKNOWN;
    }
}
