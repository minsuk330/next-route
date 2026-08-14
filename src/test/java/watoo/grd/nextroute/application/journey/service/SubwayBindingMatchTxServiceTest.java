package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.journey.config.JourneySubwayBindingProperties;
import watoo.grd.nextroute.application.journey.dto.SubwayBindingCandidate;
import watoo.grd.nextroute.application.journey.dto.SubwayBindingDecision;
import watoo.grd.nextroute.application.subway.dto.SubwayExpressType;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainEvent;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingConfidence;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingSource;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingStatus;
import watoo.grd.nextroute.domain.journey.entity.SubwayLegBinding;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.journey.repository.SubwayBindingEventRepository;
import watoo.grd.nextroute.domain.journey.repository.SubwayLegBindingRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 판정 반영 트랜잭션의 계약.
 *
 * <p>여기서 지키는 것은 <b>순서</b>다. 세션 확인 → 행 잠금 → CAS → 감사행. 한 걸음이라도 빠지거나
 * 뒤집히면 끝난 여정에 새로 확정되거나, 반영되지 않은 판단이 기록으로 남는다.
 */
class SubwayBindingMatchTxServiceTest {

    private static final long LEG_ID = 101L;
    private static final long JOURNEY_ID = 7L;
    private static final int REVISION = 1;
    private static final int LEG_INDEX = 3;
    private static final Instant NOW = Instant.parse("2026-08-14T09:00:00Z");

    private JourneySessionRepository sessionRepository;
    private SubwayLegBindingRepository bindingRepository;
    private SubwayBindingEventRepository eventRepository;
    private SubwayBindingMatchTxService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(JourneySessionRepository.class);
        bindingRepository = mock(SubwayLegBindingRepository.class);
        eventRepository = mock(SubwayBindingEventRepository.class);
        service = new SubwayBindingMatchTxService(sessionRepository, bindingRepository,
                eventRepository, new JourneySubwayBindingProperties(), new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));

        when(sessionRepository.lockActiveRevisionForShare(JOURNEY_ID, REVISION))
                .thenReturn(List.of(JOURNEY_ID));
        when(bindingRepository.findForMatch(LEG_ID))
                .thenReturn(Optional.of(binding(3, 0, SubwayBindingStatus.PENDING)));
    }

    @Test
    @DisplayName("여정이 끝났거나 개정본이 올랐으면 아무것도 하지 않는다")
    void skipsWhenSessionIsNoLongerMatchable() {
        when(sessionRepository.lockActiveRevisionForShare(JOURNEY_ID, REVISION))
                .thenReturn(List.of());

        assertThat(service.apply(binding(3, 0, SubwayBindingStatus.PENDING), provisional()))
                .isFalse();
        verify(bindingRepository, never()).casBind(any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("누가 그 행을 잡고 있으면 기다리지 않고 건너뛴다")
    void skipsWhenTheRowIsLocked() {
        when(bindingRepository.findForMatch(LEG_ID)).thenReturn(Optional.empty());

        assertThat(service.apply(binding(3, 0, SubwayBindingStatus.PENDING), provisional()))
                .isFalse();
        verify(bindingRepository, never()).casBind(any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("CAS 가 0행이면 감사행도 쓰지 않는다 — 반영 안 된 판단이 사실처럼 남으면 안 된다")
    void doesNotWriteAuditWhenCasLoses() {
        givenCasResult(0);

        assertThat(service.apply(binding(3, 0, SubwayBindingStatus.PENDING), provisional()))
                .isFalse();
        verifyNoAudit();
    }

    @Test
    @DisplayName("CAS 조건은 재료를 모으기 전에 읽은 값이다 — 순번까지 함께 본다")
    void casUsesTheSnapshotTakenBeforeGatheringMaterials() {
        givenCasResult(1);
        SubwayLegBinding expected = binding(3, 2, SubwayBindingStatus.PENDING);
        ReflectionTestUtils.setField(expected, "lastMovementSeq", 5);
        ReflectionTestUtils.setField(expected, "lastEpisodeSeq", 1);

        service.apply(expected, provisional());

        verify(bindingRepository).casBind(eq(LEG_ID), eq("PROVISIONAL"), eq("1001"),
                any(LocalDate.class), eq("1001"), eq("상행"),
                eq(3), eq(2), eq(5), eq(1), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("감사행의 binding_version 은 expectedVersion + 1 이다 — 아니면 후보 복구가 즉시 무효다")
    void auditCarriesThePostCasVersion() {
        givenCasResult(1);

        service.apply(binding(3, 0, SubwayBindingStatus.PENDING), provisional());

        ArgumentCaptor<Integer> version = ArgumentCaptor.forClass(Integer.class);
        verify(eventRepository).insertIgnoringDuplicate(anyLong(), anyLong(), anyInt(), anyInt(),
                eq("DECISION"), eq("PROVISIONAL"), any(), any(), version.capture(), anyInt(),
                any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(version.getValue()).isEqualTo(4);
    }

    @Test
    @DisplayName("교정도 지속 상태는 CONFIRMED 다 — 감사행에만 CORRECTED 가 남는다")
    void correctionEndsAsConfirmed() {
        givenCasResult(1);

        service.apply(binding(3, 0, SubwayBindingStatus.PROVISIONAL),
                SubwayBindingDecision.corrected(candidate("2002"), List.of(candidate("2002")),
                        "episode-correction"));

        verify(bindingRepository).casBind(eq(LEG_ID), eq("CONFIRMED"), eq("2002"),
                any(), any(), any(), anyInt(), anyInt(), any(), any(), any());
        verify(eventRepository).insertIgnoringDuplicate(anyLong(), anyLong(), anyInt(), anyInt(),
                eq("DECISION"), eq("CORRECTED"), any(), any(), any(), anyInt(),
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("접을 때는 열차 identity 를 지우지 않는다 — 무엇을 보고 있었는지가 분석 재료다")
    void abandonKeepsTheTrainIdentity() {
        when(bindingRepository.casAbandon(any(), anyInt(), anyInt(), any(), any(), any()))
                .thenReturn(1);

        service.apply(binding(3, 0, SubwayBindingStatus.PROVISIONAL),
                SubwayBindingDecision.abandon("match-window-expired"));

        verify(bindingRepository).casAbandon(eq(LEG_ID), eq(3), eq(0), any(), any(),
                any(LocalDateTime.class));
        verify(bindingRepository, never()).casBind(any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt(), any(), any(), any());
    }

    @Test
    @DisplayName("후보 목록을 감사행에 통째로 남긴다 — 이게 교정의 원본이다")
    void auditCarriesTheCandidateList() {
        givenCasResult(1);

        service.apply(binding(3, 0, SubwayBindingStatus.PENDING), provisional());

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(eventRepository).insertIgnoringDuplicate(anyLong(), anyLong(), anyInt(), anyInt(),
                any(), any(), any(), any(), any(), anyInt(), any(), any(), any(), any(), any(),
                any(), detail.capture(), any());
        assertThat(detail.getValue()).contains("\"trainNo\":\"1001\"")
                .contains("\"candidates\"");
    }

    // ── 재료 ────────────────────────────────────────────────────────────────

    private void givenCasResult(int rows) {
        when(bindingRepository.casBind(any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt(), any(), any(), any())).thenReturn(rows);
    }

    private void verifyNoAudit() {
        verify(eventRepository, never()).insertIgnoringDuplicate(anyLong(), anyLong(), anyInt(),
                anyInt(), any(), any(), any(), any(), any(), anyInt(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    private SubwayLegBinding binding(int version, int resetGeneration, SubwayBindingStatus status) {
        SubwayLegBinding binding = SubwayLegBinding.pending(LEG_ID, JOURNEY_ID, REVISION,
                LEG_INDEX, NOW.minusSeconds(60), SubwayBindingSource.MOTION_AUTOMOTIVE,
                SubwayBindingConfidence.HIGH);
        ReflectionTestUtils.setField(binding, "version", version);
        ReflectionTestUtils.setField(binding, "resetGeneration", resetGeneration);
        ReflectionTestUtils.setField(binding, "status", status);
        return binding;
    }

    private SubwayBindingDecision provisional() {
        return SubwayBindingDecision.provisional(candidate("1001"),
                List.of(candidate("1001"), candidate("2002")), "t0-match");
    }

    private SubwayBindingCandidate candidate(String trainNo) {
        return new SubwayBindingCandidate(trainNo, LocalDate.of(2026, 8, 14), "1001", "상행",
                NOW.minusSeconds(60), NOW.minusSeconds(50), SubwayTrainEvent.Quality.OBSERVED,
                null, SubwayExpressType.NORMAL, 0);
    }
}
