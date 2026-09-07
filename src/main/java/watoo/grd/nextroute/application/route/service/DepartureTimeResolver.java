package watoo.grd.nextroute.application.route.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.exception.InvalidDepartureTimeException;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * 요청 경계에서 출발시각을 한 번 확정한다.
 *
 * <ul>
 *   <li>생략(null/blank) → 주입 {@link Clock} 의 "지금".</li>
 *   <li>offset 포함 ISO-8601(예: {@code 2026-09-08T08:00:00+09:00}) → 그 {@link Instant}.</li>
 *   <li>형식 불량 또는 <b>offset 없음</b> → {@link InvalidDepartureTimeException}(입력 오류).</li>
 * </ul>
 *
 * <p>offset 을 요구하는 이유: offset 없는 로컬시각은 어느 시간대의 언제인지 불명확해 serviceDate·캐시 키가
 * 어긋난다. 시각 해석을 서버 시간대로 임의 보정하지 않는다.
 */
@Component
@RequiredArgsConstructor
public class DepartureTimeResolver {

    private final Clock clock;

    public Instant resolve(String departureAt) {
        if (departureAt == null || departureAt.isBlank()) {
            return clock.instant();
        }
        try {
            // OffsetDateTime.parse 는 offset 이 없으면 파싱 실패한다 → offset 강제.
            return OffsetDateTime.parse(departureAt.trim()).toInstant();
        } catch (DateTimeParseException e) {
            throw new InvalidDepartureTimeException(
                    "departureAt 은 offset 포함 ISO-8601 이어야 합니다(예: 2026-09-08T08:00:00+09:00): " + departureAt);
        }
    }
}
