package com.xinyu.invoice.web;

import com.xinyu.invoice.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 固定资产。
 *
 * 两条规则：
 * 1) 折旧：月折旧额 = 原值 / (可使用年限 × 12)，累计折旧 = 月折旧 × 已用月数，净值 = 原值 − 累计折旧。
 * 2) 状态：使用人不在系统用户里（且不是「共用」）就是闲置，共用算公用。
 */
@RestController
public class FixedAssetController extends BaseController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> KEEP_STATUS = Set.of("报废", "维修");

    @GetMapping("/api/assets")
    public Map<String, Object> list(@RequestParam(required = false) String status,
                                    @RequestParam(required = false) String category,
                                    @RequestParam(required = false) String dept,
                                    @RequestParam(required = false) String q,
                                    HttpServletRequest req) {
        requirePerm(req, "assets");
        StringBuilder sql = new StringBuilder("SELECT * FROM assets WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status.strip());
        }
        if (category != null && !category.isBlank()) {
            sql.append(" AND category = ?");
            args.add(category.strip());
        }
        if (dept != null && !dept.isBlank()) {
            sql.append(" AND dept = ?");
            args.add(dept.strip());
        }
        if (q != null && !q.isBlank()) {
            sql.append(" AND (code LIKE ? OR name LIKE ? OR user_name LIKE ?"
                    + " OR computer_name LIKE ? OR ip LIKE ? OR serial_no LIKE ?)");
            String like = "%" + q.strip() + "%";
            for (int i = 0; i < 6; i++) {
                args.add(like);
            }
        }
        sql.append(" ORDER BY code, id");
        List<Map<String, Object>> rows = args.isEmpty()
                ? jdbc.queryForList(sql.toString())
                : jdbc.queryForList(sql.toString(), args.toArray());

        Set<String> users = systemUserNames();
        double totalPrice = 0;
        double totalNet = 0;
        int inUse = 0;
        int idle = 0;
        int pub = 0;
        int incomplete = 0;
        for (Map<String, Object> r : rows) {
            enrich(r, users);
            totalPrice += num(r.get("price"));
            totalNet += num(r.get("net_value"));
            if (Boolean.TRUE.equals(r.get("incomplete"))) {
                incomplete++;
            }
            switch (String.valueOf(r.get("status"))) {
                case "在用" -> inUse++;
                case "闲置" -> idle++;
                case "公用" -> pub++;
                default -> { }
            }
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", rows.size());
        stats.put("in_use", inUse);
        stats.put("idle", idle);
        stats.put("public", pub);
        stats.put("price", round(totalPrice));
        stats.put("net", round(totalNet));
        stats.put("incomplete", incomplete);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("assets", rows);
        out.put("stats", stats);
        return out;
    }

    @PostMapping("/api/asset/save")
    public Map<String, Object> save(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Map<String, Object> u = requirePerm(req, "assets");
        String name = str(b.get("name"));
        if (name.isEmpty()) {
            throw new ApiException("请填写资产名称", 400, "name");
        }
        String now = LocalDateTime.now().format(TS);
        String status = str(b.get("status"));
        if (!KEEP_STATUS.contains(status)) {
            status = detectStatus(str(b.get("user_name")), systemUserNames());
        }
        Object id = b.get("id");
        if (id instanceof Number n) {
            jdbc.update("""
                    UPDATE assets SET code=?, name=?, type=?, category=?, status=?,
                      finance_status=?, user_name=?, dept=?, location=?, buy_date=?, price=?,
                      life_years=?, note=?, computer_name=?, os_user=?, os_version=?,
                      os_install=?, model=?, bios_date=?, serial_no=?, cpu=?, mainboard=?,
                      memory=?, disk=?, disk_sn=?, gpu=?, monitor=?, nic=?, ip=?, mac=?,
                      printer=?, updated_at=?
                    WHERE id=?""",
                    str(b.get("code")), name, str(b.get("type")), str(b.get("category")), status,
                    str(b.get("finance_status")), str(b.get("user_name")), str(b.get("dept")),
                    str(b.get("location")), str(b.get("buy_date")), dbl(b.get("price")),
                    dbl(b.get("life_years")), str(b.get("note")), str(b.get("computer_name")),
                    str(b.get("os_user")), str(b.get("os_version")), str(b.get("os_install")),
                    str(b.get("model")), str(b.get("bios_date")), str(b.get("serial_no")),
                    str(b.get("cpu")), str(b.get("mainboard")), str(b.get("memory")),
                    str(b.get("disk")), str(b.get("disk_sn")), str(b.get("gpu")),
                    str(b.get("monitor")), str(b.get("nic")), str(b.get("ip")), str(b.get("mac")),
                    str(b.get("printer")), now, n.longValue());
            return Map.of("ok", true, "id", n.longValue());
        }
        String code = str(b.get("code"));
        if (code.isEmpty()) {
            code = nextCode();
        }
        jdbc.update("""
                INSERT INTO assets (code, name, type, category, status, finance_status,
                  user_name, dept, location, buy_date, price, life_years, note,
                  computer_name, os_user, os_version, os_install, model, bios_date, serial_no,
                  cpu, mainboard, memory, disk, disk_sn, gpu, monitor, nic, ip, mac, printer,
                  created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                code, name, str(b.get("type")), str(b.get("category")), status,
                str(b.get("finance_status")), str(b.get("user_name")), str(b.get("dept")),
                str(b.get("location")), str(b.get("buy_date")), dbl(b.get("price")),
                dbl(b.get("life_years")), str(b.get("note")), str(b.get("computer_name")),
                str(b.get("os_user")), str(b.get("os_version")), str(b.get("os_install")),
                str(b.get("model")), str(b.get("bios_date")), str(b.get("serial_no")),
                str(b.get("cpu")), str(b.get("mainboard")), str(b.get("memory")),
                str(b.get("disk")), str(b.get("disk_sn")), str(b.get("gpu")),
                str(b.get("monitor")), str(b.get("nic")), str(b.get("ip")), str(b.get("mac")),
                str(b.get("printer")), now, now);
        Long newId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        return Map.of("ok", true, "id", newId == null ? 0 : newId, "code", code);
    }

    @PostMapping("/api/asset/delete")
    public Map<String, Object> delete(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Map<String, Object> u = requirePerm(req, "assets");
        if (!com.xinyu.invoice.auth.SessionService.isAdmin(u)) {
            throw new ApiException("只有管理员能删资产", 403);
        }
        Object id = b.get("id");
        if (id == null) {
            throw new ApiException("缺少 id");
        }
        jdbc.update("DELETE FROM assets WHERE id = ?", Long.parseLong(String.valueOf(id)));
        return Map.of("ok", true);
    }

    /** 批量补使用年限 */
    @PostMapping("/api/assets/batch_life_years")
    public Map<String, Object> batchLifeYears(@RequestBody Map<String, Object> b,
                                              HttpServletRequest req) {
        requirePerm(req, "assets");
        Object yearsRaw = b.get("life_years");
        Double years = dbl(yearsRaw);
        if (years == null || years <= 0 || years > 50) {
            throw new ApiException("年限要填 0 到 50 之间的数字");
        }
        Object idsRaw = b.get("ids");
        if (!(idsRaw instanceof List<?> ids) || ids.isEmpty()) {
            throw new ApiException("先勾选资产");
        }
        String now = LocalDateTime.now().format(TS);
        int n = 0;
        for (Object idObj : ids) {
            if (idObj == null) {
                continue;
            }
            n += jdbc.update("UPDATE assets SET life_years=?, updated_at=? WHERE id=?",
                    years, now, Long.parseLong(String.valueOf(idObj)));
        }
        return Map.of("ok", true, "changed", n);
    }

    /** 按「使用人在不在系统里」重新判定所有资产状态（报废/维修不动） */
    @PostMapping("/api/assets/redetect")
    public Map<String, Object> redetect(HttpServletRequest req) {
        requirePerm(req, "assets");
        Set<String> users = systemUserNames();
        int changed = 0;
        for (Map<String, Object> r : jdbc.queryForList("SELECT id, user_name, status FROM assets")) {
            String cur = String.valueOf(r.get("status"));
            if (KEEP_STATUS.contains(cur)) {
                continue;
            }
            String next = detectStatus(String.valueOf(r.get("user_name")), users);
            if (!next.equals(cur)) {
                jdbc.update("UPDATE assets SET status=?, updated_at=? WHERE id=?",
                        next, LocalDateTime.now().format(TS), r.get("id"));
                changed++;
            }
        }
        return Map.of("ok", true, "changed", changed);
    }

    // ---------------------------------------------------------------- 内部
    /** 状态判定：共用→公用；空或系统里查不到→闲置；否则在用 */
    static String detectStatus(String userName, Set<String> systemUsers) {
        String n = userName == null ? "" : userName.strip();
        if (n.isEmpty()) {
            return "闲置";
        }
        if ("共用".equals(n) || "公用".equals(n)) {
            return "公用";
        }
        // 「程文（征用当服务器）」按括号前匹配
        String base = n.replaceAll("[（(].*$", "").strip();
        if (systemUsers.contains(n) || systemUsers.contains(base)) {
            return "在用";
        }
        return "闲置";
    }

    private Set<String> systemUserNames() {
        Set<String> out = new LinkedHashSet<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT display_name, username FROM users WHERE status = 1")) {
            if (r.get("display_name") != null) {
                out.add(String.valueOf(r.get("display_name")).strip());
            }
            if (r.get("username") != null) {
                out.add(String.valueOf(r.get("username")).strip());
            }
        }
        return out;
    }

    /** 算折旧：月折旧额、累计折旧、净值 */
    private void enrich(Map<String, Object> r, Set<String> users) {
        double price = num(r.get("price"));
        double years = num(r.get("life_years"));
        double monthly = years > 0 ? price / (years * 12) : 0;
        double acc = 0;
        String buy = r.get("buy_date") == null ? "" : String.valueOf(r.get("buy_date"));
        if (monthly > 0 && !buy.isBlank()) {
            try {
                LocalDate d = LocalDate.parse(buy.length() >= 10 ? buy.substring(0, 10) : buy);
                long months = ChronoUnit.MONTHS.between(d, LocalDate.now());
                if (months > 0) {
                    acc = Math.min(price, monthly * months);
                }
            } catch (Exception ignored) {
                // 日期格式不对就不算
            }
        }
        r.put("monthly_dep", round(monthly));
        r.put("acc_dep", round(acc));
        r.put("net_value", round(Math.max(0, price - acc)));
        r.put("price", round(price));
        r.put("life_years", years == 0 ? null : years);

        // 信息不全提醒：缺年限 / 缺采购日期 / 缺原值，折旧就算不准
        java.util.List<String> missing = new java.util.ArrayList<>();
        if (price <= 0) {
            missing.add("原值");
        }
        if (years <= 0) {
            missing.add("可使用年限");
        }
        if (buy.isBlank()) {
            missing.add("采购日期");
        }
        r.put("missing_fields", missing);
        r.put("incomplete", !missing.isEmpty());
        // 状态兜底：库里没状态就现场判
        String st = r.get("status") == null ? "" : String.valueOf(r.get("status")).strip();
        if (st.isEmpty()) {
            r.put("status", detectStatus(String.valueOf(r.get("user_name")), users));
        }
    }

    private String nextCode() {
        int max = 0;
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT code FROM assets WHERE code LIKE 'RS-GDZC-%'")) {
            String tail = String.valueOf(r.get("code")).substring("RS-GDZC-".length());
            try {
                max = Math.max(max, Integer.parseInt(tail.replaceAll("[^0-9]", "")));
            } catch (NumberFormatException ignored) {
                // 跳过
            }
        }
        return "RS-GDZC-" + String.format("%04d", max + 1);
    }

    private static double num(Object o) {
        if (o == null) {
            return 0;
        }
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(o).replace(",", "").strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).strip();
    }

    private static Double dbl(Object o) {
        if (o == null || String.valueOf(o).isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(o).replace(",", "").strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
