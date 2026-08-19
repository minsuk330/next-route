package watoo.grd.nextroute.domain.bus.repository;

/**
 * 스냅샷 버전 계산용 집계 한 행.
 *
 * <p>epoch 변환을 SQL 에서 끝낸다 — {@code updated_at} 은 {@code timestamp without time zone} 이라
 * Java 시각 객체로 꺼내면 존 해석이 끼어들고 드라이버 매핑도 흔들린다.
 */
public interface BusStopSnapshotVersionProjection {
    long getStopCount();

    /** {@code max(updated_at)} 의 epoch 초. 표가 비어 있으면 0. */
    long getMaxUpdatedEpochSec();
}
