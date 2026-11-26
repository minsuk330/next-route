package watoo.grd.nextroute.infrastructure.adapter.in.api.route.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import watoo.grd.nextroute.application.route.exception.InvalidDepartureTimeException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RouteSearchControllerErrorTest {

    private final RouteSearchController controller = new RouteSearchController(
            mock(watoo.grd.nextroute.application.route.port.in.SearchRouteUseCase.class),
            mock(watoo.grd.nextroute.application.route.port.in.RecordSearchHistoryUseCase.class),
            mock(watoo.grd.nextroute.application.route.service.RouteSearchSnapshotRecorder.class),
            new org.springframework.beans.factory.support.StaticListableBeanFactory()
                    .getBeanProvider(watoo.grd.nextroute.application.route.service.RouteSearchEventRecorder.class));

    @Test
    @DisplayName("InvalidDepartureTimeException → 400 INVALID_DEPARTURE_TIME")
    void invalidDepartureTime400() {
        ResponseEntity<Map<String, Object>> res =
                controller.handleInvalidDepartureTime(new InvalidDepartureTimeException("offset 필요"));
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).containsEntry("code", "INVALID_DEPARTURE_TIME");
    }
}
