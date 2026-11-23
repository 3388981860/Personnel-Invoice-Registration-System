package com.xinyu.invoice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 月度考勤统计。
 *
 * 钉钉没有批量接口（per-user-per-day），所以按月把人×天拉一遍，存到 attendance_daily，
 * 统计时只查本地表。同步放后台线程，前端轮询进度。
 */
@Service
public class AttendanceMonthService {

    private static final Logger log = LoggerFactory.getLogger(AttendanceMonthService.class);

    private final JdbcTemplate jdbc;
    private final AttendanceService daily;
    private final DingTalkService ding;
    private final WorkdayCalendarService calendar;

    /** 当前正在同步的任务：month → 进度 */
    private final Map<String, Map<String, Object>> jobs = new ConcurrentHashMap<>();

    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "attendance-month-sync");
        t.setDaemon(true);
        return t;
    });

    public AttendanceMonthService(JdbcTemplate jdbc, AttendanceService daily, DingTalkService ding,
                                  WorkdayCalendarService calendar) {
        this.jdbc = jdbc;
        this.daily = daily;
        this.ding = ding;
        this.calendar = calendar;
    }

    // ---------------------------------------------------------------- 同步

    /** 启动某月的同步（后台跑，重复调用不会重复启动） */
    public Map<String, Object> startSync(String ym) {
        YearMonth month = parse(ym);
        String key = month.toString();
        Map<String, Object> job = jobs.get(key);
        if (job != null && Boolean.TRUE.equals(job.get("running"))) {
            return job;
        }
        List<LocalDate> days = calendar.workdaysOf(month);
        List<Map<String, Object>> members;
        try {
            members = ding.directory();
        } catch (Exception e) {
            throw new IllegalStateException("读钉钉通讯录失败：" + e.getMessage());
        }

        Map<String, Object> state = new ConcurrentHashMap<>();
        state.put("month", key);
        state.put("running", true);
        state.put("total", days.size() * members.size());
        state.put("done", 0);
        state.put("failed", 0);
        jobs.put(key, state);

        pool.submit(() -> {
            AtomicInteger done = new AtomicInteger();
            AtomicInteger failed = new AtomicInteger();
            try {
                for (LocalDate d : days) {
                    for (Map<String, Object> m : members) {
                        String dtId = str(m.get("userid"));
                        if (dtId.isEmpty()) {
                            continue;
                        }
                        try {
                            daily.syncOne(d, dtId, str(m.get("name")));
                        } catch (Exception e) {
                            failed.incrementAndGet();
                        }
                        state.put("done", done.incrementAndGet());
                        state.put("failed", failed.get());
                    }
                }
            } catch (Exception e) {
                log.warn("月度同步中断：{}", e.getMessage());
            } finally {
                state.put("running", false);
                log.info("月度同步完成 {}：成功 {}，失败 {}", key, done.get(), failed.get());
            }
        });
        return state;
    }

    public Map<String, Object> jobState(String ym) {
        return jobs.getOrDefault(parse(ym).toString(), Map.of("running", false));
    }

    // ---------------------------------------------------------------- 统计

    /** 某月每人的统计 */
    public Map<String, Object> summary(String ym) {
        YearMonth month = parse(ym);
        LocalDate today = LocalDate.now();
        List<LocalDate> days = new ArrayList<>(calendar.workdaysOf(month));
        // 当月只统计到今天
        if (month.equals(YearMonth.from(today))) {
            days = days.stream().filter(d -> !d.isAfter(today)).toList();
        }
        int workdays = days.size();
        String from = month.atDay(1).toString();
        String to = month.atEndOfMonth().toString();

        java.util.Set<String> exempt = daily.exemptIds();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT name, dt_userid,"
                        + " COUNT(DISTINCT CASE WHEN on_duty IS NOT NULL AND on_duty <> ''"
                        + "   THEN work_date END) AS attended,"
                        + " SUM(CASE WHEN on_result LIKE '%Late%' THEN 1 ELSE 0 END) AS late,"
                        + " SUM(CASE WHEN off_result LIKE '%Early%' THEN 1 ELSE 0 END) AS early,"
                        + " SUM(CASE WHEN has_leave = 1 THEN 1 ELSE 0 END) AS leave_days,"
                        + " SUM(CASE WHEN has_fix = 1 THEN 1 ELSE 0 END) AS fix_days,"
                        + " COUNT(*) AS punched_days"
                        + " FROM attendance_daily WHERE work_date BETWEEN ? AND ?"
                        + " GROUP BY dt_userid, name ORDER BY name", from, to)) {
            if (exempt.contains(str(r.get("dt_userid")))) {
                continue;                       // 免考勤的人不进统计
            }
            int attended = num(r.get("attended"));
            int leave = num(r.get("leave_days"));
            int absent = Math.max(0, workdays - attended - leave);
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("name", r.get("name"));
            one.put("attended", attended);
            one.put("late", num(r.get("late")));
            one.put("early", num(r.get("early")));
            one.put("leave_days", leave);
            one.put("fix_days", num(r.get("fix_days")));
            one.put("absent", absent);
            one.put("rate", workdays == 0 ? 0 : Math.round(attended * 1000.0 / workdays) / 10.0);
            rows.add(one);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("month", month.toString());
        out.put("workdays", workdays);
        out.put("rows", rows);
        out.put("syncing", Boolean.TRUE.equals(jobState(month.toString()).get("running")));
        out.put("job", jobState(month.toString()));
        return out;
    }

    /** 同步进度给前端看 */
    public Map<String, Object> progress(String ym) {
        Map<String, Object> st = jobState(ym);
        int total = num(st.get("total"));
        int done = num(st.get("done"));
        Map<String, Object> out = new LinkedHashMap<>(st);
        out.put("percent", total == 0 ? 100 : Math.min(100, done * 100 / total));
        return out;
    }

    // ---------------------------------------------------------------- 工具

    private static YearMonth parse(String ym) {
        try {
            return YearMonth.parse(ym);
        } catch (Exception e) {
            return YearMonth.now();
        }
    }

    /** 一个月的所有工作日（周一到周五） */
    private static List<LocalDate> workdays(YearMonth month) {
        List<LocalDate> out = new ArrayList<>();
        for (int d = 1; d <= month.lengthOfMonth(); d++) {
            LocalDate date = month.atDay(d);
            if (date.getDayOfWeek().getValue() <= 5) {
                out.add(date);
            }
        }
        return out;
    }

    private static int num(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
