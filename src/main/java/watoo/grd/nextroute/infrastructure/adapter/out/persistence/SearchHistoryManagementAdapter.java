package watoo.grd.nextroute.infrastructure.adapter.out.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.port.out.SearchHistoryManagementPort;
import watoo.grd.nextroute.domain.route.log.entity.RouteSearchHistory;
import watoo.grd.nextroute.domain.route.log.repository.RouteSearchHistoryRepository;

import java.util.List;

/** 검색 이력 조회·삭제 어댑터. PR1 의 {@link RouteSearchHistoryRepository} 를 재사용한다. */
@Component
@RequiredArgsConstructor
public class SearchHistoryManagementAdapter implements SearchHistoryManagementPort {

    private final RouteSearchHistoryRepository repository;

    @Override
    public List<RouteSearchHistory> findByUser(Long userId) {
        return repository.findByUser_IdOrderBySearchedAtDescIdDesc(userId);
    }

    @Override
    public long deleteByIdAndUser(Long id, Long userId) {
        return repository.deleteByIdAndUser_Id(id, userId);
    }

    @Override
    public void deleteByUser(Long userId) {
        repository.deleteByUser_Id(userId);
    }
}
