package watoo.grd.nextroute.common.config;

import io.micrometer.common.KeyValue;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.observation.ClientHttpObservationDocumentation;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * 외부 API 호출 지표·span 의 {@code uri}·{@code http.url} 을 만든다.
 *
 * <p><b>왜 필요한가.</b> 기본 규칙은 주소 틀({@code /arrive/{id}} 처럼 변수가 남은 문자열)이 있어야
 * {@code uri} tag 를 채운다. 공공데이터 어댑터들은 키가 이미 인코딩돼 있어 틀로 넘기면 다시
 * 인코딩돼 깨지므로 완성된 {@link URI} 를 넘긴다 — 그래서 서울 버스·지하철·data.go.kr 호출이 전부
 * {@code uri=none} 한 줄로 뭉쳐 엔드포인트별 지연을 가를 수 없었다(#415).
 *
 * <p><b>경로를 그대로 쓰지 않는 이유.</b> 서울 열린데이터 주소는 경로 안에 인증키와 역 이름이
 * 들어간다({@code /api/subway/{키}/json/realtimeStationArrival/0/1000/{역}}). 그대로 tag 로 쓰면 키가
 * 지표 저장소로 새고, 역 수만큼 시계열이 늘어난다. 그래서 "API 이름처럼 생긴 조각"만 남기고 나머지는
 * {@code {}} 로 가린다.
 *
 * <p><b>{@code http.url}(span)도 같이 고친다.</b> 기본값은 쿼리까지 통째로 실어 {@code serviceKey} 가
 * 트레이스 저장소에 남는다. 쿼리는 모두 버린다 — 장소 검색어처럼 사용자가 입력한 값도 쿼리에 있다.
 */
@Component
public class ExternalApiObservationConvention extends DefaultClientRequestObservationConvention {

    /**
     * 남길 조각: 영문자로 시작하고, 영문자·{@code . _ -} 뒤에 숫자가 최대 두 자리 붙는 것
     * ({@code getBusArrivalItemv2}, {@code v2}, {@code keyword.json}). 인증키처럼 숫자가 중간에 섞인
     * 문자열, 숫자만 있는 쪽수·기관 번호, 인코딩된 한글은 모두 걸러진다.
     */
    private static final Pattern NAME_LIKE = Pattern.compile("[A-Za-z][A-Za-z._-]*[0-9]{0,2}");

    /** 영문자만으로 된 16진 키(드물지만 가능)를 한 번 더 막는다. */
    private static final Pattern HEX_LIKE = Pattern.compile("[0-9a-fA-F]{20,}");

    private static final int MAX_SEGMENT_LENGTH = 64;
    private static final String MASK = "{}";

    @Override
    protected KeyValue uri(ClientRequestObservationContext context) {
        if (context.getUriTemplate() != null || context.getCarrier() == null) {
            return super.uri(context);
        }
        return KeyValue.of(ClientHttpObservationDocumentation.LowCardinalityKeyNames.URI,
                maskPath(context.getCarrier().getURI().getRawPath()));
    }

    @Override
    protected KeyValue requestUri(ClientRequestObservationContext context) {
        ClientHttpRequest request = context.getCarrier();
        if (request == null) {
            return super.requestUri(context);
        }
        URI target = request.getURI();
        String path = context.getUriTemplate() != null
                ? target.getRawPath()
                : maskPath(target.getRawPath());
        String origin = target.getScheme() + "://" + target.getRawAuthority();
        return KeyValue.of(ClientHttpObservationDocumentation.HighCardinalityKeyNames.HTTP_URL,
                origin + (path == null ? "" : path));
    }

    /**
     * 경로의 조각마다 API 이름처럼 생긴 것만 남긴다.
     *
     * @return 슬래시 구조는 그대로 둔 경로. 비었으면 {@code /}
     */
    public static String maskPath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return "/";
        }
        String[] segments = rawPath.split("/", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                out.append('/');
            }
            String segment = segments[i];
            if (!segment.isEmpty()) {
                out.append(keep(segment) ? segment : MASK);
            }
        }
        return out.toString();
    }

    private static boolean keep(String segment) {
        return segment.length() <= MAX_SEGMENT_LENGTH
                && NAME_LIKE.matcher(segment).matches()
                && !HEX_LIKE.matcher(segment).matches();
    }
}
