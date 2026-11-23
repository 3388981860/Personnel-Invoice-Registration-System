package com.xinyu.invoice.web;

import com.xinyu.invoice.auth.SessionService;
import com.xinyu.invoice.service.DingTalkService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 钉钉扫码登录。
 *
 * 流程：
 *   登录页点「钉钉登录」→ 后端给授权地址（带 state）
 *   → 钉钉扫码 → 回调本服务 → 换用户信息 → 匹配系统账号 → 建会话 → 回工作台
 *
 * 匹配规则（方案 A）：
 *   1. 账号已绑过 unionId → 直接登录
 *   2. 没绑过 → 用手机号查钉钉 userid，找 dingtalk_userid 相同的账号 → 绑定后登录
 *   3. 都匹配不上 → 提示「账号未开通，请联系管理员」
 */
@RestController
public class DingTalkLoginController {

    private static final Logger log = LoggerFactory.getLogger(DingTalkLoginController.class);

    private final DingTalkService ding;
    private final SessionService sessions;
    private final JdbcTemplate jdbc;

    /** state 防伪造，5 分钟有效 */
    private final Map<String, Long> states = new ConcurrentHashMap<>();

    @Value("${app.dingtalk.client-id:${app.dingtalk.app-key:}}")
    private String clientId;

    @Value("${app.dingtalk.redirect-uri:}")
    private String redirectUri;

    /** 扫码的人如果没有系统账号，要不要自动开一个（默认开） */
    @Value("${app.dingtalk.auto-create:true}")
    private boolean autoCreate;

    public DingTalkLoginController(DingTalkService ding, SessionService sessions, JdbcTemplate jdbc) {
        this.ding = ding;
        this.sessions = sessions;
        this.jdbc = jdbc;
    }

    /** 登录页拿授权地址 */
    @GetMapping("/api/dingtalk/login-url")
    public Map<String, Object> loginUrl(HttpServletRequest req) {
        if (!ding.configured()) {
            throw new com.xinyu.invoice.common.ApiException("服务端还没配置钉钉", 400);
        }
        if (redirectUri == null || redirectUri.isBlank()) {
            throw new com.xinyu.invoice.common.ApiException(
                    "还没配置 DINGTALK_REDIRECT_URI（钉钉回调地址）", 400);
        }
        String state = UUID.randomUUID().toString().replace("-", "");
        states.put(state, System.currentTimeMillis());
        // 清掉超过 5 分钟的
        long now = System.currentTimeMillis();
        states.entrySet().removeIf(e -> now - e.getValue() > 5 * 60_000);

        String url = "https://login.dingtalk.com/oauth2/auth"
                + "?redirect_uri=" + enc(redirectUri)
                + "&response_type=code"
                + "&client_id=" + enc(clientId)
                + "&scope=openid"
                + "&state=" + state
                + "&prompt=consent";
        return Map.of("ok", true, "url", url);
    }

    /** 钉钉回调：换用户、匹配账号、建会话 */
    @GetMapping("/api/dingtalk/callback")
    public void callback(@RequestParam(required = false) String code,
                         @RequestParam(required = false) String state,
                         HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String stateKey = state == null ? "" : state;
        Long issued = states.remove(stateKey);
        if (issued == null || System.currentTimeMillis() - issued > 5 * 60_000) {
            redirect(resp, "/login?ding_error=" + enc("登录已过期，请重新扫码"));
            return;
        }
        if (code == null || code.isBlank()) {
            redirect(resp, "/login?ding_error=" + enc("没有拿到授权码"));
            return;
        }

        try {
            Map<String, Object> info = ding.userInfoByCode(code);
            String unionId = str(info.get("unionId"));
            String nick = str(info.get("nick"));
            String mobile = str(info.get("mobile"));

            Map<String, Object> user = matchUser(unionId, mobile, nick);
            if (user == null) {
                if (!autoCreate) {
                    redirect(resp, "/login?ding_error=" + enc(
                            "钉钉账号「" + nick + "」还没开通系统账号，请联系管理员"));
                    return;
                }
                user = createFromDingTalk(unionId, nick, mobile);
                log.info("钉钉扫码自动开通账号：{}（dongtalk unionId {})", nick, unionId);
            }
            if (((Number) user.get("status")).intValue() != 1) {
                redirect(resp, "/login?ding_error=" + enc("这个账号已被停用"));
                return;
            }

            // 首次登录：把 unionId 记下，下次直接认
            jdbc.update("UPDATE users SET dingtalk_union_id = ?, last_login_at = ? WHERE id = ?",
                    unionId, LocalDateTime.now().format(SessionService.TS), user.get("id"));

            String token = sessions.create(((Number) user.get("id")).longValue(),
                    clientIp(req), req.getHeader("User-Agent"));
            setCookie(resp, token, req.isSecure());
            redirect(resp, "/entry");
        } catch (Exception e) {
            log.warn("钉钉登录失败：{}", e.getMessage());
            redirect(resp, "/login?ding_error=" + enc("钉钉登录失败：" + e.getMessage()));
        }
    }

    /**
     * 扫码的人没有系统账号时，直接开一个。
     *
     * 用户名优先用手机号（好认），没手机号就用 unionId 前缀；
     * 权限留空，等管理员分配。
     */
    private Map<String, Object> createFromDingTalk(String unionId, String nick, String mobile) {
        String username = (mobile != null && !mobile.isBlank()) ? mobile.trim()
                : "dt_" + unionId.substring(0, Math.min(10, unionId.length()));
        // 重名就加后缀
        String base = username;
        int n = 2;
        while (!jdbc.queryForList("SELECT id FROM users WHERE username = ?", username).isEmpty()) {
            username = base + "_" + n;
            n++;
        }

        String display = (nick == null || nick.isBlank()) ? username : nick.trim();
        String now = LocalDateTime.now().format(SessionService.TS);
        // 随机密码：他们用钉钉登录，不需要知道密码；管理员可重置
        String pwd = com.xinyu.invoice.auth.PasswordTool.hash(
                UUID.randomUUID().toString().replace("-", ""));

        jdbc.update("INSERT INTO users (username, password_hash, display_name, role, status,"
                        + " must_change_pw, perms, dingtalk_userid, dingtalk_union_id,"
                        + " created_at, last_login_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                username, pwd, display, "user", 1, 0, com.xinyu.invoice.auth.Perm.serialize(com.xinyu.invoice.auth.Perm.DEFAULT), null, unionId, now, now);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM users WHERE username = ?", username);
        Map<String, Object> u = rows.get(0);

        // 顺手把手机号对应的钉钉 userid 也绑上，方便后面发工作通知
        try {
            String dtId = ding.userIdByMobile(mobile);
            if (dtId != null && !dtId.isBlank()) {
                jdbc.update("UPDATE users SET dingtalk_userid = ? WHERE id = ?", dtId, u.get("id"));
                u.put("dingtalk_userid", dtId);
            }
        } catch (Exception e) {
            log.debug("补绑钉钉 userid 失败：{}", e.getMessage());
        }

try {
            jdbc.update("INSERT INTO audit_logs (user_id, username, action, target_type,"
                            + " target_id, detail, created_at) VALUES (?,?,?,?,?,?,?)",
                    u.get("id"), username, "user_create", "user", u.get("id"),
                    "钉钉扫码自动开通（" + display + "）", now);
            // 告诉管理员：来了个新同事
            for (Map<String, Object> a : jdbc.queryForList(
                    "SELECT id FROM users WHERE role = 'admin' AND status = 1")) {
                jdbc.update("INSERT INTO notifications (user_id, kind, title, body, created_at)"
                                + " VALUES (?,?,?,?,?)",
                        a.get("id"), "user", "新同事加入",
                        display + " 用钉钉扫码登录，已自动开通账号（权限待分配）", now);
            }
        } catch (Exception ignored) {
            // 日志/通知失败不影响登录
        }
        return u;
    }

    /** 依次尝试：unionId → 手机号对应的钉钉 userid → 姓名 */
    private Map<String, Object> matchUser(String unionId, String mobile, String nick) {
        List<Map<String, Object>> byUnion = jdbc.queryForList(
                "SELECT * FROM users WHERE dingtalk_union_id = ?", unionId);
        if (!byUnion.isEmpty()) {
            return byUnion.get(0);
        }

        String dtUserId = null;
        try {
            dtUserId = ding.userIdByMobile(mobile);
        } catch (Exception e) {
            log.debug("按手机号查钉钉用户失败：{}", e.getMessage());
        }
        if (dtUserId != null && !dtUserId.isBlank()) {
            List<Map<String, Object>> byId = jdbc.queryForList(
                    "SELECT * FROM users WHERE dingtalk_userid = ?", dtUserId);
            if (!byId.isEmpty()) {
                return byId.get(0);
            }
        }

        if (nick != null && !nick.isBlank()) {
            List<Map<String, Object>> byName = jdbc.queryForList(
                    "SELECT * FROM users WHERE display_name = ? AND status = 1", nick);
            if (byName.size() == 1) {
                return byName.get(0);
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 工具

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }

    private void setCookie(HttpServletResponse resp, String token, boolean secure) {
        String c = "sid=" + token + "; Path=/; Max-Age=" + (7 * 24 * 3600)
                + "; HttpOnly; SameSite=Lax" + (secure ? "; Secure" : "");
        resp.addHeader("Set-Cookie", c);
    }

    private void redirect(HttpServletResponse resp, String path) throws IOException {
        resp.setStatus(302);
        resp.setHeader("Location", path);
    }
}
