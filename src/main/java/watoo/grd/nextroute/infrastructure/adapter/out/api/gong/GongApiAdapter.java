package watoo.grd.nextroute.infrastructure.adapter.out.api.gong;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
import watoo.grd.nextroute.application.subway.service.HolidayCalendar;
import watoo.grd.nextroute.application.subway.service.KoreanPublicHolidayCalendar;
import watoo.grd.nextroute.infrastructure.adapter.out.api.gong.dto.GongRestDeResponse;
import watoo.grd.nextroute.infrastructure.adapter.out.api.gong.dto.GongRestDeResponse.Item;

@Slf4j
@Component
@RequiredArgsConstructor
public class GongApiAdapter implements HolidayCalendar {

    private static final String BASE_URL =
        "http://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService/getRestDeInfo";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Value("${gong.api.key}")
    private String apiKey;

    private final RestTemplate restTemplate;

    // 연도별 공휴일 캐시 (애플리케이션 재시작 전까지 유지)
    private final Map<Integer, Set<LocalDate>> cache = new ConcurrentHashMap<>();

    /// 일반 객체 주입
    private final KoreanPublicHolidayCalendar fallback = new KoreanPublicHolidayCalendar();

    @Override
    public boolean isHoliday(LocalDate date) {
        int year = date.getYear();
        Set<LocalDate> holidays = cache.computeIfAbsent(year, this::fetchYear);
        if (holidays == null) {
            return fallback.isHoliday(date);
        }
        return holidays.contains(date);
    }

    private Set<LocalDate> fetchYear(int year) {
        Set<LocalDate> result = new HashSet<>();
        try {
            for (int month = 1; month <= 12; month++) {
                result.addAll(fetchMonth(year, month));
            }
            log.info("[Gong] 공휴일 로드 완료: {}년 {}건", year, result.size());
            return result;
        } catch (Exception e) {
            log.warn("[Gong] {}년 공휴일 API 실패, 하드코딩 폴백 사용: {}", year, e.getMessage());
            return null; // computeIfAbsent에서 null 반환 시 캐시 미저장 → 다음 호출에서 재시도
        }
    }

    private Set<LocalDate> fetchMonth(int year, int month) {
        URI uri = UriComponentsBuilder.fromUriString(BASE_URL)
            .queryParam("serviceKey", apiKey)
            .queryParam("solYear", year)
            .queryParam("solMonth", String.format("%02d", month))
            .queryParam("numOfRows", 50)
            .queryParam("pageNo", 1)
            .build(true)
            .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_XML));
        GongRestDeResponse response = restTemplate.exchange(
            uri, HttpMethod.GET, new HttpEntity<>(headers), GongRestDeResponse.class).getBody();

        if (response == null) {
            log.warn("[Gong] {}/{} response NULL → raw={}", year, month, fetchRaw(uri));
            return Set.of();
        }
        if (response.getHeader() == null) {
            log.warn("[Gong] {}/{} header NULL (XML 구조 불일치/인증 실패 의심) → raw={}",
                year, month, fetchRaw(uri));
            return Set.of();
        }
        if (!response.isSuccess()) {
            log.warn("[Gong] {}/{} resultCode={} msg={}", year, month,
                response.getHeader().getResultCode(),
                response.getHeader().getResultMsg());
            return Set.of();
        }

        log.debug("[Gong] {}/{} totalCount={}, items={}", year, month,
            response.getBody() != null ? response.getBody().getTotalCount() : -1,
            response.getItems().size());

        Set<LocalDate> dates = new HashSet<>();
        for (Item item : response.getItems()) {
            if ("Y".equals(item.getIsHoliday()) && item.getLocdate() != null) {
                dates.add(LocalDate.parse(item.getLocdate(), DATE_FMT));
            }
        }
        return dates;
    }

    private String fetchRaw(URI uri) {
        try {
            String raw = restTemplate.getForObject(uri, String.class);
            if (raw == null) return "(null)";
            return raw.length() > 500 ? raw.substring(0, 500) + "..." : raw;
        } catch (Exception e) {
            return "(raw fetch failed: " + e.getMessage() + ")";
        }
    }
}
