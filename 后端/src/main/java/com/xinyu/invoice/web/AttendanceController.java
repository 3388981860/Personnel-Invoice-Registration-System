package com.xinyu.invoice.web;

import com.xinyu.invoice.common.ApiException;
import com.xinyu.invoice.service.AttendanceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 考勤看板（只看，不改钉钉数据） */
@RestController
public class AttendanceController extends BaseController {

    private final AttendanceService attendance;

    private final com.xinyu.invoice.service.AttendanceMonthService monthService;
    private final com.xinyu.invoice.service.WorkdayCalendarService calendar;
    private final com.xinyu.invoice.service.AttendanceReminderService reminder;
    private final com.xinyu.invoice.service.DingTalkService ding;

    public AttendanceController(AttendanceService attendance,
                                com.xinyu.invoice.service.AttendanceMonthService monthService,
                                com.xinyu.invoice.service.WorkdayCalendarService calendar,
                                com.xinyu.invoice.service.AttendanceReminderService reminder,
                                com.xinyu.invoice.service.DingTalkService ding) {
        this.attendance = attendance;
        this.monthService = monthService;
        this.reminder = reminder;
        this.calendar = calendar;
        this.ding = ding;
    }

    /** 今日看板 */
    @GetMapping("/api/attendance/today")
    public Map<String, Object> today(@RequestParam(required = false, defaultValue = "false") boolean force,
                                     HttpServletRequest req) {
        requirePerm(req, "attendance");
        try {
            Map<String, Object> out = new LinkedHashMap<>(attendance.today(force));
            out.put("ok", true);
            return out;
        } catch (Exception e) {
            throw new ApiException("读取考勤失败：" + e.getMessage(), 500);
        }
    }

    /** 免考勤人员列表 + 通讯录全员（前端做勾选） */
    @GetMapping("/api/attendance/exempt")
    public Map<String, Object> exempt(HttpServletRequest req) {
        requirePerm(req, "attendance");
        java.util.Set<String> ids = attendance.exemptIds();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("exempt", ids);
        java.util.List<Map<String, Object>> members = new java.util.ArrayList<>();
        try {
            for (Map<String, Object> m : ding.directory()) {
                Map<String, Object> one = new LinkedHashMap<>();
                one.put("userid", m.get("userid"));
                one.put("name", m.get("name"));
                one.put("mobile", m.get("mobile"));
                one.put("exempt", ids.contains(String.valueOf(m.get("userid"))));
                members.add(one);
            }
        } catch (Exception e) {
            out.put("error", e.getMessage());
        }
        out.put("members", members);
        return out;
    }

    /** 设置某个人是否免考勤 */
    @PostMapping("/api/attendance/exempt/set")
    public Map<String, Object> setExempt(@org.springframework.web.bind.annotation.RequestBody
                                         Map<String, Object> body, HttpServletRequest req) {
        Map<String, Object> u = requirePerm(req, "attendance");
        String dtId = body.get("userid") == null ? "" : String.valueOf(body.get("userid"));
        String name = body.get("name") == null ? "" : String.valueOf(body.get("name"));
        boolean on = Boolean.parseBoolean(String.valueOf(body.get("exempt")));
        if (dtId.isBlank()) {
            throw new ApiException("缺少 userid");
        }
        if (on) {
            jdbc.update("INSERT OR REPLACE INTO attendance_exempt"
                            + " (dt_userid, name, note, created_at) VALUES (?,?,?,?)",
                    dtId, name, "", java.time.LocalDateTime.now().format(
                            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        } else {
            jdbc.update("DELETE FROM attendance_exempt WHERE dt_userid = ?", dtId);
        }
        return Map.of("ok", true);
    }

    /** 某月的日历（标了节假日/调休的） */
    @GetMapping("/api/attendance/calendar")
    public Map<String, Object> calendar(@RequestParam(required = false) String month,
                                        HttpServletRequest req) {
        requirePerm(req, "attendance");
        java.time.YearMonth m;
        try {
            m = java.time.YearMonth.parse(month == null || month.isBlank()
                    ? java.time.YearMonth.now().toString() : month);
        } catch (Exception e) {
            m = java.time.YearMonth.now();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("month", m.toString());
        out.put("days", calendar.monthCalendar(m));
        return out;
    }

    /** 手动标某天：kind=holiday 休息 / workday 上班 / 空 取消标记 */
    @PostMapping("/api/attendance/calendar/set")
    public Map<String, Object> setDay(@org.springframework.web.bind.annotation.RequestBody
                                      Map<String, Object> body, HttpServletRequest req) {
        Map<String, Object> u = requirePerm(req, "attendance");
        String date = body.get("date") == null ? "" : String.valueOf(body.get("date"));
        String kind = body.get("kind") == null ? "" : String.valueOf(body.get("kind"));
        String name = body.get("name") == null ? "" : String.valueOf(body.get("name"));
        if (date.isBlank()) {
            throw new ApiException("缺少日期");
        }
        calendar.setDay(java.time.LocalDate.parse(date), kind, name);
        writer(u, "考勤日历", date + " → " + (kind.isBlank() ? "取消标记" : kind + " " + name));
        return Map.of("ok", true);
    }

    /** 从公开接口同步某年法定节假日 */
    @PostMapping("/api/attendance/calendar/sync")
    public Map<String, Object> syncCalendar(@org.springframework.web.bind.annotation.RequestBody
                                            Map<String, Object> body, HttpServletRequest req) {
        Map<String, Object> u = requirePerm(req, "attendance");
        int year = java.time.LocalDate.now().getYear();
        if (body.get("year") != null) {
            year = Integer.parseInt(String.valueOf(body.get("year")));
        }
        try {
            int n = calendar.syncYear(year);
            writer(u, "考勤日历", year + " 年节假日同步 " + n + " 天");
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("year", year);
            out.put("count", n);
            return out;
        } catch (Exception e) {
            throw new ApiException(e.getMessage(), 500);
        }
    }

    /** 手动跑一次「今日未打卡提醒」（不用等到 18:30） */
    @PostMapping("/api/attendance/remind")
    public Map<String, Object> remind(HttpServletRequest req) {
        requirePerm(req, "attendance");
        try {
            Map<String, Object> out = new LinkedHashMap<>(reminder.run());
            out.put("ok", true);
            return out;
        } catch (Exception e) {
            throw new ApiException("提醒失败：" + e.getMessage(), 500);
        }
    }

    private void writer(Map<String, Object> u, String target, String detail) {
        try {
            jdbc.update("INSERT INTO audit_logs (user_id, username, action, target_type,"
                            + " detail, created_at) VALUES (?,?,?,?,?,?)",
                    uid(u), String.valueOf(u.get("username")), "attendance_calendar",
                    "attendance", detail, java.time.LocalDateTime.now().format(
                            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        } catch (Exception ignored) {
            // 日志失败不影响
        }
    }

    /** 月度统计 */
    @GetMapping("/api/attendance/month")
    public Map<String, Object> month(@RequestParam(required = false) String month,
                                     HttpServletRequest req) {
        requirePerm(req, "attendance");
        try {
            Map<String, Object> out = new LinkedHashMap<>(monthService.summary(month));
            out.put("ok", true);
            return out;
        } catch (Exception e) {
            throw new ApiException("统计失败：" + e.getMessage(), 500);
        }
    }

    /** 启动某月同步（后台跑） */
    @PostMapping("/api/attendance/sync-month")
    public Map<String, Object> syncMonth(@org.springframework.web.bind.annotation.RequestBody
                                         Map<String, Object> body, HttpServletRequest req) {
        requirePerm(req, "attendance");
        String month = body.get("month") == null ? "" : String.valueOf(body.get("month"));
        try {
            Map<String, Object> st = monthService.startSync(month);
            Map<String, Object> out = new LinkedHashMap<>(st);
            out.put("ok", true);
            return out;
        } catch (Exception e) {
            throw new ApiException("同步失败：" + e.getMessage(), 500);
        }
    }

    /** 同步进度 */
    @GetMapping("/api/attendance/progress")
    public Map<String, Object> progress(@RequestParam(required = false) String month,
                                        HttpServletRequest req) {
        requirePerm(req, "attendance");
        return monthService.progress(month);
    }

    /** 导出月度考勤 Excel */
    @GetMapping("/api/attendance/export")
    public org.springframework.http.ResponseEntity<byte[]> export(
            @RequestParam(required = false) String month, HttpServletRequest req) throws Exception {
        requirePerm(req, "attendance");
        Map<String, Object> data = monthService.summary(month);
        String m = String.valueOf(data.get("month"));

        try (org.apache.poi.xssf.usermodel.XSSFWorkbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
            org.apache.poi.ss.usermodel.Sheet sh = wb.createSheet("考勤统计");
            String[] heads = {"姓名", "应出勤", "出勤", "迟到", "早退", "请假", "补卡", "缺卡", "出勤率"};
            org.apache.poi.ss.usermodel.Row head = sh.createRow(0);
            for (int i = 0; i < heads.length; i++) {
                head.createCell(i).setCellValue(heads[i]);
            }
            int r = 1;
            for (Object o : (java.util.List<?>) data.get("rows")) {
                Map<?, ?> row = (Map<?, ?>) o;
                org.apache.poi.ss.usermodel.Row line = sh.createRow(r++);
                line.createCell(0).setCellValue(String.valueOf(row.get("name")));
                line.createCell(1).setCellValue(num(row.get("attended")) + num(row.get("absent"))
                        + num(row.get("leave_days")));
                line.createCell(2).setCellValue(num(row.get("attended")));
                line.createCell(3).setCellValue(num(row.get("late")));
                line.createCell(4).setCellValue(num(row.get("early")));
                line.createCell(5).setCellValue(num(row.get("leave_days")));
                line.createCell(6).setCellValue(num(row.get("fix_days")));
                line.createCell(7).setCellValue(num(row.get("absent")));
                line.createCell(8).setCellValue(num(row.get("rate")) + "%");
            }
            for (int i = 0; i < heads.length; i++) {
                sh.setColumnWidth(i, 12 * 256);
            }
            wb.write(bos);
            String name = "考勤统计_" + m.replace("-", "") + ".xlsx";
            return org.springframework.http.ResponseEntity.ok()
                    .header("Content-Disposition", "attachment; filename*=UTF-8''"
                            + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8))
                    .contentType(org.springframework.http.MediaType
                            .parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                    .body(bos.toByteArray());
        }
    }

    private static int num(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }

    /** 手动刷新（拉最新打卡） */
    @PostMapping("/api/attendance/refresh")
    public Map<String, Object> refresh(HttpServletRequest req) {
        requirePerm(req, "attendance");
        try {
            Map<String, Object> out = new LinkedHashMap<>(attendance.today(true));
            out.put("ok", true);
            out.put("refreshed", true);
            return out;
        } catch (Exception e) {
            throw new ApiException("刷新失败：" + e.getMessage(), 500);
        }
    }
}
