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
    /** ITX-청춘이 다니는 노선(경춘). 카탈로그에 넣지 않는 종류라 따로 둔다. */
    static final String ITX_LINE_ID = "1067";
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

    /**
     * 열차 한 대를 본 근거 하나 — 도착 API 행 하나, 위치 기록 하나, 출발 기록 하나(subway-express PR-S3).
     *
     * @param trainType   도착 API {@code btrainSttus} 원문(일반·급행·특급·ITX). 없으면 null
     * @param directAtRaw 위치 API 원시 {@code directAt}. 없으면 null(옛 기록)
     * @param directAt    {@code directAt} 를 옮긴 enum. 원시 코드가 있으면 무시한다
     */
    public record Sighting(String trainType, String directAtRaw, SubwayExpressType directAt) {

        public static Sighting ofTrainType(String trainType) {
            return new Sighting(trainType, null, null);
        }

        public static Sighting ofDirectAt(SubwayExpressType directAt, String directAtRaw) {
            return new Sighting(null, directAtRaw, directAt);
        }
    }

    /**
     * <b>열차 단위</b> 판정(subway-express PR-S3). 도착 API {@code trainType} 은 열차가 아니라 <b>행마다</b> 다르다 —
     * 2026-10-21 운영 스냅숏 606편 중 13편이 행마다 '일반'/'급행'이 섞였고 전부 급행 열차였다(1호선 경인 11xx·A급행 19xx 의
     * 서울교통공사 구간·먼 예측 행, 4호선 4403). 그래서 같은 (노선, 원문 번호)의 근거를 모두 모아 한 번에 정한다.
     *
     * <p>규칙: 근거마다 위 단계로 판정하고 <b>DIRECT &gt; ITX &gt; EXPRESS &gt; NORMAL &gt; UNKNOWN</b> 중 가장 앞선 것을 고른다.
     * 즉 어느 한 행이라도 '급행'·'특급'(또는 directAt 1·7)이면 급행이다. 번호로 정하는 단계(직통·구로 급행·번호 권위 급행)는
     * 근거마다 같은 답이라 섞이지 않는다. 근거가 하나도 없으면 번호만으로 판정한다(번호 권위 급행이면 EXPRESS, 아니면 UNKNOWN).
     *
     * <p>반대 방향 오판(일반 열차의 한 행이 '급행')은 실측에서 0건이다. 그런 열차는 급행 해석이 실패해 '추적 불가'로 막힌다 —
     * 급행을 일반으로 추적하는 쪽(지나치는 역을 다음 역으로 셈)보다 안전하다.
     */
    public TrainKind classifyTrain(String lineId, String trainNo, java.util.Collection<Sighting> sightings) {
        if (sightings == null || sightings.isEmpty()) {
            return decide(lineId, trainNo, null, null, null);
        }
        TrainKind best = null;
        for (Sighting sighting : sightings) {
            if (sighting == null) {
                continue;
            }
            String raw = sighting.directAtRaw() == null || sighting.directAtRaw().isBlank()
                    ? null : sighting.directAtRaw().trim();
            SubwayExpressType directAt = raw != null ? SubwayExpressType.from(raw) : sighting.directAt();
            TrainKind kind = decide(lineId, trainNo, sighting.trainType(), raw, directAt);
            if (best == null || rank(kind) > rank(best)) {
                best = kind;
            }
        }
        return best == null ? decide(lineId, trainNo, null, null, null) : best;
    }

    /**
     * 이 노선엔 일반 열차만 다니는가 — 카탈로그 급행 서비스도 ITX-청춘도 없다. 공항철도 직통은 번호로 가려지므로 해당 없다.
     * 그런 노선의 {@link TrainKind#UNKNOWN}(원천이 종류를 비워 보냄)은 일반과 다를 수 없다.
     */
    public boolean onlyNormalTrains(String lineId) {
        if (lineId == null || ITX_LINE_ID.equals(lineId)) {
            return false;
        }
        return catalog == null || !catalog.hasServices(lineId);
    }

    /** 일반 열차로 다뤄도 되는가: {@link TrainKind#NORMAL}, 또는 일반만 다니는 노선의 {@link TrainKind#UNKNOWN}. */
    public boolean actsAsNormal(String lineId, TrainKind kind) {
        return kind == TrainKind.NORMAL || (kind == TrainKind.UNKNOWN && onlyNormalTrains(lineId));
    }

    /** 열차 단위로 모을 때의 우선순위. 높을수록 이긴다. */
    private static int rank(TrainKind kind) {
        return switch (kind) {
            case DIRECT -> 4;
            case ITX -> 3;
            case EXPRESS -> 2;
            case NORMAL -> 1;
            case UNKNOWN -> 0;
        };
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
