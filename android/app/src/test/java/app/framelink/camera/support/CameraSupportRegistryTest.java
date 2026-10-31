package app.framelink.camera.support;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import static org.junit.Assert.*;
import static app.framelink.camera.support.CameraSupportRegistry.*;

public class CameraSupportRegistryTest {
    private final CameraSupportRegistry registry = standard();

    @Test public void sonyModelsAndUnknownModelsUseTheSameAdapter() {
        Protocol adapter = registry.requireProtocol(SONY);
        for (String model : Arrays.asList("ILCE-7CM2", "ILCE-7M4", "ILCE-7M5", "ILCE-FUTURE")) {
            ProfileMatch match = registry.match(adapter.family, model, "2.06");
            assertSame(Family.SONY_ALPHA, match.profile.family);
            assertTrue(registry.assess(adapter, match, Collections.singletonMap(Capability.BROWSE, true))
                    .get(Capability.BROWSE).available());
        }
        assertFalse(registry.match(adapter.family, "ILCE-FUTURE", "2.06").knownModel);
        assertEquals("Sony α7C II", registry.match(adapter.family, "ILCE-7CM2", "2.06").profile.displayName);
    }

    @Test public void missingLegacyProtocolDefaultsButUnknownProtocolIsRejected() {
        assertSame(registry.requireProtocol(SONY), registry.requireProtocol(null));
        assertSame(registry.requireProtocol(SONY), registry.requireProtocol(""));
        try { registry.requireProtocol("future-protocol"); fail("未知协议不能默落Sony"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("尚未适配")); }
        assertEquals(8080, registry.requireProtocol(CANON).defaultPort);
        assertEquals(Transport.USB, registry.requireProtocol(USB).transport);
        assertFalse(registry.connectionOptions().contains(registry.requireProtocol(FUJI_AUTOSAVE)));
    }

    @Test public void firmwareSpecificProfileWinsWhileFamilyRestrictionsRemain() {
        ModelProfile family = profile("family", Collections.emptyList(), "", "", Capability.SHUTTER);
        ModelProfile model = profile("model", Collections.singletonList("ILCE-7M4"), "", "", Capability.FOCUS);
        ModelProfile range = profile("range", Collections.singletonList("ILCE-7M4"), "2.0", "2.9", Capability.LIVE_VIEW);
        ModelProfile exact = profile("exact", Collections.singletonList("ILCE-7M4"), "2.6", "2.6", Capability.PREVIEW);
        CameraSupportRegistry configured = new CameraSupportRegistry(SCHEMA_VERSION, Arrays.asList(family, model, range, exact));
        ProfileMatch match = configured.match(Family.SONY_ALPHA, "ILCE-7M4", "2.6");
        assertEquals("exact", match.profile.id);
        assertTrue(match.blocked.containsAll(EnumSet.of(Capability.SHUTTER, Capability.FOCUS, Capability.LIVE_VIEW, Capability.PREVIEW)));
        ProfileMatch newer = configured.match(Family.SONY_ALPHA, "ILCE-7M4", "3.0");
        assertEquals("model", newer.profile.id);
        assertFalse(newer.blocked.contains(Capability.PREVIEW));
        assertTrue(newer.blocked.contains(Capability.SHUTTER));
        assertTrue(configured.match(Family.SONY_ALPHA, "ILCE-7M4", "unreadable").blocked.contains(Capability.PREVIEW));
    }

    @Test public void profileAndModelCannotGrantUnconfirmedCapabilities() {
        Protocol adapter = registry.requireProtocol(SONY);
        ProfileMatch match = registry.match(adapter.family, "ILCE-7CM2", "2.06");
        Map<Capability, Boolean> reported = new EnumMap<>(Capability.class);
        reported.put(Capability.SHUTTER, false);
        reported.put(Capability.BROWSE, true);
        Map<Capability, Decision> decisions = registry.assess(adapter, match, reported);
        assertEquals(Availability.UNKNOWN, decisions.get(Capability.FOCUS).availability);
        assertEquals(Availability.UNAVAILABLE, decisions.get(Capability.SHUTTER).availability);
        assertTrue(decisions.get(Capability.BROWSE).available());
    }

    @Test public void readOnlyModesNeverExposeControlEvenWhenTheDeviceClaimsIt() {
        Map<Capability, Boolean> reported = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) reported.put(capability, true);
        for (String id : Arrays.asList(USB, FUJI_AUTOSAVE)) {
            Protocol adapter = registry.requireProtocol(id);
            Map<Capability, Decision> decisions = registry.assess(adapter, registry.match(adapter.family, "anything", "2.06"), reported);
            assertEquals(Availability.READ_ONLY, decisions.get(Capability.SHUTTER).availability);
            assertEquals(Availability.READ_ONLY, decisions.get(Capability.WRITE_PROPERTIES).availability);
            assertTrue(decisions.get(Capability.DOWNLOAD).available());
        }
        Protocol autosave = registry.requireProtocol(FUJI_AUTOSAVE);
        assertEquals(Availability.UNIMPLEMENTED, registry.assess(autosave,
                registry.match(autosave.family, "X-T30 II", "2.04"), reported).get(Capability.PREVIEW).availability);
    }

    @Test public void invalidSchemaAndReversedFirmwareRangesAreRejected() {
        try { new CameraSupportRegistry(2, Collections.emptyList()); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("配置版本")); }
        try { profile("bad", Collections.emptyList(), "3", "2", Capability.SHUTTER); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("顺序")); }
    }

    @Test public void networkAddressesAreValidatedWithoutResolvingNames() {
        assertTrue(CameraSupportRegistry.numericAddress("127.0.0.1"));
        assertTrue(CameraSupportRegistry.numericAddress("2001:db8::1"));
        for (String address : Arrays.asList("camera.local", "192.168.0.999", "192.168.01.1", "1.2.3", "::not-ip"))
            assertFalse(address, CameraSupportRegistry.numericAddress(address));
    }

    private ModelProfile profile(String id, java.util.List<String> models, String min, String max, Capability blocked) {
        return new ModelProfile(id, Family.SONY_ALPHA, models, "", min, max, EnumSet.of(blocked), "synthetic-rule-fixture");
    }
}
