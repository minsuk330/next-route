package watoo.grd.nextroute.domain.journey;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import watoo.grd.nextroute.common.config.JpaConfig;
import watoo.grd.nextroute.domain.journey.entity.JourneyKind;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.testsupport.AbstractJourneyOutboxPostgresIT;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 버스 차량 칼럼(V61, #265 PR-4)은 worker 의 부분 UPDATE 만 쓴다.
 *
 * <p>{@code JourneyLeg} 에 {@code @DynamicUpdate} 가 없어 엔티티 저장은 모든 칼럼을 다시 쓴다 — 차량 칼럼이
 * {@code updatable = false} 가 아니면 api 의 진행·명령 저장이 worker 가 쓴 차량을 낡은 값(null)으로 덮는다. 판정이 끝난 행은
 * 다시 판정하지 않는다(재전송·중복 tick 방어).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(JpaConfig.class)
@Sql(scripts = {
        "classpath:db/it/journey-reset.sql",
        "classpath:db/migration/V35__create_journey.sql",
        "classpath:db/migration/V36__add_journey_transit_resolution.sql",
        "classpath:db/migration/V37__align_journey_navigation_contract.sql",
        "classpath:db/migration/V38__add_journey_last_progress_id.sql",
        "classpath:db/migration/V53__add_reroute_alert_state.sql",
        "classpath:db/migration/V59__journey_session_kind.sql",
        "classpath:db/migration/V61__journey_leg_vehicle.sql"
},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
@DisplayName("버스 차량 칼럼 부분 갱신")
class JourneyLegVehicleIT extends AbstractJourneyOutboxPostgresIT {

    private static final Instant NOW = Instant.parse("2026-10-07T03:00:00Z");

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JourneySessionRepository sessionRepository;
    @Autowired JourneyLegRepository legRepository;

    private TransactionTemplate tx;
    private Long legId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        jdbcTemplate.update("delete from journey_leg");
        jdbcTemplate.update("delete from journey_session");
        jdbcTemplate.update("delete from users");
        long userId = jdbcTemplate.queryForObject(
                "insert into users (created_at, updated_at) values (now(), now()) returning id", Long.class);
        JourneySession session = sessionRepository.saveAndFlush(JourneySession.builder().userId(userId)
                .kind(JourneyKind.TRACK).startedAt(NOW).guidanceLeaseUntil(NOW.plusSeconds(180))
                .routeSnapshot(new ObjectMapper().createObjectNode()).build());
        legId = legRepository.saveAndFlush(JourneyLeg.builder().journeyId(session.getId()).routeRevision(1)
                .legIndex(1).type(JourneyLegType.RIDE_BUS).state(JourneyLegState.PENDING).build()).getId();
    }

    @Test
    @DisplayName("차량을 쓴 뒤 엔티티 저장(진행·전이)이 차량 칼럼을 지우지 않는다")
    void entitySaveKeepsVehicle() {
        JourneyLeg stale = legRepository.findById(legId).orElseThrow();   // 차량 쓰기 전에 읽어 둔 엔티티

        tx.execute(s -> legRepository.assignVehicle(legId, "대전75자9880", "PLAIN_NO", "AUTO_MATCH", NOW));
        tx.execute(s -> {
            stale.changeState(JourneyLegState.ACTIVE, NOW);
            return legRepository.saveAndFlush(stale);
        });

        Map<String, Object> row = row();
        assertThat(row.get("state")).isEqualTo("ACTIVE");
        assertThat(row.get("vehicle_id")).isEqualTo("대전75자9880");
        assertThat(row.get("vehicle_id_type")).isEqualTo("PLAIN_NO");
        assertThat(row.get("vehicle_source")).isEqualTo("AUTO_MATCH");
        assertThat(row.get("vehicle_matched_at")).isNotNull();
        assertThat(legRepository.findById(legId).orElseThrow().getVehicleId()).isEqualTo("대전75자9880");
    }

    @Test
    @DisplayName("판정이 끝난 행은 다시 고정·포기되지 않는다(재전송·중복 tick)")
    void finishedIsFinal() {
        assertThat(write(() -> legRepository.assignVehicle(legId, "A", "VEH_ID", "AUTO_MATCH", NOW))).isEqualTo(1);
        assertThat(write(() -> legRepository.assignVehicle(legId, "B", "VEH_ID", "AUTO_MATCH", NOW))).isZero();
        assertThat(write(() -> legRepository.finishVehicleUnmatched(legId, NOW))).isZero();
        assertThat(row().get("vehicle_id")).isEqualTo("A");
    }

    @Test
    @DisplayName("포기는 차량 없이 판정만 끝낸다")
    void unmatched() {
        assertThat(write(() -> legRepository.finishVehicleUnmatched(legId, NOW))).isEqualTo(1);

        assertThat(row().get("vehicle_id")).isNull();
        assertThat(row().get("vehicle_matched_at")).isNotNull();
        assertThat(write(() -> legRepository.assignVehicle(legId, "A", "VEH_ID", "AUTO_MATCH", NOW))).isZero();
    }

    @Test
    @DisplayName("사용자 해제는 차량을 지우고 판정은 끝난 채로 둔다")
    void reset() {
        tx.execute(s -> legRepository.assignVehicle(legId, "A", "VEH_ID", "AUTO_MATCH", NOW));

        tx.execute(s -> legRepository.resetVehicle(legId, NOW.plusSeconds(60)));

        Map<String, Object> row = row();
        assertThat(row.get("vehicle_id")).isNull();
        assertThat(row.get("vehicle_id_type")).isNull();
        assertThat(row.get("vehicle_source")).isNull();
        assertThat(row.get("vehicle_matched_at")).isNotNull();
    }

    @Test
    @DisplayName("차량 id·종류·출처는 함께 있거나 함께 없다, 값 목록 밖은 거절")
    void constraints() {
        assertThatThrownBy(() -> jdbcTemplate.update("update journey_leg set vehicle_id = 'A' where id = ?", legId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                update journey_leg set vehicle_id = 'A', vehicle_id_type = 'FOO', vehicle_source = 'AUTO_MATCH'
                 where id = ?""", legId)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private int write(java.util.function.IntSupplier update) {
        Integer rows = tx.execute(s -> update.getAsInt());
        return rows == null ? 0 : rows;
    }

    private Map<String, Object> row() {
        return jdbcTemplate.queryForMap("select * from journey_leg where id = ?", legId);
    }
}
