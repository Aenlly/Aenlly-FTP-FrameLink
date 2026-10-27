package app.framelink.cloud;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 将数据库列统一映射成API使用的camelCase字段 */
@Component
public class Db {
    private final JdbcTemplate jdbc;
    public Db(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<Map<String, Object>> rows(String sql, Object... args) { return jdbc.query(sql, (rs, n) -> row(rs), args); }
    public Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = rows(sql, args);
        ApiProblem.require(!rows.isEmpty(), 404, "记录不存在或没有访问权限");
        return rows.get(0);
    }
    public int update(String sql, Object... args) { return jdbc.update(sql, args); }
    private Map<String, Object> row(ResultSet rs) throws SQLException {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
            String name = rs.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT);
            StringBuilder key = new StringBuilder(); boolean upper = false;
            for (char c : name.toCharArray()) { if (c == '_') { upper = true; } else { key.append(upper ? Character.toUpperCase(c) : c); upper = false; } }
            Object value = rs.getObject(i);
            if (value instanceof java.sql.Clob clob) { value = clob.getSubString(1, (int) clob.length()); }
            map.put(key.toString(), value);
        }
        return map;
    }
    public static String str(Map<String, Object> row, String key) { Object value = row.get(key); return value == null ? "" : value.toString(); }
    public static long num(Map<String, Object> row, String key) { Object value = row.get(key); return value instanceof Number n ? n.longValue() : 0; }
    public static boolean bool(Map<String, Object> row, String key) { Object value = row.get(key); return Boolean.TRUE.equals(value) || value instanceof Number n && n.intValue() != 0; }
}
