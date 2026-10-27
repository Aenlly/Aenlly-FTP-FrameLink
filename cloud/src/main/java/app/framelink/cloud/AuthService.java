package app.framelink.cloud;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 邀请注册和可撤销会话，数据库只保留token摘要 */
@Service
public class AuthService {
    private final Db db;
    private final String invite;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Window> attempts = new ConcurrentHashMap<>();
    private record Window(long start, int count) { }
    public AuthService(Db db, @Value("${app.invite-code}") String invite) { this.db = db; this.invite = invite; }
    @Transactional
    public Map<String, Object> register(String email, String password, String code) {
        ApiProblem.require(!invite.isBlank() && MessageDigest.isEqual(invite.getBytes(StandardCharsets.UTF_8), code.getBytes(StandardCharsets.UTF_8)), 403, "邀请注册未开放或邀请码无效");
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        ApiProblem.require(normalized.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$") && normalized.length() <= 254, 400, "请输入有效邮箱");
        ApiProblem.require(password.length() >= 10 && password.getBytes(StandardCharsets.UTF_8).length <= 72, 400, "密码至少10个字符，UTF-8编码不超过72字节");
        ApiProblem.require(db.rows("SELECT id FROM accounts WHERE email=?", normalized).isEmpty(), 409, "该邮箱已注册");
        String id = UUID.randomUUID().toString();
        db.update("INSERT INTO accounts(id,email,password_hash,quota_bytes,used_bytes,created_at) VALUES(?,?,?,?,0,?)", id, normalized, passwords.encode(password), ProductPolicy.ACCOUNT_QUOTA, now());
        return session(id);
    }
    public Map<String, Object> login(String email, String password) {
        var users = db.rows("SELECT * FROM accounts WHERE email=?", email.strip().toLowerCase(Locale.ROOT));
        ApiProblem.require(!users.isEmpty() && password.getBytes(StandardCharsets.UTF_8).length<=72 && passwords.matches(password, Db.str(users.get(0), "passwordHash")), 401, "邮箱或密码错误");
        return session(Db.str(users.get(0), "id"));
    }
    public String owner(String authorization) {
        if(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes() instanceof org.springframework.web.context.request.ServletRequestAttributes attributes){
            var request=attributes.getRequest();
            if(authorization!=null && authorization.equals(request.getAttribute(OwnerAuthenticationFilter.HEADER_ATTRIBUTE)) && request.getAttribute(OwnerAuthenticationFilter.OWNER_ATTRIBUTE) instanceof String cached){return cached;}
        }
        String token = bearer(authorization);
        var rows = db.rows("SELECT user_id FROM auth_sessions WHERE token_hash=? AND expires_at>?", hash(token), now());
        ApiProblem.require(!rows.isEmpty(), 401, "登录已失效，请重新登录");
        return Db.str(rows.get(0), "userId");
    }
    public Map<String, Object> me(String owner) { return db.one("SELECT id,email,quota_bytes,used_bytes FROM accounts WHERE id=?", owner); }
    public void logout(String authorization) { db.update("DELETE FROM auth_sessions WHERE token_hash=?", hash(bearer(authorization))); }
    public void limit(String key) {
        long now = now();
        if (attempts.size() > 10000) { attempts.entrySet().removeIf(e -> now - e.getValue().start > 60_000); }
        Window result = attempts.compute(key, (k, old) -> old == null || now-old.start > 60_000 ? new Window(now, 1) : new Window(old.start, old.count+1));
        ApiProblem.require(result.count <= 20, 429, "操作过于频繁，请稍后再试");
    }
    public String token() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    public String accessCode() { return "%06d".formatted(random.nextInt(1_000_000)); }
    public String encodeCode(String code) { return passwords.encode(code); }
    public boolean matchesCode(String code, String encoded) { return passwords.matches(code, encoded); }
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static long now() { return System.currentTimeMillis(); }
    private Map<String, Object> session(String owner) {
        String token = token(); db.update("INSERT INTO auth_sessions(token_hash,user_id,expires_at) VALUES(?,?,?)", hash(token), owner, now()+ProductPolicy.SESSION_TTL);
        return Map.of("token", token, "account", me(owner));
    }
    private String bearer(String header) { ApiProblem.require(header != null && header.startsWith("Bearer ") && header.length()>10, 401, "请先登录"); return header.substring(7); }
}
