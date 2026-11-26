package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import watoo.grd.nextroute.application.journey.event.JourneyVersionChangedEvent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.dto.JourneyLegDraft;
import watoo.grd.nextroute.application.journey.dto.JourneyPlan;
import watoo.grd.nextroute.application.journey.dto.JourneyRouteOptionDraft;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.SubwayBindingAbandonReason;
import watoo.grd.nextroute.application.journey.dto.TransferConnectionDraft;
import watoo.grd.nextroute.application.journey.config.JourneyNavigationProperties;
import watoo.grd.nextroute.application.journey.exception.ActiveJourneyExistsException;
import watoo.grd.nextroute.domain.journey.entity.JourneyEndReason;
import watoo.grd.nextroute.domain.journey.entity.JourneyKind;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.JourneyRevision;
import watoo.grd.nextroute.domain.journey.entity.JourneyRevisionStatus;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.domain.journey.entity.TransferConnection;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyNotificationRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyRevisionRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.journey.repository.TransferConnectionRepository;
import watoo.grd.nextroute.domain.user.entity.User;
import watoo.grd.nextroute.domain.user.repository.UserRepository;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.CommonErrorCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import watoo.grd.nextroute.domain.journey.entity.RouteOptionSelectedBy;
import lombok.extern.slf4j.Slf4j;

/**
 * 여정을 실제로 DB 에 앉히는 트랜잭션.
 *
 * <p>{@link JourneyStartService} 와 별도 빈으로 둔다. 같은 클래스 안에서 부르면 스프링의
 * 트랜잭션 프록시를 지나가지 않아 {@code @Transactional} 이 조용히 무효가 된다.
 *
 * <p>이 트랜잭션은 짧아야 한다. Redis 조회와 경로 쪼개기는 바깥에서 끝내고 들어온다 — 사용자
 * 행을 잠근 채 외부 저장소를 기다리면 같은 사람의 다음 요청이 그만큼 밀린다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JourneyStartTxService {

    private final JourneyNavigationProperties navigationProperties;
    private final UserRepository userRepository;
    private final JourneySessionRepository sessionRepository;
    private final JourneyRevisionRepository revisionRepository;
    private final JourneyLegRepository legRepository;
    private final JourneyLegRouteOptionRepository routeOptionRepository;
    private final TransferConnectionRepository transferConnectionRepository;
    private final JourneyNotificationRepository notificationRepository;
    private final SubwayBindingLifecycleService subwayBindingLifecycle;
    private final JourneyQueryService queryService;
    private final RerouteRefreshSignal rerouteRefreshSignal;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 저장 순서는 고정이다.
     *
     * <ol>
     *   <li>사용자 행 잠금 — 같은 사람의 동시 요청을 한 줄로 세운다</li>
     *   <li>기존 여정 확인 — 있으면 갈아탈지 판단</li>
     *   <li>여정·경로 개정본·단계 저장</li>
     *   <li>노선 후보 저장 → 첫 routeOption을 짝이 되는 두 단계의 현재 노선으로 선택</li>
     *   <li>환승 연결 저장</li>
     * </ol>
     *
     * <p>단계가 후보를 가리키고 후보도 단계를 가리키는 순환이라 한 번의 저장으로 끝낼 수 없다.
     * 단계를 먼저 저장해 번호를 받고, 그 번호로 후보를 저장해 다시 번호를 받아야 한다.
     */
    @Transactional
    public JourneySnapshot start(Long userId, JourneyPlan plan, JsonNode routeSnapshot,
                                 JsonNode destinationSummary, boolean replace, Instant now) {
        return start(userId, plan, routeSnapshot, destinationSummary, replace, now, JourneyKind.NAVIGATION);
    }

    /**
     * @param kind 단독 추적({@link JourneyKind#TRACK})이면 재탐색 보드 첫 예약을 하지 않는다. 보드 SQL 도
     *             {@code kind} 로 거르지만, 애초에 예약을 심지 않아 두 겹으로 막는다
     */
    @Transactional
    public JourneySnapshot start(Long userId, JourneyPlan plan, JsonNode routeSnapshot,
                                 JsonNode destinationSummary, boolean replace, Instant now,
                                 JourneyKind kind) {
        return start(userId, plan, routeSnapshot, destinationSummary, replace, now, kind, false);
    }

    /**
     * @param excludeExpress 시작 검색의 급행 끄기 요청값(subway-express S5). 세션에 남아 재탐색이 같은 급행 정책으로 찾는다.
     *                       단독 추적은 검색을 거치지 않아 근거가 없으므로 false 다
     */
    @Transactional
    public JourneySnapshot start(Long userId, JourneyPlan plan, JsonNode routeSnapshot,
                                 JsonNode destinationSummary, boolean replace, Instant now,
                                 JourneyKind kind, boolean excludeExpress) {
        return start(userId, plan, routeSnapshot, destinationSummary, replace, now, kind, excludeExpress, null);
    }

    /**
     * @param searchSessionId 시작 검색의 스냅샷 id(V80). 사용 퍼널에서 검색(route_search_event)과 여정을 잇는다.
     *                        단독 추적은 검색이 없어 null
     */
    @Transactional
    public JourneySnapshot start(Long userId, JourneyPlan plan, JsonNode routeSnapshot,
                                 JsonNode destinationSummary, boolean replace, Instant now,
                                 JourneyKind kind, boolean excludeExpress, String searchSessionId) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException(CommonErrorCode.NOT_FOUND));

        sessionRepository.findByUserIdAndStatusAndDeletedAtIsNull(user.getId(), JourneyStatus.ACTIVE)
                .ifPresent(existing -> replaceOrReject(existing, replace, now));

        JourneySession session = JourneySession.builder()
                .userId(user.getId())
                .kind(kind)
                .excludeExpress(excludeExpress)
                .searchSessionId(searchSessionId)
                .startedAt(now)
                .guidanceLeaseUntil(now.plus(navigationProperties.guidanceLease()))
                .routeSnapshot(routeSnapshot)
                .destinationSummary(destinationSummary)
                .build();
        // 시작하는 순간부터 평가 대상이다. 다음 주기를 기다리면 첫 화면이 예측 없이 그려진다.
        session.scheduleEvaluationAt(now);
        sessionRepository.saveAndFlush(session);

        revisionRepository.save(JourneyRevision.builder()
                .journeyId(session.getId())
                .routeRevision(session.getRouteRevision())
                .status(JourneyRevisionStatus.ACTIVE)
                .routeSnapshot(routeSnapshot)
                .activatedAt(now)
                .build());

        Map<Integer, Integer> defaults = defaultOptionIndexes(plan, now);
        List<JourneyLeg> legs = saveLegs(session, plan, rideSecondsOverrides(plan, defaults));
        // 새 번호다. 만들기 전에 누가 이 번호를 물어 캐시에 "없음"이 남았을 수 있다 — 커밋 뒤 덮는다(처방 4차).
        eventPublisher.publishEvent(JourneyVersionChangedEvent.of(session.getId()));
        saveRouteOptions(session, plan, legs, defaults);
        saveTransfers(session, plan);
        // 재탐색 보드 첫 갱신은 시작 + 60초다(#264 PR-2). 시작 직후 3분의 push 차단은 push 판단이 맡는다.
        if (session.getKind() == JourneyKind.NAVIGATION) {
            rerouteRefreshSignal.scheduleInitial(session.getId());
        }

        return queryService.snapshotOf(session);
    }

    /**
     * 기존 여정을 갈아탄다. 명시적으로 요청했을 때만이다.
     *
     * <p>{@code flush()} 로 취소를 먼저 DB 에 보낸다. 새 여정을 넣을 때까지 미루면 "사람당 진행
     * 중인 여정 하나" 인덱스가 <b>자기 자신</b>과 충돌한다.
     */
    private void replaceOrReject(JourneySession existing, boolean replace, Instant now) {
        if (!replace) {
            throw new ActiveJourneyExistsException(queryService.snapshotOf(existing));
        }
        existing.end(JourneyStatus.CANCELED, JourneyEndReason.SUPERSEDED, now);
        sessionRepository.saveAndFlush(existing);
        // 갈아탄 옛 여정의 알림이 새 여정 중에 나가면 사용자는 엉뚱한 환승 안내를 받는다.
        notificationRepository.cancelPendingByJourneyId(existing.getId());
        // 옛 여정의 미완료 지하철 고정도 여기서 접는다. 여정을 끝내는 길이 여기에도 있다 -
        // 빠뜨리면 이 경로로 끝난 여정의 고정만 영원히 남는다.
        subwayBindingLifecycle.abandonOpen(existing.getId(),
                SubwayBindingAbandonReason.SUPERSEDED_BY_NEW_JOURNEY);
    }

    /**
     * 노선 후보가 있는 단계(기다리는 단계)마다 기본 선택 후보 순번(#614). 검색 스냅샷 실시간상 걸어서 닿은 뒤 가장 먼저 오는
     * lane, 없으면 첫 후보({@link JourneyRouteOptionDraft#defaultIndex}).
     */
    static Map<Integer, Integer> defaultOptionIndexes(JourneyPlan plan, Instant startedAt) {
        Map<Integer, Integer> defaults = new HashMap<>();
        for (JourneyLegDraft draft : plan.legs()) {
            if (!draft.routeOptions().isEmpty()) {
                defaults.put(draft.legIndex(), JourneyRouteOptionDraft.defaultIndex(draft.routeOptions(), startedAt));
            }
        }
        return defaults;
    }

    /**
     * 기본 선택이 첫 후보가 아니면 짝 탑승 단계의 계획 소요를 그 lane 의 탑승시간으로(#614). 구간 {@code sectionTime} 은 대표
     * 노선(lane 0) 값이라 그대로 두면 "140 대기 + 402 탑승" 으로 도착 예정을 잰다. lane 탑승시간을 모르면 구간 값 그대로.
     */
    static Map<Integer, Integer> rideSecondsOverrides(JourneyPlan plan, Map<Integer, Integer> defaults) {
        Map<Integer, Integer> overrides = new HashMap<>();
        for (JourneyLegDraft draft : plan.legs()) {
            Integer index = defaults.get(draft.legIndex());
            if (index == null || index == 0 || draft.pairedLegIndex() == null) {
                continue;
            }
            draft.routeOptions().stream()
                    .filter(option -> option.optionIndex() == index && option.rideSeconds() != null)
                    .findFirst()
                    .ifPresent(option -> overrides.put(draft.pairedLegIndex(), option.rideSeconds()));
        }
        return overrides;
    }

    private List<JourneyLeg> saveLegs(JourneySession session, JourneyPlan plan, Map<Integer, Integer> plannedOverrides) {
        List<JourneyLeg> legs = new ArrayList<>(plan.legs().size());
        for (JourneyLegDraft draft : plan.legs()) {
            legs.add(JourneyLeg.builder()
                    .journeyId(session.getId())
                    .routeRevision(session.getRouteRevision())
                    .legIndex(draft.legIndex())
                    .type(draft.type())
                    .state(draft.state())
                    .startStopId(draft.startStopId())
                    .endStopId(draft.endStopId())
                    .sourceStartStopId(draft.sourceStartStopId())
                    .sourceEndStopId(draft.sourceEndStopId())
                    .startName(draft.startName())
                    .endName(draft.endName())
                    .plannedDurationSec(plannedOverrides.getOrDefault(draft.legIndex(), draft.plannedDurationSec()))
                    .evidenceQuality(draft.evidenceQuality())
                    .guidance(draft.guidance())
                    .activatedAt(draft.state() == watoo.grd.nextroute.domain.journey.entity.JourneyLegState.ACTIVE
                            ? session.getStartedAt() : null)
                    .build());
        }
        return legRepository.saveAllAndFlush(legs);
    }

    /**
     * 노선 후보를 저장하고 기본 선택 후보를 짝이 되는 두 단계의 현재 노선으로 둔다(주인 DEFAULT).
     *
     * <p>기본 선택은 묶인 버스 구간이면 검색 실시간상 먼저 오는 노선, 아니면 첫 후보다(#614). 사용자에게 차량 선택을
     * 요구하지 않는 제품 계약이고, 탄 노선이 다르면 앱이 #598 선택을 보낸다. 실제 차량·운행 회차는 확정하지 않는다.
     */
    private void saveRouteOptions(JourneySession session, JourneyPlan plan, List<JourneyLeg> legs,
                                  Map<Integer, Integer> defaults) {
        for (JourneyLegDraft draft : plan.legs()) {
            if (draft.routeOptions().isEmpty()) {
                continue;
            }
            JourneyLeg waitLeg = legs.get(draft.legIndex());
            List<JourneyLegRouteOption> saved = routeOptionRepository.saveAllAndFlush(
                    draft.routeOptions().stream()
                            .map(option -> toEntity(waitLeg, option))
                            .toList());

            if (saved.isEmpty()) {
                continue;
            }
            int index = defaults.getOrDefault(draft.legIndex(), 0);
            JourneyLegRouteOption chosen = saved.stream()
                    .filter(option -> option.getOptionIndex() == index)
                    .findFirst()
                    .orElse(saved.get(0));
            if (chosen != saved.get(0)) {
                log.info("[JourneyStart] journeyId={} legIndex={} 기본 선택 {} -> {}({}) — 검색 실시간상 먼저 옴",
                        session.getId(), draft.legIndex(), saved.get(0).getRouteName(),
                        chosen.getRouteName(), chosen.getOptionIndex());
            }
            waitLeg.selectRouteOption(chosen.getId(), chosen.getRouteId(), RouteOptionSelectedBy.DEFAULT);
            if (draft.pairedLegIndex() != null) {
                legs.get(draft.pairedLegIndex())
                        .selectRouteOption(chosen.getId(), chosen.getRouteId(), RouteOptionSelectedBy.DEFAULT);
            }
        }
        legRepository.flush();
    }

    private JourneyLegRouteOption toEntity(JourneyLeg leg, JourneyRouteOptionDraft draft) {
        return JourneyLegRouteOption.builder()
                .journeyLegId(leg.getId())
                .optionIndex(draft.optionIndex())
                .laneIndex(draft.laneIndex())
                .routeId(draft.routeId())
                .sourceRouteId(draft.sourceRouteId())
                .routeName(draft.routeName())
                .directionKey(draft.directionKey())
                .wayCode(draft.wayCode())
                .boardingSeq(draft.boardingSeq())
                .alightingSeq(draft.alightingSeq())
                .routeDisplayColor(draft.routeDisplayColor())
                .rideSeconds(draft.rideSeconds())
                .build();
    }

    private void saveTransfers(JourneySession session, JourneyPlan plan) {
        if (plan.transfers().isEmpty()) {
            return;
        }
        List<TransferConnection> transfers = new ArrayList<>(plan.transfers().size());
        for (TransferConnectionDraft draft : plan.transfers()) {
            transfers.add(TransferConnection.builder()
                    .journeyId(session.getId())
                    .routeRevision(session.getRouteRevision())
                    .transferIndex(draft.transferIndex())
                    .fromLegIndex(draft.fromLegIndex())
                    .walkLegIndex(draft.walkLegIndex())
                    .toLegIndex(draft.toLegIndex())
                    // 위험도는 실시간 도착 정보를 보는 Phase 3 이 채운다. 지금은 판단 근거가 없다.
                    .status(TransferStatus.UNKNOWN)
                    .build());
        }
        transferConnectionRepository.saveAll(transfers);
    }
}
