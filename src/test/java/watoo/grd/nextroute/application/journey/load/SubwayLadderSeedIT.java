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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 지하철 부하 사다리(#427)용 여정을 DB 에 심는다. 지우지 않는다 — 뒤이어 띄운 앱·k6·표본기가 쓴다.
 *
 * <pre>
 * LADDER_SUBWAY_SEED=true LADDER_JOURNEYS=2000 LADDER_OUT=out/ladder.tsv ./gradlew cleanTest test --tests '*SubwayLadderSeedIT'
 * LADDER_SUBWAY_SEED=true LADDER_CLEAN=true ./gradlew cleanTest test --tests '*SubwayLadderSeedIT'   # 지우기
 * </pre>
 *
 * <p>Gradle 은 환경 변수를 입력으로 보지 않아 {@code cleanTest} 를 빼면 조용히 건너뛴다(버스 사다리에서 겪음).
 *
 * <p>출력 TSV: journeyId, userId, lineId, stationId, wayCode, 부류(A/B/C). 토큰은 tokens.py 가 앞 두 칸으로 서명하고,
 * 감지 지연 집계(subway_detection.py)가 노선·역·방향으로 공급자 변화 기록과 짝짓는다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "LADDER_SUBWAY_SEED", matches = "true")
@DisplayName("지하철 부하 사다리 — 여정 심기")
class SubwayLadderSeedIT {

    @Autowired JdbcTemplate jdbc;

    @Test
    void seed() throws IOException {
        SubwayLadderFixture fixture = new SubwayLadderFixture(jdbc);
        if (Boolean.parseBoolean(System.getenv().getOrDefault("LADDER_CLEAN", "false"))) {
            System.out.println("cleaned " + fixture.cleanUp() + " subway ladder journeys");
            return;
        }
        int journeys = Integer.parseInt(System.getenv().getOrDefault("LADDER_JOURNEYS", "2000"));
        List<String> lines = Arrays.asList(System.getenv().getOrDefault("LADDER_SUBWAY_LINES",
                String.join(",", SubwayLadderFixture.DEFAULT_LINES)).split(","));
        int stations = Integer.parseInt(System.getenv().getOrDefault("LADDER_SUBWAY_STATIONS", "60"));
        Instant now = Instant.now();
        // 실제 원천 회차: 심기 직전 ALL 한 장에서 뽑은 키 TSV(run_subway_ladder.sh). 없으면 가상 노선망.
        String keysFile = System.getenv("LADDER_SUBWAY_KEYS");
        List<SubwayLadderFixture.Key> keys = keysFile == null || keysFile.isBlank()
                ? SubwayLadderFixture.keys(lines, stations)
                : SubwayLadderFixture.readKeys(Files.readAllLines(Path.of(keysFile)));
        SubwayLadderFixture.Seeded seeded = fixture.seed(journeys, keys, now);

        List<String> out = new ArrayList<>();
        for (int i = 0; i < seeded.journeyIds().size(); i++) {
            SubwayLadderFixture.Plan plan = seeded.plans().get(i);
            out.add(seeded.journeyIds().get(i) + "\t" + seeded.userIds().get(i) + "\t" + plan.key().lineId()
                    + "\t" + plan.key().stationId() + "\t" + plan.key().wayCode() + "\t" + plan.cycle()
                    + "\t" + plan.key().stationName());
        }
        Path path = Path.of(System.getenv().getOrDefault("LADDER_OUT", "build/ladder-subway.tsv"));
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.write(path, out);
        System.out.println("seeded " + out.size() + " subway journeys → " + path.toAbsolutePath());
    }
}
