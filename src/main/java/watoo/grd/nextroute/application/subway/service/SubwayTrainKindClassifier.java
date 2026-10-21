package watoo.grd.nextroute.application.subway.service;

import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.subway.dto.SubwayExpressType;
import watoo.grd.nextroute.application.subway.dto.TrainKind;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 실시간 열차 하나의 종류(subway-express PR-S1). 규칙은 위에서부터 처음 맞는 것:
 * <ol>
 *   <li>공항철도(1065) {@code A1001–A1056} → {@link TrainKind#DIRECT} — 실시간은 직통을 '급행'으로 준다</li>
 *   <li>{@code trainType} 에 "ITX" 또는 원시 {@code directAt} 9 → {@link TrainKind#ITX}</li>
 *   <li>카탈로그 {@code allStopTrains}(급행 표시지만 모든 역 정차, 1호선 용산↔구로 '구로 급행' 1401–1416) →
 *       {@link TrainKind#NORMAL}. 실시간이 '급행'으로 줘도 일반으로 본다 — 서는 역이 일반과 같아 급행 배지가 틀린 안내다.
 *       이 단계가 '급행' 보다 먼저여야 한다</li>
 *   <li>{@code trainType} 에 "급행"·"특급" 또는 {@code directAt} 1·7 → {@link TrainKind#EXPRESS}</li>
 *   <li>카탈로그가 번호만으로 급행이라 한 열차 → {@link TrainKind#EXPRESS}. 4호선 K44xx 는 실시간 trainType·directAt 이
 *       '일반'으로 오고 시간표 API 도 EXPRESS_YN=G 다(2026-10-21 실측). 지나치는 역 시간표에 아예 없는 것만이 근거라
 *       번호를 믿는다. 이 단계가 '일반' 보다 먼저여야 한다</li>
 *   <li>{@code trainType} "일반" 또는 {@code directAt} 0 → {@link TrainKind#NORMAL}</li>
 *   <li>그 밖 → {@link TrainKind#UNKNOWN}. 일반으로 승격하지 않는다</li>
 * </ol>
 *
 * <p>{@link SubwayExpressType} 는 9(ITX)를 {@code UNKNOWN} 으로 접는다(롤백 안전 때문에 바꾸지 않는다). ITX 를 directAt 로
 * 알려면 원시 코드를 받는 {@link #classifyRaw} 를 쓴다.
 */
@Component
public class SubwayTrainKindClassifier {

    static final String AIRPORT_LINE_ID = "1065";
    static final int DIRECT_FIRST = 1001;
    static final int DIRECT_LAST = 1056;

    private static final Pattern AIRPORT_NO = Pattern.compile("A(\\d+)");

    private final SubwayExpressCatalog catalog;

    public SubwayTrainKindClassifier(SubwayExpressCatalog catalog) {
        this.catalog = catalog;
    }

    /** @param directAt 실시간 {@code directAt} 를 옮긴 값. 9(ITX)는 여기서 알 수 없다 */
    public TrainKind classify(String lineId, String trainNo, String trainType, SubwayExpressType directAt) {
        return decide(lineId, trainNo, trainType, null, directAt);
    }

    /** @param directAtRaw 원시 {@code directAt} 코드({@code "0"}, {@code "1"}, {@code "7"}, {@code "9"}) */
    public TrainKind classifyRaw(String lineId, String trainNo, String trainType, String directAtRaw) {
        String raw = directAtRaw == null ? null : directAtRaw.trim();
        return decide(lineId, trainNo, trainType, raw, SubwayExpressType.from(raw));
    }

    private TrainKind decide(String lineId, String trainNo, String trainType, String raw, SubwayExpressType directAt) {
        if (isAirportDirect(lineId, trainNo)) {
            return TrainKind.DIRECT;
        }
        String type = trainType == null ? "" : trainType.trim();
        if (type.toUpperCase().contains("ITX") || "9".equals(raw)) {
            return TrainKind.ITX;
        }
        if (catalog != null && catalog.isAllStopTrain(lineId, trainNo)) {
            return TrainKind.NORMAL;
        }
        if (type.contains("급행") || type.contains("특급")
                || directAt == SubwayExpressType.EXPRESS || directAt == SubwayExpressType.SPECIAL) {
            return TrainKind.EXPRESS;
        }
        if (catalog != null && catalog.isTrainNoAuthoritativeExpress(lineId, trainNo)) {
            return TrainKind.EXPRESS;
        }
        if ("일반".equals(type) || directAt == SubwayExpressType.NORMAL) {
            return TrainKind.NORMAL;
        }
        return TrainKind.UNKNOWN;
    }

    /** 공항철도 직통 번호인가({@code A1001–A1056}). */
    static boolean isAirportDirect(String lineId, String trainNo) {
        if (!AIRPORT_LINE_ID.equals(lineId)) {
            return false;
        }
        String no = SubwayTrainIdentity.catalogNumber(trainNo);
        if (no == null) {
            return false;
        }
        Matcher m = AIRPORT_NO.matcher(no);
        if (!m.matches() || m.group(1).length() > 6) {
            return false;
        }
        int n = Integer.parseInt(m.group(1));
        return DIRECT_FIRST <= n && n <= DIRECT_LAST;
    }
}
