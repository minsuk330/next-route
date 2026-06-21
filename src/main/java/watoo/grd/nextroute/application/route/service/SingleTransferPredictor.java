package watoo.grd.nextroute.application.route.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.bus.dto.BusPositionInfo;
import watoo.grd.nextroute.application.route.config.MlPredictorProperties;
import watoo.grd.nextroute.application.route.config.TransferArrivalProperties;
import watoo.grd.nextroute.application.route.config.TransferPredictProperties;
import watoo.grd.nextroute.application.route.dto.TransferArrival;
import watoo.grd.nextroute.application.route.dto.TransferPredictionResult;
import watoo.grd.nextroute.application.route.port.in.PredictTransferUseCase;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort.MlFeatureVector;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort.MlPrediction;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.Outcome;
import watoo.grd.nextroute.domain.bus.repository.BusRouteStopRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 단일 (stopId, routeId, seq) 환승 예측. REALTIME 우선 → 미해당 시 MODEL(ML) fallback.
 * {@link TransferArrivalEnricher}(검색 hot path)와 독립 — 포트/빌더/리졸버만 재사용(로직은 단일타깃용 복제).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SingleTransferPredictor implements PredictTransferUseCase {

    private static final long STALE_BEFORE_SEC = 120;
    private static final long STALE_AFTER_SEC = 60;

    private final TransferArrivalProperties transferProps;
    private final TransferPredictProperties predictProps;
    private final MlPredictorProperties mlProps;
    private final SearchTimeBusQueryPort busPort;
    private final MlArrivalPredictorPort mlPort;
    private final MlFeatureVectorBuilder featureBuilder;
    private final TransferStopResolver resolver;
    private final BusRouteStopRepository routeStopRepo;
    private final PredictionSupportService supportService;
    private final Clock clock;

    @Override
    public TransferPredictionResult predict(String stopId, String routeId, Integer seq, Instant userArrivalAt) {
        Instant calculatedAt = clock.instant();
        Instant deadline = calculatedAt.plusMillis(predictProps.getDeadlineMs());

        // 1. feature gate
        if (!transferProps.isEnabled()) {
            return no(stopId, routeId, seq, TransferArrival.Source.NONE,
                    TransferArrival.Status.DISABLED, calculatedAt, userArrivalAt);
        }

        // 2. seq 확정·검증
        Integer targetSeq;
        List<Integer> seqCandidates;
        if (seq != null) {
            if (!routeStopRepo.existsByRouteIdAndStopIdAndSeq(routeId, stopId, seq)) {
                return no(stopId, routeId, seq, TransferArrival.Source.NONE,
                        TransferArrival.Status.STOP_MAPPING_FAILED, calculatedAt, userArrivalAt);
            }
            targetSeq = seq;
            seqCandidates = List.of(seq);
        } else {
            seqCandidates = resolver.resolveSeq(routeId, stopId).candidates();
            if (seqCandidates.isEmpty()) {
                return no(stopId, routeId, null, TransferArrival.Source.NONE,
                        TransferArrival.Status.STOP_MAPPING_FAILED, calculatedAt, userArrivalAt);
            }
            targetSeq = seqCandidates.size() == 1 ? seqCandidates.get(0) : null; // 다건이면 realtime으로 유일화
        }

        // 3. REALTIME 조회
        // getArrInfoByRoute는 (stopId, routeId, ord) 단위 — 호출 전 seq가 유일 확정돼야 한다.
        // 사용자가 노선·정류장을 특정한 상황이라 seq는 단일이지만, 방어적으로 다건이면 매핑 실패 처리.
        if (targetSeq == null) {
            return no(stopId, routeId, null, TransferArrival.Source.NONE,
                    TransferArrival.Status.STOP_MAPPING_FAILED, calculatedAt, userArrivalAt);
        }
        if (remaining(deadline) <= 0) {
            return no(stopId, routeId, targetSeq, TransferArrival.Source.NONE,
                    TransferArrival.Status.LIMITED, calculatedAt, userArrivalAt);
        }
        BusQueryResult<BusArrivalInfo> arrRes = busPort.getArrInfoByStop(stopId, routeId, targetSeq);
        if (arrRes.outcome() == Outcome.BLOCKED) {
            return no(stopId, routeId, targetSeq, TransferArrival.Source.NONE,
                    TransferArrival.Status.BLOCKED, calculatedAt, userArrivalAt);
        }
        boolean arrOk = arrRes.isOk();
        List<BusArrivalInfo> arrivals = arrOk ? arrRes.data() : List.of();
        // 응답은 해당 노선 1건. 방어적으로 routeId 필터.
        Optional<BusArrivalInfo> matched = arrivals.stream()
                .filter(a -> routeId.equals(a.routeId())).findFirst();

        // REALTIME 판정 (arr OK일 때만)
        if (arrOk && matched.isPresent()) {
            Optional<RealtimePick> pick = pickRealtime(matched.get(), userArrivalAt);
            if (pick.isPresent()) {
                return TransferPredictionResult.available(stopId, routeId, targetSeq,
                        TransferArrival.Source.REALTIME, calculatedAt, userArrivalAt,
                        pick.get().arrivalAt(), pick.get().vehicleId(), null);
            }
        }

        // 4. MODEL 게이트
        PredictionSupportService.Support support = supportService.support(routeId);
        if (support == PredictionSupportService.Support.UNSUPPORTED) {
            return arrOk
                    ? no(stopId, routeId, targetSeq, TransferArrival.Source.NONE,
                        TransferArrival.Status.UNSUPPORTED_ROUTE, calculatedAt, userArrivalAt)
                    : no(stopId, routeId, targetSeq, TransferArrival.Source.NONE,
                        mapOutcome(arrRes.outcome()), calculatedAt, userArrivalAt);
        }
        if (!mlProps.isEnabled()) {
            return arrOk
                    ? no(stopId, routeId, targetSeq, TransferArrival.Source.NONE,
                        TransferArrival.Status.MODEL_UNAVAILABLE, calculatedAt, userArrivalAt)
                    : no(stopId, routeId, targetSeq, TransferArrival.Source.NONE,
                        mapOutcome(arrRes.outcome()), calculatedAt, userArrivalAt);
        }

        // 5. MODEL fallback (SUPPORTED or UNKNOWN, ml on)
        return modelFallback(stopId, routeId, targetSeq, userArrivalAt, calculatedAt, deadline);
    }

    private TransferPredictionResult modelFallback(
            String stopId, String routeId, int targetSeq,
            Instant userArrivalAt, Instant calculatedAt, Instant deadline) {

        if (remaining(deadline) <= 0) {
            return no(stopId, routeId, targetSeq, TransferArrival.Source.MODEL,
                    TransferArrival.Status.LIMITED, calculatedAt, userArrivalAt);
        }
        BusQueryResult<BusPositionInfo> posRes = busPort.getBusPosByRtid(routeId);
        if (!posRes.isOk()) {
            TransferArrival.Status st = posRes.outcome() == Outcome.BLOCKED
                    ? TransferArrival.Status.BLOCKED : mapOutcome(posRes.outcome());
            return no(stopId, routeId, targetSeq, TransferArrival.Source.MODEL, st, calculatedAt, userArrivalAt);
        }

        // feature 생성 (유효 차량만, vehicleId 중복 제거)
        List<MlFeatureVector> vectors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<BusPositionInfo> valid = new ArrayList<>();
        for (BusPositionInfo pos : posRes.data()) {
            if (!isValidVehicle(pos, targetSeq, calculatedAt)) continue;
            if (pos.vehicleId() == null || !seen.add(pos.vehicleId())) continue;
            valid.add(pos);
            featureBuilder.build(reqId(routeId, pos.vehicleId()), pos, targetSeq, routeId)
                    .ifPresent(vectors::add);
        }
        if (vectors.isEmpty()) {
            return no(stopId, routeId, targetSeq, TransferArrival.Source.MODEL,
                    TransferArrival.Status.NO_VEHICLE, calculatedAt, userArrivalAt);
        }
        if (remaining(deadline) <= 0) {
            return no(stopId, routeId, targetSeq, TransferArrival.Source.MODEL,
                    TransferArrival.Status.LIMITED, calculatedAt, userArrivalAt);
        }

        java.util.Map<String, MlPrediction> byId = new java.util.HashMap<>();
        try {
            mlPort.predict(vectors).forEach(p -> byId.put(p.requestId(), p));
        } catch (Exception e) {
            log.warn("[TransferPredict] ML predict failed: {}", e.getMessage());
            return no(stopId, routeId, targetSeq, TransferArrival.Source.MODEL,
                    TransferArrival.Status.MODEL_UNAVAILABLE, calculatedAt, userArrivalAt);
        }

        // earliest boardable 우선, 없으면 earliest 전체
        boolean anyUnsupported = false;
        boolean anyAvailable = false;
        List<ModelPick> picks = new ArrayList<>();
        for (BusPositionInfo pos : valid) {
            MlPrediction pred = byId.get(reqId(routeId, pos.vehicleId()));
            if (pred == null) continue;
            if (pred.status() == MlArrivalPredictorPort.MlPredictionStatus.UNSUPPORTED_ROUTE) {
                anyUnsupported = true;
                continue;
            }
            if (pred.status() != MlArrivalPredictorPort.MlPredictionStatus.AVAILABLE
                    || pred.secondsToArrival() == null) continue;
            Optional<Instant> snap = BusTimeParser.parse(pos.dataTm());
            if (snap.isEmpty()) continue;
            anyAvailable = true;
            Instant modelAt = snap.get().plusSeconds(pred.secondsToArrival().longValue());
            picks.add(new ModelPick(modelAt, pos.vehicleId(), pred.modelVersion()));
        }

        if (picks.isEmpty()) {
            TransferArrival.Status st = (anyUnsupported && !anyAvailable)
                    ? TransferArrival.Status.UNSUPPORTED_ROUTE : TransferArrival.Status.NO_VEHICLE;
            return no(stopId, routeId, targetSeq, TransferArrival.Source.MODEL, st, calculatedAt, userArrivalAt);
        }

        ModelPick best = chooseBoardable(picks, userArrivalAt);
        return TransferPredictionResult.available(stopId, routeId, targetSeq,
                TransferArrival.Source.MODEL, calculatedAt, userArrivalAt,
                best.arrivalAt(), best.vehicleId(), best.modelVersion());
    }

    /** earliest boardable(>= userAt) 우선, 없으면 earliest 전체. */
    private static ModelPick chooseBoardable(List<ModelPick> picks, Instant userAt) {
        ModelPick earliestBoardable = null;
        ModelPick earliest = null;
        for (ModelPick p : picks) {
            if (earliest == null || p.arrivalAt().isBefore(earliest.arrivalAt())) earliest = p;
            if (!p.arrivalAt().isBefore(userAt)
                    && (earliestBoardable == null || p.arrivalAt().isBefore(earliestBoardable.arrivalAt()))) {
                earliestBoardable = p;
            }
        }
        return earliestBoardable != null ? earliestBoardable : earliest;
    }

    /**
     * predictTime1/2 중 earliest boardable(userAt 이후 도착) 선택.
     * 탑승가능 버스가 없으면(도착 API가 준 다음 1~2대가 전부 user 도착 전) empty 반환 →
     * 지난 버스를 REALTIME으로 단정하지 않고 MODEL fallback으로 넘긴다(Enricher와 동작 일치).
     */
    private Optional<RealtimePick> pickRealtime(BusArrivalInfo ai, Instant userAt) {
        Optional<Instant> baseOpt = BusTimeParser.parse(ai.dataTimestamp());
        if (baseOpt.isEmpty()) return Optional.empty();
        Instant base = baseOpt.get();

        RealtimePick earliestBoardable = null;
        if (ai.predictTime1() != null) {
            Instant t = base.plusSeconds(ai.predictTime1());
            if (!t.isBefore(userAt)) earliestBoardable = new RealtimePick(t, ai.vehicleId1());
        }
        if (ai.predictTime2() != null) {
            Instant t = base.plusSeconds(ai.predictTime2());
            if (!t.isBefore(userAt)
                    && (earliestBoardable == null || t.isBefore(earliestBoardable.arrivalAt()))) {
                earliestBoardable = new RealtimePick(t, ai.vehicleId2());
            }
        }
        return Optional.ofNullable(earliestBoardable);
    }

    private boolean isValidVehicle(BusPositionInfo pos, int targetSeq, Instant calculatedAt) {
        if (!"1".equals(pos.isRunYn())) return false;
        if (pos.sectionOrder() == null || pos.dataTm() == null) return false;
        if (pos.sectionOrder() > targetSeq) return false;
        Optional<Instant> snap = BusTimeParser.parse(pos.dataTm());
        if (snap.isEmpty()) return false;
        Instant s = snap.get();
        return !s.isBefore(calculatedAt.minusSeconds(STALE_BEFORE_SEC))
                && !s.isAfter(calculatedAt.plusSeconds(STALE_AFTER_SEC));
    }

    private static TransferArrival.Status mapOutcome(Outcome outcome) {
        return switch (outcome) {
            case BLOCKED -> TransferArrival.Status.BLOCKED;
            case LIMITED -> TransferArrival.Status.LIMITED;
            default -> TransferArrival.Status.ERROR;
        };
    }

    private long remaining(Instant deadline) {
        return java.time.Duration.between(clock.instant(), deadline).toMillis();
    }

    private static String reqId(String routeId, String vehicleId) {
        return routeId + ":" + vehicleId;
    }

    private static TransferPredictionResult no(
            String stopId, String routeId, Integer seq, TransferArrival.Source source,
            TransferArrival.Status status, Instant calculatedAt, Instant userArrivalAt) {
        return TransferPredictionResult.noResult(stopId, routeId, seq, source, status, calculatedAt, userArrivalAt);
    }

    private record RealtimePick(Instant arrivalAt, String vehicleId) {}

    private record ModelPick(Instant arrivalAt, String vehicleId, String modelVersion) {}
}
