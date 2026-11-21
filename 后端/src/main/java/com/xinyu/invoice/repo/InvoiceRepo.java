package com.xinyu.invoice.repo;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 发票查询：SQL 与旧版 store.py 保持一致，保证同样的数据同样的输出 */
@Repository
public class InvoiceRepo {

    private static final String SELECT_INVOICE =
            "SELECT i.*, b.batch_no AS batch_no, b.review_status AS batch_review, "
                    + "b.owner_id AS batch_owner FROM invoices i "
                    + "LEFT JOIN batches b ON b.id = i.batch_id";

    /** 排序字段白名单，避免 SQL 注入 */
    private static final Map<String, String> SORT = Map.of(
            "invoice_no", "i.invoice_no",
            "invoice_date", "i.invoice_date",
            "amount", "i.amount",
            "person", "i.person",
            "status", "i.status",
            "batch_no", "b.batch_no",
            "created_at", "i.created_at"
    );

    private final NamedParameterJdbcTemplate jdbc;

    public InvoiceRepo(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param ownerId 非空时只看这个人的数据（普通用户）
     */
    public List<Map<String, Object>> list(String q, String person, String month,
                                          String status, String audit, String batch,
                                          String review, Long ownerId,
                                          String sort, boolean desc) {
        List<String> where = new ArrayList<>();
        MapSqlParameterSource p = new MapSqlParameterSource();

        if (q != null && !q.isBlank()) {
            where.add("(i.invoice_no LIKE :like OR i.seller LIKE :like OR i.person LIKE :like "
                    + "OR i.payee LIKE :like OR i.note LIKE :like OR i.category LIKE :like "
                    + "OR i.file_name LIKE :like OR b.batch_no LIKE :like)");
            p.addValue("like", "%" + q.strip() + "%");
        }
        if (person != null && !person.isBlank()) {
            where.add("i.person = :person");
            p.addValue("person", person);
        }
        if (month != null && !month.isBlank()) {
            where.add("substr(COALESCE(i.invoice_date, i.created_at), 1, 7) = :month");
            p.addValue("month", month);
        }
        if (status != null && !status.isBlank()) {
            where.add("COALESCE(i.status, 0) = :status");
            p.addValue("status", Integer.parseInt(status));
        }
        if (audit != null && !audit.isBlank()) {
            if ("unchecked".equals(audit)) {
                where.add("COALESCE(i.audit_status, 0) IN (0, 2)");
            } else {
                where.add("COALESCE(i.audit_status, 0) = :audit");
                p.addValue("audit", Integer.parseInt(audit));
            }
        }
        if (batch != null && !batch.isBlank()) {
            if ("none".equals(batch)) {
                where.add("i.batch_id IS NULL");
            } else {
                where.add("i.batch_id = :batchId");
                p.addValue("batchId", Long.parseLong(batch));
            }
        }
        if (review != null && !review.isBlank()) {
            where.add("b.review_status = :review");
            p.addValue("review", review);
        }
        if (ownerId != null) {
            where.add("i.owner_id = :ownerId");
            p.addValue("ownerId", ownerId);
        }

        String col = SORT.getOrDefault(sort == null ? "invoice_date" : sort, "i.invoice_date");
        StringBuilder sql = new StringBuilder(SELECT_INVOICE);
        if (!where.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", where));
        }
        sql.append(" ORDER BY ").append(col).append(desc ? " DESC" : " ASC")
                .append(", i.id DESC");

        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), p);
        attachPayees(rows);
        return rows;
    }

    /**
     * 把「这张单付给谁」附到每条记录上，台账列表才能显示「张三 等 2 人」。
     * 一次查完，别在循环里查库。
     */
    private void attachPayees(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        java.util.Set<Long> ids = new java.util.LinkedHashSet<>();
        for (Map<String, Object> r : rows) {
            Object b = r.get("batch_id");
            if (b instanceof Number n) {
                ids.add(n.longValue());
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        java.util.Map<Long, List<String>> names = new java.util.HashMap<>();
        java.util.Map<Long, Double> totals = new java.util.HashMap<>();
        for (Map<String, Object> p : jdbc.queryForList(
                "SELECT batch_id, name, amount FROM batch_payees "
                        + "WHERE batch_id IN (:ids) ORDER BY sort, id",
                java.util.Map.of("ids", ids))) {
            Long bid = ((Number) p.get("batch_id")).longValue();
            names.computeIfAbsent(bid, k -> new java.util.ArrayList<>())
                    .add(String.valueOf(p.get("name")));
            totals.merge(bid, p.get("amount") == null ? 0
                    : ((Number) p.get("amount")).doubleValue(), Double::sum);
        }
        for (Map<String, Object> r : rows) {
            Object b = r.get("batch_id");
            if (!(b instanceof Number n)) {
                continue;
            }
            List<String> list = names.get(n.longValue());
            if (list == null || list.isEmpty()) {
                continue;
            }
            r.put("payee_names", String.join("、", list));
            r.put("payee_count", list.size());
            r.put("payee_total", Math.round(totals.getOrDefault(n.longValue(), 0d) * 100d) / 100d);
        }
    }

    public Map<String, Object> get(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                SELECT_INVOICE + " WHERE i.id = :id", Map.of("id", id));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<String> persons() {
        return jdbc.queryForList("SELECT name FROM persons ORDER BY sort, name", Map.of(),
                String.class);
    }

    public List<String> months() {
        return jdbc.queryForList(
                "SELECT DISTINCT substr(COALESCE(invoice_date, created_at), 1, 7) m "
                        + "FROM invoices WHERE m IS NOT NULL AND m <> '' ORDER BY m DESC",
                Map.of(), String.class);
    }

    public List<Map<String, Object>> batchOptions(Long ownerId) {
        String sql = "SELECT id, batch_no, review_status FROM batches";
        return ownerId == null
                ? jdbc.queryForList(sql + " ORDER BY id DESC", Map.of())
                : jdbc.queryForList(sql + " WHERE owner_id = :o ORDER BY id DESC",
                        Map.of("o", ownerId));
    }
}
