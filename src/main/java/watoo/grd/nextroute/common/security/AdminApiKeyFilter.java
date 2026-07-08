package watoo.grd.nextroute.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * X-Admin-Key 헤더가 설정된 시크릿과 일치하면 SecurityContext에 ROLE_ADMIN 인증을 채운다.
 * 인가는 SecurityConfig의 hasRole("ADMIN")이 담당한다. 키가 공백(미설정)이면 절대 인증하지 않음(fail-closed).
 * SecurityConfig에서 직접 인스턴스화한다(@Component 아님 → @WebMvcTest 슬라이스 비오염).
 */
@RequiredArgsConstructor
public class AdminApiKeyFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Admin-Key";

    private final String configuredKey;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (provided != null && !configuredKey.isBlank() && constantTimeEquals(provided, configuredKey)) {
            var auth = new UsernamePasswordAuthenticationToken(
                    "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        filterChain.doFilter(request, response);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
