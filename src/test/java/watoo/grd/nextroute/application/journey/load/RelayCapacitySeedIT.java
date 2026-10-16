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
import java.util.ArrayList;
import java.util.List;

/**
 * 발송(relay) 용량 실험(#461)용 여정·사용자·토큰을 DB 에 심는다. 알림 행은 생성기(relay_load_gen.py)가 넣는다.
 *
 * <pre>
 * RELAY_SEED=true RELAY_SESSIONS=2000 RELAY_OUT=out/relay-seed.tsv ./gradlew cleanTest test --tests '*RelayCapacitySeedIT'
 * RELAY_SEED=true RELAY_CLEAN=true ./gradlew cleanTest test --tests '*RelayCapacitySeedIT'   # 지우기
 * </pre>
 *
 * <p>심는 여정은 발송 직전 확인({@code JourneyNotificationOutboxStateService.loadTask})을 모두 통과하게 둔다: ACTIVE,
 * 안내 lease 6시간, route_revision·command_version 1. 평가기가 손대지 않게 {@code next_evaluation_at} 은 NULL 이다.
 * 활성 push 토큰이 없으면 {@code no-active-token} 영구 실패라 사용자마다 하나씩 둔다.
 *
 * <p>출력 TSV: 첫 여정 id, 마지막 여정 id, 개수. 생성기가 이 범위에서 여정을 돌려 쓴다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "RELAY_SEED", matches = "true")
@DisplayName("발송 용량 실험 — 여정 심기")
class RelayCapacitySeedIT {

    static final String MARKER = "LOAD-RELAY";
    static final String MARKER_JSON = "{\"ladder\":\"" + MARKER + "\"}";
    static final String TOKEN_PREFIX = "loadtest-relay-";
    private static final int BATCH = 500;

    @Autowired JdbcTemplate jdbc;

    @Test
    void seed() throws IOException {
        if (Boolean.parseBoolean(System.getenv().getOrDefault("RELAY_CLEAN", "false"))) {
            System.out.println("cleaned " + cleanUp() + " relay capacity journeys");
            return;
        }
        int sessions = Integer.parseInt(System.getenv().getOrDefault("RELAY_SESSIONS", "2000"));
        List<Long> ids = new ArrayList<>(sessions);
        for (int from = 0; from < sessions; from += BATCH) {
            int size = Math.min(BATCH, sessions - from);
            List<Long> users = jdbc.queryForList("""
                    insert into users (created_at, updated_at, token_generation)
                    select now(), now(), 0 from generate_series(1, ?)
                    returning id
                    """, Long.class, size);
            Long[] userIds = users.toArray(Long[]::new);
            jdbc.update("""
                    insert into user_push_token (created_at, updated_at, user_id, platform, provider, token,
                        environment, enabled, last_seen_at, quiet_update_supported)
                    select now(), now(), u, 'IOS', 'FCM', ? || u, 'PRODUCTION', true, now(), true
                    from unnest(?::bigint[]) u
                    """, TOKEN_PREFIX, userIds);
            ids.addAll(jdbc.queryForList("""
                    insert into journey_session (
                        created_at, updated_at, user_id, status, command_version, evaluation_version,
                        route_revision, current_leg_index, started_at, last_heartbeat_at,
                        guidance_lease_until, next_evaluation_at, route_snapshot, destination_summary)
                    select now(), now(), u, 'ACTIVE', 1, 0, 1, 0, now(), now(),
                           now() + interval '6 hours', null, ?::jsonb, ?::jsonb
                    from unnest(?::bigint[]) u
                    returning id
                    """, Long.class, SubwayLadderFixture.SNAPSHOT, MARKER_JSON, userIds));
        }
        long first = ids.stream().mapToLong(Long::longValue).min().orElseThrow();
        long last = ids.stream().mapToLong(Long::longValue).max().orElseThrow();
        Path path = Path.of(System.getenv().getOrDefault("RELAY_OUT", "build/relay-seed.tsv"));
        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, first + "\t" + last + "\t" + ids.size() + "\n");
        System.out.println("seeded " + ids.size() + " relay journeys [" + first + ", " + last + "] → "
                + path.toAbsolutePath());
    }

    /**
     * 표지로 찾은 여정의 알림 → 여정 → 토큰 → 사용자 순으로 지운다. 안내 lease 6시간으로 심으므로 지우지 않으면 같은 DB 를
     * 쓰는 IT 의 선점 시험이 잡는다.
     *
     * @return 지운 여정 수
     */
    int cleanUp() {
        List<Long> journeys = jdbc.queryForList(
                "select id from journey_session where destination_summary->>'ladder' = ?", Long.class, MARKER);
        Long[] ids = journeys.toArray(Long[]::new);
        Long[] users = jdbc.queryForList("select distinct user_id from journey_session where id = any (?)",
                Long.class, (Object) ids).toArray(Long[]::new);
        for (String table : List.of("journey_notification", "journey_event", "journey_revision")) {
            jdbc.update("delete from " + table + " where journey_id = any (?)", (Object) ids);
        }
        jdbc.update("delete from journey_session where id = any (?)", (Object) ids);
        jdbc.update("delete from user_push_token where token like ? or user_id = any (?)",
                TOKEN_PREFIX + "%", users);
        jdbc.update("delete from users where id = any (?)", (Object) users);
        return journeys.size();
    }
}
