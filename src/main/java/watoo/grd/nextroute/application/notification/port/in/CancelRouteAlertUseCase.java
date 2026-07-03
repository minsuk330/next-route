package watoo.grd.nextroute.application.notification.port.in;

public interface CancelRouteAlertUseCase {
    void cancel(long userId, Long alertId);
}
