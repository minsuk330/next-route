package watoo.grd.nextroute.application.journey.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidate;
import watoo.grd.nextroute.application.journey.dto.RerouteContext;
import watoo.grd.nextroute.application.journey.dto.RerouteProposalView;
import watoo.grd.nextroute.application.journey.observability.JourneyRerouteMetrics;
import watoo.grd.nextroute.application.journey.port.out.RerouteCandidateProvider;
import watoo.grd.nextroute.application.journey.port.out.RerouteEligibilityPolicy;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
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
 * <p><b>트랜잭션이 없다.</b> 후보 생성(③④)이 실시간 버스 API·ML·ODsay 를 부르므로 그 호출을
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
                                  Clock clock) {
        this.sessionRepository = sessionRepository;
        this.txService = txService;
        this.eligibilityPolicy = eligibilityPolicy;
        this.providers = providers.stream()
                .sorted(Comparator.comparingInt(RerouteCandidateProvider::priority))
                .toList();
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
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
        txService.resolveStale(journeyId, journey.getRouteRevision(), Instant.now(clock));
        return txService.findActive(journeyId);
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
        Optional<String> denial = eligibilityPolicy.denialReason(journeyId, transferIndex, now);
        if (denial.isPresent()) {
            metrics.recordRejected(denial.get());
            throw new BusinessException(JourneyErrorCode.REROUTE_NOT_ELIGIBLE);
        }

        RerouteContext context = new RerouteContext(journeyId, userId, journey.getRouteRevision(),
                journey.getCommandVersion(), transferIndex, now);

        for (RerouteCandidateProvider provider : providers) {
            List<RerouteCandidate> candidates = findCandidatesSafely(provider, context);
            if (candidates.isEmpty()) {
                continue;
            }
            return Optional.of(txService.store(context, provider.kind(), candidates,
                    observedAt(candidates, now), now.plus(properties.getProposalTtl())));
        }

        metrics.recordEmpty();
        return Optional.empty();
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
