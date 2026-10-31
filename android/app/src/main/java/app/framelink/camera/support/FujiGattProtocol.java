package app.framelink.camera.support;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/** 富士手机配对与Wi-Fi交接，只声明已核对的特征，不包含快门、GPS或设置恢复 */
public final class FujiGattProtocol {
    public static final UUID LEGACY_SERVICE = uuid("91f1de68-dff6-466e-8b65-ff13b0f16fb8");
    public static final UUID LEGACY_PAIR = uuid("aba356eb-9633-4e60-b73f-f52516dbd671");
    public static final UUID SECURE_SERVICE = uuid("123d8f06-62a1-4935-9322-833c531ee225");
    public static final UUID SECURE_STATUS = uuid("f557d96b-8284-4667-8793-b971c1deca2a");
    public static final UUID CLIENT_NAME = uuid("85b9163e-62d1-49ff-a6f5-054b4630d4a1");
    public static final UUID NETWORK_SERVICE = uuid("4e941240-d01d-46b9-a5ea-67636806830b");
    public static final UUID SSID = uuid("bf6dc9cf-3606-4ec9-a4c8-d77576e93ea4");
    public static final UUID PASSWORD = uuid("e809256a-915c-4967-92e8-53b7d4cad213");
    public static final UUID STATUS_SERVICE = uuid("4c0020fe-f3b6-40de-acc9-77d129067b14");
    public static final UUID WIFI_READY = uuid("a68e3f66-0fcc-4395-8d4c-aa980b5877fa");
    public static final UUID WIFI_CONTROL_SERVICE = uuid("6514eb81-4e8f-458d-aa2a-e691336cdfac");
    // 与同服务中的快门特征不同，此特征0400仅用于启动Wi-Fi
    public static final UUID WIFI_START = uuid("600655e6-3637-42f1-8fb2-44efc5c63b13");
    public static final UUID FIRMWARE_SERVICE = uuid("0000180a-0000-1000-8000-00805f9b34fb");
    public static final UUID FIRMWARE = uuid("00002a26-0000-1000-8000-00805f9b34fb");
    public static final UUID CCCD = uuid("00002902-0000-1000-8000-00805f9b34fb");

    private FujiGattProtocol() {}

    /** Android已移除广播厂商数据的companyID，剩余布局为type加四字节token */
    public static byte[] pairingToken(int companyId, byte[] manufacturerData) {
        if (companyId != FujiDiscoveryRules.COMPANY_ID || manufacturerData == null || manufacturerData.length != 5 || manufacturerData[0] != 2) return null;
        return Arrays.copyOfRange(manufacturerData, 1, 5);
    }

    public static byte[] secureAcknowledgement(byte[] status) {
        if (status == null || status.length != 4) throw new IllegalArgumentException("富士配对状态长度无效");
        byte[] acknowledgement = status.clone(); acknowledgement[3] = 0x20;
        return acknowledgement;
    }

    public static String decodeText(byte[] raw, int maximumBytes) {
        if (raw == null || raw.length > maximumBytes) throw new IllegalArgumentException("富士蓝牙文本长度无效");
        int end = 0; while (end < raw.length && raw[end] != 0) end++;
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw, 0, end)).toString();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("富士蓝牙文本编码无效");
        }
    }

    /** 写入集中校验，发现未知特征或数据时停止，不尝试遥控和未解释的命令 */
    public static void checkWrite(UUID characteristic, byte[] data) {
        boolean permitted = false;
        if (LEGACY_PAIR.equals(characteristic)) permitted = data != null && data.length == 4;
        else if (SECURE_STATUS.equals(characteristic)) permitted = data != null && data.length == 4 && data[3] == 0x20;
        else if (CLIENT_NAME.equals(characteristic) && data != null && data.length >= 1 && data.length <= 24) {
            permitted = true;
            for (byte value : data) if (value < 32 || value > 126) permitted = false;
        } else if (WIFI_START.equals(characteristic)) permitted = Arrays.equals(data, new byte[]{4, 0});
        if (!permitted) throw new IllegalArgumentException("富士连接流程禁止此蓝牙写入");
    }

    private static UUID uuid(String value) { return UUID.fromString(value); }
}
