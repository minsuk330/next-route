package watoo.grd.nextroute.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import watoo.grd.nextroute.global.error.ErrorResponse;
import watoo.grd.nextroute.global.error.code.SecurityErrorCode;

/** 인증은 됐지만 필요한 권한이 없는 요청을 공통 JSON 응답으로 변환한다. */
@RequiredArgsConstructor
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

  private final ObjectMapper objectMapper;

  @Override
  public void handle(
      HttpServletRequest request,
      HttpServletResponse response,
      AccessDeniedException exception
  ) throws IOException, ServletException {
    SecurityErrorCode errorCode = SecurityErrorCode.ACCESS_DENIED;
    response.setStatus(errorCode.getHttpStatus().value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());

    objectMapper.writeValue(
        response.getWriter(),
        ErrorResponse.of(errorCode, request.getRequestURI())
    );
  }
}
