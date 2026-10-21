package watoo.grd.nextroute.application.journey.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidate;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateDisplay;
import watoo.grd.nextroute.application.journey.dto.RerouteContext;
import watoo.grd.nextroute.application.journey.dto.RerouteDecision;
import watoo.grd.nextroute.application.journey.dto.RerouteProposalView;
import watoo.grd.nextroute.application.journey.observability.JourneyRerouteMetrics;
import watoo.grd.nextroute.application.journey.port.out.RerouteCandidateProvider;
import watoo.grd.nextroute.application.journey.port.out.RerouteEligibilityPolicy;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposalKind;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposalStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 재탐색 제안의 조회·요청 진입점.
 *
 * <p><b>트랜잭션이 없다.</b> 후보 생성(④)이 실시간 버스 API·ML·경로 검색을 부르므로 그 호출을
 * 트랜잭션 밖에 둔다. 저장만 {@link RerouteProposalTxService} 가 트랜잭션 안에서 한다
 * (TECHNICAL_DECISIONS §6.3).
 *
 * <p>이 PR 에는 후보 공급자 구현이 하나도 없고 적격성 판정의 기본값이 항상 거절이다. 그래서
 * 요청은 {@code 409}, 조회는 "제안 없음"이다 — <b>그것이 의도한 기본 상태다.</b> 반대로 두면
 * 트리거(①)가 늦어지는 동안 아무 제한 없이 외부 검색이 돈다.
 */
@Slf4j
@Service
public class RerouteProposalService {

    private final JourneySessionRepository sessionRepository;
    private final RerouteProposalTxService txService;
    private final RerouteEligibilityPolicy eligibilityPolicy;
    private final List<RerouteCandidateProvider> providers;
    private final JourneyRerouteProperties properties;
    private final JourneyRerouteMetrics metrics;
    private final Clock clock;
    private final RerouteBoardRefreshService boardRefreshService;

    /**
     * 공급자를 {@code priority} 순으로 <b>주입 시점에 한 번</b> 정렬한다. 부를 때마다 정렬하면
     * 순서가 배선에 따라 흔들릴 수 있고, 순서가 뒤집히면 같은 정류장의 다른 노선으로 끝날 일에도
     * 매번 경로를 통째로 다시 검색해 요금이 폭발한다.
     */
    public RerouteProposalService(JourneySessionRepository sessionRepository,
                                  RerouteProposalTxService txService,
                                  RerouteEligibilityPolicy eligibilityPolicy,
                                  List<RerouteCandidateProvider> providers,
                                  JourneyRerouteProperties properties,
                                  JourneyRerouteMetrics metrics,
                                  Clock clock,
                                  RerouteBoardRefreshService boardRefreshService) {
        this.sessionRepository = sessionRepository;
        this.txService = txService;
        this.eligibilityPolicy = eligibilityPolicy;
        this.providers = providers.stream()
                .sorted(Comparator.comparingInt(RerouteCandidateProvider::priority))
                .toList();
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
        this.boardRefreshService = boardRefreshService;
    }

    /**
     * 살아 있는 제안. 소유권 확인 → 만료 전이 → 조회 순이다.
     *
     * <p>만료 전이를 조회에서도 도는 이유는 <b>즉시성</b> 때문이다. 배치 sweep 만 믿으면 사용자가
     * 화면을 여는 순간 이미 만료된 제안이 최대 한 주기 동안 살아 있는 척한다.
     *
     * @throws BusinessException 남의 여정일 때
     */
    public Optional<RerouteProposalView> findActive(Long userId, Long journeyId) {
        JourneySession journey = loadOwnedJourney(userId, journeyId);
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        Instant now = Instant.now(clock);
        if (properties.getBoard().isEnabled()) {
            return findActiveBoard(journeyId, journey, now);
        }
        txService.resolveStale(journeyId, journey.getRouteRevision(), now);
        return txService.findActive(journeyId).flatMap(view -> validate(view, now));
    }

    /**
     * 보드 모드의 조회(#264). <b>DB 에 쓰지 않는다.</b>
     *
     * <p>앱이 화면을 켜 둔 동안 15초마다 부른다. 여기서 만료 전이(resolveStale)나 전멸 접기를 하면 폴링이
     * 보드를 죽였다 살리는 순환이 되고, 세션 → 제안 잠금 순서와 반대인 쓰기(제안 → 세션)가 폴링 빈도로
     * 늘어 교착 위험이 커진다. 만료·교체는 갱신기와 만료 스케줄러가 맡고, 여기서는 <b>응답에서만</b>
     * 거른다 — 개정본이 바뀌었거나 유효시간이 지난 보드는 없는 것으로, 곧 닿는 자리(60초 미만)는 뺀다.
     */
    private Optional<RerouteProposalView> findActiveBoard(Long journeyId, JourneySession journey,
                                                           Instant now) {
        Instant earliest = now.plusSeconds(properties.getBoard().getLeadTimeSec());
        return txService.findActive(journeyId)
                .filter(view -> view.routeRevision() == journey.getRouteRevision())
                .filter(view -> view.expiresAt().isAfter(now))
                .map(view -> view.withCandidates(view.candidates().stream()
                        .filter(candidate -> reachable(candidate, earliest))
                        .toList()));
    }

    /**
     * 목록을 여는 순간 <b>지나간 하차점을 걷어 낸다</b>(PR-3 pull validate).
     *
     * <p>제안은 만들어진 순간의 사진이다. 사용자가 알림을 보고 들어오기까지 몇 분이 흐르고, 그
     * 사이 버스는 계속 달린다 — 그 정류장은 이미 지나갔을 수 있다. 지나간 자리를 그대로 보여
     * 주면 사용자는 내릴 수 없는 곳을 고르게 되고, 그것이 이 기능에서 가장 나쁜 사고다.
     *
     * <p><b>저장된 행은 고치지 않는다.</b> 걸러 내기는 읽는 순간의 판단이라, 몇 초 뒤 다시 열면
     * 또 한 자리가 사라질 수 있다. 저장을 따라 고치면 같은 판단을 두 곳이 갖게 된다.
     *
     * <p>전멸하면 제안 자체를 접는다({@code all-passed}) — 빈 목록을 살아 있는 제안으로 두면
     * 여정당 하나뿐인 자리를 막아 새 제안이 만들어지지 않는다. 다시 찾는 것은 다음 방아쇠나
     * 사용자의 "다시 찾아줘"가 한다.
     */
    private Optional<RerouteProposalView> validate(RerouteProposalView view, Instant now) {
        if (!properties.getAlert().isValidateOnRead()) {
            return Optional.of(view);
        }
        Instant earliest = now.plusSeconds(properties.getNextStop().getLeadTimeSec());
        List<RerouteCandidate> alive = view.candidates().stream()
                .filter(candidate -> reachable(candidate, earliest))
                .toList();
        if (alive.size() == view.candidates().size()) {
            return Optional.of(view);
        }
        metrics.recordAlert("validated-out", view.candidates().size() - alive.size());
        if (alive.isEmpty()) {
            txService.expireValidatedOut(view.proposalId(), now);
            return Optional.empty();
        }
        return Optional.of(view.withCandidates(alive));
    }

    /**
     * 이 후보의 하차점에 <b>아직 닿을 수 있는가</b>.
     *
     * <p>하차 정보가 없는 후보(PR-2 이전에 저장된 행)는 그대로 통과시킨다. 그 후보는 leg
     * 경계에서 갈아타는 옛 모양이라 "정류장을 지나쳤다"가 성립하지 않는다.
     */
    private boolean reachable(RerouteCandidate candidate, Instant earliest) {
        RerouteCandidateDisplay.AlightStop alight = candidate.display().alight();
        return alight == null || alight.alightAt() == null || !alight.alightAt().isBefore(earliest);
    }

    /**
     * 사용자가 "다시 찾아줘"를 누른 경로.
     *
     * <p>순서: 소유권·{@code ACTIVE} 확인 → 적격성(①) → {@code priority} 순 공급자 순회(첫
     * 비어 있지 않은 종류를 채택) → 상한만큼 절단 → 저장.
     *
     * <p>명시 요청도 적격성을 지난다. <b>버튼 연타가 외부 검색 연타가 되면 안 된다.</b>
     *
     * <p>공급자가 전부 빈손이면 예외가 아니라 빈 결과다 — "찾아봤지만 나은 것이 없다"는 정상
     * 결과이고, 그것을 실패로 만들면 앱이 재시도를 돌기 시작한다.
     *
     * @throws BusinessException 남의 여정·끝난 여정·적격하지 않을 때
     */
    public Optional<RerouteProposalView> request(Long userId, Long journeyId, int transferIndex) {
        JourneySession journey = loadOwnedJourney(userId, journeyId);
        if (journey.getStatus() != JourneyStatus.ACTIVE) {
            metrics.recordRejected("not-active");
            throw new BusinessException(JourneyErrorCode.JOURNEY_NOT_ACTIVE);
        }
        Instant now = Instant.now(clock);
        if (!properties.isEnabled()) {
            metrics.recordRejected("disabled");
            throw new BusinessException(JourneyErrorCode.REROUTE_NOT_ELIGIBLE);
        }
        Optional<String> denial = eligibilityPolicy.denialReason(journey, transferIndex, now);
        if (denial.isPresent()) {
            metrics.recordRejected(denial.get());
            throw new BusinessException(JourneyErrorCode.REROUTE_NOT_ELIGIBLE);
        }

        if (properties.getBoard().isEnabled()) {
            // 보드 모드(#264 PR-2): 새 제안을 심지 않고 보드를 지금 한 번 갱신해 그대로 보여 준다.
            // 따로 심으면 보드를 밀어낸다. 적격성(시간 창·cooldown, 테스트 계정 우회)은 위에서 같다.
            boardRefreshService.refresh(journeyId);
            return txService.findActive(journeyId);
        }

        RerouteContext context = new RerouteContext(journeyId, userId, journey.getRouteRevision(),
                journey.getCommandVersion(), transferIndex, now, journey.isExcludeExpress());

        Optional<Proposed> proposed = collect(context);
        if (proposed.isEmpty()) {
            metrics.recordEmpty();
            return Optional.empty();
        }
        // 후보를 만드는 동안 여정이 움직였으면 저장 쪽이 빈 결과를 낸다. 그때 다음 공급자로
        // 넘어가지 않는다 — 더 비싼 검색을 부를 이유가 없다. 상황이 바뀐 것이지 후보가
        // 없었던 것이 아니다.
        return store(context, proposed.get(), now.plus(properties.getProposalTtl()));
    }

    /**
     * 공급자 사다리를 돌려 <b>첫 비어 있지 않은 종류</b>를 고른다. 저장은 하지 않는다.
     *
     * <p>사용자 요청(pull)과 서버 알림(push, PR-3)이 <b>같은 후보를 같은 순서로</b> 만들어야 한다.
     * 두 경로가 각자 사다리를 돌리면 같은 상황에서 다른 목록이 나가고, 사용자는 알림을 열었을 때
     * 아까 본 것과 다른 화면을 본다.
     */
    public Optional<Proposed> collect(RerouteContext context) {
        for (RerouteCandidateProvider provider : providers) {
            List<RerouteCandidate> candidates = findCandidatesSafely(provider, context);
            if (!candidates.isEmpty()) {
                return Optional.of(new Proposed(provider.kind(), candidates));
            }
        }
        return Optional.empty();
    }

    /** 고른 후보를 저장한다. TTL 은 부르는 쪽이 정한다 — 알림 제안은 더 오래 살아야 한다. */
    public Optional<RerouteProposalView> store(RerouteContext context, Proposed proposed,
                                               Instant expiresAt) {
        return txService.store(context, proposed.kind(), proposed.candidates(),
                observedAt(proposed.candidates(), context.now()), expiresAt);
    }

    /** 사다리가 고른 종류와 그 후보들. 저장 전 단계다. */
    public record Proposed(RerouteProposalKind kind, List<RerouteCandidate> candidates) {
    }

    /**
     * 사용자가 제안을 닫았다 — 거절 또는 무시(PR-3 알림 계약).
     *
     * <p><b>스위치가 꺼져 있어도 받는다.</b> 기능을 끈 순간에도 사용자의 손에는 이미 받은 알림이
     * 남아 있고, 그 화면의 "괜찮아요"가 오류를 뱉으면 앱은 재시도를 돈다.
     *
     * <p><b>멱등이다.</b> 같은 요청이 두 번 와도 한 번만 반영된다 — 두 번째 호출은 이미 닫힌
     * 제안을 만나 아무것도 바꾸지 않고, 그래도 {@code 200} 이다. 재전송과 오류를 가르는 것은
     * 네트워크가 흔들릴 때 가장 먼저 필요해지는 성질이다.
     *
     * @return 이번 호출이 실제로 닫았으면 true. 이미 닫혀 있었으면 false
     * @throws BusinessException 남의 여정일 때
     */
    public RerouteDecision decide(Long userId, Long journeyId, Long proposalId,
                                  RerouteProposalStatus decision, boolean keepAlerts) {
        loadOwnedJourney(userId, journeyId);
        return txService.close(journeyId, proposalId, decision, keepAlerts, Instant.now(clock));
    }

    /**
     * 공급자 하나가 터져도 요청 전체를 죽이지 않는다.
     *
     * <p>포트 계약이 "예외를 던지지 않는다"이지만 구현은 외부 API 를 부른다 — 계약에 기대어
     * 감싸지 않으면 ODsay 가 500 을 주는 날 재탐색 요청이 전부 500 이 된다.
     */
    private List<RerouteCandidate> findCandidatesSafely(RerouteCandidateProvider provider,
                                                        RerouteContext context) {
        try {
            List<RerouteCandidate> candidates = provider.findCandidates(context);
            return candidates == null ? List.of() : candidates;
        } catch (Exception e) {
            log.warn("[JourneyReroute] 후보 생성 실패 kind={} journeyId={}: {}",
                    provider.kind(), context.journeyId(), e.getMessage());
            return List.of();
        }
    }

    /**
     * 후보들이 근거로 삼은 관측 시각 중 <b>가장 오래된 것</b>.
     *
     * <p>가장 새것을 쓰면 제안 전체가 실제보다 신선해 보인다. 사용자에게 보여 줄 신선도는 가장
     * 낡은 근거를 따라야 한다.
     */
    private Instant observedAt(List<RerouteCandidate> candidates, Instant fallback) {
        return candidates.stream()
                .map(candidate -> candidate.display().dataObservedAt())
                .filter(Objects::nonNull)
                .min(Instant::compareTo)
                .orElse(fallback);
    }

    private JourneySession loadOwnedJourney(Long userId, Long journeyId) {
        return sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(journeyId, userId)
                .orElseThrow(() -> new BusinessException(JourneyErrorCode.JOURNEY_NOT_FOUND));
    }
}
