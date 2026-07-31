package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.exception.ActiveJourneyExistsException;
import watoo.grd.nextroute.application.journey.exception.JourneyCompileFailedException;
import watoo.grd.nextroute.application.journey.exception.SnapshotOwnerMismatchException;
import watoo.grd.nextroute.application.journey.exception.SnapshotUnavailableException;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static watoo.grd.nextroute.application.journey.service.SubPathFixture.bus;
import static watoo.grd.nextroute.application.journey.service.SubPathFixture.busLane;
import static watoo.grd.nextroute.application.journey.service.SubPathFixture.walk;

/**
 * 시작 전에 걸러야 하는 것들과, 잠금을 뚫고 들어온 충돌을 어떻게 번역하는지를 고정한다.
 *
 * <p>저장 자체는 실제 DB 가 있어야 확인할 수 있어 {@code JourneyStartIT} 가 맡는다. 여기서는
 * 트랜잭션 밖 판단만 본다.
 */
class JourneyStartServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-31T09:00:00Z");
    private static final Long USER_ID = 7L;

    private RouteSearchSnapshotPort snapshotPort;
    private JourneyStartTxService txService;
    private JourneyQueryService queryService;
    private JourneyPlanResolver planResolver;
    private JourneyStartService service;

    @BeforeEach
    void setUp() {
        snapshotPort = mock(RouteSearchSnapshotPort.class);
        txService = mock(JourneyStartTxService.class);
        queryService = mock(JourneyQueryService.class);
        planResolver = mock(JourneyPlanResolver.class);
        given(planResolver.resolve(any())).willAnswer(returnsFirstArg());
        service = new JourneyStartService(
                snapshotPort,
                new JourneyCompiler(new StopIdentityResolver()),
                planResolver,
                txService,
                queryService,
                new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private StartJourneyCommand command() {
        return new StartJourneyCommand("sess-1", "0", false);
    }

    private PathResult usablePath() {
        return new PathResult(1,
                new PathInfo(30, 1500, 500, 0, "정류장A", "정류장F", null),
                List.of(
                        walk(5).names("집", "정류장A").build(),
                        bus(20).names("정류장A", "정류장F")
                                .busStops("ST-A", "ST-F")
                                .lanes(busLane("370", "BL-370")).build()),
                List.of());
    }

    private JourneySnapshot snapshot(Long journeyId) {
        return new JourneySnapshot(journeyId, JourneyStatus.ACTIVE, 1L, 0L, 1, 0,
                NOW.plusSeconds(180), NOW, List.of());
    }

    private void givenStoredPath(Long ownerUserId, PathResult path) {
        given(snapshotPort.find("sess-1", "0")).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(ownerUserId, path, NOW.plusSeconds(600))));
    }

    // ── 시작 전 검사 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("스냅샷이 없으면 만료로 거절하고 저장을 시도하지 않는다")
    void missingSnapshot() {
        given(snapshotPort.find("sess-1", "0")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.start(USER_ID, command()))
                .isInstanceOf(SnapshotUnavailableException.class);
        verify(txService, never()).start(anyLong(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("남의 검색 결과면 거절한다")
    void otherUsersSnapshot() {
        givenStoredPath(99L, usablePath());

        assertThatThrownBy(() -> service.start(USER_ID, command()))
                .isInstanceOf(SnapshotOwnerMismatchException.class);
        verify(txService, never()).start(anyLong(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("로그인 없이 검색한 결과로는 시작할 수 있다")
    void anonymousSnapshot_allowed() {
        givenStoredPath(null, usablePath());
        given(txService.start(eq(USER_ID), any(), any(), any(), eq(false), eq(NOW)))
                .willReturn(snapshot(1L));

        assertThat(service.start(USER_ID, command()).journeyId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("쪼갤 구간이 없으면 만료와 구분해 거절한다")
    void emptyPath_isNotExpiry() {
        givenStoredPath(USER_ID, new PathResult(1, null, List.of(), List.of()));

        assertThatThrownBy(() -> service.start(USER_ID, command()))
                .isInstanceOf(JourneyCompileFailedException.class);
    }

    @Test
    @DisplayName("경로 원본과 목적지 이름을 함께 넘긴다")
    void passesSnapshotAndDestination() {
        givenStoredPath(USER_ID, usablePath());
        given(txService.start(anyLong(), any(), any(), any(), anyBoolean(), any()))
                .willReturn(snapshot(1L));

        service.start(USER_ID, command());

        verify(txService).start(eq(USER_ID), any(),
                org.mockito.ArgumentMatchers.argThat(node -> node.get("pathType").asInt() == 1),
                org.mockito.ArgumentMatchers.argThat(node -> "정류장F".equals(node.get("name").asText())),
                eq(false), eq(NOW));
    }

    // ── 충돌 번역 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("사람당 하나 인덱스 위반은 409 로 바꾸고 현재 여정을 실어 준다")
    void uniqueViolation_becomesConflict() {
        givenStoredPath(USER_ID, usablePath());
        given(txService.start(anyLong(), any(), any(), any(), anyBoolean(), any()))
                .willThrow(activeJourneyViolation());
        given(queryService.getActive(USER_ID)).willReturn(Optional.of(snapshot(42L)));

        assertThatThrownBy(() -> service.start(USER_ID, command()))
                .isInstanceOf(ActiveJourneyExistsException.class)
                .satisfies(e -> assertThat(((ActiveJourneyExistsException) e).getCurrent().journeyId())
                        .isEqualTo(42L));
    }

    @Test
    @DisplayName("다른 무결성 오류는 409 로 숨기지 않는다")
    void otherIntegrityError_notTranslated() {
        givenStoredPath(USER_ID, usablePath());
        DataIntegrityViolationException original = violation("fk_journey_leg_journey", "23503");
        given(txService.start(anyLong(), any(), any(), any(), anyBoolean(), any()))
                .willThrow(original);

        assertThatThrownBy(() -> service.start(USER_ID, command()))
                .as("FK 위반이 '이미 여정이 있습니다'로 둔갑하면 진짜 버그가 묻힌다")
                .isSameAs(original);
        verify(queryService, never()).getActive(anyLong());
    }

    @Test
    @DisplayName("충돌 상대가 그새 끝났으면 원래 오류를 그대로 올린다")
    void conflictGone_rethrows() {
        givenStoredPath(USER_ID, usablePath());
        DataIntegrityViolationException original = activeJourneyViolation();
        given(txService.start(anyLong(), any(), any(), any(), anyBoolean(), any()))
                .willThrow(original);
        given(queryService.getActive(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.start(USER_ID, command())).isSameAs(original);
    }

    private DataIntegrityViolationException activeJourneyViolation() {
        return violation("uq_journey_active_per_user", "23505");
    }

    private DataIntegrityViolationException violation(String constraint, String sqlState) {
        ConstraintViolationException cause = new ConstraintViolationException(
                "violation", new SQLException("violation", sqlState), constraint);
        return new DataIntegrityViolationException("could not execute statement", cause);
    }

    /** 컴파일러가 실제로 쓰이는지 확인하는 최소 경로. */
    @Test
    @DisplayName("쪼갠 결과를 트랜잭션으로 넘긴다")
    void passesCompiledPlan() {
        givenStoredPath(USER_ID, usablePath());
        given(txService.start(anyLong(), any(), any(), any(), anyBoolean(), any()))
                .willReturn(snapshot(1L));

        service.start(USER_ID, command());

        verify(txService).start(eq(USER_ID),
                org.mockito.ArgumentMatchers.argThat(plan -> plan.legs().size() == 3),
                any(), any(), eq(false), eq(NOW));
    }
}
