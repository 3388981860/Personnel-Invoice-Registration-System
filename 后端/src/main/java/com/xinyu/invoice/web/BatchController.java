package com.xinyu.invoice.web;

import com.xinyu.invoice.auth.SessionService;
import com.xinyu.invoice.common.ApiException;
import com.xinyu.invoice.repo.BatchQueryRepo;
import com.xinyu.invoice.repo.BatchRepo;
import com.xinyu.invoice.repo.StatsRepo;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 报销单：详情 / 审核 / 重新提交 / 撤回删除 / 整单标记已报销 */
@RestController
public class BatchController extends BaseController {

    private final BatchQueryRepo query;
    private final BatchRepo writer;
    private final StatsRepo statsRepo;
    private final com.xinyu.invoice.repo.PayeeRepo payees;

    public BatchController(BatchQueryRepo query, BatchRepo writer, StatsRepo statsRepo,
                           com.xinyu.invoice.repo.PayeeRepo payees) {
        this.query = query;
        this.writer = writer;
        this.statsRepo = statsRepo;
        this.payees = payees;
    }

    // ---------------------------------------------------------------- 列表 / 详情

    @GetMapping("/api/batches")
    public Map<String, Object> batches(@RequestParam(required = false) String q,
                                       @RequestParam(required = false) String review,
                                       HttpServletRequest req) {
        Map<String, Object> u = requireUser(req);
        Long owner = SessionService.isAdmin(u) ? null : uid(u);
        return Map.of("ok", true, "batches", query.list(q, review, owner));
    }

    @GetMapping("/api/batch")
    public Map<String, Object> detail(@RequestParam String id, HttpServletRequest req) {
        Map<String, Object> u = requireUser(req);
        Map<String, Object> b = load(id, u);
        b.put("issues", query.issues(b));
        b.put("can_edit", editable(b, u));
        return Map.of("ok", true, "batch", b);
    }

    // ---------------------------------------------------------------- 审核

    @PostMapping("/api/batch/review")
    public Map<String, Object> review(@RequestBody Map<String, Object> body,
                                      HttpServletRequest req) {
        Map<String, Object> admin = requirePerm(req, "review");
        long bid = asLong(body.get("id"), "缺少报销单编号");
        String action = str(body.get("action")).strip();
        if (!"approve".equals(action) && !"return".equals(action)) {
            throw new ApiException("审核动作只能是 approve 或 return");
        }
        Map<String, Object> b = query.detail(bid);
        BatchQueryRepo.requireFound(b);
        if (!"submitted".equals(BatchQueryRepo.reviewStatus(b))) {
            throw new ApiException("这张单不是待审核状态，不能重复审核", 409);
        }

        String note = str(body.get("note")).strip();
        List<Integer> issues = new ArrayList<>();
        String status;
        if ("return".equals(action)) {
            if (note.isEmpty()) {
                throw new ApiException("退回时请写明原因，方便提交人修改");
            }
            for (Object o : asList(body.get("issues"))) {
                if (o instanceof Number n) {
                    issues.add(n.intValue());
                } else if (o != null && str(o).matches("\\d+")) {
                    issues.add(Integer.parseInt(str(o)));
                }
            }
            status = "returned";
        } else {
            status = "approved";
        }

        query.setReview(bid, status, uid(admin), note.isEmpty() ? null : note, issues);
        Map<String, Object> after = query.detail(bid);

        // 通知提交人
        long ownerId = BatchQueryRepo.ownerOf(after);
        if (ownerId > 0) {
            if ("returned".equals(status)) {
                // 退回只通知提交人，管理员自己就是审核人，不用再收一遍
                notifyOne(ownerId, "return", "单据 " + after.get("batch_no") + " 被退回",
                        "审核意见" + (note.isEmpty() ? "：请查看后退回修改" : "：" + note), bid);
            } else {
                notifyOne(ownerId, "approve", "单据 " + after.get("batch_no") + " 已通过",
                        "审核通过，可以报销了", bid);
            }
        }
        writer.log(uid(admin), str(admin.get("username")),
                "returned".equals(status) ? "review_return" : "review_approve",
                "batch", bid, String.valueOf(after.get("batch_no")));

        return Map.of("ok", true, "batch", after,
                "stats", statsRepo.stats(null));
    }

    // ---------------------------------------------------------------- 重新提交

    @PostMapping("/api/batch_resubmit")
    public Map<String, Object> resubmit(@RequestBody Map<String, Object> body,
                                        HttpServletRequest req) {
        Map<String, Object> u = requireUser(req);
        long bid = asLong(body.get("id"), "缺少报销单编号");
        Map<String, Object> b = load(String.valueOf(bid), u);
        requireEditable(b, u);

        query.setReview(bid, "submitted", null, null, new ArrayList<>());
        Map<String, Object> after = query.detail(bid);

        writer.notifyAdmins("submit", "单据 " + after.get("batch_no") + " 重新提交",
                str(u.getOrDefault("display_name", u.get("username"))) + " 修改后重新提交，请审核",
                bid);
        writer.log(uid(u), str(u.get("username")), "resubmit", "batch", bid,
                String.valueOf(after.get("batch_no")));

        return Map.of("ok", true, "batch", after);
    }

    // ---------------------------------------------------------------- 修改单据

    /**
     * 改报销单：单号/备注只改单本身；报销人/收款人/报销日期要同步到单里所有发票，
     * 否则单头和明细对不上。跟旧版 update_batch 的行为一致。
     */
    @PostMapping("/api/batch_update")
    public Map<String, Object> update(@RequestBody Map<String, Object> body,
                                      HttpServletRequest req) {
        Map<String, Object> u = requireUser(req);
        long bid = asLong(body.get("id"), "缺少报销单编号");
        Map<String, Object> b = load(String.valueOf(bid), u);
        requireEditable(b, u);

        String stamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        // 单头字段
        Map<String, Object> head = new LinkedHashMap<>();
        if (body.containsKey("batch_no")) {
            String no = str(body.get("batch_no")).strip();
            if (!no.isEmpty()) {
                List<Map<String, Object>> dup = jdbc.queryForList(
                        "SELECT id FROM batches WHERE batch_no = ? AND id <> ?", no, bid);
                if (!dup.isEmpty()) {
                    throw new ApiException("这个报销单号已经被别的单用了", 409);
                }
                head.put("batch_no", no);
            }
        }
        if (body.containsKey("note")) {
            String note = str(body.get("note")).strip();
            head.put("note", note.isEmpty() ? null : note);
        }
        if (!head.isEmpty()) {
            head.put("updated_at", stamp);
            StringBuilder sql = new StringBuilder("UPDATE batches SET ");
            List<Object> args = new ArrayList<>();
            boolean first = true;
            for (Map.Entry<String, Object> e : head.entrySet()) {
                if (!first) {
                    sql.append(", ");
                }
                sql.append(e.getKey()).append(" = ?");
                args.add(e.getValue());
                first = false;
            }
            sql.append(" WHERE id = ?");
            args.add(bid);
            jdbc.update(sql.toString(), args.toArray());
        }

        // 这几个字段同步到单内每张发票
        Map<String, Object> inv = new LinkedHashMap<>();
        for (String k : List.of("person", "payee", "reimburse_date")) {
            if (body.containsKey(k)) {
                String v = str(body.get(k)).strip();
                inv.put(k, v.isEmpty() ? null : v);
            }
        }
        if (!inv.isEmpty()) {
            inv.put("updated_at", stamp);
            StringBuilder sql = new StringBuilder("UPDATE invoices SET ");
            List<Object> args = new ArrayList<>();
            boolean first = true;
            for (Map.Entry<String, Object> e : inv.entrySet()) {
                if (!first) {
                    sql.append(", ");
                }
                sql.append(e.getKey()).append(" = ?");
                args.add(e.getValue());
                first = false;
            }
            sql.append(" WHERE batch_id = ?");
            args.add(bid);
            jdbc.update(sql.toString(), args.toArray());
            Object person = inv.get("person");
            if (person != null && !String.valueOf(person).isBlank()) {
                addPerson(String.valueOf(person));
            }
        }

        // 收款信息整组替换（改了金额才需要传）
        if (body.containsKey("payees")) {
            List<Map<String, Object>> list =
                    com.xinyu.invoice.repo.PayeeRepo.clean(body.get("payees"));
            double invoiceTotal = 0;
            for (Map<String, Object> i : (List<Map<String, Object>>)
                    (query.detail(bid).get("invoices"))) {
                Object a = i.get("amount");
                invoiceTotal += a == null ? 0 : ((Number) a).doubleValue();
            }
            invoiceTotal = com.xinyu.invoice.repo.PayeeRepo.round(invoiceTotal);
            com.xinyu.invoice.repo.PayeeRepo.requireMatches(invoiceTotal, list);
            payees.replace(bid, list);
        }

        writer.log(uid(u), str(u.get("username")), "update_batch", "batch", bid,
                String.valueOf(b.get("batch_no")));
        return Map.of("ok", true, "batch", query.detail(bid));
    }

    /** 报销人下拉里没有就补一条 */
    private void addPerson(String name) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM persons WHERE name = ?", Integer.class, name);
        if (n == null || n == 0) {
            jdbc.update("INSERT INTO persons (name, sort) VALUES (?, 0)", name);
        }
    }

    // ---------------------------------------------------------------- 撤回删除

    @PostMapping("/api/batch_delete")
    public Map<String, Object> delete(@RequestBody Map<String, Object> body,
                                      HttpServletRequest req) {
        Map<String, Object> u = requireUser(req);
        long bid = asLong(body.get("id"), "缺少报销单编号");
        Map<String, Object> b = query.detail(bid);
        BatchQueryRepo.requireFound(b);

        if (BatchQueryRepo.ownerOf(b) != uid(u)) {
            throw new ApiException("只有提交人自己才能删除这张单", 403);
        }
        if (Boolean.TRUE.equals(b.get("locked"))) {
            throw new ApiException("这张单已经报销，已锁定，不能删除", 409);
        }
        String st = BatchQueryRepo.reviewStatus(b);
        if ("approved".equals(st)) {
            throw new ApiException("单据已通过审核，不能删除", 409);
        }
        boolean withdrawn = "submitted".equals(st);

        int n = query.deleteBatch(bid);
        writer.log(uid(u), str(u.get("username")),
                withdrawn ? "withdraw" : "delete_batch", "batch", bid,
                b.get("batch_no") + "｜" + n + " 张");
        if (withdrawn) {
            writer.notifyAdmins("withdraw", "单据 " + b.get("batch_no") + " 已撤回",
                    str(u.getOrDefault("display_name", u.get("username")))
                            + " 撤回了这张单，不用再审了", bid);
        }
        return Map.of("ok", true, "deleted", n, "withdrawn", withdrawn,
                "stats", statsRepo.stats(null));
    }

    // ---------------------------------------------------------------- 整单标记已报销

    @PostMapping("/api/batch_status")
    public Map<String, Object> batchStatus(@RequestBody Map<String, Object> body,
                                           HttpServletRequest req) {
        Map<String, Object> admin = requireAdmin(req);
        long bid = asLong(body.get("id"), "缺少报销单编号");
        int status = asLong(body.get("status"), "缺少状态") == 0 ? 0 : 1;
        String stamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        jdbc.update("UPDATE invoices SET status = ?, paid_at = ?, updated_at = ? "
                        + "WHERE batch_id = ?",
                status, status == 1 ? stamp : null, stamp, bid);
        jdbc.update("UPDATE batches SET updated_at = ? WHERE id = ?", stamp, bid);
        // 如果是外协付款单，打款后回填到合同里
        Object cid = null;
        List<Map<String, Object>> bRows = jdbc.queryForList(
                "SELECT contract_id FROM batches WHERE id = ?", bid);
        if (!bRows.isEmpty()) {
            cid = bRows.get(0).get("contract_id");
        }
        if (cid instanceof Number n && status == 1) {
            Double sum = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(amount),0) FROM invoices WHERE batch_id = ?",
                    Double.class, bid);
            String no = jdbc.queryForObject(
                    "SELECT COALESCE(MIN(invoice_no),'') FROM invoices WHERE batch_id = ?",
                    String.class, bid);
            jdbc.update("INSERT INTO contract_billings (contract_id, kind, stage, amount, "
                            + "ratio, no, happened_at, note, created_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?)",
                    n.longValue(), "payment", "外协付款", sum, null, no,
                    java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), "报销单 " + jdbc.queryForObject("SELECT COALESCE(batch_no,'') FROM batches WHERE id = ?", String.class, bid));
            jdbc.update("UPDATE batches SET updated_at = ? WHERE id = ?", stamp, bid);
        }

        writer.log(uid(admin), str(admin.get("username")), "batch_paid", "batch", bid,
                status == 1 ? "整单标记已报销" : "整单改回未报销");
        Map<String, Object> after = query.detail(bid);
        return Map.of("ok", true, "batch", after, "stats", statsRepo.stats(null));
    }

    // ---------------------------------------------------------------- 权限

    private Map<String, Object> load(String id, Map<String, Object> u) {
        long bid;
        try {
            bid = Long.parseLong(str(id));
        } catch (NumberFormatException e) {
            throw new ApiException("缺少报销单编号");
        }
        Map<String, Object> b = query.detail(bid);
        BatchQueryRepo.requireFound(b);
        if (!SessionService.isAdmin(u) && BatchQueryRepo.ownerOf(b) != uid(u)) {
            throw new ApiException("这张报销单不属于你", 403);
        }
        return b;
    }

    /** 管理员随时可改（已通过除外）；普通用户只能改自己已退回的 */
    private boolean editable(Map<String, Object> b, Map<String, Object> u) {
        if (b == null) {
            return false;
        }
        // 已报销 = 锁定：谁都不能再改，管理员先「改回未报销」才能动
        if (Boolean.TRUE.equals(b.get("locked"))) {
            return false;
        }
        // 管理员：只要没锁定（没报销）就能改——审核通过不等于不能改，
        // 真正锁死的是"已经打款"。要动已报销的单，先「改回未报销」。
        String st = BatchQueryRepo.reviewStatus(b);
        if (SessionService.isAdmin(u)) {
            return true;
        }
        return BatchQueryRepo.ownerOf(b) == uid(u) && "returned".equals(st);
    }

    private void requireEditable(Map<String, Object> b, Map<String, Object> u) {
        if (Boolean.TRUE.equals(b.get("locked"))) {
            throw new ApiException("这张单已经报销，已锁定。要改先让管理员「改回未报销」", 409);
        }
        if (!editable(b, u)) {
            throw new ApiException("这张单当前状态不能修改（待审核或已通过）", 409);
        }
    }

    private void notifyOne(long userId, String kind, String title, String body, long batchId) {
        jdbc.update("INSERT INTO notifications (user_id, kind, title, body, batch_id,"
                        + " created_at) VALUES (?,?,?,?,?,?)",
                userId, kind, title, body, batchId,
                java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
    }

    private static long asLong(Object v, String msg) {
        try {
            return Long.parseLong(str(v));
        } catch (NumberFormatException e) {
            throw new ApiException(msg);
        }
    }

    private static List<?> asList(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
