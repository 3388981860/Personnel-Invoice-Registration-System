package com.xinyu.invoice.service;

import com.xinyu.invoice.web.DingTalkController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每日考勤异常推送。
 *
 * 工作日 18:30 检查今天的打卡情况：
 *   - 没打卡也没请假的人，推给他自己
 *   - 管理员收一条汇总
 */
@Service
public class AttendanceReminderService {

    private static final Logger log = LoggerFactory.getLogger(AttendanceReminderService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final AttendanceService attendance;
    private final WorkdayCalendarService calendar;
    private final NotifyBridge notify;

    @Autowired
    public AttendanceReminderService(JdbcTemplate jdbc, AttendanceService attendance,
                                     WorkdayCalendarService calendar, NotifyBridge notify) {
        this.jdbc = jdbc;
        this.attendance = attendance;
        this.calendar = calendar;
        this.notify = notify;
    }

    /** 每天 18:30（周一到周五）跑一次；节假日会自己跳过 */
    @Scheduled(cron = "0 30 18 * * MON-FRI")
    public void dailyCheck() {
        try {
            if (!calendar.todayIsWorkday()) {
                log.info("今天不是工作日，跳过考勤提醒");
                return;
            }
            Map<String, Object> r = run();
            log.info("考勤提醒已发：未打卡 {} 人", r.get("absent"));
        } catch (Exception e) {
            log.warn("考勤提醒失败：{}", e.getMessage());
        }
    }

    /** 跑一次检查（也供手动触发用） */
    public Map<String, Object> run() throws Exception {
        Map<String, Object> data = attendance.today(true);
        List<Map<String, Object>> rows = cast(data.get("rows"));

        java.util.Set<String> exempt = attendance.exemptIds();
        List<String> absentNames = new ArrayList<>();
        List<Long> absentUserIds = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (!"absent".equals(String.valueOf(row.get("status")))) {
                continue;
            }
            String name = String.valueOf(row.get("name"));
            Long checkId = userIdByName(name);
            if (checkId != null && exempt.contains(dingIdOf(checkId))) {
                continue;                       // 免考勤的人不提醒
            }
            absentNames.add(name);
            Long uid = userIdByName(name);
            if (uid != null) {
                absentUserIds.add(uid);
            }
        }

        String date = String.valueOf(data.get("date"));

        // 推给未打卡的人
        if (!absentUserIds.isEmpty()) {
            notify.push(absentUserIds, "今天还没打卡",
                    date + " 没查到你的打卡记录，如果是忘记了请尽快补卡；请假/外出请走审批。",
                    "/attendance");
        }

        // 推给管理员汇总
        if (!absentNames.isEmpty()) {
            List<Long> adminIds = new ArrayList<>();
            for (Map<String, Object> a : jdbc.queryForList(
                    "SELECT id FROM users WHERE role = 'admin' AND status = 1")) {
                adminIds.add(((Number) a.get("id")).longValue());
            }
            String list = String.join("、", absentNames);
            notify.push(adminIds, "今日未打卡 " + absentNames.size() + " 人",
                    date + "：" + list, "/attendance");

            String now = LocalDateTime.now().format(TS);
            for (Long aid : adminIds) {
                try {
                    jdbc.update("INSERT INTO notifications (user_id, kind, title, body, created_at)"
                                    + " VALUES (?,?,?,?,?)",
                            aid, "attendance", "今日未打卡 " + absentNames.size() + " 人",
                            list, now);
                } catch (Exception ignored) {
                    // 站内消息失败不影响
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", date);
        out.put("absent", absentNames.size());
        out.put("names", absentNames);
        out.put("pushed_to_self", absentUserIds.size());
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cast(Object o) {
        return o instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    private String dingIdOf(Long userId) {
        java.util.List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT dingtalk_userid FROM users WHERE id = ?", userId);
        return rows.isEmpty() || rows.get(0).get("dingtalk_userid") == null
                ? null : String.valueOf(rows.get(0).get("dingtalk_userid"));
    }

    private Long userIdByName(String name) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id FROM users WHERE display_name = ? AND status = 1", name);
        if (rows.isEmpty()) {
            return null;
        }
        return ((Number) rows.get(0).get("id")).longValue();
    }
}
