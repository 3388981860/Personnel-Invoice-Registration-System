package com.xinyu.invoice.web;

import com.xinyu.invoice.auth.SessionService;
import com.xinyu.invoice.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 客户档案：开票申请要用到的单位全称、税号、开户行、账号 */
@RestController
public class CustomerController extends BaseController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @GetMapping("/api/customers")
    public Map<String, Object> list(@RequestParam(required = false) String q,
                                    HttpServletRequest req) {
        requirePerm(req, "contracts");
        StringBuilder sql = new StringBuilder(
                "SELECT id, name, tax_no, bank, account, contact, phone, note FROM customers");
        List<Object> args = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            sql.append(" WHERE name LIKE ? OR tax_no LIKE ? OR contact LIKE ?");
            String like = "%" + q.strip() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        sql.append(" ORDER BY id DESC");
        List<Map<String, Object>> rows = args.isEmpty()
                ? jdbc.queryForList(sql.toString())
                : jdbc.queryForList(sql.toString(), args.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("customers", rows);
        return out;
    }

    @PostMapping("/api/customer/save")
    public Map<String, Object> save(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        requirePerm(req, "contracts");
        String name = str(body.get("name"));
        if (name.isEmpty()) {
            throw new ApiException("请填写单位全称", 400, "name");
        }
        String now = LocalDateTime.now().format(TS);
        Object id = body.get("id");
        if (id instanceof Number n) {
            jdbc.update("UPDATE customers SET name=?, tax_no=?, bank=?, account=?, contact=?,"
                            + " phone=?, note=? WHERE id=?",
                    name, str(body.get("tax_no")), str(body.get("bank")), str(body.get("account")),
                    str(body.get("contact")), str(body.get("phone")), str(body.get("note")),
                    n.longValue());
            return Map.of("ok", true, "id", n.longValue());
        }
        jdbc.update("INSERT INTO customers (name, tax_no, bank, account, contact, phone, note, created_at)"
                        + " VALUES (?,?,?,?,?,?,?,?)",
                name, str(body.get("tax_no")), str(body.get("bank")), str(body.get("account")),
                str(body.get("contact")), str(body.get("phone")), str(body.get("note")), now);
        Long newId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        return Map.of("ok", true, "id", newId == null ? 0 : newId);
    }

    @PostMapping("/api/customer/delete")
    public Map<String, Object> delete(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Map<String, Object> u = requirePerm(req, "contracts");
        if (!SessionService.isAdmin(u)) {
            throw new ApiException("只有管理员能删客户档案", 403);
        }
        Object id = body.get("id");
        if (id == null) {
            throw new ApiException("缺少 id");
        }
        Integer used = jdbc.queryForObject(
                "SELECT COUNT(*) FROM contracts WHERE customer_id = ?", Integer.class,
                Long.parseLong(String.valueOf(id)));
        if (used != null && used > 0) {
            throw new ApiException("还有 " + used + " 份合同挂在这个客户上，先改合同再删");
        }
        jdbc.update("DELETE FROM customers WHERE id = ?", Long.parseLong(String.valueOf(id)));
        return Map.of("ok", true);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
