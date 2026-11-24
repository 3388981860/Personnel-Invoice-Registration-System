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
