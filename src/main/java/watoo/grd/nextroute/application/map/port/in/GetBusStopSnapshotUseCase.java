package watoo.grd.nextroute.application.map.port.in;

import watoo.grd.nextroute.application.map.dto.BusStopSnapshotPayload;

public interface GetBusStopSnapshotUseCase {

    /**
     * 현재 스냅샷 버전만 계산한다. 본문을 만들지 않는다 — 304 판단이 목적이다.
     *
     * <p>이 메서드가 본문 생성을 건드리면 기능 전체의 의미가 없어진다.
     */
    String currentVersion();

    /** 버전 + 직렬화된 본문. 버전이 그대로면 캐시된 바이트를 그대로 준다. */
    BusStopSnapshotPayload snapshot();
}
