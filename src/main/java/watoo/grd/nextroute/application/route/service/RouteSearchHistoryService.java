package watoo.grd.nextroute.application.route.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.route.dto.RecentPlaceResponse;
import watoo.grd.nextroute.application.route.port.in.ClearSearchHistoryUseCase;
import watoo.grd.nextroute.application.route.port.in.DeleteSearchHistoryUseCase;
import watoo.grd.nextroute.application.route.port.in.GetSearchHistoryUseCase;
import watoo.grd.nextroute.application.route.port.out.SearchHistoryManagementPort;
import watoo.grd.nextroute.domain.route.log.entity.RouteSearchHistory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * 검색행(출발+도착 쌍)을 장소 단위로 평탄화한다. 한 행은 출발 좌표·도착 좌표 2개 후보를 낳고,
     * 좌표(lng,lat) 정확일치로 dedup 한다. 포트가 {@code searched_at DESC, id DESC} 로 정렬해
     * 돌려주므로 좌표별 첫 등장이 곧 max(searched_at)·tie 큰 id → {@code putIfAbsent} 로 대표를 잡는다.
     * 대표 id 는 그 좌표가 최근 등장한 행 id 이며 삭제 대상이 된다. lat=Y, lng=X.
     */
    @Override
    public List<RecentPlaceResponse> getAll(Long userId) {
        Map<List<Double>, RecentPlaceResponse> byCoord = new LinkedHashMap<>();
        for (RouteSearchHistory h : managementPort.findByUser(userId)) {
            putIfAbsentPlace(byCoord, h.getId(), h.getStartY(), h.getStartX(), h.getStartName(), h.getSearchedAt());
            putIfAbsentPlace(byCoord, h.getId(), h.getEndY(), h.getEndX(), h.getEndName(), h.getSearchedAt());
        }
        return new ArrayList<>(byCoord.values());
    }

    private void putIfAbsentPlace(Map<List<Double>, RecentPlaceResponse> acc,
                                  Long rowId, Double lat, Double lng, String name, LocalDateTime at) {
        acc.putIfAbsent(List.of(lng, lat), new RecentPlaceResponse(rowId, lat, lng, name, at));
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
