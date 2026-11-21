package com.xinyu.invoice.auth;

import com.xinyu.invoice.common.ApiException;
import com.xinyu.invoice.config.AppProps;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 会话：存在 sessions 表里，与旧版 Python 共用。
 *
 * 好处是迁移期间两边能互相认会话——同一张表、同一个 Cookie 名（sid），
 * 用户从旧版切到新版不用重新登录。
 */
@Service
public class SessionService {

    public static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final AppProps props;

    public SessionService(JdbcTemplate jdbc, AppProps props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    public String create(long userId, String ip, String ua) {
        String token = PasswordTool.newToken();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO sessions (token, user_id, created_at, expires_at, ip, ua) "
                        + "VALUES (?,?,?,?,?,?)",
                token, userId,
                now.format(TS),
                now.plusDays(props.getSessionDays()).format(TS),
                ip == null ? "" : ip,
                ua == null ? "" : ua.substring(0, Math.min(ua.length(), 200)));
        return token;
    }

    /** 令牌 → 用户（不含密码哈希）。过期、停用、不存在都返回 null。 */
    public Map<String, Object> resolve(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.expires_at AS sess_expires, u.* FROM sessions s "
                        + "JOIN users u ON u.id = s.user_id WHERE s.token = ?", token);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        String expires = String.valueOf(row.remove("sess_expires"));
        try {
            if (LocalDateTime.parse(expires, TS).isBefore(LocalDateTime.now())) {
                delete(token);
                return null;
            }
        } catch (Exception e) {
            return null;
        }
        Object status = row.get("status");
        if (status == null || ((Number) status).intValue() != 1) {
            return null;
        }
        row.remove("password_hash");
        return row;
    }

    public void delete(String token) {
        if (token != null && !token.isBlank()) {
            jdbc.update("DELETE FROM sessions WHERE token = ?", token);
        }
    }

    public void deleteAllFor(long userId) {
        jdbc.update("DELETE FROM sessions WHERE user_id = ?", userId);
    }

    public void purgeExpired() {
        jdbc.update("DELETE FROM sessions WHERE expires_at < ?",
                LocalDateTime.now().format(TS));
    }

    /** 取当前登录用户，没有就抛 401 */
    public Map<String, Object> require(Map<String, Object> user) {
        if (user == null) {
            throw new ApiException("请先登录", 401);
        }
        return user;
    }

    public static boolean isAdmin(Map<String, Object> user) {
        return user != null && "admin".equals(user.get("role"));
    }

    public static long id(Map<String, Object> user) {
        return ((Number) user.get("id")).longValue();
    }
}
