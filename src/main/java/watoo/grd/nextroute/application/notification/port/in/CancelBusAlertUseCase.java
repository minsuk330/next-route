package watoo.grd.nextroute.application.notification.port.in;

public interface CancelBusAlertUseCase {
    void cancel(long userId, Long alertId);
}
