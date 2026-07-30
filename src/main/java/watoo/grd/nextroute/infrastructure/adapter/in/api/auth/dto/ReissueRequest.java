package watoo.grd.nextroute.infrastructure.adapter.in.api.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** 재발급 요청. access 가 만료된 뒤 부르는 경로라 신원 증명은 이 본문의 refresh 토큰이 한다. */
public record ReissueRequest(@NotBlank String refreshToken) { }
