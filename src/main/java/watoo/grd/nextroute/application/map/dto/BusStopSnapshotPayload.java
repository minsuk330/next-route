package watoo.grd.nextroute.application.map.dto;

/**
 * 직렬화까지 끝낸 스냅샷 응답.
 *
 * <p>DTO 가 아니라 바이트를 들고 있는 이유: 페이로드가 정적인데 요청마다 1.3만 행을 다시
 * 직렬화하는 것이 이 기능에서 가장 비싼 낭비다. 한 번 만들어 재사용한다.
 *
 * @param version {@code "<정류장 수>-<max(updated_at) epoch초>"}
 * @param json    응답 본문 (JSON UTF-8)
 */
public record BusStopSnapshotPayload(String version, byte[] json) {
}
