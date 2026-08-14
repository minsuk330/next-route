package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneySubwayBindingProperties;
import watoo.grd.nextroute.application.journey.dto.SubwayBindingCandidate;
import watoo.grd.nextroute.application.journey.dto.SubwayBindingDecision;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainEvent;
import watoo.grd.nextroute.application.subway.dto.TrainIdentity;
import watoo.grd.nextroute.application.subway.service.DestinationNormalizer;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 쌓인 관측과 쌓인 열차 로그를 맞춰 어느 열차에 탔는지 고른다. <b>DB·Redis 를 모르는 계산기다.</b>
 *
 * <p>재료를 모으는 일과 고르는 일을 나눈 이유. 고르는 규칙은 관문 숫자를 실측으로 조이는 동안
 * 계속 바뀌는데, 그때마다 트랜잭션·잠금이 딸려 오면 시험이 Testcontainers 를 켜야 한다.
 * 여기가 순수하면 경계 예제를 값으로 고정할 수 있다.
 *
 * <h2>두 단계로 나뉜다</h2>
 * <ol>
 *   <li><b>t0 잠정 판정</b> — 이동 시작 시각과 출발 사건을 맞춘다. 관측 해상도가 60~90초라
 *       이것만으로는 앞차와 뒷차를 자주 못 가른다. 그래서 여기서는 확정하지 않는다</li>
 *   <li><b>후행 확정</b> — 앱이 보낸 정차 관측을, 후보들이 <b>같은 역</b>에 도착한 로그와
 *       맞춘다. 이쪽이 부가가 아니라 주된 확정 경로다</li>
 * </ol>
 *
 * <h2>고르지 않는 쪽으로 기운다</h2>
 *
 * <p>잘못 고정하면 사용자는 엉뚱한 역에서 내린다. 못 고르면 안내가 "확인 중"에 머무를 뿐이다.
 * 두 실패의 값이 다르므로 모든 관문이 "애매하면 고르지 않는다"로 되어 있다.
 */
@Component
@RequiredArgsConstructor
public class SubwayTrainMatcher {

    /** 후보가 하나뿐일 때 2위와의 거리. 무한대로 두면 관문 (2)·(3)이 자동 통과한다. */
    private static final long NO_SECOND = Long.MAX_VALUE;

    /** headway 중앙값을 내려면 간격이 최소 이만큼 필요하다. 하나로는 "간격"이 아니라 "한 번"이다. */
    private static final int MIN_HEADWAY_INTERVALS = 2;

    private final JourneySubwayBindingProperties properties;
    private final DestinationNormalizer destinationNormalizer;

    /**
     * 판정에 필요한 재료 전부.
     *
     * <p>출발 후보를 {@code TrainDeparture} 가 아니라 {@link SubwayBindingCandidate} 로 받는다.
     * 조회창이 영업일 경계(04:00)를 걸치면 <b>두 영업일을 합쳐</b> 부르는데, 그때 어느 줄이 어느
     * 운행일에서 왔는지는 조회한 쪽만 안다. 여기서 다시 유도하면 자정 근처에서 열차 identity 가
     * 하루씩 어긋난다.
     *
     * @param status              지금 상태. {@code PENDING} 이면 t0 판정, {@code PROVISIONAL} 이면 후행 확정
     * @param movementStartedAt   이동 시작 관측이 말한 시각. 없으면 판정하지 않는다
     * @param departures          탑승역의 출발 후보. 노선·방향 필터 전이어도 된다
     * @param rejected            사용자가 아니라고 한 열차. 없으면 방금 거부한 열차를 그대로 다시 고른다
     * @param clockSuspect        기기 시각이 의심스럽다. 버리지 않고 <b>더 보수적으로</b> 본다
     * @param currentTrain        잠정으로 보고 있던 열차. 확정 대상이 이것과 다르면 교정이다
     * @param plannedNextStation  계획 역 체인에서 뽑은 비교역 이름. 없으면 확정하지 않는다
     * @param knownCandidates     잠정 판정 때 함께 남긴 후보들. 후행 확정은 이 집합 안에서만 고른다
     * @param arrivalsByCandidate 후보별 도착 로그. 비교역 필터는 여기서 하지 않는다
     */
    public record MatchInput(
            SubwayBindingStatus status,
            Instant movementStartedAt,
            List<SubwayBindingCandidate> departures,
            Set<TrainIdentity> rejected,
            String lineId,
            String direction,
            String boardStationId,
            Instant now,
            boolean clockSuspect,

            // ── 후행 확정 전용. 정차 관측이 없으면 전부 비어 있다 ──
            Instant episodeFrom,
            Instant episodeTo,
            TrainIdentity currentTrain,
            String plannedNextStation,
            List<SubwayBindingCandidate> knownCandidates,
            Map<TrainIdentity, List<SubwayTrainEvent>> arrivalsByCandidate,
            Instant provisionalDecidedAt
    ) {
    }

    public SubwayBindingDecision match(MatchInput input) {
        if (input.lineId() == null || input.boardStationId() == null) {
            // 여정 시작 때 ID 해석에 실패했다. 후보가 2개 이상이면 해석기가 null 을 넣는다.
            return SubwayBindingDecision.none("leg-unresolved");
        }
        if (input.direction() == null) {
            // 방향을 지어내면 반대 방향 열차에 고정된다. 모름이 그럴듯한 오답보다 낫다.
            return SubwayBindingDecision.none("direction-unknown");
        }
        return input.status() == SubwayBindingStatus.PROVISIONAL
                ? confirmStage(input)
                : provisionalStage(input);
    }

    // ── t0 잠정 판정 ────────────────────────────────────────────────────────

    private SubwayBindingDecision provisionalStage(MatchInput input) {
        if (input.movementStartedAt() == null) {
            return SubwayBindingDecision.none("no-movement-start");
        }
        if (expired(input.movementStartedAt(), properties.getMatchWindow(), input.now())) {
            // 원천이 1~2분 뒤처져도 이만큼 지나면 더 볼 것이 없다.
            return SubwayBindingDecision.abandon("match-window-expired");
        }

        List<SubwayBindingCandidate> onRoute = onRoute(input);
        if (onRoute.isEmpty()) {
            return SubwayBindingDecision.none("no-departure");
        }
        Long headway = localHeadwaySeconds(onRoute);

        List<SubwayBindingCandidate> candidates = scored(onRoute, input).stream()
                .filter(candidate -> !input.rejected().contains(candidate.identity()))
                .sorted(Comparator.comparingLong(SubwayBindingCandidate::gapSeconds))
                .toList();
        if (candidates.isEmpty()) {
            return SubwayBindingDecision.none("no-departure");
        }

        SubwayBindingCandidate best = candidates.get(0);
        long e1 = best.gapSeconds();
        long e2 = candidates.size() > 1 ? candidates.get(1).gapSeconds() : NO_SECOND;
        long skewBudget = skewBudgetSeconds(input.clockSuspect());

        if (e1 > properties.getAbsoluteMaxSkew().toSeconds()) {
            // 유일해도 고정하지 않는다. 후보가 하나뿐인 것은 "맞다"가 아니라 "로그가 하나뿐"이다.
            return SubwayBindingDecision.none("gate1", candidates);
        }
        if (e2 != NO_SECOND && e1 + skewBudget >= e2 - skewBudget) {
            return SubwayBindingDecision.none("gate2", candidates);
        }
        if (headway != null && e2 != NO_SECOND
                && (e2 - e1) < properties.getHeadwayBeta() * headway) {
            return SubwayBindingDecision.none("gate3", candidates);
        }

        // 관문 (2)를 통과했으면 best 는 정의상 유일하다 — 동점 처리 분기를 만들지 않는다.
        return SubwayBindingDecision.provisional(best, candidates, "t0-match");
    }

    /** 노선·방향이 맞고 급행이 아닌 후보만. 급행은 서는 역이 달라 계획 체인과 대조할 수 없다. */
    private List<SubwayBindingCandidate> onRoute(MatchInput input) {
        return input.departures().stream()
                .filter(candidate -> input.lineId().equals(candidate.lineId()))
                .filter(candidate -> input.direction().equals(candidate.direction()))
                .filter(SubwayBindingCandidate::isNormalTrain)
                .toList();
    }

    /** 이동 시작 구간과 출발 구간의 거리를 재 후보에 얹는다. */
    private List<SubwayBindingCandidate> scored(List<SubwayBindingCandidate> candidates,
                                                MatchInput input) {
        Duration motion = properties.getMotionUncertainty();
        Instant motionFrom = input.movementStartedAt().minus(motion);
        Instant motionTo = input.movementStartedAt().plus(motion);
        return candidates.stream()
                .map(candidate -> candidate.withGapSeconds(gapSeconds(
                        motionFrom, motionTo, candidate.departureFrom(), candidate.departureTo())))
                .toList();
    }

    /**
     * 구간에 안 들어간 잔여 불확실성.
     *
     * <p>기기 시각이 의심스러우면 두 배로 본다. 관측을 버리지 않고 관문을 좁히는 쪽을 고른다 —
     * 버리면 시계가 틀어진 기기는 영영 안내를 못 받고 그 사실을 아무도 모른다.
     */
    private long skewBudgetSeconds(boolean clockSuspect) {
        long budget = properties.getClockSkewBudget().toSeconds();
        return clockSuspect ? budget * 2 : budget;
    }

    /**
     * 같은 노선·방향의 최근 출발 간격 중앙값. 표본이 모자라면 {@code null} 이다.
     *
     * <p>거부된 후보도 포함해 센다. 배차간격은 우리가 무엇을 고르는지와 무관한 운행의 성질이다.
     */
    private Long localHeadwaySeconds(List<SubwayBindingCandidate> onRoute) {
        List<Instant> midpoints = onRoute.stream()
                .map(candidate -> midpoint(candidate.departureFrom(), candidate.departureTo()))
                .sorted()
                .toList();
        List<Long> intervals = new ArrayList<>(Math.max(0, midpoints.size() - 1));
        for (int i = 1; i < midpoints.size(); i++) {
            intervals.add(Duration.between(midpoints.get(i - 1), midpoints.get(i)).toSeconds());
        }
        if (intervals.size() < MIN_HEADWAY_INTERVALS) {
            return null;
        }
        intervals.sort(Comparator.naturalOrder());
        int middle = intervals.size() / 2;
        return intervals.size() % 2 == 1
                ? intervals.get(middle)
                : (intervals.get(middle - 1) + intervals.get(middle)) / 2;
    }

    // ── 후행 확정 ───────────────────────────────────────────────────────────

    /**
     * 정차 관측을 후보들의 <b>같은 역</b> 도착 로그와 맞춘다.
     *
     * <p>비교역을 도착 로그에서 뽑지 않는 것이 핵심이다. "출발 후 첫 도착"은 다음 역을 보장하지
     * 않는다 — 관측 해상도가 60~90초라 한 역을 통째로 놓치는 일이 실제로 있다. 후보들이 다 같이
     * 놓치면 비교역이 서로 <b>일치</b>해 합의된 것처럼 보이고, 앱의 정차 관측과 한 구간(약 2분)
     * 어긋난 채 자신 있게 확정된다. 배차간격과 같은 자릿수라 그대로 오답이다.
     */
    private SubwayBindingDecision confirmStage(MatchInput input) {
        if (input.provisionalDecidedAt() != null
                && expired(input.provisionalDecidedAt(), properties.getConfirmWindow(), input.now())) {
            return SubwayBindingDecision.abandon("confirm-window-expired");
        }
        if (input.episodeFrom() == null || input.episodeTo() == null) {
            return SubwayBindingDecision.none("no-episode");
        }
        if (input.plannedNextStation() == null) {
            // 계획 역 체인이 없으면 어느 역과 비교해야 하는지 모른다.
            return SubwayBindingDecision.none("no-planned-chain");
        }
        if (input.knownCandidates().isEmpty()) {
            return SubwayBindingDecision.none("no-candidates");
        }

        String comparisonStation = destinationNormalizer.normalize(input.plannedNextStation());
        List<Scored> comparable = new ArrayList<>();
        for (SubwayBindingCandidate candidate : input.knownCandidates()) {
            SubwayTrainEvent arrival = arrivalAt(input, candidate, comparisonStation);
            if (arrival == null) {
                continue;
            }
            comparable.add(new Scored(candidate, gapSeconds(
                    input.episodeFrom(), input.episodeTo(), arrival.from(), arrival.to())));
        }

        // 로그 없음은 "틀림"이 아니라 "모름"이다. best 만 로그가 있으면 결측이 곧 확정 근거가 된다.
        if (comparable.size() < 2) {
            return SubwayBindingDecision.none("not-discriminative", input.knownCandidates());
        }

        comparable.sort(Comparator.comparingLong(Scored::gap));
        Scored best = comparable.get(0);
        if (best.gap() == comparable.get(1).gap()) {
            return SubwayBindingDecision.none("not-discriminative", input.knownCandidates());
        }
        if (best.gap() > properties.getEpisodeMatchTolerance().toSeconds()) {
            return SubwayBindingDecision.none("episode-tolerance", input.knownCandidates());
        }

        boolean sameAsProvisional = best.candidate().identity().equals(input.currentTrain());
        return sameAsProvisional
                ? SubwayBindingDecision.confirmed(best.candidate(), input.knownCandidates(),
                        "episode-match")
                : SubwayBindingDecision.corrected(best.candidate(), input.knownCandidates(),
                        "episode-correction");
    }

    /**
     * 그 후보가 비교역에 도착한 로그.
     *
     * <p>대조는 <b>이름</b>으로 한다. 계획 체인은 ODsay 역 이름이고 도착 사건은 서울 {@code statnId}
     * 라 식별자끼리는 아무것도 일치하지 않는다.
     */
    private SubwayTrainEvent arrivalAt(MatchInput input, SubwayBindingCandidate candidate,
                                       String comparisonStation) {
        List<SubwayTrainEvent> arrivals =
                input.arrivalsByCandidate().getOrDefault(candidate.identity(), List.of());
        return arrivals.stream()
                .filter(event -> event.type() == SubwayTrainEvent.Type.ARRIVED)
                .filter(event -> comparisonStation != null
                        && comparisonStation.equals(destinationNormalizer.normalize(event.stationName())))
                .findFirst()
                .orElse(null);
    }

    private record Scored(SubwayBindingCandidate candidate, long gap) {
    }

    // ── 구간 산술 ───────────────────────────────────────────────────────────

    /**
     * 두 구간의 거리(초). <b>겹치면 0 이다.</b>
     *
     * <p>사건 시각을 점으로 다루면 관측이 빌 때 생긴 모름이 사라져 가짜 정밀도가 된다.
     * 구간끼리 재면 흐릿한 근거가 자동으로 큰 오차를 만들어 관문에서 걸린다.
     */
    static long gapSeconds(Instant aFrom, Instant aTo, Instant bFrom, Instant bTo) {
        if (!aFrom.isAfter(bTo) && !bFrom.isAfter(aTo)) {
            return 0L;
        }
        return aTo.isBefore(bFrom)
                ? Duration.between(aTo, bFrom).toSeconds()
                : Duration.between(bTo, aFrom).toSeconds();
    }

    private static Instant midpoint(Instant from, Instant to) {
        return from.plus(Duration.between(from, to).dividedBy(2));
    }

    private static boolean expired(Instant since, Duration window, Instant now) {
        return now.isAfter(since.plus(window));
    }
}
