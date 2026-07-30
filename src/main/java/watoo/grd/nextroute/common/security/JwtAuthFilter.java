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
import watoo.grd.nextroute.application.auth.port.out.TokenGenerationPort;

import java.io.IOException;
import java.util.List;
import java.util.OptionalInt;

/**
 * Bearer 토큰이 유효한 <b>access</b> 토큰이면 SecurityContext에 인증 정보를 채운다(principal=userId Long).
 * SecurityConfig에서 직접 인스턴스화해 필터 체인에 추가한다(@Component 아님 → @WebMvcTest 슬라이스 비오염).
 *
 * <p>세 가지를 본다. 서명·만료가 멀쩡한가, <b>access</b> 토큰인가({@link JwtProvider#parseAccessPrincipal}
 * — refresh 를 통과시키면 14일짜리 토큰으로 보호된 API 를 전부 호출할 수 있다), 그리고 그 토큰이
 * <b>아직 폐기되지 않았는가</b>다. 마지막 것이 로그아웃을 실제로 동작하게 만든다.
 *
 * <p>어느 하나라도 어긋나면 인증을 <b>채우지 않고</b> 요청은 그대로 흘려보낸다. 공개 경로는 계속
 * 익명으로 동작해야 하기 때문이다. 거부는 SecurityConfig 의 {@code authenticated()} 규칙이 맡는다.
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final JwtProvider jwtProvider;
    private final TokenGenerationPort tokenGenerationPort;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER)) {
            String token = header.substring(BEARER.length());
            try {
                JwtProvider.TokenPrincipal principal = jwtProvider.parseAccessPrincipal(token);
                if (isStillValid(principal)) {
                    var auth = new UsernamePasswordAuthenticationToken(
                            principal.userId(), null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (JwtException | NumberFormatException e) {
                log.debug("[JWT] 유효하지 않은 토큰: {}", e.getMessage());
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 토큰에 적힌 세대 번호가 지금도 그 사용자의 번호인지.
     *
     * <p>로그아웃하면 사용자 쪽 번호가 올라가므로, 그 전에 발급된 토큰은 여기서 걸린다.
     * 사용자가 아예 없으면 서명이 멀쩡해도 통과시키지 않는다.
     */
    private boolean isStillValid(JwtProvider.TokenPrincipal principal) {
        OptionalInt current = tokenGenerationPort.currentGeneration(principal.userId());
        if (current.isEmpty()) {
            log.debug("[JWT] 없는 사용자의 토큰: userId={}", principal.userId());
            return false;
        }
        if (current.getAsInt() != principal.generation()) {
            log.debug("[JWT] 폐기된 세대의 토큰: userId={}", principal.userId());
            return false;
        }
        return true;
    }
}
