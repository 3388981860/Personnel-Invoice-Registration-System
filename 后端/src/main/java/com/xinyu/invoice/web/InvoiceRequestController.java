package com.xinyu.invoice.web;

import com.xinyu.invoice.auth.SessionService;
import com.xinyu.invoice.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 开票申请单。
 *
 * 流程跟报销单一套：草稿 → 提交 → 管理员审核（通过/退回）。
 * 申请人手写签名存成图片，打印时贴到「申请人」那一行。
 */
@RestController
public class InvoiceRequestController extends BaseController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final com.xinyu.invoice.config.AppProps props;

    public InvoiceRequestController(com.xinyu.invoice.config.AppProps props) {
        this.props = props;
    }

    // ---------------------------------------------------------------- 列表
    @GetMapping("/api/invoice_requests")
    public Map<String, Object> list(@RequestParam(required = false) String status,
                                    @RequestParam(required = false) String q,
                                    @RequestParam(required = false) String contract_id,
                                    HttpServletRequest req) {
        Map<String, Object> u = requirePerm(req, "contracts");
        StringBuilder sql = new StringBuilder("""
                SELECT r.*,
                  (SELECT p.name FROM projects p WHERE p.id = r.project_id) AS project_name,
                  (SELECT p.code FROM projects p WHERE p.id = r.project_id) AS project_code,
                  (SELECT c.party_a FROM contracts c WHERE c.id = r.contract_id) AS party_a
                FROM invoice_requests r WHERE 1=1""");
        List<Object> args = new ArrayList<>();
        if (!SessionService.isAdmin(u)) {
            sql.append(" AND r.applicant_id = ?");
            args.add(uid(u));
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND r.status = ?");
            args.add(status.strip());
        }
        if (contract_id != null && !contract_id.isBlank()) {
            sql.append(" AND r.contract_id = ?");
            args.add(Long.parseLong(contract_id.strip()));
        }
        if (q != null && !q.isBlank()) {
            sql.append(" AND (r.no LIKE ? OR r.buyer_name LIKE ? OR r.content LIKE ?)");
            String like = "%" + q.strip() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY r.id DESC LIMIT 300");
        List<Map<String, Object>> rows = args.isEmpty()
                ? jdbc.queryForList(sql.toString())
                : jdbc.queryForList(sql.toString(), args.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("requests", rows);
        out.put("pending", jdbc.queryForObject(
                "SELECT COUNT(*) FROM invoice_requests WHERE status='submitted'", Integer.class));
        return out;
    }

    @GetMapping("/api/invoice_request")
    public Map<String, Object> detail(@RequestParam String id, HttpServletRequest req) {
        Map<String, Object> u = requirePerm(req, "contracts");
        Map<String, Object> r = one(Long.parseLong(id));
        if (r == null) {
            throw new ApiException("找不到这张申请单", 404);
        }
        if (!SessionService.isAdmin(u) && !String.valueOf(uid(u)).equals(String.valueOf(r.get("applicant_id")))) {
            throw new ApiException("这不是你提交的单", 403);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("request", r);
        return out;
    }

    // ---------------------------------------------------------------- 保存
    @PostMapping("/api/invoice_request/save")
    public Map<String, Object> save(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Map<String, Object> u = requirePerm(req, "contracts");
        String now = LocalDateTime.now().format(TS);
        Object id = b.get("id");

        if (id instanceof Number n) {
            Map<String, Object> old = one(n.longValue());
            if (old == null) {
                throw new ApiException("找不到这张申请单", 404);
            }
            if (!"draft".equals(old.get("status")) && !"returned".equals(old.get("status"))) {
                throw new ApiException("已提交、已通过、已开票的单都不能再改", 403);
            }
            jdbc.update("""
                    UPDATE invoice_requests SET contract_id=?, project_id=?, customer_id=?,
                      buyer_name=?, buyer_tax_no=?, amount=?, content=?, note=?, inv_type=?,
                      dept=?, bank=?, applicant_name=?, agent_name=?, updated_at=?
                    WHERE id=?""",
                    num(b.get("contract_id")), num(b.get("project_id")), num(b.get("customer_id")),
                    str(b.get("buyer_name")), str(b.get("buyer_tax_no")), dbl(b.get("amount")),
                    str(b.get("content")), str(b.get("note")), str(b.get("inv_type")),
                    str(b.get("dept")), str(b.get("bank")), str(b.get("applicant_name")),
                    str(b.get("agent_name")), now, n.longValue());
            return Map.of("ok", true, "id", n.longValue());
        }

        String no = nextNo();
        jdbc.update("""
                INSERT INTO invoice_requests (no, contract_id, project_id, customer_id,
                  buyer_name, buyer_tax_no, amount, content, note, inv_type, dept, bank,
                  applicant_id, applicant_name, agent_name, status, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'draft',?,?)""",
                no, num(b.get("contract_id")), num(b.get("project_id")), num(b.get("customer_id")),
                str(b.get("buyer_name")), str(b.get("buyer_tax_no")), dbl(b.get("amount")),
                str(b.get("content")), str(b.get("note")), str(b.get("inv_type")),
                str(b.get("dept")), str(b.get("bank")),
                uid(u), str(b.get("applicant_name")), str(b.get("agent_name")), now, now);
        Long newId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        return Map.of("ok", true, "id", newId == null ? 0 : newId, "no", no);
    }

    // ---------------------------------------------------------------- 签名
    /** 手写签名：前端传 data:image/png;base64,xxx */
    @PostMapping("/api/invoice_request/sign")
    public Map<String, Object> sign(@RequestBody Map<String, Object> b, HttpServletRequest req)
            throws IOException {
        Map<String, Object> u = requirePerm(req, "contracts");
        Object idRaw = b.get("id");
        if (idRaw == null) {
            throw new ApiException("缺少 id");
        }
        long id = Long.parseLong(String.valueOf(idRaw));
        Map<String, Object> old = one(id);
        if (old == null) {
            throw new ApiException("找不到这张申请单", 404);
        }
        String data = str(b.get("image"));
        int comma = data.indexOf(',');
        if (comma < 0) {
            throw new ApiException("签名图片格式不对");
        }
        byte[] png = Base64.getDecoder().decode(data.substring(comma + 1));
        if (png.length < 200) {
            throw new ApiException("签名太简单，请重新签");
        }
        Path dir = props.dataDir().resolve("开票签名")
                .resolve(String.valueOf(LocalDate.now().getYear()));
        Files.createDirectories(dir);
        String fileName = "sign_" + id + "_" + System.currentTimeMillis() + ".png";
        Path file = dir.resolve(fileName);
        Files.write(file, png);
        String rel = "数据/开票签名/" + LocalDate.now().getYear() + "/" + fileName;
        String now = LocalDateTime.now().format(TS);
        jdbc.update("UPDATE invoice_requests SET sign_rel=?, sign_at=?, updated_at=? WHERE id=?",
                rel, now, now, id);
        return Map.of("ok", true, "sign_rel", rel, "sign_at", now);
    }

    /** 取签名图（打印用） */
    @GetMapping("/api/invoice_request/sign_img")
    public byte[] signImg(@RequestParam String id, HttpServletRequest req) throws IOException {
        requirePerm(req, "contracts");
        Map<String, Object> r = one(Long.parseLong(id));
        if (r == null || r.get("sign_rel") == null) {
            throw new ApiException("这张单还没有签名", 404);
        }
        Path f = props.base().resolve(String.valueOf(r.get("sign_rel")));
        if (!Files.isRegularFile(f)) {
            throw new ApiException("签名图片丢了", 404);
        }
        return Files.readAllBytes(f);
    }

    // ---------------------------------------------------------------- 提交 / 审核 / 删除
    @PostMapping("/api/invoice_request/submit")
    public Map<String, Object> submit(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Map<String, Object> u = requirePerm(req, "contracts");
        long id = Long.parseLong(String.valueOf(b.get("id")));
        Map<String, Object> r = one(id);
        if (r == null) {
            throw new ApiException("找不到这张申请单", 404);
        }
        if (r.get("sign_rel") == null) {
            throw new ApiException("请先签名再提交");
        }
        String now = LocalDateTime.now().format(TS);
        jdbc.update("UPDATE invoice_requests SET status='submitted', updated_at=? WHERE id=?",
                now, id);
        announce(id, r, "submit");
        return Map.of("ok", true);
    }

    @PostMapping("/api/invoice_request/review")
    public Map<String, Object> review(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Map<String, Object> u = requirePerm(req, "review");
        long id = Long.parseLong(String.valueOf(b.get("id")));
        boolean pass = Boolean.parseBoolean(String.valueOf(b.get("pass")));
        String note = str(b.get("note"));
        Map<String, Object> r = one(id);
        if (r == null) {
            throw new ApiException("找不到这张申请单", 404);
        }
        if (!"submitted".equals(r.get("status"))) {
            throw new ApiException("这张单不在待审核状态");
        }
        String now = LocalDateTime.now().format(TS);
        jdbc.update("UPDATE invoice_requests SET status=?, reviewer_id=?, reviewed_at=?,"
                        + " review_note=?, updated_at=? WHERE id=?",
                pass ? "approved" : "returned", uid(u), now, note, now, id);
        announce(id, r, pass ? "approved" : "returned");
        return Map.of("ok", true);
    }

    /**
     * 标记已开票：往合同里记一笔开票记录，申请单状态变「已开票」。
     * 这样合同的已开票金额、开票率就自动算进去了。
     */
    @PostMapping("/api/invoice_request/mark_invoiced")
    public Map<String, Object> markInvoiced(@RequestBody Map<String, Object> b,
                                            HttpServletRequest req) {
        Map<String, Object> u = requirePerm(req, "contracts");
        long id = Long.parseLong(String.valueOf(b.get("id")));
        Map<String, Object> r = one(id);
        if (r == null) {
            throw new ApiException("找不到这张申请单", 404);
        }
        if (!"approved".equals(r.get("status"))) {
            throw new ApiException("只有审核通过的单才能标记已开票");
        }
        Object cid = r.get("contract_id");
        if (cid == null) {
            throw new ApiException("这张单没关联合同，没法回写开票记录");
        }
        long contractId = ((Number) cid).longValue();
        List<Map<String, Object>> cs = jdbc.queryForList(
                "SELECT id, kind, amount FROM contracts WHERE id = ?", contractId);
        if (cs.isEmpty()) {
            throw new ApiException("关联的合同不存在了", 404);
        }
        if (!"income".equals(String.valueOf(cs.get(0).get("kind")))) {
            throw new ApiException("只有收入合同才能记开票");
        }
        String now = LocalDateTime.now().format(TS);
        String happenedAt = str(b.get("happened_at"));
        if (happenedAt.isEmpty()) {
            happenedAt = LocalDate.now().toString();
        }
        String invoiceNo = str(b.get("invoice_no"));
        Double amount = r.get("amount") == null ? null : ((Number) r.get("amount")).doubleValue();
        String stage = str(b.get("stage"));
        if (stage.isEmpty()) {
            stage = "按申请单开票";
        }
        jdbc.update("INSERT INTO contract_billings (contract_id, kind, stage, amount, ratio,"
                        + " no, happened_at, note, created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                contractId, "invoice", stage, amount, null, invoiceNo, happenedAt,
                "开票申请 " + r.get("no"), now);
        Long billingId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        jdbc.update("UPDATE invoice_requests SET status='invoiced', billing_id=?,"
                + " invoiced_at=?, updated_at=? WHERE id=?", billingId, now, now, id);

        // 通知申请人一声
        if (r.get("applicant_id") != null) {
            jdbc.update("INSERT INTO notifications (user_id, kind, title, body, created_at)"
                            + " VALUES (?,?,?,?,?)", r.get("applicant_id"), "invoice_req",
                    "开票申请 " + r.get("no"),
                    "已开票：" + (r.get("buyer_name") == null ? "" : r.get("buyer_name"))
                            + " ¥" + (amount == null ? "" : amount), now);
        }
        return Map.of("ok", true, "billing_id", billingId == null ? 0 : billingId);
    }

    @PostMapping("/api/invoice_request/delete")
    public Map<String, Object> delete(@RequestBody Map<String, Object> b, HttpServletRequest req) {
        Map<String, Object> u = requirePerm(req, "contracts");
        long id = Long.parseLong(String.valueOf(b.get("id")));
        Map<String, Object> r = one(id);
        if (r == null) {
            return Map.of("ok", true);
        }
        boolean mine = String.valueOf(uid(u)).equals(String.valueOf(r.get("applicant_id")));
        boolean editable = "draft".equals(r.get("status")) || "returned".equals(r.get("status"));
        if (!SessionService.isAdmin(u) && !(mine && editable)) {
            throw new ApiException("只能删自己还没提交的单", 403);
        }
        jdbc.update("DELETE FROM notifications WHERE batch_id IS NULL AND title LIKE ?",
                "%" + r.get("no") + "%");
        jdbc.update("DELETE FROM invoice_requests WHERE id = ?", id);
        return Map.of("ok", true);
    }

    // ---------------------------------------------------------------- 内部
    private Map<String, Object> one(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT r.*,
                  (SELECT p.name FROM projects p WHERE p.id = r.project_id) AS project_name,
                  (SELECT p.code FROM projects p WHERE p.id = r.project_id) AS project_code,
                  (SELECT c.party_a FROM contracts c WHERE c.id = r.contract_id) AS party_a
                FROM invoice_requests r WHERE r.id = ?""", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String nextNo() {
        String year = String.valueOf(LocalDate.now().getYear());
        String prefix = "KP-" + year + "-";
        int max = 0;
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT no FROM invoice_requests WHERE no LIKE ?", prefix + "%")) {
            String tail = String.valueOf(r.get("no")).substring(prefix.length());
            try {
                max = Math.max(max, Integer.parseInt(tail.replaceAll("[^0-9]", "")));
            } catch (NumberFormatException ignored) {
                // 跳过不规范的
            }
        }
        return prefix + String.format("%03d", max + 1);
    }

    /** 提交/审核结果通知对方 */
    private void announce(long id, Map<String, Object> r, String kind) {
        String now = LocalDateTime.now().format(TS);
        String title = "开票申请 " + r.get("no");
        String body = (r.get("buyer_name") == null ? "" : r.get("buyer_name"))
                + " ¥" + r.get("amount");
        if ("submit".equals(kind)) {
            for (Map<String, Object> a : jdbc.queryForList(
                    "SELECT id FROM users WHERE status = 1 AND role = 'admin'")) {
                jdbc.update("INSERT INTO notifications (user_id, kind, title, body, created_at)"
                        + " VALUES (?,?,?,?,?)", a.get("id"), "invoice_req", title,
                        r.get("applicant_name") + " 提交了开票申请：" + body, now);
            }
        } else {
            String label = "approved".equals(kind) ? "已通过" : "被退回";
            jdbc.update("INSERT INTO notifications (user_id, kind, title, body, created_at)"
                            + " VALUES (?,?,?,?,?)", r.get("applicant_id"), "invoice_req", title,
                    "你的开票申请" + label + "：" + body
                            + (r.get("review_note") == null ? "" : "（" + r.get("review_note") + "）"),
                    now);
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static Object num(Object o) {
        return o == null ? null : Long.parseLong(String.valueOf(o));
    }

    private static Double dbl(Object o) {
        if (o == null || String.valueOf(o).isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(o).replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
