package watoo.grd.nextroute.domain.push.entity;

/**
 * push token 을 발급받은 기기의 운영체제.
 *
 * <p>발송 payload 모양이 갈리기 때문에 저장한다. 웹은 없다 — 이 슬라이스는 네이티브 앱용이다.
 *
 * <p>값 목록은 DB 가 소유한다({@code user_push_token_platform_check}, V39).
 */
public enum PushPlatform {
    ANDROID,
    IOS
}
