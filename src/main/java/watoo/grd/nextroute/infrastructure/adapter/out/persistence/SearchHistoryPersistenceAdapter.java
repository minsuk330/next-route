package watoo.grd.nextroute.infrastructure.adapter.out.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.route.dto.SearchHistoryEntry;
import watoo.grd.nextroute.application.route.port.out.SearchHistoryCommandPort;
import watoo.grd.nextroute.domain.route.log.repository.RouteSearchHistoryRepository;

/**
 * 검색 이력 쓰기 어댑터. upsert 후 trim 을 한 트랜잭션에서 순차 수행한다.
 *
 * <p>비동기 워커 스레드에서만 호출되므로 응답 경로와 분리돼 있다. 그럼에도 짧은 tx timeout 을 둬
 * DB 지연이 워커 스레드를 오래 붙잡지 않게 한다(풀 고갈 방지).
 */
@Component
@RequiredArgsConstructor
public class SearchHistoryPersistenceAdapter implements SearchHistoryCommandPort {

    private final RouteSearchHistoryRepository repository;

    @Override
    @Transactional(timeout = 3)
    public void upsertAndTrim(SearchHistoryEntry entry, int maxPerUser) {
        repository.upsert(
                entry.userId(),
                entry.startX(), entry.startY(), entry.endX(), entry.endY(),
                entry.startName(), entry.endName(),
                entry.pathCount(), entry.searchType(),
                entry.searchedAt());
        repository.trim(entry.userId(), maxPerUser);
    }
}
