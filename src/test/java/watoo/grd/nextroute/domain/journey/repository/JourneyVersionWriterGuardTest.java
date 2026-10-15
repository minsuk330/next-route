package watoo.grd.nextroute.domain.journey.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 버전 캐시(#427 처방 4차) 가드 — 조건부 조회가 읽는 칸을 고치는 저장소 쓰기가 새로 생기면 실패한다.
 *
 * <p>캐시는 쓰는 자리가 {@code JourneyVersionChangedEvent} 를 내야만 최신이 된다. 빠뜨린 쓰기는 앱에 최대 30초 낡은
 * 204 를 준다 — 조용히 지나간다. 그래서 SET 절을 칸 이름으로 훑어, 알려진 자리(사건을 내는 것을 확인한 곳) 밖의
 * 쓰기를 잡는다. 새 쓰기를 넣었다면 부르는 쪽에서 사건을 내고 아래 목록에 더한다.
 *
 * <p>엔티티 변경(dirty checking)은 여기서 못 잡는다 — {@code JourneySession} 의 번호·단계 변경 메서드
 * ({@code bumpCommandVersion}·{@code bumpRouteRevision}·{@code moveToLeg})를 부르는 자리는 노트 좌표에 적었다.
 */
@DisplayName("버전 캐시 쓰기 가드")
class JourneyVersionWriterGuardTest {

    /** 조건부 조회가 읽는 칸(SQL·JPQL 이름 둘 다). SET 절 안에서 "칸 =" 모양만 본다. */
    private static final Pattern WATCHED = Pattern.compile(
            "(?i)[\\s,.](command_version|commandVersion|evaluation_version|evaluationVersion"
                    + "|current_leg_index|currentLegIndex|route_revision|routeRevision|deleted_at|deletedAt"
                    + "|user_id|userId|type)\\s*=");

    /** 사건을 내는 것을 확인한 쓰기(부르는 쪽 좌표는 implementation_note.md 처방 4차). */
    private static final Set<String> KNOWN = Set.of(
            "JourneySessionRepository.bumpEvaluationVersion",   // JourneyProgressService
            "JourneySessionRepository.finishEvaluation",        // JourneyEvaluationScheduleWriter
            "JourneySessionRepository.softDeleteByUserId",      // WithdrawService
            "JourneyLegRepository.softDeleteByUserId");         // WithdrawService (단계 종류를 가린다)

    @Test
    @DisplayName("버전 칸을 고치는 쓰기는 알려진 자리뿐이다")
    void onlyKnownWritersTouchVersionColumns() {
        Set<String> found = new TreeSet<>();
        for (Class<?> repo : new Class<?>[]{JourneySessionRepository.class, JourneyLegRepository.class}) {
            for (Method m : repo.getDeclaredMethods()) {
                Query q = m.getAnnotation(Query.class);
                if (q == null) {
                    continue;
                }
                for (String setClause : setClauses(q.value())) {
                    if (WATCHED.matcher(setClause).find()) {
                        found.add(repo.getSimpleName() + "." + m.getName());
                    }
                }
            }
        }
        assertThat(found)
                .as("새 쓰기가 버전 칸을 고친다면 부르는 쪽에서 JourneyVersionChangedEvent 를 내고 KNOWN 에 더한다")
                .isSubsetOf(KNOWN);
        // 가드가 아무것도 못 잡는 상태(정규식 고장)를 막는다.
        assertThat(found).contains("JourneySessionRepository.bumpEvaluationVersion",
                "JourneySessionRepository.finishEvaluation", "JourneySessionRepository.softDeleteByUserId");
    }

    /** "SET … (FROM|WHERE|RETURNING|끝)" 구간들. FOR UPDATE 의 update 와 헷갈리지 않게 SET 부터 찾는다. */
    private static java.util.List<String> setClauses(String sql) {
        java.util.List<String> out = new java.util.ArrayList<>();
        var matcher = Pattern.compile("(?is)\\bset\\b(.*?)(\\bfrom\\b|\\bwhere\\b|\\breturning\\b|$)").matcher(sql);
        while (matcher.find()) {
            out.add(" " + matcher.group(1));
        }
        return out;
    }
}
