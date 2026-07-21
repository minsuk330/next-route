package watoo.grd.nextroute.application.route.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.route.dto.SearchHistoryResponse;
import watoo.grd.nextroute.application.route.port.in.ClearSearchHistoryUseCase;
import watoo.grd.nextroute.application.route.port.in.DeleteSearchHistoryUseCase;
import watoo.grd.nextroute.application.route.port.in.GetSearchHistoryUseCase;
import watoo.grd.nextroute.application.route.port.out.SearchHistoryManagementPort;

import java.util.List;
import java.util.NoSuchElementException;

/**
 * 검색 이력 조회·관리(PR2). 삭제는 물리삭제이며 소유 검증은 (id, userId) 동시 일치로 한다
 * (영향 0 → {@link NoSuchElementException} → 컨트롤러 404).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RouteSearchHistoryService
        implements GetSearchHistoryUseCase, DeleteSearchHistoryUseCase, ClearSearchHistoryUseCase {

    private final SearchHistoryManagementPort managementPort;

    @Override
    public List<SearchHistoryResponse> getAll(Long userId) {
        return managementPort.findByUser(userId).stream()
                .map(SearchHistoryResponse::from)
                .toList();
    }

    @Override
    @Transactional
    public void delete(Long userId, Long id) {
        if (managementPort.deleteByIdAndUser(id, userId) == 0) {
            throw new NoSuchElementException("검색 이력을 찾을 수 없습니다: " + id);
        }
    }

    @Override
    @Transactional
    public void clear(Long userId) {
        managementPort.deleteByUser(userId);
    }
}
