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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 考勤：从钉钉拉打卡数据，算今日看板。
 *
 * 只读，不写钉钉。数据缓存到 attendance_daily 表，5 分钟内不重复拉。
 */
@Service
public class AttendanceService {

    private static final Logger log = LoggerFactory.getLogger(AttendanceService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int CACHE_MINUTES = 5;

    private final JdbcTemplate jdbc;
    private final DingTalkService ding;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8)).build();

    public AttendanceService(JdbcTemplate jdbc, DingTalkService ding) {
        this.jdbc = jdbc;
        this.ding = ding;
    }

    /** 今日看板 */
    public Map<String, Object> today(boolean force) throws Exception {
        LocalDate date = LocalDate.now();
        boolean weekend = date.getDayOfWeek().getValue() >= 6;
        if (force || stale(date)) {
            sync(date);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        int checked = 0, late = 0, absent = 0, leave = 0;
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT * FROM attendance_daily WHERE work_date = ? ORDER BY name", date.toString())) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("name", r.get("name"));
            one.put("on_duty", r.get("on_duty"));
            one.put("off_duty", r.get("off_duty"));
            one.put("on_result", label(str(r.get("on_result"))));
            one.put("off_result", label(str(r.get("off_result"))));
            one.put("address", r.get("address"));
            one.put("has_leave", num(r.get("has_leave")) > 0);

            String status;
            if (num(r.get("has_leave")) > 0) {
                status = "leave";
            } else if (r.get("on_duty") == null || str(r.get("on_duty")).isEmpty()) {
                status = weekend ? "rest" : "absent";
            } else if (str(r.get("on_result")).contains("Late")) {
                status = "late";
            } else {
                status = "normal";
            }
            one.put("status", status);

            if ("leave".equals(status)) {
                leave++;
            } else if ("late".equals(status)) {
                late++;
                checked++;
            } else if ("absent".equals(status)) {
                absent++;
            } else if ("normal".equals(status)) {
                checked++;
            }
            rows.add(one);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("date", date.toString());
        out.put("weekday", "周" + "一二三四五六日".charAt(date.getDayOfWeek().getValue() - 1));
        out.put("weekend", weekend);
        out.put("total", rows.size());
        out.put("checked", checked);
        out.put("late", late);
        out.put("absent", absent);
        out.put("leave", leave);
        out.put("rows", rows);
        out.put("synced_at", lastSync(date));
        return out;
    }

    // ---------------------------------------------------------------- 同步

    /** 拉某一天的打卡，写进缓存表 */
    public int sync(LocalDate date) throws Exception {
        String tok = ding.accessToken();
        List<Map<String, Object>> members = ding.directory();
        String day = date.toString();
        String now = LocalDateTime.now().format(TS);
        int ok = 0, fail = 0;

        for (Map<String, Object> m : members) {
            String dtId = str(m.get("userid"));
            String name = str(m.get("name"));
            if (dtId.isEmpty()) {
                continue;
            }
            try {
                JsonNode r = postJson("https://oapi.dingtalk.com/topapi/attendance/getupdatedata?access_token=" + tok,
                        Map.of("userid", dtId, "work_date", day), tok);
                JsonNode result = r.path("result");

                String onDuty = null, offDuty = null, onResult = "", offResult = "", address = "";
                for (JsonNode a : result.path("attendance_result_list")) {
                    String type = a.path("check_type").asText("");
                    String t = a.path("user_check_time").asText("");
                    String res = a.path("time_result").asText("");
                    String addr = a.path("user_address").asText("");
                    if ("OnDuty".equals(type) && (onDuty == null || t.compareTo(onDuty) < 0)) {
                        onDuty = t;
                        onResult = res;
                    }
                    if ("OffDuty".equals(type) && (offDuty == null || t.compareTo(offDuty) > 0)) {
                        offDuty = t;
                        offResult = res;
                    }
                    if (!addr.isEmpty() && address.isEmpty()) {
                        address = addr;
                    }
                }
                int hasLeave = result.path("approve_list").size() > 0 ? 1 : 0;

                jdbc.update("INSERT OR REPLACE INTO attendance_daily"
                                + " (work_date, dt_userid, name, on_duty, off_duty, on_result,"
                                + "  off_result, address, has_leave, updated_at)"
                                + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                        day, dtId, name, onDuty, offDuty, onResult, offResult, address, hasLeave, now);
                ok++;
            } catch (Exception e) {
                fail++;
                log.debug("拉考勤失败 {}：{}", name, e.getMessage());
            }
        }
        log.info("考勤同步 {}：成功 {} 人，失败 {} 人", day, ok, fail);
        return ok;
    }

    private boolean stale(LocalDate date) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT MAX(updated_at) AS t FROM attendance_daily WHERE work_date = ?", date.toString());
        if (rows.isEmpty() || rows.get(0).get("t") == null) {
            return true;
        }
        String t = str(rows.get(0).get("t"));
        try {
            LocalDateTime last = LocalDateTime.parse(t, TS);
            return last.plusMinutes(CACHE_MINUTES).isBefore(LocalDateTime.now());
        } catch (Exception e) {
            return true;
        }
    }

    private String lastSync(LocalDate date) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT MAX(updated_at) AS t FROM attendance_daily WHERE work_date = ?", date.toString());
        return rows.isEmpty() ? "" : str(rows.get(0).get("t"));
    }

    private JsonNode postJson(String url, Object body, String tok) throws Exception {
        String payload = json.writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return json.readTree(resp.body());
    }

    /** 钉钉的状态值换成中文 */
    private static String label(String v) {
        if (v == null || v.isEmpty()) {
            return "";
        }
        return switch (v) {
            case "Normal" -> "正常";
            case "Late" -> "迟到";
            case "SeriousLate" -> "严重迟到";
            case "Absenteeism" -> "旷工";
            case "Early" -> "早退";
            case "Leave" -> "请假";
            case "NotSigned" -> "未打卡";
            default -> v;
        };
    }

    private static int num(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
