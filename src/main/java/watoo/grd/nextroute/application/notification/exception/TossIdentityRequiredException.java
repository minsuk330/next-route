package watoo.grd.nextroute.application.notification.exception;

/**
 * 알림 구독을 만들려는데 토스 신원이 없다. 422 로 나간다.
 *
 * <p>현재 알림 발송 채널은 토스 하나뿐이라, 애플·카카오로 가입한 사용자는 구독을 만들어도
 * 받을 수단이 없다. 요청은 성공시키고 나중에 백그라운드 발송만 조용히 실패하면 사용자는
 * 알림이 온다고 믿는다. 그래서 만드는 시점에 막는다.
 *
 * <p>독립 발송 채널(FCM)이 붙는 Phase 5 에서 걷어낸다.
 */
public class TossIdentityRequiredException extends RuntimeException {

    public TossIdentityRequiredException() {
        super("이 알림 기능은 토스 로그인 계정에서만 쓸 수 있다");
    }
}
