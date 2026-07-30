package watoo.grd.nextroute.domain.auth.entity;

/**
 * 외부 로그인 수단.
 *
 * <p>{@code TOSS} 는 미니앱 안에서만 동작한다. 네이티브 앱에서는 인가코드를 얻을 방법이 없으므로
 * 공용 로그인 API 로 들어올 수 없고, 대신 앱 수단 하나와 한 계정에 공존할 수 있다.
 */
public enum AuthProvider {

    /** 미니앱 전용. 앱에서는 고를 수 없고, 앱 수단 하나와 공존할 수 있다. */
    TOSS(false, false),
    APPLE(true, true),
    KAKAO(true, true);

    private final boolean appExclusive;
    private final boolean appLoginAllowed;

    AuthProvider(boolean appExclusive, boolean appLoginAllowed) {
        this.appExclusive = appExclusive;
        this.appLoginAllowed = appLoginAllowed;
    }

    /** 계정당 하나만 허용되는 앱 로그인 수단인지. TOSS 만 false. */
    public boolean isAppExclusive() {
        return appExclusive;
    }

    /** 공용 로그인 API 로 들어올 수 있는 수단인지. TOSS 만 false. */
    public boolean isAppLoginAllowed() {
        return appLoginAllowed;
    }
}
