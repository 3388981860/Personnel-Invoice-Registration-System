package com.xinyu.invoice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 钉钉对接：拿 token、读通讯录、发工作通知。
 *
 * 配置来自 application.yml 的 app.dingtalk.*，默认读环境变量，
 * 密钥不写进代码库。
 */
@Service
public class DingTalkService {

    private static final Logger log = LoggerFactory.getLogger(DingTalkService.class);

    @Value("${app.dingtalk.app-key:}")
    private String appKey;

    @Value("${app.dingtalk.app-secret:}")
    private String appSecret;

    @Value("${app.dingtalk.agent-id:}")
    private String agentId;

    @Value("${app.dingtalk.redirect-uri:}")
    private String redirectUri;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private volatile String token;
    private volatile long tokenExpireAt = 0;

    public DingTalkService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean configured() {
        return !appKey.isBlank() && !appSecret.isBlank() && !agentId.isBlank();
    }

    // ---------------------------------------------------------------- token

    /** 拿 access_token，缓存到快过期才重新取 */
    public synchronized String accessToken() {
        if (!configured()) {
            throw new IllegalStateException("钉钉还没配置（app-key / app-secret / agent-id）");
        }
        if (token != null && System.currentTimeMillis() < tokenExpireAt) {
            return token;
        }
        Map<String, Object> body = Map.of("appKey", appKey, "appSecret", appSecret);
        JsonNode r = postJson("https://api.dingtalk.com/v1.0/oauth2/accessToken", body);
        String t = r.path("accessToken").asText("");
        if (t.isEmpty()) {
            throw new IllegalStateException("钉钉返回没有 accessToken：" + r);
        }
        long expire = r.path("expireIn").asLong(7200);
        token = t;
        tokenExpireAt = System.currentTimeMillis() + (expire - 300) * 1000;
        return token;
    }

    // ---------------------------------------------------------------- 扫码登录

    /**
     * 用授权码换钉钉用户信息（unionId / 昵称 / 手机号）。
     *
     * 走的是「第三方网站登录」那套：先换 userAccessToken，再取 /contact/users/me。
     */
    public Map<String, Object> userInfoByCode(String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("clientId", appKey);
        body.put("clientSecret", appSecret);
        body.put("code", code);
        body.put("grantType", "authorization_code");
        JsonNode tokenResp = postJson("https://api.dingtalk.com/v1.0/oauth2/userAccessToken", body);
        String userToken = tokenResp.path("accessToken").asText("");
        if (userToken.isEmpty()) {
            throw new IllegalStateException("换用户令牌失败：" + tokenResp);
        }

        JsonNode me = getJson("https://api.dingtalk.com/v1.0/contact/users/me", userToken);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("unionId", me.path("unionId").asText(""));
        out.put("openId", me.path("openId").asText(""));
        out.put("nick", me.path("nick").asText(""));
        out.put("avatarUrl", me.path("avatarUrl").asText(""));
        out.put("mobile", me.path("mobile").asText(""));
        return out;
    }

    // ---------------------------------------------------------------- 通讯录

    /** 读全公司成员：userid / 姓名 / 手机 / 部门 */
    /** 通讯录缓存（10 分钟），避免每次导出都重新遍历部门 */
    private volatile List<Map<String, Object>> cacheDir;
    private volatile long cacheAt;
    private static final long CACHE_MS = 10 * 60 * 1000L;

    public List<Map<String, Object>> directory() {
        List<Map<String, Object>> cached = cacheDir;
        if (cached != null && System.currentTimeMillis() - cacheAt < CACHE_MS) {
            return cached;
        }
        List<Map<String, Object>> fresh = directoryUncached();
        cacheDir = fresh;
        cacheAt = System.currentTimeMillis();
        return fresh;
    }

    /** 强制重读通讯录 */
    public List<Map<String, Object>> directoryFresh() {
        cacheDir = null;
        return directory();
    }

    private List<Map<String, Object>> directoryUncached() {
        String tok = accessToken();
        List<Long> depts = new ArrayList<>();
        depts.add(1L);
        JsonNode sub = postJson("https://oapi.dingtalk.com/topapi/v2/department/listsub?access_token=" + tok,
                Map.of("dept_id", 1, "language", "zh_CN"));
        checkScope(sub, "通讯录部门信息读权限");
        for (JsonNode d : sub.path("result")) {
            depts.add(d.path("dept_id").asLong());
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (Long deptId : depts) {
            long cursor = 0;
            while (true) {
                JsonNode r = postJson("https://oapi.dingtalk.com/topapi/v2/user/list?access_token=" + tok,
                        Map.of("dept_id", deptId, "cursor", cursor, "size", 50, "language", "zh_CN"));
                checkScope(r, "通讯录成员信息读权限");
                JsonNode result = r.path("result");
                for (JsonNode u : result.path("list")) {
                    Map<String, Object> one = new LinkedHashMap<>();
                    one.put("userid", u.path("userid").asText(""));
                    one.put("name", u.path("name").asText(""));
                    one.put("mobile", u.path("mobile").asText(""));
                    one.put("title", u.path("title").asText(""));
                    one.put("dept_id", deptId);
                    one.put("active", u.path("active").asBoolean(true));
                    out.add(one);
                }
                if (!result.path("has_more").asBoolean(false)) {
                    break;
                }
                cursor = result.path("next_cursor").asLong(0);
            }
        }
        // 同一个人可能属于多个部门，按 userid 去重
        Map<String, Map<String, Object>> uniq = new LinkedHashMap<>();
        for (Map<String, Object> one : out) {
            uniq.putIfAbsent(String.valueOf(one.get("userid")), one);
        }
        return new ArrayList<>(uniq.values());
    }

    /** 部门树节点：dept_id / name / parent_id */
    public java.util.List<Map<String, Object>> deptTree() {
        String tok = accessToken();
        java.util.List<Map<String, Object>> out = new ArrayList<>();
        java.util.List<Long> queue = new ArrayList<>();
        java.util.List<Long> parents = new ArrayList<>();
        queue.add(1L);
        parents.add(0L);
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 200) {
            long parent = queue.remove(0);
            long grand = parents.remove(0);
            JsonNode sub = postJson("https://oapi.dingtalk.com/topapi/v2/department/listsub?access_token=" + tok,
                    Map.of("dept_id", parent, "language", "zh_CN"));
            for (JsonNode d : sub.path("result")) {
                long id = d.path("dept_id").asLong();
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("dept_id", id);
                one.put("name", d.path("name").asText(""));
                one.put("parent_id", parent == 1L ? 0L : parent);
                out.add(one);
                queue.add(id);
                parents.add(parent);
            }
        }
        return out;
    }

    /** userid → 所属部门 ID 列表 */
    public Map<String, java.util.List<Long>> deptIdsByUser() {
        String tok = accessToken();
        Map<String, java.util.List<Long>> out = new LinkedHashMap<>();
        java.util.List<Long> depts = new ArrayList<>();
        depts.add(1L);
        JsonNode sub = postJson("https://oapi.dingtalk.com/topapi/v2/department/listsub?access_token=" + tok,
                Map.of("dept_id", 1, "language", "zh_CN"));
        for (JsonNode d : sub.path("result")) {
            long id = d.path("dept_id").asLong();
            depts.add(id);
            JsonNode sub2 = postJson("https://oapi.dingtalk.com/topapi/v2/department/listsub?access_token=" + tok,
                    Map.of("dept_id", id, "language", "zh_CN"));
            for (JsonNode d2 : sub2.path("result")) {
                depts.add(d2.path("dept_id").asLong());
            }
        }
        for (Long deptId : depts) {
            long cursor = 0;
            while (true) {
                JsonNode r = postJson("https://oapi.dingtalk.com/topapi/v2/user/list?access_token=" + tok,
                        Map.of("dept_id", deptId, "cursor", cursor, "size", 50, "language", "zh_CN"));
                JsonNode result = r.path("result");
                for (JsonNode u : result.path("list")) {
                    String uid = u.path("userid").asText("");
                    if (uid.isEmpty() || deptId == 1L) {
                        continue;
                    }
                    out.computeIfAbsent(uid, k -> new ArrayList<>()).add(deptId);
                }
                if (!result.path("has_more").asBoolean(false)) {
                    break;
                }
                cursor = result.path("next_cursor").asLong(0);
            }
        }
        return out;
    }

    /** 钉钉部门树里的所有部门名（按层级展开，不含根组织） */
    public java.util.List<String> deptNames() {
        String tok = accessToken();
        java.util.List<String> out = new ArrayList<>();
        java.util.List<Long> queue = new ArrayList<>();
        queue.add(1L);
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 200) {
            long parent = queue.remove(0);
            JsonNode sub = postJson("https://oapi.dingtalk.com/topapi/v2/department/listsub?access_token=" + tok,
                    Map.of("dept_id", parent, "language", "zh_CN"));
            for (JsonNode d : sub.path("result")) {
                String name = d.path("name").asText("");
                if (!name.isEmpty()) {
                    out.add(name);
                }
                queue.add(d.path("dept_id").asLong());
            }
        }
        return out;
    }

    /**
     * 每个人在哪些部门（userid → 部门名列表）。
     *
     * 通讯录是按部门拉成员的，所以反过来遍历一遍就知道谁属于哪些部门。
     */
    public Map<String, java.util.List<String>> deptMapByUser() {
        String tok = accessToken();
        Map<Long, String> deptNames = new LinkedHashMap<>();
        deptNames.put(1L, "根组织");
        java.util.List<Long> depts = new ArrayList<>();
        depts.add(1L);
        JsonNode sub = postJson("https://oapi.dingtalk.com/topapi/v2/department/listsub?access_token=" + tok,
                Map.of("dept_id", 1, "language", "zh_CN"));
        for (JsonNode d : sub.path("result")) {
            long id = d.path("dept_id").asLong();
            depts.add(id);
            deptNames.put(id, d.path("name").asText(""));
            // 二级部门
            JsonNode sub2 = postJson("https://oapi.dingtalk.com/topapi/v2/department/listsub?access_token=" + tok,
                    Map.of("dept_id", id, "language", "zh_CN"));
            for (JsonNode d2 : sub2.path("result")) {
                depts.add(d2.path("dept_id").asLong());
                deptNames.put(d2.path("dept_id").asLong(), d2.path("name").asText(""));
            }
        }

        Map<String, java.util.List<String>> out = new LinkedHashMap<>();
        for (Long deptId : depts) {
            if (deptId == 1L) {
                continue;                      // 根组织不算部门
            }
            long cursor = 0;
            while (true) {
                JsonNode r = postJson("https://oapi.dingtalk.com/topapi/v2/user/list?access_token=" + tok,
                        Map.of("dept_id", deptId, "cursor", cursor, "size", 50, "language", "zh_CN"));
                JsonNode result = r.path("result");
                for (JsonNode u : result.path("list")) {
                    String uid = u.path("userid").asText("");
                    if (uid.isEmpty()) {
                        continue;
                    }
                    out.computeIfAbsent(uid, k -> new ArrayList<>())
                            .add(deptNames.getOrDefault(deptId, ""));
                }
                if (!result.path("has_more").asBoolean(false)) {
                    break;
                }
                cursor = result.path("next_cursor").asLong(0);
            }
        }
        return out;
    }

    /** 按手机号查钉钉 userid，查不到返回 null */
    public String userIdByMobile(String mobile) {
        if (mobile == null || mobile.isBlank()) {
            return null;
        }
        JsonNode r = postJson("https://oapi.dingtalk.com/topapi/v2/user/getbymobile?access_token=" + accessToken(),
                Map.of("mobile", mobile.trim()));
        if (r.path("errcode").asInt(0) != 0) {
            return null;
        }
        String id = r.path("result").path("userid").asText("");
        return id.isEmpty() ? null : id;
    }

    // ---------------------------------------------------------------- 发消息

    /**
     * 给指定钉钉用户发工作通知（点开能跳到系统页面）。
     *
     * @param userIds 钉钉 userid 列表
     * @param title   标题
     * @param text    正文
     * @param url     点开跳转的地址，可为空
     */
    public int sendWorkNotice(List<String> userIds, String title, String text, String url) {
        if (userIds == null || userIds.isEmpty()) {
            return 0;
        }
        if (!configured()) {
            log.debug("钉钉未配置，跳过发送");
            return 0;
        }
        String tok = accessToken();
        Map<String, Object> msg;
        if (url != null && !url.isBlank()) {
            // 用 markdown：link 类型要求必填 picUrl，我们没有图
            String md = "### " + title + "\n\n" + text
                    + "\n\n[点这里打开系统](" + url + ")";
            msg = Map.of(
                    "msgtype", "markdown",
                    "markdown", Map.of("title", title, "text", md));
        } else {
            msg = Map.of(
                    "msgtype", "text",
                    "text", Map.of("content", title + "\n" + text));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("agent_id", agentId);
        body.put("userid_list", String.join(",", userIds));
        body.put("msg", msg);
        JsonNode r = postJson("https://oapi.dingtalk.com/topapi/message/corpconversation/asyncsend_v2"
                + "?access_token=" + tok, body);
        if (r.path("errcode").asInt(0) != 0) {
            log.warn("钉钉发送失败：{}", r);
            return 0;
        }
        log.info("钉钉通知已发：{} → {} 人", title, userIds.size());
        return userIds.size();
    }

    /** 按系统用户 id 发（查他绑定的钉钉 userid） */
    public int sendToUsers(List<Long> userIds, String title, String text, String url) {
        if (userIds == null || userIds.isEmpty() || !configured()) {
            return 0;
        }
        List<String> ids = new ArrayList<>();
        for (Long uid : userIds) {
            String dt = dingtalkIdOf(uid);
            if (dt != null && !dt.isBlank()) {
                ids.add(dt);
            }
        }
        return sendWorkNotice(ids, title, text, url);
    }

    /** 某个系统账号绑定的钉钉 userid */
    public String dingtalkIdOf(Long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT dingtalk_userid FROM users WHERE id = ?", userId);
        if (rows.isEmpty()) {
            return null;
        }
        Object v = rows.get(0).get("dingtalk_userid");
        return v == null ? null : String.valueOf(v);
    }

    // ---------------------------------------------------------------- 审批

    /** 创建审批实例（用印申请等） */
    public com.fasterxml.jackson.databind.JsonNode createProcessInstance(Map<String, Object> body) {
        String tok = accessToken();
        return postJson("https://oapi.dingtalk.com/topapi/processinstance/create?access_token=" + tok, body);
    }

    /** 查审批实例详情 */
    public com.fasterxml.jackson.databind.JsonNode getProcessInstance(String instanceId) {
        String tok = accessToken();
        return postJson("https://oapi.dingtalk.com/topapi/processinstance/get?access_token=" + tok,
                Map.of("process_instance_id", instanceId));
    }

    /** 上传文件到钉钉素材库，返回 media_id */
    public String uploadFile(byte[] data, String filename, String type) {
        String tok = accessToken();
        String boundary = "----dsh" + System.currentTimeMillis();
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            String head = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"media\"; filename=\""
                    + (filename == null ? "a.bin" : filename) + "\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n";
            bos.write(head.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            bos.write(data);
            bos.write(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));

            HttpRequest req = HttpRequest.newBuilder(URI.create(
                            "https://oapi.dingtalk.com/media/upload?access_token=" + tok
                                    + "&type=" + (type == null ? "file" : type)))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bos.toByteArray()))
                    .build();
            HttpResponse<String> resp = http.send(req,
                    HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            var r = json.readTree(resp.body());
            if (r.path("errcode").asInt(-1) != 0) {
                throw new IllegalStateException("上传失败：" + r.path("errmsg").asText());
            }
            return r.path("media_id").asText("");
        } catch (Exception e) {
            throw new IllegalStateException("上传失败：" + e.getMessage(), e);
        }
    }

    /** 上传图片到钉钉，返回 media_id（用印照片字段要用） */
    public String uploadImage(byte[] data, String filename) {
        String tok = accessToken();
        String boundary = "----dsh" + System.currentTimeMillis();
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            String head = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"media\"; filename=\""
                    + (filename == null ? "a.png" : filename) + "\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n";
            bos.write(head.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            bos.write(data);
            bos.write(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));

            HttpRequest req = HttpRequest.newBuilder(URI.create(
                            "https://oapi.dingtalk.com/media/upload?access_token=" + tok + "&type=image"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bos.toByteArray()))
                    .build();
            HttpResponse<String> resp = http.send(req,
                    HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            var r = json.readTree(resp.body());
            if (r.path("errcode").asInt(-1) != 0) {
                throw new IllegalStateException("上传图片失败：" + r.path("errmsg").asText());
            }
            return r.path("media_id").asText("");
        } catch (Exception e) {
            throw new IllegalStateException("上传图片失败：" + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- 工具

    private JsonNode getJson(String url, String bearer) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("x-acs-dingtalk-access-token", bearer)
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return json.readTree(resp.body());
        } catch (Exception e) {
            throw new IllegalStateException("调用钉钉接口失败：" + e.getMessage(), e);
        }
    }

    private JsonNode postJson(String url, Object body) {
        try {
            String payload = json.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return json.readTree(resp.body());
        } catch (Exception e) {
            throw new IllegalStateException("调用钉钉接口失败：" + e.getMessage(), e);
        }
    }

    private void checkScope(JsonNode r, String need) {
        if (r.path("errcode").asInt(0) == 88
                || String.valueOf(r.path("sub_code")).equals("60011")) {
            throw new IllegalStateException("钉钉应用还没开通「" + need + "」，"
                    + "请到开放平台的权限管理里申请开通");
        }
    }
}
