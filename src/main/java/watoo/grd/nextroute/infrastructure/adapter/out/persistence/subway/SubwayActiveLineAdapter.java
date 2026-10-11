package watoo.grd.nextroute.infrastructure.adapter.out.persistence.subway;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.subway.port.out.SubwayActiveLinePort;
import watoo.grd.nextroute.domain.journey.repository.SubwayLegBindingRepository;

import java.util.HashSet;
import java.util.Set;

/** 링 대상 고정({@code findRingTargets} 와 같은 조건)의 노선. */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubwayActiveLineAdapter implements SubwayActiveLinePort {

    private final SubwayLegBindingRepository bindingRepository;

    @Override
    public Set<String> activeRingLineIds() {
        try {
            return new HashSet<>(bindingRepository.findActiveRingLineIds());
        } catch (RuntimeException e) {
            log.warn("[PositionCollector] 진행 중 노선 조회 실패 — 평소 주기로 돈다: {}", e.getMessage());
            return Set.of();
        }
    }
}
