package watoo.grd.nextroute.common.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Bearer 토큰이 유효한 <b>access</b> 토큰이면 SecurityContext에 인증 정보를 채운다(principal=userId Long).
 * SecurityConfig에서 직접 인스턴스화해 필터 체인에 추가한다(@Component 아님 → @WebMvcTest 슬라이스 비오염).
 *
 * <p>refresh 토큰은 여기서 통과하지 못한다({@link JwtProvider#parseAccessUserId}).
 * 통과시키면 14일짜리 토큰으로 보호된 API 를 전부 호출할 수 있다.
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final JwtProvider jwtProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER)) {
            String token = header.substring(BEARER.length());
            try {
                long userId = jwtProvider.parseAccessUserId(token);
                var auth = new UsernamePasswordAuthenticationToken(
                        userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (JwtException | NumberFormatException e) {
                log.debug("[JWT] 유효하지 않은 토큰: {}", e.getMessage());
            }
        }
        filterChain.doFilter(request, response);
    }
}
