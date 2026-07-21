package watoo.grd.nextroute.application.route.port.in;

/** 본인 검색 이력 전체를 삭제한다. */
public interface ClearSearchHistoryUseCase {

    void clear(Long userId);
}
