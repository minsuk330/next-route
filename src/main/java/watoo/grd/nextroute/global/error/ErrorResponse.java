package watoo.grd.nextroute.global.error;


import java.util.List;

public record ErrorResponse<T>(
    String code,
    String message,
    String path,
    List<FieldErrorResponse> fieldErrors,
    T details
) {

  public static ErrorResponse<Void> of(
      ErrorCode errorCode,
      String path
  ) {
    return new ErrorResponse<>(
        errorCode.getCode(),
        errorCode.getMessage(),
        path,
        List.of(),
        null
    );
  }


  public static <T> ErrorResponse<T> of(
      ErrorCode errorCode,
      String path,
      T details
  ) {
    return new ErrorResponse<>(
        errorCode.getCode(),
        errorCode.getMessage(),
        path,
        List.of(),
        details
    );
  }
}
