package watoo.grd.nextroute.common.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import watoo.grd.nextroute.application.user.service.DailyActivityRecorder;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DailyActivityFilterTest {

    private final DailyActivityRecorder recorder = mock(DailyActivityRecorder.class);
    private final DailyActivityFilter filter = new DailyActivityFilter(recorder);
    private final FilterChain chain = mock(FilterChain.class);

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("익명 요청은 남기지 않고 체인을 계속한다")
    void anonymous() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(recorder, never()).touch(anyLong(), any());
        verify(chain).doFilter(req, res);
    }

    @Test
    @DisplayName("인증된 요청은 사용자와 UA 로 남긴다")
    void authenticated() throws Exception {
        authenticate(42L);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("User-Agent", "app/7 CFNetwork/3860");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(recorder).touch(42L, "app/7 CFNetwork/3860");
        verify(chain).doFilter(req, res);
    }

    @Test
    @DisplayName("기록기가 터져도 요청은 계속된다")
    void recorderFailureDoesNotBreakRequest() throws Exception {
        authenticate(42L);
        doThrow(new IllegalStateException("boom")).when(recorder).touch(anyLong(), any());
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, chain);

        verify(chain).doFilter(req, res);
    }

    private static void authenticate(long userId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }
}
