package dev.jellydb;

public final class ApiException extends RuntimeException {
  public final int status;
  public final String code;

  public ApiException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public static ApiException missing() {
    return new ApiException(404, "not_found", "Namespace or item is missing or expired");
  }

  public static ApiException invalid(String message) {
    return new ApiException(400, "invalid_request", message);
  }

  public static ApiException unavailable() {
    return new ApiException(
        503,
        "backend_unavailable",
        "Storage unavailable; mutations may have committed. Do not blindly replay writes.");
  }
}
