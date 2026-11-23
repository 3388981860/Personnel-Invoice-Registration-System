package com.xinyu.invoice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作日日历：把国家法定节假日、调休上班、以及公司自己调整的日子记下来。
 *
 * 统计「应出勤天数」时以这里为准：先看有没有标过，没标就按周一到周五算。
 */
@Service
public class WorkdayCalendarService {

    private static final Logger log = LoggerFactory.getLogger(WorkdayCalendarService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();

    public WorkdayCalendarService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- 判断

    /** 某天是不是工作日 */
    public boolean isWorkday(LocalDate date) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT kind FROM workday_calendar WHERE work_date = ?", date.toString());
        if (!rows.isEmpty()) {
            return "workday".equals(String.valueOf(rows.get(0).get("kind")));
        }
        return date.getDayOfWeek().getValue() <= 5;
    }

    /** 一个月的所有工作日 */
    public List<LocalDate> workdaysOf(YearMonth month) {
        List<LocalDate> out = new ArrayList<>();
        for (int d = 1; d <= month.lengthOfMonth(); d++) {
            LocalDate date = month.atDay(d);
            if (isWorkday(date)) {
                out.add(date);
            }
        }
        return out;
    }

    /** 一个月的日历明细（给界面画格子） */
    public List<Map<String, Object>> monthCalendar(YearMonth month) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int d = 1; d <= month.lengthOfMonth(); d++) {
            LocalDate date = month.atDay(d);
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("date", date.toString());
            one.put("day", d);
            one.put("weekday", "周" + "一二三四五六日".charAt(date.getDayOfWeek().getValue() - 1));
            one.put("is_workday", isWorkday(date));
            List<Map<String, Object>> mark = jdbc.queryForList(
                    "SELECT kind, name, source FROM workday_calendar WHERE work_date = ?",
                    date.toString());
            if (!mark.isEmpty()) {
                one.put("kind", mark.get(0).get("kind"));
                one.put("name", mark.get(0).get("name"));
                one.put("source", mark.get(0).get("source"));
            } else {
                one.put("kind", null);
                one.put("name", null);
                one.put("source", null);
            }
            out.add(one);
        }
        return out;
    }

    // ---------------------------------------------------------------- 维护

    /** 手动标记某天 */
    public void setDay(LocalDate date, String kind, String name) {
        if (!"holiday".equals(kind) && !"workday".equals(kind)) {
            jdbc.update("DELETE FROM workday_calendar WHERE work_date = ?", date.toString());
            return;
        }
        String label = name == null || name.isBlank()
                ? ("holiday".equals(kind) ? "休息" : "上班")
                : name.trim();
        jdbc.update("INSERT OR REPLACE INTO workday_calendar"
                        + " (work_date, kind, name, source, updated_at) VALUES (?,?,?,?,?)",
                date.toString(), kind, label, "manual",
                LocalDateTime.now().format(TS));
    }

    /** 从公开接口同步某一年的法定节假日（会覆盖同年的自动标记，不动手动的） */
    public int syncYear(int year) {
        String body;
        try {
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create("https://timor.tech/api/holiday/year/" + year))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            body = resp.body();
        } catch (Exception e) {
            throw new IllegalStateException("拉取节假日失败：" + e.getMessage(), e);
        }

        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("节假日数据解析失败", e);
        }
        JsonNode holiday = root.path("holiday");
        if (!holiday.isObject()) {
            throw new IllegalStateException("节假日接口返回格式不对");
        }

        String now = LocalDateTime.now().format(TS);
        int n = 0;
        var it = holiday.fields();
        while (it.hasNext()) {
            var e = it.next();
            JsonNode v = e.getValue();
            String date = v.path("date").asText("");
            if (date.isEmpty()) {
                continue;
            }
            boolean isHoliday = v.path("holiday").asBoolean(false);
            String name = v.path("name").asText("");
            String kind = isHoliday ? "holiday" : "workday";
            String label = isHoliday ? (name.isEmpty() ? "节假日" : name + "放假")
                    : (name.isEmpty() ? "调休上班" : name + "调休上班");
            // 手动的优先级更高，不被覆盖
            List<Map<String, Object>> exists = jdbc.queryForList(
                    "SELECT source FROM workday_calendar WHERE work_date = ?", date);
            if (!exists.isEmpty() && "manual".equals(String.valueOf(exists.get(0).get("source")))) {
                continue;
            }
            jdbc.update("INSERT OR REPLACE INTO workday_calendar"
                            + " (work_date, kind, name, source, updated_at) VALUES (?,?,?,?,?)",
                    date, kind, label, "auto", now);
            n++;
        }
        log.info("同步 {} 年节假日：{} 条", year, n);
        return n;
    }

    /** 今天是不是工作日（给定时任务用） */
    public boolean todayIsWorkday() {
        return isWorkday(LocalDate.now());
    }
}
