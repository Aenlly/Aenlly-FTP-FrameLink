package top.aenlly.ftp.properties;

import lombok.Data;

/**
 * @author Aenlly||tnw
 * @create 2024/04/02 11:14
 * @since 1.0.0
 */
@Data
public class FtpClientProperties {
    /**
     * 远程地址
     */
    public static String host;
    /**
     * 端口
     */
    public static int port;
    /**
     * 传输模式
     */
    public static String mode;
    /**
     * 连接模式，ftp,sftp,ftps
     */
    public static String connect;
    /**
     * 账号
     */
    public static String username;
    /**
     * 密码
     */
    public static String password;
    /**
     * 字符编码,可以先默认UTF-8,后续再加
     */
    public static String encoding;

}
