package watoo.grd.nextroute.application.journey.port.in;

import watoo.grd.nextroute.application.journey.dto.SubwayBindingView;
import watoo.grd.nextroute.application.journey.dto.SubwayBoardingEvidence;

/**
 * 앱이 보낸 탑승 관측을 받아 둔다.
 *
 * <p><b>여기서 열차를 고르지 않는다.</b> 두 가지 이유다.
 * <ol>
 *   <li>고를 재료가 아직 없을 수 있다. 원천 데이터는 1~2분 뒤처져 있어, 사용자가 탄 직후에는
 *       그 열차의 출발이 아직 관측되지 않았을 수 있다. 한 번 보고 끝내면 영영 못 고른다</li>
 *   <li>고른 뒤 알림을 보내야 하는데, 요청을 받는 쪽에는 알림을 보낼 자격이 없다</li>
 * </ol>
 *
 * <p>그래서 받아 두기만 하고, 고르는 일은 뒤에서 반복해서 시도한다. 응답은 "받았다"까지다.
 */
public interface SubmitSubwayBoardingEvidenceUseCase {

    /**
     * @param legIndex 앱이 가리킨 단계. 대기 단계를 가리켜도 짝이 되는 타는 단계로 옮겨 적는다
     * @return 지금 이 단계의 고정 상태. 같은 관측을 다시 보내도 <b>현재 상태</b>를 돌려준다
     */
    SubwayBindingView submit(Long userId, Long journeyId, int routeRevision, int legIndex,
                             SubwayBoardingEvidence evidence);
}
