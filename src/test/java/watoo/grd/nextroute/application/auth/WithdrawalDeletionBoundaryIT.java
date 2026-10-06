package watoo.grd.nextroute.application.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import watoo.grd.nextroute.application.auth.service.WithdrawService;
import watoo.grd.nextroute.common.config.JpaConfig;
import watoo.grd.nextroute.domain.user.entity.User;
import watoo.grd.nextroute.domain.user.repository.UserRepository;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 탈퇴 삭제 경계 계약을 실 PostgreSQL 로 고정한다.
 *
 * <p>스키마는 <b>엔티티에서 Hibernate 가 생성</b>한다({@code ddl-auto=create-drop}). V1 baseline 이
 * pg_dump 스냅샷이라 선형 replay 가 안 되고(프로덕션은 baseline 마커로 건너뜀), 마이그레이션을
 * 통째로 컨테이너에 올릴 수 없기 때문이다. 컬럼 정합성은 기능별 스키마 IT
 * ({@code UserPushTokenSchemaIT}·{@code JourneySchemaIT})가 이미 마이그레이션으로 고정하므로, 여기서는
 * <b>탈퇴 서비스의 행위 계약</b>(어떤 행을 지우고 어떤 행을 남기는가, FK 안전 순서, 멱등)을 실
 * PostgreSQL 로 고정한다.
 *
 * <p>한 사용자에게 전 기능의 데이터를 심고 {@link WithdrawService#withdraw} 를 호출해 버킷 3종을
 * 검증한다. 물리=행 0, 소프트={@code deleted_at} 채워짐, 손대지않음=행 그대로.
 *
 * <p>또한 {@code users}·{@code journey_session}·{@code journey_leg}·알림 부모에 매달린 FK 자식(엔티티)이
 * 코드가 아는 목록 밖으로 새로 생기면 실패하게 한다(자식 엔티티 추가 시 조용한 누락 방지).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({JpaConfig.class, WithdrawService.class})
class WithdrawalDeletionBoundaryIT {

    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"));

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        // 엔티티에서 스키마 생성(위 클래스 주석의 근거). Flyway 는 끈다.
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        r.add("spring.flyway.enabled", () -> "false");
    }

    /** 물리 삭제 대상: 탈퇴 후 이 사용자 관련 행이 0이어야 한다. */
    private static final List<String> PHYSICAL = List.of(
            "auth_identity", "apple_user_token", "user_push_token", "subway_live_activity_token",
            "subway_ring_push_outbox", "route_arrival_alert", "route_arrival_alert_notification",
            "bus_arrival_alert", "bus_alert_notification", "route_alert_schedule",
            "route_search_history", "favorite_route", "route_search_log");

    /** 소프트 삭제 대상: 탈퇴 후 이 사용자 관련 행이 전부 deleted_at 채워져야 한다. */
    private static final List<String> SOFT = List.of(
            "journey_session", "journey_leg", "journey_leg_route_option", "transfer_connection",
            "journey_event", "journey_revision", "journey_telemetry_event", "reroute_proposal",
            "subway_binding_event");

    /** 손대지 않음: 탈퇴 후에도 행이 그대로 살아 있어야 한다(V40·V44 삭제 금지). */
    private static final List<String> UNTOUCHED = List.of(
            "journey_notification", "subway_leg_binding");

    @Autowired JdbcTemplate jdbc;
    @Autowired WithdrawService withdrawService;
    @Autowired UserRepository userRepository;

    private long userId;
    private long journeyId;
    private long legId;

    @BeforeEach
    void setUp() {
        // 자식→부모 역순으로 비운다(FK). 필요한 표만.
        for (String t : List.of(
                "subway_ring_push_outbox", "subway_binding_event", "subway_leg_binding",
                "subway_live_activity_token", "journey_notification", "journey_telemetry_event",
                "reroute_proposal", "journey_event", "journey_revision", "transfer_connection",
                "journey_leg_route_option", "journey_leg", "journey_session",
                "bus_alert_notification", "bus_arrival_alert", "route_arrival_alert_notification",
                "route_arrival_alert", "route_alert_schedule", "route_search_history",
                "route_search_log", "favorite_route", "user_push_token", "auth_identity",
                "apple_user_token", "toss_user_token", "users")) {
            jdbc.update("delete from " + t);
        }
        userId = insertUser(700L);
        seedAll(userId);
    }

    // ── 경계 계약 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("탈퇴하면 물리 대상은 0행, 소프트 대상은 deleted_at 채움, 손대지않음은 그대로다")
    void withdraw_appliesThreeBuckets() {
        withdrawService.withdraw(userId);

        for (String t : PHYSICAL) {
            assertThat(userRowCount(t)).as("물리 삭제: %s 는 이 사용자 행이 없어야 한다", t).isZero();
        }
        for (String t : SOFT) {
            assertThat(userRowCount(t)).as("소프트: %s 행은 남아야 한다", t).isPositive();
            assertThat(liveRowCount(t)).as("소프트: %s 는 전부 deleted_at 이 찍혀야 한다", t).isZero();
        }
        for (String t : UNTOUCHED) {
            assertThat(userRowCount(t)).as("손대지않음: %s 행은 그대로 있어야 한다", t).isPositive();
            assertThat(liveRowCount(t)).as("손대지않음: %s 는 deleted_at 이 없어야 한다", t).isPositive();
        }
        // toss_user_token 은 user_key 로 매달려 위 switch 규칙 밖이다. 물리 삭제 대상.
        assertThat(jdbc.queryForObject("select count(*) from toss_user_token", Integer.class))
                .as("물리 삭제: 토스 토큰이 남으면 안 된다").isZero();
    }

    @Test
    @DisplayName("탈퇴하면 users 는 deleted_at 채움·toss_user_key NULL·세대 증가다")
    void withdraw_finalizesUsers() {
        int before = jdbc.queryForObject(
                "select token_generation from users where id = ?", Integer.class, userId);

        withdrawService.withdraw(userId);

        Map<String, Object> row = jdbc.queryForMap(
                "select deleted_at, toss_user_key, token_generation from users where id = ?", userId);
        assertThat(row.get("deleted_at")).as("users 는 소프트 삭제된다").isNotNull();
        assertThat(row.get("toss_user_key")).as("토스 신원을 지운다").isNull();
        assertThat(((Number) row.get("token_generation")).intValue())
                .as("세대를 올려 이전 토큰을 끊는다").isEqualTo(before + 1);
    }

    @Test
    @DisplayName("같은 트랜잭션 밖에서 JPA 재조회해도 탈퇴 상태가 보인다(stale 아님)")
    void withdraw_isVisibleToJpaReread() {
        withdrawService.withdraw(userId);

        // 활성 조회는 비어야 한다. 벌크 update 가 컨텍스트를 우회해도 커밋되었음을 확인한다.
        assertThat(userRepository.findByIdAndDeletedAtIsNull(userId))
                .as("탈퇴한 사용자는 활성 조회에서 사라진다").isEmpty();
        User reloaded = userRepository.findById(userId).orElseThrow();
        assertThat(reloaded.getDeletedAt()).isNotNull();
        assertThat(reloaded.getTossUserKey()).isNull();
    }

    @Test
    @DisplayName("이미 탈퇴한 사용자를 다시 탈퇴해도 예외 없이 상태가 불변이다(멱등)")
    void withdraw_isIdempotent() {
        withdrawService.withdraw(userId);
        int genAfterFirst = jdbc.queryForObject(
                "select token_generation from users where id = ?", Integer.class, userId);

        withdrawService.withdraw(userId); // 재호출

        int genAfterSecond = jdbc.queryForObject(
                "select token_generation from users where id = ?", Integer.class, userId);
        assertThat(genAfterSecond).as("멱등: 두 번째 호출은 아무 것도 바꾸지 않는다")
                .isEqualTo(genAfterFirst);
    }

    @Test
    @DisplayName("다른 사용자의 데이터는 탈퇴에 휩쓸리지 않는다")
    void withdraw_doesNotTouchOtherUsers() {
        long otherId = insertUser(701L);
        seedAll(otherId);

        withdrawService.withdraw(userId);

        assertThat(jdbc.queryForObject(
                "select count(*) from user_push_token where user_id = ?", Integer.class, otherId))
                .as("남의 발송 주소는 지우지 않는다").isPositive();
        assertThat(jdbc.queryForObject(
                "select deleted_at from journey_session where user_id = ?", Object.class, otherId))
                .as("남의 여정은 소프트 삭제되지 않는다").isNull();
        assertThat(userRepository.findByIdAndDeletedAtIsNull(otherId))
                .as("남의 계정은 활성 상태 그대로다").isPresent();
    }

    // ── FK 그래프 완전성(자식 추가 시 조용한 누락 방지) ──────────────────────

    @Test
    @DisplayName("users·journey·알림 부모에 매달린 FK 자식이 전부 알려진 버킷에 있다")
    void everyChildTableIsAccountedFor() {
        Set<String> known = new java.util.HashSet<>();
        known.addAll(PHYSICAL);
        known.addAll(SOFT);
        known.addAll(UNTOUCHED);
        // 우리가 다루는 부모들(이들에 매달린 자식이 검사 대상).
        Set<String> handledParents = Set.of(
                "users", "journey_session", "journey_leg",
                "route_arrival_alert", "bus_arrival_alert");

        List<Map<String, Object>> edges = jdbc.queryForList("""
                select distinct kcu.table_name as child, ccu.table_name as parent
                  from information_schema.referential_constraints rc
                  join information_schema.key_column_usage kcu
                    on kcu.constraint_name = rc.constraint_name
                   and kcu.constraint_schema = rc.constraint_schema
                  join information_schema.constraint_column_usage ccu
                    on ccu.constraint_name = rc.unique_constraint_name
                   and ccu.constraint_schema = rc.unique_constraint_schema
                 where kcu.table_schema = 'public'
                   and ccu.table_schema = 'public'
                """);

        List<String> unaccounted = edges.stream()
                .filter(e -> handledParents.contains(String.valueOf(e.get("parent"))))
                .map(e -> String.valueOf(e.get("child")))
                .filter(child -> !known.contains(child) && !handledParents.contains(child))
                .distinct()
                .toList();

        assertThat(unaccounted)
                .as("이 자식 테이블(들)이 탈퇴 경계 목록에 없다. 물리/소프트/손대지않음 중 하나로 "
                        + "분류하고 WithdrawService 에 삭제를 추가하라: %s", unaccounted)
                .isEmpty();
    }

    @Test
    @DisplayName("코드가 아는 버킷 테이블이 실제 스키마에 전부 존재한다")
    void knownBucketTablesExist() {
        List<String> all = jdbc.queryForList("""
                select table_name from information_schema.tables where table_schema = 'public'
                """, String.class);
        for (String t : PHYSICAL) assertThat(all).as("물리 %s", t).contains(t);
        for (String t : SOFT) assertThat(all).as("소프트 %s", t).contains(t);
        for (String t : UNTOUCHED) assertThat(all).as("손대지않음 %s", t).contains(t);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** 그 사용자에 매달린 행 수(직접 user_id, 또는 여정/알림 경유). */
    private int userRowCount(String table) {
        String sql = switch (table) {
            case "auth_identity", "apple_user_token", "user_push_token", "subway_live_activity_token",
                 "bus_arrival_alert", "route_alert_schedule", "route_search_history",
                 "route_search_log", "journey_session", "journey_telemetry_event",
                 "reroute_proposal", "journey_notification" ->
                    "select count(*) from " + table + " where user_id = ?";
            case "route_arrival_alert" ->
                    "select count(*) from route_arrival_alert where user_id = ?";
            case "favorite_route" ->
                    "select count(*) from favorite_route where user_id = ?";
            case "route_arrival_alert_notification" -> """
                    select count(*) from route_arrival_alert_notification n
                     where n.alert_id in (select id from route_arrival_alert where user_id = ?)""";
            case "bus_alert_notification" -> """
                    select count(*) from bus_alert_notification n
                     where n.alert_id in (select id from bus_arrival_alert where user_id = ?)""";
            case "subway_ring_push_outbox", "journey_leg", "transfer_connection",
                 "journey_event", "journey_revision", "subway_leg_binding" -> """
                    select count(*) from %s
                     where journey_id in (select id from journey_session where user_id = ?)"""
                    .formatted(table);
            case "journey_leg_route_option", "subway_binding_event" -> """
                    select count(*) from %s
                     where journey_leg_id in (
                         select id from journey_leg where journey_id in (
                             select id from journey_session where user_id = ?))"""
                    .formatted(table);
            default -> throw new IllegalArgumentException("no rule for " + table);
        };
        return jdbc.queryForObject(sql, Integer.class, userId);
    }

    /** 그 사용자에 매달린 행 중 아직 살아 있는(deleted_at IS NULL) 행 수. */
    private int liveRowCount(String table) {
        String base = switch (table) {
            case "journey_session", "journey_telemetry_event", "reroute_proposal",
                 "journey_notification" ->
                    "select count(*) from " + table + " where user_id = ? and deleted_at is null";
            case "journey_leg", "transfer_connection", "journey_event", "journey_revision",
                 "subway_leg_binding" -> """
                    select count(*) from %s
                     where journey_id in (select id from journey_session where user_id = ?)
                       and deleted_at is null""".formatted(table);
            case "journey_leg_route_option", "subway_binding_event" -> """
                    select count(*) from %s
                     where journey_leg_id in (
                         select id from journey_leg where journey_id in (
                             select id from journey_session where user_id = ?))
                       and deleted_at is null""".formatted(table);
            default -> throw new IllegalArgumentException("no live rule for " + table);
        };
        return jdbc.queryForObject(base, Integer.class, userId);
    }

    private long insertUser(long tossKey) {
        return jdbc.queryForObject("""
                insert into users (created_at, updated_at, toss_user_key, token_generation)
                values (now(), now(), ?, 0) returning id
                """, Long.class, tossKey);
    }

    /**
     * 전 기능의 데이터를 한 사용자에 심는다. FK 순서로 부모→자식.
     *
     * <p>컬럼명은 <b>엔티티 매핑 기준</b>이다(스키마를 Hibernate 가 만든다). 좌표는
     * {@code startx/starty/endx/endy} 로 매핑된다 — CamelCaseToUnderscores 가 끝자리 단일 대문자
     * ({@code startX})에 underscore 를 붙이지 않기 때문이다({@code RouteAlertSchedule} 주석 참조).
     * {@code "sequence"} 는 SQL 예약어라 따옴표로 감싼다.
     */
    private void seedAll(long uid) {
        long tossKey = jdbc.queryForObject(
                "select toss_user_key from users where id = ?", Long.class, uid);

        jdbc.update("""
                insert into auth_identity (created_at, updated_at, user_id, provider, provider_subject, linked_at)
                values (now(), now(), ?, 'TOSS', ?, now())""", uid, "sub-" + uid);
        jdbc.update("""
                insert into toss_user_token (created_at, updated_at, user_key, access_token, refresh_token)
                values (now(), now(), ?, 'acc', 'ref')""", tossKey);
        jdbc.update("""
                insert into apple_user_token (created_at, updated_at, user_id, refresh_token_enc, apple_sub)
                values (now(), now(), ?, ?, ?)""", uid, "enc-" + uid, "apple-sub-" + uid);

        long pushId = jdbc.queryForObject("""
                insert into user_push_token
                    (created_at, updated_at, user_id, platform, provider, token, environment, enabled, last_seen_at)
                values (now(), now(), ?, 'ANDROID', 'FCM', ?, 'PRODUCTION', true, now()) returning id
                """, Long.class, uid, "tok-" + uid);

        jdbc.update("""
                insert into route_search_history
                    (created_at, updated_at, user_id, startx, starty, endx, endy, searched_at)
                values (now(), now(), ?, 1, 2, 3, 4, now())""", uid);
        jdbc.update("""
                insert into route_search_log
                    (created_at, updated_at, user_id, startx, starty, endx, endy, searched_at)
                values (now(), now(), ?, 1, 2, 3, 4, now())""", uid);
        jdbc.update("""
                insert into favorite_route
                    (created_at, updated_at, user_id, type, name, address, end_place, ex, ey)
                values (now(), now(), ?, 'HOME', '집', '서울', '집', 3, 4)""", uid);

        // 알림 부모 + 자식
        long raAlertId = jdbc.queryForObject("""
                insert into route_arrival_alert
                    (created_at, updated_at, user_id, startx, starty, endx, endy,
                     target_arrival_at, user_lead_minutes, buffer_minutes, change_alert_count,
                     phase, status, expires_at, version)
                values (now(), now(), ?, 1, 2, 3, 4, now(), 10, 5, 0,
                        'WAITING_FIRST_CHECK', 'ACTIVE', now(), 0)
                returning id""", Long.class, uid);
        jdbc.update("""
                insert into route_arrival_alert_notification
                    (created_at, updated_at, alert_id, type, dedup_key, status, scheduled_at,
                     target_leave_by_at, attempt_count)
                values (now(), now(), ?, 'DEPARTURE', ?, 'PENDING', now(), now(), 0)""",
                raAlertId, "ded-r-" + uid);

        long baAlertId = jdbc.queryForObject("""
                insert into bus_arrival_alert
                    (created_at, updated_at, user_id, stop_id, route_id, ord, route_name, stop_name,
                     user_eta, bus_arrival_minutes, status, expires_at, attempt_count)
                values (now(), now(), ?, 's1', 'r1', 1, '노선', '정류장', now(), 3, 'PENDING', now(), 0)
                returning id""", Long.class, uid);
        jdbc.update("""
                insert into bus_alert_notification
                    (created_at, updated_at, alert_id, dedup_key, channel, event_type, status,
                     scheduled_at, attempt_count, requeue_count)
                values (now(), now(), ?, ?, 'TOSS', 'ARRIVAL', 'PENDING', now(), 0, 0)""",
                baAlertId, "ded-b-" + uid);

        jdbc.update("""
                insert into route_alert_schedule
                    (created_at, updated_at, user_id, startx, starty, endx, endy, arrival_time,
                     weekdays_mask, repeat, user_lead_minutes, buffer_minutes,
                     materialization_retry_count, status, version)
                values (now(), now(), ?, 1, 2, 3, 4, '08:00', 62, true, 10, 5, 0, 'ACTIVE', 0)""", uid);

        // 여정 부모 + 자식
        // 재탐색 알림 상태 3칸을 손으로 채운다(reroute_proposal.board_version 도 같은 이유, V57). 이 IT 의 스키마는 마이그레이션이 아니라
        // ddl-auto 가 만들고, Hibernate 는 원시타입 필드를 <b>기본값 없는</b> not null 로 낸다 —
        // V53 이 default 를 주더라도 여기서는 안 온다.
        long jid = jdbc.queryForObject("""
                insert into journey_session
                    (created_at, updated_at, user_id, status, command_version, evaluation_version,
                     route_revision, current_leg_index, started_at, last_heartbeat_at,
                     guidance_lease_until, route_snapshot,
                     reroute_terminated, reroute_alert_opt_in, reroute_ignored_count)
                values (now(), now(), ?, 'ACTIVE', 0, 0, 1, 0, now(), now(), now(), '{}'::jsonb,
                        false, false, 0)
                returning id""", Long.class, uid);
        long lid = jdbc.queryForObject("""
                insert into journey_leg
                    (created_at, updated_at, journey_id, route_revision, leg_index, type, state)
                values (now(), now(), ?, 1, 0, 'WALK', 'PENDING') returning id""", Long.class, jid);
        jdbc.update("""
                insert into journey_leg_route_option
                    (created_at, updated_at, journey_leg_id, option_index, lane_index)
                values (now(), now(), ?, 0, 0)""", lid);
        jdbc.update("""
                insert into transfer_connection
                    (created_at, updated_at, journey_id, route_revision, transfer_index,
                     from_leg_index, to_leg_index, status)
                values (now(), now(), ?, 1, 0, 0, 1, 'COMFORTABLE')""", jid);
        jdbc.update("""
                insert into journey_event
                    (created_at, updated_at, event_id, journey_id, base_command_version,
                     route_revision, type, server_received_at, result)
                values (now(), now(), gen_random_uuid(), ?, 1, 1, 'START', now(), 'APPLIED')""", jid);
        jdbc.update("""
                insert into journey_revision
                    (created_at, updated_at, journey_id, route_revision, status, route_snapshot, activated_at)
                values (now(), now(), ?, 1, 'ACTIVE', '{}'::jsonb, now())""", jid);
        jdbc.update("""
                insert into journey_telemetry_event
                    (created_at, updated_at, telemetry_id, user_id, journey_id, event_type,
                     occurred_at, received_at)
                values (now(), now(), ?, ?, ?, 'PING', now(), now())""", "tel-" + uid, uid, jid);
        jdbc.update("""
                insert into reroute_proposal
                    (created_at, updated_at, journey_id, user_id, route_revision,
                     kind, status, base_command_version, candidates, snapshot_observed_at,
                     expires_at, alerted, board_version, push_seq)
                values (now(), now(), ?, ?, 1, 'REMAINING_PATH', 'ACTIVE', 1, '[]'::jsonb, now(),
                        now(), false, 0, 0)""",
                jid, uid);
        jdbc.update("""
                insert into subway_binding_event
                    (created_at, updated_at, journey_leg_id, journey_id, route_revision, leg_index,
                     kind, event_type, reset_generation, occurred_at, received_at)
                values (now(), now(), ?, ?, 1, 0, 'EVIDENCE', 'MOVEMENT_START', 0, now(), now())""", lid, jid);
        jdbc.update("""
                insert into subway_ring_push_outbox
                    (created_at, updated_at, journey_id, route_revision, leg_index, binding_version,
                     "sequence", aps_timestamp, signature, critical, reason, event, payload, status,
                     attempt_count, expires_at)
                values (now(), now(), ?, 1, 0, 0, 1, 1, 'sig', false, 'r', 'update', '{}'::jsonb,
                        'PENDING', 0, now())""", jid);
        jdbc.update("""
                insert into subway_live_activity_token
                    (created_at, updated_at, user_id, journey_id, activity_token, push_token_id,
                     environment, enabled, last_seen_at)
                values (now(), now(), ?, ?, ?, ?, 'PRODUCTION', true, now())""",
                uid, jid, "act-" + uid, pushId);

        // 손대지 않음(V40·V44)
        jdbc.update("""
                insert into journey_notification
                    (created_at, updated_at, journey_id, user_id, route_revision, command_version,
                     evaluation_version, scope_type, scope_id, checkpoint, channel,
                     logical_checkpoint_key, delivery_key, status, scheduled_at, expires_at,
                     attempt_count, requeue_count)
                values (now(), now(), ?, ?, 1, 1, 0, 'TRANSFER', 0, 'TRANSFER_RISK_AT_RISK', 'TOSS', ?, ?,
                        'PENDING', now(), now(), 0, 0)""",
                jid, uid, "lck-" + uid, "dk-" + uid);
        jdbc.update("""
                insert into subway_leg_binding
                    (created_at, updated_at, journey_leg_id, journey_id, route_revision, leg_index,
                     status, version, reset_generation)
                values (now(), now(), ?, ?, 1, 0, 'PENDING', 0, 0)""", lid, jid);

        this.journeyId = jid;
        this.legId = lid;
    }
}
