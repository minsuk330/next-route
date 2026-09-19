package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.service.RerouteRanking.Scheduled;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 후보를 줄 세우는 한 벌뿐인 규칙.
 *
 * <p>규칙이 한 벌이어야 하는 이유 — ④의 목록, 기준선을 못 잰 요청의 "하나만", 그리고 (PR-2 의)
 * 정류장별 목록이 <b>같은 순서</b>로 나와야 한다. 자리마다 다른 정렬을 쓰면 같은 후보가 상황에
 * 따라 다른 자리에 나타난다.
 */
@DisplayName("재탐색 후보 줄 세우기")
class RerouteRankingTest {

    private static final Instant BASE = Instant.parse("2026-09-19T09:00:00Z");

    @Test
    @DisplayName("먼저 도착 시각이다 — 개선폭이 아니라")
    void earliestArrivalWins() {
        Scheduled late = ranked(BASE.plusSeconds(1800), 0, 0, 0);
        Scheduled early = ranked(BASE.plusSeconds(1200), 2, 900, 0);

        assertThat(RerouteRanking.rank(List.of(late, early))).containsExactly(early, late);
        assertThat(RerouteRanking.best(List.of(late, early))).isSameAs(early);
    }

    @Test
    @DisplayName("같은 시각이면 덜 갈아탄다")
    void fewerTransfersBreakTies() {
        Scheduled twoTransfers = ranked(BASE.plusSeconds(1200), 2, 0, 0);
        Scheduled oneTransfer = ranked(BASE.plusSeconds(1200), 1, 600, 0);

        assertThat(RerouteRanking.rank(List.of(twoTransfers, oneTransfer)))
                .containsExactly(oneTransfer, twoTransfers);
    }

    @Test
    @DisplayName("환승 수도 같으면 덜 걷는다")
    void lessWalkingBreaksTies() {
        Scheduled far = ranked(BASE.plusSeconds(1200), 1, 900, 600);
        Scheduled near = ranked(BASE.plusSeconds(1200), 1, 120, 0);

        assertThat(RerouteRanking.rank(List.of(far, near))).containsExactly(near, far);
    }

    @Test
    @DisplayName("거기까지 같으면 여유가 큰 쪽이다 — 같은 시각에 닿는다면 지켜질 확률이 높다")
    void moreSlackBreaksTies() {
        Scheduled tight = ranked(BASE.plusSeconds(1200), 1, 120, 30);
        Scheduled relaxed = ranked(BASE.plusSeconds(1200), 1, 120, 300);

        assertThat(RerouteRanking.rank(List.of(tight, relaxed)))
                .containsExactly(relaxed, tight);
    }

    @Test
    @DisplayName("시간표 값도 같은 규칙으로 줄 세운다 — overlay 전에 거르는 기준이 달라지면 안 된다")
    void scheduledVariantUsesTheSameOrder() {
        Instant anchor = BASE;
        Scheduled slow = Scheduled.of(BASE.plusSeconds(2400), 1, 120, anchor,
                anchor.plusSeconds(60));
        Scheduled fast = Scheduled.of(BASE.plusSeconds(1500), 1, 120, anchor,
                anchor.plusSeconds(240));

        assertThat(RerouteRanking.best(List.of(slow, fast))).isSameAs(fast);
        // 여유는 앵커와 승차 시각의 차다. 음수(이미 지난 차)는 0으로 접는다.
        assertThat(fast.slackSec()).isEqualTo(240);
        assertThat(Scheduled.of(BASE, 0, 0, anchor, anchor.minusSeconds(60)).slackSec()).isZero();
    }

    @Test
    @DisplayName("빈 목록에는 최선이 없다")
    void emptyHasNoBest() {
        assertThat(RerouteRanking.best(List.<Scheduled>of())).isNull();
        assertThat(RerouteRanking.rank(List.<Scheduled>of())).isEmpty();
    }

    private Scheduled ranked(Instant etaAt, int transfers, int walkingSec, int slackSec) {
        return new Scheduled(etaAt, transfers, walkingSec, slackSec);
    }
}
