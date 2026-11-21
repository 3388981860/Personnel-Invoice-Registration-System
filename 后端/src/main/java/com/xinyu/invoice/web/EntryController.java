package com.xinyu.invoice.web;

import com.xinyu.invoice.auth.SessionService;
import com.xinyu.invoice.common.ApiException;
import com.xinyu.invoice.config.AppProps;
import com.xinyu.invoice.repo.BatchRepo;
import com.xinyu.invoice.repo.InvoiceRepo;
import com.xinyu.invoice.repo.StatsRepo;
import com.xinyu.invoice.service.ParseService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** 上传识别 + 提交报销单 */
@RestController
public class EntryController extends BaseController {

    private static final Logger log = LoggerFactory.getLogger(EntryController.class);
    private static final Set<String> EXT = Set.of(".pdf", ".ofd");

    private final ParseService parser;
    private final BatchRepo batches;
    private final StatsRepo statsRepo;
    private final InvoiceRepo invoices;
    private final AppProps props;
    private final com.xinyu.invoice.repo.PayeeRepo payees;

    public EntryController(ParseService parser, BatchRepo batches, StatsRepo statsRepo,
                           com.xinyu.invoice.repo.PayeeRepo payees,
                           InvoiceRepo invoices, AppProps props) {
        this.parser = parser;
        this.batches = batches;
        this.statsRepo = statsRepo;
        this.invoices = invoices;
        this.props = props;
        this.payees = payees;
    }

    // ---------------------------------------------------------------- 上传识别

    @PostMapping("/api/parse")
    public Map<String, Object> parse(HttpServletRequest req) throws IOException {
        requireUser(req);
        byte[] raw = req.getInputStream().readAllBytes();
        if (raw.length == 0) {
            throw new ApiException("没有收到文件内容");
        }
        String filename = fileName(req.getHeader("X-File-Name"));
        String ext = extension(filename);
        if (!EXT.contains(ext)) {
            throw new ApiException("只支持 PDF 和 OFD 发票文件，收到的是 " + (ext.isEmpty() ? "未知格式" : ext));
        }

        Path tmpDir = props.dataDir().resolve("临时");
        Files.createDirectories(tmpDir);
        String token = UUID.randomUUID().toString().replace("-", "") + ext;
        Path tmp = tmpDir.resolve(token);
        Files.write(tmp, raw);

        Map<String, Object> fields;
        try {
            fields = parser.parse(tmp);
        } catch (RuntimeException e) {
            // 识别失败就把临时文件清掉，别在目录里越堆越多
            Files.deleteIfExists(tmp);
            throw e;
        }

        String no = BatchRepo.digits(fields.get("invoice_no"));
        Map<String, Object> exist = no.isEmpty() ? null : batches.byNo(no);
        if (exist != null) {
            Files.deleteIfExists(tmp);
            exist.remove("password_hash");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("token", exist != null ? null : token);
        out.put("file_name", filename);
        out.put("fields", fields);
        out.put("exists", exist != null);
        out.put("record", exist);
        return out;
    }

    // ---------------------------------------------------------------- 提交报销单

    @PostMapping("/api/batch_add")
    public Map<String, Object> batchAdd(@RequestBody Map<String, Object> body,
                                        HttpServletRequest req) {
        Map<String, Object> u = requireUser(req);
        long ownerId = uid(u);

        List<Map<String, Object>> items = asList(body.get("items"));
        if (items.isEmpty()) {
            throw new ApiException("本单还没有发票，请先拖入或手工加一行");
        }
        String person = str(body.get("person")).strip();
        String payee = str(body.get("payee")).strip();
        if (person.isEmpty()) {
            throw new ApiException("请填写报销人", 400, "person");
        }
        if (payee.isEmpty()) {
            throw new ApiException("请填写收款人", 400, "payee");
        }

        // 先全部校验，任何一张有问题就整批不落库
        Set<String> seen = new HashSet<>();
        List<Object[]> clean = new ArrayList<>();
        for (Map<String, Object> it : items) {
            String no = BatchRepo.digits(it.get("invoice_no"));
            if (no.isEmpty()) {
                throw new ApiException("有发票号码是空的，请补上再提交");
            }
            if (!seen.add(no)) {
                throw new ApiException("本单里有重复的发票号码：" + no, 409);
            }
            Map<String, Object> exist = batches.byNo(no);
            if (exist != null) {
                throw new ApiException("发票号码 " + no + " 之前已经登记过了", 409);
            }
            clean.add(new Object[]{no, it});
        }

        String batchNo = str(body.get("batch_no")).strip();
        String note = str(body.get("note")).strip();
        String reimburseDate = str(body.get("reimburse_date")).strip();

        // 发票合计，用来跟收款信息对账
        double invoiceTotal = 0;
        for (Object[] pair : clean) {
            Double amt = number(asMap(pair[1]).get("amount"));
            invoiceTotal += amt == null ? 0 : amt;
        }
        invoiceTotal = com.xinyu.invoice.repo.PayeeRepo.round(invoiceTotal);

        // 收款信息：没传就按「一个人收全部」处理，跟旧版行为一致
        List<Map<String, Object>> payeeList =
                com.xinyu.invoice.repo.PayeeRepo.clean(body.get("payees"));
        if (payeeList.isEmpty()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("name", payee);
            one.put("amount", invoiceTotal);
            payeeList = new ArrayList<>(List.of(one));
        }
        com.xinyu.invoice.repo.PayeeRepo.requireMatches(invoiceTotal, payeeList);

        long bid = batches.createBatch(batchNo.isEmpty() ? null : batchNo,
                note.isEmpty() ? null : note, ownerId, "submitted");

        List<Long> ids = new ArrayList<>();
        for (Object[] pair : clean) {
            String no = (String) pair[0];
            Map<String, Object> it = asMap(pair[1]);

            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("invoice_no", no);
            rec.put("invoice_date", blankToNull(it.get("invoice_date")));
            rec.put("amount", number(it.get("amount")));
            rec.put("seller", blankToNull(it.get("seller")));
            rec.put("buyer", blankToNull(it.get("buyer")));
            rec.put("category", blankToNull(it.get("category")));
            rec.put("source", blankToNull(it.get("source")));
            rec.put("file_name", blankToNull(it.get("file_name")));
            rec.put("person", person);
            rec.put("payee", payee);
            rec.put("reimburse_date", reimburseDate.isEmpty() ? null : reimburseDate);
            rec.put("note", blankToNull(it.get("note")));
            rec.put("status", 0);
            rec.put("audit_status", 0);
            rec.put("batch_id", bid);
            rec.put("owner_id", ownerId);

            Path tmp = tmpFile(it.get("token"));
            ids.add(batches.insertInvoice(rec, tmp));
        }

        payees.replace(bid, payeeList);

        String finalNo = String.valueOf(batches.batch(bid).get("batch_no"));
        batches.notifyAdmins("submit", "新的报销单 " + finalNo,
                str(u.get("display_name")) + " 提交了 " + ids.size() + " 张发票（收款 "
                        + payeeList.size() + " 人），等待审核",
                bid);
        batches.log(ownerId, str(u.get("username")), "submit_batch", "batch", bid,
                finalNo + "｜" + ids.size() + " 张");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("batch_id", bid);
        out.put("batch_no", finalNo);
        out.put("ids", ids);
        out.put("stats", statsRepo.stats(SessionService.isAdmin(u) ? null : ownerId));
        return out;
    }

    // ---------------------------------------------------------------- 工具

    private Path tmpFile(Object token) {
        String t = str(token);
        if (t.isEmpty()) {
            return null;
        }
        Path p = props.dataDir().resolve("临时").resolve(Path.of(t).getFileName().toString());
        return Files.isRegularFile(p) ? p : null;
    }

    private static String fileName(String header) {
        if (header == null || header.isBlank()) {
            return "发票";
        }
        try {
            return new String(Base64.getDecoder().decode(header), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            try {
                return URLDecoder.decode(header, StandardCharsets.UTF_8);
            } catch (Exception e2) {
                return header;
            }
        }
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase();
    }

    private static Object blankToNull(Object v) {
        String s = str(v).strip();
        return s.isEmpty() ? null : s;
    }

    private static Double number(Object v) {
        if (v == null || str(v).isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(str(v).replace(",", "").replace("¥", "").strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object x : list) {
                if (x instanceof Map) {
                    out.add((Map<String, Object>) x);
                }
            }
        }
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
