package watoo.grd.nextroute.infrastructure.adapter.out.api.gong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import watoo.grd.nextroute.infrastructure.adapter.out.api.gong.dto.GongRestDeResponse;
import watoo.grd.nextroute.infrastructure.adapter.out.api.gong.dto.GongRestDeResponse.Body;
import watoo.grd.nextroute.infrastructure.adapter.out.api.gong.dto.GongRestDeResponse.Header;
import watoo.grd.nextroute.infrastructure.adapter.out.api.gong.dto.GongRestDeResponse.Item;
import watoo.grd.nextroute.infrastructure.adapter.out.api.gong.dto.GongRestDeResponse.Items;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GongApiAdapterTest {

    @Mock
    RestTemplate restTemplate;

    GongApiAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new GongApiAdapter(restTemplate);
        ReflectionTestUtils.setField(adapter, "apiKey", "test-key");
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────

    private GongRestDeResponse successResponse(String... locdates) {
        List<Item> items = List.of(locdates).stream().map(d -> {
            Item item = new Item();
            item.setLocdate(d);
            item.setIsHoliday("Y");
            item.setDateName("공휴일");
            return item;
        }).toList();

        Items wrapper = new Items();
        wrapper.setItem(items);

        Body body = new Body();
        body.setItems(wrapper);
        body.setTotalCount(items.size());

        Header header = new Header();
        header.setResultCode("00");
        header.setResultMsg("OK");

        GongRestDeResponse response = new GongRestDeResponse();
        response.setHeader(header);
        response.setBody(body);
        return response;
    }

    private GongRestDeResponse emptyResponse() {
        Items wrapper = new Items();
        wrapper.setItem(List.of());

        Body body = new Body();
        body.setItems(wrapper);
        body.setTotalCount(0);

        Header header = new Header();
        header.setResultCode("00");
        header.setResultMsg("OK");

        GongRestDeResponse response = new GongRestDeResponse();
        response.setHeader(header);
        response.setBody(body);
        return response;
    }

    private void stubExchange(GongRestDeResponse response) {
        when(restTemplate.exchange(
            any(URI.class),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(GongRestDeResponse.class)))
            .thenReturn(ResponseEntity.ok(response));
    }

    // ── 단위 테스트 ───────────────────────────────────────────────────

    @Test
    void TC_isHoliday_Y인_날짜는_true를_반환한다() {
        // 1월만 공휴일(신정), 나머지는 빈 응답
        stubExchange(emptyResponse());
        when(restTemplate.exchange(
            argThat(uri -> uri != null && uri.toString().contains("solMonth=01")),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(GongRestDeResponse.class)))
            .thenReturn(ResponseEntity.ok(successResponse("20260101")));

        assertThat(adapter.isHoliday(LocalDate.of(2026, 1, 1))).isTrue();
    }

    @Test
    void TC_isHoliday_Y가_아닌_날짜는_false를_반환한다() {
        stubExchange(emptyResponse());

        assertThat(adapter.isHoliday(LocalDate.of(2026, 1, 2))).isFalse();
    }

    @Test
    void TC_같은_연도는_API를_한_번만_호출한다() {
        stubExchange(emptyResponse());

        adapter.isHoliday(LocalDate.of(2026, 1, 1));
        adapter.isHoliday(LocalDate.of(2026, 6, 6));
        adapter.isHoliday(LocalDate.of(2026, 12, 25));

        // 2026년 12개월 → API 12번만 호출 (연도 캐시 후 추가 호출 없음)
        verify(restTemplate, times(12)).exchange(
            any(URI.class),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(GongRestDeResponse.class));
    }

    @Test
    void TC_다른_연도는_각각_API를_호출한다() {
        stubExchange(emptyResponse());

        adapter.isHoliday(LocalDate.of(2025, 1, 1));
        adapter.isHoliday(LocalDate.of(2026, 1, 1));

        // 2025년 12번 + 2026년 12번 = 24번
        verify(restTemplate, times(24)).exchange(
            any(URI.class),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(GongRestDeResponse.class));
    }

    @Test
    void TC_API_실패시_하드코딩_폴백으로_대체된다() {
        when(restTemplate.exchange(
            any(URI.class),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(GongRestDeResponse.class)))
            .thenThrow(new RuntimeException("network error"));

        // 2026-01-01(신정)은 KoreanPublicHolidayCalendar 폴백에서 true여야 함
        assertThat(adapter.isHoliday(LocalDate.of(2026, 1, 1))).isTrue();
        // 2026-01-02는 평일 → false
        assertThat(adapter.isHoliday(LocalDate.of(2026, 1, 2))).isFalse();
    }

    @Test
    void TC_isHoliday_N인_항목은_false를_반환한다() {
        // isHoliday=N 인 기념일(비공휴일)이 응답에 포함된 경우
        Item nonHoliday = new Item();
        nonHoliday.setLocdate("20260301");
        nonHoliday.setIsHoliday("N");
        nonHoliday.setDateName("3·1운동 기념일");

        Items wrapper = new Items();
        wrapper.setItem(List.of(nonHoliday));
        Body body = new Body();
        body.setItems(wrapper);
        Header header = new Header();
        header.setResultCode("00");
        GongRestDeResponse response = new GongRestDeResponse();
        response.setHeader(header);
        response.setBody(body);

        stubExchange(emptyResponse());
        when(restTemplate.exchange(
            argThat(uri -> uri != null && uri.toString().contains("solMonth=03")),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(GongRestDeResponse.class)))
            .thenReturn(ResponseEntity.ok(response));

        // isHoliday=N 이므로 false (삼일절은 고정 공휴일이지만 API가 N으로 반환한 경우 테스트)
        // 실제론 Y로 오지만, 파싱 로직 검증용
        assertThat(adapter.isHoliday(LocalDate.of(2026, 3, 1))).isFalse();
    }

    // ── 실제 API 호출 테스트 (GONG_API 환경변수 필요) ──────────────────

    @Test
    // 키는 환경변수에서만 읽는다(저장소에 키를 두지 않는다 — 2026-11-28 공개 준비 때 재발급).
    // 40자 미만(CI 의 더미 값 등)이면 건너뛴다.
    @EnabledIfEnvironmentVariable(named = "GONG_API", matches = ".{40,}")
    void integration_실제_API_2026년_공휴일_조회() {
        // 실제 RestTemplate으로 교체
        org.springframework.boot.web.client.RestTemplateBuilder builder =
            new org.springframework.boot.web.client.RestTemplateBuilder();
        RestTemplate realTemplate = builder.build();

        GongApiAdapter realAdapter = new GongApiAdapter(realTemplate);
        ReflectionTestUtils.setField(realAdapter, "apiKey", System.getenv("GONG_API"));

        // 신정(1/1)은 공휴일
        assertThat(realAdapter.isHoliday(LocalDate.of(2026, 1, 1))).isTrue();
        // 평일
        assertThat(realAdapter.isHoliday(LocalDate.of(2026, 1, 2))).isFalse();
        // 어린이날(5/5) - 2026년 화요일
        assertThat(realAdapter.isHoliday(LocalDate.of(2026, 5, 5))).isTrue();
        // 크리스마스(12/25)
        assertThat(realAdapter.isHoliday(LocalDate.of(2026, 12, 25))).isTrue();
    }
}
