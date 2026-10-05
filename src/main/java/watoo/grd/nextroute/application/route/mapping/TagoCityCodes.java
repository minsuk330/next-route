package watoo.grd.nextroute.application.route.mapping;

import java.util.Map;
import java.util.Optional;

/**
 * OTP TAGO id 접두사(3글자) → 국토교통부 TAGO 버스도착 API {@code cityCode}.
 *
 * <p>2026-10-05 실측: 접두사마다 노선이 가장 많은 정류장 2곳에 도시코드 138개를 전부 넣어 보고,
 * 도착 정보가 나온 코드만 남겼다. 47개 접두사 중 1:1 로 확정된 38개만 둔다.
 * 빠진 것: 서울(SEB)·경기(GGB)는 TAGO 범위 밖(각각 서울 API·GBIS),
 * TSB(농어촌 — 접두사 하나가 여러 시군)와 BEB·CGB·CNB·GJB·JEB·MPB·MYB·OCB(미확정)는 후속 실측.
 * 표에 없으면 id 를 채우지 않는다 — 틀린 도시코드로 빈 결과를 내느니 호출하지 않는 편이 낫다.
 */
public final class TagoCityCodes {

    private static final Map<String, String> CITY_CODES = Map.ofEntries(
            Map.entry("ADB", "37040"),   // 안동
            Map.entry("ASB", "34040"),   // 아산
            Map.entry("BSB", "21"),      // 부산
            Map.entry("CAB", "34010"),   // 천안
            Map.entry("CHB", "33020"),   // 충주
            Map.entry("CJB", "33010"),   // 청주
            Map.entry("CWB", "38010"),   // 창원
            Map.entry("DGB", "22"),      // 대구
            Map.entry("DJB", "25"),      // 대전/계룡
            Map.entry("GCB", "37030"),   // 김천
            Map.entry("GHB", "38070"),   // 김해
            Map.entry("GMB", "37050"),   // 구미
            Map.entry("GOB", "34020"),   // 공주
            Map.entry("GRB", "34070"),   // 계룡
            Map.entry("GSB", "36320"),   // 곡성
            Map.entry("GUB", "35020"),   // 군산
            Map.entry("GYB", "37100"),   // 경산
            Map.entry("HPB", "36430"),   // 함평
            Map.entry("ICB", "23"),      // 인천
            Map.entry("JCB", "33030"),   // 제천
            Map.entry("JJB", "38030"),   // 진주
            Map.entry("JUB", "35010"),   // 전주
            Map.entry("KJB", "24"),      // 광주
            Map.entry("KUB", "37020"),   // 경주
            Map.entry("KYB", "36060"),   // 광양
            Map.entry("NJB", "36040"),   // 나주
            Map.entry("NWB", "35050"),   // 남원
            Map.entry("PHB", "37010"),   // 포항
            Map.entry("SAB", "38060"),   // 사천
            Map.entry("SCB", "36030"),   // 순천
            Map.entry("SJB", "12"),      // 세종
            Map.entry("SSB", "34050"),   // 서산
            Map.entry("USB", "26"),      // 울산
            Map.entry("WJB", "32020"),   // 원주/횡성
            Map.entry("YCB", "37070"),   // 영천
            Map.entry("YDB", "37350"),   // 영덕
            Map.entry("YEB", "36020"),   // 여수
            Map.entry("YSB", "38100")    // 양산
    );

    private TagoCityCodes() {
    }

    public static Optional<String> cityCodeOf(String prefix) {
        return prefix == null ? Optional.empty() : Optional.ofNullable(CITY_CODES.get(prefix));
    }

    /** {@code BSB167850202} 처럼 접두사가 붙은 TAGO id 의 도시코드. 모양이 다르거나 표 밖이면 empty. */
    public static Optional<String> cityCodeOfId(String tagoId) {
        if (tagoId == null || !tagoId.matches("^[A-Z]{3}\\d+$")) {
            return Optional.empty();
        }
        return cityCodeOf(tagoId.substring(0, 3));
    }
}
