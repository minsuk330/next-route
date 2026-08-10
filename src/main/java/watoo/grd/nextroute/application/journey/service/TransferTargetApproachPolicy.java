package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * "갈아탈 차가 곧 온다"고 알릴 때인지 판단한다(N2).
 *
 * <p>세 조건의 곱이다. 상태 하나로 정해지는 위험 알림과 달리 <b>상태·남은 시간·현재 단계</b>가
 * 모두 맞아야 한다.
 *
 * <ol>
 *   <li>사용자가 그 환승의 <b>대기 단계에 들어와 있다</b> — 아직 앞 차를 타고 있으면 "다음 차가
 *       온다"를 들어도 할 수 있는 일이 없다</li>
 *   <li>환승 상태가 <b>안전하다</b>({@code COMFORTABLE}·{@code CATCHABLE}). 이것은 "위험 알림이
 *       나가지 않는 상태"라는 뜻이라, 같은 환승에 대해 두 알림이 겹치지 않는다</li>
 *   <li>다음 차 도착이 <b>창 안의 미래</b>다. 왼쪽은 열려 있고(이미 지나갔으면 안 보낸다) 오른쪽은
 *       닫혀 있다(정확히 창 끝이면 보낸다)</li>
 * </ol>
 *
 * <p>{@code COMFORTABLE} 을 포함하는 것이 중요하다. 여유는
 * {@code nextBusArrivalAt - userReadyAt} 이라, 정류장에 <b>일찍 도착해 기다리는 중</b>이면
 * 준비 시각이 과거가 되어 여유가 커진다. 그러면서 차는 2분 뒤에 온다 — N2 가 가장 쓸모 있는
 * 상황이 정확히 이것이다.
 *
 * <p>{@code UNKNOWN} 은 목록에 없어도 도착 시각이 비어 조건 3 에서 저절로 떨어진다.
 *
 * <p><b>이 판단을 두 벌로 두지 않는다.</b> 발송 정보를 만들지 결정하는
 * {@link JourneyEvaluationService} 와 실제로 쌓는 {@link TransferTargetApproachNotifier} 가 같은
 * 조건을 봐야 한다. 한쪽만 좁으면 어디서도 터지지 않은 채 N2 가 조용히 사라진다.
 *
 * <p>저장소를 보지 않고 아무것도 저장하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class TransferTargetApproachPolicy {

    /** 알림이 나가는 상태. "위험 알림이 나가지 않는 상태"와 같은 뜻이다. */
    private static final Set<TransferStatus> SAFE =
            EnumSet.of(TransferStatus.COMFORTABLE, TransferStatus.CATCHABLE);

    private final JourneyNotificationProperties properties;

    /**
     * @param assessment      환승 하나의 판정
     * @param currentLegIndex 사용자가 지금 있는 단계
     * @param now             <b>평가 한 번의 기준 시각</b>. 부르는 쪽이 캡처한 값을 그대로 넘긴다 —
     *                        여기서 다시 읽으면 조건을 통과한 알림이 삽입 시점에 이미 만료일 수 있다
     */
    public boolean isApproaching(TransferRiskAssessment assessment, int currentLegIndex,
                                 Instant now) {
        if (!properties.getApproach().isEnabled()) {
            return false;
        }
        if (assessment.toLegIndex() != currentLegIndex) {
            return false;
        }
        if (!SAFE.contains(assessment.status())) {
            return false;
        }
        Instant arrival = assessment.nextBusArrivalAt();
        if (arrival == null || !arrival.isAfter(now)) {
            return false;
        }
        return Duration.between(now, arrival).compareTo(properties.getApproach().window()) <= 0;
    }
}
