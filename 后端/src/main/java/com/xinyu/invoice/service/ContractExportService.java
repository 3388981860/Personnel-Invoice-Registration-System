package com.xinyu.invoice.service;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 合同台账导出：三个工作表
 *   项目台账  —— 一个项目一行，看整体
 *   合同明细  —— 一份合同一行，含开票/回款进度
 *   资金流水  —— 每一笔开票、回款、付款
 */
@Service
public class ContractExportService {

    private final JdbcTemplate jdbc;

    public ContractExportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public byte[] build() throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            CellStyle head = headStyle(wb);
            CellStyle money = moneyStyle(wb);

            projects(wb, head, money);
            contracts(wb, head, money);
            billings(wb, head, money);

            wb.write(out);
            return out.toByteArray();
        }
    }

    private void projects(XSSFWorkbook wb, CellStyle head, CellStyle money) {
        Sheet ws = wb.createSheet("项目台账");
        String[] titles = {"编号", "项目名称", "甲方单位", "开始", "结束",
                "收入合同额", "已开票", "已回款", "未回款", "外协成本", "外协已付", "备注"};
        int[] widths = {10, 34, 16, 12, 12, 14, 14, 14, 14, 14, 14, 30};
        fillHead(ws, titles, head);

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.code, p.name, p.unit, p.start_date, p.end_date, p.note,
                  COALESCE((SELECT SUM(c.amount) FROM contracts c
                            WHERE c.project_id=p.id AND c.kind='income'),0) AS income,
                  COALESCE((SELECT SUM(c.amount) FROM contracts c
                            WHERE c.project_id=p.id AND c.kind='cost'),0) AS cost,
                  COALESCE((SELECT SUM(b.amount) FROM contract_billings b
                            JOIN contracts c ON c.id=b.contract_id
                            WHERE c.project_id=p.id AND c.kind='income'
                              AND b.kind='invoice'),0) AS invoiced,
                  COALESCE((SELECT SUM(b.amount) FROM contract_billings b
                            JOIN contracts c ON c.id=b.contract_id
                            WHERE c.project_id=p.id AND c.kind='income'
                              AND b.kind='payment'),0) AS received,
                  COALESCE((SELECT SUM(b.amount) FROM contract_billings b
                            JOIN contracts c ON c.id=b.contract_id
                            WHERE c.project_id=p.id AND c.kind='cost'
                              AND b.kind='payment'),0) AS cost_paid
                FROM projects p ORDER BY p.unit, p.id
                """);

        int r = 1;
        for (Map<String, Object> x : rows) {
            Row row = ws.createRow(r++);
            row.createCell(0).setCellValue(str(x.get("code")));
            row.createCell(1).setCellValue(str(x.get("name")));
            row.createCell(2).setCellValue(str(x.get("unit")));
            row.createCell(3).setCellValue(str(x.get("start_date")));
            row.createCell(4).setCellValue(str(x.get("end_date")));
            double income = num(x.get("income"));
            double received = num(x.get("received"));
            moneyCell(row, 5, income, money);
            moneyCell(row, 6, num(x.get("invoiced")), money);
            moneyCell(row, 7, received, money);
            moneyCell(row, 8, Math.max(0, income - received), money);
            moneyCell(row, 9, num(x.get("cost")), money);
            moneyCell(row, 10, num(x.get("cost_paid")), money);
            row.createCell(11).setCellValue(str(x.get("note")));
        }
        finish(ws, titles.length, widths, rows.size());
    }

    private void contracts(XSSFWorkbook wb, CellStyle head, CellStyle money) {
        Sheet ws = wb.createSheet("合同明细");
        String[] titles = {"项目编号", "项目名称", "甲方单位", "类型", "付款方", "收款方",
                "合同编号", "合同金额", "签订状态", "已开票/已付", "已回款", "进度", "备注"};
        int[] widths = {10, 30, 16, 8, 20, 24, 18, 14, 12, 14, 14, 10, 30};
        fillHead(ws, titles, head);

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.code, p.name AS project, p.unit, c.kind, c.party_a, c.party_b,
                       c.contract_no, c.amount, c.signed, c.note,
                  COALESCE((SELECT SUM(b.amount) FROM contract_billings b
                            WHERE b.contract_id=c.id AND b.kind='invoice'),0) AS invoiced,
                  COALESCE((SELECT SUM(b.amount) FROM contract_billings b
                            WHERE b.contract_id=c.id AND b.kind='payment'),0) AS paid
                FROM contracts c JOIN projects p ON p.id=c.project_id
                ORDER BY p.unit, p.id,
                  CASE c.kind WHEN 'reference' THEN 0 WHEN 'income' THEN 1 ELSE 2 END, c.id
                """);

        int r = 1;
        for (Map<String, Object> x : rows) {
            Row row = ws.createRow(r++);
            row.createCell(0).setCellValue(str(x.get("code")));
            row.createCell(1).setCellValue(str(x.get("project")));
            row.createCell(2).setCellValue(str(x.get("unit")));
            row.createCell(3).setCellValue(kindText(str(x.get("kind"))));
            row.createCell(4).setCellValue(str(x.get("party_a")));
            row.createCell(5).setCellValue(str(x.get("party_b")));
            row.createCell(6).setCellValue(str(x.get("contract_no")));
            moneyCell(row, 7, num(x.get("amount")), money);
            row.createCell(8).setCellValue(str(x.get("signed")));
            moneyCell(row, 9, num(x.get("invoiced")), money);
            moneyCell(row, 10, num(x.get("paid")), money);
            double amt = num(x.get("amount"));
            row.createCell(11).setCellValue(amt > 0
                    ? Math.round(num(x.get("paid")) * 100 / amt) + "%" : "");
            row.createCell(12).setCellValue(str(x.get("note")));
        }
        finish(ws, titles.length, widths, rows.size());
    }

    private void billings(XSSFWorkbook wb, CellStyle head, CellStyle money) {
        Sheet ws = wb.createSheet("资金流水");
        String[] titles = {"项目", "合同编号", "付款方", "收款方", "类型",
                "阶段", "金额", "发票号", "日期", "备注"};
        int[] widths = {30, 18, 20, 24, 10, 12, 14, 24, 12, 30};
        fillHead(ws, titles, head);

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT p.name AS project, c.contract_no, c.party_a, c.party_b, c.kind AS ckind,
                       b.kind, b.stage, b.amount, b.no, b.happened_at, b.note
                FROM contract_billings b
                JOIN contracts c ON c.id=b.contract_id
                JOIN projects p ON p.id=c.project_id
                ORDER BY b.happened_at DESC, b.id DESC
                """);

        int r = 1;
        double sum = 0;
        for (Map<String, Object> x : rows) {
            Row row = ws.createRow(r++);
            row.createCell(0).setCellValue(str(x.get("project")));
            row.createCell(1).setCellValue(str(x.get("contract_no")));
            row.createCell(2).setCellValue(str(x.get("party_a")));
            row.createCell(3).setCellValue(str(x.get("party_b")));
            String ck = str(x.get("ckind"));
            String bk = str(x.get("kind"));
            row.createCell(4).setCellValue("invoice".equals(bk) ? "开票"
                    : ("cost".equals(ck) ? "付款" : "回款"));
            row.createCell(5).setCellValue(str(x.get("stage")));
            moneyCell(row, 6, num(x.get("amount")), money);
            row.createCell(7).setCellValue(str(x.get("no")));
            row.createCell(8).setCellValue(str(x.get("happened_at")));
            row.createCell(9).setCellValue(str(x.get("note")));
            sum += num(x.get("amount"));
        }
        finish(ws, titles.length, widths, rows.size());
        if (!rows.isEmpty()) {
            Row tail = ws.createRow(rows.size() + 2);
            tail.createCell(5).setCellValue("合计");
            moneyCell(tail, 6, sum, money);
        }
    }

    // ---------------------------------------------------------------- 工具

    private CellStyle headStyle(XSSFWorkbook wb) {
        CellStyle s = wb.createCellStyle();
        Font f = wb.createFont();
        f.setBold(true);
        f.setColor(IndexedColors.WHITE.getIndex());
        s.setFont(f);
        s.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.setAlignment(HorizontalAlignment.CENTER);
        s.setVerticalAlignment(VerticalAlignment.CENTER);
        return s;
    }

    private CellStyle moneyStyle(XSSFWorkbook wb) {
        CellStyle s = wb.createCellStyle();
        s.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
        return s;
    }

    private void fillHead(Sheet ws, String[] titles, CellStyle head) {
        Row row = ws.createRow(0);
        row.setHeightInPoints(22);
        for (int i = 0; i < titles.length; i++) {
            Cell c = row.createCell(i);
            c.setCellValue(titles[i]);
            c.setCellStyle(head);
        }
    }

    private void moneyCell(Row row, int col, double v, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(v);
        c.setCellStyle(style);
    }

    private void finish(Sheet ws, int cols, int[] widths, int rowCount) {
        for (int i = 0; i < cols && i < widths.length; i++) {
            ws.setColumnWidth(i, widths[i] * 256);
        }
        ws.createFreezePane(0, 1);
        if (rowCount > 0) {
            ws.setAutoFilter(new CellRangeAddress(0, rowCount, cols - 1, cols - 1));
        }
    }

    private static String kindText(String k) {
        return switch (k) {
            case "income" -> "收入";
            case "cost" -> "外协";
            case "reference" -> "关联";
            default -> k;
        };
    }

    private static double num(Object o) {
        return o == null ? 0 : ((Number) o).doubleValue();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
