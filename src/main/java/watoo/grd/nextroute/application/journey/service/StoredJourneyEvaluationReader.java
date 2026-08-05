package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.config.JourneyEvaluatorProperties;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.repository.TransferConnectionRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 평가기가 남긴 결과를 읽는다. 외부 API 를 부르지 않는다.
 *
 * <p>이것이 조회 요청이 빨라지는 이유다. 지금까지는 앱이 여정을 조회할 때마다 그 요청 안에서
 * 실시간 도착 API 를 불렀다. provider 가 느리면 여정 화면도 그만큼 느려졌고, 목표(스냅샷 p95
 * 500ms)를 provider 지연이 직접 깨뜨릴 수 있었다.
 *
 * <h2>낡음을 어떻게 가리는가</h2>
 *
 * 기준이 시간이 아니라 <b>가리키는 시각</b>이다. 5분 전에 계산했어도 "10분 뒤 도착"이면 아직
 * 쓸 만하고, 30초 전에 계산했어도 "이미 도착"이면 쓸모없다. 이미 지난 도착 시각을 그대로
 * 내려보내면 사용자는 오지 않을 차를 기다린다.
 *
 * <p>시간 상한도 함께 둔다(기본 5분). 평가기가 멈춰 있는데 도착 시각만 먼 미래인 값이 계속
 * 최신인 척하는 것을 막는 안전망이다.
 */
@Service
@RequiredArgsConstructor
public class StoredJourneyEvaluationReader {

    private final TransferConnectionRepository transferRepository;
    private final JourneyEvaluatorProperties properties;

    /**
     * 저장된 환승 판정. 낡은 것은 모름으로 바꿔 돌려준다.
     *
     * @param now 낡음 판단 기준 시각
     */
    @Transactional(readOnly = true)
    public List<TransferRiskAssessment> assessments(Long journeyId, int routeRevision, Instant now) {
        return transferRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByTransferIndex(
                        journeyId, routeRevision)
                .stream()
                .map(TransferAssessmentMapper::fromStored)
                .map(assessment -> usable(assessment, now) ? assessment : assessment.asUnknown())
                .toList();
    }

    /**
     * 기다리는 단계의 도착 예측. 평가기가 단계 행에 적어 둔 값을 읽는다.
     *
     * <p>여기서 실시간을 부르지 않는 것이 요점이다. 기다리는 단계가 현재 단계일 때가 가장 조회가
     * 잦은 구간이고(앱이 10초마다 묻는다), 그때마다 외부 API 를 부르면 호출량이 사용자 수에
     * 비례한다.
     */
    public CurrentLegArrival boardingArrival(JourneyLeg waitLeg, Instant now) {
        if (waitLeg == null || waitLeg.getPredictedEndAt() == null) {
            return CurrentLegArrival.unknown();
        }
        if (waitLeg.getPredictedEndAt().isBefore(now) || tooOld(waitLeg.getEvidenceObservedAt(), now)) {
            return CurrentLegArrival.unknown();
        }
        return new CurrentLegArrival(
                waitLeg.getPredictedEndAt(),
                (int) Math.max(0, Duration.between(now, waitLeg.getPredictedEndAt()).getSeconds()),
                null,
                null,
                sourceOf(waitLeg.getEvidenceSource()),
                TransferAssessmentMapper.qualityOf(waitLeg.getEvidenceQuality()),
                waitLeg.getEvidenceObservedAt());
    }

    /** 이 판정을 아직 써도 되는지. */
    private boolean usable(TransferRiskAssessment assessment, Instant now) {
        if (assessment.isUnknown()) {
            return true;
        }
        if (assessment.nextBusArrivalAt() == null) {
            return true;
        }
        return !assessment.nextBusArrivalAt().isBefore(now)
                && !tooOld(assessment.calculatedAt(), now);
    }

    private boolean tooOld(Instant calculatedAt, Instant now) {
        if (calculatedAt == null) {
            return true;
        }
        return Duration.between(calculatedAt, now).compareTo(properties.staleAfter()) > 0;
    }

    private static CurrentLegArrival.Source sourceOf(String stored) {
        if (stored == null) {
            return null;
        }
        try {
            return CurrentLegArrival.Source.valueOf(stored);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
