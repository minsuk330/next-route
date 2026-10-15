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
        registry.add("spring.datasource.url", AbstractJourneyOutboxPostgresIT::jdbcUrlWithoutServerPreparedStatements);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.flyway.enabled", () -> "false");
    }

    /**
     * 서버 쪽 prepared statement 를 끈다({@code prepareThreshold=0}).
     *
     * <p>이 컨테이너를 쓰는 클래스들은 {@code journey-reset.sql} 로 표를 지우고 <b>서로 다른 마이그레이션 묶음</b>으로
     * 다시 만든다 — 그래서 {@code journey_session} 의 열 구성이 클래스마다 다르다. 같은 설정의 클래스는 Spring
     * 컨텍스트(= Hikari 풀)를 공유하고, pgjdbc 는 같은 SQL 을 5번 넘게 쓰면 서버에 이름 붙은 계획으로 굳힌다.
     * Hibernate 의 insert 는 생성 키 때문에 {@code RETURNING *} 가 붙어 결과 모양이 표의 열 전체다. 앞 클래스가
     * 굳힌 계획을 열이 바뀐 표에 다시 쓰면 PostgreSQL 이 {@code cached plan must not change result type}(0A000)
     * 으로 거절하고, Hikari 는 0A000 을 치명 오류로 보고 연결을 닫아 롤백까지 {@code Connection is closed} 로 깨졌다.
     * 단독 실행은 풀이 새것이라 늘 통과했다(전체 실행에서만 {@code JourneyNotificationSchemaIT} 첫 시험이 실패).
     * 운영은 실행 중에 표를 다시 만들지 않으므로 시험 컨테이너에만 건다.
     */
    private static String jdbcUrlWithoutServerPreparedStatements() {
        String url = POSTGRES.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "prepareThreshold=0";
    }
}
