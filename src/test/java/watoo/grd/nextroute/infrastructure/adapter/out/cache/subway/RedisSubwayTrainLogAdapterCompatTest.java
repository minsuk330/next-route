package watoo.grd.nextroute.infrastructure.adapter.out.cache.subway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import watoo.grd.nextroute.application.subway.dto.SubwayExpressType;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainEvent;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainObservation;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainStatus;
import watoo.grd.nextroute.infrastructure.adapter.out.cache.subway.RedisSubwayTrainLogAdapter.ArrivalRow;
import watoo.grd.nextroute.infrastructure.adapter.out.cache.subway.RedisSubwayTrainLogAdapter.DepartureRow;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 위치·출발·도착 기록에 덧붙인 {@code directAt} 원문(subway-express PR-S3)의 롤백·혼재 호환.
 *
 * <p>Redis 기록은 배포 중·롤백 뒤에 옛 코드와 새 코드가 서로의 값을 읽는다. 옛 reader 는 이 시험에 원문 그대로 옮겨 둔다
 * ({@code main} 3b7db147 의 {@code RedisSubwayTrainLogAdapter}). {@code ObjectMapper} 는 운영과 같은 Spring Boot 기본값
 * ({@code FAIL_ON_UNKNOWN_PROPERTIES=false})이다.
 */
class RedisSubwayTrainLogAdapterCompatTest {

    private static final ObjectMapper SPRING = Jackson2ObjectMapperBuilder.json().build();
    private static final Instant AT = Instant.parse("2026-10-21T11:21:00Z");

    /** 옛 출발 줄(directAtRaw 없음). */
    record LegacyDepartureRow(String trainNo, long from, long to, String quality,
                              String stationName, String terminalId, String express) {
    }

    /** 옛 도착 줄(directAtRaw 없음). */
    record LegacyArrivalRow(String stationId, String stationName, long from, long to,
                            String terminalId, String express) {
    }

    /** 옛 직전 위치 reader 원문. */
    private static Optional<SubwayTrainObservation> legacyParseObservation(String raw) {
        String[] parts = raw.split("\\|", -1);
        if (parts.length < 6) {
            return Optional.empty();
        }
        try {
            return Optional.of(new SubwayTrainObservation(
                    parts[0].isEmpty() ? null : parts[0], parts[1].isEmpty() ? null : parts[1],
                    SubwayTrainStatus.valueOf(parts[2]),
                    Instant.ofEpochSecond(Long.parseLong(parts[3])),
                    parts[4].isEmpty() ? null : parts[4], SubwayExpressType.valueOf(parts[5])));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static SubwayTrainEvent itxDeparture() {
        return new SubwayTrainEvent(SubwayTrainEvent.Type.DEPARTED, "1067080117", "회기", AT.minusSeconds(60), AT,
                SubwayTrainEvent.Quality.CORROBORATED, "1067080140", SubwayExpressType.UNKNOWN, "9");
    }

    @Test
    @DisplayName("직전 위치: 새 값(일곱 칸)을 옛 reader 가 그대로 읽고, 옛 값(여섯 칸)을 새 reader 가 원문 null 로 읽는다")
    void observationBothWays() {
        SubwayTrainObservation itx = new SubwayTrainObservation("1067080117", "회기", SubwayTrainStatus.values()[0],
                AT, "1067080140", SubwayExpressType.UNKNOWN, "9");

        String written = RedisSubwayTrainLogAdapter.serialize(itx);
        assertThat(written.split("\\|", -1)).hasSize(7);
        assertThat(RedisSubwayTrainLogAdapter.parseObservation(written)).contains(itx);
        assertThat(legacyParseObservation(written)).contains(new SubwayTrainObservation("1067080117", "회기",
                SubwayTrainStatus.values()[0], AT, "1067080140", SubwayExpressType.UNKNOWN));

        String legacy = "1002000222|강남|" + SubwayTrainStatus.values()[0].name() + "|" + AT.getEpochSecond()
                + "|1002000211|NORMAL";
        SubwayTrainObservation read = RedisSubwayTrainLogAdapter.parseObservation(legacy).orElseThrow();
        assertThat(read.directAtRaw()).isNull();
        assertThat(read.express()).isEqualTo(SubwayExpressType.NORMAL);

        SubwayTrainObservation noRaw = new SubwayTrainObservation("a", "b", SubwayTrainStatus.values()[0], AT, null,
                SubwayExpressType.NORMAL);
        assertThat(RedisSubwayTrainLogAdapter.parseObservation(RedisSubwayTrainLogAdapter.serialize(noRaw)))
                .contains(noRaw);
    }

    @Test
    @DisplayName("출발 줄: 새 JSON 을 옛 record 가 읽는다(모르는 칸 무시, express 값 공간 그대로), 옛 JSON 은 원문 null")
    void departureRowBothWays() throws Exception {
        String written = SPRING.writeValueAsString(DepartureRow.of("2029", itxDeparture()));
        assertThat(written).contains("\"directAtRaw\":\"9\"").contains("\"express\":\"UNKNOWN\"");

        LegacyDepartureRow old = SPRING.readValue(written, LegacyDepartureRow.class);
        assertThat(old.trainNo()).isEqualTo("2029");
        assertThat(SubwayExpressType.valueOf(old.express())).isEqualTo(SubwayExpressType.UNKNOWN);

        SubwayTrainEvent roundTrip = SPRING.readValue(written, DepartureRow.class)
                .toEvent(SubwayTrainEvent.Type.DEPARTED, "1067080117");
        assertThat(roundTrip.directAtRaw()).isEqualTo("9");
        assertThat(roundTrip.terminalId()).isEqualTo("1067080140");

        String legacyJson = SPRING.writeValueAsString(new LegacyDepartureRow("2301", AT.getEpochSecond() - 60,
                AT.getEpochSecond(), "CORROBORATED", "강남", "1002000211", "NORMAL"));
        SubwayTrainEvent fromLegacy = SPRING.readValue(legacyJson, DepartureRow.class)
                .toEvent(SubwayTrainEvent.Type.DEPARTED, "1002000222");
        assertThat(fromLegacy.directAtRaw()).isNull();
        assertThat(fromLegacy.express()).isEqualTo(SubwayExpressType.NORMAL);
    }

    @Test
    @DisplayName("도착 줄: 출발 줄과 같은 호환")
    void arrivalRowBothWays() throws Exception {
        SubwayTrainEvent arrived = new SubwayTrainEvent(SubwayTrainEvent.Type.ARRIVED, "1067080118", "중랑",
                AT.minusSeconds(30), AT, SubwayTrainEvent.Quality.CORROBORATED, "1067080140",
                SubwayExpressType.UNKNOWN, "9");
        String written = SPRING.writeValueAsString(ArrivalRow.of(arrived));

        assertThat(SPRING.readValue(written, LegacyArrivalRow.class).express()).isEqualTo("UNKNOWN");
        assertThat(SPRING.readValue(written, ArrivalRow.class).toEvent().directAtRaw()).isEqualTo("9");

        String legacyJson = SPRING.writeValueAsString(new LegacyArrivalRow("1002000223", "역삼",
                AT.getEpochSecond() - 30, AT.getEpochSecond(), "1002000211", "NORMAL"));
        assertThat(SPRING.readValue(legacyJson, ArrivalRow.class).toEvent().directAtRaw()).isNull();
    }

    @Test
    @DisplayName("품질 승격(corroborated)도 원문을 잃지 않는다")
    void corroboratedKeepsRaw() {
        assertThat(itxDeparture().corroborated(AT.minusSeconds(30), AT).directAtRaw()).isEqualTo("9");
    }
}
