package watoo.grd.nextroute.domain.subway.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalRaw;
import watoo.grd.nextroute.domain.subway.repository.SubwayArrivalRawRepository;

@Service
@RequiredArgsConstructor
public class SubwayArrivalRawWriter {

	private final SubwayArrivalRawRepository subwayArrivalRawRepository;

	@Transactional
	public ArrivalRawInsertResult insertArrivalRawIgnoreDuplicates(List<SubwayArrivalRaw> raws) {
		int insertedRows = 0;
		int attemptedCode1Rows = 0;
		int insertedCode1Rows = 0;

		for (SubwayArrivalRaw raw : raws) {
			boolean code1 = "1".equals(raw.getArrivalCode());
			if (code1) {
				attemptedCode1Rows++;
			}

			int inserted = subwayArrivalRawRepository.insertIgnore(raw);
			insertedRows += inserted;

			if (code1) {
				insertedCode1Rows += inserted;
			}
		}

		return new ArrivalRawInsertResult(
				raws.size(),
				insertedRows,
				raws.size() - insertedRows,
				attemptedCode1Rows,
				insertedCode1Rows,
				attemptedCode1Rows - insertedCode1Rows);
	}
}
