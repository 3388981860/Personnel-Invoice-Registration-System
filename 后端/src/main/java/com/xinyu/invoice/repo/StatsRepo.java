package com.xinyu.invoice.repo;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** 统计：SQL 与旧版 store.stats() 一致 */
@Repository
public class StatsRepo {

    private final NamedParameterJdbcTemplate jdbc;

    public StatsRepo(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> stats(Long ownerId) {
        LocalDate today = LocalDate.now();
        String month = today.toString().substring(0, 7);
        String year = today.toString().substring(0, 4);

        String ownerWhere = ownerId == null ? "" : " AND owner_id = :owner";
        MapSqlParameterSource p = new MapSqlParameterSource();
        if (ownerId != null) {
            p.addValue("owner", ownerId);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", sum(month, ownerWhere, p));
        out.put("year", sum(year, ownerWhere, p));
        out.put("total", pair("SELECT COUNT(*) c, COALESCE(SUM(amount),0) s FROM invoices"
                + (ownerId == null ? "" : " WHERE owner_id = :owner"), p));
        out.put("unpaid", byStatus(0, ownerWhere, p));
        out.put("paid", byStatus(1, ownerWhere, p));

        String oJoin = ownerId == null ? "" : " WHERE b.owner_id = :owner";
        out.put("unpaid_batches", jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT b.id FROM batches b "
                        + "JOIN invoices i ON i.batch_id = b.id" + oJoin
                        + " GROUP BY b.id HAVING COALESCE(SUM(COALESCE(i.status,0)),0) < COUNT(i.id))",
                p, Integer.class));
        out.put("batches", jdbc.queryForObject(
                "SELECT COUNT(*) FROM (SELECT b.id FROM batches b "
                        + "JOIN invoices i ON i.batch_id = b.id" + oJoin + " GROUP BY b.id)",
                p, Integer.class));

        // 查验状态分布
        Map<String, Object> audit = new LinkedHashMap<>();
        for (int i = 0; i < 4; i++) {
            audit.put(String.valueOf(i), 0);
        }
        jdbc.queryForList("SELECT COALESCE(audit_status,0) s, COUNT(*) c FROM invoices WHERE 1=1"
                        + ownerWhere + " GROUP BY s", p)
                .forEach(r -> audit.put(String.valueOf(((Number) r.get("s")).intValue()),
                        ((Number) r.get("c")).intValue()));
        out.put("audit", audit);

        out.put("audit_warn", jdbc.queryForObject(
                "SELECT COUNT(*) FROM invoices WHERE COALESCE(audit_warn,'') <> ''"
                        + ownerWhere, p, Integer.class));

        // 按月的查验分布（前端画趋势用）
        out.put("audit_by_month", jdbc.queryForList(
                "SELECT substr(COALESCE(invoice_date, created_at),1,7) month, "
                        + "COALESCE(audit_status,0) s, COUNT(*) c FROM invoices "
                        + (ownerId == null ? "" : "WHERE owner_id = :owner ")
                        + "GROUP BY month, s ORDER BY month", p));

        out.put("audit_month", monthAudit(month, ownerWhere, p));
        return out;
    }

    private Map<String, Object> sum(String pattern, String ownerWhere, MapSqlParameterSource p) {
        MapSqlParameterSource q = new MapSqlParameterSource(p.getValues());
        q.addValue("pat", pattern);
        return pair("SELECT COUNT(*) c, COALESCE(SUM(amount),0) s FROM invoices "
                + "WHERE substr(COALESCE(invoice_date, created_at),1,"
                + pattern.length() + ") = :pat" + ownerWhere, q);
    }

    private Map<String, Object> byStatus(int status, String ownerWhere, MapSqlParameterSource p) {
        MapSqlParameterSource q = new MapSqlParameterSource(p.getValues());
        q.addValue("st", status);
        return pair("SELECT COUNT(*) c, COALESCE(SUM(amount),0) s FROM invoices "
                + "WHERE COALESCE(status,0) = :st" + ownerWhere, q);
    }

    private Map<String, Object> monthAudit(String month, String ownerWhere,
                                           MapSqlParameterSource p) {
        MapSqlParameterSource q = new MapSqlParameterSource(p.getValues());
        q.addValue("pat", month);
        Map<String, Object> res = new LinkedHashMap<>();
        for (int i = 0; i < 4; i++) {
            res.put(String.valueOf(i), 0);
        }
        jdbc.queryForList("SELECT COALESCE(audit_status,0) s, COUNT(*) c FROM invoices "
                        + "WHERE substr(COALESCE(invoice_date, created_at),1,7) = :pat"
                        + ownerWhere + " GROUP BY s", q)
                .forEach(r -> res.put(String.valueOf(((Number) r.get("s")).intValue()),
                        ((Number) r.get("c")).intValue()));
        return res;
    }

    private Map<String, Object> pair(String sql, MapSqlParameterSource p) {
        Map<String, Object> row = jdbc.queryForMap(sql, p);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", ((Number) row.get("c")).intValue());
        Object s = row.get("s");
        double amount = s == null ? 0d : ((Number) s).doubleValue();
        out.put("amount", Math.round(amount * 100d) / 100d);
        return out;
    }
}
