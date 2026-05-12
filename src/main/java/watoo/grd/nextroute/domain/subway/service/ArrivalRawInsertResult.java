package watoo.grd.nextroute.domain.subway.service;

public record ArrivalRawInsertResult(
        int attemptedRows,
        int insertedRows,
        int duplicateRows,
        int attemptedCode1Rows,
        int insertedCode1Rows,
        int duplicateCode1Rows
) {
}
