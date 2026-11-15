package watoo.grd.nextroute.infrastructure.adapter.out.api.gong;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /** 실패한 연도를 다시 부르기까지 기다리는 시간. 그 사이엔 하드코딩 폴백(매 요청 12회 동기 호출 방지). */
    static final Duration RETRY_AFTER_FAILURE = Duration.ofHours(1);

    // 연도별 공휴일(날짜 → 이름) 캐시 (애플리케이션 재시작 전까지 유지)
    private final Map<Integer, Map<LocalDate, String>> cache = new ConcurrentHashMap<>();
    private final Map<Integer, Instant> failedAt = new ConcurrentHashMap<>();
    private final Map<Integer, Object> yearLocks = new ConcurrentHashMap<>();
    private Clock clock = Clock.systemUTC();

    /// 일반 객체 주입
    private final KoreanPublicHolidayCalendar fallback = new KoreanPublicHolidayCalendar();

    @Override
    public boolean isHoliday(LocalDate date) {
        Map<LocalDate, String> holidays = holidays(date.getYear());
        if (holidays == null) {
            return fallback.isHoliday(date);
        }
        return holidays.containsKey(date);
    }

    @Override
    public Optional<String> holidayName(LocalDate date) {
        Map<LocalDate, String> holidays = holidays(date.getYear());
        return holidays == null ? Optional.empty() : Optional.ofNullable(holidays.get(date));
    }

    /** null = 이번엔 API 를 못 믿는다(실패 직후 1시간 포함) → 폴백. 같은 해는 한 스레드만 부른다(콜드 스타트 동시 요청). */
    private Map<LocalDate, String> holidays(int year) {
        Map<LocalDate, String> cached = cache.get(year);
        if (cached != null) {
            return cached;
        }
        synchronized (yearLocks.computeIfAbsent(year, y -> new Object())) {
            return loadLocked(year);
        }
    }

    private Map<LocalDate, String> loadLocked(int year) {
        Map<LocalDate, String> cached = cache.get(year);
        if (cached != null) {
            return cached;
        }
        Instant failed = failedAt.get(year);
        if (failed != null && clock.instant().isBefore(failed.plus(RETRY_AFTER_FAILURE))) {
            return null;
        }
        Map<LocalDate, String> loaded = fetchYear(year);
        if (loaded == null) {
            failedAt.put(year, clock.instant());
            return null;
        }
        failedAt.remove(year);
        cache.put(year, loaded);
        return loaded;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    private Map<LocalDate, String> fetchYear(int year) {
        Map<LocalDate, String> result = new HashMap<>();
        try {
            for (int month = 1; month <= 12; month++) {
                result.putAll(fetchMonth(year, month));
            }
            log.info("[Gong] 공휴일 로드 완료: {}년 {}건", year, result.size());
            return Map.copyOf(result);
        } catch (Exception e) {
            log.warn("[Gong] {}년 공휴일 API 실패, {} 동안 하드코딩 폴백 사용: {}", year, RETRY_AFTER_FAILURE, e.getMessage());
            return null;
        }
    }

    /** 한 달이라도 정상 응답이 아니면 예외 → 그 해 전체를 폴백으로(빈 달을 "공휴일 없음"으로 캐시하지 않는다). */
    private Map<LocalDate, String> fetchMonth(int year, int month) {
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
            throw new IllegalStateException("gong response null " + year + "/" + month);
        }
        if (response.getHeader() == null) {
            log.warn("[Gong] {}/{} header NULL (XML 구조 불일치/인증 실패 의심) → raw={}",
                year, month, fetchRaw(uri));
            throw new IllegalStateException("gong header null " + year + "/" + month);
        }
        if (!response.isSuccess()) {
            log.warn("[Gong] {}/{} resultCode={} msg={}", year, month,
                response.getHeader().getResultCode(),
                response.getHeader().getResultMsg());
            throw new IllegalStateException("gong resultCode " + response.getHeader().getResultCode());
        }

        log.debug("[Gong] {}/{} totalCount={}, items={}", year, month,
            response.getBody() != null ? response.getBody().getTotalCount() : -1,
            response.getItems().size());

        Map<LocalDate, String> dates = new HashMap<>();
        for (Item item : response.getItems()) {
            if ("Y".equals(item.getIsHoliday()) && item.getLocdate() != null) {
                // 같은 날 두 이름(예 대체공휴일 겹침)은 먼저 온 이름. 이름 없으면 빈 문자열
                dates.putIfAbsent(LocalDate.parse(item.getLocdate(), DATE_FMT),
                    item.getDateName() == null ? "" : item.getDateName().trim());
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
