package app.framelink.camera.support;

import org.junit.Test;
import java.util.UUID;
import static org.junit.Assert.*;

public class FujiGattProtocolTest {
    @Test public void androidAdvertisementLayoutDoesNotIncludeTheCompanyId() {
        assertArrayEquals(new byte[]{1,2,3,4}, FujiGattProtocol.pairingToken(FujiDiscoveryRules.COMPANY_ID,new byte[]{2,1,2,3,4}));
        assertNull(FujiGattProtocol.pairingToken(0x004c,new byte[]{2,1,2,3,4}));
        assertNull(FujiGattProtocol.pairingToken(FujiDiscoveryRules.COMPANY_ID,new byte[]{1,1,2,3,4}));
        assertNull(FujiGattProtocol.pairingToken(FujiDiscoveryRules.COMPANY_ID,new byte[]{2,1,2}));
        assertNull(FujiGattProtocol.pairingToken(FujiDiscoveryRules.COMPANY_ID,new byte[]{2,1,2,3,4,5}));
    }
    @Test public void secureAcknowledgementDoesNotMutateTheReceivedStatus() {
        byte[] status = {1,2,3,4};
        assertArrayEquals(new byte[]{1,2,3,0x20}, FujiGattProtocol.secureAcknowledgement(status));
        assertArrayEquals(new byte[]{1,2,3,4}, status);
        assertThrows(IllegalArgumentException.class, () -> FujiGattProtocol.secureAcknowledgement(new byte[3]));
    }
    @Test public void wifiActivationCannotBeUsedToSendShutterCommands() {
        FujiGattProtocol.checkWrite(FujiGattProtocol.WIFI_START, new byte[]{4,0});
        FujiGattProtocol.checkWrite(FujiGattProtocol.CLIENT_NAME, "FrameLink".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> FujiGattProtocol.checkWrite(FujiGattProtocol.WIFI_START, new byte[]{1,0}));
        UUID shutter = UUID.fromString("7fcf49c6-4ff0-4777-a03d-1a79166af7a8");
        assertThrows(IllegalArgumentException.class, () -> FujiGattProtocol.checkWrite(shutter, new byte[]{1,0}));
        assertThrows(IllegalArgumentException.class, () -> FujiGattProtocol.checkWrite(UUID.randomUUID(), new byte[4]));
    }
    @Test public void textIsBoundedAndMalformedUtf8IsRejected() {
        assertEquals("FUJI", FujiGattProtocol.decodeText(new byte[]{70,85,74,73,0,0},32));
        assertThrows(IllegalArgumentException.class, () -> FujiGattProtocol.decodeText(new byte[33],32));
        assertThrows(IllegalArgumentException.class, () -> FujiGattProtocol.decodeText(new byte[]{(byte)0xff},32));
    }
}
