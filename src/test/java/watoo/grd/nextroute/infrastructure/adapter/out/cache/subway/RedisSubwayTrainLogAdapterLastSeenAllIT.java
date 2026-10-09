package watoo.grd.nextroute.infrastructure.adapter.out.cache.subway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import watoo.grd.nextroute.application.subway.config.SubwayPositionProperties;
import watoo.grd.nextroute.application.subway.dto.SubwayExpressType;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainObservation;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainStatus;
import watoo.grd.nextroute.application.subway.dto.TrainIdentity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 역 도착 응답(#317)이 쓰는 위치 일괄 조회 — 한 번씩 물은 것({@code lastSeen})과 같은 답이어야 한다.
 *
 * <p>DB 가 필요 없는 계약이라 스프링 컨텍스트 없이 어댑터만 Redis 컨테이너에 붙인다.
 */
@DisplayName("열차 위치 일괄 조회 Redis 계약")
class RedisSubwayTrainLogAdapterLastSeenAllIT {

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory factory;
    private static RedisSubwayTrainLogAdapter adapter;

    @BeforeAll
    static void start() {
        REDIS.start();
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(),
                REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        StringRedisSerializer strings = new StringRedisSerializer();
        template.setKeySerializer(strings);
        template.setValueSerializer(strings);
        template.setHashKeySerializer(strings);
        template.setHashValueSerializer(strings);
        template.afterPropertiesSet();
        adapter = new RedisSubwayTrainLogAdapter(template, new ObjectMapper(), new SubwayPositionProperties());
    }

    @AfterAll
    static void stop() {
        factory.destroy();
        REDIS.stop();
    }

    @Test
    @DisplayName("여러 노선·운행일·방향을 섞어 물어도 있는 것만, 한 대씩 물은 것과 같은 값으로")
    void matchesSingleLookups() {
        LocalDate today = LocalDate.of(2026, 10, 7);
        TrainIdentity inner = new TrainIdentity(today, "1002", "내선", "2231");
        TrainIdentity outer = new TrainIdentity(today, "1002", "외선", "2231");
        TrainIdentity yesterday = new TrainIdentity(today.minusDays(1), "1002", "내선", "2231");
        TrainIdentity otherLine = new TrainIdentity(today, "1001", "하행", "K101");
        TrainIdentity missing = new TrainIdentity(today, "1002", "내선", "9999");
        SubwayTrainObservation a = new SubwayTrainObservation("1002000223", "역삼", SubwayTrainStatus.DEPARTED,
                Instant.parse("2026-10-07T07:00:00Z"), "1002000211", SubwayExpressType.NORMAL);
        SubwayTrainObservation b = new SubwayTrainObservation("1002000224", "선릉", SubwayTrainStatus.LEFT_PREVIOUS,
                Instant.parse("2026-10-07T06:59:00Z"), null, SubwayExpressType.UNKNOWN);
        SubwayTrainObservation c = new SubwayTrainObservation("1001000160", "진위", SubwayTrainStatus.ARRIVED,
                Instant.parse("2026-10-07T06:58:00Z"), null, SubwayExpressType.EXPRESS);
        adapter.remember(inner, a);
        adapter.remember(outer, b);
        adapter.remember(otherLine, c);

        Map<TrainIdentity, SubwayTrainObservation> all =
                adapter.lastSeenAll(List.of(inner, outer, yesterday, otherLine, missing));

        assertThat(all).containsOnlyKeys(inner, outer, otherLine);
        assertThat(all.get(inner)).isEqualTo(adapter.lastSeen(inner).orElseThrow()).isEqualTo(a);
        assertThat(all.get(outer)).isEqualTo(b);
        assertThat(all.get(otherLine)).isEqualTo(c);
        assertThat(adapter.lastSeenAll(List.of())).isEmpty();
    }
}
