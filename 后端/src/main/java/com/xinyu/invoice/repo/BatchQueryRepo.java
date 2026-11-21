package com.xinyu.invoice.repo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinyu.invoice.common.ApiException;
import com.xinyu.invoice.config.AppProps;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 报销单读与状态流转（详情、审核、重新提交、删除） */
@Repository
public class BatchQueryRepo {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final String SELECT_INVOICE =
            "SELECT i.*, b.batch_no AS batch_no, b.review_status AS batch_review, "
                    + "b.owner_id AS batch_owner FROM invoices i "
                    + "LEFT JOIN batches b ON b.id = i.batch_id";

    private final JdbcTemplate jdbc;
    private final AppProps props;
    private final ObjectMapper json;
    private final PayeeRepo payees;

    public BatchQueryRepo(JdbcTemplate jdbc, AppProps props, ObjectMapper json,
                          PayeeRepo payees) {
        this.jdbc = jdbc;
        this.props = props;
        this.json = json;
        this.payees = payees;
    }

    // ---------------------------------------------------------------- 详情

    /** 单头 + 单内发票 + 合计 + 汇总状态（与 Python batch_detail 一致） */
    public Map<String, Object> detail(long bid) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM batches WHERE id = ?", bid);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> b = new LinkedHashMap<>(rows.get(0));
        List<Map<String, Object>> invs = jdbc.queryForList(
                SELECT_INVOICE + " WHERE i.batch_id = ? ORDER BY i.id", bid);

        double amount = 0;
        int paid = 0;
        for (Map<String, Object> i : invs) {
            Object a = i.get("amount");
            amount += a == null ? 0 : ((Number) a).doubleValue();
            Object st = i.get("status");
            if (st != null && ((Number) st).intValue() == 1) {
                paid++;
            }
        }
        b.put("invoices", invs);
        b.put("count", invs.size());
        b.put("amount", Math.round(amount * 100d) / 100d);
        b.put("paid_count", paid);
        // 1=整单已报销 0=整单未报销 -1=部分已报销
        int status = !invs.isEmpty() && paid == invs.size() ? 1 : (paid == 0 ? 0 : -1);
        b.put("status", status);

        // 收款信息 + 是否已锁定（整单已报销就是锁定的）
        b.put("payees", payees.list(bid));
        b.put("pay_total", payees.total(bid));
        b.put("locked", status == 1);

        Map<String, Object> first = invs.isEmpty() ? Map.of() : invs.get(0);
        b.put("person", first.get("person"));
        b.put("payee", first.get("payee"));
        b.put("reimburse_date", first.get("reimburse_date"));
        b.put("paid_at", first.get("paid_at"));
        return b;
    }

    public List<Map<String, Object>> list(String q, String review, Long ownerId) {
        StringBuilder sql = new StringBuilder(
                "SELECT b.*, COUNT(i.id) AS count, COALESCE(SUM(i.amount),0) AS amount, "
                        + "COALESCE(SUM(CASE WHEN COALESCE(i.status,0)=1 THEN 1 ELSE 0 END),0)"
                        + " AS paid_count FROM batches b LEFT JOIN invoices i ON i.batch_id = b.id");
        List<String> where = new ArrayList<>();
        Map<String, Object> args = new LinkedHashMap<>();
        if (ownerId != null) {
            where.add("b.owner_id = :owner");
            args.put("owner", ownerId);
        }
        if (review != null && !review.isBlank()) {
            where.add("b.review_status = :review");
            args.put("review", review);
        }
        if (q != null && !q.isBlank()) {
            where.add("(b.batch_no LIKE :q OR b.note LIKE :q)");
            args.put("q", "%" + q.strip() + "%");
        }
        if (!where.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", where));
        }
        sql.append(" GROUP BY b.id ORDER BY b.id DESC");
        return namedQuery(sql.toString(), args);
    }

    // ---------------------------------------------------------------- 审核

    public void setReview(long bid, String status, Long reviewerId, String note,
                          List<Integer> issues) {
        String stamp = LocalDateTime.now().format(TS);
        String issuesJson = null;
        if (issues != null) {
            try {
                issuesJson = json.writeValueAsString(issues);
            } catch (Exception ignored) {
                issuesJson = "[]";
            }
        }
        jdbc.update("UPDATE batches SET review_status = ?, reviewer_id = ?, reviewed_at = ?,"
                        + " review_note = ?, review_issues = ?, updated_at = ? WHERE id = ?",
                status, reviewerId, stamp, note, issuesJson, stamp, bid);
    }

    /** 把库里存的 issues JSON 解析出来 */
    @SuppressWarnings("unchecked")
    public List<Integer> issues(Map<String, Object> batch) {
        Object raw = batch == null ? null : batch.get("review_issues");
        if (raw == null || String.valueOf(raw).isBlank()) {
            return new ArrayList<>();
        }
        try {
            Object v = json.readValue(String.valueOf(raw), Object.class);
            List<Integer> out = new ArrayList<>();
            if (v instanceof List<?> list) {
                for (Object x : list) {
                    if (x instanceof Number n) {
                        out.add(n.intValue());
                    }
                }
            }
            return out;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    // ---------------------------------------------------------------- 删除

    /** 整单删除：发票逐张归档到「已删除」后删行，再删单据本身 */
    public int deleteBatch(long bid) {
        List<Map<String, Object>> invs = jdbc.queryForList(
                "SELECT id, file_rel FROM invoices WHERE batch_id = ? ORDER BY id", bid);
        for (Map<String, Object> inv : invs) {
            long rid = ((Number) inv.get("id")).longValue();
            Object rel = inv.get("file_rel");
            if (rel != null && !String.valueOf(rel).isBlank()) {
                moveToDeleted(String.valueOf(rel));
            }
            jdbc.update("DELETE FROM invoices WHERE id = ?", rid);
        }
        jdbc.update("DELETE FROM batches WHERE id = ?", bid);
        return invs.size();
    }

    public void moveToDeleted(String relPath) {
        try {
            Path src = props.base().resolve(relPath.replace("/", java.io.File.separator))
                    .normalize();
            if (!Files.isRegularFile(src)) {
                return;
            }
            Path dir = props.dataDir().resolve("已删除");
            Files.createDirectories(dir);
            String name = LocalDateTime.now().format(FILE_TS) + "_"
                    + src.getFileName().toString();
            Files.move(src, dir.resolve(name));
        } catch (IOException e) {
            // 移动失败不影响删除纪录
        }
    }

    // ---------------------------------------------------------------- 工具

    private List<Map<String, Object>> namedQuery(String sql, Map<String, Object> args) {
        return new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc)
                .queryForList(sql, args);
    }

    public static long ownerOf(Map<String, Object> batch) {
        Object o = batch == null ? null : batch.get("owner_id");
        return o == null ? 0 : ((Number) o).longValue();
    }

    public static String reviewStatus(Map<String, Object> batch) {
        Object s = batch == null ? null : batch.get("review_status");
        return s == null ? "approved" : String.valueOf(s);
    }

    public static void requireFound(Map<String, Object> batch) {
        if (batch == null) {
            throw new ApiException("找不到这张报销单", 404);
        }
    }
}
