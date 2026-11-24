package com.xinyu.invoice.web;

import com.xinyu.invoice.auth.SessionService;
import com.xinyu.invoice.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据字典：报销部门、报销项目这类下拉选项。
 *
 * 谁都能读（填报销单要用），管理员才能改。
 */
@RestController
public class DictController extends BaseController {

    private final com.xinyu.invoice.service.DingTalkService dingTalk;

    public DictController(com.xinyu.invoice.service.DingTalkService dingTalk) {
        this.dingTalk = dingTalk;
    }


    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @GetMapping("/api/dict")
    public Map<String, Object> list(@RequestParam(required = false) String type,
                                    HttpServletRequest req) {
        requireUser(req);
        StringBuilder sql = new StringBuilder(
                "SELECT id, type, name, sort_no, enabled FROM dict_items WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (type != null && !type.isBlank()) {
            sql.append(" AND type = ?");
            args.add(type);
        }
        sql.append(" ORDER BY type, sort_no, id");
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("items", rows);
        return out;
    }

    @PostMapping("/api/dict/save")
    public Map<String, Object> save(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        requirePerm(req, "dict");
        String type = str(body.get("type"));
        String name = str(body.get("name"));
        if (type.isEmpty()) {
            throw new ApiException("缺少字典类型");
        }
        if (name.isEmpty()) {
            throw new ApiException("名称不能为空");
        }
        // 同类型下不许重名
        List<Map<String, Object>> dup = jdbc.queryForList(
                "SELECT id FROM dict_items WHERE type = ? AND name = ?", type, name);
        Object idRaw = body.get("id");
        if (idRaw == null) {
            if (!dup.isEmpty()) {
                throw new ApiException("已经有「" + name + "」了", 409);
            }
            Integer max = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(sort_no),0) FROM dict_items WHERE type = ?", Integer.class, type);
            jdbc.update("INSERT INTO dict_items (type, name, sort_no, enabled, created_at)"
                            + " VALUES (?,?,?,?,?)",
                    type, name, (max == null ? 0 : max) + 10, 1,
                    LocalDateTime.now().format(TS));
        } else {
            long id = Long.parseLong(String.valueOf(idRaw));
            jdbc.update("UPDATE dict_items SET name = ?, enabled = ? WHERE id = ?",
                    name, body.get("enabled") == null ? 1 : ((Number) body.get("enabled")).intValue(), id);
        }
        return Map.of("ok", true);
    }

    /**
     * 一键导入：source = dingtalk（钉钉部门树）| project（合同项目）
     * 只补缺的，不动已有的。
     */
    @PostMapping("/api/dict/import")
    public Map<String, Object> importFrom(@RequestBody Map<String, Object> body,
                                          HttpServletRequest req) {
        requirePerm(req, "dict");
        String source = str(body.get("source"));
        List<String> names = new ArrayList<>();
        if ("dingtalk".equals(source)) {
            try {
                names = dingTalk.deptNames();
            } catch (Exception e) {
                throw new ApiException("读钉钉部门失败：" + e.getMessage(), 500);
            }
        } else if ("project".equals(source)) {
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT name FROM projects WHERE name IS NOT NULL AND name <> '' ORDER BY id")) {
                names.add(String.valueOf(r.get("name")).trim());
            }
        } else {
            throw new ApiException("不认识的来源：" + source);
        }

        String type = "dingtalk".equals(source) ? "dept" : "project";
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sort_no),0) FROM dict_items WHERE type = ?", Integer.class, type);
        int sort = max == null ? 0 : max;
        int added = 0;
        java.util.Set<String> exists = new java.util.HashSet<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT name FROM dict_items WHERE type = ?", type)) {
            exists.add(String.valueOf(r.get("name")).trim());
        }
        String now = LocalDateTime.now().format(TS);
        for (String n : names) {
            if (n.isEmpty() || exists.contains(n)) {
                continue;
            }
            sort += 10;
            jdbc.update("INSERT INTO dict_items (type, name, sort_no, enabled, created_at)"
                    + " VALUES (?,?,?,?,?)", type, n, sort, 1, now);
            exists.add(n);
            added++;
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("type", type);
        out.put("scanned", names.size());
        out.put("added", added);
        return out;
    }

    @PostMapping("/api/dict/delete")
    public Map<String, Object> delete(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        requirePerm(req, "dict");
        Object idRaw = body.get("id");
        if (idRaw == null) {
            throw new ApiException("缺少 id");
        }
        jdbc.update("DELETE FROM dict_items WHERE id = ?", Long.parseLong(String.valueOf(idRaw)));
        return Map.of("ok", true);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
