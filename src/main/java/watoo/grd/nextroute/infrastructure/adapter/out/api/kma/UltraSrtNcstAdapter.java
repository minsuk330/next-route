package watoo.grd.nextroute.infrastructure.adapter.out.api.kma;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import watoo.grd.nextroute.application.route.config.KmaWeatherProperties;
import watoo.grd.nextroute.application.route.port.out.WeatherPort;
import watoo.grd.nextroute.infrastructure.adapter.out.api.kma.dto.KmaNcstResponse;

/**
 * 기상청 초단기실황(getUltraSrtNcst) 어댑터.
 * <p>
 * base_time = 매시 정시 생성·HH:10 제공 → now가 HH:10 이전이면 직전 시각을 쓴다.
 * 키는 data.go.kr 공용(${gong.api.key}, 이미 URL-encoded → 재인코딩 금지, build(true)).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UltraSrtNcstAdapter implements WeatherPort {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter BASE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter BASE_TIME = DateTimeFormatter.ofPattern("HHmm");
    // 초단기실황 PTY: 0없음 1비 2비/눈 3눈 4소나기 5빗방울 6빗방울눈날림 7눈날림
    private static final Set<Integer> RAIN_PTY = Set.of(1, 4, 5);
    private static final Set<Integer> SNOW_PTY = Set.of(2, 3, 6, 7);

    @Value("${gong.api.key}")
    private String apiKey;

    private final RestTemplate restTemplate;
    private final KmaWeatherProperties props;

    @Override
    public Optional<WeatherFeatures> fetchCurrent() {
        if (!props.isEnabled()) return Optional.empty();
        try {
            LocalDateTime base = latestBase(LocalDateTime.now(SEOUL));
            URI uri = UriComponentsBuilder.fromUriString(props.getBaseUrl())
                    .queryParam("serviceKey", apiKey)
                    .queryParam("pageNo", 1)
                    .queryParam("numOfRows", 100)
                    .queryParam("dataType", "JSON")
                    .queryParam("base_date", base.format(BASE_DATE))
                    .queryParam("base_time", base.format(BASE_TIME))
                    .queryParam("nx", props.getNx())
                    .queryParam("ny", props.getNy())
                    .build(true)
                    .toUri();

            HttpHeaders headers = new HttpHeaders();
            headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
            KmaNcstResponse body = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(headers), KmaNcstResponse.class).getBody();

            if (body == null || !body.isSuccess()) {
                log.warn("[KMA] 초단기실황 실패: code={}",
                        body != null && body.getResponse() != null && body.getResponse().getHeader() != null
                                ? body.getResponse().getHeader().getResultCode() : "null");
                return Optional.empty();
            }

            Map<String, String> obs = new HashMap<>();
            for (KmaNcstResponse.Item it : body.items()) {
                if (it.getCategory() != null) obs.put(it.getCategory(), it.getObsrValue());
            }
            return Optional.of(toFeatures(obs));
        } catch (Exception e) {
            log.warn("[KMA] 초단기실황 조회 예외: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** HH:10 이전이면 직전 시각(자정 넘어가면 전날). 분/초/나노 0으로 정규화. */
    static LocalDateTime latestBase(LocalDateTime now) {
        LocalDateTime hour = now.withMinute(0).withSecond(0).withNano(0);
        return now.getMinute() < 10 ? hour.minusHours(1) : hour;
    }

    private WeatherFeatures toFeatures(Map<String, String> obs) {
        Double temp = parseNumber(obs.get("T1H"));
        Double precip = parsePrecip(obs.get("RN1"));
        Double humidity = parseNumber(obs.get("REH"));
        Double wind = parseNumber(obs.get("WSD"));
        Integer pty = parsePty(obs.get("PTY"));
        Integer isRaining = pty == null ? null : (RAIN_PTY.contains(pty) ? 1 : 0);
        Integer isSnowing = pty == null ? null : (SNOW_PTY.contains(pty) ? 1 : 0);
        return new WeatherFeatures(temp, precip, humidity, wind, isRaining, isSnowing);
    }

    private static Double parseNumber(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty()) return null;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** RN1: '강수없음'/null/공백/비수치 → 0.0(없음). 그 외 수치 mm. */
    private static Double parsePrecip(String raw) {
        if (raw == null) return 0.0;
        String v = raw.trim();
        if (v.isEmpty() || v.equals("강수없음")) return 0.0;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static Integer parsePty(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty()) return null;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
