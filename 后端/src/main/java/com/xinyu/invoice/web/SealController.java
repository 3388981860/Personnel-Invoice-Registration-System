package com.xinyu.invoice.web;

import com.xinyu.invoice.common.ApiException;
import com.xinyu.invoice.service.SealService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 用印申请：系统发起，钉钉审批，结果回传 */
@RestController
public class SealController extends BaseController {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SealService seal;
    private final com.xinyu.invoice.service.DingTalkService ding;

    public SealController(SealService seal, com.xinyu.invoice.service.DingTalkService ding) {
        this.seal = seal;
        this.ding = ding;
    }

    /** 下拉选项（从钉钉历史单据里收集出来的真实可选值） */
    @GetMapping("/api/seal/options")
    public Map<String, Object> options(HttpServletRequest req) {
        requireUser(req);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("categories", List.of("公告类", "规章制度类", "合同类", "其他"));
        out.put("seals", List.of("公章", "合同章", "法人章", "其他"));
        out.put("approver", "李洪兵");
        return out;
    }

    /** 我的申请（管理员可看全部） */
    @GetMapping("/api/seal/list")
    public Map<String, Object> list(@RequestParam(required = false, defaultValue = "false") boolean all,
                                    HttpServletRequest req) {
        Map<String, Object> u = requireUser(req);
        boolean admin = com.xinyu.invoice.auth.SessionService.isAdmin(u);
        String sql = "SELECT * FROM seal_requests";
        List<Map<String, Object>> rows;
        if (all && admin) {
            rows = jdbc.queryForList(sql + " ORDER BY id DESC LIMIT 200");
        } else {
            rows = jdbc.queryForList(sql + " WHERE applicant_id = ? ORDER BY id DESC LIMIT 200",
                    uid(u));
        }
        for (Map<String, Object> r : rows) {
            r.put("status_text", statusText(String.valueOf(r.get("status"))));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("rows", rows);
        return out;
    }

    /** 上传图片或附件，返回钉钉 media_id（type=image / file） */
    @PostMapping("/api/seal/upload")
    public Map<String, Object> upload(@RequestParam(required = false, defaultValue = "image") String type,
                                      HttpServletRequest req) throws Exception {
        requireUser(req);
        byte[] raw = req.getInputStream().readAllBytes();
        if (raw.length == 0) {
            throw new ApiException("没有收到文件");
        }
        if (raw.length > 20 * 1024 * 1024) {
            throw new ApiException("文件太大了，请压到 20MB 以内");
        }
        // 前端把文件名 base64 放在头里，这里解回来
        String name = "";
        String rawName = req.getHeader("X-File-Name");
        if (rawName != null && !rawName.isBlank()) {
            try {
                name = new String(java.util.Base64.getDecoder().decode(rawName),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                name = rawName;
            }
        }
        String mediaId;
        if ("file".equals(type)) {
            mediaId = ding.uploadFile(raw, name.isBlank() ? "attachment" : name, "file");
        } else {
            if (name.isBlank()) {
                name = "seal.png";
            } else if (!name.toLowerCase().matches(".*\\.(png|jpg|jpeg|gif|bmp)$")) {
                name = name + ".png";
            }
            mediaId = ding.uploadImage(raw, name);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("media_id", mediaId);
        return out;
    }

    /** 提交用印申请 */
    @PostMapping("/api/seal/submit")
    public Map<String, Object> submit(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        Map<String, Object> u = requireUser(req);
        String dtId = u.get("dingtalk_userid") == null
                ? "" : String.valueOf(u.get("dingtalk_userid"));
        if (dtId.isBlank()) {
            throw new ApiException("你的账号还没绑定钉钉，先在「用户管理」里绑定才能发起审批");
        }
        String person = str(body.get("person"));
        String docName = str(body.get("doc_name")).trim();
        if (docName.isEmpty()) {
            throw new ApiException("请填写用印文件名称");
        }
        if (person.isEmpty()) {
            person = str(u.get("display_name"));
        }
        int copies = 1;
        try {
            copies = Integer.parseInt(String.valueOf(body.get("copies")).trim());
        } catch (Exception ignored) {
            copies = 1;
        }
        String category = str(body.get("doc_category"));
        if (category.isEmpty()) {
            category = "合同类";
        }
        String sealType = str(body.get("seal_type"));
        if (sealType.isEmpty()) {
            sealType = "公章";
        }
        String bizDate = str(body.get("biz_date"));
        if (bizDate.isEmpty()) {
            bizDate = LocalDate.now().toString();
        }
        String note = str(body.get("note"));

        List<String> photoIds = toList(body.get("photo_ids"));
        List<String> attachIds = toList(body.get("attachment_ids"));
        if (attachIds.isEmpty()) {
            throw new ApiException("请上传用印文件附件");
        }
        if (photoIds.isEmpty()) {
            throw new ApiException("请上传用印文件照片（钉钉模板里这个字段是必填的）");
        }

        // 部门 id：钉钉建单必填，取发起人的第一个部门，拿不到就用人数的 1
        long deptId = 1L;
        List<Map<String, Object>> userRows = jdbc.queryForList(
                "SELECT dept_ids FROM users WHERE id = ?", uid(u));
        if (!userRows.isEmpty() && userRows.get(0).get("dept_ids") != null) {
            String ids = String.valueOf(userRows.get(0).get("dept_ids"));
            if (!ids.isBlank()) {
                try {
                    deptId = Long.parseLong(ids.split(",")[0].trim());
                } catch (NumberFormatException ignored) {
                    deptId = 1L;
                }
            }
        }

        Map<String, String> form = new LinkedHashMap<>();
        form.put(SealService.FIELD_PERSON, person);
        form.put(SealService.FIELD_DATE, bizDate);
        form.put(SealService.FIELD_DOC_NAME, docName);
        form.put(SealService.FIELD_COPIES, String.valueOf(copies));
        form.put(SealService.FIELD_CATEGORY, category);
        form.put(SealService.FIELD_SEAL, sealType);
        form.put(SealService.FIELD_NOTE, note);
        if (!photoIds.isEmpty()) {
            form.put(SealService.FIELD_PHOTO, toJsonArray(photoIds));
        }
        form.put(SealService.FIELD_ATTACH, attachJson(attachIds));

        String instanceId;
        try {
            instanceId = seal.createInstance(dtId, deptId, SealService.DEFAULT_APPROVER, form);
        } catch (Exception e) {
            throw new ApiException("钉钉建审批失败：" + e.getMessage(), 500);
        }

        Map<String, Object> rec = new LinkedHashMap<>();
        rec.put("applicant_id", uid(u));
        rec.put("applicant_name", person);
        rec.put("dept_name", str(u.get("dept_name")));
        rec.put("biz_date", bizDate);
        rec.put("doc_name", docName);
        rec.put("copies", copies);
        rec.put("doc_category", category);
        rec.put("seal_type", sealType);
        rec.put("note", note);
        rec.put("photo_ids", photoIds.isEmpty() ? null : toJsonArray(photoIds));
        rec.put("attachment_ids", toJsonArray(attachIds));
        rec.put("dt_instance_id", instanceId);
        long id = seal.insert(rec);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", id);
        out.put("dt_instance_id", instanceId);
        return out;
    }

    /** 手动同步一次状态（列表页的刷新按钮用） */
    @PostMapping("/api/seal/sync")
    public Map<String, Object> sync(HttpServletRequest req) {
        requireUser(req);
        seal.poll();
        return Map.of("ok", true);
    }

    private static String statusText(String s) {
        return switch (s) {
            case "approved" -> "已通过";
            case "refused" -> "已驳回";
            case "canceled" -> "已撤销";
            default -> "审批中";
        };
    }

    private static List<String> toList(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    out.add(String.valueOf(o));
                }
            }
        }
        return out;
    }

    /** 附件字段要的是对象数组：[{"fileId":"..."}] */
    private static String attachJson(List<String> ids) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\"fileId\":\"").append(ids.get(i)).append("\"}");
        }
        return sb.append("]").toString();
    }

    private static String toJsonArray(List<String> ids) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("\"").append(ids.get(i)).append("\"");
        }
        return sb.append("]").toString();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
