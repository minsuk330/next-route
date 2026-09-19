package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.port.out.RerouteCooldownPort;
import watoo.grd.nextroute.application.journey.port.out.RerouteEligibilityPolicy;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.TransferConnection;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.domain.journey.repository.TransferConnectionRepository;

import java.time.Instant;
import java.util.Optional;

/**
 * 사용자가 "다시 찾아줘"를 눌렀을 때 실제로 찾아봐도 되는지.
 *
 * <p>다섯 가지를 <b>이 순서대로</b> 본다. 순서가 계약이다.
 *
 * <ol>
 *   <li>그 환승이 <b>이 개정본에</b> 있는가 — {@code no-transfer}</li>
 *   <li>아직 지나가지 않았는가 — {@code past-transfer}</li>
 *   <li>판정의 근거가 살아 있는가 — {@code window}</li>
 *   <li>환승 준비 시각이 문 안인가 — {@code window}</li>
 *   <li>최근에 이미 찾지 않았는가 — {@code cooldown}</li>
 * </ol>
 *
 * <h2>왜 진행 단계를 보는가 (R2)</h2>
 * 시간 조건만 보면 <b>30분 전에 지나간 환승도 통과한다</b> — "준비 시각까지 600초 이내"는 음수
 * 간격도 참이기 때문이다. 게다가 요청 본문의 {@code transferIndex} 는 없으면 0 으로 채워지므로
 * ({@code CreateRerouteProposalRequest}) 필드를 안 보내는 옛 앱 버전이 <b>첫 환승에 대한 제안</b>을
 * 만들어 버린다. 진행 단계로 걸러야 막힌다.
 *
 * <h2>왜 cooldown 이 마지막인가</h2>
 * 앞에서 어차피 떨어질 요청이 잠금을 태우면, 그 뒤에 오는 <b>실제로 가능한 요청</b>이 2분간
 * 막힌다. 잠금은 "찾아보기로 한 요청"만 태워야 한다.
 *
 * <h2>왜 살아 있는 제안을 막지 않는가</h2>
 * 저장 쪽이 앞선 제안을 {@code SUPERSEDED} 로 밀어내고, 연타는 cooldown 이 막는다. 여기서 또
 * 막으면 만료 직전 제안 하나 때문에 새 제안을 못 받는 상태가 생긴다.
 */
@Component
@RequiredArgsConstructor
public class WindowCooldownEligibilityPolicy implements RerouteEligibilityPolicy {

    private final TransferConnectionRepository transferRepository;
    private final RerouteTriggerPolicy triggerPolicy;
    private final RerouteCooldownPort cooldownPort;

    @Override
    public Optional<String> denialReason(JourneySession journey, int transferIndex, Instant now) {
        Optional<TransferConnection> found = transferRepository
                .findByJourneyIdAndRouteRevisionAndTransferIndexAndDeletedAtIsNull(
                        journey.getId(), journey.getRouteRevision(), transferIndex);
        if (found.isEmpty()) {
            return Optional.of("no-transfer");
        }
        TransferConnection transfer = found.get();

        if (transfer.getToLegIndex() < journey.getCurrentLegIndex()) {
            // 이미 그 환승을 지나 다음 단계로 갔다. 찾아 봐야 사용자가 할 수 있는 일이 없다.
            return Optional.of("past-transfer");
        }
        if (!hasLiveEvidence(transfer)) {
            return Optional.of("window");
        }
        if (!triggerPolicy.withinWindow(transfer.getUserReadyAt(), now)) {
            return Optional.of("window");
        }
        if (!cooldownPort.tryAdmit(journey.getId(), journey.getRouteRevision(),
                RerouteCooldownPort.Scope.REQUEST)) {
            return Optional.of("cooldown");
        }
        return Optional.empty();
    }

    /**
     * 판정의 근거가 아직 살아 있는가. <b>주기 평가의 문과 같은 기준이다</b>({@code RerouteTriggerPolicy}).
     *
     * <p>GPS 가 끊기면 진행도가 사라져 {@code userReadyAt} 이 null 이 되고 상태·신뢰도가
     * {@code UNKNOWN} 으로 떨어진다 — 문이 저절로 닫힌다. 낡은 값으로 열면 엉뚱한 때에 외부
     * 검색이 나가고, 그 결과를 비교할 기준도 낡아 있다.
     */
    private boolean hasLiveEvidence(TransferConnection transfer) {
        return transfer.getUserReadyAt() != null
                && transfer.getStatus() != TransferStatus.UNKNOWN
                && !CurrentLegArrival.Quality.UNKNOWN.name().equals(transfer.getConfidence());
    }
}
