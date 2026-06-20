package watoo.grd.nextroute.application.stopselection.port.in;

import watoo.grd.nextroute.application.stopselection.dto.SearchSuggestResult;

public interface SearchSuggestUseCase {
    /** 버스번호 + 정류장명 통합 자동완성. blank/길이초과 입력은 빈 결과. */
    SearchSuggestResult suggest(String keyword);
}
