package watoo.grd.nextroute.domain.push;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import watoo.grd.nextroute.common.config.JpaConfig;
import watoo.grd.nextroute.domain.push.entity.PushEnvironment;
import watoo.grd.nextroute.domain.push.entity.PushPlatform;
import watoo.grd.nextroute.domain.push.entity.PushProvider;
import watoo.grd.nextroute.domain.push.entity.UserPushToken;
import watoo.grd.nextroute.domain.push.repository.UserPushTokenRepository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V39 가 만든 스키마와 {@link UserPushToken} 매핑을 실 PostgreSQL 로 확인한다.
 *
 * <p>스키마를 테스트용으로 복제하지 않고 <b>실제 마이그레이션 파일을 그대로 실행</b>한다
 * ({@code @Sql}). 복제본을 두면 마이그레이션만 고치고 테스트는 통과하는 상태가 생긴다.
 *
 * <p>여기서 고정하는 것.
 * <ul>
 *   <li>살아 있는 행 기준 token 유일성 — 그리고 해제한 token 은 다시 등록될 수 있다는 것</li>
 *   <li>기기 물려주기 — 같은 token 을 다른 사용자가 등록하면 앞선 소유자에게서 떨어진다</li>
 *   <li>{@code enabled} 와 {@code deleted_at} 이 서로 다른 사건이라는 것</li>
 *   <li>동시 등록에도 살아 있는 행은 하나</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(JpaConfig.class)
@Sql(scripts = "classpath:db/migration/V39__create_user_push_token.sql",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class UserPushTokenSchemaIT {

    /**
     * {@code @Testcontainers} 확장 대신 정적 초기화로 띄운다.
     *
     * <p>{@code @Sql(BEFORE_TEST_CLASS)} 가 확장보다 먼저 컨텍스트를 만들기 때문에, 확장에
     * 시작을 맡기면 datasource 를 물을 때 아직 컨테이너가 없다.
     */
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
                    .withInitScript("db/it/push-token-it-schema.sql");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        r.add("spring.flyway.enabled", () -> "false");
    }

    private static final String TOKEN = "fcm-token-eXy3-abcdefghijklmnopqrstuvwxyz";

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired UserPushTokenRepository repository;

    private TransactionTemplate tx;
    private long userId;
    private long otherUserId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        jdbcTemplate.update("delete from user_push_token");
        jdbcTemplate.update("delete from users");
        userId = insertUser();
        otherUserId = insertUser();
    }

    // ---------------------------------------------------------------- 유일성

    @Test
    @DisplayName("같은 token 으로 살아 있는 행을 둘 만들 수 없다")
    void liveTokenIsUnique() {
        insertRaw(userId, TOKEN, null);

        assertThatThrownBy(() -> insertRaw(otherUserId, TOKEN, null))
                .as("한 기기에 두 사람의 알림이 가는 사고를 DB 가 막아야 한다")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("해제한 token 은 같은 값으로 다시 등록할 수 있다")
    void softDeletedTokenCanBeRegisteredAgain() {
        insertRaw(userId, TOKEN, LocalDateTime.now());

        assertThatCode(() -> insertRaw(userId, TOKEN, null))
                .as("통 unique 였다면 이 기기는 영영 재등록하지 못한다 — 부분 인덱스인 이유")
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- register

    @Test
    @DisplayName("새 token 을 등록하면 enabled 로 한 행이 생기고 감사 칼럼이 채워진다")
    void registerInsertsRow() {
        Long id = register(userId, TOKEN, Instant.now());

        assertThat(id).isNotNull();
        UserPushToken saved = repository.findById(id).orElseThrow();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getDeletedAt()).isNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getPlatform()).isEqualTo(PushPlatform.ANDROID);
        assertThat(saved.getProvider()).isEqualTo(PushProvider.FCM);
        assertThat(saved.getEnvironment()).isEqualTo(PushEnvironment.PRODUCTION);
    }

    @Test
    @DisplayName("같은 token 을 다른 사용자가 등록하면 앞선 소유자에게서 떨어진다")
    void registerReassignsOwnerOnHandover() {
        Long first = register(userId, TOKEN, Instant.now());

        Long second = register(otherUserId, TOKEN, Instant.now());

        assertThat(second).as("행을 새로 만들지 않고 소유자만 바꾼다").isEqualTo(first);
        assertThat(repository.findById(first).orElseThrow().getUserId()).isEqualTo(otherUserId);
        assertThat(repository.findByUserIdAndEnabledTrueAndDeletedAtIsNull(userId)).isEmpty();
        assertThat(repository.findByUserIdAndEnabledTrueAndDeletedAtIsNull(otherUserId)).hasSize(1);
    }

    @Test
    @DisplayName("provider 가 껐던 token 도 앱이 다시 등록하면 되살아난다")
    void registerRevivesDisabledToken() {
        Long id = register(userId, TOKEN, Instant.now());
        jdbcTemplate.update("update user_push_token set enabled = false where id = ?", id);

        register(userId, TOKEN, Instant.now());

        assertThat(repository.findById(id).orElseThrow().isEnabled())
                .as("앱이 지금 등록하고 있다는 사실이 provider 의 옛 판정보다 새롭다")
                .isTrue();
    }

    @Test
    @DisplayName("등록은 lastSeenAt 을 갱신한다")
    void registerUpdatesLastSeenAt() {
        Instant first = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        Instant second = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Long id = register(userId, TOKEN, first);

        register(userId, TOKEN, second);

        assertThat(repository.findById(id).orElseThrow().getLastSeenAt()).isEqualTo(second);
    }

    // ---------------------------------------------------------------- unregister

    @Test
    @DisplayName("해제하면 deleted_at 이 찍힌다")
    void unregisterSoftDeletes() {
        Long id = register(userId, TOKEN, Instant.now());

        int affected = tx.execute(s -> repository.unregister(userId, TOKEN, LocalDateTime.now()));

        assertThat(affected).isEqualTo(1);
        assertThat(repository.findById(id).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(repository.findByUserIdAndEnabledTrueAndDeletedAtIsNull(userId)).isEmpty();
    }

    @Test
    @DisplayName("남의 token 은 해제되지 않는다")
    void unregisterGuardsOwnership() {
        Long id = register(userId, TOKEN, Instant.now());

        int affected = tx.execute(s -> repository.unregister(otherUserId, TOKEN, LocalDateTime.now()));

        assertThat(affected).isZero();
        assertThat(repository.findById(id).orElseThrow().getDeletedAt()).isNull();
    }

    // ---------------------------------------------------------------- 발송 대상 조회

    @Test
    @DisplayName("발송 대상은 disabled 도 soft delete 도 제외한다")
    void sendTargetsExcludeDisabledAndDeleted() {
        Long live = register(userId, TOKEN, Instant.now());
        Long disabled = register(userId, TOKEN + "-b", Instant.now());
        Long deleted = register(userId, TOKEN + "-c", Instant.now());
        jdbcTemplate.update("update user_push_token set enabled = false where id = ?", disabled);
        jdbcTemplate.update("update user_push_token set deleted_at = now() where id = ?", deleted);

        List<UserPushToken> targets =
                repository.findByUserIdAndEnabledTrueAndDeletedAtIsNull(userId);

        assertThat(targets).extracting(UserPushToken::getId).containsExactly(live);
    }

    @Test
    @DisplayName("사용자당 여러 기기를 허용한다")
    void allowsMultipleTokensPerUser() {
        register(userId, TOKEN + "-1", Instant.now());
        register(userId, TOKEN + "-2", Instant.now());

        assertThat(repository.findByUserIdAndEnabledTrueAndDeletedAtIsNull(userId)).hasSize(2);
    }

    // ---------------------------------------------------------------- 값 제약

    @Test
    @DisplayName("허용하지 않은 platform·provider·environment 와 공백 token 을 DB 가 막는다")
    void checkConstraintsRejectBadValues() {
        assertThatThrownBy(() -> insertRaw(userId, TOKEN, null, "WEB", "FCM", "PRODUCTION"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRaw(userId, TOKEN, null, "ANDROID", "APNS", "PRODUCTION"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRaw(userId, TOKEN, null, "ANDROID", "FCM", "STAGING"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRaw(userId, "   ", null, "ANDROID", "FCM", "PRODUCTION"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------------------------------------------------------------- 매핑

    @Test
    @DisplayName("Instant lastSeenAt 이 timestamptz 를 그대로 왕복한다")
    void instantRoundTrips() {
        Instant seenAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        UserPushToken saved = tx.execute(s -> repository.save(UserPushToken.builder()
                .userId(userId)
                .platform(PushPlatform.IOS)
                .provider(PushProvider.FCM)
                .token(TOKEN)
                .environment(PushEnvironment.DEVELOPMENT)
                .lastSeenAt(seenAt)
                .build()));

        UserPushToken loaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getLastSeenAt()).isEqualTo(seenAt);
        assertThat(loaded.getEnvironment()).isEqualTo(PushEnvironment.DEVELOPMENT);
        assertThat(loaded.getPlatform()).isEqualTo(PushPlatform.IOS);
        assertThat(loaded.getCreatedAt()).as("auditing 이 붙어야 한다").isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
    }

    // ---------------------------------------------------------------- 동시성

    @Test
    @DisplayName("두 요청이 같은 새 token 을 동시에 등록해도 예외 없이 살아 있는 행은 하나다")
    void concurrentRegisterKeepsSingleLiveRow() throws Exception {
        int workers = 2;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        long[] owners = {userId, otherUserId};
        try {
            List<Future<Long>> futures = IntStream.range(0, workers)
                    .mapToObj(i -> executor.submit((Callable<Long>) () -> {
                        start.await();
                        return register(owners[i], TOKEN, Instant.now());
                    }))
                    .toList();
            start.countDown();
            for (Future<Long> f : futures) {
                assertThat(f.get(15, TimeUnit.SECONDS))
                        .as("ON CONFLICT 가 충돌을 SQL 안에서 흡수한다 — 예외로 터지지 않는다")
                        .isNotNull();
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(liveRowCount(TOKEN)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select user_id from user_push_token where token = ?", Long.class, TOKEN))
                .isIn(userId, otherUserId);
    }

    // ---------------------------------------------------------------- helpers

    private long insertUser() {
        return jdbcTemplate.queryForObject(
                "insert into users (created_at, updated_at) values (now(), now()) returning id",
                Long.class);
    }

    private Long register(long owner, String token, Instant seenAt) {
        return tx.execute(s -> repository.register(
                owner, "ANDROID", "FCM", token, "PRODUCTION", seenAt, LocalDateTime.now()));
    }

    private void insertRaw(long owner, String token, LocalDateTime deletedAt) {
        insertRaw(owner, token, deletedAt, "ANDROID", "FCM", "PRODUCTION");
    }

    private void insertRaw(long owner, String token, LocalDateTime deletedAt,
                           String platform, String provider, String environment) {
        jdbcTemplate.update("""
                insert into user_push_token
                    (created_at, updated_at, deleted_at, user_id, platform, provider,
                     token, environment, enabled, last_seen_at)
                values (now(), now(), ?, ?, ?, ?, ?, ?, true, now())
                """, deletedAt, owner, platform, provider, token, environment);
    }

    private int liveRowCount(String token) {
        return jdbcTemplate.queryForObject(
                "select count(*) from user_push_token where token = ? and deleted_at is null",
                Integer.class, token);
    }
}
