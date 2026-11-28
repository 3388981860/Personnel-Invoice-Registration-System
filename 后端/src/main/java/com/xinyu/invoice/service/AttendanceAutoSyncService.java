package com.xinyu.invoice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * 考勤自动同步。
 *
 * 钉钉考勤只有「人 × 天」的接口，所以：
 *  - 每天凌晨把「昨天 + 今天」补一遍（当天数据会变，第二天再补一次才准）
 *  - 每月 1 号把上个月整月重拉一遍，补齐漏掉的日子
 */
@Service
public class AttendanceAutoSyncService {

    private static final Logger log = LoggerFactory.getLogger(AttendanceAutoSyncService.class);

    private final AttendanceService daily;
    private final AttendanceMonthService monthService;
    private final DingTalkService ding;

    public AttendanceAutoSyncService(AttendanceService daily,
                                     AttendanceMonthService monthService,
                                     DingTalkService ding) {
        this.daily = daily;
        this.monthService = monthService;
        this.ding = ding;
    }

    /** 每天 07:10：补昨天和今天（昨天数据可能在夜里才定稿） */
    @Scheduled(cron = "0 10 7 * * *")
    public void syncMorning() {
        runRecent(2);
    }

    /** 中午 12:30：只补今天 */
    @Scheduled(cron = "0 30 12 * * *")
    public void syncNoon() {
        runRecent(1);
    }

    /** 下班后 18:10：只补今天，保证当天的打卡记录是全的 */
    @Scheduled(cron = "0 10 18 * * *")
    public void syncEvening() {
        runRecent(1);
    }

    /** 每月 1 号 06:00：把上个月整月重拉一遍 */
    @Scheduled(cron = "0 0 6 1 * *")
    public void syncLastMonth() {
        YearMonth last = YearMonth.now().minusMonths(1);
        try {
            monthService.startSync(last.toString());
            log.info("自动同步上月 {} 已启动", last);
        } catch (Exception e) {
            log.warn("自动同步上月失败：{}", e.getMessage());
        }
    }

    /** 把最近 n 天（含今天）逐人拉一遍 */
    private void runRecent(int n) {
        List<Map<String, Object>> members;
        try {
            members = ding.directory();
        } catch (Exception e) {
            log.warn("自动同步跳过：读通讯录失败 {}", e.getMessage());
            return;
        }
        int ok = 0, fail = 0;
        LocalDate today = LocalDate.now();
        for (int i = n - 1; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            for (Map<String, Object> m : members) {
                String dtId = m.get("userid") == null ? "" : String.valueOf(m.get("userid"));
                String name = m.get("name") == null ? "" : String.valueOf(m.get("name"));
                if (dtId.isEmpty()) {
                    continue;
                }
                try {
                    daily.syncOne(d, dtId, name);
                    ok++;
                } catch (Exception e) {
                    fail++;
                }
            }
        }
        log.info("自动同步最近 {} 天：成功 {}，失败 {}", n, ok, fail);
    }
}
