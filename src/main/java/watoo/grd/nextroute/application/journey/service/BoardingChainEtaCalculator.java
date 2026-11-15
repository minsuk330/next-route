package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.BoardingEstimate;
import watoo.grd.nextroute.application.journey.dto.BoardingOutcome;
import watoo.grd.nextroute.application.journey.dto.BoardingQuery;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.port.out.BoardingTimeEstimator;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 경로 하나를 <b>처음부터 끝까지 시계를 굴리며</b> 목적지 도착 시각으로 옮긴다.
 *
 * <h2>왜 대기를 0으로 두지 않는가 (R1 폐기)</h2>
 * 예전 합산({@link RouteOptionEtaCalculator})은 <b>첫 대기만</b> 예측하고 그 뒤 대기는 0으로 뒀다.
 * 같은 구조를 비교할 때는 그 값이 양쪽에서 똑같이 빠져 상쇄됐지만, 재탐색 ④는 <b>경로 구조 자체를
 * 바꾼다</b> — 환승 수도 정류장도 다른 두 경로의 절대 도착 시각을 견주게 되므로, 빠진 대기 시간이
 * 상쇄되지 않고 그대로 <b>없는 개선폭</b>이 된다.
 *
 * <p>그래서 여기서는 <b>모든 대기</b>에 승차 예측을 물어본다. 앞 단계의 승차·주행·도보를 더한
 * 시각이 다음 대기의 앵커가 된다 — 30분 뒤에 닿을 정류장의 차를 "지금" 기준으로 묻지 않는다.
 *
 * <h2>못 풀면 후보가 아니다</h2>
 * 중간 대기 하나를 못 풀면 그 경로의 도착 시각은 <b>모르는 값</b>이다. 0으로 두면 낙관적인
 * 가짜 개선폭이 되고, 사용자는 그것을 믿고 갈아탄다. 그래서 <b>후보를 통째로 버린다</b>
 * (실시간→ML 은 예측기 안에서 이미 시도된 뒤다).
 *
 * <h2>기준선도 같은 체인을 탄다</h2>
 * 지금 경로와 후보를 <b>다른 방식</b>으로 재면 그 차이가 곧 개선폭으로 보인다. 그래서 이 계산기는
 * 저장된 여정({@code JourneyLeg})과 방금 검색한 후보({@code JourneyLegDraft}) 어느 쪽도 모르는
 * 중립 {@link Step} 만 받는다 — 부르는 쪽이 자기 모양을 여기 맞춘다.
 */
@Component
@RequiredArgsConstructor
public class BoardingChainEtaCalculator {

    private final List<BoardingTimeEstimator> boardingTimeEstimators;
    private final Clock clock;

    /**
     * @param steps                 한 경로의 단계 전부({@code legIndex} 오름차순)
     * @param fromLegIndexExclusive 이 번호까지는 이미 {@code anchorAt} 에 반영돼 있다
     * @param anchorAt              사용자가 <b>그다음 단계를 시작할 수 있는</b> 시각
     * @param now                   판정 기준 시각(예측기에 그대로 넘긴다)
     */
    public Result compute(List<Step> steps, int fromLegIndexExclusive, Instant anchorAt,
                          Instant now, Budget budget) {
        Instant deadline = clock.instant().plusMillis(budget.chainBudgetMs());
        Instant cursor = anchorAt;
        int riding = 0;
        int walking = 0;
        int waiting = 0;
        int boardings = 0;
        BoardingEstimate first = null;
        CurrentLegArrival.Quality worst = null;
        boolean modelBased = false;

        for (Step step : steps) {
            if (step.legIndex() <= fromLegIndexExclusive) {
                continue;
            }
            if (!step.type().isWaiting()) {
                Integer duration = step.plannedDurationSec();
                if (duration == null) {
                    // 검색 결과가 채우는 값이다. 없다면 데이터가 깨진 것이고, 0으로 치면
                    // 도착 시각이 실제보다 빨라져 없는 개선폭을 만든다.
                    return Result.failed("missing-duration");
                }
                cursor = cursor.plusSeconds(duration);
                riding += duration;
                if (step.type() == JourneyLegType.WALK
                        || step.type() == JourneyLegType.TRANSFER_WALK) {
                    walking += duration;
                }
                continue;
            }

            long perCall = Math.min(budget.perCallBudgetMs(),
                    Duration.between(clock.instant(), deadline).toMillis());
            if (perCall <= 0) {
                return Result.failed("budget-exhausted");
            }
            BoardingTimeEstimator estimator = estimatorFor(step.type());
            if (estimator == null) {
                return Result.failed("unresolved-route");
            }
            BoardingOutcome outcome = estimator.estimate(step.query(), cursor, now, perCall);
            if (!outcome.isPresent()) {
                // 첫 대기와 그 뒤 대기를 가른다 — 앞은 예전부터 있던 실패지만, 뒤는 체인이
                // 생기며 새로 보이게 된 실패다. 한 이름으로 묶으면 제외율이 왜 늘었는지 못 읽는다.
                return Result.failed(boardings == 0 ? "no-prediction" : "boarding-unresolved");
            }
            BoardingEstimate estimate = outcome.estimate();
            if (!estimate.boardable()) {
                // 사용자가 닿기 전에 지나갈 차다. "이걸 타면 5분 빠릅니다"로 제안할 수 없다.
                return Result.failed("unboardable");
            }
            long gap = Duration.between(cursor, estimate.boardingAt()).getSeconds();
            waiting += (int) Math.max(0, gap);
            cursor = estimate.boardingAt();
            if (first == null) {
                first = estimate;
            }
            worst = worse(worst, estimate.quality());
            // 실시간이 아니면(모델, 지방 공식 시간표 TIMETABLE) "추정 기반"으로 센다
            if (estimate.source() != watoo.grd.nextroute.application.journey.dto.CurrentLegArrival.Source.REALTIME_ARRIVAL) {
                modelBased = true;
            }
            boardings++;
        }

        return new Result(cursor, riding, walking, waiting, first,
                worst == null ? CurrentLegArrival.Quality.APPROXIMATE : worst, boardings, null,
                modelBased);
    }

    /**
     * 체인 전체의 신뢰도는 <b>가장 나쁜 고리</b>가 정한다. 첫 차를 실시간으로 잡았어도 두 번째
     * 차가 모델 추정이면 목적지 도착 시각의 오차는 그 모델 오차만큼이다.
     */
    private CurrentLegArrival.Quality worse(CurrentLegArrival.Quality a,
                                            CurrentLegArrival.Quality b) {
        if (a == null) {
            return b;
        }
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    private BoardingTimeEstimator estimatorFor(JourneyLegType waitType) {
        return boardingTimeEstimators.stream()
                .filter(estimator -> estimator.supports(waitType))
                .findFirst()
                .orElse(null);
    }

    /**
     * 체인의 한 단계.
     *
     * @param query 대기 단계면 승차를 물어볼 값 전부. 나머지 단계면 null
     */
    public record Step(int legIndex, JourneyLegType type, Integer plannedDurationSec,
                       BoardingQuery query) {

        public static Step of(int legIndex, JourneyLegType type, Integer plannedDurationSec) {
            return new Step(legIndex, type, plannedDurationSec, null);
        }
    }

    /**
     * 체인이 쓸 수 있는 시간.
     *
     * <p>둘을 나눠 받는 이유 — 대기가 둘인 경로는 예측을 <b>두 번</b> 부른다. 한 번의 상한
     * ({@code perCallBudgetMs})만 두면 총 대기가 그 배수로 늘고, 총 상한만 두면 마지막 호출이
     * 남은 시간을 통째로 물고 늘어진다.
     */
    public record Budget(long chainBudgetMs, long perCallBudgetMs) {

        public static Budget of(long budgetMs) {
            return new Budget(budgetMs, budgetMs);
        }
    }

    /**
     * 체인의 결과 또는 실패 사유.
     *
     * @param destinationEtaAt 목적지 도착 예상 시각
     * @param ridingSec        대기를 뺀 소요 합(주행+도보)
     * @param walkingSec       그중 걷는 시간. 화면 표시용
     * @param waitingSec       실제로 기다리는 시간의 합. <b>0으로 두지 않은 값</b>이다
     * @param firstEstimate    첫 대기의 승차 예측. 화면이 "이 차를 타세요"로 쓰는 값이다.
     *                         걷기만으로 닿는 경로면 null
     * @param quality          체인에서 가장 나쁜 고리의 신뢰도
     * @param boardings        예측한 승차 수
     * @param modelBased       승차 예측 중 하나라도 실시간이 아니었는가(ML 등). 재탐색 보드의 표시 문턱이
     *                         이 값으로 갈린다(#264) — 첫 고리의 출처만 보면 두 번째 차가 모델 추정이어도
     *                         "실시간"으로 읽힌다
     */
    public record Result(Instant destinationEtaAt, int ridingSec, int walkingSec, int waitingSec,
                         BoardingEstimate firstEstimate, CurrentLegArrival.Quality quality,
                         int boardings, String failureReason, boolean modelBased) {

        /** 근거 집계 이전 모양. 모르는 근거는 보수적으로 "모델 섞임"으로 둔다. */
        public Result(Instant destinationEtaAt, int ridingSec, int walkingSec, int waitingSec,
                      BoardingEstimate firstEstimate, CurrentLegArrival.Quality quality,
                      int boardings, String failureReason) {
            this(destinationEtaAt, ridingSec, walkingSec, waitingSec, firstEstimate, quality,
                    boardings, failureReason, true);
        }

        static Result failed(String reason) {
            return new Result(null, 0, 0, 0, null, CurrentLegArrival.Quality.UNKNOWN, 0, reason, true);
        }

        public boolean failed() {
            return destinationEtaAt == null;
        }
    }
}
