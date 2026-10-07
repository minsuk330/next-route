package watoo.grd.nextroute.application.route.mapping;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OTP(GTFS) 버스 정류장 id → 실시간 도착 API({@code /api/arrivals/bus/{stopId}/...})가 받는 정류장 id.
 * 길찾기 응답의 {@code startLocalStationID} 와 같은 규칙이다.
 *
 * <ul>
 *   <li>서울·경기 {@code 1:BS_TAGO_SEB|GGB<9자리>} → 9자리</li>
 *   <li>그 밖 TAGO {@code 1:BS_TAGO_BSB167850202} → 접두사 포함({@code BSB167850202}), 그 접두사의 실시간을 물을 수 있을 때만
 *       ({@link TagoCityCodeLookup#isRealtimePrefix})</li>
 *   <li>나머지 → empty</li>
 * </ul>
 *
 * <p>정류장 하나만 보고 정한다(노선을 모른다). 서울 9자리도 우리 DB 존재 검증을 하지 않는다 — 표시·대조용이지
 * 서울 노선표 조회의 열쇠가 아니다.
 */
@Component
@RequiredArgsConstructor
public class BusRealtimeStopIds {

    private static final Pattern SEOUL_GYEONGGI = Pattern.compile("^\\d+:BS_TAGO_(?:SEB|GGB)(\\d{9})$");
    private static final Pattern TAGO = Pattern.compile("^\\d+:BS_TAGO_(([A-Z]{3})\\d+)$");

    private final TagoCityCodeLookup tagoCityCodeLookup;

    public Optional<String> of(String gtfsStopId) {
        if (gtfsStopId == null) {
            return Optional.empty();
        }
        Matcher sg = SEOUL_GYEONGGI.matcher(gtfsStopId);
        if (sg.matches()) {
            return Optional.of(sg.group(1));
        }
        Matcher tago = TAGO.matcher(gtfsStopId);
        if (tago.matches() && tagoCityCodeLookup.isRealtimePrefix(tago.group(2))) {
            return Optional.of(tago.group(1));
        }
        return Optional.empty();
    }
}
