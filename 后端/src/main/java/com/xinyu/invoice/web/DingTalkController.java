package com.xinyu.invoice.web;

import com.xinyu.invoice.auth.SessionService;
import com.xinyu.invoice.common.ApiException;
import com.xinyu.invoice.service.DingTalkService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 钉钉对接的接口：连通性、通讯录、绑定、试发 */
@RestController
public class DingTalkController extends BaseController {

    private final DingTalkService ding;

    @Value("${app.dingtalk.web-base:}")
    private String webBase;

    public DingTalkController(DingTalkService ding) {
        this.ding = ding;
    }

    // 部门 + 成员缓存，避免每次开表单都去拉一遍通讯录
    private static java.util.List<String> pickerDepts = null;
    private static java.util.List<Map<String, Object>> pickerMembers = null;
    private static long pickerAt = 0L;

    /**
     * 下拉选项：钉钉部门 + 钉钉成员。
     *
     * 开票申请这类表单只要有合同权限就能用，不该要求 users 权限，
     * 所以单独开一个轻量接口。
     */
    @GetMapping("/api/dingtalk/picker")
    public Map<String, Object> picker(HttpServletRequest req) {
        requirePerm(req, "contracts");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("configured", ding.configured());
        if (!ding.configured()) {
            out.put("departments", java.util.List.of());
            out.put("members", java.util.List.of());
            out.put("error", "服务端还没配置钉钉密钥");
            return out;
        }
        // 缓存 10 分钟
        if (pickerDepts == null || System.currentTimeMillis() - pickerAt > 600_000L) {
            try {
                pickerDepts = ding.deptNames();
            } catch (Exception e) {
                pickerDepts = new java.util.ArrayList<>();
            }
            try {
                pickerMembers = ding.directory();
            } catch (Exception e) {
                pickerMembers = new java.util.ArrayList<>();
            }
            pickerAt = System.currentTimeMillis();
        }
        out.put("departments", pickerDepts);
        java.util.List<Map<String, Object>> slim = new java.util.ArrayList<>();
        for (Map<String, Object> m : pickerMembers) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("userid", m.get("userid"));
            one.put("name", m.get("name"));
            one.put("mobile", m.get("mobile"));
            slim.add(one);
        }
        out.put("members", slim);
        return out;
    }

    @GetMapping("/api/dingtalk/status")
    public Map<String, Object> status(HttpServletRequest req) {
        requirePerm(req, "users");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("configured", ding.configured());
        out.put("web_base", webBase);
        try {
            ding.accessToken();
            out.put("token_ok", true);
        } catch (Exception e) {
            out.put("token_ok", false);
            out.put("error", e.getMessage());
        }
        List<Map<String, Object>> members = new ArrayList<>();
        String dirError = null;
        try {
            members = ding.directory();
        } catch (Exception e) {
            dirError = e.getMessage();
        }
        out.put("members", members);
        out.put("members_count", members.size());
        out.put("directory_error", dirError);
        return out;
    }

    /** 把系统账号和钉钉账号绑起来 */
    @PostMapping("/api/dingtalk/bind")
    public Map<String, Object> bind(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        requirePerm(req, "users");
        Object uidRaw = body.get("user_id");
        if (uidRaw == null) {
            throw new ApiException("缺少 user_id");
        }
        long userId = Long.parseLong(String.valueOf(uidRaw));
        String dtId = body.get("dingtalk_userid") == null ? "" : String.valueOf(body.get("dingtalk_userid")).trim();
        jdbc.update("UPDATE users SET dingtalk_userid = ? WHERE id = ?",
                dtId.isEmpty() ? null : dtId, userId);

        // 顺带把系统姓名改成钉钉里的名字（前端默认勾选，可以取消）
        boolean syncName = body.get("sync_name") != null
                && Boolean.parseBoolean(String.valueOf(body.get("sync_name")));
        String dtName = body.get("dingtalk_name") == null
                ? "" : String.valueOf(body.get("dingtalk_name")).trim();
        if (syncName && !dtName.isEmpty() && !dtId.isEmpty()) {
            jdbc.update("UPDATE users SET display_name = ? WHERE id = ?", dtName, userId);
        }
        return Map.of("ok", true);
    }

    /** 给某个人试发一条，确认链路通 */
    @PostMapping("/api/dingtalk/test")
    public Map<String, Object> test(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Map<String, Object> me = requirePerm(req, "users");
        long userId = body.get("user_id") == null
                ? uid(me) : Long.parseLong(String.valueOf(body.get("user_id")));
        String dt = ding.dingtalkIdOf(userId);
        if (dt == null || dt.isBlank()) {
            throw new ApiException("这个账号还没绑定钉钉，先绑定再试");
        }
        int n = ding.sendWorkNotice(List.of(dt), "人数行政平台",
                "这是一条测试消息，收到说明钉钉通知已经通了。",
                webBase.isBlank() ? null : webBase);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", n > 0);
        out.put("sent", n);
        if (n == 0) {
            out.put("error", "发送失败，看后台日志");
        }
        return out;
    }

    /** 报销单有新进展时，把消息顺带推到钉钉 */
    public void pushToDingTalk(List<Long> userIds, String title, String text, String path) {
        try {
            String url = webBase.isBlank() ? null : webBase.replaceAll("/+$", "") + path;
            ding.sendToUsers(userIds, title, text, url);
        } catch (Exception e) {
            // 钉钉挂了不能影响主流程
            org.slf4j.LoggerFactory.getLogger(DingTalkController.class)
                    .warn("钉钉推送失败（不影响业务）：{}", e.getMessage());
        }
    }
}
