package com.xinyu.invoice.repo;

import com.xinyu.invoice.common.ApiException;
import com.xinyu.invoice.config.AppProps;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 发票的改 / 删 / 归单 / 拆单 */
@Repository
public class InvoiceWriteRepo {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 允许改的字段（顺序无所谓，只用来过滤） */
    private static final List<String> TEXT_FIELDS = List.of(
            "invoice_no", "invoice_date", "seller", "buyer", "category",
            "person", "payee", "reimburse_date", "note", "file_name", "source");
    private static final List<String> NUM_FIELDS = List.of("amount", "amount_net", "tax");

    private final JdbcTemplate jdbc;
    private final AppProps props;

    public InvoiceWriteRepo(JdbcTemplate jdbc, AppProps props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    // ---------------------------------------------------------------- 改

    /**
     * 只改请求里带上的字段（没带的一律不动，否则会把别的字段清空）。
     */
    public void update(long id, Map<String, Object> body) {
        Map<String, Object> set = new LinkedHashMap<>();
        for (String k : TEXT_FIELDS) {
            if (!body.containsKey(k)) {
                continue;
            }
            String v = trim(body.get(k));
            set.put(k, v.isEmpty() ? null : v);
        }
        for (String k : NUM_FIELDS) {
            if (!body.containsKey(k)) {
                continue;
            }
            set.put(k, number(body.get(k)));
        }

        if (set.containsKey("invoice_no")) {
            String no = digits(set.get("invoice_no"));
            if (no.isEmpty()) {
                set.remove("invoice_no");       // 号码不允许被清空
            } else {
                List<Map<String, Object>> other = jdbc.queryForList(
                        "SELECT id, invoice_no FROM invoices WHERE invoice_no = ?", no);
                if (!other.isEmpty() && ((Number) other.get(0).get("id")).longValue() != id) {
                    throw new ApiException("这个发票号码已经被另一条记录用了，不能改成它", 409);
                }
                set.put("invoice_no", no);
            }
        }
        if (set.isEmpty()) {
            return;
        }
        set.put("updated_at", LocalDateTime.now().format(TS));

        StringBuilder sql = new StringBuilder("UPDATE invoices SET ");
        List<Object> args = new ArrayList<>();
        boolean first = true;
        for (Map.Entry<String, Object> e : set.entrySet()) {
            if (!first) {
                sql.append(", ");
            }
            sql.append(e.getKey()).append(" = ?");
            args.add(e.getValue());
            first = false;
        }
        sql.append(" WHERE id = ?");
        args.add(id);
        jdbc.update(sql.toString(), args.toArray());

        Object person = set.get("person");
        if (person != null && !String.valueOf(person).isBlank()) {
            addPerson(String.valueOf(person));
        }
    }

    /** 切换单张报销状态（管理员） */
    public void setStatus(long id, int status) {
        String now = LocalDateTime.now().format(TS);
        jdbc.update("UPDATE invoices SET status = ?, paid_at = ?, updated_at = ? WHERE id = ?",
                status, status == 1 ? now : null, now, id);
    }

    // ---------------------------------------------------------------- 删

    /** 删一张：原件移到「已删除」，行删掉，空单据顺手清理 */
    public void deleteInvoice(long id, Map<String, Object> rec) {
        Object rel = rec.get("file_rel");
        if (rel != null && !String.valueOf(rel).isBlank()) {
            moveToDeleted(String.valueOf(rel));
        }
        Object bid = rec.get("batch_id");
        jdbc.update("DELETE FROM invoices WHERE id = ?", id);
        if (bid != null) {
            pruneBatch(((Number) bid).longValue());
        }
    }

    /** 单里的发票删空了就顺手把单据删掉 */
    public void pruneBatch(long batchId) {
        Integer left = jdbc.queryForObject(
                "SELECT COUNT(*) FROM invoices WHERE batch_id = ?", Integer.class, batchId);
        if (left == null || left == 0) {
            jdbc.update("DELETE FROM batches WHERE id = ?", batchId);
        }
    }

    private void moveToDeleted(String relPath) {
        try {
            Path src = props.base()
                    .resolve(relPath.replace("/", java.io.File.separator)).normalize();
            if (!Files.isRegularFile(src)) {
                return;
            }
            Path dir = props.dataDir().resolve("已删除");
            Files.createDirectories(dir);
            String name = LocalDateTime.now().format(FILE_TS) + "_"
                    + src.getFileName().toString();
            Files.move(src, dir.resolve(name));
        } catch (IOException ignored) {
            // 移动失败不影响删除记录
        }
    }

    // ---------------------------------------------------------------- 归单 / 拆单

    /** 把发票挂到某张单上；没给单号就新建一张 */
    public long attach(List<Long> ids, Long batchId, String note, Long ownerId,
                       BatchRepo batches) {
        if (ids.isEmpty()) {
            throw new ApiException("请先选中要归单的发票");
        }
        long target;
        if (batchId == null) {
            target = batches.createBatch(null, note, ownerId, "submitted");
        } else {
            target = batchId;
            if (batches.batch(target) == null) {
                throw new ApiException("找不到这张报销单", 404);
            }
        }
        String now = LocalDateTime.now().format(TS);
        StringBuilder in = new StringBuilder();
        List<Object> args = new ArrayList<>();
        args.add(target);
        args.add(now);
        for (Long id : ids) {
            if (in.length() > 0) {
                in.append(",");
            }
            in.append("?");
            args.add(id);
        }
        jdbc.update("UPDATE invoices SET batch_id = ?, updated_at = ? WHERE id IN (" + in + ")",
                args.toArray());
        return target;
    }

    /** 从单里拆出来，变成单独一张票 */
    public void detach(List<Long> ids) {
        if (ids.isEmpty()) {
            throw new ApiException("请先选中要拆出的发票");
        }
        List<Long> oldBatches = new ArrayList<>();
        StringBuilder in = new StringBuilder();
        List<Object> args = new ArrayList<>();
        for (Long id : ids) {
            if (in.length() > 0) {
                in.append(",");
            }
            in.append("?");
            args.add(id);
        }
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT DISTINCT batch_id FROM invoices WHERE id IN (" + in + ")", args.toArray())) {
            Object b = r.get("batch_id");
            if (b != null) {
                oldBatches.add(((Number) b).longValue());
            }
        }
        List<Object> args2 = new ArrayList<>();
        args2.add(LocalDateTime.now().format(TS));
        args2.addAll(ids);
        jdbc.update("UPDATE invoices SET batch_id = NULL, updated_at = ? WHERE id IN (" + in + ")",
                args2.toArray());
        for (Long b : oldBatches) {
            pruneBatch(b);
        }
    }

    // ---------------------------------------------------------------- 工具

    private void addPerson(String name) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM persons WHERE name = ?",
                Integer.class, name);
        if (n == null || n == 0) {
            jdbc.update("INSERT INTO persons (name, sort) VALUES (?, 0)", name);
        }
    }

    public static String digits(Object v) {
        return String.valueOf(v == null ? "" : v).replaceAll("\\D", "");
    }

    private static String trim(Object v) {
        return v == null ? "" : String.valueOf(v).strip();
    }

    private static Double number(Object v) {
        if (v == null || String.valueOf(v).isBlank()) {
            return null;
        }
        try {
            double d = Double.parseDouble(String.valueOf(v).replace(",", "").replace("¥", "").strip());
            return Math.round(d * 100d) / 100d;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
