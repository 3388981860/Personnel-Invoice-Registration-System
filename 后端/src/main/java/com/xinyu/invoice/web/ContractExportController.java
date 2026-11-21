package com.xinyu.invoice.web;

import com.xinyu.invoice.service.ContractExportService;
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

/** 导出合同台账 */
@RestController
public class ContractExportController extends BaseController {

    private final ContractExportService exporter;

    public ContractExportController(ContractExportService exporter) {
        this.exporter = exporter;
    }

    @GetMapping("/api/contracts/export")
    public ResponseEntity<byte[]> export(HttpServletRequest req) throws Exception {
        requirePerm(req, "export");
        byte[] data = exporter.build();
        String name = "合同台账_" + LocalDate.now()
                .format(DateTimeFormatter.BASIC_ISO_DATE) + ".xlsx";
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encoded)
                .body(data);
    }
}
