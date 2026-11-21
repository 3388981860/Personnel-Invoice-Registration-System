package com.xinyu.invoice.web;

import com.xinyu.invoice.auth.AuthFilter;
import com.xinyu.invoice.auth.PasswordTool;
import com.xinyu.invoice.auth.SessionService;
import com.xinyu.invoice.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 登录 / 注册 / 退出 / 改密码 / 会话信息 */
@RestController
public class AuthController extends BaseController {

    private static final String BUILD = "20261220";

    /** 登录失败限流：同一个用户名 5 次 / 5 分钟 */
    private final Map<String, int[]> fails = new ConcurrentHashMap<>();
    private final Map<String, Long> failSince = new ConcurrentHashMap<>();

    @Value("${app.login-fail-limit:5}")
    private int failLimit;

    @Value("${app.login-fail-window-seconds:300}")
    private int failWindow;

    // ---------------------------------------------------------------- 会话

    @GetMapping("/api/me")
    public Map<String, Object> me(HttpServletRequest req) {
        Map<String, Object> u = requireUser(req);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("user", publicUser(u));
        out.put("unread", unread(uid(u)));
        out.put("pending_reviews", pendingReviews());
        out.put("build", BUILD);
        out.put("can_open_local", canOpenLocal());
        out.put("perms", com.xinyu.invoice.auth.Perm.of(u));
        out.put("perm_all", com.xinyu.invoice.auth.Perm.ALL);
        return out;
    }

    @PostMapping("/api/login")
    public Map<String, Object> login(@RequestBody Map<String, Object> body,
                                     HttpServletRequest req, HttpServletResponse resp) {
        String username = str(body.get("username"));
        String password = str(body.get("password"));
        if (username.isBlank() || password.isEmpty()) {
            throw new ApiException("请填写用户名和密码");
        }
        checkThrottle(username);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM users WHERE username = ?", username);
        if (rows.isEmpty() || !PasswordTool.verify(password, str(rows.get(0).get("password_hash")))) {
            noteFail(username);
            throw new ApiException("用户名或密码不对", 401);
        }
        Map<String, Object> u = rows.get(0);
        if (((Number) u.get("status")).intValue() != 1) {
            throw new ApiException("这个账号已被停用", 403);
        }
        clearFail(username);
        jdbc.update("UPDATE users SET last_login_at = ? WHERE id = ?",
                java.time.LocalDateTime.now().format(SessionService.TS), u.get("id"));

        String token = sessions.create(((Number) u.get("id")).longValue(),
                clientIp(req), req.getHeader("User-Agent"));
        setSessionCookie(resp, token, req.isSecure());

        u.remove("password_hash");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("user", publicUser(u));
        return out;
    }

    @PostMapping("/api/logout")
    public Map<String, Object> logout(HttpServletRequest req, HttpServletResponse resp) {
        String token = (String) req.getAttribute("token");
        sessions.delete(token);
        clearCookie(resp);
        return Map.of("ok", true);
    }

    @PostMapping("/api/register")
    public Map<String, Object> register(@RequestBody Map<String, Object> body,
                                        HttpServletRequest req, HttpServletResponse resp) {
        String username = str(body.get("username")).strip();
        String password = str(body.get("password"));
        String display = str(body.get("display_name")).strip();

        String problem = PasswordTool.usernameProblem(username);
        if (problem != null) {
            throw new ApiException(problem, 400, "username");
        }
        String pw = PasswordTool.problem(password);
        if (pw != null) {
            throw new ApiException(pw, 400, "password");
        }
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, username);
        if (exists != null && exists > 0) {
            throw new ApiException("这个用户名已经被占用了", 409, "username");
        }
        String now = java.time.LocalDateTime.now().format(SessionService.TS);
        jdbc.update("INSERT INTO users (username, password_hash, display_name, role, status,"
                        + " must_change_pw, created_at) VALUES (?,?,?,?,?,?,?)",
                username, PasswordTool.hash(password),
                display.isBlank() ? username : display, "user", 1, 0, now);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM users WHERE username = ?", username);
        Map<String, Object> u = rows.get(0);
        String token = sessions.create(((Number) u.get("id")).longValue(),
                clientIp(req), req.getHeader("User-Agent"));
        setSessionCookie(resp, token, req.isSecure());
        log(((Number) u.get("id")).longValue(), username, "register", "user",
                ((Number) u.get("id")).longValue(), null);

        u.remove("password_hash");
        return Map.of("ok", true, "user", publicUser(u));
    }

    @PostMapping("/api/password")
    public Map<String, Object> changePassword(@RequestBody Map<String, Object> body,
                                              HttpServletRequest req,
                                              HttpServletResponse resp) {
        Map<String, Object> u = requireUser(req);
        String oldPwd = str(body.get("old"));
        String newPwd = str(body.get("new"));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT password_hash FROM users WHERE id = ?", u.get("id"));
        if (rows.isEmpty() || !PasswordTool.verify(oldPwd, str(rows.get(0).get("password_hash")))) {
            throw new ApiException("原密码不对", 400, "old");
        }
        String problem = PasswordTool.problem(newPwd);
        if (problem != null) {
            throw new ApiException(problem, 400, "new");
        }
        jdbc.update("UPDATE users SET password_hash = ?, must_change_pw = 0 WHERE id = ?",
                PasswordTool.hash(newPwd), u.get("id"));
        log(uid(u), str(u.get("username")), "change_password", "user", uid(u), null);
        return Map.of("ok", true);
    }

    // ---------------------------------------------------------------- 工具

    public static Map<String, Object> publicUser(Map<String, Object> u) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", u.get("id"));
        out.put("username", u.get("username"));
        out.put("display_name", u.getOrDefault("display_name", u.get("username")));
        out.put("role", u.get("role"));
        out.put("must_change_pw", u.get("must_change_pw"));
        out.put("status", u.get("status"));
        out.put("created_at", u.get("created_at"));
        out.put("last_login_at", u.get("last_login_at"));
        return out;
    }

    private int unread(long uid) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND read_at IS NULL",
                Integer.class, uid);
        return n == null ? 0 : n;
    }

    private int pendingReviews() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batches WHERE review_status = 'submitted'", Integer.class);
        return n == null ? 0 : n;
    }

    private boolean canOpenLocal() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private void setSessionCookie(HttpServletResponse resp, String token, boolean secure) {
        ResponseCookie c = ResponseCookie.from("sid", token)
                .httpOnly(true).sameSite("Lax").path("/")
                .maxAge(Duration.ofDays(7)).secure(secure).build();
        resp.addHeader(HttpHeaders.SET_COOKIE, c.toString());
    }

    private void clearCookie(HttpServletResponse resp) {
        ResponseCookie c = ResponseCookie.from("sid", "").httpOnly(true)
                .sameSite("Lax").path("/").maxAge(0).build();
        resp.addHeader(HttpHeaders.SET_COOKIE, c.toString());
    }

    private String clientIp(HttpServletRequest req) {
        String xf = req.getHeader("X-Forwarded-For");
        if (xf != null && !xf.isBlank()) {
            return xf.split(",")[0].strip();
        }
        return req.getRemoteAddr();
    }

    private void log(long userId, String username, String action, String targetType,
                     Long targetId, String detail) {
        jdbc.update("INSERT INTO audit_logs (user_id, username, action, target_type,"
                        + " target_id, detail, created_at) VALUES (?,?,?,?,?,?,?)",
                userId, username, action, targetType, targetId, detail,
                java.time.LocalDateTime.now().format(SessionService.TS));
    }

    private void checkThrottle(String username) {
        Long since = failSince.get(username);
        if (since == null) {
            return;
        }
        if (System.currentTimeMillis() - since > failWindow * 1000L) {
            clearFail(username);
            return;
        }
        int[] n = fails.get(username);
        if (n != null && n[0] >= failLimit) {
            throw new ApiException("密码错误次数太多，请 " + (failWindow / 60) + " 分钟后再试", 429);
        }
    }

    private void noteFail(String username) {
        fails.computeIfAbsent(username, k -> new int[1])[0]++;
        failSince.putIfAbsent(username, System.currentTimeMillis());
    }

    private void clearFail(String username) {
        fails.remove(username);
        failSince.remove(username);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
