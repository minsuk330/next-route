package watoo.grd.nextroute.application.auth.dto;

import watoo.grd.nextroute.domain.auth.entity.AuthProvider;

/**
 * provider 토큰 검증을 통과한 신원.
 *
 * @param subject 회사가 매긴 고유 번호. 신원의 전부다. 비었으면 검증기가 거부해야 한다.
 * @param email   회사가 준 값. 소유권 미확인. 표시용.
 * @param name    표시용. 애플은 토큰에 담지 않아 앱이 넘긴 값을 그대로 쓴다.
 */
public record VerifiedIdentity(AuthProvider provider, String subject, String email, String name) {
}
