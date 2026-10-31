package app.framelink.camera.support;

import org.junit.Test;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class FujiDiscoveryRulesTest {
    private static final String ADDRESS = "00:11:22:33:44:55";
    private FujiDiscoveryRules.Match basic() {
        return FujiDiscoveryRules.manufacturer(FujiDiscoveryRules.COMPANY_ID, new byte[]{2,1,2,3,4}, Collections.emptySet());
    }
    @Test public void genuineUnnamedFujiWithoutAdvertisedServicesIsStillDiscovered() {
        FujiDiscoveryRules.Collector collector = new FujiDiscoveryRules.Collector();
        collector.offer(ADDRESS, null, basic());
        List<FujiDiscoveryRules.Candidate> found = collector.finish();
        assertEquals(1, found.size());
        assertTrue(found.get(0).manufacturerVerified);
        assertTrue(found.get(0).name.startsWith("FUJIFILM"));
        assertArrayEquals(new byte[]{1,2,3,4}, found.get(0).token());
    }
    @Test public void otherManufacturersCannotSupplyFujiTokensEvenWithTheSamePayload() {
        assertNull(FujiDiscoveryRules.manufacturer(0x004c,new byte[]{2,1,2,3,4},Collections.emptySet()));
        FujiDiscoveryRules.Collector collector = new FujiDiscoveryRules.Collector();
        collector.offer(ADDRESS,"FUJIFILM",FujiDiscoveryRules.manufacturer(0x004c,new byte[]{2,1,2,3,4},Collections.emptySet()));
        assertTrue(collector.finish().isEmpty());
    }
    @Test public void shortAndMalformedPacketsDoNotBecomePairingTokens() {
        for (byte[] payload : new byte[][]{new byte[0],new byte[]{2},new byte[]{2,1,2,3},new byte[]{2,1,2,3,4,5,6},new byte[30]})
            assertNull(FujiDiscoveryRules.manufacturer(FujiDiscoveryRules.COMPANY_ID,payload,Collections.emptySet()));
        FujiDiscoveryRules.Match secure = FujiDiscoveryRules.manufacturer(FujiDiscoveryRules.COMPANY_ID,new byte[]{2,1,2,3,4,5},Collections.emptySet());
        assertEquals(FujiDiscoveryRules.Variant.SECURE, secure.variant);
        assertNull(secure.token());
    }
    @Test public void advertisedServiceNamesAreSeparateFromGattPairingServices() {
        assertNotNull(FujiDiscoveryRules.services(Collections.singleton(FujiDiscoveryRules.XAPP_ADVERTISEMENT)));
        assertNotNull(FujiDiscoveryRules.services(Collections.singleton(FujiDiscoveryRules.SECURE_ADVERTISEMENT)));
        assertNull(FujiDiscoveryRules.services(Collections.emptySet()));
    }
    @Test public void repeatedResultsAndScanResponseNamesPreserveOneCandidateAndVerifiedToken() {
        FujiDiscoveryRules.Collector collector = new FujiDiscoveryRules.Collector();
        collector.offer(ADDRESS,null,basic());
        collector.offer(ADDRESS,null,basic());
        collector.nameOnly(ADDRESS,"X-T30 II");
        collector.offer(ADDRESS,null,FujiDiscoveryRules.manufacturer(FujiDiscoveryRules.COMPANY_ID,new byte[]{1},Collections.emptySet()));
        List<FujiDiscoveryRules.Candidate> found=collector.finish();
        assertEquals(1,found.size());assertEquals("X-T30 II",found.get(0).name);
        assertArrayEquals(new byte[]{1,2,3,4},found.get(0).token());
    }
    @Test public void cachedBondedNameDoesNotNeedAnAdvertisingServiceOrBorrowOtherDevices() {
        FujiDiscoveryRules.Collector collector = new FujiDiscoveryRules.Collector();
        collector.bonded(ADDRESS,"X-T30II");collector.bonded("00:11:22:33:44:66","X-Headset");
        List<FujiDiscoveryRules.Candidate> found=collector.finish();
        assertEquals(1,found.size());assertNull(found.get(0).token());
    }
    @Test public void revocationAndLateCallbacksCannotPublishOldDevices() {
        FujiDiscoveryRules.Collector collector = new FujiDiscoveryRules.Collector();
        collector.offer(ADDRESS,null,basic());collector.close();collector.offer(ADDRESS,"X-T30 II",basic());
        assertTrue(collector.finish().isEmpty());
    }
    @Test public void mutableCallerBuffersCannotChangeStoredTokens() {
        byte[] payload={2,1,2,3,4};
        FujiDiscoveryRules.Match match=FujiDiscoveryRules.manufacturer(FujiDiscoveryRules.COMPANY_ID,payload,Collections.emptySet());
        payload[1]=9;byte[] exposed=match.token();exposed[0]=8;
        assertArrayEquals(new byte[]{1,2,3,4},match.token());
    }
}
