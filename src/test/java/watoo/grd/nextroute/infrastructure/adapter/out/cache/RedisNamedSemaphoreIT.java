package watoo.grd.nextroute.infrastructure.adapter.out.cache;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import watoo.grd.nextroute.infrastructure.adapter.out.cache.bus.RedisTestSupport;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("이름 붙은 서버 공유 세마포어")
class RedisNamedSemaphoreIT {

    private LettuceConnectionFactory factory;
    private RedisTemplate<String, String> redis;
    private String name;

    @BeforeEach
    void setUp() {
        factory = RedisTestSupport.factory();
        redis = RedisTestSupport.template(factory);
        name = "it-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        redis.delete(RedisNamedSemaphore.key(name));
        factory.destroy();
    }

    @Test
    @DisplayName("두 인스턴스가 합쳐 상한을 넘지 않고, 반납하면 다시 얻는다")
    void sharedLimit() {
        RedisNamedSemaphore a = new RedisNamedSemaphore(redis);
        RedisNamedSemaphore b = new RedisNamedSemaphore(redis);

        Optional<String> t1 = a.tryAcquire(name, 2, 5000, 0, 10);
        Optional<String> t2 = b.tryAcquire(name, 2, 5000, 0, 10);
        Optional<String> t3 = a.tryAcquire(name, 2, 5000, 100, 10);

        assertThat(t1).isPresent();
        assertThat(t2).isPresent();
        assertThat(t3).as("합산 2 를 넘지 않는다").isEmpty();

        b.release(name, t2.get());
        assertThat(a.tryAcquire(name, 2, 5000, 0, 10)).isPresent();
    }

    @Test
    @DisplayName("lease 가 지나면 자리가 저절로 회수된다")
    void leaseExpires() throws Exception {
        RedisNamedSemaphore a = new RedisNamedSemaphore(redis);
        assertThat(a.tryAcquire(name, 1, 200, 0, 10)).isPresent();
        assertThat(a.tryAcquire(name, 1, 200, 0, 10)).isEmpty();
        Thread.sleep(300);
        assertThat(a.tryAcquire(name, 1, 200, 0, 10)).isPresent();
    }
}
