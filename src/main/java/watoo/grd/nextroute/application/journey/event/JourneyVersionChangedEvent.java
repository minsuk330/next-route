package watoo.grd.nextroute.application.journey.event;

import java.util.Collection;
import java.util.List;

/**
 * 조건부 조회(204)를 가르는 값 — 명령·평가 번호, 지금 단계(개정본·번호·종류), 소유자, 삭제 여부 — 이 바뀌었다.
 *
 * <p>버전 캐시({@code CachedJourneyVersionAdapter})가 이 사건을 커밋 <b>뒤</b>에 받아 DB 의 새 값으로 덮어쓴다.
 * 커밋 전에 덮으면 롤백된 변경이 캐시에만 남는다. 값을 바꾸는 자리는 반드시 같은 트랜잭션 안에서 이것을 낸다 —
 * 빠뜨리면 앱이 캐시 수명(30초)만큼 옛 화면을 본다. 바꾸는 자리 목록은 implementation_note.md(처방 4차) 에 있다.
 *
 * @param journeyIds 바뀐 여정들. 탈퇴처럼 한 번에 여러 여정이 바뀌는 경우가 있어 목록이다
 */
public record JourneyVersionChangedEvent(List<Long> journeyIds) {

    public JourneyVersionChangedEvent {
        journeyIds = List.copyOf(journeyIds);
    }

    /** 번호가 없으면(아직 저장 전 — 단위 시험의 가짜 저장소) 빈 사건이다. 덮을 칸이 없다. */
    public static JourneyVersionChangedEvent of(Long journeyId) {
        return new JourneyVersionChangedEvent(journeyId == null ? List.of() : List.of(journeyId));
    }

    public static JourneyVersionChangedEvent of(Collection<Long> journeyIds) {
        return new JourneyVersionChangedEvent(List.copyOf(journeyIds));
    }
}
