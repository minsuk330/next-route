package watoo.grd.nextroute.application.journey.load;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.subway.service.SubwayDirectionNormalizer;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 지하철 사다리 심기 계획(#427)의 규칙. 가짜 공급자와 맞춰야 하는 이름 규칙, 등급 비율 유지를 고정한다.
 */
@DisplayName("지하철 부하 사다리 — 심기 계획")
class SubwayLadderFixtureTest {

    private static final List<String> LINES = SubwayLadderFixture.DEFAULT_LINES;

    @Test
    @DisplayName("관심 키는 노선 × 역 × 두 방향이고 역 id·이름은 LOADST{n}·부하역{n} 이다")
    void keysFollowNamingShared() {
        List<SubwayLadderFixture.Key> keys = SubwayLadderFixture.keys(LINES, 60);

        assertThat(keys).hasSize(8 * 60 * 2);
        assertThat(new HashSet<>(keys)).hasSize(keys.size());
        SubwayLadderFixture.Key key = SubwayLadderFixture.Key.synthetic("1003", 61, 2);
        assertThat(key.stationId()).isEqualTo("LOADST61");
        assertThat(key.stationName()).isEqualTo("부하역61");
    }

    @Test
    @DisplayName("실제 원천 키 TSV 를 (노선, 역 id, 역 이름, 방향)으로 읽는다 — 2호선 내선 0 도 받는다")
    void readsRealKeys() {
        List<SubwayLadderFixture.Key> keys = SubwayLadderFixture.readKeys(List.of(
                "1002\t1002000222\t강남\t0", "1004\t1004000426\t서울\t1", "", "bad"));

        assertThat(keys).containsExactlyInAnyOrder(
                new SubwayLadderFixture.Key("1002", "1002000222", "강남", 0),
                new SubwayLadderFixture.Key("1004", "1004000426", "서울", 1));
        assertThat(SubwayDirectionNormalizer.fromWayCode("1002", 0)).isEqualTo("내선");
    }

    @Test
    @DisplayName("방향 코드는 앱이 상행·하행으로 읽는다 — 내·외선인 2호선은 쓰지 않는다")
    void wayCodesMapToUpDown() {
        assertThat(LINES).doesNotContain("1002");
        for (String line : LINES) {
            assertThat(SubwayDirectionNormalizer.fromWayCode(line, 1)).isEqualTo("상행");
            assertThat(SubwayDirectionNormalizer.fromWayCode(line, 2)).isEqualTo("하행");
        }
    }

    @Test
    @DisplayName("여정은 키에 고르게 흩어진다 — 2,000건이면 키마다 2~3건")
    void journeysSpreadOverKeys() {
        List<SubwayLadderFixture.Plan> plans = SubwayLadderFixture.plans(2000, LINES, 60);
        Set<SubwayLadderFixture.Key> used = new HashSet<>();
        plans.forEach(plan -> used.add(plan.key()));

        assertThat(used).hasSize(960);
    }

    @Test
    @DisplayName("준비 시각 기준 등급 비율이 심은 직후부터 40분 동안 긴급 20 / 주의 30 / 여유 50% 로 유지된다")
    void tierMixPersistsWithRecycling() {
        List<SubwayLadderFixture.Plan> plans = SubwayLadderFixture.plans(6000, LINES, 60);
        Instant seeded = Instant.parse("2026-10-15T00:00:00Z");

        for (int minute : new int[]{0, 3, 7, 13, 25, 40}) {
            double[] shares = SubwayLadderFixture.tierShares(plans, seeded, seeded.plusSeconds(minute * 60L));
            assertThat(shares[0]).as("긴급 @%d분", minute).isCloseTo(0.20, within(0.03));
            assertThat(shares[1]).as("주의 @%d분", minute).isCloseTo(0.30, within(0.03));
            assertThat(shares[2]).as("여유 @%d분", minute).isCloseTo(0.50, within(0.03));
        }
    }

    @Test
    @DisplayName("주기 되돌림 뒤 준비 시각은 늘 (지금, 지금 + 주기] 안이다")
    void readyAtStaysAhead() {
        Instant seeded = Instant.parse("2026-10-15T00:00:00Z");
        for (SubwayLadderFixture.Plan plan : SubwayLadderFixture.plans(500, LINES, 60)) {
            Instant at = seeded.plusSeconds(1234);
            Instant ready = plan.readyAt(seeded, at);
            assertThat(ready).isAfter(at);
            assertThat(ready).isBeforeOrEqualTo(at.plusSeconds(plan.cycle().cycleSeconds()));
        }
    }
}
