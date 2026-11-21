package com.xinyu.invoice.web;

import com.xinyu.invoice.auth.SessionService;
import com.xinyu.invoice.config.AppProps;
import com.xinyu.invoice.repo.InvoiceRepo;
import com.xinyu.invoice.repo.StatsRepo;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 台账列表 + 统计数据 */
@RestController
public class InvoiceController extends BaseController {

    private final InvoiceRepo repo;
    private final StatsRepo statsRepo;
    private final AppProps props;

    public InvoiceController(InvoiceRepo repo, StatsRepo statsRepo, AppProps props) {
        this.repo = repo;
        this.statsRepo = statsRepo;
        this.props = props;
    }

    @GetMapping("/api/list")
    public Map<String, Object> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String person,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String audit,
            @RequestParam(required = false) String batch,
            @RequestParam(required = false) String review,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false, defaultValue = "1") String desc,
            HttpServletRequest req) {

        Map<String, Object> u = requireUser(req);
        // 普通用户强制只看自己的
        Long owner = SessionService.isAdmin(u) ? null : uid(u);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("records", repo.list(q, person, month, status, audit, batch,
                SessionService.isAdmin(u) ? review : null, owner,
                sort, !"0".equals(desc)));
        out.put("stats", statsRepo.stats(owner));
        out.put("persons", repo.persons());
        out.put("months", repo.months());
        out.put("batches", repo.batchOptions(owner));
        return out;
    }

    @GetMapping("/api/stats/overview")
    public Map<String, Object> overview(HttpServletRequest req) {
        Map<String, Object> u = requirePerm(req, "stats");
        Long owner = null;

        Map<String, Object> st = statsRepo.stats(owner);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);

        Map<String, Object> o = new LinkedHashMap<>();
        o.put("pending", count("submitted"));
        o.put("returned", count("returned"));
        o.put("approved", count("approved"));
        o.put("users", jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE status = 1", Integer.class));
        o.put("claims", ((Map<?, ?>) st.get("total")).get("count"));
        o.put("claims_amount", ((Map<?, ?>) st.get("total")).get("amount"));
        o.put("month_count", ((Map<?, ?>) st.get("month")).get("count"));
        o.put("month_amount", ((Map<?, ?>) st.get("month")).get("amount"));
        o.put("avg_review_hours", avgReviewHours());
        out.put("overview", o);

        out.put("trend", jdbc.queryForList(
                "SELECT substr(COALESCE(invoice_date, created_at),1,7) month, COUNT(*) count, "
                        + "ROUND(COALESCE(SUM(amount),0),2) amount FROM invoices "
                        + "GROUP BY month ORDER BY month"));
        out.put("by_person", jdbc.queryForList(
                "SELECT person name, COUNT(*) count, ROUND(COALESCE(SUM(amount),0),2) amount "
                        + "FROM invoices WHERE person IS NOT NULL AND person <> '' "
                        + "GROUP BY person ORDER BY amount DESC"));
        out.put("by_seller", jdbc.queryForList(
                "SELECT seller name, COUNT(*) count, ROUND(COALESCE(SUM(amount),0),2) amount "
                        + "FROM invoices WHERE seller IS NOT NULL AND seller <> '' "
                        + "GROUP BY seller ORDER BY amount DESC LIMIT 10"));
        // 注意：invoices 表本身有 status 列（报销状态），别名再叫 status 会让
        // SQLite 的 GROUP BY 认成表列，结果就变成按报销状态分组了。这里按表达式分组。
        out.put("by_audit", jdbc.queryForList(
                "SELECT COALESCE(audit_status,0) AS status, COUNT(*) AS count FROM invoices "
                        + "GROUP BY COALESCE(audit_status,0) ORDER BY 1"));
        return out;
    }

    private int count(String reviewStatus) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM batches WHERE review_status = ?",
                Integer.class, reviewStatus);
        return n == null ? 0 : n;
    }

    private double avgReviewHours() {
        Double v = jdbc.queryForObject(
                "SELECT AVG((julianday(reviewed_at) - julianday(created_at)) * 24) "
                        + "FROM batches WHERE reviewed_at IS NOT NULL AND created_at IS NOT NULL",
                Double.class);
        return v == null ? 0 : Math.round(v * 10d) / 10d;
    }
}
