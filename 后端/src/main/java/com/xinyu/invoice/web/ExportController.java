package com.xinyu.invoice.web;

import com.xinyu.invoice.auth.SessionService;
import com.xinyu.invoice.service.ExcelExportService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** 导出 Excel */
@RestController
public class ExportController extends BaseController {

    private final ExcelExportService exporter;

    public ExportController(ExcelExportService exporter) {
        this.exporter = exporter;
    }

    @GetMapping("/api/export")
    public ResponseEntity<byte[]> export(HttpServletRequest req) throws Exception {
        Map<String, Object> u = requireUser(req);
        // 普通用户只导出自己的
        Long owner = SessionService.isAdmin(u) ? null : uid(u);

        byte[] data = exporter.build(owner);
        String name = "发票台账_" + LocalDate.now()
                .format(DateTimeFormatter.BASIC_ISO_DATE) + ".xlsx";
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8)
                .replace("+", "%20");

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encoded)
                .body(data);
    }
}
