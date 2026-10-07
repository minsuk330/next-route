package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitPattern;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitPatternDetail;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitStopTime;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitTripRef;
import watoo.grd.nextroute.infrastructure.adapter.out.api.otp.OtpTransitPatternAdapter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/** 2026-10-07 운영 OTP 실측 응답({@code otp/track/}) → 포트 값. 어댑터의 순수 파서를 그대로 쓴다. */
final class TrackFixtures {

    /** 2호선 내선 ::10 — 87역, 강남(1:RS_ACC1_S-1-0222)을 31·74 번째에서 두 번 지난다. */
    static final String LINE2_PATTERN = "1:RR_ACC1_S-1-02-1I::10";
    static final String GANGNAM = "1:RS_ACC1_S-1-0222";
    static final String BUS640_PATTERN = "1:BR_TAGO_SEB100100093::01";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TrackFixtures() {
    }

    static JsonNode json(String name) {
        try {
            return MAPPER.readTree(new ClassPathResource("otp/track/" + name).getContentAsByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static TransitPatternDetail line2Detail() {
        return OtpTransitPatternAdapter.parsePatternDetail(json("pattern-detail-subway2.json")).orElseThrow();
    }

    static List<TransitStopTime> line2Times() {
        return OtpTransitPatternAdapter.parseTripStopTimes(json("trip-stoptimes-subway2.json"));
    }

    static List<TransitTripRef> line2Trips() {
        return OtpTransitPatternAdapter.parseTripsForDate(json("trips-for-date-subway2.json"));
    }

    static TransitPatternDetail bus640Detail() {
        return OtpTransitPatternAdapter.parsePatternDetail(json("pattern-detail-bus.json")).orElseThrow();
    }

    static List<TransitStopTime> bus640Times() {
        return OtpTransitPatternAdapter.parseTripStopTimes(json("trip-stoptimes-bus.json"));
    }

    static List<TransitTripRef> bus640Trips() {
        return OtpTransitPatternAdapter.parseTripsForDate(json("trips-for-date-bus.json"));
    }

    static List<TransitPattern> busStopPatterns() {
        return OtpTransitPatternAdapter.parsePatternsAtStop(json("patterns-at-stop-bus.json"));
    }

    /** 상세에서 정류장 패턴(가벼운 모양)을 만든다 — 같은 그래프에서 온 두 응답처럼. */
    static TransitPattern lightOf(TransitPatternDetail detail, int tripCount) {
        return new TransitPattern(detail.code(), null, detail.route(),
                detail.stops().stream()
                        .map(s -> new watoo.grd.nextroute.application.route.port.out.TransitPatternPort.PatternStop(
                                s.gtfsId(), s.name()))
                        .toList(),
                tripCount);
    }
}
