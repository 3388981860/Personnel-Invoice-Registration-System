package com.xinyu.invoice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用印申请：系统里填表 → 调钉钉创建审批实例 → 定时轮询审批结果。
 *
 * 钉钉的表单字段 id 是从历史实例里读出来的，模板是「用印申请-新」。
 */
@Service
public class SealService {

    private static final Logger log = LoggerFactory.getLogger(SealService.class);

    /** 钉钉「用印申请-新」模板 */
    public static final String PROCESS_CODE = "PROC-5C9C8F29-C854-42C9-93CA-3421C8AC1CFB";

    /** 表单字段 id（从钉钉历史实例读出来的，不能乱改） */
    public static final String FIELD_PERSON = "TextField-K344W0HL";      // 经办人
    public static final String FIELD_DATE = "日期";
    public static final String FIELD_DOC_NAME = "用印文件名称";
    public static final String FIELD_PHOTO = "DDPhotoField_1HDWSW5OT1FK0";
    public static final String FIELD_ATTACH = "DDAttachment_9Z513QIMMUO0";
    public static final String FIELD_COPIES = "文件份数";
    public static final String FIELD_CATEGORY = "文件类别";
    public static final String FIELD_SEAL = "加盖何种印章";
    public static final String FIELD_NOTE = "备注";
    /** 组件 key → 中文标签（钉钉建单时 id 和 name 都要） */
    private static final Map<String, String> LABELS = Map.of(
            FIELD_PERSON, "经办人",
            FIELD_DATE, "日期",
            FIELD_DOC_NAME, "用印文件名称",
            FIELD_PHOTO, "用印文件照片",
            FIELD_ATTACH, "附件",
            FIELD_COPIES, "文件份数",
            FIELD_CATEGORY, "文件类别",
            FIELD_SEAL, "加盖何种印章",
            FIELD_NOTE, "备注");


    /** 抄送人：王潘亮、黄周瑜 */
    public static final java.util.List<String> CC_USERS =
            java.util.List.of("1792112218111106", "080124312539758520");

    /** 第一版审批人固定为李洪兵 */
    public static final String DEFAULT_APPROVER = "1532230426301785";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final DingTalkService ding;

    public SealService(JdbcTemplate jdbc, DingTalkService ding) {
        this.jdbc = jdbc;
        this.ding = ding;
    }

    // ---------------------------------------------------------------- 发起

    /** 创建钉钉审批实例，返回 process_instance_id */
    public String createInstance(String originatorUserId, long originatorDeptId,
                                 String approverUserId, Map<String, String> form) {
        java.util.List<Map<String, String>> ccs = new java.util.ArrayList<>();
        for (String uid : CC_USERS) {
            ccs.add(Map.of("user_id", uid));
        }
        java.util.List<Map<String, Object>> comps = new java.util.ArrayList<>();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (e.getValue() == null || e.getValue().isBlank()) {
                continue;
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("id", e.getKey());                       // 组件 key
            one.put("name", LABELS.getOrDefault(e.getKey(), e.getKey()));  // 中文标签
            one.put("value", e.getValue());
            comps.add(one);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("process_code", PROCESS_CODE);
        body.put("originator_user_id", originatorUserId);
        body.put("dept_id", originatorDeptId);
        body.put("approvers", List.of(Map.of("user_id", approverUserId)));
        body.put("cc_list", ccs);
        body.put("cc_position", "START_FINISH");
        body.put("form_component_values", comps);

        var r = ding.createProcessInstance(body);
        if (r.path("errcode").asInt(-1) != 0) {
            throw new IllegalStateException("钉钉建单失败：" + r.path("errmsg").asText()
                    + " " + r.path("sub_msg").asText(""));
        }
        return r.path("process_instance_id").asText("");
    }

    /** 查一条实例的当前状态 */
    public Map<String, Object> fetchStatus(String instanceId) {
        var r = ding.getProcessInstance(instanceId);
        var pi = r.path("process_instance");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", pi.path("title").asText(""));
        out.put("business_id", pi.path("business_id").asText(""));
        out.put("status", pi.path("status").asText(""));
        out.put("result", pi.path("result").asText(""));
        out.put("finish_time", pi.path("finish_time").asText(""));
        return out;
    }

    // ---------------------------------------------------------------- 本地记录

    public long insert(Map<String, Object> r) {
        String now = LocalDateTime.now().format(TS);
        jdbc.update("INSERT INTO seal_requests (applicant_id, applicant_name, dept_name,"
                        + " biz_date, doc_name, copies, doc_category, seal_type, note, photo_ids,"
                        + " attachment_ids, dt_instance_id, status, created_at, updated_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                r.get("applicant_id"), r.get("applicant_name"), r.get("dept_name"),
                r.get("biz_date"), r.get("doc_name"), r.get("copies"),
                r.get("doc_category"), r.get("seal_type"), r.get("note"),
                r.get("photo_ids"), r.get("attachment_ids"),
                r.get("dt_instance_id"), "pending", now, now);
        Long id = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        return id == null ? 0 : id;
    }

    /** 轮询所有还在审批中的单 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void poll() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, dt_instance_id FROM seal_requests WHERE status = 'pending'"
                        + " AND dt_instance_id IS NOT NULL ORDER BY id DESC LIMIT 50");
        if (rows.isEmpty()) {
            return;
        }
        int changed = 0;
        for (Map<String, Object> row : rows) {
            try {
                Map<String, Object> st = fetchStatus(String.valueOf(row.get("dt_instance_id")));
                String status = String.valueOf(st.get("status"));
                String result = String.valueOf(st.get("result"));
                if ("COMPLETED".equals(status)) {
                    String local = "agree".equals(result) ? "approved" : "refused";
                    jdbc.update("UPDATE seal_requests SET status = ?, result_text = ?,"
                                    + " dt_business_id = ?, finish_time = ?, updated_at = ? WHERE id = ?",
                            local, result, st.get("business_id"), st.get("finish_time"),
                            LocalDateTime.now().format(TS), row.get("id"));
                    changed++;
                } else if (!String.valueOf(st.get("business_id")).isBlank()) {
                    jdbc.update("UPDATE seal_requests SET dt_business_id = ?, updated_at = ?"
                            + " WHERE id = ?", st.get("business_id"),
                            LocalDateTime.now().format(TS), row.get("id"));
                }
            } catch (Exception e) {
                log.warn("查用印审批状态失败 {}：{}", row.get("dt_instance_id"), e.getMessage());
            }
        }
        if (changed > 0) {
            log.info("用印申请状态已更新 {} 条", changed);
        }
    }
}
