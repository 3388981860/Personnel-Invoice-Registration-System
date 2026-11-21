package com.xinyu.invoice.web;

import com.xinyu.invoice.auth.SessionService;
import com.xinyu.invoice.common.ApiException;
import com.xinyu.invoice.config.AppProps;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 查看发票原件（PDF/OFD/图片），浏览器里直接打开 */
@RestController
public class FileController extends BaseController {

    private final AppProps props;

    public FileController(AppProps props) {
        this.props = props;
    }

    @GetMapping("/api/file")
    public ResponseEntity<byte[]> file(@RequestParam String id, HttpServletRequest req)
            throws IOException {
        Map<String, Object> u = requireUser(req);
        long rid;
        try {
            rid = Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw new ApiException("缺少记录编号");
        }

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT owner_id, file_rel, file_name FROM invoices WHERE id = ?", rid);
        if (rows.isEmpty()) {
            throw new ApiException("找不到这张发票", 404);
        }
        Map<String, Object> rec = rows.get(0);
        Object rel = rec.get("file_rel");
        if (rel == null || String.valueOf(rel).isBlank()) {
            throw new ApiException("没有对应的发票原件", 404);
        }
        // 普通用户只能看自己的
        if (!SessionService.isAdmin(u)
                && ((Number) rec.getOrDefault("owner_id", 0)).longValue() != uid(u)) {
            throw new ApiException("这个原件不属于你", 403);
        }

        Path base = props.base().toAbsolutePath().normalize();
        Path file = props.base()
                .resolve(String.valueOf(rel).replace("/", java.io.File.separator))
                .toAbsolutePath().normalize();
        if (!file.startsWith(base)) {
            throw new ApiException("路径不合法");
        }
        if (!Files.isRegularFile(file)) {
            throw new ApiException("原件已不在原位置", 404);
        }

        String ext = "";
        int dot = file.getFileName().toString().lastIndexOf('.');
        if (dot >= 0) {
            ext = file.getFileName().toString().substring(dot).toLowerCase();
        }
        MediaType type = switch (ext) {
            case ".pdf" -> MediaType.APPLICATION_PDF;
            case ".ofd" -> MediaType.parseMediaType("application/ofd");
            case ".png" -> MediaType.IMAGE_PNG;
            case ".jpg", ".jpeg" -> MediaType.IMAGE_JPEG;
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };

        String name = String.valueOf(
                rec.get("file_name") != null ? rec.get("file_name") : file.getFileName());
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");

        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.noCache())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename*=UTF-8''" + encoded)
                .body(Files.readAllBytes(file));
    }
}
