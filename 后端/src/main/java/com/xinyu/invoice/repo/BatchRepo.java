package com.xinyu.invoice.repo;

import com.xinyu.invoice.common.ApiException;
import com.xinyu.invoice.config.AppProps;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 报销单与发票写入（提交报销单用） */
@Repository
public class BatchRepo {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NamedParameterJdbcTemplate jdbc;   // 具名参数（INSERT 用）
    private final JdbcTemplate plain;                // 位置参数（查询用）
    private final AppProps props;

    public BatchRepo(NamedParameterJdbcTemplate jdbc, JdbcTemplate plain, AppProps props) {
        this.jdbc = jdbc;
        this.plain = plain;
        this.props = props;
    }

    // ---------------------------------------------------------------- 报销单

    /** 生成报销单号：BX + 日期 + 三位序号 */
    public String nextBatchNo() {
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String prefix = "BX" + day + "-";
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT batch_no FROM batches WHERE batch_no LIKE :p ORDER BY batch_no DESC LIMIT 1",
                Map.of("p", prefix + "%"));
        int n = 1;
        if (!rows.isEmpty()) {
            String last = String.valueOf(rows.get(0).get("batch_no"));
            try {
                n = Integer.parseInt(last.substring(last.lastIndexOf('-') + 1)) + 1;
            } catch (Exception ignored) {
                n = 1;
            }
        }
        return String.format("%s%03d", prefix, n);
    }

    /** 建一张报销单；指定的单号重复时自动加 -2、-3 */
    public long createBatch(String batchNo, String note, Long ownerId, String reviewStatus) {
        String no = (batchNo == null || batchNo.isBlank()) ? nextBatchNo() : batchNo.trim();
        for (int i = 2; exists(no); i++) {
            no = batchNo.trim() + "-" + i;
            if (i > 50) {
                throw new ApiException("这个报销单号已经存在，请换一个", 409);
            }
        }
        String now = LocalDateTime.now().format(TS);
        KeyHolder keys = new GeneratedKeyHolder();
        MapSqlParameterSource p = new MapSqlParameterSource();
        p.addValue("no", no);
        p.addValue("note", note);
        p.addValue("now", now);
        p.addValue("owner", ownerId);
        p.addValue("review", reviewStatus == null ? "submitted" : reviewStatus);
        jdbc.update("INSERT INTO batches (batch_no, note, created_at, updated_at, owner_id,"
                        + " review_status) VALUES (:no,:note,:now,:now,:owner,:review)",
                p, keys, new String[]{"id"});
        Number key = keys.getKey();
        if (key == null) {
            throw new ApiException("创建报销单失败", 500);
        }
        return key.longValue();
    }

    public boolean exists(String batchNo) {
        Integer n = plain.queryForObject("SELECT COUNT(*) FROM batches WHERE batch_no = ?",
                Integer.class, batchNo);
        return n != null && n > 0;
    }

    public Map<String, Object> batch(long id) {
        List<Map<String, Object>> rows = plain.queryForList(
                "SELECT * FROM batches WHERE id = ?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ---------------------------------------------------------------- 发票

    public Map<String, Object> byNo(String invoiceNo) {
        List<Map<String, Object>> rows = plain.queryForList(
                "SELECT * FROM invoices WHERE invoice_no = ?", invoiceNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 插入一张发票。
     *
     * @param data    字段（已是数据库列名）
     * @param tmpFile 原件临时文件，会归档到 发票存档/年/月/号码.pdf
     * @return 新发票 id
     */
    public long insertInvoice(Map<String, Object> data, Path tmpFile) {
        String no = digits(data.get("invoice_no"));
        if (no.isEmpty()) {
            throw new ApiException("发票号码不能为空");
        }
        if (byNo(no) != null) {
            throw new ApiException("发票号码 " + no + " 之前已经登记过了", 409);
        }

        Map<String, Object> rec = new LinkedHashMap<>(data);
        rec.put("invoice_no", no);
        rec.put("file_rel", null);
        if (tmpFile != null && Files.isRegularFile(tmpFile)) {
            rec.put("file_rel", archive(tmpFile, no, str(data.get("invoice_date"))));
            try {
                Files.deleteIfExists(tmpFile);
            } catch (IOException ignored) {
                // 归档成功即可，临时文件删不掉也不影响
            }
        }
        rec.putIfAbsent("status", 0);
        rec.putIfAbsent("audit_status", 0);
        String now = LocalDateTime.now().format(TS);
        rec.put("created_at", now);
        rec.put("updated_at", now);
        rec.remove("id");

        StringBuilder cols = new StringBuilder();
        StringBuilder vals = new StringBuilder();
        MapSqlParameterSource p = new MapSqlParameterSource();
        for (Map.Entry<String, Object> e : rec.entrySet()) {
            if (cols.length() > 0) {
                cols.append(", ");
                vals.append(", ");
            }
            cols.append(e.getKey());
            vals.append(":").append(e.getKey());
            p.addValue(e.getKey(), e.getValue());
        }
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update("INSERT INTO invoices (" + cols + ") VALUES (" + vals + ")",
                p, keys, new String[]{"id"});
        Number id = keys.getKey();
        if (id == null) {
            throw new ApiException("写入发票失败", 500);
        }
        String person = str(rec.get("person"));
        if (!person.isBlank()) {
            addPerson(person);
        }
        return id.longValue();
    }

    /** 把原件复制到 发票存档/年/月/号码.ext，返回相对项目根的路径 */
    public String archive(Path tmpFile, String invoiceNo, String invoiceDate) {
        String ext = ".pdf";
        String name = tmpFile.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            ext = name.substring(dot).toLowerCase();
        }
        String year = null;
        String month = null;
        if (invoiceDate != null && invoiceDate.matches("^\\d{4}-\\d{2}.*")) {
            year = invoiceDate.substring(0, 4);
            month = invoiceDate.substring(5, 7);
        }
        if (year == null) {
            LocalDate today = LocalDate.now();
            year = String.format("%04d", today.getYear());
            month = String.format("%02d", today.getMonthValue());
        }
        Path folder = props.archiveDir().resolve(year).resolve(month);
        try {
            Files.createDirectories(folder);
            Path target = folder.resolve(invoiceNo + ext);
            int n = 2;
            while (Files.exists(target)) {
                if (Files.size(target) == Files.size(tmpFile)) {
                    return relative(target);
                }
                target = folder.resolve(invoiceNo + "-" + n + ext);
                n++;
            }
            Files.copy(tmpFile, target, StandardCopyOption.REPLACE_EXISTING);
            return relative(target);
        } catch (IOException e) {
            throw new ApiException("保存原件失败：" + e.getMessage(), 500);
        }
    }

    private String relative(Path target) {
        Path base = props.base();
        return base.relativize(target.toAbsolutePath().normalize()).toString()
                .replace("\\", "/");
    }

    public void addPerson(String name) {
        Integer n = plain.queryForObject("SELECT COUNT(*) FROM persons WHERE name = ?",
                Integer.class, name);
        if (n == null || n == 0) {
            plain.update("INSERT INTO persons (name, sort) VALUES (?, 0)", name);
        }
    }

    // ---------------------------------------------------------------- 通知 / 日志

    public void notifyAdmins(String kind, String title, String body, Long batchId) {
        List<Map<String, Object>> admins = jdbc.queryForList(
                "SELECT id FROM users WHERE role = 'admin' AND status = 1", Map.of());
        String now = LocalDateTime.now().format(TS);
        for (Map<String, Object> a : admins) {
            plain.update("INSERT INTO notifications (user_id, kind, title, body, batch_id,"
                            + " created_at) VALUES (?,?,?,?,?,?)",
                    a.get("id"), kind, title, body, batchId, now);
        }
    }

    public void log(long userId, String username, String action, String targetType,
                    Long targetId, String detail) {
        plain.update("INSERT INTO audit_logs (user_id, username, action, target_type,"
                        + " target_id, detail, created_at) VALUES (?,?,?,?,?,?,?)",
                userId, username, action, targetType, targetId, detail,
                LocalDateTime.now().format(TS));
    }

    public static String digits(Object v) {
        return String.valueOf(v == null ? "" : v).replaceAll("\\D", "");
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
