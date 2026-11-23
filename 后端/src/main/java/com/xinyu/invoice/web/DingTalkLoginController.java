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
                redirect(resp, "/login?ding_error=" + enc(
                        "钉钉账号「" + nick + "」还没开通系统账号，请联系管理员"));
                return;
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
