package watoo.grd.nextroute.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import watoo.grd.nextroute.application.user.service.DailyActivityRecorder;

import java.io.IOException;

/**
 * {@link JwtAuthFilter} 가 채운 사용자로 그날 활동을 남긴다(사용 통계 DAU). 빈이 아니다 — JwtAuthFilter 와
 * 같은 이유(@WebMvcTest 오염 방지)로 SecurityConfig 가 직접 만든다.
 *
 * <p>기록은 어떤 경우에도 요청을 막지 않는다. 기록기 예외도 여기서 삼키고 체인을 계속한다.
 */
@Slf4j
@RequiredArgsConstructor
public class DailyActivityFilter extends OncePerRequestFilter {

    private final DailyActivityRecorder recorder;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Long userId) {
            try {
                recorder.touch(userId, request.getHeader("User-Agent"));
            } catch (RuntimeException e) {
                log.warn("[DailyActivity] 기록 건너뜀: {}", e.toString());
            }
        }
        filterChain.doFilter(request, response);
    }
}
