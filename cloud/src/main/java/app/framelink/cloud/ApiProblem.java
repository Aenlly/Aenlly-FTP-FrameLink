package app.framelink.cloud;

/** 对外返回可处理的业务失败，避免内部异常细节泄露 */
public class ApiProblem extends RuntimeException {
    private final int status;
    public ApiProblem(int status, String message) { super(message); this.status = status; }
    public int status() { return status; }
    public static void require(boolean valid, int status, String message) {
        if (!valid) { throw new ApiProblem(status, message); }
    }
}
