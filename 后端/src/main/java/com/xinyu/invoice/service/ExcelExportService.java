package com.xinyu.invoice.service;

import com.xinyu.invoice.repo.InvoiceRepo;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 导出 Excel：表头、配色、列宽、冻结首行、自动筛选都跟旧版一致，
 * 财务拿到手的文件格式不变。
 */
@Service
public class ExcelExportService {

    private record Col(String key, String title) {}

    private static final List<Col> COLS = List.of(
            new Col("batch_no", "报销单号"),
            new Col("invoice_no", "发票号码"),
            new Col("invoice_date", "开票日期"),
            new Col("amount", "金额(价税合计)"),
            new Col("amount_net", "不含税金额"),
            new Col("tax", "税额"),
            new Col("seller", "销售方"),
            new Col("buyer", "购买方"),
            new Col("category", "项目"),
            new Col("person", "报销人"),
            new Col("payee", "收款人"),
            new Col("reimburse_date", "报销日期"),
            new Col("status", "报销状态"),
            new Col("paid_at", "报销确认时间"),
            new Col("audit_status", "查验状态"),
            new Col("audit_at", "查验时间"),
            new Col("audit_msg", "查验说明"),
            new Col("audit_warn", "查验提示"),
            new Col("note", "备注"),
            new Col("created_at", "登记时间")
    );

    private static final int[] WIDTHS = {18, 24, 12, 15, 13, 10, 32, 26, 22, 10,
            10, 12, 10, 20, 12, 20, 30, 40, 26, 20};

    private static final String[] AUDIT_TEXT = {"未查验", "查验通过", "查验不通过", "无需查验"};

    private final InvoiceRepo invoices;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public ExcelExportService(InvoiceRepo invoices,
                              org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.invoices = invoices;
        this.jdbc = jdbc;
    }

    /** 生成 xlsx 字节，ownerId 非空时只导出这个人的 */
    public byte[] build(Long ownerId) throws IOException {
        List<Map<String, Object>> rows = invoices.list(null, null, null, null, null, null,
                null, ownerId, "created_at", false);

        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet ws = wb.createSheet("发票台账");

            CellStyle headStyle = wb.createCellStyle();
            Font headFont = wb.createFont();
            headFont.setBold(true);
            headFont.setColor(IndexedColors.WHITE.getIndex());
            headFont.setFontHeightInPoints((short) 11);
            headStyle.setFont(headFont);
            headStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headStyle.setAlignment(HorizontalAlignment.CENTER);
            headStyle.setVerticalAlignment(VerticalAlignment.CENTER);

            CellStyle money = wb.createCellStyle();
            money.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));

            Row head = ws.createRow(0);
            head.setHeightInPoints(22);
            for (int c = 0; c < COLS.size(); c++) {
                Cell cell = head.createCell(c);
                cell.setCellValue(COLS.get(c).title());
                cell.setCellStyle(headStyle);
            }

            int r = 1;
            for (Map<String, Object> rec : rows) {
                Row row = ws.createRow(r++);
                for (int c = 0; c < COLS.size(); c++) {
                    Col col = COLS.get(c);
                    Cell cell = row.createCell(c);
                    Object v = rec.get(col.key());
                    if ("status".equals(col.key())) {
                        cell.setCellValue(v != null && ((Number) v).intValue() == 1 ? "已报销" : "未报销");
                    } else if ("audit_status".equals(col.key())) {
                        int s = v == null ? 0 : ((Number) v).intValue();
                        cell.setCellValue(AUDIT_TEXT[Math.max(0, Math.min(3, s))]);
                    } else if (v instanceof Number n
                            && List.of("amount", "amount_net", "tax").contains(col.key())) {
                        cell.setCellValue(n.doubleValue());
                        cell.setCellStyle(money);
                    } else if (v != null) {
                        cell.setCellValue(String.valueOf(v));
                    }
                }
            }

            for (int c = 0; c < WIDTHS.length && c < COLS.size(); c++) {
                ws.setColumnWidth(c, WIDTHS[c] * 256);
            }
            ws.createFreezePane(0, 1);
            if (!rows.isEmpty()) {
                ws.setAutoFilter(new CellRangeAddress(0, rows.size(),
                        COLS.size() - 1, COLS.size() - 1));
            }

            buildPayeeSheet(wb, ownerId, headStyle, money);

            wb.write(out);
            return out.toByteArray();
        }
    }

    // ---------------------------------------------------------------- 收款明细

    /**
     * 第二个工作表：钱付给了谁。
     * 财务打款、对账看这张表，不用再回系统里一个个点开单据。
     */
    private void buildPayeeSheet(XSSFWorkbook wb, Long ownerId, CellStyle headStyle,
                                 CellStyle money) {
        String sql = "SELECT b.batch_no, b.review_status, p.name, p.amount, p.note, "
                + "(SELECT SUM(i.amount) FROM invoices i WHERE i.batch_id = b.id) AS batch_amount, "
                + "(SELECT COUNT(*) FROM invoices i WHERE i.batch_id = b.id) AS batch_count "
                + "FROM batch_payees p JOIN batches b ON b.id = p.batch_id "
                + (ownerId != null ? "WHERE b.owner_id = ? " : "")
                + "ORDER BY b.id DESC, p.sort, p.id";
        List<Map<String, Object>> rows = ownerId != null
                ? jdbc.queryForList(sql, ownerId)
                : jdbc.queryForList(sql);

        Sheet ws = wb.createSheet("收款明细");
        String[] titles = {"报销单号", "发票张数", "单合计", "收款人", "该笔金额", "备注", "审核状态"};
        int[] widths = {20, 10, 14, 18, 14, 26, 10};

        Row head = ws.createRow(0);
        head.setHeightInPoints(22);
        for (int c = 0; c < titles.length; c++) {
            Cell cell = head.createCell(c);
            cell.setCellValue(titles[c]);
            cell.setCellStyle(headStyle);
        }

        int r = 1;
        double sum = 0;
        for (Map<String, Object> row : rows) {
            Row line = ws.createRow(r++);
            line.createCell(0).setCellValue(str(row.get("batch_no")));
            Object cnt = row.get("batch_count");
            line.createCell(1).setCellValue(cnt == null ? 0 : ((Number) cnt).doubleValue());
            Cell total = line.createCell(2);
            total.setCellValue(num(row.get("batch_amount")));
            total.setCellStyle(money);
            line.createCell(3).setCellValue(str(row.get("name")));
            Cell amt = line.createCell(4);
            double a = num(row.get("amount"));
            amt.setCellValue(a);
            amt.setCellStyle(money);
            line.createCell(5).setCellValue(str(row.get("note")));
            line.createCell(6).setCellValue(reviewText(str(row.get("review_status"))));
            sum += a;
        }

        if (!rows.isEmpty()) {
            Row tail = ws.createRow(r + 1);
            tail.createCell(3).setCellValue("合计");
            Cell cell = tail.createCell(4);
            cell.setCellValue(Math.round(sum * 100d) / 100d);
            cell.setCellStyle(money);
            ws.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(
                    0, rows.size(), titles.length - 1, titles.length - 1));
        }

        for (int c = 0; c < widths.length; c++) {
            ws.setColumnWidth(c, widths[c] * 256);
        }
        ws.createFreezePane(0, 1);
    }

    private static String reviewText(String s) {
        return switch (s == null ? "" : s) {
            case "submitted" -> "待审核";
            case "returned" -> "已退回";
            case "approved" -> "已通过";
            default -> s == null ? "" : s;
        };
    }

    private static double num(Object o) {
        return o == null ? 0 : ((Number) o).doubleValue();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
