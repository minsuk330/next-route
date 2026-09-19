package watoo.grd.nextroute.application.notification.port.in;

import watoo.grd.nextroute.application.notification.dto.RouteAlertRequest;
import watoo.grd.nextroute.application.notification.dto.RouteAlertScheduleResponse;
import watoo.grd.nextroute.application.notification.dto.RouteAlertUpdateRequest;

import java.util.List;

/** 목표 도착 알림 구독 일정을 생성·조회·수정·취소한다. */
public interface RouteAlertUseCase {
    RouteAlertScheduleResponse create(long userId, RouteAlertRequest request);

    RouteAlertScheduleResponse update(long userId, Long scheduleId, RouteAlertUpdateRequest request);

    List<RouteAlertScheduleResponse> getActive(long userId);

    void cancel(long userId, Long alertId);
}
