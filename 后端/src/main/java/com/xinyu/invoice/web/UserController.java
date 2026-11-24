package com.xinyu.invoice.web;

import com.xinyu.invoice.auth.PasswordTool;
import com.xinyu.invoice.auth.SessionService;
import com.xinyu.invoice.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 用户管理（仅管理员） */
@RestController
public class UserController extends BaseController {

    private final com.xinyu.invoice.service.DingTalkService dingTalk;

    public UserController(com.xinyu.invoice.service.DingTalkService dingTalk) {
        this.dingTalk = dingTalk;
    }


    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 从钉钉通讯录同步所有人的部门 */
    @PostMapping("/api/users/sync-dept")
    public Map<String, Object> syncDept(HttpServletRequest req) {
        requirePerm(req, "users");
        try {
            java.util.Map<String, java.util.List<String>> names = dingTalk.deptMapByUser();
            java.util.Map<String, java.util.List<Long>> ids = dingTalk.deptIdsByUser();
            int n = 0;
            for (Map<String, Object> u : jdbc.queryForList(
                    "SELECT id, dingtalk_userid FROM users WHERE dingtalk_userid IS NOT NULL")) {
                String dtId = String.valueOf(u.get("dingtalk_userid"));
                java.util.List<String> depts = names.get(dtId);
                if (depts == null || depts.isEmpty()) {
                    continue;
                }
                java.util.List<Long> deptIds = ids.get(dtId);
                String idStr = "";
                if (deptIds != null && !deptIds.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (Long d : deptIds) {
                        if (sb.length() > 0) {
                            sb.append(",");
                        }
                        sb.append(d);
                    }
                    idStr = sb.toString();
                }
                jdbc.update("UPDATE users SET dept_name = ?, dept_ids = ? WHERE id = ?",
                        String.join("、", depts), idStr, u.get("id"));
                n++;
            }
            return Map.of("ok", true, "updated", n, "total_dt", names.size());
        } catch (Exception e) {
            throw new ApiException("同步部门失败：" + e.getMessage(), 500);
        }
    }

    /** 把钉钉通讯录里的人全部建成系统账号（已存在的只补部门和绑定） */
    @PostMapping("/api/users/import-dingtalk")
    public Map<String, Object> importDingTalk(HttpServletRequest req) {
        requirePerm(req, "users");
        java.util.List<Map<String, Object>> members;
        java.util.Map<String, java.util.List<String>> deptMap;
        java.util.Map<String, java.util.List<Long>> deptIdMap;
        try {
            members = dingTalk.directory();
            deptMap = dingTalk.deptMapByUser();
            deptIdMap = dingTalk.deptIdsByUser();
        } catch (Exception e) {
            throw new ApiException("读钉钉通讯录失败：" + e.getMessage(), 500);
        }

        java.util.function.Function<String, String> idStr = dtId -> {
            java.util.List<Long> ids = deptIdMap.get(dtId);
            if (ids == null || ids.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (Long d : ids) {
                if (sb.length() > 0) {
                    sb.append(",");
                }
                sb.append(d);
            }
            return sb.toString();
        };

        int created = 0, bound = 0, updated = 0, skipped = 0;
        String now = java.time.LocalDateTime.now().format(TS);
        for (Map<String, Object> m : members) {
            String dtId = m.get("userid") == null ? "" : String.valueOf(m.get("userid")).trim();
            String name = m.get("name") == null ? "" : String.valueOf(m.get("name")).trim();
            String mobile = m.get("mobile") == null ? "" : String.valueOf(m.get("mobile")).trim();
            if (dtId.isEmpty() || name.isEmpty()) {
                skipped++;
                continue;
            }
            String dept = String.join("、", deptMap.getOrDefault(dtId, java.util.List.of()));

            // ① 已经绑过这个钉钉 id
            java.util.List<Map<String, Object>> byDt = jdbc.queryForList(
                    "SELECT id FROM users WHERE dingtalk_userid = ?", dtId);
            if (!byDt.isEmpty()) {
                jdbc.update("UPDATE users SET dept_name = ?, dept_ids = ?,"
                                + " display_name = COALESCE(NULLIF(display_name,''), ?) WHERE id = ?",
                        dept, idStr.apply(dtId), name, byDt.get(0).get("id"));
                updated++;
                continue;
            }

            // ② 有同名/同手机号的账号：把钉钉绑上去
            java.util.List<Map<String, Object>> byMobile = mobile.isEmpty()
                    ? new java.util.ArrayList<>()
                    : jdbc.queryForList("SELECT id FROM users WHERE username = ?", mobile);
            java.util.List<Map<String, Object>> byName = jdbc.queryForList(
                    "SELECT id FROM users WHERE display_name = ?", name);
            java.util.List<Map<String, Object>> hit = !byMobile.isEmpty() ? byMobile : byName;
            if (!hit.isEmpty()) {
                jdbc.update("UPDATE users SET dingtalk_userid = ?, dept_name = ?, dept_ids = ?"
                                + " WHERE id = ?",
                        dtId, dept, idStr.apply(dtId), hit.get(0).get("id"));
                bound++;
                continue;
            }

            // ③ 新建：用户名用手机号，没手机号就用钉钉 id
            String username = mobile.isEmpty() ? dtId : mobile;
            if (!jdbc.queryForList("SELECT id FROM users WHERE username = ?", username).isEmpty()) {
                username = dtId;
            }
            String pwd = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            jdbc.update("INSERT INTO users (username, password_hash, display_name, role, status,"
                            + " must_change_pw, perms, dingtalk_userid, dept_name, dept_ids,"
                            + " created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    username,
                    com.xinyu.invoice.auth.PasswordTool.hash(pwd),
                    name, "user", 1, 1,
                    com.xinyu.invoice.auth.Perm.serialize(
                            new java.util.ArrayList<>(com.xinyu.invoice.auth.Perm.DEFAULT)),
                    dtId, dept, idStr.apply(dtId), now);
            created++;
        }

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("ok", true);
        out.put("total", members.size());
        out.put("created", created);
        out.put("bound", bound);
        out.put("updated", updated);
        out.put("skipped", skipped);
        return out;
    }

    /** 组织架构树（钉钉部门 + 每个部门有多少人） */
    @GetMapping("/api/users/org")
    public Map<String, Object> org(HttpServletRequest req) {
        requirePerm(req, "users");
        java.util.List<Map<String, Object>> flat;
        try {
            flat = dingTalk.deptTree();
        } catch (Exception e) {
            flat = new java.util.ArrayList<>();
        }

        // 每个人属于哪些部门（用 dept_ids，逗号分隔）
        java.util.List<Map<String, Object>> users = jdbc.queryForList(
                "SELECT id, dept_ids FROM users");
        java.util.Map<Long, Integer> counts = new java.util.HashMap<>();
        int noDept = 0;
        for (Map<String, Object> u : users) {
            String ids = u.get("dept_ids") == null ? "" : String.valueOf(u.get("dept_ids"));
            if (ids.isBlank()) {
                noDept++;
                continue;
            }
            for (String s : ids.split(",")) {
                try {
                    long d = Long.parseLong(s.trim());
                    counts.merge(d, 1, Integer::sum);
                } catch (NumberFormatException ignored) {
                    // 脏数据跳过
                }
            }
        }

        // 拼成树
        java.util.Map<Long, Map<String, Object>> byId = new java.util.LinkedHashMap<>();
        for (Map<String, Object> d : flat) {
            long id = ((Number) d.get("dept_id")).longValue();
            Map<String, Object> node = new java.util.LinkedHashMap<>();
            node.put("id", id);
            node.put("name", d.get("name"));
            node.put("parent_id", ((Number) d.get("parent_id")).longValue());
            node.put("count", counts.getOrDefault(id, 0));
            node.put("children", new java.util.ArrayList<Map<String, Object>>());
            byId.put(id, node);
        }
        java.util.List<Map<String, Object>> roots = new java.util.ArrayList<>();
        for (Map<String, Object> node : byId.values()) {
            long parent = ((Number) node.get("parent_id")).longValue();
            Map<String, Object> p = parent == 0 ? null : byId.get(parent);
            if (p == null) {
                roots.add(node);
            } else {
                @SuppressWarnings("unchecked")
                java.util.List<Map<String, Object>> kids =
                        (java.util.List<Map<String, Object>>) p.get("children");
                kids.add(node);
            }
        }

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("ok", true);
        out.put("total", users.size());
        out.put("no_dept", noDept);
        out.put("tree", roots);
        return out;
    }

    @GetMapping("/api/users")
    public Map<String, Object> list(HttpServletRequest req) {
        requirePerm(req, "users");
        List<Map<String, Object>> users = new ArrayList<>();
        for (Map<String, Object> u : jdbc.queryForList(
                "SELECT * FROM users ORDER BY id")) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", u.get("id"));
            one.put("username", u.get("username"));
            one.put("display_name", u.get("display_name"));
            one.put("role", u.get("role"));
            one.put("status", u.get("status") == null ? 0 : ((Number) u.get("status")).intValue());
            one.put("created_at", u.get("created_at"));
            one.put("last_login_at", u.get("last_login_at"));
one.put("perms", com.xinyu.invoice.auth.Perm.of(u));
            one.put("dingtalk_userid", u.get("dingtalk_userid"));
            one.put("dept_name", u.get("dept_name"));
            one.put("dept_ids", u.get("dept_ids"));
            one.put("dingtalk_title", u.get("dingtalk_title"));
            one.put("perms_custom", u.get("perms") != null
                    && !String.valueOf(u.get("perms")).isBlank());
            users.add(one);
        }
        return Map.of("ok", true, "users", users);
    }

    @PostMapping("/api/users/create")
    public Map<String, Object> create(@RequestBody Map<String, Object> body,
                                      HttpServletRequest req) {
        Map<String, Object> admin = requirePerm(req, "users");
        String username = str(body.get("username")).strip();
        String password = str(body.get("password"));
        boolean generated = password.isBlank();
        if (generated) {
            password = PasswordTool.newPassword(10);
        }
        String display = str(body.get("display_name")).strip();
        String role = "admin".equals(str(body.get("role"))) ? "admin" : "user";

        String problem = PasswordTool.usernameProblem(username);
        if (problem != null) {
            throw new ApiException(problem, 400, "username");
        }
        String pwdProblem = PasswordTool.problem(password);
        if (pwdProblem != null && !generated) {
            throw new ApiException(pwdProblem, 400, "password");
        }
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, username);
        if (exists != null && exists > 0) {
            throw new ApiException("这个用户名已经被占用了", 409, "username");
        }

        List<String> perms = body.containsKey("perms")
                ? com.xinyu.invoice.auth.Perm.parse(body.get("perms"))
                : new ArrayList<>(com.xinyu.invoice.auth.Perm.DEFAULT);
        jdbc.update("INSERT INTO users (username, password_hash, display_name, role, status,"
                        + " must_change_pw, perms, created_at) VALUES (?,?,?,?,?,?,?,?)",
                username, PasswordTool.hash(password),
                display.isEmpty() ? username : display, role, 1, 1,
                com.xinyu.invoice.auth.Perm.serialize(perms),
                LocalDateTime.now().format(TS));

        Long uid = jdbc.queryForObject(
                "SELECT id FROM users WHERE username = ?", Long.class, username);
        log(uid(admin), str(admin.get("username")), "user_create", "user", uid,
                username + "（" + (role.equals("admin") ? "管理员" : "普通用户") + "）");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", uid);
        out.put("username", username);
        if (generated) {
            out.put("password", password);   // 只回一次，让管理员转告
        }
        return out;
    }

    @PostMapping("/api/users/update")
    public Map<String, Object> update(@RequestBody Map<String, Object> body,
                                      HttpServletRequest req) {
        Map<String, Object> admin = requirePerm(req, "users");
        long uid = asLong(body.get("id"), "缺少用户编号");
        Map<String, Object> target = jdbc.queryForList(
                "SELECT * FROM users WHERE id = ?", uid).stream().findFirst().orElse(null);
        if (target == null) {
            throw new ApiException("找不到这个用户", 404);
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        List<String> desc = new ArrayList<>();

        if (body.containsKey("display_name")) {
            String v = str(body.get("display_name")).strip();
            fields.put("display_name", v.isEmpty() ? target.get("username") : v);
            desc.add("姓名→" + fields.get("display_name"));
        }
        if (body.containsKey("status")) {
            int want = asLong(body.get("status"), "状态不对") == 0 ? 0 : 1;
            if (uid == uid(admin) && want == 0) {
                throw new ApiException("不能停用自己");
            }
            if ("admin".equals(target.get("role")) && want == 0 && countAdmins() <= 1) {
                throw new ApiException("这是最后一个管理员，不能停用");
            }
            fields.put("status", want);
            desc.add("状态→" + (want == 1 ? "启用" : "停用"));
        }
        if (body.containsKey("perms")) {
            List<String> perms = com.xinyu.invoice.auth.Perm.parse(body.get("perms"));
            fields.put("perms", com.xinyu.invoice.auth.Perm.serialize(perms));
            desc.add("权限→" + (perms.isEmpty() ? "无" : String.join("/", perms)));
        }
        if (body.containsKey("role")) {
            String want = "admin".equals(str(body.get("role"))) ? "admin" : "user";
            if ("admin".equals(target.get("role")) && "user".equals(want) && countAdmins() <= 1) {
                throw new ApiException("这是最后一个管理员，不能降级");
            }
            fields.put("role", want);
            desc.add("角色→" + ("admin".equals(want) ? "管理员" : "普通用户"));
        }
        if (fields.isEmpty()) {
            return Map.of("ok", true);
        }

        StringBuilder sql = new StringBuilder("UPDATE users SET ");
        List<Object> args = new ArrayList<>();
        boolean first = true;
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            if (!first) {
                sql.append(", ");
            }
            sql.append(e.getKey()).append(" = ?");
            args.add(e.getValue());
            first = false;
        }
        sql.append(" WHERE id = ?");
        args.add(uid);
        jdbc.update(sql.toString(), args.toArray());

        // 停用后立刻掉线
        if (fields.containsKey("status") && ((Number) fields.get("status")).intValue() == 0) {
            sessions.deleteAllFor(uid);
        }
        log(uid(admin), str(admin.get("username")), "user_update", "user", uid,
                String.join("；", desc));
        return Map.of("ok", true);
    }

    // ---------------------------------------------------------------- 工具

    private int countAdmins() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE role = 'admin' AND status = 1", Integer.class);
        return n == null ? 0 : n;
    }

    private void log(long userId, String username, String action, String targetType,
                     Long targetId, String detail) {
        jdbc.update("INSERT INTO audit_logs (user_id, username, action, target_type,"
                        + " target_id, detail, created_at) VALUES (?,?,?,?,?,?,?)",
                userId, username, action, targetType, targetId, detail,
                LocalDateTime.now().format(TS));
    }

    private static long asLong(Object v, String msg) {
        try {
            return Long.parseLong(str(v));
        } catch (NumberFormatException e) {
            throw new ApiException(msg);
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
