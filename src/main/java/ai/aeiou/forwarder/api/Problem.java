package ai.aeiou.forwarder.api;

public class Problem extends RuntimeException {
  public final int status;
  public final String code;

  public Problem(int status, String code, String detail) {
    super(detail);
    this.status = status;
    this.code = code;
  }

  public static Problem missing() {
    return new Problem(404, "NOT_FOUND", "Resource was not found.");
  }

  public static Problem invalid(String detail) {
    return new Problem(400, "INVALID_REQUEST", detail);
  }

  public static Problem conflict(String detail) {
    return new Problem(409, "CONFLICT", detail);
  }

  public static Problem infeasible(String detail) {
    return new Problem(422, "INVALID_COMMAND", detail);
  }
}
