package top.aenlly.ftp.properties;

import lombok.Data;

/**
 * @author Aenlly||tnw
 * @create 2024/04/02 11:14
 * @since 1.0.0
 */
@Data
public class FtpServerProperties {

    public static String host;
    public static int port;
    public static String username;
    public static String password;
    /**
     * 远程目录
     */
    public static String remoteDir;
    /**
     * 压缩地址
     */
    public static String compressDir;
    /**
     * 压缩状态，是否启用
     */
    public static boolean compressState;
    /**
     * 略缩图状态，是否启用
     */
    public static boolean compressThumbState;
    /**
     * 字符编码，目前没法指定
     */
    public static String encoding;
    /**
     * 压缩匹配格式
     */
    public static String[] imageFormat;
    /**
     * 压缩文件的缩小倍率
     */
    public static float imageMul;
}
