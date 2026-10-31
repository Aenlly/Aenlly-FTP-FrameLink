package app.framelink.camera.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 相机协议注册与机型差异规则，不持有设备账号、媒体身份或平台对象 */
public final class CameraSupportRegistry {
    public static final int SCHEMA_VERSION = 1;
    public static final String SONY = "sony-ptpip";
    public static final String CANON = "canon-ccapi";
    public static final String USB = "usb-mtp";
    public static final String FUJI_AUTOSAVE = "fuji-autosave";
    public static final String FUJI_PHONE = "fuji-phone";

    public enum Transport { WIFI, USB }
    public enum Family { SONY_ALPHA, CANON_CCAPI, USB_READER, FUJI_AUTOSAVE, FUJI_PHONE }
    public enum Discovery { NONE, SONY_SSDP, WIFI_GATEWAY, USB_DEVICES }
    public enum Capability {
        BROWSE, DOWNLOAD, PREVIEW, LIVE_VIEW, FOCUS, SHUTTER, AUTO_IMPORT,
        READ_PROPERTIES, WRITE_PROPERTIES
    }
    public enum Availability { UNKNOWN, UNIMPLEMENTED, UNAVAILABLE, READ_ONLY, AVAILABLE }

    /** 已实现适配器的边界，型号配置不能改变驱动或传输方式 */
    public static final class Protocol {
        public final String id;
        public final String title;
        public final Family family;
        public final Transport transport;
        public final int defaultPort;
        public final boolean readOnly;
        public final boolean experimental;
        public final Set<Capability> implemented;
        public final boolean accessCredentials;
        public final Discovery discovery;

        private Protocol(String id, String title, Family family, Transport transport, int defaultPort,
                         boolean readOnly, boolean experimental, Set<Capability> implemented) {
            this.id = id;
            this.title = title;
            this.family = family;
            this.transport = transport;
            this.defaultPort = defaultPort;
            this.readOnly = readOnly;
            this.experimental = experimental;
            this.implemented = immutable(implemented);
            this.accessCredentials = family == Family.SONY_ALPHA || family == Family.CANON_CCAPI;
            this.discovery = transport == Transport.USB ? Discovery.USB_DEVICES :
                    family == Family.SONY_ALPHA ? Discovery.SONY_SSDP :
                    family == Family.FUJI_PHONE ? Discovery.WIFI_GATEWAY : Discovery.NONE;
        }

        public String connectionHint() {
            switch (family) {
                case SONY_ALPHA: return "Sony网络浏览与遥控 · 同一Wi-Fi或相机热点，按机身实际能力连接";
                case CANON_CCAPI: return "CCAPI测试适配 · 启用机身CCAPI后接入同一网络，功能由实际端点决定";
                case FUJI_PHONE: return "富士手机无线测试通道 · 蓝牙配对后交接Wi-Fi，当前提供图库和按需导入";
                default: return "按当前连接方式与设备公布的能力使用";
            }
        }

        public String preparationTitle() {
            switch (family) {
                case SONY_ALPHA: return "开启电脑遥控与访问身份验证";
                case CANON_CCAPI: return "启用机身CCAPI";
                case FUJI_PHONE: return "在机身打开蓝牙配对";
                default: return "连接准备";
            }
        }

        public String preparationSteps() {
            switch (family) {
                case SONY_ALPHA: return "① 网络→连接/电脑遥控→电脑遥控→开\n② 读取访问身份验证信息\n③ 填写机身地址、账号密码，确认自动读取的指纹\n连接前断开其他控制App的相机会话";
                case CANON_CCAPI: return "① 确认机型、固件和CCAPI启用条件\n② 读取机身HTTP地址及端口\n③ 机身启用了HTTP认证时填写账号密码";
                case FUJI_PHONE: return "① 在相机拍摄界面按DISP/BACK，选择蓝牙→配对\n② 点击蓝牙发现相机，选择目标机身\n③ 连接时确认系统蓝牙和Wi-Fi提示，在相机允许访问\n已有机身热点连接时也可手动填写地址；手机无线不是PC AutoSave，不自动导入原片";
                default: return "按设备提示确认当前连接模式";
            }
        }
    }

    /** 有来源的差异配置只能限制能力，不能代替真实机身能力协商 */
    public static final class ModelProfile {
        public final String id;
        public final Family family;
        public final List<String> models;
        public final String displayName;
        public final String minimumFirmware;
        public final String maximumFirmware;
        public final Set<Capability> blocked;
        public final String source;

        public ModelProfile(String id, Family family, List<String> models, String displayName,
                            String minimumFirmware, String maximumFirmware, Set<Capability> blocked,
                            String source) {
            if (id == null || id.trim().isEmpty() || family == null || source == null || source.trim().isEmpty()) {
                throw new IllegalArgumentException("相机差异配置缺少身份或来源");
            }
            this.id = id;
            this.family = family;
            List<String> names = new ArrayList<>();
            for (String model : models) names.add(normalize(model));
            this.models = Collections.unmodifiableList(names);
            this.displayName = displayName == null ? "" : displayName;
            this.minimumFirmware = minimumFirmware == null ? "" : minimumFirmware;
            this.maximumFirmware = maximumFirmware == null ? "" : maximumFirmware;
            if ((!this.minimumFirmware.isEmpty() && version(this.minimumFirmware) == null) ||
                    (!this.maximumFirmware.isEmpty() && version(this.maximumFirmware) == null)) {
                throw new IllegalArgumentException("固件范围必须为数字版本");
            }
            if (!this.minimumFirmware.isEmpty() && !this.maximumFirmware.isEmpty() &&
                    compare(version(this.minimumFirmware), version(this.maximumFirmware)) > 0) {
                throw new IllegalArgumentException("固件范围顺序无效");
            }
            this.blocked = immutable(blocked);
            this.source = source;
        }

        private boolean matchesModel(String model) { return models.isEmpty() || models.contains(normalize(model)); }
        private boolean hasFirmwareRange() { return !minimumFirmware.isEmpty() || !maximumFirmware.isEmpty(); }
        private boolean matchesFirmware(int[] firmware) {
            // 无法识别固件时保留可能适用的限制，不因漏读版本绕过保护
            return firmware == null ||
                    ((minimumFirmware.isEmpty() || compare(firmware, version(minimumFirmware)) >= 0) &&
                     (maximumFirmware.isEmpty() || compare(firmware, version(maximumFirmware)) <= 0));
        }
        private int specificity() {
            return (models.isEmpty() ? 0 : 2) + (hasFirmwareRange() ?
                    (minimumFirmware.equals(maximumFirmware) ? 2 : 1) : 0);
        }
    }

    public static final class ProfileMatch {
        public final ModelProfile profile;
        public final boolean knownModel;
        public final Set<Capability> blocked;
        public final List<String> evidence;

        private ProfileMatch(ModelProfile profile, boolean knownModel, Set<Capability> blocked, List<String> evidence) {
            this.profile = profile;
            this.knownModel = knownModel;
            this.blocked = immutable(blocked);
            this.evidence = Collections.unmodifiableList(new ArrayList<>(evidence));
        }
    }

    public static final class Decision {
        public final Availability availability;
        public final String evidence;

        private Decision(Availability availability, String evidence) {
            this.availability = availability;
            this.evidence = evidence;
        }

        public boolean available() { return availability == Availability.AVAILABLE; }
    }

    private static final CameraSupportRegistry STANDARD = new CameraSupportRegistry(SCHEMA_VERSION, standardProfiles());
    private final List<ModelProfile> profiles;
    private final Map<String, Protocol> protocols;

    public CameraSupportRegistry(int schemaVersion, List<ModelProfile> profiles) {
        if (schemaVersion != SCHEMA_VERSION) throw new IllegalArgumentException("相机支持配置版本尚未适配");
        this.profiles = Collections.unmodifiableList(new ArrayList<>(profiles));
        Map<String, Protocol> entries = new LinkedHashMap<>();
        register(entries, new Protocol(SONY, "Sony 网络", Family.SONY_ALPHA, Transport.WIFI, 15740,
                false, false, EnumSet.allOf(Capability.class)));
        register(entries, new Protocol(CANON, "佳能 CCAPI", Family.CANON_CCAPI, Transport.WIFI, 8080,
                false, false, EnumSet.allOf(Capability.class)));
        register(entries, new Protocol(USB, "USB读卡", Family.USB_READER, Transport.USB, 0,
                true, false, EnumSet.of(Capability.BROWSE, Capability.DOWNLOAD, Capability.PREVIEW)));
        register(entries, new Protocol(FUJI_AUTOSAVE, "Fuji AutoSave", Family.FUJI_AUTOSAVE, Transport.WIFI, 55740,
                true, true, EnumSet.of(Capability.BROWSE, Capability.DOWNLOAD)));
        register(entries, new Protocol(FUJI_PHONE, "富士 Wi-Fi（测试）", Family.FUJI_PHONE, Transport.WIFI, 55740,
                true, false, EnumSet.of(Capability.BROWSE, Capability.DOWNLOAD, Capability.PREVIEW)));
        protocols = Collections.unmodifiableMap(entries);
    }

    public static CameraSupportRegistry standard() { return STANDARD; }

    /** 只对旧配置缺失协议保留Sony默认，明确的未知协议在切换设备前拒绝 */
    public Protocol requireProtocol(String protocol) {
        String key = protocol == null || protocol.trim().isEmpty() ? SONY : protocol;
        Protocol result = protocols.get(key);
        if (result == null) throw new IllegalArgumentException("当前连接协议尚未适配");
        return result;
    }

    public List<Protocol> connectionOptions() {
        List<Protocol> result = new ArrayList<>();
        for (Protocol protocol : protocols.values()) if (!protocol.experimental) result.add(protocol);
        return Collections.unmodifiableList(result);
    }

    /** 只接受数字IP，不在设备切换前隐式发起DNS查询 */
    public static boolean numericAddress(String address) {
        if (address == null) return false;
        if (address.contains(":")) {
            if (!address.matches("[0-9a-fA-F:.]+")) return false;
            try { java.net.InetAddress.getByName(address); return true; }
            catch (java.net.UnknownHostException error) { return false; }
        }
        String[] segments = address.split("\\.", -1);
        if (segments.length != 4) return false;
        for (String segment : segments) {
            if (!segment.matches("[0-9]{1,3}") || segment.length() > 1 && segment.startsWith("0") || Integer.parseInt(segment) > 255) return false;
        }
        return true;
    }

    /** 同族共用适配器，精确型号和固件优先命名，所有适用的保护限制取并集 */
    public ProfileMatch match(Family family, String model, String firmware) {
        ModelProfile selected = null;
        boolean known = false;
        Set<Capability> blocked = EnumSet.noneOf(Capability.class);
        List<String> evidence = new ArrayList<>();
        int[] actualFirmware = version(firmware);
        for (ModelProfile profile : profiles) {
            if (profile.family != family || !profile.matchesModel(model) || !profile.matchesFirmware(actualFirmware)) continue;
            known |= !profile.models.isEmpty();
            blocked.addAll(profile.blocked);
            if (!profile.blocked.isEmpty()) evidence.add(profile.source);
            if (selected == null || profile.specificity() > selected.specificity()) selected = profile;
        }
        return new ProfileMatch(selected, known, blocked, evidence);
    }

    /** 最终能力由已实现适配器、连接模式、机身协商与限制共同决定 */
    public Map<Capability, Decision> assess(Protocol protocol, ProfileMatch profile, Map<Capability, Boolean> negotiated) {
        Map<Capability, Decision> result = new EnumMap<>(Capability.class);
        for (Capability capability : Capability.values()) {
            Availability availability;
            String evidence;
            if (protocol.readOnly && writesCamera(capability)) {
                availability = Availability.READ_ONLY; evidence = "当前连接为只读媒体模式";
            } else if (!protocol.implemented.contains(capability)) {
                availability = Availability.UNIMPLEMENTED; evidence = "当前适配器未实现此能力";
            } else if (profile.blocked.contains(capability)) {
                availability = Availability.UNAVAILABLE; evidence = String.join(";", profile.evidence);
            } else if (negotiated.get(capability) == null) {
                availability = Availability.UNKNOWN; evidence = "机身尚未确认此能力";
            } else if (!negotiated.get(capability)) {
                availability = Availability.UNAVAILABLE; evidence = "当前机身或模式未提供此能力";
            } else {
                availability = Availability.AVAILABLE; evidence = "适配器已实现且机身已确认";
            }
            result.put(capability, new Decision(availability, evidence));
        }
        return Collections.unmodifiableMap(result);
    }

    private static boolean writesCamera(Capability capability) {
        return capability == Capability.WRITE_PROPERTIES || capability == Capability.FOCUS || capability == Capability.SHUTTER;
    }

    private static void register(Map<String, Protocol> entries, Protocol protocol) {
        if (entries.put(protocol.id, protocol) != null) throw new IllegalArgumentException("相机协议重复注册");
    }

    private static List<ModelProfile> standardProfiles() {
        List<ModelProfile> result = new ArrayList<>();
        String source = "docs/CAMERA_BACKENDS.md";
        for (Family family : Family.values()) {
            result.add(new ModelProfile(family.name().toLowerCase(Locale.ROOT), family,
                    Collections.emptyList(), "", "", "", Collections.emptySet(), source));
        }
        String[][] sony = {{"a7c2", "ILCE-7CM2", "Sony α7C II"}, {"a7m4", "ILCE-7M4", "Sony α7 IV"},
                           {"a7m5", "ILCE-7M5", "Sony α7 V"}, {"a6700", "ILCE-6700", "Sony α6700"}};
        for (String[] model : sony) result.add(new ModelProfile(model[0], Family.SONY_ALPHA,
                Arrays.asList(model[1], model[2]), model[2], "", "", Collections.emptySet(), source));
        return result;
    }

    private static Set<Capability> immutable(Set<Capability> capabilities) {
        EnumSet<Capability> copy = EnumSet.noneOf(Capability.class);
        copy.addAll(capabilities);
        return Collections.unmodifiableSet(copy);
    }

    private static String normalize(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }

    private static int[] version(String firmware) {
        if (firmware == null || !firmware.matches("[0-9]{1,5}(\\.[0-9]{1,5}){0,3}")) return null;
        String[] segments = firmware.split("\\.");
        int[] values = new int[4];
        for (int i = 0; i < segments.length; i++) values[i] = Integer.parseInt(segments[i]);
        return values;
    }

    private static int compare(int[] left, int[] right) {
        for (int i = 0; i < left.length; i++) {
            int order = Integer.compare(left[i], right[i]);
            if (order != 0) return order;
        }
        return 0;
    }
}
