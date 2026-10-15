package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import watoo.grd.nextroute.application.journey.event.JourneyVersionChangedEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.config.JourneyNavigationProperties;
import watoo.grd.nextroute.application.journey.dto.JourneyProgressCommand;
import watoo.grd.nextroute.application.journey.dto.JourneyProgressOutcome;
import watoo.grd.nextroute.application.journey.dto.JourneyProgressOutcome.Result;
import watoo.grd.nextroute.application.journey.exception.ProgressTimestampConflictException;
import watoo.grd.nextroute.application.journey.port.in.ApplyJourneyProgressUseCase;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 앱이 확정한 진행 요약을 여정에 반영한다.
 *
 * <p>여정 행을 잠그지 않는다. progress 는 여정마다 15초(하차 임박 5초)에 한 번씩 들어오는데,
 * 명령 처리와 같은 잠금을 쓰면 둘이 계속 부딪친다. 대신 "지금 값보다 최신일 때만 쓴다"를 SQL
 * 조건에 넣어 읽고 판단해 쓰는 사이의 틈을 없앤다.
 *
 * <h2>지키는 것</h2>
 * <ul>
 *   <li>같은 progress 를 다시 받아도 {@code evaluationVersion} 은 한 번만 올라간다</li>
 *   <li>오래된 관측은 최신 상태를 덮어쓰지 않는다</li>
 *   <li>{@code commandVersion} 은 절대 건드리지 않는다</li>
 *   <li>정밀 좌표를 저장하지 않는다</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JourneyProgressService implements ApplyJourneyProgressUseCase {

    private final JourneySessionRepository sessionRepository;
    private final JourneyLegRepository legRepository;
    private final JourneyQueryService queryService;
    private final SubwayOfflineProtectionPolicy subwayProtectionPolicy;
    private final JourneyNavigationProperties properties;
    private final RerouteRefreshSignal rerouteRefreshSignal;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public JourneyProgressOutcome apply(Long userId, Long journeyId, JourneyProgressCommand command) {
        JourneySession journey = sessionRepository
                .findByIdAndUserIdAndDeletedAtIsNull(journeyId, userId)
                .orElseThrow(() -> new BusinessException(JourneyErrorCode.JOURNEY_NOT_FOUND));

        if (journey.getStatus() != JourneyStatus.ACTIVE) {
            throw new BusinessException(JourneyErrorCode.JOURNEY_NOT_ACTIVE);
        }
        if (command.routeRevision() != journey.getRouteRevision()) {
            throw new BusinessException(JourneyErrorCode.STALE_ROUTE_REVISION);
        }

        JourneyLeg leg = requireLeg(journey, command);
        // UPDATE 전에 읽어 둔다 — 조건부 UPDATE 뒤에는 영속성 컨텍스트가 비워져 직전 값을 잃는다.
        Integer stopsBefore = leg.getRemainingStops();
        Result result = merge(leg, command);

        if (result == Result.APPLIED) {
            // 개정본을 조건에 넣는다. 이 요청이 도는 사이 재탐색 수락(⑤)이 경로를 갈아끼웠을 수
            // 있는데, 여기서는 여정 행을 잠그지 않으므로 그 경합을 막을 다른 장치가 없다.
            if (sessionRepository.bumpEvaluationVersion(journeyId, command.routeRevision()) > 0) {
                // 평가 번호가 올랐다 — 커밋 뒤 버전 캐시를 덮는다(처방 4차).
                eventPublisher.publishEvent(JourneyVersionChangedEvent.of(journeyId));
            }
        }
        extendLease(journeyId);

        JourneySession refreshed = sessionRepository
                .findByIdAndUserIdAndDeletedAtIsNull(journeyId, userId)
                .orElseThrow(() -> new BusinessException(JourneyErrorCode.JOURNEY_NOT_FOUND));
        if (refreshed.getRouteRevision() != command.routeRevision()) {
            // 읽고 반영하는 사이에 경로가 바뀌었다. 방금 쓴 단계는 이전 개정본의 행이라 아무도
            // 읽지 않지만, 그 관측으로 새 개정본의 보호 시각까지 세우면 엉뚱한 경로의 값이 된다.
            throw new BusinessException(JourneyErrorCode.STALE_ROUTE_REVISION);
        }
        refreshSubwayProtection(refreshed, leg, command, result);
        signalStopPassed(journeyId, stopsBefore, command, result);
        return new JourneyProgressOutcome(result, queryService.snapshotOf(refreshed));
    }

    /**
     * 정류장·역을 하나 지났으면 재탐색 보드를 바로 다시 찾게 한다(#264 PR-2).
     *
     * <p>기준은 앱이 보낸 남은 정류장·역 수가 <b>줄었는가</b>다. 버스는 정류장, 지하철은 역 수다(#264
     * 결정 3-가). 처음 온 진행 보고(직전 값 없음)는 줄었는지 알 수 없지만, 플래그를 켜기 전에 시작한 여정의
     * 예정을 심는 기회라 함께 신호를 보낸다 — 이미 예정이 있으면 최소 간격 규칙이 막는다.
     */
    private void signalStopPassed(Long journeyId, Integer stopsBefore, JourneyProgressCommand command,
                                  Result result) {
        if (result != Result.APPLIED || command.remainingStops() == null) {
            return;
        }
        if (stopsBefore == null || command.remainingStops() < stopsBefore) {
            rerouteRefreshSignal.requestNow(journeyId);
        }
    }

    /**
     * 지하철 보호 시각을 새 관측으로 다시 계산한다(SC-12 3항).
     *
     * <p>단계 전이 때 세운 보호 시각은 검색이 준 계획 소요시간에서 나온 값이다. 실제 지하철이
     * 늦으면 그 시각이 지나 버리고, 앱이 정상인데도 여정이 만료 대상이 된다. 앱이 "이 단계가
     * 아직 얼마나 남았다"고 보고할 때마다 그 값으로 다시 세운다.
     *
     * <p>다시 세우는 조건이 셋이다.
     * <ul>
     *   <li>실제로 반영된 관측일 때만 — 재전송이나 뒤늦게 온 관측으로는 움직이지 않는다</li>
     *   <li>보고한 단계가 지금 지나는 단계일 때만 — progress 는 다음 단계를 미리 가리킬 수 있다</li>
     *   <li>남은 시간을 함께 보냈을 때만 — 없으면 정책이 계획 소요시간으로 내려간다</li>
     * </ul>
     *
     * <p>60분 상한은 정책이 지킨다. 앱이 계속 "더 걸린다"고 해도 단계 시작 + 60분을 넘기지 않는다.
     *
     * <p>여정 행을 다시 읽은 뒤에 건드리는 이유 — 위의 조건부 UPDATE 들이 영속성 컨텍스트를
     * 비우므로, 그 전에 바꾼 값은 사라진다.
     */
    private void refreshSubwayProtection(JourneySession journey, JourneyLeg leg,
                                         JourneyProgressCommand command, Result result) {
        if (result != Result.APPLIED || command.legIndex() != journey.getCurrentLegIndex()) {
            return;
        }
        Instant observedEndAt = command.remainingDurationSeconds() == null
                ? null
                : command.observedAt().plusSeconds(command.remainingDurationSeconds());
        subwayProtectionPolicy.apply(journey, leg, observedEndAt, Instant.now(clock));
    }

    /**
     * 조건부로 덮어쓰고, 덮어쓰지 못했으면 왜인지 가른다.
     *
     * <p>순서가 중요하다. 재전송 판정을 먼저 한다 — 앱이 응답을 못 받아 다시 보낸 것이라면 그것은
     * 오류가 아니고, 같은 시각·같은 내용이므로 충돌로 오해하기 쉽다.
     */
    private Result merge(JourneyLeg leg, JourneyProgressCommand command) {
        int updated = legRepository.applyProgressIfNewer(
                leg.getId(),
                command.progressId(),
                command.geometrySegmentIndex(),
                command.segmentProgress(),
                command.legProgress(),
                command.sectionId(),
                command.remainingDistanceMeters(),
                command.remainingDurationSeconds(),
                command.remainingStops(),
                command.observedAt(),
                JourneyProgressCommand.EVIDENCE_SOURCE,
                command.quality());
        if (updated > 0) {
            return Result.APPLIED;
        }

        JourneyLeg current = legRepository.findById(leg.getId())
                .orElseThrow(() -> new BusinessException(JourneyErrorCode.JOURNEY_LEG_NOT_FOUND));

        if (command.progressId().equals(current.getLastProgressId())) {
            return Result.DUPLICATE;
        }
        if (command.observedAt().equals(current.getProgressObservedAt())) {
            // 같은 시각을 두 번 관측할 수는 없다. 어느 쪽이 맞는지 서버가 고를 근거가 없다.
            throw new ProgressTimestampConflictException(command.observedAt());
        }
        return Result.STALE;
    }

    /**
     * progress 가 가리키는 단계.
     *
     * <p>검증은 <b>명백한 위반만</b> 본다. 구간 순번이 실제 좌표 개수 안에 드는지까지 확인하려면
     * 얼려 둔 경로를 매 요청마다 다시 쪼개야 하고, 15초마다 오는 요청에 그 비용을 붙이면
     * progress 응답 시간 목표를 스스로 깎는다. 구간 정합은 좌표를 들고 있는 앱의 책임이다.
     *
     * <p>대신 되돌릴 수 없는 위반은 막는다. 이미 지나온 단계로 진행을 되돌리는 요청이다.
     */
    private JourneyLeg requireLeg(JourneySession journey, JourneyProgressCommand command) {
        List<JourneyLeg> legs = legRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                        journey.getId(), journey.getRouteRevision());

        JourneyLeg leg = legs.stream()
                .filter(candidate -> candidate.getLegIndex() == command.legIndex())
                .findFirst()
                .orElseThrow(() -> new BusinessException(JourneyErrorCode.JOURNEY_LEG_NOT_FOUND));

        if (command.legIndex() < journey.getCurrentLegIndex()) {
            throw new BusinessException(JourneyErrorCode.STALE_PROGRESS_LEG);
        }
        return leg;
    }

    /**
     * lease 를 연장한다. 정상 progress 는 heartbeat 를 겸한다.
     *
     * <p>실제 쓰기는 여정별 최대 60초에 한 번으로 합친다. 15초마다 이 행을 쓰면 여정 하나가 계속
     * 갱신되는 hot row 가 된다. lease 는 3분이라 60초 간격으로 늘려도 만료되지 않는다.
     *
     * <p>만료 판정과 heartbeat 전용 API 는 Phase 4 의 몫이다. 여기서는 연장만 한다.
     */
    private void extendLease(Long journeyId) {
        Instant now = Instant.now(clock);
        sessionRepository.extendLeaseIfStale(
                journeyId,
                now,
                now.plus(properties.guidanceLease()),
                now.minus(properties.leaseWriteCoalesce()));
    }
}
