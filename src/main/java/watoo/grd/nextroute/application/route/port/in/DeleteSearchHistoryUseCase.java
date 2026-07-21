package watoo.grd.nextroute.application.route.port.in;

import java.util.NoSuchElementException;

/** 본인 소유 검색 이력 1건을 삭제한다. 미소유·부재 시 {@link NoSuchElementException}. */
public interface DeleteSearchHistoryUseCase {

    void delete(Long userId, Long id);
}
