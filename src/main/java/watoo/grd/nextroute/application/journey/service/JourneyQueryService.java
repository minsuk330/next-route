package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.dto.JourneyLegView;
import watoo.grd.nextroute.application.journey.dto.JourneyRouteOptionView;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.port.in.JourneyQueryUseCase;
import watoo.grd.nextroute.application.journey.port.in.JourneyQueryUseCase.JourneyVersions;
import watoo.grd.nextroute.application.journey.port.out.JourneyVersionPort;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegGuidance;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 여정 조회. 앱이 재실행됐을 때 화면을 되살리는 경로다.
 *
 * <p>단계마다 노선 후보를 따로 조회하지 않는다. 단계를 한 번, 그 단계들의 후보를 한 번 읽고
 * 메모리에서 붙인다 — 환승이 많은 여정일수록 단계 수만큼의 쿼리가 그대로 지연이 된다.
 */
@Service
@lombok.extern.slf4j.Slf4j
@RequiredArgsConstructor
public class JourneyQueryService implements JourneyQueryUseCase {

    /** 빠른 하차 JSON 은 저장 형식이 고정된 작은 배열이라 공용 매퍼 설정과 무관하다(#266). */
    private static final com.fasterxml.jackson.databind.ObjectMapper FAST_ALIGHT_READER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final JourneySessionRepository sessionRepository;
    private final JourneyLegRepository legRepository;
    private final JourneyLegRouteOptionRepository routeOptionRepository;
    private final JourneySubwayGuidanceComposer subwayGuidanceComposer;
    private final JourneyVersionPort versionPort;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public Optional<JourneySnapshot> getActive(Long userId) {
        return sessionRepository
                .findByUserIdAndStatusAndDeletedAtIsNull(userId, JourneyStatus.ACTIVE)
                .map(this::snapshotOf);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<JourneySnapshot> get(Long userId, Long journeyId) {
        return sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(journeyId, userId)
                .map(this::snapshotOf);
    }

    /**
     * 트랜잭션을 열지 않는다(처방 4차) — 열면 캐시가 답하는 경우에도 시작 시점에 커넥션을 빌린다. 캐시가 비었을 때의
     * DB 읽기는 저장소가 스스로 짧게 연다.
     *
     * <p>소유자는 여기서 거른다. 버전 캐시가 여정마다 한 칸이라 원천이 소유자를 거르지 않고 함께 돌려준다.
     */
    @Override
    public Optional<JourneyVersions> versions(Long userId, Long journeyId) {
        return versionPort.find(journeyId)
                .filter(entry -> userId != null && entry.userId() == userId)
                .map(entry -> new JourneyVersions(entry.commandVersion(), entry.evaluationVersion(),
                        entry.subwayLeg(), entry.currentLegType(), entry.changedAtEpochMs()));
    }

    /**
     * 여정 하나를 최소 상태로 만든다.
     *
     * <p>여정을 막 만든 트랜잭션 안에서도 쓴다. 그래서 이 메서드는 스스로 트랜잭션을 열지 않고
     * 부르는 쪽의 트랜잭션에 얹힌다.
     */
    public JourneySnapshot snapshotOf(JourneySession session) {
        List<JourneyLeg> legs = legRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                        session.getId(), session.getRouteRevision());

        Map<Long, List<JourneyLegRouteOption>> optionsByLeg = legs.isEmpty()
                ? Map.of()
                : routeOptionRepository
                        .findByJourneyLegIdInAndDeletedAtIsNull(legs.stream().map(JourneyLeg::getId).toList())
                        .stream()
                        .collect(Collectors.groupingBy(JourneyLegRouteOption::getJourneyLegId));

        return JourneySnapshot.of(
                        session.getId(),
                        session.getStatus(),
                        session.getKind(),
                        session.getCommandVersion(),
                        session.getEvaluationVersion(),
                        session.getRouteRevision(),
                        session.getCurrentLegIndex(),
                        session.getGuidanceLeaseUntil(),
                        session.getSubwayOfflineProtectionUntil(),
                        clock.instant(),
                        legs.stream().map(leg -> toView(leg, optionsByLeg)).toList())
                // 지금 화면이 지하철일 때만 한 단계를 더 읽는다. 단계마다 물으면 정확히 N+1 이다.
                .withSubwayGuidance(subwayGuidanceComposer.compose(
                        session.getRouteRevision(), session.getCurrentLegIndex(),
                        legs, optionsByLeg));
    }

    /** 저장된 단계 → 응답 모양. 안내 값(#266)·열차 종류(subway-express S2)는 {@link JourneyLeg#getGuidance} 에서. */
    static JourneyLegView toView(JourneyLeg leg,
                                 Map<Long, List<JourneyLegRouteOption>> optionsByLeg) {
        List<JourneyRouteOptionView> options = optionsByLeg
                .getOrDefault(leg.getId(), List.of())
                .stream()
                .sorted(java.util.Comparator.comparingInt(JourneyLegRouteOption::getOptionIndex))
                .map(option -> new JourneyRouteOptionView(
                        option.getId(),
                        option.getOptionIndex(),
                        option.getLaneIndex(),
                        option.getRouteId(),
                        option.getSourceRouteId(),
                        option.getRouteName(),
                        option.getDirectionKey(),
                        option.getWayCode(),
                        option.getBoardingSeq(),
                        option.getAlightingSeq(),
                        option.getRouteDisplayColor()))
                .toList();

        JourneyLegGuidance guidance = leg.getGuidance();
        return new JourneyLegView(
                leg.getId(),
                leg.getLegIndex(),
                leg.getType(),
                leg.getState(),
                leg.getStartStopId(),
                leg.getEndStopId(),
                leg.getStartName(),
                leg.getEndName(),
                leg.getRouteId(),
                leg.getPlannedDurationSec(),
                leg.getEvidenceQuality(),
                leg.getSelectedRouteOptionId(),
                options,
                guidance.startStopNo(),
                guidance.endStopNo(),
                guidance.directionName(),
                guidance.startExitNo(),
                guidance.endExitNo(),
                fastAlight(guidance.fastAlightJson()),
                guidance.alightDoorSide(),
                guidance.trainKind(),
                leg.getSourceStartStopId(),
                leg.getSourceEndStopId(),
                leg.getRouteOptionSelectedByOrDefault() == null ? null : leg.getRouteOptionSelectedByOrDefault().name());
    }

    /** 저장된 JSON 을 응답 모양으로. 깨진 값은 없는 것으로 본다 — 안내 줄 하나 때문에 여정 조회가 실패하면 안 된다. */
    static List<JourneyLegView.FastAlight> fastAlight(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return List.of(FAST_ALIGHT_READER.readValue(json, JourneyLegView.FastAlight[].class));
        } catch (Exception e) {
            log.warn("[Journey] 빠른 하차 값 해석 실패 — 비움: {}", e.getMessage());
            return null;
        }
    }
}
