package watoo.grd.nextroute.application.journey.dto;

/**
 * 여정 시작 요청.
 *
 * <p>경로 JSON 을 통째로 받지 않고 두 값만 받는다. 검색 응답을 만든 것이 서버이므로, 서버가
 * 잠깐 보관해 둔 원본을 다시 꺼내 쓰는 편이 안전하다 — 앱이 되돌려 보낸 JSON 은 중간에
 * 바뀌었을 수도 있고, 지하철에서 올려 보내기엔 크다.
 *
 * @param searchSessionId 검색 응답이 준 스냅샷 식별자
 * @param pathKey         그 검색 결과 안에서 고른 경로
 * @param replace         진행 중인 여정이 있어도 갈아탈지. 기본은 갈아타지 않는다
 */
public record StartJourneyCommand(String searchSessionId, String pathKey, boolean replace) {
}
