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
 *   <li>그 차를 <b>아직 놓치지 않았다</b>({@code MISSED}·{@code UNKNOWN} 이 아니다). 이미 지나간
 *       차를 두고 "곧 온다"고 하면 거짓말이다</li>
 *   <li>다음 차 도착이 <b>창 안의 미래</b>다. 왼쪽은 열려 있고(이미 지나갔으면 안 보낸다) 오른쪽은
 *       닫혀 있다(정확히 창 끝이면 보낸다)</li>
 * </ol>
 *
 * <p><b>{@code AT_RISK} 를 막지 않는 이유.</b> 정류장에 서 있는 사용자에게 여유는
 * {@code 다음 차 도착 - 지금} 이므로, 차가 창(기본 2분) 안에 오면 여유도 그만큼 작아 상태는
 * 거의 항상 {@code AT_RISK} 다. 여기서 안전한 상태만 허용하면 조건이 스스로를 배제한다.
 * 그리고 그 자리에서 {@code AT_RISK} 는 위험이 아니라 <b>탈 준비를 하라</b>는 뜻이다 —
 * 그래서 위험 알림 쪽이 대기 단계의 {@code AT_RISK} 를 양보한다
 * ({@link TransferRiskNotifier} 참고). 배타성의 축은 상태가 아니라 <b>단계</b>다.
 *
 * <p>{@code UNKNOWN} 은 도착 시각이 비어 조건 3 에서 저절로 떨어진다.
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

    /**
     * 알림이 나가는 상태.
     *
     * <p>{@code MISSED} 는 이미 지나간 차라 "곧 온다"가 거짓말이고, {@code UNKNOWN} 은 근거가
     * 없다는 뜻이다. {@code UNKNOWN} 은 도착 시각도 비어 있어 어차피 아래에서 떨어지지만, 값을
     * 적어 두는 쪽을 고른다 — 판정의 근거가 "다른 필드가 우연히 비어서"가 되면 안 된다.
     */
    private static final Set<TransferStatus> NOTIFIABLE = EnumSet.of(
            TransferStatus.COMFORTABLE, TransferStatus.CATCHABLE, TransferStatus.AT_RISK);

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
        if (!NOTIFIABLE.contains(assessment.status())) {
            return false;
        }
        Instant arrival = assessment.nextBusArrivalAt();
        if (arrival == null || !arrival.isAfter(now)) {
            return false;
        }
        return Duration.between(now, arrival).compareTo(properties.getApproach().window()) <= 0;
    }
}
