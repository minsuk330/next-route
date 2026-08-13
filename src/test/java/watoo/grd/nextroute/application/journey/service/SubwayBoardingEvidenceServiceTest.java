package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.journey.config.JourneySubwayBindingProperties;
import watoo.grd.nextroute.application.journey.dto.SubwayBindingView;
import watoo.grd.nextroute.application.journey.dto.SubwayBoardingEvidence;
import watoo.grd.nextroute.application.journey.exception.StaleRouteRevisionException;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingConfidence;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingEventType;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingSource;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingStatus;
import watoo.grd.nextroute.domain.journey.entity.SubwayLegBinding;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.journey.repository.SubwayBindingEventRepository;
import watoo.grd.nextroute.domain.journey.repository.SubwayLegBindingRepository;
import watoo.grd.nextroute.global.error.BusinessException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 탑승 관측 수집과 사용자 보정의 계약.
 *
 * <p>가장 비싼 실패는 <b>엉뚱한 열차에 고정되는 것</b>이다. 사용자가 다른 역에서 내린다.
 * 그래서 여기 있는 시험 대부분은 "무엇을 하지 <b>않는가</b>"를 고정한다.
 */
class SubwayBoardingEvidenceServiceTest {

    private static final long USER_ID = 42L;
    private static final long JOURNEY_ID = 7L;
    private static final int REVISION = 1;
    private static final int WAIT_INDEX = 2;
    private static final int RIDE_INDEX = 3;

    private static final Instant NOW = Instant.parse("2026-08-13T09:00:00Z");

    private JourneySessionRepository sessionRepository;
    private JourneyLegRepository legRepository;
    private FakeBindingRepository bindingRepository;
    private SubwayBindingEventRepository eventRepository;
    private SubwayBoardingEvidenceService service;

    private long insertedRows;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(JourneySessionRepository.class);
        legRepository = mock(JourneyLegRepository.class);
        bindingRepository = new FakeBindingRepository();
        eventRepository = mock(SubwayBindingEventRepository.class);
        insertedRows = 0;

        // 기본은 "처음 보는 관측". 중복 시험에서만 빈 목록으로 바꾼다.
        when(eventRepository.insertIgnoringDuplicate(anyLong(), anyLong(), anyInt(), anyInt(),
                any(), any(), any(), any(), any(), anyInt(), any(), any(),
                any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> List.of(++insertedRows));

        service = new SubwayBoardingEvidenceService(sessionRepository, legRepository,
                bindingRepository, eventRepository, new JourneySubwayBindingProperties(),
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));

        givenActiveJourney();
        givenSubwayPair();
    }

    // ── 소유권·개정본 ────────────────────────────────────────────────────

    @Test
    @DisplayName("남의 여정이면 받지 않는다")
    void rejectsOtherUsersJourney() {
        when(sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(JOURNEY_ID, 99L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> submit(99L, REVISION, RIDE_INDEX, movementStart(1)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("끝난 여정에는 관측을 얹지 않는다")
    void rejectsFinishedJourney() {
        JourneySession journey = session();
        ReflectionTestUtils.setField(journey, "status", JourneyStatus.COMPLETED);
        when(sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(JOURNEY_ID, USER_ID))
                .thenReturn(Optional.of(journey));

        assertThatThrownBy(() -> submit(USER_ID, REVISION, RIDE_INDEX, movementStart(1)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("재탐색으로 경로가 바뀐 뒤 늦게 온 관측은 거절한다")
    void rejectsStaleRevision() {
        assertThatThrownBy(() -> submit(USER_ID, REVISION + 1, RIDE_INDEX, movementStart(1)))
                .isInstanceOf(StaleRouteRevisionException.class);
    }

    // ── 단계 정규화 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("대기 단계로 보내도 짝이 되는 타는 단계에 적는다")
    void normalizesWaitLegToItsRideLeg() {
        SubwayBindingView view = submit(USER_ID, REVISION, WAIT_INDEX, movementStart(1));

        assertThat(view.legIndex()).isEqualTo(RIDE_INDEX);
        assertThat(bindingRepository.saved).hasSize(1);
        assertThat(bindingRepository.saved.get(0).getLegIndex()).isEqualTo(RIDE_INDEX);
    }

    @Test
    @DisplayName("지하철이 아닌 단계는 받지 않는다")
    void rejectsNonSubwayLeg() {
        assertThatThrownBy(() -> submit(USER_ID, REVISION, 0, movementStart(1)))
                .isInstanceOf(BusinessException.class);
    }

    // ── 관측 수신 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("첫 이동 시작은 아직 열차를 고르지 않은 상태로 연다")
    void firstMovementOpensPendingBinding() {
        SubwayBindingView view = submit(USER_ID, REVISION, RIDE_INDEX, movementStart(1));

        assertThat(view.status()).isEqualTo(SubwayBindingStatus.PENDING);
        SubwayLegBinding binding = bindingRepository.saved.get(0);
        assertThat(binding.getTrainNo()).isNull();
        assertThat(binding.getBoardedDetectedAt()).isEqualTo(NOW.minusSeconds(20));
        assertThat(binding.getSource()).isEqualTo(SubwayBindingSource.MOTION_AUTOMOTIVE);
    }

    @Test
    @DisplayName("차량 이동으로 단정되지 않은 관측은 근거를 갈라 둔다")
    void unknownActivityIsRecordedSeparately() {
        submit(USER_ID, REVISION, RIDE_INDEX, new SubwayBoardingEvidence(
                SubwayBindingEventType.MOVEMENT_START, 1, NOW.minusSeconds(20), null, null,
                "STATIONARY", "UNKNOWN_MOVING", SubwayBindingConfidence.LOW, 12, true,
                NOW.minusSeconds(5)));

        assertThat(bindingRepository.saved.get(0).getSource())
                .isEqualTo(SubwayBindingSource.MOTION_UNKNOWN);
    }

    @Test
    @DisplayName("이동 시작을 놓치고 정차부터 와도 받아 둔다")
    void acceptsEpisodeWithoutPrecedingMovement() {
        SubwayBindingView view = submit(USER_ID, REVISION, RIDE_INDEX, episode(1));

        assertThat(view.status()).isEqualTo(SubwayBindingStatus.PENDING);
        // 감지 시각과 근거는 이동 시작에서만 온다. 정차만으로 지어내지 않는다.
        assertThat(bindingRepository.saved.get(0).getBoardedDetectedAt()).isNull();
        assertThat(bindingRepository.saved.get(0).getSource()).isNull();
    }

    @Test
    @DisplayName("두 번째 이동 시작이 이미 진행된 판정을 되돌리지 않는다")
    void secondMovementDoesNotResetProgress() {
        submit(USER_ID, REVISION, RIDE_INDEX, movementStart(1));
        SubwayLegBinding binding = bindingRepository.saved.get(0);
        ReflectionTestUtils.setField(binding, "status", SubwayBindingStatus.PROVISIONAL);

        SubwayBindingView view = submit(USER_ID, REVISION, RIDE_INDEX, movementStart(2));

        assertThat(view.status()).isEqualTo(SubwayBindingStatus.PROVISIONAL);
        assertThat(bindingRepository.saved).hasSize(1);
    }

    @Test
    @DisplayName("같은 관측을 다시 보내도 순번이 앞으로만 간다")
    void duplicateEvidenceDoesNotMoveTheSequence() {
        submit(USER_ID, REVISION, RIDE_INDEX, movementStart(3));
        // 저장소가 중복을 흡수한 상황
        when(eventRepository.insertIgnoringDuplicate(anyLong(), anyLong(), anyInt(), anyInt(),
                any(), any(), any(), any(), any(), anyInt(), any(), any(),
                any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        submit(USER_ID, REVISION, RIDE_INDEX, movementStart(2));

        assertThat(bindingRepository.saved.get(0).getLastMovementSeq()).isEqualTo(3);
    }

    @Test
    @DisplayName("이동 시작과 정차는 순번 칸을 나눠 쓴다 — 서로를 덮지 않는다")
    void movementAndEpisodeKeepSeparateSequences() {
        submit(USER_ID, REVISION, RIDE_INDEX, movementStart(1));
        submit(USER_ID, REVISION, RIDE_INDEX, episode(1));

        SubwayLegBinding binding = bindingRepository.saved.get(0);
        assertThat(binding.getLastMovementSeq()).isEqualTo(1);
        assertThat(binding.getLastEpisodeSeq()).isEqualTo(1);
    }

    @Test
    @DisplayName("타지 않았다고 한 뒤 늦게 온 관측이 그 결정을 뒤집지 않는다")
    void evidenceAfterAbandonIsIgnored() {
        submit(USER_ID, REVISION, RIDE_INDEX, movementStart(1));
        service.notBoarded(USER_ID, JOURNEY_ID, REVISION, RIDE_INDEX);

        SubwayBindingView view = submit(USER_ID, REVISION, RIDE_INDEX, movementStart(2));

        assertThat(view.status()).isEqualTo(SubwayBindingStatus.ABANDONED);
    }

    // ── 사용자 보정 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("다른 열차를 탔다고 하면 보정 세대가 올라간다 — 거부한 열차를 다시 고르지 않게")
    void resetAdvancesTheCorrectionGeneration() {
        submit(USER_ID, REVISION, RIDE_INDEX, movementStart(1));
        SubwayLegBinding binding = bindingRepository.saved.get(0);
        int before = binding.getResetGeneration();

        SubwayBindingView view = service.reset(USER_ID, JOURNEY_ID, REVISION, RIDE_INDEX);

        assertThat(view.status()).isEqualTo(SubwayBindingStatus.PENDING);
        assertThat(binding.getResetGeneration()).isEqualTo(before + 1);
        assertThat(binding.getVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("보정해도 행을 지우지 않는다 — 지우면 잠금 값이 0 으로 돌아간다")
    void resetKeepsTheRow() {
        submit(USER_ID, REVISION, RIDE_INDEX, movementStart(1));
        service.reset(USER_ID, JOURNEY_ID, REVISION, RIDE_INDEX);

        assertThat(bindingRepository.byLegId).hasSize(1);
        assertThat(bindingRepository.deleted).isEmpty();
    }

    @Test
    @DisplayName("보정하면 새 관측이 오기 전까지 다시 고르지 않는다")
    void resetClearsObservedSequences() {
        submit(USER_ID, REVISION, RIDE_INDEX, movementStart(1));
        service.reset(USER_ID, JOURNEY_ID, REVISION, RIDE_INDEX);

        SubwayLegBinding binding = bindingRepository.saved.get(0);
        assertThat(binding.getLastMovementSeq()).isNull();
        assertThat(binding.getLastEpisodeSeq()).isNull();
    }

    @Test
    @DisplayName("고정이 없는 단계를 보정하려 하면 404 다")
    void correctingWithoutBindingFails() {
        assertThatThrownBy(() -> service.reset(USER_ID, JOURNEY_ID, REVISION, RIDE_INDEX))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("보정도 개정본이 어긋나면 거절한다")
    void correctionIsRevisionFenced() {
        submit(USER_ID, REVISION, RIDE_INDEX, movementStart(1));

        assertThatThrownBy(() -> service.reset(USER_ID, JOURNEY_ID, REVISION + 1, RIDE_INDEX))
                .isInstanceOf(StaleRouteRevisionException.class);
    }

    // ── 도우미 ──────────────────────────────────────────────────────────

    private SubwayBindingView submit(long userId, int revision, int legIndex,
                                     SubwayBoardingEvidence evidence) {
        return service.submit(userId, JOURNEY_ID, revision, legIndex, evidence);
    }

    private SubwayBoardingEvidence movementStart(int sequence) {
        return new SubwayBoardingEvidence(SubwayBindingEventType.MOVEMENT_START, sequence,
                NOW.minusSeconds(20), null, null, "STATIONARY", "AUTOMOTIVE",
                SubwayBindingConfidence.HIGH, 15, true, NOW.minusSeconds(5));
    }

    private SubwayBoardingEvidence episode(int sequence) {
        return new SubwayBoardingEvidence(SubwayBindingEventType.STATION_STOP_EPISODE, sequence,
                null, NOW.minusSeconds(40), NOW.minusSeconds(20), "AUTOMOTIVE", "AUTOMOTIVE",
                SubwayBindingConfidence.MEDIUM, 20, true, NOW.minusSeconds(5));
    }

    private void givenActiveJourney() {
        when(sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(JOURNEY_ID, USER_ID))
                .thenReturn(Optional.of(session()));
    }

    private JourneySession session() {
        JourneySession journey = JourneySession.builder()
                .userId(USER_ID)
                .startedAt(NOW.minusSeconds(600))
                .guidanceLeaseUntil(NOW.plusSeconds(600))
                .build();
        ReflectionTestUtils.setField(journey, "id", JOURNEY_ID);
        return journey;
    }

    /** 걷기 → 대기 → 타기. 대기 번호로 들어와도 타는 단계로 옮겨 적히는지 보려고 짝을 만든다. */
    private void givenSubwayPair() {
        when(legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                eq(JOURNEY_ID), anyInt()))
                .thenReturn(List.of(
                        leg(0, JourneyLegType.WALK),
                        leg(WAIT_INDEX, JourneyLegType.WAIT_SUBWAY),
                        leg(RIDE_INDEX, JourneyLegType.RIDE_SUBWAY)));
    }

    private JourneyLeg leg(int index, JourneyLegType type) {
        JourneyLeg leg = JourneyLeg.builder()
                .journeyId(JOURNEY_ID)
                .routeRevision(REVISION)
                .legIndex(index)
                .type(type)
                .state(JourneyLegState.PENDING)
                .plannedDurationSec(300)
                .build();
        ReflectionTestUtils.setField(leg, "id", 100L + index);
        return leg;
    }

    /**
     * 저장소 대역.
     *
     * <p>목으로 두면 "저장한 것이 다음 조회에 보이는가"를 시험할 수 없다. 여기서는 그 연속성이
     * 곧 계약이라(같은 단계에 두 번 열지 않는다) 실제로 담아 둔다.
     */
    private static class FakeBindingRepository
            implements SubwayLegBindingRepository {

        final Map<Long, SubwayLegBinding> byLegId = new HashMap<>();
        final List<SubwayLegBinding> saved = new ArrayList<>();
        final List<SubwayLegBinding> deleted = new ArrayList<>();

        @Override
        public Optional<SubwayLegBinding> findByJourneyLegId(Long journeyLegId) {
            return Optional.ofNullable(byLegId.get(journeyLegId));
        }

        @Override
        public List<SubwayLegBinding> findByJourneyLegIdIn(List<Long> journeyLegIds) {
            return journeyLegIds.stream().map(byLegId::get).filter(java.util.Objects::nonNull).toList();
        }

        @Override
        public <S extends SubwayLegBinding> S save(S entity) {
            byLegId.put(entity.getJourneyLegId(), entity);
            saved.add(entity);
            return entity;
        }

        @Override
        public void delete(SubwayLegBinding entity) {
            byLegId.remove(entity.getJourneyLegId());
            deleted.add(entity);
        }

        // ── 아래는 이 시험이 쓰지 않는다 ──

        @Override public <S extends SubwayLegBinding> List<S> saveAll(Iterable<S> entities) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<SubwayLegBinding> findById(Long id) {
            throw new UnsupportedOperationException();
        }
        @Override public boolean existsById(Long id) { throw new UnsupportedOperationException(); }
        @Override public List<SubwayLegBinding> findAll() { throw new UnsupportedOperationException(); }
        @Override public List<SubwayLegBinding> findAllById(Iterable<Long> ids) {
            throw new UnsupportedOperationException();
        }
        @Override public long count() { throw new UnsupportedOperationException(); }
        @Override public void deleteById(Long id) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllById(Iterable<? extends Long> ids) {
            throw new UnsupportedOperationException();
        }
        @Override public void deleteAll(Iterable<? extends SubwayLegBinding> entities) {
            throw new UnsupportedOperationException();
        }
        @Override public void deleteAll() { throw new UnsupportedOperationException(); }
        @Override public void flush() { }
        @Override public <S extends SubwayLegBinding> S saveAndFlush(S entity) { return save(entity); }
        @Override public <S extends SubwayLegBinding> List<S> saveAllAndFlush(Iterable<S> entities) {
            throw new UnsupportedOperationException();
        }
        @Override public void deleteAllInBatch(Iterable<SubwayLegBinding> entities) {
            throw new UnsupportedOperationException();
        }
        @Override public void deleteAllByIdInBatch(Iterable<Long> ids) {
            throw new UnsupportedOperationException();
        }
        @Override public void deleteAllInBatch() { throw new UnsupportedOperationException(); }
        @Override public SubwayLegBinding getOne(Long id) { throw new UnsupportedOperationException(); }
        @Override public SubwayLegBinding getById(Long id) { throw new UnsupportedOperationException(); }
        @Override public SubwayLegBinding getReferenceById(Long id) {
            throw new UnsupportedOperationException();
        }
        @Override public <S extends SubwayLegBinding> Optional<S> findOne(
                org.springframework.data.domain.Example<S> example) {
            throw new UnsupportedOperationException();
        }
        @Override public <S extends SubwayLegBinding> List<S> findAll(
                org.springframework.data.domain.Example<S> example) {
            throw new UnsupportedOperationException();
        }
        @Override public <S extends SubwayLegBinding> List<S> findAll(
                org.springframework.data.domain.Example<S> example,
                org.springframework.data.domain.Sort sort) {
            throw new UnsupportedOperationException();
        }
        @Override public <S extends SubwayLegBinding> org.springframework.data.domain.Page<S> findAll(
                org.springframework.data.domain.Example<S> example,
                org.springframework.data.domain.Pageable pageable) {
            throw new UnsupportedOperationException();
        }
        @Override public <S extends SubwayLegBinding> long count(
                org.springframework.data.domain.Example<S> example) {
            throw new UnsupportedOperationException();
        }
        @Override public <S extends SubwayLegBinding> boolean exists(
                org.springframework.data.domain.Example<S> example) {
            throw new UnsupportedOperationException();
        }
        @Override public <S extends SubwayLegBinding, R> R findBy(
                org.springframework.data.domain.Example<S> example,
                java.util.function.Function<org.springframework.data.repository.query.FluentQuery
                        .FetchableFluentQuery<S>, R> queryFunction) {
            throw new UnsupportedOperationException();
        }
        @Override public List<SubwayLegBinding> findAll(
                org.springframework.data.domain.Sort sort) {
            throw new UnsupportedOperationException();
        }
        @Override public org.springframework.data.domain.Page<SubwayLegBinding> findAll(
                org.springframework.data.domain.Pageable pageable) {
            throw new UnsupportedOperationException();
        }
    }
}
