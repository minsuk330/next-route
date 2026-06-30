package watoo.grd.nextroute.infrastructure.adapter.out.api.kma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import watoo.grd.nextroute.application.route.config.KmaWeatherProperties;
import watoo.grd.nextroute.application.route.port.out.WeatherPort.WeatherFeatures;
import watoo.grd.nextroute.infrastructure.adapter.out.api.kma.dto.KmaNcstResponse;

class UltraSrtNcstAdapterTest {

    private final RestTemplate restTemplate = mock(RestTemplate.class);

    private KmaWeatherProperties props(boolean enabled) {
        KmaWeatherProperties p = new KmaWeatherProperties();
        p.setEnabled(enabled);
        return p;
    }

    private UltraSrtNcstAdapter adapter(KmaWeatherProperties props) {
        UltraSrtNcstAdapter a = new UltraSrtNcstAdapter(restTemplate, props);
        // @Value 필드는 테스트에서 직접 안 채워도 build(true) 시 null→예외 없이 진행되나,
        // 안전하게 reflection으로 더미 키 주입.
        org.springframework.test.util.ReflectionTestUtils.setField(a, "apiKey", "DUMMYKEY");
        return a;
    }

    private void stubResponse(KmaNcstResponse.Item... items) {
        KmaNcstResponse resp = new KmaNcstResponse();
        KmaNcstResponse.Response r = new KmaNcstResponse.Response();
        KmaNcstResponse.Header h = new KmaNcstResponse.Header();
        h.setResultCode("00");
        r.setHeader(h);
        KmaNcstResponse.Body b = new KmaNcstResponse.Body();
        KmaNcstResponse.Items it = new KmaNcstResponse.Items();
        List<KmaNcstResponse.Item> list = new ArrayList<>(List.of(items));
        it.setItem(list);
        b.setItems(it);
        r.setBody(b);
        resp.setResponse(r);
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET),
                any(HttpEntity.class), eq(KmaNcstResponse.class)))
                .thenReturn(ResponseEntity.ok(resp));
    }

    private static KmaNcstResponse.Item item(String category, String value) {
        KmaNcstResponse.Item i = new KmaNcstResponse.Item();
        i.setCategory(category);
        i.setObsrValue(value);
        return i;
    }

    @Test
    @DisplayName("PTY=3(눈) → is_snowing=1, is_raining=0, 관측값 매핑")
    void parsesSnow() {
        stubResponse(item("T1H", "-2.0"), item("RN1", "0"), item("REH", "70"),
                item("WSD", "2.1"), item("PTY", "3"));
        WeatherFeatures w = adapter(props(true)).fetchCurrent().orElseThrow();
        assertThat(w.temperatureC()).isEqualTo(-2.0);
        assertThat(w.precipitationMm()).isEqualTo(0.0);
        assertThat(w.humidityPct()).isEqualTo(70.0);
        assertThat(w.windSpeedMps()).isEqualTo(2.1);
        assertThat(w.isRaining()).isEqualTo(0);
        assertThat(w.isSnowing()).isEqualTo(1);
    }

    @Test
    @DisplayName("PTY=1(비) + RN1='강수없음' → is_raining=1, precip=0")
    void parsesRainAndPrecipGuard() {
        stubResponse(item("T1H", "12.0"), item("RN1", "강수없음"), item("PTY", "1"));
        WeatherFeatures w = adapter(props(true)).fetchCurrent().orElseThrow();
        assertThat(w.precipitationMm()).isEqualTo(0.0);
        assertThat(w.isRaining()).isEqualTo(1);
        assertThat(w.isSnowing()).isEqualTo(0);
    }

    @Test
    @DisplayName("enabled=false → 외부 호출 없이 empty")
    void disabledSkipsCall() {
        Optional<WeatherFeatures> w = adapter(props(false)).fetchCurrent();
        assertThat(w).isEmpty();
        verify(restTemplate, never()).exchange(any(URI.class), any(), any(), eq(KmaNcstResponse.class));
    }

    @Test
    @DisplayName("base_time: HH:10 이전이면 직전 시각(자정 넘어가면 전날)")
    void latestBaseBoundary() {
        assertThat(UltraSrtNcstAdapter.latestBase(LocalDateTime.of(2026, 1, 5, 14, 5)))
                .isEqualTo(LocalDateTime.of(2026, 1, 5, 13, 0));
        assertThat(UltraSrtNcstAdapter.latestBase(LocalDateTime.of(2026, 1, 5, 14, 15)))
                .isEqualTo(LocalDateTime.of(2026, 1, 5, 14, 0));
        assertThat(UltraSrtNcstAdapter.latestBase(LocalDateTime.of(2026, 1, 5, 0, 5)))
                .isEqualTo(LocalDateTime.of(2026, 1, 4, 23, 0));
    }
}
