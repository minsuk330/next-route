package watoo.grd.nextroute.infrastructure.adapter.in.api.auth.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 기기 익명 계정 요청.
 *
 * <p>{@code deviceSecret} 은 앱이 첫 실행에 만든 32바이트 난수의 base64url(43자). 형식 검증은 서비스가 한다 —
 * 빈 값·형식 위반을 한 가지 오류 코드({@code INVALID_DEVICE_SECRET})로 돌려주기 위해서다.
 */
@Getter
@Setter
@NoArgsConstructor
public class DeviceLoginRequest {

    private String deviceSecret;
}
