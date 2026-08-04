package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.journey.config.JourneyEvaluatorProperties;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.JourneyEtaView;
import watoo.grd.nextroute.application.journey.dto.JourneyEtaView.ActiveTransferEta;
import watoo.grd.nextroute.application.journey.dto.JourneyEtaView.AlightingEta;
import watoo.grd.nextroute.application.journey.dto.JourneyEtaView.CurrentLegEta;
import watoo.grd.nextroute.application.journey.dto.JourneyEtaView.NextAction;
import watoo.grd.nextroute.application.journey.dto.JourneyLegView;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.LegGeometry;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.application.journey.port.in.GetJourneyNavigationGeometryUseCase;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 스냅샷에 현재 단계·환승·목적지 예측을 얹는다.
 *
 * <p>여기가 Phase 3 의 조각들이 처음으로 하나의 응답이 되는 자리다. 이동 단계 ETA, 대기 단계
 * ETA, 환승 위험도를 모아 앱이 화면을 그릴 수 있는 모양으로 만든다.
 *
 * <p><b>트랜잭션을 열지 않는다.</b> 안에서 실시간 도착 조회가 일어나므로 트랜잭션을 걸면 외부
 * API 가 느린 동안 DB 커넥션을 붙잡는다. 저장이 필요한 부분은 각자 짧은 트랜잭션을 연다.
 *
 * <p>모르는 값은 채우지 않는다. 앱은 null 을 보고 "아직 모름"을 표시하면 되고, 그것이 그럴듯하게
 * 틀린 시각을 보여 주는 것보다 낫다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JourneySnapshotEtaComposer {

    /** 환승이 위험할 때의 폴링 간격. 상황이 초 단위로 바뀌는 구간이다. */
    private static final int POLL_TRANSFER_AT_RISK = 5;

    private final JourneyLegRepository legRepository;
    private final GetJourneyNavigationGeometryUseCase geometryUseCase;
    private final CurrentLegArrivalService arrivalService;
    private final TransferRiskEvaluator transferRiskEvaluator;
    private final JourneyEvaluatorProperties evaluatorProperties;

    /**
     * @param userId 좌표 조회가 소유자 확인을 하므로 필요하다
     */
    public JourneySnapshot compose(Long userId, JourneySnapshot snapshot) {
        if (snapshot.status() != JourneyStatus.ACTIVE) {
            // 끝난 여정은 예측할 것이 없다. 실시간 조회도 하지 않는다.
            return snapshot;
        }

        Map<Integer, LegGeometry> geometry = loadGeometry(userId, snapshot);
        Map<Integer, JourneyLeg> legsByIndex = legRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                        snapshot.journeyId(), snapshot.routeRevision())
                .stream()
                .collect(Collectors.toMap(JourneyLeg::getLegIndex, Function.identity()));

        JourneyLeg currentLeg = legsByIndex.get(snapshot.currentLegIndex());
        CurrentLegArrival currentArrival = currentLeg == null
                ? CurrentLegArrival.unknown()
                : arrivalService.predict(currentLeg, geometry.get(snapshot.currentLegIndex()));

        List<TransferRiskAssessment> assessments = assess(snapshot, geometry);
        TransferRiskAssessment activeTransfer = firstUpcoming(assessments, snapshot);

        JourneyEtaView eta = new JourneyEtaView(
                currentLegEta(currentArrival),
                alighting(snapshot, currentArrival),
                activeTransferEta(activeTransfer),
                journeyEta(snapshot, legsByIndex, currentArrival, activeTransfer),
                nextAction(snapshot, currentArrival, activeTransfer));

        return snapshot.withEta(eta, pollSeconds(snapshot, activeTransfer));
    }

    /**
     * 환승 판정. <b>저장까지 할지는 평가기가 도는지에 달렸다.</b>
     *
     * <p>평가기가 켜져 있으면 저장은 그쪽의 일이다. 여기서도 쓰면 서로 다른 시점에 계산한 두
     * 결과가 커밋 순서로 승부가 나고, 낡은 계산이 최신을 덮을 수 있다(환승 행에는 낙관적 락도
     * 잠금도 없다). 평가기가 꺼져 있으면 쓰는 주체가 아무도 없으므로 지금까지처럼 여기서 저장한다.
     *
     * <p>다음 PR 에서 이 분기 자체가 사라진다 — 조회는 저장된 판정을 읽기만 하게 된다.
     */
    private List<TransferRiskAssessment> assess(JourneySnapshot snapshot,
                                                Map<Integer, LegGeometry> geometry) {
        if (evaluatorProperties.isEnabled()) {
            return transferRiskEvaluator.assess(
                    snapshot.journeyId(), snapshot.routeRevision(), geometry).assessments();
        }
        return transferRiskEvaluator.assessAndStore(
                snapshot.journeyId(), snapshot.routeRevision(), geometry);
    }

    // ── 조각들 ──────────────────────────────────────────────────────────────

    private CurrentLegEta currentLegEta(CurrentLegArrival arrival) {
        if (arrival.isUnknown()) {
            return new CurrentLegEta(null, null, null, CurrentLegArrival.Quality.UNKNOWN, null);
        }
        return new CurrentLegEta(arrival.arrivalAt(), arrival.remainingSeconds(),
                arrival.source(), arrival.quality(), arrival.observedAt());
    }

    /**
     * 하차 예측. 버스를 타고 있을 때만 의미가 있다.
     *
     * <p>지하철은 하차 안내를 하지 않기로 했다(SC-6). 걷거나 기다리는 중에도 하차는 없다.
     */
    private AlightingEta alighting(JourneySnapshot snapshot, CurrentLegArrival arrival) {
        JourneyLegView current = snapshot.currentLeg();
        if (current == null || current.type() != JourneyLegType.RIDE_BUS || arrival.isUnknown()) {
            return null;
        }
        return new AlightingEta(arrival.remainingStops(), arrival.arrivalAt(),
                arrival.source(), arrival.quality());
    }

    private ActiveTransferEta activeTransferEta(TransferRiskAssessment assessment) {
        if (assessment == null) {
            return null;
        }
        return new ActiveTransferEta(assessment.transferIndex(), assessment.status(),
                assessment.userReadyAt(), assessment.nextBusArrivalAt(),
                assessment.transferSlackSeconds(), assessment.confidence(),
                // 성공 확률은 캘리브레이션이 준비된 뒤에 채운다. 지금 숫자를 만들면 근거가 없다.
                null);
    }

    /**
     * 다음에 맞이할 환승. 이미 지나온 환승은 보지 않는다.
     *
     * <p>환승의 기준점은 "그 환승으로 갈아탈 차량을 기다리는 단계"다. 그 단계가 현재보다 앞에
     * 있거나 지금이면 아직 지나지 않은 환승이다.
     */
    private TransferRiskAssessment firstUpcoming(List<TransferRiskAssessment> assessments,
                                                 JourneySnapshot snapshot) {
        return assessments.stream()
                .filter(assessment -> assessment.isUpcoming(snapshot.currentLegIndex()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 목적지 도착 예측.
     *
     * <p>현재 단계 종료 시각에서 시작해 남은 단계의 계획 소요시간을 더한다. 대기 단계는 계획
     * 소요시간이 없으므로, 그 값을 아는 환승이 있으면 거기서 다시 기준을 잡고 없으면 전체를
     * 모른다고 한다. 대기 시간을 0으로 두면 목적지 도착이 늘 낙관적으로 나온다.
     */
    private java.time.Instant journeyEta(JourneySnapshot snapshot,
                                         Map<Integer, JourneyLeg> legsByIndex,
                                         CurrentLegArrival currentArrival,
                                         TransferRiskAssessment activeTransfer) {
        if (currentArrival.isUnknown() || currentArrival.arrivalAt() == null) {
            return null;
        }
        java.time.Instant cursor = currentArrival.arrivalAt();
        for (int index = snapshot.currentLegIndex() + 1; index < snapshot.legs().size(); index++) {
            JourneyLeg leg = legsByIndex.get(index);
            if (leg == null) {
                return null;
            }
            if (leg.getType().isWaiting()) {
                if (activeTransfer == null || activeTransfer.nextBusArrivalAt() == null) {
                    return null;
                }
                cursor = activeTransfer.nextBusArrivalAt();
                continue;
            }
            Integer planned = leg.getPlannedDurationSec();
            if (planned == null) {
                return null;
            }
            cursor = cursor.plusSeconds(planned);
        }
        return cursor;
    }

    /** 사용자가 지금 할 일. 환승이 위험하면 그것이 우선이다. */
    private NextAction nextAction(JourneySnapshot snapshot, CurrentLegArrival arrival,
                                  TransferRiskAssessment activeTransfer) {
        JourneyLegView current = snapshot.currentLeg();
        if (current == null) {
            return new NextAction(NextAction.Type.NONE, snapshot.currentLegIndex(), null, null);
        }
        boolean transferAtRisk = activeTransfer != null
                && (activeTransfer.status() == TransferStatus.AT_RISK
                || activeTransfer.status() == TransferStatus.MISSED);
        if (transferAtRisk && current.type().isRiding()) {
            return new NextAction(NextAction.Type.PREPARE_TRANSFER, current.legIndex(),
                    current.type(), arrival.arrivalAt());
        }
        NextAction.Type type = switch (current.type()) {
            case WAIT_BUS, WAIT_SUBWAY -> NextAction.Type.WAIT_FOR_VEHICLE;
            case RIDE_BUS, RIDE_SUBWAY -> NextAction.Type.RIDE;
            case TRANSFER_WALK -> NextAction.Type.WALK_TO_STOP;
            case WALK -> isLast(snapshot, current)
                    ? NextAction.Type.WALK_TO_DESTINATION
                    : NextAction.Type.WALK_TO_STOP;
        };
        return new NextAction(type, current.legIndex(), current.type(), arrival.arrivalAt());
    }

    private boolean isLast(JourneySnapshot snapshot, JourneyLegView leg) {
        return leg.legIndex() == snapshot.legs().size() - 1;
    }

    /** 환승이 위험한 구간에서는 더 자주 묻게 한다. */
    private int pollSeconds(JourneySnapshot snapshot, TransferRiskAssessment activeTransfer) {
        if (activeTransfer != null
                && (activeTransfer.status() == TransferStatus.AT_RISK
                || activeTransfer.status() == TransferStatus.MISSED)) {
            return POLL_TRANSFER_AT_RISK;
        }
        return snapshot.recommendedPollSeconds();
    }

    /**
     * 좌표. 실패해도 예측을 포기하지 않는다.
     *
     * <p>좌표가 없으면 이동 단계 ETA 가 계획 소요시간으로 떨어질 뿐이고, 대기 단계 ETA 와 환승
     * 판정은 좌표와 무관하게 동작한다.
     */
    private Map<Integer, LegGeometry> loadGeometry(Long userId, JourneySnapshot snapshot) {
        try {
            JourneyNavigationGeometry geometry =
                    geometryUseCase.get(userId, snapshot.journeyId(), snapshot.routeRevision());
            return geometry.legs().stream()
                    .collect(Collectors.toMap(LegGeometry::legIndex, Function.identity()));
        } catch (Exception e) {
            log.warn("여정 좌표 조회 실패 — journeyId={}, 계획 소요시간으로 내려간다",
                    snapshot.journeyId(), e);
            return Map.of();
        }
    }
}
