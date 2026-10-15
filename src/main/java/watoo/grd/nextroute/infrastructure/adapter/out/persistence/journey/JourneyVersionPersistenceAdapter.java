package watoo.grd.nextroute.infrastructure.adapter.out.persistence.journey;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.port.out.JourneyVersionPort;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;

import java.util.Optional;

/** 조건부 조회용 버전을 DB 에서 읽는다(#415-6). 캐시가 비었거나 꺼졌을 때의 원천이다. */
@Component
@RequiredArgsConstructor
public class JourneyVersionPersistenceAdapter implements JourneyVersionPort {

    private final JourneySessionRepository sessionRepository;

    @Override
    public Optional<Entry> find(long journeyId) {
        return sessionRepository.findVersionsById(journeyId)
                .map(row -> new Entry(row.getUserId(), row.getCommandVersion(), row.getEvaluationVersion(),
                        row.getCurrentLegType(), null));
    }
}
