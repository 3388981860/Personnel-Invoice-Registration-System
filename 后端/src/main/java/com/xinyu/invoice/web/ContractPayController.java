package com.xinyu.invoice.web;

import com.xinyu.invoice.auth.SessionService;
import com.xinyu.invoice.common.ApiException;
import com.xinyu.invoice.repo.BatchRepo;
import com.xinyu.invoice.repo.ContractRepo;
import com.xinyu.invoice.repo.PayeeRepo;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外协付款：从合同生成一张报销单，走现有审批流。
 *
 * 生成后报销单会记住来自哪份合同（batches.contract_id）；
 * 管理员把这单标记「已报销」时，ContractController 会自动往合同里补一条付款记录。
 */
@RestController
public class ContractPayController extends BaseController {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ContractRepo contracts;
    private final BatchRepo batches;
    private final PayeeRepo payees;

    public ContractPayController(ContractRepo contracts, BatchRepo batches,
                                 PayeeRepo payees) {
        this.contracts = contracts;
        this.batches = batches;
        this.payees = payees;
    }

    @PostMapping("/api/contract/pay")
    public Map<String, Object> pay(@RequestBody Map<String, Object> body,
                                   HttpServletRequest req) {
        Map<String, Object> u = requirePerm(req, "contracts");
        long cid = ContractRepo.asLong(body.get("contract_id"), "缺少合同编号");
        Map<String, Object> c = contracts.contract(cid);
        if (c == null) {
            throw new ApiException("找不到这份合同", 404);
        }
        if (!"cost".equals(String.valueOf(c.get("kind")))) {
            throw new ApiException("只有外协合同才能生成付款单");
        }

        String invoiceNo = String.valueOf(body.get("invoice_no") == null ? ""
                : body.get("invoice_no")).replaceAll("\\D", "");
        if (invoiceNo.isEmpty()) {
            throw new ApiException("请填写外协开给我们的发票号码", 400, "invoice_no");
        }
        Double amount = ContractRepo.number(body.get("amount"));
        if (amount == null || amount <= 0) {
            throw new ApiException("请填写付款金额", 400, "amount");
        }
        String person = str(body.get("person")).strip();
        if (person.isEmpty()) {
            throw new ApiException("请填写报销人（一般是项目负责人）", 400, "person");
        }
        String payee = str(body.get("payee")).strip();
        if (payee.isEmpty()) {
            payee = str(c.get("party_b"));     // 默认外协单位
        }
        String note = str(body.get("note")).strip();
        String date = str(body.get("reimburse_date")).strip();

        // 报销单
        long bid = batches.createBatch(null, "外协付款：" + str(c.get("party_b")),
                uid(u), "submitted");
        // 记下这份单来自哪份合同，打款后好回填
        jdbc.update("UPDATE batches SET contract_id = ? WHERE id = ?", cid, bid);

        // 一张发票（外协开给我们的，可能没上传扫描件）
        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("invoice_no", invoiceNo);
        rec.put("invoice_date", date.isEmpty() ? null : date);
        rec.put("amount", amount);
        rec.put("seller", str(c.get("party_b")));
        rec.put("buyer", "人数（我司）");
        rec.put("person", person);
        rec.put("payee", payee);
        rec.put("reimburse_date", date.isEmpty() ? null : date);
        rec.put("source", "外协付款");
        rec.put("note", note.isEmpty() ? ("合同 " + str(c.get("contract_no"))
                + " 外协付款") : note);
        rec.put("status", 0);
        rec.put("audit_status", 3);         // 外协发票无需查验
        rec.put("batch_id", bid);
        rec.put("owner_id", uid(u));
        long rid = batches.insertInvoice(rec, null);

        // 收款人 = 外协单位
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", payee);
        p.put("amount", amount);
        p.put("note", "外协付款");
        payees.replace(bid, List.of(p));

        String batchNo = str(batches.batch(bid).get("batch_no"));
        // 通知要说清楚"谁提交的"，不然只看到报销人容易误会
        String operator = str(u.getOrDefault("display_name", u.get("username")));
        batches.notifyAdmins("submit", "外协付款单 " + batchNo,
                "收款 " + payee + " ¥" + amount + "（报销人 " + person
                        + "，提交人 " + operator + "）", bid);
        batches.log(uid(u), str(u.get("username")), "contract_pay_batch", "contract", cid,
                batchNo + "｜" + payee + "｜" + amount);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("batch_id", bid);
        out.put("batch_no", batchNo);
        out.put("invoice_id", rid);
        out.put("project", contracts.project(
                ((Number) c.get("project_id")).longValue()));
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
