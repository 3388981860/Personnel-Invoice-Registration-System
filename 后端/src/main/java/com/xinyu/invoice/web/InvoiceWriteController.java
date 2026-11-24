package com.xinyu.invoice.web;

import com.xinyu.invoice.auth.SessionService;
import com.xinyu.invoice.common.ApiException;
import com.xinyu.invoice.repo.BatchQueryRepo;
import com.xinyu.invoice.repo.BatchRepo;
import com.xinyu.invoice.repo.InvoiceRepo;
import com.xinyu.invoice.repo.InvoiceWriteRepo;
import com.xinyu.invoice.repo.StatsRepo;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 台账的改 / 状态 / 删 / 归单 / 拆单 */
@RestController
public class InvoiceWriteController extends BaseController {

    private final InvoiceRepo invoices;
    private final InvoiceWriteRepo writer;
    private final BatchRepo batches;
    private final BatchQueryRepo batchQuery;
    private final StatsRepo statsRepo;

    public InvoiceWriteController(InvoiceRepo invoices, InvoiceWriteRepo writer,
                                  BatchRepo batches, BatchQueryRepo batchQuery,
                                  StatsRepo statsRepo) {
        this.invoices = invoices;
        this.writer = writer;
        this.batches = batches;
        this.batchQuery = batchQuery;
        this.statsRepo = statsRepo;
    }

    // ---------------------------------------------------------------- 修改

    @PostMapping("/api/update")
    public Map<String, Object> update(@RequestBody Map<String, Object> body,
                                      HttpServletRequest req) {
        Map<String, Object> u = requireUser(req);
        long id = asLong(body.get("id"), "缺少记录编号");
        Map<String, Object> rec = invoices.get(id);
        if (rec == null) {
            throw new ApiException("找不到这张发票", 404);
        }
        requireOwn(rec, u);
        requireEditable(rec, u);

        writer.update(id, body);
        Map<String, Object> after = invoices.get(id);
        batches.log(uid(u), str(u.get("username")), "update_invoice", "invoice", id,
                str(after.get("invoice_no")));
        return Map.of("ok", true, "record", after,
                "stats", statsRepo.stats(ownerFilter(u)));
    }

    // ---------------------------------------------------------------- 报销状态

    @PostMapping("/api/status")
    public Map<String, Object> status(@RequestBody Map<String, Object> body,
                                      HttpServletRequest req) {
        Map<String, Object> admin = requirePerm(req, "review");
        long id = asLong(body.get("id"), "缺少记录编号");
        Map<String, Object> rec = invoices.get(id);
        if (rec == null) {
            throw new ApiException("找不到这条记录", 404);
        }
        int status = asLong(body.get("status"), "缺少状态") == 0 ? 0 : 1;
        writer.setStatus(id, status);
        batches.log(uid(admin), str(admin.get("username")), "set_status", "invoice", id,
                str(rec.get("invoice_no")) + (status == 1 ? " 已报销" : " 未报销"));
        return Map.of("ok", true, "record", invoices.get(id),
                "stats", statsRepo.stats(null));
    }

    // ---------------------------------------------------------------- 删除

    @PostMapping("/api/delete")
    public Map<String, Object> delete(@RequestBody Map<String, Object> body,
                                      HttpServletRequest req) {
        Map<String, Object> u = requireUser(req);
        long id = asLong(body.get("id"), "缺少记录编号");
        Map<String, Object> rec = invoices.get(id);
        if (rec == null) {
            throw new ApiException("找不到这张发票", 404);
        }

        // 已报销 = 锁定，谁的都不许删
        Object bid0 = rec.get("batch_id");
        if (bid0 != null) {
            Map<String, Object> b0 = batchQuery.detail(((Number) bid0).longValue());
            if (b0 != null && Boolean.TRUE.equals(b0.get("locked"))) {
                throw new ApiException("这张单已经报销，已锁定，不能删除", 409);
            }
        }
        // 规则：只有发起人本人，且单据不是「已通过」
        if (ownerOfRec(rec) != uid(u)) {
            throw new ApiException("只有提交人自己才能删除", 403);
        }
        Object bid = rec.get("batch_id");
        if (bid != null) {
            Map<String, Object> b = batchQuery.detail(((Number) bid).longValue());
            if (b != null) {
                if (BatchQueryRepo.ownerOf(b) != uid(u)) {
                    throw new ApiException("只有提交人自己才能删除", 403);
                }
                if ("approved".equals(BatchQueryRepo.reviewStatus(b))) {
                    throw new ApiException("单据已通过审核，不能删除", 409);
                }
            }
        }

        writer.deleteInvoice(id, rec);
        batches.log(uid(u), str(u.get("username")), "delete_invoice", "invoice", id,
                str(rec.get("invoice_no")));
        return Map.of("ok", true, "stats", statsRepo.stats(ownerFilter(u)));
    }

    // ---------------------------------------------------------------- 归单 / 拆单

    @PostMapping("/api/attach")
    public Map<String, Object> attach(@RequestBody Map<String, Object> body,
                                      HttpServletRequest req) {
        Map<String, Object> u = requireUser(req);
        List<Long> ids = ids(body.get("ids"));
        if (ids.isEmpty()) {
            throw new ApiException("请先选中要归单的发票");
        }
        for (Long id : ids) {
            Map<String, Object> rec = invoices.get(id);
            if (rec == null) {
                throw new ApiException("找不到这张发票", 404);
            }
            requireOwn(rec, u);
            requireEditable(rec, u);
        }

        Long batchId = null;
        Object raw = body.get("batch_id");
        if (raw != null && !String.valueOf(raw).isBlank()) {
            batchId = asLong(raw, "报销单编号不对");
            Map<String, Object> b = batchQuery.detail(batchId);
            BatchQueryRepo.requireFound(b);
            if (!SessionService.isAdmin(u) && BatchQueryRepo.ownerOf(b) != uid(u)) {
                throw new ApiException("这张报销单不属于你", 403);
            }
            if (!editable(b, u)) {
                throw new ApiException("这张单当前状态不能修改（待审核或已通过）", 409);
            }
        }
        String note = str(body.get("note")).strip();

        long target = writer.attach(ids, batchId, note.isEmpty() ? null : note,
                uid(u), batches);
        batches.log(uid(u), str(u.get("username")), "attach", "batch", target,
                ids.size() + " 张");
        return Map.of("ok", true, "batch_id", target,
                "stats", statsRepo.stats(ownerFilter(u)));
    }

    @PostMapping("/api/detach")
    public Map<String, Object> detach(@RequestBody Map<String, Object> body,
                                      HttpServletRequest req) {
        Map<String, Object> u = requireUser(req);
        List<Long> ids = ids(body.get("ids"));
        if (ids.isEmpty()) {
            throw new ApiException("请先选中要拆出的发票");
        }
        for (Long id : ids) {
            Map<String, Object> rec = invoices.get(id);
            if (rec == null) {
                throw new ApiException("找不到这张发票", 404);
            }
            requireOwn(rec, u);
            requireEditable(rec, u);
        }
        writer.detach(ids);
        batches.log(uid(u), str(u.get("username")), "detach", "invoice", null,
                ids.size() + " 张");
        return Map.of("ok", true, "stats", statsRepo.stats(ownerFilter(u)));
    }

    // ---------------------------------------------------------------- 权限

    private void requireOwn(Map<String, Object> rec, Map<String, Object> u) {
        if (!SessionService.isAdmin(u) && ownerOfRec(rec) != uid(u)) {
            throw new ApiException("这张发票不属于你", 403);
        }
    }

    /** 在单里就要看单的状态；没归单的随便改 */
    private void requireEditable(Map<String, Object> rec, Map<String, Object> u) {
        Object bid = rec.get("batch_id");
        if (bid == null) {
            return;
        }
        Map<String, Object> b = batchQuery.detail(((Number) bid).longValue());
        if (b == null) {
            return;
        }
        // 整单已报销 = 锁定
        if (Boolean.TRUE.equals(b.get("locked"))) {
            throw new ApiException("这张单已经报销，已锁定，不能再改", 409);
        }
        if (!editable(b, u)) {
            throw new ApiException("这张单当前状态不能修改（待审核或已通过）", 409);
        }
    }

    private boolean editable(Map<String, Object> b, Map<String, Object> u) {
        if (Boolean.TRUE.equals(b.get("locked"))) {
            return false;
        }
        // 管理员只要没锁定就能改；普通用户只有自己「已退回」的单
        String st = BatchQueryRepo.reviewStatus(b);
        if (SessionService.isAdmin(u)) {
            return true;
        }
        return BatchQueryRepo.ownerOf(b) == uid(u) && "returned".equals(st);
    }

    private Long ownerFilter(Map<String, Object> u) {
        return SessionService.isAdmin(u) ? null : uid(u);
    }

    private static long ownerOfRec(Map<String, Object> rec) {
        Object o = rec.get("owner_id");
        return o == null ? 0 : ((Number) o).longValue();
    }

    private static List<Long> ids(Object raw) {
        List<Long> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object x : list) {
                try {
                    out.add(Long.parseLong(String.valueOf(x)));
                } catch (NumberFormatException ignored) {
                    // 跳过非法 id
                }
            }
        }
        return out;
    }

    private static long asLong(Object v, String msg) {
        try {
            return Long.parseLong(str(v));
        } catch (NumberFormatException e) {
            throw new ApiException(msg);
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
