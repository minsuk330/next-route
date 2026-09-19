package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.config.JourneySubwayBindingProperties;
import watoo.grd.nextroute.application.journey.dto.SubwayBindingView;
import watoo.grd.nextroute.application.journey.dto.SubwayBoardingEvidence;
import watoo.grd.nextroute.application.journey.exception.StaleRouteRevisionException;
import watoo.grd.nextroute.application.journey.port.in.SubwayBindingUseCase;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingEventType;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingSource;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingStatus;
import watoo.grd.nextroute.domain.journey.entity.SubwayLegBinding;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.journey.repository.SubwayBindingEventRepository;
import watoo.grd.nextroute.domain.journey.repository.SubwayLegBindingRepository;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 앱이 보낸 탑승 관측을 받아 두고, 사용자가 손으로 고친 것을 반영한다.
 *
 * <h2>여기서 열차를 고르지 않는다</h2>
 *
 * <p>고를 재료가 아직 없을 수 있다. 원천 데이터는 1~2분 뒤처져 있어, 사용자가 탄 직후에는 그
 * 열차의 출발이 아직 관측되지 않았을 수 있다. 한 번 보고 끝내면 그 경우를 영영 못 잡는다.
 * 게다가 고른 뒤에는 알림을 보내야 하는데 요청을 받는 쪽에는 그 자격이 없다.
 *
 * <p>그래서 받아 두기만 하고, 고르는 일은 뒤에서 반복해 시도한다. 응답은 "받았다"까지다.
 *
 * <h2>트랜잭션 경계</h2>
 *
 * <p>감사 행 저장과 단계 상태 변경이 <b>한 트랜잭션</b>이다. 쪼개지면 "관측은 남았는데 상태가
 * 없다" 또는 그 반대가 생기고, 둘 다 나중에 무엇이 진짜인지 가릴 수 없는 상태다.
 *
 * <h2>고정 행은 반드시 잠그고 읽는다</h2>
 *
 * <p>이 엔티티에는 {@code @Version} 도 {@code @DynamicUpdate} 도 없다. 더티체킹 UPDATE 가 매핑
 * 칼럼 <b>전부</b>를 낡은 스냅샷 값으로 쓴다는 뜻이다. 잠금 없이 읽으면 — 판정기가 그 사이
 * 상태·열차·version 을 바꿔 커밋해도 — 이쪽이 flush 하는 순간 그 판정이 통째로 되돌아간다.
 * <b>예외도 나지 않는다.</b>
 *
 * <p>그래서 순번만 올리는 경로까지 포함해 전부 {@code findForUpdate} 를 쓴다. 판정기는
 * {@code SKIP LOCKED} 로 건너뛰므로 서로 기다리지 않고, 이쪽만 짧게 기다린다
 * ({@link SubwayBindingLockGuard}).
 *
 * <h2>기기 시각을 믿지 않는다</h2>
 *
 * <p>시계가 틀어진 기기가 섞인다. 범위 밖이면 관측을 <b>버리지 않고</b> 표시만 해 둔다 —
 * 버리면 그 기기는 영영 지하철 안내를 못 받고, 그 사실을 아무도 모른다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubwayBoardingEvidenceService implements SubwayBindingUseCase {

    private final JourneySessionRepository sessionRepository;
    private final JourneyLegRepository legRepository;
    private final SubwayLegBindingRepository bindingRepository;
    private final SubwayBindingEventRepository eventRepository;
    private final JourneySubwayBindingProperties properties;
    private final SubwayBindingLockGuard lockGuard;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // ── 관측 수신 ────────────────────────────────────────────────────────

    @Override
    @Transactional
    public SubwayBindingView submit(Long userId, Long journeyId, int routeRevision, int legIndex,
                                    SubwayBoardingEvidence evidence) {
        JourneySession journey = activeJourney(userId, journeyId, routeRevision);
        JourneyLeg ride = rideLegOf(journeyId, routeRevision, legIndex);
        Instant now = Instant.now(clock);

        lockGuard.applyLockTimeout();
        SubwayLegBinding binding = bindingRepository.findForUpdate(ride.getId())
                .orElseGet(() -> openBinding(ride, evidence, now));

        // 이미 끝난 단계에는 새 관측을 얹지 않는다. 사용자가 "안 탔다"고 한 뒤에 늦게 도착한
        // 관측이 그 결정을 뒤집으면 안 된다.
        if (binding.getStatus() == SubwayBindingStatus.ABANDONED) {
            log.debug("[SubwayEvidence] 접힌 단계의 관측 무시 — journeyId={} legIndex={}",
                    journeyId, ride.getLegIndex());
            return SubwayBindingView.from(binding);
        }

        boolean fresh = recordEvidence(journey, ride, binding, evidence, now);
        if (fresh) {
            advanceSequence(binding, evidence);
        }
        return SubwayBindingView.from(binding);
    }

    /**
     * 처음 이동 시작을 받았다. 아직 어느 열차인지는 모른다.
     *
     * <p>정차 관측이 먼저 오는 경우도 열어 준다 — 이동 시작을 놓쳤다고 그 뒤를 통째로 버리면
     * 판정 기회가 사라진다. 다만 그때는 감지 시각과 근거를 비워 둔다.
     */
    private SubwayLegBinding openBinding(JourneyLeg ride, SubwayBoardingEvidence evidence,
                                         Instant now) {
        SubwayLegBinding created = SubwayLegBinding.pending(
                ride.getId(), ride.getJourneyId(), ride.getRouteRevision(), ride.getLegIndex(),
                evidence.isMovementStart() ? evidence.movementStartedAt() : null,
                evidence.isMovementStart() ? sourceOf(evidence) : null,
                evidence.isMovementStart() ? evidence.confidence() : null);
        return bindingRepository.save(created);
    }

    /**
     * 기기가 차량 이동이라고 단정했는지 갈라 둔다.
     *
     * <p>나중에 오판율을 재려면 근거별로 나눠야 한다. 뭉치면 어느 쪽에서 틀리는지 모른다.
     */
    private SubwayBindingSource sourceOf(SubwayBoardingEvidence evidence) {
        return "AUTOMOTIVE".equals(evidence.currentActivity())
                ? SubwayBindingSource.MOTION_AUTOMOTIVE
                : SubwayBindingSource.MOTION_UNKNOWN;
    }

    /** @return 새로 쌓였으면 true, 이미 본 관측이면 false */
    private boolean recordEvidence(JourneySession journey, JourneyLeg ride,
                                   SubwayLegBinding binding, SubwayBoardingEvidence evidence,
                                   Instant now) {
        List<Long> inserted = eventRepository.insertIgnoringDuplicate(
                ride.getId(), journey.getId(), ride.getRouteRevision(), ride.getLegIndex(),
                evidence.type().kind().name(), evidence.type().name(), evidence.sequence(),
                null, binding.getVersion(), binding.getResetGeneration(),
                evidence.occurredAt(), now,
                null, null, null, null,
                detailJson(evidence, now), LocalDateTime.now(clock));
        return !inserted.isEmpty();
    }

    private void advanceSequence(SubwayLegBinding binding, SubwayBoardingEvidence evidence) {
        if (evidence.isMovementStart()) {
            binding.advanceMovementSeq(evidence.sequence());
        } else {
            binding.advanceEpisodeSeq(evidence.sequence());
        }
    }

    /**
     * 판정에 쓸 재료를 통째로 남긴다.
     *
     * <p>칼럼으로 박지 않는 이유는 어떤 값이 쓸모 있는지 아직 모르기 때문이다. 관문을 한 번
     * 조정할 때마다 필드가 바뀌는데 그때마다 마이그레이션을 만들 수는 없다.
     */
    private String detailJson(SubwayBoardingEvidence evidence, Instant receivedAt) {
        ObjectNode node = objectMapper.createObjectNode();
        putInstant(node, "movementStartedAt", evidence.movementStartedAt());
        putInstant(node, "stationaryAt", evidence.stationaryAt());
        putInstant(node, "resumedAt", evidence.resumedAt());
        node.put("previousActivity", evidence.previousActivity());
        node.put("currentActivity", evidence.currentActivity());
        node.put("confidence", evidence.confidence() == null ? null : evidence.confidence().name());
        node.put("sustainedSeconds", evidence.sustainedSeconds());
        node.put("vibrationEvidence", evidence.vibrationEvidence());
        if (isClockSuspect(evidence.occurredAt(), receivedAt)) {
            // 버리지 않고 표시만 한다. 버리면 시계가 틀어진 기기는 영영 안내를 못 받고,
            // 그 사실을 아무도 모른다. 판정기가 이 표시를 보고 가중을 낮춘다.
            node.put("clockSuspect", true);
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            // 근거를 못 적는 것이 관측 수신을 막을 이유는 아니다.
            log.warn("[SubwayEvidence] 근거 직렬화 실패 — {}", e.getMessage());
            return "{}";
        }
    }

    private boolean isClockSuspect(Instant occurredAt, Instant receivedAt) {
        if (occurredAt == null) {
            return true;
        }
        Duration skew = Duration.between(occurredAt, receivedAt);
        return skew.compareTo(properties.getMaxClockPast()) > 0
                || skew.negated().compareTo(properties.getMaxClockFuture()) > 0;
    }

    private void putInstant(ObjectNode node, String field, Instant value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value.toString());
        }
    }

    // ── 사용자 보정 ──────────────────────────────────────────────────────

    @Override
    @Transactional
    public SubwayBindingView reset(Long userId, Long journeyId, int routeRevision, int legIndex) {
        return correct(userId, journeyId, routeRevision, legIndex,
                SubwayBindingEventType.USER_RESET);
    }

    @Override
    @Transactional
    public SubwayBindingView notBoarded(Long userId, Long journeyId, int routeRevision,
                                        int legIndex) {
        return correct(userId, journeyId, routeRevision, legIndex,
                SubwayBindingEventType.USER_NOT_BOARDED);
    }

    /**
     * 사용자가 손으로 고친다. <b>확정도 뒤집을 수 있는 유일한 경로다.</b>
     *
     * <p>고친 사실을 먼저 남기고 상태를 바꾼다. 순서가 반대면 상태 변경 뒤 감사 저장이 실패했을 때
     * "왜 바뀌었는지 모르는 변경"이 남는다.
     */
    private SubwayBindingView correct(Long userId, Long journeyId, int routeRevision, int legIndex,
                                      SubwayBindingEventType type) {
        JourneySession journey = activeJourney(userId, journeyId, routeRevision);
        JourneyLeg ride = rideLegOf(journeyId, routeRevision, legIndex);
        lockGuard.applyLockTimeout();
        SubwayLegBinding binding = bindingRepository.findForUpdate(ride.getId())
                .orElseThrow(() -> new BusinessException(JourneyErrorCode.SUBWAY_BINDING_NOT_FOUND));

        Instant now = Instant.now(clock);
        eventRepository.insertIgnoringDuplicate(
                ride.getId(), journey.getId(), ride.getRouteRevision(), ride.getLegIndex(),
                type.kind().name(), type.name(), null,
                null, binding.getVersion(), binding.getResetGeneration(), now, now,
                // 거부한 열차를 남긴다. 이것이 없으면 판정기가 방금 거부한 열차를 다시 고른다.
                binding.getTrainNo(), binding.getTrainServiceDate(),
                binding.getTrainLineId(), binding.getTrainDirection(),
                "{}", LocalDateTime.now(clock));

        if (type == SubwayBindingEventType.USER_RESET) {
            binding.resetByUser();
        } else {
            binding.abandon();
        }
        return SubwayBindingView.from(binding);
    }

    // ── 공통 확인 ────────────────────────────────────────────────────────

    private JourneySession activeJourney(Long userId, Long journeyId, int routeRevision) {
        JourneySession journey = sessionRepository
                .findByIdAndUserIdAndDeletedAtIsNull(journeyId, userId)
                .orElseThrow(() -> new BusinessException(JourneyErrorCode.JOURNEY_NOT_FOUND));
        if (journey.getStatus() != JourneyStatus.ACTIVE) {
            throw new BusinessException(JourneyErrorCode.JOURNEY_NOT_ACTIVE);
        }
        if (journey.getRouteRevision() != routeRevision) {
            // 재탐색으로 경로가 바뀐 뒤 늦게 도착한 관측이다. 옛 단계에 적으면 아무도 안 보는
            // 곳에 쌓이고, 새 단계에 적으면 다른 경로의 관측이 섞인다.
            throw new StaleRouteRevisionException(journey.getRouteRevision());
        }
        return journey;
    }

    /**
     * 관측을 적을 단계를 찾는다.
     *
     * <p>앱이 대기 단계를 가리켜도 <b>짝이 되는 타는 단계</b>에 적는다. 고정은 타는 단계가
     * 소유하기 때문이다(노선 후보는 반대로 대기 단계가 소유한다). 축을 나눠 두면 한 개념이
     * 두 행에 흩어지지 않는다.
     */
    private JourneyLeg rideLegOf(Long journeyId, int routeRevision, int legIndex) {
        List<JourneyLeg> legs = legRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                        journeyId, routeRevision);
        JourneyLeg target = legs.stream()
                .filter(leg -> leg.getLegIndex() == legIndex)
                .findFirst()
                .orElseThrow(() -> new BusinessException(JourneyErrorCode.JOURNEY_LEG_NOT_FOUND));

        if (target.getType() == JourneyLegType.RIDE_SUBWAY) {
            return target;
        }
        if (target.getType() == JourneyLegType.WAIT_SUBWAY) {
            return legs.stream()
                    .filter(leg -> leg.getLegIndex() == legIndex + 1)
                    .filter(leg -> leg.getType() == JourneyLegType.RIDE_SUBWAY)
                    .findFirst()
                    .orElseThrow(() ->
                            new BusinessException(JourneyErrorCode.SUBWAY_LEG_REQUIRED));
        }
        throw new BusinessException(JourneyErrorCode.SUBWAY_LEG_REQUIRED);
    }
}
