package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.dto.SubwayBindingView;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingEventType;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingTrainKind;
import watoo.grd.nextroute.domain.journey.entity.SubwayLegBinding;
import watoo.grd.nextroute.domain.journey.repository.SubwayBindingEventRepository;
import watoo.grd.nextroute.domain.journey.repository.SubwayLegBindingRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 사용자가 고른 열차를 고정 행에 적는 트랜잭션(#265). 도착 조회(외부 호출)는 바깥에서 끝내고 들어온다.
 *
 * <p>고정 행은 <b>잠그고</b> 읽는다 — 이 엔티티는 {@code @Version}·{@code @DynamicUpdate} 가 없어 잠금 없이 고치면 다른
 * 요청이 그 사이 쓴 결과를 통째로 덮는다. 감사(USER_SELECTED)를 먼저 남기고 상태를 바꾼다.
 */
@Service
@RequiredArgsConstructor
public class SubwayTrainSelectionTxService {

    private final SubwayLegBindingRepository bindingRepository;
    private final SubwayBindingEventRepository eventRepository;
    private final SubwayBindingLockGuard lockGuard;
    private final Clock clock;

    /**
     * 고른 열차. 식별 4칸은 함께 있어야 한다(V44).
     *
     * @param aboard         이미 탄 열차(떠난 열차·탑승 중 재선택). 링이 다음 역부터 센다
     * @param kind           열차 종류(subway-express PR-S4, V72). null 이면 미분류 — 링이 한 번 분류한다
     * @param serviceId      급행 카탈로그 서비스 id. 급행이면 반드시 있다
     * @param catalogVersion 그 서비스를 해석한 카탈로그 판
     */
    public record ChosenTrain(String trainNo, LocalDate serviceDate, String lineId, String direction, boolean aboard,
                              SubwayBindingTrainKind kind, String serviceId, String catalogVersion) {
        public ChosenTrain(String trainNo, LocalDate serviceDate, String lineId, String direction, boolean aboard) {
            this(trainNo, serviceDate, lineId, direction, aboard, null, null, null);
        }

        public ChosenTrain(String trainNo, LocalDate serviceDate, String lineId, String direction) {
            this(trainNo, serviceDate, lineId, direction, false);
        }

        /** 감사 행 payload — {@code {"trainKind","serviceId"}}. 모르면 칸이 null 이다. */
        String auditPayload() {
            com.fasterxml.jackson.databind.node.ObjectNode payload =
                    com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
            payload.put("trainKind", kind == null ? null : kind.name());
            payload.put("serviceId", serviceId);
            return payload.toString();
        }
    }

    @Transactional
    public SubwayBindingView bind(JourneyLeg ride, ChosenTrain train) {
        lockGuard.applyLockTimeout();
        Instant now = Instant.now(clock);
        SubwayLegBinding binding = bindingRepository.findForUpdate(ride.getId()).orElse(null);

        eventRepository.insertIgnoringDuplicate(
                ride.getId(), ride.getJourneyId(), ride.getRouteRevision(), ride.getLegIndex(),
                SubwayBindingEventType.USER_SELECTED.kind().name(), SubwayBindingEventType.USER_SELECTED.name(),
                null, null, binding == null ? 0 : binding.getVersion(),
                binding == null ? 0 : binding.getResetGeneration(), now, now,
                train.trainNo(), train.serviceDate(), train.lineId(), train.direction(),
                train.auditPayload(), LocalDateTime.now(clock));

        if (binding == null) {
            binding = SubwayLegBinding.selected(ride.getId(), ride.getJourneyId(),
                    ride.getRouteRevision(), ride.getLegIndex(), train.trainNo(), train.serviceDate(),
                    train.lineId(), train.direction(), train.kind(), train.serviceId(), train.catalogVersion());
            if (train.aboard()) {
                binding.markBoardedDetected(now);
            }
            binding = bindingRepository.saveAndFlush(binding);
        } else {
            binding.selectByUser(train.trainNo(), train.serviceDate(), train.lineId(), train.direction(),
                    train.aboard() ? now : null, train.kind(), train.serviceId(), train.catalogVersion());
        }
        return SubwayBindingView.from(binding);
    }
}
