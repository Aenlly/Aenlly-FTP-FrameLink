package app.framelink.camera.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 富士广播识别与结果合并，先核对厂商/协议证据，不以设备名称作为发现前提 */
public final class FujiDiscoveryRules {
    // gkoh/furble Fujifilm.h，Android厂商数据API已将companyID从载荷中分离
    public static final int COMPANY_ID = 0x04d8;
    public static final UUID BASIC_ADVERTISEMENT = UUID.fromString("af854c2e-b214-458e-97e2-912c4ecf2cb8");
    public static final UUID XAPP_ADVERTISEMENT = UUID.fromString("117c4142-edd4-4c77-8696-dd18eebb770a");
    public static final UUID SECURE_ADVERTISEMENT = UUID.fromString("a9d2b304-e8d6-4902-8336-352b772d7597");
    private static final int MAX_PAYLOAD = 29;

    public enum Variant { BASIC, SECURE, UNKNOWN }

    public static final class Match {
        public final Variant variant;
        public final boolean manufacturerVerified;
        private final transient byte[] token;
        private Match(Variant variant, boolean manufacturerVerified, byte[] token) {
            this.variant = variant;
            this.manufacturerVerified = manufacturerVerified;
            this.token = token == null ? null : token.clone();
        }
        public byte[] token() { return token == null ? null : token.clone(); }
    }

    public static final class Candidate {
        public final String address;
        public final String name;
        public final boolean manufacturerVerified;
        public final Variant variant;
        private final transient byte[] token;
        private Candidate(String address, String name, Match match) {
            this.address = address;
            this.name = name;
            this.manufacturerVerified = match.manufacturerVerified;
            this.variant = match.variant;
            this.token = match.token();
        }
        public byte[] token() { return token == null ? null : token.clone(); }
        @Override public String toString() { return name; }
    }

    private FujiDiscoveryRules() {}

    /** 仅已核对的Fuji厂商载荷可产生配对token，短包和其他厂家同形数据都拒绝 */
    public static Match manufacturer(int company, byte[] payload, Set<UUID> services) {
        if (company != COMPANY_ID || payload == null || payload.length < 1 || payload.length > MAX_PAYLOAD) return null;
        int type = payload[0] & 0xff;
        if (type == 2) {
            if (payload.length == 5) return new Match(Variant.BASIC, true, FujiGattProtocol.pairingToken(company, payload));
            if (payload.length == 6) return new Match(Variant.SECURE, true, null);
            return null;
        }
        return new Match(services.contains(SECURE_ADVERTISEMENT) ? Variant.SECURE : Variant.UNKNOWN, true, null);
    }

    /** 广告服务UUID与连接后的GATT配对服务不同，服务证据不携带未验证的token */
    public static Match services(Set<UUID> services) {
        if (services.contains(SECURE_ADVERTISEMENT) || services.contains(FujiGattProtocol.SECURE_SERVICE))
            return new Match(Variant.SECURE, false, null);
        if (services.contains(BASIC_ADVERTISEMENT) || services.contains(XAPP_ADVERTISEMENT) || services.contains(FujiGattProtocol.LEGACY_SERVICE))
            return new Match(Variant.UNKNOWN, false, null);
        return null;
    }

    public static boolean knownBondedName(String value) {
        String name = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return name.contains("FUJIFILM") || name.matches("^(X-(T|H|S|E|A|M|PRO)[0-9]|X100|GFX).*");
    }

    /** 每次扫描独立收集，重复回调按地址合并，结束或权限撤回后不接受旧结果 */
    public static final class Collector implements AutoCloseable {
        private final Map<String, Candidate> candidates = new LinkedHashMap<>();
        private boolean active = true;

        public synchronized boolean offer(String address, String name, Match match) {
            if (!active || match == null || address == null || !address.matches("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}")) return false;
            String key = address.toUpperCase(Locale.ROOT);
            Candidate old = candidates.get(key);
            String label = label(name);
            if (label.isEmpty()) label = old == null ? "FUJIFILM 相机 · " + key.substring(key.length() - 5).replace(":", "") : old.name;
            Match merged = match;
            if (old != null && match.token == null && match.variant != Variant.SECURE && old.token != null)
                merged = new Match(old.variant, old.manufacturerVerified || match.manufacturerVerified, old.token);
            Candidate next = new Candidate(key, label, merged);
            if (old != null && old.name.equals(next.name) && old.variant == next.variant &&
                    old.manufacturerVerified == next.manufacturerVerified && Arrays.equals(old.token, next.token)) return false;
            replace(key, next);
            return true;
        }

        public synchronized void bonded(String address, String name) {
            if (knownBondedName(name)) offer(address, name, new Match(Variant.UNKNOWN, false, null));
        }

        public synchronized boolean nameOnly(String address, String name) {
            if (!active || address == null) return false;
            String key = address.toUpperCase(Locale.ROOT);
            Candidate old = candidates.get(key);
            if (old != null && !label(name).isEmpty() && !old.name.equals(label(name))) {
                replace(key, new Candidate(key, label(name), new Match(old.variant, old.manufacturerVerified, old.token)));
                return true;
            }
            return false;
        }

        public synchronized List<Candidate> snapshot() {
            List<Candidate> snapshot = new ArrayList<>();
            if (active) for (Candidate candidate : candidates.values())
                snapshot.add(new Candidate(candidate.address, candidate.name, new Match(candidate.variant, candidate.manufacturerVerified, candidate.token)));
            return Collections.unmodifiableList(snapshot);
        }

        public synchronized List<Candidate> finish() { List<Candidate> result=snapshot(); close(); return result; }

        @Override public synchronized void close() {
            active = false;
            for (Candidate value : candidates.values()) if (value.token != null) Arrays.fill(value.token, (byte) 0);
            candidates.clear();
        }

        private void replace(String address, Candidate next) {
            Candidate old = candidates.put(address, next);
            if (old != null && old.token != null) Arrays.fill(old.token, (byte) 0);
        }

        private static String label(String raw) {
            if (raw == null) return "";
            String value = raw.replaceAll("[\\p{Cntrl}]", " ").trim();
            return value.length() <= 64 ? value : value.substring(0, 64);
        }
    }
}
