package watoo.grd.nextroute.application.journey.load;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import watoo.grd.nextroute.application.journey.reeval.InterestIndex;
import watoo.grd.nextroute.application.journey.reeval.InterestKey;
import watoo.grd.nextroute.application.journey.reeval.ReevaluationTier;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 심은 지하철 여정이 앱이 실제로 읽는 모양인지 — 관심 색인이 (노선, 역, 방향) 키로 찾고, 지우기가 남김없이 지운다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false",
        "journey.navigation.evaluator.enabled=false"
})
@DisplayName("지하철 부하 사다리 — 심기와 지우기")
class SubwayLadderFixtureIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired InterestIndex interestIndex;

    @AfterEach
    void cleanUp() {
        new SubwayLadderFixture(jdbc).cleanUp();
    }

    @Test
    @DisplayName("관심 색인이 심은 여정을 (노선, 역, 방향)으로 찾고, 지우면 여정·토큰·사용자가 0 이 된다")
    void seededJourneysAreWatchedAndCleaned() {
        SubwayLadderFixture fixture = new SubwayLadderFixture(jdbc);
        SubwayLadderFixture.Seeded seeded = fixture.seed(40, List.of("1003", "1004"), 10, Instant.now());
        SubwayLadderFixture.Plan first = seeded.plans().get(0);

        interestIndex.rebuild();
        InterestKey.Subway key = new InterestKey.Subway(first.key().lineId(), first.key().stationId(),
                first.key().wayCode() == 1 ? "상행" : "하행");
        Map<ReevaluationTier, Set<Long>> watching = interestIndex.journeysFor(List.of(key));
        assertThat(watching.values().stream().flatMap(Set::stream)).contains(seeded.journeyIds().get(0));
        assertThat(interestIndex.stopNamesFor(key)).containsExactly(first.key().stationName());
        assertThat(jdbc.queryForObject("select count(*) from user_push_token where token like 'loadtest-%'",
                Integer.class)).isEqualTo(40);

        assertThat(fixture.cleanUp()).isEqualTo(40);
        assertThat(jdbc.queryForObject("select count(*) from journey_leg where start_stop_id like 'LOADST%'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from journey_session where " + SubwayLadderFixture.MARKER_SQL,
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from user_push_token where token like 'loadtest-%'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from users where id = any (?)", Integer.class,
                (Object) seeded.userIds().toArray(Long[]::new))).isZero();
    }
}
