package watoo.grd.nextroute.application.journey.service;

import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 판정이 "바뀌었는지"를 가르는 규칙(#415 후속).
 *
 * <p><b>왜 허용 폭을 두나.</b> 실시간 도착 초는 공급자가 응답을 만든 시각 기준이라, 버스가 그대로여도
 * 절대 예정 시각이 조회마다 몇 초씩 움직인다. 시각을 그대로 비교하면 거의 모든 평가가 "바뀜"이 돼
 * (부하 사다리: 43,060 건 중 43,044) 무변화 백오프가 걸리지 않고, 앱 조회도 늘 본문(200)을 받는다.
 * 사용자에게 의미 없는 몇 초 흔들림은 같은 것으로 본다. 상태·출처가 바뀌면 폭과 무관하게 즉시 바뀜이다.
 *
 * <p><b>왜 허용 폭 안이면 옛 값을 그대로 저장하나.</b> 비교 기준을 "직전에 저장한 값"으로 두면, 예정
 * 시각이 평가마다 5초씩 밀릴 때 매번 폭 안이라 영영 "바뀜"이 안 되고 앱이 들고 있는 값과 몇 분씩
 * 벌어진다. 폭 안이면 시각은 그대로 두고 계산 시각만 새로 적어, 기준이 "마지막으로 번호를 올린 값"에
 * 고정되게 한다 — 저장값과 평가 번호가 늘 같은 것을 가리킨다.
 */
final class AssessmentChangeRule {

    private AssessmentChangeRule() {
    }

    /** 앱에 보일 값 기준으로 같은지. 시각·여유는 {@code tolerance} 안이면 같다. */
    static boolean same(TransferRiskAssessment now, TransferRiskAssessment previous, Duration tolerance) {
        if (previous == null) {
            return false;
        }
        return now.status() == previous.status()
                && now.targetSource() == previous.targetSource()
                && near(now.currentLegArrivalAt(), previous.currentLegArrivalAt(), tolerance)
                && near(now.userReadyAt(), previous.userReadyAt(), tolerance)
                && near(now.nextBusArrivalAt(), previous.nextBusArrivalAt(), tolerance)
                && nearSeconds(now.transferSlackSeconds(), previous.transferSlackSeconds(), tolerance);
    }

    /**
     * 저장할 판정. 직전과 같으면(폭 안) 직전 값을 그대로 두고 계산 시각만 이번 것으로 — 낡음 판정
     * ({@code stale-after})은 계산 시각을 보므로 계속 새로워야 한다.
     */
    static List<TransferRiskAssessment> stabilize(List<TransferRiskAssessment> now,
                                                  Map<Integer, TransferRiskAssessment> previous,
                                                  Duration tolerance) {
        List<TransferRiskAssessment> out = new ArrayList<>(now.size());
        for (TransferRiskAssessment assessment : now) {
            TransferRiskAssessment before = previous.get(assessment.transferIndex());
            out.add(same(assessment, before, tolerance) ? keepValues(before, assessment) : assessment);
        }
        return out;
    }

    /** 이번 판정 중 하나라도 직전과 다른지. 평가 번호를 올리고 백오프를 건너뛰는 근거. */
    static boolean anyChanged(List<TransferRiskAssessment> now,
                              Map<Integer, TransferRiskAssessment> previous,
                              Duration tolerance) {
        return now.stream().anyMatch(a -> !same(a, previous.get(a.transferIndex()), tolerance));
    }

    private static TransferRiskAssessment keepValues(TransferRiskAssessment before, TransferRiskAssessment now) {
        return new TransferRiskAssessment(now.transferIndex(), now.fromLegIndex(), now.toLegIndex(),
                before.status(), before.currentLegArrivalAt(), before.userReadyAt(), before.nextBusArrivalAt(),
                before.transferSlackSeconds(), now.confidence(), now.calculatedAt(), before.targetSource(),
                now.modelVersion());
    }

    private static boolean near(Instant a, Instant b, Duration tolerance) {
        if (a == null || b == null) {
            return Objects.equals(a, b);
        }
        return Duration.between(a, b).abs().compareTo(tolerance) < 0;
    }

    private static boolean nearSeconds(Long a, Long b, Duration tolerance) {
        if (a == null || b == null) {
            return Objects.equals(a, b);
        }
        return Duration.ofSeconds(Math.abs(a - b)).compareTo(tolerance) < 0;
    }
}
