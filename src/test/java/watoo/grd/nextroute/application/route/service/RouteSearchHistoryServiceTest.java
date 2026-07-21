package watoo.grd.nextroute.application.route.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.route.dto.SearchHistoryResponse;
import watoo.grd.nextroute.application.route.port.out.SearchHistoryManagementPort;
import watoo.grd.nextroute.domain.route.log.entity.RouteSearchHistory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouteSearchHistoryServiceTest {

    @Mock SearchHistoryManagementPort managementPort;
    @InjectMocks RouteSearchHistoryService service;

    private RouteSearchHistory history(String startName, LocalDateTime at) {
        return RouteSearchHistory.builder()
                .startX(127.0).startY(37.5).endX(127.01).endY(37.51)
                .startName(startName).endName("도착").pathCount(2).searchType(0)
                .searchedAt(at)
                .build();
    }

    @Test
    void TC_getAll은_포트가_돌려준_순서대로_DTO로_매핑한다() {
        // 정렬은 repository/포트가 담당한다. 서비스는 순서를 보존하며 매핑만 한다.
        given(managementPort.findByUser(7L)).willReturn(List.of(
                history("나중", LocalDateTime.parse("2026-07-21T11:00:00")),
                history("먼저", LocalDateTime.parse("2026-07-21T10:00:00"))));

        List<SearchHistoryResponse> result = service.getAll(7L);

        assertThat(result).extracting(SearchHistoryResponse::startName)
                .containsExactly("나중", "먼저");
        assertThat(result.get(0).pathCount()).isEqualTo(2);
    }

    @Test
    void TC_delete_본인소유행_삭제() {
        when(managementPort.deleteByIdAndUser(3L, 7L)).thenReturn(1L);

        service.delete(7L, 3L);

        verify(managementPort).deleteByIdAndUser(3L, 7L);
    }

    @Test
    void TC_delete_타인소유거나_부재면_NoSuchElement() {
        when(managementPort.deleteByIdAndUser(99L, 7L)).thenReturn(0L);

        assertThatThrownBy(() -> service.delete(7L, 99L))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void TC_clear_전체삭제_위임() {
        service.clear(7L);

        verify(managementPort).deleteByUser(7L);
    }
}
