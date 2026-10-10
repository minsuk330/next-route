package watoo.grd.nextroute.application.journey.observability;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.SubwayBindingAbandonReason;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingStatus;

/**
 * 열차 고정 수명주기 지표(접힘·재탐색 계승).
 *
 * <p>tag 는 고정 집합이다 — 여정 번호·열차번호처럼 요청마다 달라지는 값은 넣지 않는다. 넣으면
 * 시계열이 무한히 늘어난다.
 */
@Component
@RequiredArgsConstructor
public class SubwayBindingMetrics {

    public static final String LIFECYCLE = "journey.subway.binding.lifecycle";

    private final MeterRegistry registry;

    /**
     * 수명주기가 접은 미완료 고정.
     *
     * <p>사유별로 갈라야 뜻이 생긴다. {@code lease_expired} 가 몰리면 판정 기회를 잃고 있는
     * 것이고, {@code ended} 가 대부분이면 정상 종료다.
     */
    public void recordAbandoned(SubwayBindingAbandonReason reason, int count) {
        if (count > 0) {
            registry.counter(LIFECYCLE, "event", "abandoned", "reason", reason.tag())
                    .increment(count);
        }
    }

    /**
     * 재탐색에서 이어받은 고정.
     *
     * <p>0 에 붙어 있으면 계승 조건(구간 완전 동일)이 실제로는 거의 안 맞는다는 뜻이고, 그때
     * 탑승 중 재탐색은 남은 내내 "확인 중"에 머문다.
     */
    public void recordInherited(SubwayBindingStatus status, int count) {
        if (count > 0) {
            registry.counter(LIFECYCLE, "event", "inherited",
                    "reason", status.name().toLowerCase()).increment(count);
        }
    }
}
