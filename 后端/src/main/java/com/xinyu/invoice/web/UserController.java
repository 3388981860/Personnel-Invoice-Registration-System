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

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @GetMapping("/api/users")
    public Map<String, Object> list(HttpServletRequest req) {
        Map<String, Object> me = requireUser(req);
        if (!com.xinyu.invoice.auth.SessionService.isAdmin(me)) {
            throw new ApiException("这个操作需要管理员权限", 403);
        }
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
            one.put("perms_custom", u.get("perms") != null
                    && !String.valueOf(u.get("perms")).isBlank());
            users.add(one);
        }
        return Map.of("ok", true, "users", users);
    }

    @PostMapping("/api/users/create")
    public Map<String, Object> create(@RequestBody Map<String, Object> body,
                                      HttpServletRequest req) {
        Map<String, Object> admin = requireAdmin(req);
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
        Map<String, Object> admin = requireAdmin(req);
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
