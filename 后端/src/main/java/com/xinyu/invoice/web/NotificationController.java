package com.xinyu.invoice.web;

import com.xinyu.invoice.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 站内消息 */
@RestController
public class NotificationController extends BaseController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @GetMapping("/api/notifications")
    public Map<String, Object> list(HttpServletRequest req) {
        Map<String, Object> u = requireUser(req);
        List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT * FROM notifications WHERE user_id = ? "
                        + "ORDER BY id DESC LIMIT 100", uid(u));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("items", items);
        out.put("unread", unread(uid(u)));
        return out;
    }

    @PostMapping("/api/notifications/read")
    public Map<String, Object> read(@RequestBody Map<String, Object> body,
                                    HttpServletRequest req) {
        Map<String, Object> u = requireUser(req);
        List<Long> ids = new ArrayList<>();
        Object raw = body.get("ids");
        if (raw instanceof List<?> list) {
            for (Object x : list) {
                try {
                    ids.add(Long.parseLong(String.valueOf(x)));
                } catch (NumberFormatException ignored) {
                    // 跳过非法 id
                }
            }
        }
        String now = LocalDateTime.now().format(TS);
        if (ids.isEmpty()) {
            // 不传 id = 全部标为已读
            jdbc.update("UPDATE notifications SET read_at = ? "
                    + "WHERE user_id = ? AND read_at IS NULL", now, uid(u));
        } else {
            StringBuilder in = new StringBuilder();
            List<Object> args = new ArrayList<>();
            args.add(now);
            args.add(uid(u));
            for (Long id : ids) {
                if (in.length() > 0) {
                    in.append(",");
                }
                in.append("?");
                args.add(id);
            }
            jdbc.update("UPDATE notifications SET read_at = ? WHERE user_id = ? "
                    + "AND id IN (" + in + ")", args.toArray());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("unread", unread(uid(u)));
        return out;
    }

    private int unread(long uid) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE user_id = ? AND read_at IS NULL",
                Integer.class, uid);
        return n == null ? 0 : n;
    }
}
