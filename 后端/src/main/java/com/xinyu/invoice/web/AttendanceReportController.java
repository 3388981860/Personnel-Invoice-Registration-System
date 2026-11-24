package com.xinyu.invoice.web;

import com.xinyu.invoice.service.AttendanceService;
import com.xinyu.invoice.service.WorkdayCalendarService;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 考勤报表导出（照钉钉「批量导出报表」的格式）。
 *
 * 两个表：月度汇总、打卡时间。
 */
@RestController
public class AttendanceReportController extends BaseController {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 日状态用到的顺序 */
    private static final String[] WEEK_CN = {"", "一", "二", "三", "四", "五", "六", "日"};

    private final WorkdayCalendarService calendar;
    private final AttendanceService attendanceSvc;
    private final com.xinyu.invoice.service.DingTalkService ding;

    public AttendanceReportController(WorkdayCalendarService calendar,
                                      AttendanceService attendanceSvc,
                                      com.xinyu.invoice.service.DingTalkService ding) {
        this.calendar = calendar;
        this.attendanceSvc = attendanceSvc;
        this.ding = ding;
    }

    @GetMapping("/api/attendance/export-report")
    public ResponseEntity<byte[]> exportReport(@RequestParam String month,
                                               HttpServletRequest req) throws Exception {
        requirePerm(req, "attendance");
        YearMonth ym;
        try {
            ym = YearMonth.parse(month);
        } catch (Exception e) {
            throw new com.xinyu.invoice.common.ApiException("月份格式不对，应为 2026-11");
        }
        LocalDate first = ym.atDay(1);
        LocalDate last = ym.atEndOfMonth();
        int dayCount = ym.lengthOfMonth();

        // 本月的工作日
        Set<String> workdays = new HashSet<>();
        for (LocalDate d = first; !d.isAfter(last); d = d.plusDays(1)) {
            if (calendar.isWorkday(d)) {
                workdays.add(d.toString());
            }
        }

        // 缓存：dt_userid + 日期 → 打卡记录
        Map<String, Map<String, Object>> daily = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT work_date, dt_userid, name, on_duty, off_duty, on_result, off_result,"
                        + " has_leave, has_fix FROM attendance_daily"
                        + " WHERE work_date >= ? AND work_date <= ?",
                first.toString(), last.toString())) {
            daily.put(str(r.get("dt_userid")) + "|" + str(r.get("work_date")), r);
        }

        // 人员：优先用钉钉通讯录全员（没注册的也在），拿不到再退回系统账号
        Set<String> exempt = attendanceSvc.exemptIds();
        List<Map<String, Object>> people = new ArrayList<>();
        try {
            Map<String, List<String>> deptMap = ding.deptMapByUser();
            for (Map<String, Object> m : ding.directory()) {
                String dtId = str(m.get("userid"));
                if (dtId.isEmpty() || exempt.contains(dtId)) {
                    continue;
                }
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("display_name", str(m.get("name")));
                one.put("dept_name", String.join("、",
                        deptMap.getOrDefault(dtId, new ArrayList<>())));
                one.put("dingtalk_userid", dtId);
                people.add(one);
            }
            people.sort((a, b) -> {
                int c = str(a.get("dept_name")).compareTo(str(b.get("dept_name")));
                return c != 0 ? c : str(a.get("display_name")).compareTo(str(b.get("display_name")));
            });
        } catch (Exception e) {
            for (Map<String, Object> u : jdbc.queryForList(
                    "SELECT id, username, display_name, dept_name, dingtalk_userid FROM users"
                            + " WHERE status = 1 ORDER BY COALESCE(dept_name,'zz'), display_name")) {
                String dtId = u.get("dingtalk_userid") == null
                        ? "" : String.valueOf(u.get("dingtalk_userid"));
                if (!dtId.isEmpty() && exempt.contains(dtId)) {
                    continue;
                }
                if (dtId.isEmpty() && !hasAnyRecord(daily, str(u.get("display_name")))) {
                    continue;
                }
                people.add(u);
            }
        }

        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {

            Styles st = new Styles(wb);
            buildSummarySheet(wb, st, ym, dayCount, workdays, daily, people);
            buildPunchSheet(wb, st, ym, dayCount, daily, people);

            wb.write(bos);

            String name = "考勤报表_" + ym + ".xlsx";
            String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
            h.set(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"report.xlsx\"; filename*=UTF-8''" + encoded);
            return new ResponseEntity<>(bos.toByteArray(), h,
                    org.springframework.http.HttpStatus.OK);
        }
    }

    // ---------------------------------------------------------------- 月度汇总

    private void buildSummarySheet(XSSFWorkbook wb, Styles st, YearMonth ym, int dayCount,
                                   Set<String> workdays,
                                   Map<String, Map<String, Object>> daily,
                                   List<Map<String, Object>> people) {
        Sheet sh = wb.createSheet("月度汇总");
        int lastCol = 25;                     // 只保留统计列，不再输出每日明细

        // 标题
        Row r0 = sh.createRow(0);
        cell(r0, 0, "月度汇总　统计日期：" + ym.atDay(1) + " 至 " + ym.atEndOfMonth(), st.title);
        sh.addMergedRegion(new CellRangeAddress(0, 0, 0, lastCol));
        r0.setHeightInPoints(26);

        Row r1 = sh.createRow(1);
        cell(r1, 0, "报表生成时间：" + LocalDateTime.now().format(TS), st.sub);
        sh.addMergedRegion(new CellRangeAddress(1, 1, 0, lastCol));

        // 表头（两行）
        Row h1 = sh.createRow(2);
        Row h2 = sh.createRow(3);
        h1.setHeightInPoints(30);
        h2.setHeightInPoints(22);
        String[] fixed = {"姓名", "考勤组", "部门", "法定工作日", "UserId", "出勤天数", "请假天数",
                "休息天数", "工作时长", "请假时长"};
        for (int i = 0; i < fixed.length; i++) {
            cell(h1, i, fixed[i], st.head);
            cell(h2, i, "", st.head);
            sh.addMergedRegion(new CellRangeAddress(2, 3, i, i));
        }
        // 加班天数（3 列）
        cell(h1, 10, "加班天数", st.head);
        sh.addMergedRegion(new CellRangeAddress(2, 2, 10, 12));
        cell(h2, 10, "工作日加班", st.head);
        cell(h2, 11, "休息日加班", st.head);
        cell(h2, 12, "节假日加班", st.head);

        String[] rest = {"迟到次数", "迟到时长", "严重迟到次数", "严重迟到时长", "旷工迟到次数",
                "早退次数", "早退时长", "上班缺卡次数", "下班缺卡次数", "旷工天数",
                "出差时长", "外出时长", "补卡次数"};
        for (int i = 0; i < rest.length; i++) {
            int col = 13 + i;
            cell(h1, col, rest[i], st.head);
            cell(h2, col, "", st.head);
            sh.addMergedRegion(new CellRangeAddress(2, 3, col, col));
        }

        int dayStart = 26;

        // 人员行
        int rowIdx = 4;
        int workdayCount = workdays.size();
        for (Map<String, Object> p : people) {
            String dtId = p.get("dingtalk_userid") == null
                    ? "" : String.valueOf(p.get("dingtalk_userid"));
            String name = str(p.get("display_name"));
            Row row = sh.createRow(rowIdx++);

            int attended = 0, late = 0, early = 0, noOn = 0, noOff = 0, absent = 0,
                    leave = 0, fix = 0, workMinutes = 0;

            cell(row, 0, name, st.body);
            cell(row, 1, "", st.body);
            cell(row, 2, str(p.get("dept_name")), st.body);
            cell(row, 3, workdayCount, st.bodyNum);
            cell(row, 4, dtId, st.body);

            for (int d = 1; d <= dayCount; d++) {
                LocalDate date = ym.atDay(d);
                Map<String, Object> rec = daily.get(dtId + "|" + date);
                if (rec == null && !dtId.isEmpty()) {
                    rec = byName(daily, name, date.toString());
                }
                boolean isWorkday = workdays.contains(date.toString());
                String on = rec == null ? "" : str(rec.get("on_duty"));
                String off = rec == null ? "" : str(rec.get("off_duty"));
                String onRes = rec == null ? "" : str(rec.get("on_result"));
                String offRes = rec == null ? "" : str(rec.get("off_result"));
                boolean hasLeave = rec != null && "1".equals(str(rec.get("has_leave")));
                boolean hasFix = rec != null && "1".equals(str(rec.get("has_fix")));

                // 还没到的日子不判缺卡（不然整月报表里后半段全是红的）
                boolean future = date.isAfter(LocalDate.now());
                // 统计（未来的日子不算）
                if (future) {
                    continue;
                }
                boolean both = !on.isEmpty() && !off.isEmpty();
                if (isWorkday) {
                    if (hasLeave) {
                        leave++;
                    } else if (on.isEmpty() && off.isEmpty()) {
                        absent++;              // 一天都没打才算旷工
                    } else {
                        attended++;
                    }
                    if ("Late".equals(onRes) || "SeriousLate".equals(onRes)) {
                        late++;
                    }
                    if ("Early".equals(offRes)) {
                        early++;
                    }
                    if (on.isEmpty() && !hasLeave) {
                        noOn++;
                    }
                    if (off.isEmpty() && !hasLeave) {
                        noOff++;
                    }
                }
                if (hasFix) {
                    fix++;
                }
                if (both) {
                    workMinutes += minutes(on, off);
                }
            }

            cell(row, 5, attended, st.bodyNum);
            cell(row, 6, leave, st.bodyNum);
            cell(row, 7, dayCount - workdayCount, st.bodyNum);          // 休息天数
            cell(row, 8, workMinutes, st.bodyNum);                      // 工作时长（分钟）
            cell(row, 9, 0, st.bodyNum);                                // 请假时长
            cell(row, 10, 0, st.bodyNum);
            cell(row, 11, 0, st.bodyNum);
            cell(row, 12, 0, st.bodyNum);
            cell(row, 13, late, st.bodyNum);
            cell(row, 14, 0, st.bodyNum);
            cell(row, 15, 0, st.bodyNum);
            cell(row, 16, 0, st.bodyNum);
            cell(row, 17, 0, st.bodyNum);
            cell(row, 18, early, st.bodyNum);
            cell(row, 19, 0, st.bodyNum);
            cell(row, 20, noOn, st.bodyNum);
            cell(row, 21, noOff, st.bodyNum);
            cell(row, 22, absent, st.bodyNum);
            cell(row, 23, 0, st.bodyNum);
            cell(row, 24, 0, st.bodyNum);
            cell(row, 25, fix, st.bodyNum);
        }

        // 列宽 + 冻结
        for (int i = 0; i <= lastCol; i++) {
            sh.setColumnWidth(i, (i == 0 || i == 2) ? 13 * 256 : 9 * 256);
        }
        sh.setColumnWidth(1, 20 * 256);
        sh.setColumnWidth(4, 22 * 256);
        sh.createFreezePane(0, 4);
        for (int i = 0; i <= lastCol; i++) {
            sh.setDefaultColumnStyle(i, st.body);
        }
    }

    // ---------------------------------------------------------------- 打卡时间

    private void buildPunchSheet(XSSFWorkbook wb, Styles st, YearMonth ym, int dayCount,
                                 Map<String, Map<String, Object>> daily,
                                 List<Map<String, Object>> people) {
        Sheet sh = wb.createSheet("打卡时间");
        int lastCol = 6 + dayCount;

        Row r0 = sh.createRow(0);
        cell(r0, 0, "打卡时间　统计日期：" + ym.atDay(1) + " 至 " + ym.atEndOfMonth(), st.title);
        sh.addMergedRegion(new CellRangeAddress(0, 0, 0, lastCol));
        r0.setHeightInPoints(26);

        Row r1 = sh.createRow(1);
        cell(r1, 0, "报表生成时间：" + LocalDateTime.now().format(TS), st.sub);
        sh.addMergedRegion(new CellRangeAddress(1, 1, 0, lastCol));

        Row h1 = sh.createRow(2);
        Row h2 = sh.createRow(3);
        h1.setHeightInPoints(30);
        h2.setHeightInPoints(22);
        String[] fixed = {"姓名", "考勤组", "部门", "工号", "职位", "UserId"};
        for (int i = 0; i < fixed.length; i++) {
            cell(h1, i, fixed[i], st.head);
            cell(h2, i, "", st.head);
            sh.addMergedRegion(new CellRangeAddress(2, 3, i, i));
        }
        cell(h1, 6, "打卡时间", st.head);
        sh.addMergedRegion(new CellRangeAddress(2, 2, 6, 6 + dayCount - 1));
        for (int d = 1; d <= dayCount; d++) {
            LocalDate date = ym.atDay(d);
            int dow = date.getDayOfWeek().getValue();
            cell(h2, 6 + d - 1, dow >= 6 ? WEEK_CN[dow] : String.valueOf(d),
                    dow >= 6 ? st.headWeekend : st.head);
        }

        int rowIdx = 4;
        for (Map<String, Object> p : people) {
            String dtId = p.get("dingtalk_userid") == null
                    ? "" : String.valueOf(p.get("dingtalk_userid"));
            String name = str(p.get("display_name"));
            Row row = sh.createRow(rowIdx++);
            cell(row, 0, name, st.body);
            cell(row, 1, "", st.body);
            cell(row, 2, str(p.get("dept_name")), st.body);
            cell(row, 3, "", st.body);
            cell(row, 4, "", st.body);
            cell(row, 5, dtId, st.body);

            for (int d = 1; d <= dayCount; d++) {
                String date = ym.atDay(d).toString();
                Map<String, Object> rec = daily.get(dtId + "|" + date);
                if (rec == null && !dtId.isEmpty()) {
                    rec = byName(daily, name, date);
                }
                String text = "";
                if (rec != null) {
                    String on = hhmm(str(rec.get("on_duty")));
                    String off = hhmm(str(rec.get("off_duty")));
                    text = (on.isEmpty() && off.isEmpty()) ? "" : on + "\n" + off;
                }
                cell(row, 6 + d - 1, text, st.bodyWrap);
            }
        }

        for (int i = 0; i <= lastCol; i++) {
            sh.setColumnWidth(i, (i < 6) ? 12 * 256 : 8 * 256);
        }
        sh.setColumnWidth(2, 18 * 256);
        sh.setColumnWidth(5, 22 * 256);
        sh.createFreezePane(6, 4);
    }

    // ---------------------------------------------------------------- 工具

    /** 当天的考勤结果文字 */
    private String dayStatus(boolean isWorkday, String on, String off,
                             String onRes, String offRes, boolean hasLeave) {
        boolean punched = !on.isEmpty() || !off.isEmpty();
        if (!isWorkday) {
            return punched ? "休息并打卡" : "休息";
        }
        if (hasLeave && !punched) {
            return "请假";
        }
        List<String> parts = new ArrayList<>();
        if ("Late".equals(onRes)) {
            parts.add("迟到");
        } else if ("SeriousLate".equals(onRes)) {
            parts.add("严重迟到");
        }
        if ("Early".equals(offRes)) {
            parts.add("早退");
        }
        if (on.isEmpty()) {
            parts.add("上班缺卡");
        }
        if (off.isEmpty()) {
            parts.add("下班缺卡");
        }
        if (parts.isEmpty()) {
            return hasLeave ? "正常（请假）" : "正常";
        }
        if (parts.size() == 2 && on.isEmpty() && off.isEmpty()) {
            return "缺卡";
        }
        return String.join("/", parts);
    }

    private static boolean hasAnyRecord(Map<String, Map<String, Object>> daily, String name) {
        for (Map<String, Object> r : daily.values()) {
            if (name.equals(str(r.get("name")))) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> byName(Map<String, Map<String, Object>> daily,
                                              String name, String date) {
        for (Map.Entry<String, Map<String, Object>> e : daily.entrySet()) {
            if (!e.getKey().endsWith("|" + date)) {
                continue;
            }
            if (name.equals(str(e.getValue().get("name")))) {
                return e.getValue();
            }
        }
        return null;
    }

    private static int minutes(String on, String off) {
        try {
            LocalDateTime a = LocalDateTime.parse(on.replace(' ', 'T'));
            LocalDateTime b = LocalDateTime.parse(off.replace(' ', 'T'));
            long m = Duration.between(a, b).toMinutes();
            return (int) Math.max(0, m);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String hhmm(String s) {
        if (s == null || s.length() < 16) {
            return "";
        }
        return s.substring(11, 16);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static void cell(Row row, int col, String v, CellStyle cs) {
        Cell c = row.createCell(col);
        c.setCellValue(v == null ? "" : v);
        c.setCellStyle(cs);
    }

    private static void cell(Row row, int col, int v, CellStyle cs) {
        Cell c = row.createCell(col);
        c.setCellValue(v);
        c.setCellStyle(cs);
    }

    /** 常用样式，避免每个单元格都建一遍 */
    private static final class Styles {
        final CellStyle title;
        final CellStyle sub;
        final CellStyle head;
        final CellStyle headWeekend;
        final CellStyle body;
        final CellStyle bodyNum;
        final CellStyle bodyWrap;
        final CellStyle bodyRest;
        final CellStyle bodyBad;

        Styles(XSSFWorkbook wb) {
            title = style(wb, true, 14, HorizontalAlignment.CENTER, false, null);
            sub = style(wb, false, 9, HorizontalAlignment.LEFT, false, null);
            head = style(wb, true, 10, HorizontalAlignment.CENTER, true, null);
            headWeekend = style(wb, true, 10, HorizontalAlignment.CENTER, true,
                    IndexedColors.LEMON_CHIFFON);
            body = style(wb, false, 10, HorizontalAlignment.CENTER, true, null);
            bodyNum = style(wb, false, 10, HorizontalAlignment.CENTER, true, null);
            bodyWrap = style(wb, false, 9, HorizontalAlignment.CENTER, true, null);
            bodyWrap.setWrapText(true);
            bodyRest = style(wb, false, 10, HorizontalAlignment.CENTER, true,
                    IndexedColors.GREY_25_PERCENT);
            bodyBad = style(wb, false, 10, HorizontalAlignment.CENTER, true,
                    IndexedColors.ROSE);
        }

        private static CellStyle style(XSSFWorkbook wb, boolean bold, int size,
                                       HorizontalAlignment align, boolean border,
                                       IndexedColors fill) {
            CellStyle cs = wb.createCellStyle();
            Font f = wb.createFont();
            f.setBold(bold);
            f.setFontHeightInPoints((short) size);
            f.setFontName("微软雅黑");
            cs.setFont(f);
            cs.setAlignment(align);
            cs.setVerticalAlignment(VerticalAlignment.CENTER);
            if (border) {
                cs.setBorderTop(BorderStyle.THIN);
                cs.setBorderBottom(BorderStyle.THIN);
                cs.setBorderLeft(BorderStyle.THIN);
                cs.setBorderRight(BorderStyle.THIN);
            }
            if (fill != null) {
                cs.setFillForegroundColor(fill.getIndex());
                cs.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            }
            return cs;
        }
    }
}
