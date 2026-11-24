package com.xinyu.invoice.repo;

import com.xinyu.invoice.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 合同管理：项目 → 合同 → 资金流水 三层。
 *
 * 一个项目可以签任意多份合同，合同只按"钱往哪走"分三种：
 *   income    收入合同（别人付给我们）
 *   cost      成本合同（我们付给外协）
 *   reference 关联合同（我们不签，只跟踪上游进度）
 */
@Repository
public class ContractRepo {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;

    public ContractRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- 项目

    /** 项目集列表（带项目数） */
    public List<Map<String, Object>> projectSets() {
        return jdbc.queryForList("""
                SELECT s.*,
                  (SELECT COUNT(*) FROM projects p WHERE p.set_id = s.id) AS project_count
                FROM project_sets s ORDER BY s.year DESC, s.id DESC
                """);
    }

    /** 取单个项目集 */
    public Map<String, Object> projectSet(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM project_sets WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 保存项目集（没有 id 就新建，编号 JC-年份-序号） */
    public long saveProjectSet(Map<String, Object> b) {
        String name = str(b.get("name")).strip();
        if (name.isEmpty()) {
            throw new ApiException("请填写项目集名称", 400, "name");
        }
        String now = LocalDateTime.now().format(TS);
        Object id = b.get("id");
        if (id instanceof Number n) {
            jdbc.update("UPDATE project_sets SET name=?, year=?, unit=?, note=? WHERE id=?",
                    name,
                    b.get("year") == null ? null : Integer.parseInt(String.valueOf(b.get("year"))),
                    blank(b.get("unit")), blank(b.get("note")), n.longValue());
            return n.longValue();
        }
        int year = b.get("year") == null
                ? java.time.LocalDate.now().getYear()
                : Integer.parseInt(String.valueOf(b.get("year")).trim());
        String prefix = "JC-" + year + "-";
        int next = 1;
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT code FROM project_sets WHERE code LIKE ?", prefix + "%")) {
            String tail = String.valueOf(r.get("code")).substring(prefix.length());
            try {
                next = Math.max(next, Integer.parseInt(tail.replaceAll("[^0-9]", "")) + 1);
            } catch (NumberFormatException ignored) {
                // 跳过不规范的
            }
        }
        jdbc.update("INSERT INTO project_sets (code, name, year, unit, note, created_at)"
                        + " VALUES (?,?,?,?,?,?)",
                prefix + String.format("%03d", next), name, year,
                blank(b.get("unit")), blank(b.get("note")), now);
        Long newId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        return newId == null ? 0 : newId;
    }

    /** 项目列表：带上合同数、收入合计、已开票、已回款 */
    public List<Map<String, Object>> projects(Long ownerId) {
        return projects(ownerId, null);
    }

    public List<Map<String, Object>> projects(Long ownerId, Long setId) {
        String sql = """
                SELECT p.*,
                  (SELECT COALESCE(NULLIF(u.display_name,''), u.username) FROM users u
                    WHERE u.id = p.owner_id) AS owner_name,
                  (SELECT s.name FROM project_sets s WHERE s.id = p.set_id) AS set_name,
                  (SELECT s.code FROM project_sets s WHERE s.id = p.set_id) AS set_code,
                  (SELECT COUNT(*) FROM contracts c WHERE c.project_id = p.id) AS contract_count,
                  COALESCE((SELECT SUM(c.amount) FROM contracts c
                            WHERE c.project_id = p.id AND c.kind = 'income'), 0) AS income_amount,
                  COALESCE((SELECT SUM(c.amount) FROM contracts c
                            WHERE c.project_id = p.id AND c.kind = 'cost'), 0) AS cost_amount,
                  COALESCE((SELECT SUM(b.amount) FROM contract_billings b
                            JOIN contracts c ON c.id = b.contract_id
                            WHERE c.project_id = p.id AND c.kind = 'income'
                              AND b.kind = 'invoice'), 0) AS invoiced,
                  COALESCE((SELECT SUM(b.amount) FROM contract_billings b
                            JOIN contracts c ON c.id = b.contract_id
                            WHERE c.project_id = p.id AND c.kind = 'income'
                              AND b.kind = 'payment'), 0) AS received
                FROM projects p
                """
                + ((ownerId != null || setId != null) ? " WHERE " : "")
                + (ownerId != null ? " p.owner_id = ?" : "")
                + ((ownerId != null && setId != null) ? " AND " : "")
                + (setId != null ? " p.set_id = ?" : "")
                + " ORDER BY p.id DESC";
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (ownerId != null) {
            args.add(ownerId);
        }
        if (setId != null) {
            args.add(setId);
        }
        List<Map<String, Object>> rows = args.isEmpty()
                ? jdbc.queryForList(sql)
                : jdbc.queryForList(sql, args.toArray());
        for (Map<String, Object> r : rows) {
            r.put("invoiced", round(num(r.get("invoiced"))));
            r.put("received", round(num(r.get("received"))));
            r.put("income_amount", round(num(r.get("income_amount"))));
            r.put("cost_amount", round(num(r.get("cost_amount"))));
        }
        return rows;
    }

    public Map<String, Object> project(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM projects WHERE id = ?", id);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> p = new LinkedHashMap<>(rows.get(0));

        List<Map<String, Object>> contracts = jdbc.queryForList(
                "SELECT * FROM contracts WHERE project_id = ? "
                        + "ORDER BY CASE kind WHEN 'reference' THEN 0 WHEN 'income' THEN 1 "
                        + "ELSE 2 END, sort, id", id);
        double income = 0;
        double cost = 0;
        double invoiced = 0;
        double received = 0;
        double paid = 0;
        for (Map<String, Object> c : contracts) {
            List<Map<String, Object>> bills = jdbc.queryForList(
                    "SELECT * FROM contract_billings WHERE contract_id = ? "
                            + "ORDER BY happened_at, id", c.get("id"));
            c.put("billings", bills);
            double inv = 0;
            double pay = 0;
            for (Map<String, Object> b : bills) {
                double a = num(b.get("amount"));
                if ("invoice".equals(str(b.get("kind")))) {
                    inv += a;
                } else {
                    pay += a;
                }
            }
            c.put("invoiced", round(inv));
            c.put("paid", round(pay));
            c.put("remain", round(Math.max(0, num(c.get("amount")) - pay)));
            double amt = num(c.get("amount"));
            c.put("invoice_ratio", amt > 0 ? round(inv * 100 / amt) : null);
            c.put("paid_ratio", amt > 0 ? round(pay * 100 / amt) : null);

            switch (str(c.get("kind"))) {
                case "income" -> {
                    income += amt;
                    invoiced += inv;
                    received += pay;
                }
                case "cost" -> {
                    cost += amt;
                    paid += pay;
                }
                default -> { }
            }
        }
        p.put("contracts", contracts);
        p.put("income_amount", round(income));
        p.put("cost_amount", round(cost));
        p.put("invoiced", round(invoiced));
        p.put("received", round(received));
        p.put("paid_cost", round(paid));
        return p;
    }

    // ---------------------------------------------------------------- 保存

    /**
     * 下一个项目编号，规则 HT-年份-序号，例如 HT-2026-001。
     * 序号在该年份内递增，取已有最大号 +1。
     */
    public String nextProjectCode() {
        String year = String.valueOf(java.time.LocalDate.now().getYear());
        String prefix = "HT-" + year + "-";
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT code FROM projects WHERE code LIKE ? ORDER BY code DESC LIMIT 1", prefix + "%");
        int next = 1;
        if (!rows.isEmpty()) {
            String last = String.valueOf(rows.get(0).get("code"));
            String tail = last.substring(prefix.length());
            try {
                next = Integer.parseInt(tail.replaceAll("[^0-9]", "")) + 1;
            } catch (NumberFormatException e) {
                List<Map<String, Object>> all = jdbc.queryForList(
                        "SELECT code FROM projects WHERE code LIKE ?", prefix + "%");
                next = all.size() + 1;
            }
        }
        return prefix + String.format("%03d", next);
    }

    public long saveProject(Map<String, Object> b, Long ownerId) {
        String name = str(b.get("name")).strip();
        if (name.isEmpty()) {
            throw new ApiException("请填写项目名称", 400, "name");
        }
        String now = LocalDateTime.now().format(TS);
        Object id = b.get("id");
        if (id instanceof Number n) {
            jdbc.update("UPDATE projects SET code=?, name=?, unit=?, start_date=?, "
                            + "end_date=?, note=?, set_id=?, updated_at=? WHERE id=?",
                    blank(b.get("code")), name, blank(b.get("unit")),
                    blank(b.get("start_date")), blank(b.get("end_date")),
                    blank(b.get("note")),
                    b.get("set_id") == null ? null : Long.parseLong(String.valueOf(b.get("set_id"))),
                    now, n.longValue());
            return n.longValue();
        }
        String code = str(b.get("code")).strip();
        if (code.isEmpty()) {
            code = nextProjectCode();     // 没填就按 HT-YYYY-序号 自动生成
        }
        jdbc.update("INSERT INTO projects (code, name, unit, start_date, end_date, "
                        + "owner_id, note, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
                code, name, blank(b.get("unit")),
                blank(b.get("start_date")), blank(b.get("end_date")),
                ownerId, blank(b.get("note")), now, now);
        Long newId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        return newId == null ? 0 : newId;
    }

    public long saveContract(Map<String, Object> b) {
        long pid = asLong(b.get("project_id"), "缺少项目编号");
        String kind = str(b.get("kind"));
        if (!List.of("income", "cost", "reference").contains(kind)) {
            throw new ApiException("合同类型不对");
        }
        String now = LocalDateTime.now().format(TS);
        Object id = b.get("id");
        if (id instanceof Number n) {
            jdbc.update("UPDATE contracts SET kind=?, party_a=?, party_b=?, contract_no=?, "
                            + "amount=?, signed=?, signed_at=?, note=?, customer_id=?,"
                            + " updated_at=? WHERE id=?",
                    kind, blank(b.get("party_a")), blank(b.get("party_b")),
                    blank(b.get("contract_no")), number(b.get("amount")),
                    blank(b.get("signed")), blank(b.get("signed_at")),
                    blank(b.get("note")), asLongOrNull(b.get("customer_id")), now, n.longValue());
            return n.longValue();
        }
        jdbc.update("INSERT INTO contracts (project_id, kind, party_a, party_b, contract_no, "
                        + "amount, signed, signed_at, note, sort, created_at, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,0,?,?)",
                pid, kind, blank(b.get("party_a")), blank(b.get("party_b")),
                blank(b.get("contract_no")), number(b.get("amount")),
                blank(b.get("signed")), blank(b.get("signed_at")),
                blank(b.get("note")), now, now);
        Long newId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        return newId == null ? 0 : newId;
    }

    // ---------------------------------------------------------------- 开票 / 回款

    public long saveBilling(Map<String, Object> b) {
        long cid = asLong(b.get("contract_id"), "缺少合同编号");
        String kind = str(b.get("kind"));
        if (!List.of("invoice", "payment").contains(kind)) {
            throw new ApiException("只能记开票或回款");
        }
        Double amount = number(b.get("amount"));
        if (amount == null || amount <= 0) {
            throw new ApiException("请填写金额", 400, "amount");
        }
        // 比例按合同金额自动算，合同金额没填就是 null
        Map<String, Object> c = contract(cid);
        Double ratio = null;
        if (c != null && num(c.get("amount")) > 0) {
            ratio = round(amount * 100 / num(c.get("amount")));
        }
        String now = LocalDateTime.now().format(TS);
        Object id = b.get("id");
        if (id instanceof Number n) {
            jdbc.update("UPDATE contract_billings SET kind=?, stage=?, amount=?, ratio=?, "
                            + "no=?, happened_at=?, note=? WHERE id=?",
                    kind, blank(b.get("stage")), amount, ratio, blank(b.get("no")),
                    blank(b.get("happened_at")), blank(b.get("note")), n.longValue());
            return n.longValue();
        }
        jdbc.update("INSERT INTO contract_billings (contract_id, kind, stage, amount, ratio, "
                        + "no, happened_at, note, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                cid, kind, blank(b.get("stage")), amount, ratio, blank(b.get("no")),
                blank(b.get("happened_at")), blank(b.get("note")), now);
        Long newId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        return newId == null ? 0 : newId;
    }

    public void deleteBilling(long id) {
        jdbc.update("DELETE FROM contract_billings WHERE id = ?", id);
    }

    public Map<String, Object> billing(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM contract_billings WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public Map<String, Object> contract(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM contracts WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public void deleteContract(long id) {
        jdbc.update("DELETE FROM contract_billings WHERE contract_id = ?", id);
        jdbc.update("DELETE FROM contracts WHERE id = ?", id);
    }

    public void deleteProject(long id) {
        for (Map<String, Object> c : jdbc.queryForList(
                "SELECT id FROM contracts WHERE project_id = ?", id)) {
            deleteContract(((Number) c.get("id")).longValue());
        }
        jdbc.update("DELETE FROM projects WHERE id = ?", id);
    }

    // ---------------------------------------------------------------- 工具

    public static double round(double v) {
        return Math.round(v * 100d) / 100d;
    }

    static double num(Object o) {
        return o == null ? 0 : ((Number) o).doubleValue();
    }

    static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    static Object blank(Object o) {
        String s = str(o).strip();
        return s.isEmpty() ? null : s;
    }

    public static Double number(Object v) {
        if (v == null || String.valueOf(v).isBlank()) {
            return null;
        }
        try {
            return round(Double.parseDouble(
                    String.valueOf(v).replace(",", "").replace("¥", "").strip()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 可空的 id：空串/null 返回 null */
    public static Long asLongOrNull(Object o) {
        if (o == null || String.valueOf(o).isBlank() || "null".equals(String.valueOf(o))) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static long asLong(Object v, String msg) {
        try {
            return Long.parseLong(str(v).strip());
        } catch (NumberFormatException e) {
            throw new ApiException(msg);
        }
    }
}
