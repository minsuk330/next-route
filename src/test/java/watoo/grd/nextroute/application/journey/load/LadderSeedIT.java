package watoo.grd.nextroute.application.journey.load;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 부하 사다리(#415-4)용 여정을 DB 에 심는다. 지우지 않는다 — 뒤이어 띄운 앱과 k6 가 이 여정을 쓴다.
 *
 * <pre>
 * LADDER_SEED=true LADDER_JOURNEYS=2000 LADDER_RIDES=300,600,1080,2400 LADDER_OUT=build/ladder.tsv \
 *   ./gradlew test --tests '*LadderSeedIT'
 * LADDER_CLEAN=true ./gradlew test --tests '*LadderSeedIT'   # 지난 회차 지우기
 * </pre>
 *
 * <p>출력 TSV: journeyId, userId (토큰은 load/ladder/tokens.py 가 사용자 번호로 서명한다, gen=0).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "LADDER_SEED", matches = "true")
@DisplayName("부하 사다리 — 여정 심기")
class LadderSeedIT {

    @Autowired JdbcTemplate jdbc;

    @Test
    void seed() throws IOException {
        JourneyLoadFixture fixture = new JourneyLoadFixture(jdbc);
        if (Boolean.parseBoolean(System.getenv().getOrDefault("LADDER_CLEAN", "false"))) {
            List<Long> journeys = jdbc.queryForList(
                    "select s.id from journey_session s join journey_leg l on l.journey_id = s.id "
                            + "where l.route_id like 'LOAD-ROUTE-%' group by s.id", Long.class);
            List<Long> users = jdbc.queryForList(
                    "select distinct user_id from journey_session where id = any (?)", Long.class,
                    (Object) journeys.toArray(Long[]::new));
            fixture.cleanUp(new JourneyLoadFixture.Prepared(journeys, users, 0));
            return;
        }
        int journeys = Integer.parseInt(System.getenv().getOrDefault("LADDER_JOURNEYS", "2000"));
        String[] rideText = System.getenv().getOrDefault("LADDER_RIDES", "300,600,1080,2400").split(",");
        int[] rides = new int[rideText.length];
        for (int i = 0; i < rides.length; i++) {
            rides[i] = Integer.parseInt(rideText[i].trim());
        }
        // 정류장 공유: 실제로 여러 사용자가 같은 정류장·노선을 본다. 기본은 여정 3건당 키 1개.
        int distinct = Integer.parseInt(System.getenv().getOrDefault("LADDER_DISTINCT_STOPS",
                String.valueOf(Math.max(1, journeys / 3))));
        JourneyLoadFixture.Spec spec = new JourneyLoadFixture.Spec(journeys, distinct, Instant.now(),
                Duration.ofSeconds(30), 0.0, JourneyLoadFixture.DEFAULT_RIDE_SECONDS, rides);
        JourneyLoadFixture.Prepared prepared = fixture.prepare(spec);

        List<String> lines = new ArrayList<>();
        for (int i = 0; i < prepared.journeyIds().size(); i++) {
            lines.add(prepared.journeyIds().get(i) + "\t" + prepared.userIds().get(i));
        }
        Path out = Path.of(System.getenv().getOrDefault("LADDER_OUT", "build/ladder.tsv"));
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.write(out, lines);
        System.out.println("seeded " + lines.size() + " journeys → " + out.toAbsolutePath());
    }
}
