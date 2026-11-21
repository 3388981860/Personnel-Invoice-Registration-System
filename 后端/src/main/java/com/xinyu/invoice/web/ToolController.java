package com.xinyu.invoice.web;

import com.xinyu.invoice.auth.Perm;
import com.xinyu.invoice.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具箱：把外面的小工具（公文助手、PDF 工具箱之类）挂到系统里点开就用。
 *
 * 只存名称和地址，点卡片直接在新标签页打开那个地址——不在后端做代理，
 * 免得把别人的页面内容、登录态搅进我们系统。
 */
@RestController
public class ToolController extends BaseController {

    private static final String COLS =
            "SELECT id, name, url, description, icon, category, sort_no, enabled FROM tools ";

    /** 列表：所有人都能看（工具箱本身是常用入口） */
    @GetMapping("/api/tools")
    public Map<String, Object> list(HttpServletRequest req) {
        Map<String, Object> u = requireUser(req);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(COLS + "ORDER BY sort_no, id")) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", r.get("id"));
            one.put("name", r.get("name"));
            one.put("url", r.get("url"));
            one.put("description", r.get("description"));
            one.put("icon", r.get("icon"));
            one.put("category", r.get("category"));
            one.put("sort_no", r.get("sort_no"));
            one.put("enabled", r.get("enabled"));
            rows.add(one);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("tools", rows);
        out.put("can_edit", canEdit(u));
        return out;
    }

    /** 新增或修改：有管理权限的人才能改 */
    @PostMapping("/api/tools/save")
    public Map<String, Object> save(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Map<String, Object> u = requireUser(req);
        if (!canEdit(u)) {
            throw new ApiException("只有管理员能改工具箱", 403);
        }
        String name = str(body.get("name"));
        if (name.isEmpty()) {
            throw new ApiException("名称不能为空");
        }
        String url = str(body.get("url"));
        if (!url.isEmpty() && !url.startsWith("http")) {
            url = "http://" + url;
        }
        Object idRaw = body.get("id");
        if (idRaw == null) {
            Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(sort_no),0) FROM tools", Integer.class);
            jdbc.update("INSERT INTO tools (name, url, description, icon, category, sort_no, enabled)"
                            + " VALUES (?,?,?,?,?,?,1)",
                    name, url, str(body.get("description")), str(body.get("icon")),
                    str(body.get("category")), (max == null ? 0 : max) + 10);
        } else {
            long id = Long.parseLong(String.valueOf(idRaw));
            jdbc.update("UPDATE tools SET name=?, url=?, description=?, icon=?, category=?"
                            + " WHERE id=?",
                    name, url, str(body.get("description")), str(body.get("icon")),
                    str(body.get("category")), id);
        }
        return Map.of("ok", true);
    }

    @PostMapping("/api/tools/delete")
    public Map<String, Object> delete(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Map<String, Object> u = requireUser(req);
        if (!canEdit(u)) {
            throw new ApiException("只有管理员能改工具箱", 403);
        }
        Object idRaw = body.get("id");
        if (idRaw == null) {
            throw new ApiException("缺少 id");
        }
        jdbc.update("DELETE FROM tools WHERE id = ?", Long.parseLong(String.valueOf(idRaw)));
        return Map.of("ok", true);
    }

    /** 管理员，或者被单独授予「合同台账」这类管理权限的人 */
    private boolean canEdit(Map<String, Object> u) {
        return com.xinyu.invoice.auth.SessionService.isAdmin(u) || Perm.has(u, "tools");
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
