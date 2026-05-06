package watoo.grd.nextroute.application.kakao.port.out;

import watoo.grd.nextroute.application.kakao.dto.KakaoSearchResult;

public interface KakaoApiPort {

  KakaoSearchResult searchSubwayStation(String query);

}
