package watoo.grd.nextroute.application.regionalsubway.port.out;

import java.time.LocalDate;
import java.util.Optional;

/** data.go.kr 데이터셋 메타데이터(수정일). 커밋한 파일 원천이 갱신됐는지 감시한다. */
public interface PublicDataCatalogPort {

    /** {@code https://www.data.go.kr/catalog/{id}/fileData.json} 의 dateModified. 못 읽으면 empty. */
    Optional<LocalDate> fileDataModified(String datasetId);
}
