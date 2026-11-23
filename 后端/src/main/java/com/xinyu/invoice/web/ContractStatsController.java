package com.xinyu.invoice.web;

import com.xinyu.invoice.repo.ContractRepo;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 合同相关的汇总数字（数据总览用） */
@RestController
public class ContractStatsController extends BaseController {

    private final ContractRepo repo;

    public ContractStatsController(ContractRepo repo) {
        this.repo = repo;
    }

    @GetMapping("/api/contracts/overview")
    public Map<String, Object> overview(HttpServletRequest req) {
        requirePerm(req, "stats");

        Map<String, Object> totals = jdbc.queryForMap("""
                SELECT
                  (SELECT COALESCE(SUM(amount),0) FROM contracts WHERE kind='income') AS income_total,
                  (SELECT COALESCE(SUM(amount),0) FROM contracts WHERE kind='cost')   AS cost_total,
                  (SELECT COUNT(*) FROM projects) AS project_count,
                  (SELECT COUNT(*) FROM contracts) AS contract_count,
                  (SELECT COALESCE(SUM(b.amount),0) FROM contract_billings b
                     JOIN contracts c ON c.id=b.contract_id
                     WHERE c.kind='income' AND b.kind='invoice') AS invoiced,
                  (SELECT COALESCE(SUM(b.amount),0) FROM contract_billings b
                     JOIN contracts c ON c.id=b.contract_id
                     WHERE c.kind='income' AND b.kind='payment') AS received,
                  (SELECT COALESCE(SUM(b.amount),0) FROM contract_billings b
                     JOIN contracts c ON c.id=b.contract_id
                     WHERE c.kind='cost' AND b.kind='payment') AS cost_paid
                """);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("totals", roundAll(totals));

        // 按甲方单位汇总（收入合同额、已回款）
        List<Map<String, Object>> byUnit = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT COALESCE(NULLIF(p.unit,''),'未填单位') AS unit,
                       COUNT(DISTINCT p.id) AS projects,
                       COALESCE(SUM(CASE WHEN c.kind='income' THEN c.amount END),0) AS income
                FROM projects p LEFT JOIN contracts c ON c.project_id = p.id
                GROUP BY COALESCE(NULLIF(p.unit,''),'未填单位')
                ORDER BY income DESC
                """)) {
            Map<String, Object> one = new LinkedHashMap<>(r);
            one.put("income", ContractRepo.round(((Number) r.get("income")).doubleValue()));
            byUnit.add(one);
        }
        out.put("by_unit", byUnit);

        // 收入合同的回款进度前 10（最该盯的）
        List<Map<String, Object>> watch = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT p.unit, p.name AS project, c.party_a, c.party_b,
                       COALESCE(c.amount,0) AS amount,
                       COALESCE((SELECT SUM(b.amount) FROM contract_billings b
                                 WHERE b.contract_id=c.id AND b.kind='invoice'),0) AS invoiced,
                       COALESCE((SELECT SUM(b.amount) FROM contract_billings b
                                 WHERE b.contract_id=c.id AND b.kind='payment'),0) AS received
                FROM contracts c JOIN projects p ON p.id = c.project_id
                WHERE c.kind='income' AND COALESCE(c.amount,0) > 0
                ORDER BY (COALESCE(c.amount,0) -
                          COALESCE((SELECT SUM(b.amount) FROM contract_billings b
                                    WHERE b.contract_id=c.id AND b.kind='payment'),0)) DESC
                LIMIT 10
                """)) {
            Map<String, Object> one = new LinkedHashMap<>(r);
            double amt = ((Number) r.get("amount")).doubleValue();
            double rec = ((Number) r.get("received")).doubleValue();
            one.put("remain", ContractRepo.round(Math.max(0, amt - rec)));
            one.put("paid_ratio", amt > 0 ? Math.round(rec * 100 / amt) : 0);
            watch.add(one);
        }
        out.put("watch", watch);

        // 按项目集汇总：一个集一行，看这批的规模、开票、回款
        List<Map<String, Object>> bySet = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("""
                SELECT s.id, s.code, s.name,
                       (SELECT COUNT(*) FROM projects p WHERE p.set_id = s.id) AS projects,
                       (SELECT COUNT(*) FROM contracts c JOIN projects p ON p.id = c.project_id
                         WHERE p.set_id = s.id) AS contracts,
                       COALESCE((SELECT SUM(c.amount) FROM contracts c
                                 JOIN projects p ON p.id = c.project_id
                                 WHERE p.set_id = s.id AND c.kind = 'income'), 0) AS income,
                       COALESCE((SELECT SUM(b.amount) FROM contract_billings b
                                 JOIN contracts c ON c.id = b.contract_id
                                 JOIN projects p ON p.id = c.project_id
                                 WHERE p.set_id = s.id AND c.kind = 'income'
                                   AND b.kind = 'invoice'), 0) AS invoiced,
                       COALESCE((SELECT SUM(b.amount) FROM contract_billings b
                                 JOIN contracts c ON c.id = b.contract_id
                                 JOIN projects p ON p.id = c.project_id
                                 WHERE p.set_id = s.id AND c.kind = 'income'
                                   AND b.kind = 'payment'), 0) AS received,
                       COALESCE((SELECT SUM(c.amount) FROM contracts c
                                 JOIN projects p ON p.id = c.project_id
                                 WHERE p.set_id = s.id AND c.kind = 'cost'), 0) AS cost
                FROM project_sets s
                ORDER BY s.year DESC, s.id DESC
                """)) {
            Map<String, Object> one = new LinkedHashMap<>(r);
            double inc = ((Number) r.get("income")).doubleValue();
            double rec = ((Number) r.get("received")).doubleValue();
            one.put("income", ContractRepo.round(inc));
            one.put("invoiced", ContractRepo.round(((Number) r.get("invoiced")).doubleValue()));
            one.put("received", ContractRepo.round(rec));
            one.put("cost", ContractRepo.round(((Number) r.get("cost")).doubleValue()));
            one.put("remain", ContractRepo.round(Math.max(0, inc - rec)));
            one.put("paid_ratio", inc > 0 ? Math.round(rec * 100 / inc) : 0);
            bySet.add(one);
        }
        out.put("by_set", bySet);
        return out;
    }

    private Map<String, Object> roundAll(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            Object v = e.getValue();
            out.put(e.getKey(), v instanceof Number n
                    ? (n.doubleValue() == Math.rint(n.doubleValue()) && !e.getKey().contains("total")
                       && !e.getKey().equals("invoiced") && !e.getKey().equals("received")
                       && !e.getKey().equals("cost_paid")
                       ? n.intValue() : ContractRepo.round(n.doubleValue()))
                    : v);
        }
        return out;
    }
}
