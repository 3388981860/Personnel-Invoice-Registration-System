package com.xinyu.invoice.repo;

import com.xinyu.invoice.common.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 报销单的「收款信息」：一张单可以付给多个人，每人一笔金额。
 * 规则：收款合计必须和单里的发票合计一分不差。
 */
@Repository
public class PayeeRepo {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public PayeeRepo(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    public List<Map<String, Object>> list(long batchId) {
        return jdbc.queryForList(
                "SELECT id, name, amount, note, sort FROM batch_payees "
                        + "WHERE batch_id = ? ORDER BY sort, id", batchId);
    }

    public double total(long batchId) {
        Double d = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM batch_payees WHERE batch_id = ?",
                Double.class, batchId);
        return d == null ? 0 : round(d);
    }

    /** 整组替换：先删后插，简单可靠 */
    public void replace(long batchId, List<Map<String, Object>> payees) {
        jdbc.update("DELETE FROM batch_payees WHERE batch_id = ?", batchId);
        String now = LocalDateTime.now().format(TS);
        int sort = 0;
        for (Map<String, Object> p : payees) {
            jdbc.update("INSERT INTO batch_payees (batch_id, name, amount, note, sort,"
                            + " created_at) VALUES (?,?,?,?,?,?)",
                    batchId, str(p.get("name")), number(p.get("amount")),
                    blank(p.get("note")), sort++, now);
        }
    }

    // ---------------------------------------------------------------- 校验

    /**
     * 收款合计必须等于发票合计。
     * 不等就说清楚差多少，别只丢一句"金额不对"。
     */
    public static void requireMatches(double invoiceTotal, List<Map<String, Object>> payees) {
        if (payees.isEmpty()) {
            throw new ApiException("请填写收款人：钱付给谁、每笔多少");
        }
        double payTotal = 0;
        for (Map<String, Object> p : payees) {
            String name = str(p.get("name")).strip();
            if (name.isEmpty()) {
                throw new ApiException("有收款人没填姓名");
            }
            Double amt = number(p.get("amount"));
            if (amt == null || amt <= 0) {
                throw new ApiException("收款人「" + name + "」的金额还没填");
            }
            payTotal += amt;
        }
        payTotal = round(payTotal);
        double diff = round(invoiceTotal - payTotal);
        if (Math.abs(diff) >= 0.005) {
            String word = diff > 0 ? "还差" : "多了";
            throw new ApiException(String.format(
                    "收款合计 ¥%.2f，发票合计 ¥%.2f，%s ¥%.2f 没对上",
                    payTotal, invoiceTotal, word, Math.abs(diff)), 400, "payees");
        }
    }

    /** 把前端传来的 payees 数组整理成干净的对象列表 */
    public static List<Map<String, Object>> clean(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return out;
        }
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                continue;
            }
            String name = str(m.get("name")).strip();
            Double amount = number(m.get("amount"));
            if (name.isEmpty() && (amount == null || amount == 0)) {
                continue;   // 空行跳过
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("name", name);
            one.put("amount", amount);
            one.put("note", blank(m.get("note")));
            out.add(one);
        }
        return out;
    }

    // ---------------------------------------------------------------- 工具

    public static double round(double v) {
        return Math.round(v * 100d) / 100d;
    }

    static Double number(Object v) {
        if (v == null || String.valueOf(v).isBlank()) {
            return null;
        }
        try {
            double d = Double.parseDouble(
                    String.valueOf(v).replace(",", "").replace("¥", "").strip());
            return round(d);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    static Object blank(Object o) {
        String s = str(o).strip();
        return s.isEmpty() ? null : s;
    }
}
