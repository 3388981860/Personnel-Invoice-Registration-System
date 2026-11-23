package com.xinyu.invoice.web;

import com.xinyu.invoice.auth.Perm;
import com.xinyu.invoice.auth.SessionService;
import com.xinyu.invoice.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 公告：管理员发一条，所有人右上角消息里就会收到提醒。
 *
 * 公告本身存一份（便于回看/删除），同时给每个在用账号写一条通知，
 * 这样消息红点、未读计数都跟现有通知逻辑一致。
 */
@RestController
public class AnnouncementController extends BaseController {

    private final com.xinyu.invoice.service.DingTalkService dingTalk;

    public AnnouncementController(com.xinyu.invoice.service.DingTalkService dingTalk) {
        this.dingTalk = dingTalk;
    }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @GetMapping("/api/announcements")
    public Map<String, Object> list(HttpServletRequest req) {
        Map<String, Object> u = requireUser(req);
        List<Map<String, Object>> rows = new ArrayList<>();
        long me = uid(u);
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT a.id, a.title, a.content, a.created_at, a.created_by,"
                        + " (SELECT COALESCE(NULLIF(display_name,''), username) FROM users"
                        + "   WHERE id = a.created_by) AS author,"
                        + " (SELECT CASE WHEN n.read_at IS NULL THEN 0 ELSE 1 END"
                        + "    FROM notifications n WHERE n.announcement_id = a.id"
                        + "      AND n.user_id = ? LIMIT 1) AS is_read,"
                        + " (SELECT COUNT(*) FROM notifications n WHERE n.announcement_id = a.id"
                        + "    AND n.read_at IS NOT NULL) AS ack_count,"
                        + " (SELECT COUNT(*) FROM notifications n WHERE n.announcement_id = a.id)"
                        + "    AS sent_count,"
                        + " (SELECT GROUP_CONCAT(COALESCE(NULLIF(u2.display_name,''), u2.username), '、')"
                        + "    FROM notifications n JOIN users u2 ON u2.id = n.user_id"
                        + "    WHERE n.announcement_id = a.id AND n.read_at IS NULL) AS pending_names"
                        + " FROM announcements a ORDER BY a.id DESC LIMIT 100", me)) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", r.get("id"));
            one.put("title", r.get("title"));
            one.put("content", r.get("content"));
            one.put("created_at", r.get("created_at"));
            one.put("author", r.get("author"));
            one.put("read", r.get("is_read") != null && ((Number) r.get("is_read")).intValue() == 1);
            one.put("ack_count", r.get("ack_count"));
            one.put("sent_count", r.get("sent_count"));
            one.put("pending_names", r.get("pending_names"));
            rows.add(one);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("announcements", rows);
        out.put("can_publish", SessionService.isAdmin(u) || Perm.has(u, "notice"));
        return out;
    }

    @PostMapping("/api/announcements/save")
    public Map<String, Object> save(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Map<String, Object> u = requireUser(req);
        if (!SessionService.isAdmin(u) && !Perm.has(u, "notice")) {
            throw new ApiException("只有管理员能发布公告", 403);
        }
        String title = str(body.get("title"));
        String content = str(body.get("content"));
        if (title.isEmpty()) {
            throw new ApiException("公告标题不能为空");
        }
        if (content.isEmpty()) {
            throw new ApiException("公告内容不能为空");
        }
        String now = LocalDateTime.now().format(TS);
        jdbc.update("INSERT INTO announcements (title, content, created_by, created_at)"
                + " VALUES (?,?,?,?)", title, content, uid(u), now);
        Integer aid = jdbc.queryForObject("SELECT MAX(id) FROM announcements", Integer.class);

        // 给每个在用账号发一条通知（发的人自己也留一条，方便回看）
        List<Map<String, Object>> users = jdbc.queryForList("SELECT id FROM users WHERE status = 1");
        for (Map<String, Object> one : users) {
            jdbc.update("INSERT INTO notifications (user_id, kind, title, body, announcement_id,"
                            + " created_at) VALUES (?,?,?,?,?,?)",
                    one.get("id"), "notice", "公告：" + title,
                    content.length() > 120 ? content.substring(0, 120) + "…" : content,
                    aid, now);
        }
        // 顺带推到钉钉（绑定了的人才会收到）
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> one : users) {
            ids.add(((Number) one.get("id")).longValue());
        }
        int ding = 0;
        try {
            ding = dingTalk.sendToUsers(ids, "公告：" + title, content, null);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(AnnouncementController.class)
                    .warn("公告钉钉推送失败：{}", e.getMessage());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", aid);
        out.put("notified", users.size());
        out.put("dingtalk_sent", ding);
        return out;
    }

    /** 我已知晓：把这条公告对应的通知标记为已读（消息里也会变成已读） */
    @PostMapping("/api/announcements/ack")
    public Map<String, Object> ack(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Map<String, Object> u = requireUser(req);
        Object idRaw = body.get("id");
        if (idRaw == null) {
            throw new ApiException("缺少 id");
        }
        long aid = Long.parseLong(String.valueOf(idRaw));
        long me = uid(u);
        String now = LocalDateTime.now().format(TS);
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM announcements WHERE id = ?", Integer.class, aid);
        if (exists == null || exists == 0) {
            throw new ApiException("找不到这条公告", 404);
        }
        int n = jdbc.update("UPDATE notifications SET read_at = ?"
                + " WHERE user_id = ? AND announcement_id = ? AND read_at IS NULL", now, me, aid);
        if (n == 0) {
            // 没收到过这条通知（比如公告发在他注册之前），补一条已读的，避免下次又冒出来
            Integer has = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND announcement_id = ?",
                    Integer.class, me, aid);
            if (has == null || has == 0) {
                jdbc.update("INSERT INTO notifications (user_id, kind, title, body,"
                                + " announcement_id, created_at, read_at) VALUES (?,?,?,?,?,?,?)",
                        me, "notice", "公告：" + str(body.get("title")), "", aid, now, now);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        return out;
    }

    @PostMapping("/api/announcements/delete")
    public Map<String, Object> delete(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Map<String, Object> u = requireUser(req);
        if (!SessionService.isAdmin(u) && !Perm.has(u, "notice")) {
            throw new ApiException("只有管理员能删公告", 403);
        }
        Object idRaw = body.get("id");
        if (idRaw == null) {
            throw new ApiException("缺少 id");
        }
        long id = Long.parseLong(String.valueOf(idRaw));
        jdbc.update("DELETE FROM notifications WHERE announcement_id = ?", id);
        jdbc.update("DELETE FROM announcements WHERE id = ?", id);
        return Map.of("ok", true);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
