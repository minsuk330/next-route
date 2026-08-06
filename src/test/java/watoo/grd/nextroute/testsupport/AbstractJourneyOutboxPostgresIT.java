package watoo.grd.nextroute.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 여정 알림 outbox IT 의 PostgreSQL 컨테이너.
 *
 * <p>{@code AbstractBusAlertOutboxPostgresIT} 와 같은 모양이지만 <b>따로 둔다</b> — 두 outbox 는
 * 스키마도 상태 기계도 분리 유지하는 것이 결정이고, init script 를 공유하면 한쪽 표가 다른 쪽
 * 테스트에 딸려 들어온다.
 *
 * <p>init script 는 FK 대상인 {@code users} 만 만든다. journey 표는 하위 클래스가
 * {@code @Sql(BEFORE_TEST_CLASS)} 로 <b>실제 마이그레이션 파일</b>(V35~V40)을 실행해 만든다 —
 * DDL 복제본을 두면 마이그레이션만 고치고 테스트는 통과하는 상태가 생긴다.
 *
 * <p>{@code @Testcontainers} 확장 대신 정적 초기화로 띄우는 이유도 그것이다.
 * {@code @Sql(BEFORE_TEST_CLASS)} 가 확장보다 먼저 컨텍스트를 만들기 때문에 확장에 시작을
 * 맡기면 datasource 를 물을 때 아직 컨테이너가 없다.
 */
public abstract class AbstractJourneyOutboxPostgresIT {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
                    .withInitScript("db/it/journey-it-schema.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "false");
    }
}
