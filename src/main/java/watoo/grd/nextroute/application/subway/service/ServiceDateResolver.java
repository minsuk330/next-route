package watoo.grd.nextroute.application.subway.service;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * 운행일을 정한다. 자정을 넘겨 다니는 열차는 <b>전날의 운행</b>이다.
 *
 * <p>경계는 04:00 이다. 이 값은 이 코드베이스가 이미 쓰던 규칙이라 새로 정한 것이 아니다 —
 * {@code SubwayArrivalEventDerivationService.deriveForDate} 가 하루를 04:00~다음날 04:00 으로
 * 자르고, {@code TimetableConverter.parseTime} 이 {@code hh >= 4} 로 당일·익일을 가른다.
 *
 * <p><b>왜 필요한가.</b> 열차번호는 전역 유일하지 않다. 자정 전후로 같은 번호가 다시 쓰이고,
 * 노선마다 겹치는 번호도 있다. 운행일이 identity 에 들어가야 어제 01:30 의 2390 과
 * 오늘 01:30 의 2390 이 한 열차로 뭉치지 않는다.
 */
@Component
public class ServiceDateResolver {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** 하루가 시작되는 시각. 이보다 이르면 전날 운행이다. */
    private static final LocalTime DAY_BOUNDARY = LocalTime.of(4, 0);

    public LocalDate resolve(Instant at) {
        return resolve(LocalDateTime.ofInstant(at, SEOUL));
    }

    public LocalDate resolve(LocalDateTime at) {
        return at.toLocalTime().isBefore(DAY_BOUNDARY)
                ? at.toLocalDate().minusDays(1)
                : at.toLocalDate();
    }
}
