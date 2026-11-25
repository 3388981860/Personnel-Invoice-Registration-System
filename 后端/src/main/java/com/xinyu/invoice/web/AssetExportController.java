package com.xinyu.invoice.web;

import com.xinyu.invoice.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 固定资产导出：列顺序跟原来的 Excel 一致 */
@RestController
public class AssetExportController extends BaseController {

    private static final String[] HEAD = {
            "资产编号", "名称", "类型", "类别", "状态", "财务状态", "使用人", "部门", "位置",
            "采购日期", "原值", "可使用年限", "月折旧额", "累计折旧", "净值",
            "计算机名", "username", "操作系统", "osInstallDate", "型号", "BIOS日期", "序列号",
            "CPU", "主板", "内存", "硬盘", "硬盘序列号", "显卡", "显示器", "网卡", "IP", "MAC",
            "printer"
    };

    @GetMapping("/api/assets/export")
    public byte[] export(@RequestParam(required = false) String status,
                         @RequestParam(required = false) String category,
                         @RequestParam(required = false) String dept,
                         @RequestParam(required = false) String q,
                         HttpServletRequest req) throws IOException {
        requirePerm(req, "assets");
        StringBuilder sql = new StringBuilder("SELECT * FROM assets WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            args.add(status.strip());
        }
        if (category != null && !category.isBlank()) {
            sql.append(" AND category = ?");
            args.add(category.strip());
        }
        if (dept != null && !dept.isBlank()) {
            sql.append(" AND dept = ?");
            args.add(dept.strip());
        }
        if (q != null && !q.isBlank()) {
            sql.append(" AND (code LIKE ? OR name LIKE ? OR user_name LIKE ?"
                    + " OR computer_name LIKE ? OR ip LIKE ? OR serial_no LIKE ?)");
            String like = "%" + q.strip() + "%";
            for (int i = 0; i < 6; i++) {
                args.add(like);
            }
        }
        sql.append(" ORDER BY code, id");
        List<Map<String, Object>> rows = args.isEmpty()
                ? jdbc.queryForList(sql.toString())
                : jdbc.queryForList(sql.toString(), args.toArray());

        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("资产");
            CellStyle headStyle = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            headStyle.setFont(bold);

            Row h = sh.createRow(0);
            for (int i = 0; i < HEAD.length; i++) {
                Cell c = h.createCell(i);
                c.setCellValue(HEAD[i]);
                c.setCellStyle(headStyle);
                sh.setColumnWidth(i, (i < 6 ? 14 : 20) * 256);
            }

            int r = 1;
            for (Map<String, Object> a : rows) {
                double price = num(a.get("price"));
                double years = num(a.get("life_years"));
                double monthly = years > 0 ? price / (years * 12) : 0;
                double acc = 0;
                String buy = a.get("buy_date") == null ? "" : String.valueOf(a.get("buy_date"));
                if (monthly > 0 && !buy.isBlank()) {
                    try {
                        LocalDate d = LocalDate.parse(buy.length() >= 10 ? buy.substring(0, 10) : buy);
                        long m = ChronoUnit.MONTHS.between(d, LocalDate.now());
                        if (m > 0) {
                            acc = Math.min(price, monthly * m);
                        }
                    } catch (Exception ignored) {
                        // 忽略坏日期
                    }
                }
                Row row = sh.createRow(r++);
                int c = 0;
                put(row, c++, str(a.get("code")));
                put(row, c++, str(a.get("name")));
                put(row, c++, str(a.get("type")));
                put(row, c++, str(a.get("category")));
                put(row, c++, str(a.get("status")));
                put(row, c++, str(a.get("finance_status")));
                put(row, c++, str(a.get("user_name")));
                put(row, c++, str(a.get("dept")));
                put(row, c++, str(a.get("location")));
                put(row, c++, str(a.get("buy_date")));
                putNum(row, c++, price);
                putNum(row, c++, years);
                putNum(row, c++, round(monthly));
                putNum(row, c++, round(acc));
                putNum(row, c++, round(Math.max(0, price - acc)));
                put(row, c++, str(a.get("computer_name")));
                put(row, c++, str(a.get("os_user")));
                put(row, c++, str(a.get("os_version")));
                put(row, c++, str(a.get("os_install")));
                put(row, c++, str(a.get("model")));
                put(row, c++, str(a.get("bios_date")));
                put(row, c++, str(a.get("serial_no")));
                put(row, c++, str(a.get("cpu")));
                put(row, c++, str(a.get("mainboard")));
                put(row, c++, str(a.get("memory")));
                put(row, c++, str(a.get("disk")));
                put(row, c++, str(a.get("disk_sn")));
                put(row, c++, str(a.get("gpu")));
                put(row, c++, str(a.get("monitor")));
                put(row, c++, str(a.get("nic")));
                put(row, c++, str(a.get("ip")));
                put(row, c++, str(a.get("mac")));
                put(row, c, str(a.get("printer")));
            }
            sh.createFreezePane(0, 1);
            if (rows.size() > 0) {
                sh.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(
                        0, rows.size(), 0, HEAD.length - 1));
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static void put(Row row, int idx, String v) {
        row.createCell(idx).setCellValue(v == null ? "" : v);
    }

    private static void putNum(Row row, int idx, double v) {
        row.createCell(idx).setCellValue(v);
    }

    private static double num(Object o) {
        if (o == null) {
            return 0;
        }
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
