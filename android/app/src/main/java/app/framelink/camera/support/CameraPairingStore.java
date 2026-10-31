package app.framelink.camera.support;

/** 平台连接模块使用的配对凭据仓库，驱动不依赖设置实现或云账号 */
public interface CameraPairingStore {
    byte[] loadToken(String protocol, String address);
    void saveToken(String protocol, String address, byte[] token);
    void forget(String protocol, String address);
}
