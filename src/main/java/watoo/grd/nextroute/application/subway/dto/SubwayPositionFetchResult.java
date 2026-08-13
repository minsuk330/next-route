package watoo.grd.nextroute.application.subway.dto;

import java.time.Instant;
import java.util.List;

/**
 * 노선 하나를 물어본 결과. 목록이 아니라 봉투로 돌려주는 이유가 있다.
 *
 * <p>빈 목록만으로는 <b>"정상인데 운행 중인 열차가 없다"</b>와 <b>"호출이 실패했다"</b>를 가를 수
 * 없다. 이 둘을 섞으면 새벽에 열차가 없는 것과 API 장애가 같은 모양이 되고, 전이 추적기는
 * 멀쩡한 열차가 통째로 사라졌다고 읽는다.
 *
 * @param lineId     내부 line_id
 * @param rows       위치 행. {@link Status#FAILED} 면 비어 있다
 * @param totalCount API 가 말한 전체 건수({@code errorMessage.total}). 없으면 null
 * @param status     이 응답을 얼마나 믿을 수 있는지
 * @param fetchedAt  우리가 받은 시각. <b>신선도 판단에만 쓴다</b> — 사건 시각이 아니다
 * @param errorCode  서울 API 결과 코드. 성공이면 null
 */
public record SubwayPositionFetchResult(
        String lineId,
        List<SubwayPositionInfo> rows,
        Integer totalCount,
        Status status,
        Instant fetchedAt,
        String errorCode
) {

    public enum Status {
        /** 정상이고 응답이 완결됐다. 전이를 만들어도 되는 유일한 상태. */
        COMPLETE,
        /** 응답은 왔지만 완결되지 않았다(건수 불일치 등). 전이를 만들지 않는다. */
        PARTIAL,
        /** 호출 자체가 실패했다. */
        FAILED
    }

    public static SubwayPositionFetchResult failed(String lineId, Instant fetchedAt, String errorCode) {
        return new SubwayPositionFetchResult(lineId, List.of(), null, Status.FAILED, fetchedAt, errorCode);
    }

    public SubwayPositionFetchResult {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public boolean isComplete() {
        return status == Status.COMPLETE;
    }
}
