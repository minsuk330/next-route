package watoo.grd.nextroute.application.notification.port.in;

import watoo.grd.nextroute.application.notification.dto.BusAlertRequest;
import watoo.grd.nextroute.application.notification.dto.BusAlertResponse;

import java.util.List;

/** 버스 도착 알림 구독을 생성·조회·취소한다. */
public interface BusAlertUseCase {
    BusAlertResponse create(long userId, BusAlertRequest request);

    List<BusAlertResponse> getActive(long userId);

    void cancel(long userId, Long alertId);
}
