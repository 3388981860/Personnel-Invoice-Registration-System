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

    public AttendanceController(AttendanceService attendance) {
        this.attendance = attendance;
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
