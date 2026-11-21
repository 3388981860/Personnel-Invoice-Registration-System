package com.xinyu.invoice.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 操作日志（仅管理员） */
@RestController
public class LogController extends BaseController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @GetMapping("/api/logs")
    public Map<String, Object> logs(@RequestParam(required = false, defaultValue = "30") int days,
                                    @RequestParam(required = false) String q,
                                    @RequestParam(required = false, defaultValue = "500") int limit,
                                    HttpServletRequest req) {
        requireAdmin(req);

        StringBuilder sql = new StringBuilder(
                "SELECT * FROM audit_logs WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (days > 0 && days < 3650) {
            sql.append(" AND created_at >= ?");
            args.add(LocalDateTime.now().minusDays(days).format(TS));
        }
        if (q != null && !q.isBlank()) {
            sql.append(" AND (detail LIKE ? OR username LIKE ? OR action LIKE ?)");
            String like = "%" + q.strip() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        args.add(Math.max(1, Math.min(limit, 2000)));

        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("logs", rows);
        return out;
    }
}
