package watoo.grd.nextroute.global.error;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.HandlerMapping;
import watoo.grd.nextroute.common.observability.SyntheticRequest;

import java.io.IOException;

/**
 * 5xx 로 나간 응답은 어느 핸들러에서 나왔든 ERROR 로그 한 줄(가능하면 스택 트레이스 포함)을 남긴다.
 *
 * <p>장애 분석기(alert-analyzer)는 Loki 의 ERROR 줄을 재료로 원인을 추정한다. 그런데 5xx 를 응답으로 바꾸면서
 * 아무것도 안 남기는 핸들러가 있었다(경로 검색 502/504, 토스 502, BusinessException 503). 알림은 울려도 재료가
 * 없었다. 핸들러마다 로그를 넣는 대신 여기 한 곳에서 지킨다 — 새 핸들러가 생겨도 빈칸이 다시 안 생긴다.
 *
 * <p>예외는 DispatcherServlet 이 @ExceptionHandler 로 처리한 뒤 {@link DispatcherServlet#EXCEPTION_ATTRIBUTE}
 * 에 남겨 둔 것을 쓴다. 이미 스택을 남긴 곳({@link GlobalExceptionHandler} 의 처리 안 된 예외)은
 * {@link #markLogged} 로 표시해 두 번 찍지 않는다. 체인 밖으로 새는 예외는 Tomcat 이 스택과 함께 남기므로 여기서 다루지 않는다.
 */
@Slf4j
@Component
public class ServerErrorLoggingFilter extends OncePerRequestFilter {

    static final String LOGGED_ATTRIBUTE = ServerErrorLoggingFilter.class.getName() + ".LOGGED";

    /** 이 요청의 5xx 는 이미 스택과 함께 기록했다. */
    public static void markLogged(HttpServletRequest request) {
        request.setAttribute(LOGGED_ATTRIBUTE, Boolean.TRUE);
    }

    public static void markLogged(WebRequest request) {
        request.setAttribute(LOGGED_ATTRIBUTE, Boolean.TRUE, RequestAttributes.SCOPE_REQUEST);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        filterChain.doFilter(request, response);

        int status = response.getStatus();
        if (status < 500 || request.getAttribute(LOGGED_ATTRIBUTE) != null) {
            return;
        }
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        boolean synthetic = SyntheticRequest.isSynthetic(request);
        if (request.getAttribute(DispatcherServlet.EXCEPTION_ATTRIBUTE) instanceof Throwable ex) {
            log.error("[ServerError] status={} method={} uri={} pattern={} synthetic={} exception={}: {}",
                    status, request.getMethod(), request.getRequestURI(), pattern, synthetic,
                    ex.getClass().getName(), ex.getMessage(), ex);
        } else {
            log.error("[ServerError] status={} method={} uri={} pattern={} synthetic={} exception=none",
                    status, request.getMethod(), request.getRequestURI(), pattern, synthetic);
        }
    }
}
