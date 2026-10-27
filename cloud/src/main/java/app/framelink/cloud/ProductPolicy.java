package app.framelink.cloud;

import java.time.Duration;

/** 首轮邀请测试的默认额度和媒体处理边界 */
public final class ProductPolicy {
    public static final long ACCOUNT_QUOTA = 10L * 1024 * 1024 * 1024;
    public static final long SESSION_TTL = Duration.ofDays(7).toMillis();
    public static final int SHARE_DAYS = 7;
    public static final int MAX_SHARE_DAYS = 90;
    public static final int PREVIEW_EDGE = 2560;
    public static final long PREVIEW_UPLOAD_LIMIT = 30L * 1024 * 1024;
    public static final long MAX_IMAGE_PIXELS = 120_000_000;
    private ProductPolicy() { }
}
