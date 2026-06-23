package watoo.grd.nextroute.domain.notification;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import watoo.grd.nextroute.domain.notification.entity.BusArrivalAlert;
import watoo.grd.nextroute.domain.notification.repository.BusArrivalAlertRepository;
import watoo.grd.nextroute.domain.user.entity.User;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 실 Postgres 기반 통합 테스트: bus_arrival_alert 의 partial unique index 동작을 검증한다.
 * (단위 테스트로는 불가능.) 스키마는 IT 전용 init script(V19 와 동일 DDL)로 구성한다 —
 * 전체 Flyway 체인은 V1 baseline 이 postgis 내장 객체와 충돌해 fresh 컨테이너에서 부적합하므로 제외.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class BusArrivalAlertRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
                    .withInitScript("db/it/bus-alert-it-schema.sql");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        r.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired BusArrivalAlertRepository repository;
    @Autowired TestEntityManager em;

    private User persistUser(long tossUserKey) {
        return em.persistAndFlush(new User(tossUserKey));
    }

    private BusArrivalAlert pending(User user) {
        return BusArrivalAlert.builder()
                .user(user).stopId("S1").routeId("R1").ord(3)
                .routeName("간선143").stopName("강남역")
                .expiresAt(LocalDateTime.now().plusMinutes(60))
                .build();
    }

    @Test
    void migrationApplied_andValidatePasses() {
        // 컨텍스트 로드(=validate 통과) + 테이블 비어있음
        assertThat(repository.count()).isZero();
    }

    @Test
    void duplicateActiveSubscription_violatesPartialUnique() {
        User user = persistUser(1001L);
        repository.saveAndFlush(pending(user));

        assertThatThrownBy(() -> repository.saveAndFlush(pending(user)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void canceledThenNewPending_allowed() {
        User user = persistUser(1002L);
        BusArrivalAlert first = repository.saveAndFlush(pending(user));
        first.markCanceled();
        repository.saveAndFlush(first);

        // 동일 키지만 이전 건이 CANCELED → partial unique(PENDING/PROCESSING) 영향 없음
        repository.saveAndFlush(pending(user));

        assertThat(repository.count()).isEqualTo(2);
    }
}
