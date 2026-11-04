package watoo.grd.nextroute.infrastructure.adapter.out.cache.bus;

import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/** REDIS_HOST·REDIS_PORT 의 일회용 Redis 에 붙는 템플릿(IT 공용). 호출자가 factory.destroy() 를 맡는다. */
public final class RedisTestSupport {

    private RedisTestSupport() {
    }

    public static LettuceConnectionFactory factory() {
        String host = System.getenv().getOrDefault("REDIS_HOST", "localhost");
        int port = Integer.parseInt(System.getenv().getOrDefault("REDIS_PORT", "6379"));
        LettuceConnectionFactory factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
        factory.afterPropertiesSet();
        return factory;
    }

    public static RedisTemplate<String, String> template(LettuceConnectionFactory factory) {
        RedisTemplate<String, String> redis = new RedisTemplate<>();
        redis.setConnectionFactory(factory);
        redis.setKeySerializer(new StringRedisSerializer());
        redis.setValueSerializer(new StringRedisSerializer());
        redis.afterPropertiesSet();
        return redis;
    }
}
