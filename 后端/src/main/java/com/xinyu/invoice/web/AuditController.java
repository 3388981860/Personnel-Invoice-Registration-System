package com.xinyu.invoice.web;

import com.xinyu.invoice.common.ApiException;
import com.xinyu.invoice.config.AppProps;
import com.xinyu.invoice.repo.StatsRepo;
import com.xinyu.invoice.service.AuditProxyService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查验：权限和截图在本服务处理；真正的浏览器 + 验证码 OCR 交给 Python 小服务。
 */
@RestController
public class AuditController extends BaseController {

    private final AuditProxyService audit;
    private final StatsRepo statsRepo;
    private final AppProps props;

    public AuditController(AuditProxyService audit, StatsRepo statsRepo, AppProps props) {
        this.audit = audit;
        this.statsRepo = statsRepo;
        this.props = props;
    }

    @GetMapping("/api/audit/status")
    public Map<String, Object> status(HttpServletRequest req) {
        requireAdmin(req);
        Map<String, Object> out = new LinkedHashMap<>(audit.status());
        out.put("stats", statsRepo.stats(null));
        return out;
    }

    @PostMapping("/api/audit/start")
    public Map<String, Object> start(@RequestBody Map<String, Object> body,
                                     HttpServletRequest req) {
        requireAdmin(req);
        List<Long> ids = new ArrayList<>();
        Object raw = body.get("ids");
        if (raw instanceof List<?> list) {
            for (Object x : list) {
                try {
                    ids.add(Long.parseLong(String.valueOf(x)));
                } catch (NumberFormatException ignored) {
                    // 跳过非法 id
                }
            }
        }
        if (ids.isEmpty()) {
            throw new ApiException("没有选中要查验的发票");
        }
        Map<String, Object> out = new LinkedHashMap<>(audit.start(ids));
        out.put("stats", statsRepo.stats(null));
        return out;
    }

    @PostMapping("/api/audit/stop")
    public Map<String, Object> stop(HttpServletRequest req) {
        requireAdmin(req);
        Map<String, Object> out = new LinkedHashMap<>(audit.stop());
        out.put("stats", statsRepo.stats(null));
        return out;
    }

    /** 查验结果截图：只给管理员，路径必须在 数据/查验截图 下 */
    @GetMapping("/api/audit/shot")
    public ResponseEntity<byte[]> shot(@RequestParam String id, HttpServletRequest req)
            throws Exception {
        requireAdmin(req);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT audit_shot FROM invoices WHERE id = ?", Long.parseLong(id));
        Object rel = rows.isEmpty() ? null : rows.get(0).get("audit_shot");
        if (rel == null || String.valueOf(rel).isBlank()) {
            throw new ApiException("这张发票还没有查验截图", 404);
        }
        Path base = props.dataDir().resolve("查验截图").toAbsolutePath().normalize();
        Path file = props.base()
                .resolve(String.valueOf(rel).replace("/", java.io.File.separator))
                .toAbsolutePath().normalize();
        if (!file.startsWith(base)) {
            throw new ApiException("路径不合法");
        }
        if (!Files.isRegularFile(file)) {
            throw new ApiException("截图文件不在了", 404);
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noCache())
                .body(Files.readAllBytes(file));
    }
}
