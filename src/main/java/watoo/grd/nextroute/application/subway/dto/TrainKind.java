package watoo.grd.nextroute.application.subway.dto;

/**
 * 열차 종류(subway-express). 경로 검색과 실시간이 같이 쓰는 값이다.
 *
 * <p>{@link SubwayExpressType} 는 실시간 {@code directAt} 를 옮긴 값이라 그대로 둔다(Redis 기록이 {@code valueOf} 로
 * 읽으므로 상수를 늘리면 롤백 때 구버전이 깨진다). 이 enum 은 그 위에서 판정한 결과다 —
 * {@code SubwayTrainKindClassifier}.
 */
public enum TrainKind {

    NORMAL("일반"),
    EXPRESS("급행"),
    /** 공항철도 직통. 별도 승차권이라 경로에 넣지 않고 추적하지 않는다(D3). */
    DIRECT("직통"),
    /** ITX-청춘. 경로에는 남기고 추적하지 않는다(D4). */
    ITX("ITX-청춘"),
    /** 근거가 없다. 일반으로 승격하지 않는다 — 급행을 일반으로 추적하면 지나치는 역을 다음 역으로 센다. */
    UNKNOWN(null);

    private final String label;

    TrainKind(String label) {
        this.label = label;
    }

    /** 표시 이름. {@link #UNKNOWN} 은 보여 줄 이름이 없어 null 이다. */
    public String label() {
        return label;
    }
}
