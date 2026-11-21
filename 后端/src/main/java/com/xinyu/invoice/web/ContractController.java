package com.xinyu.invoice.web;

import com.xinyu.invoice.auth.SessionService;
import com.xinyu.invoice.common.ApiException;
import com.xinyu.invoice.config.AppProps;
import com.xinyu.invoice.repo.ContractRepo;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 合同管理：项目 / 合同 / 扫描件 */
@RestController
public class ContractController extends BaseController {

    private static final Set<String> OK_EXT = Set.of(".pdf", ".ofd", ".jpg", ".jpeg", ".png");
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ContractRepo repo;
    private final AppProps props;

    public ContractController(ContractRepo repo, AppProps props) {
        this.repo = repo;
        this.props = props;
    }

    // ---------------------------------------------------------------- 项目

    @GetMapping("/api/projects")
    public Map<String, Object> projects(@RequestParam(required = false) String q,
                                        HttpServletRequest req) {
        Map<String, Object> u = requirePerm(req, "contracts");
        Long owner = SessionService.isAdmin(u) ? null : uid(u);
        List<Map<String, Object>> rows = repo.projects(owner);
        if (q != null && !q.isBlank()) {
            String k = q.strip().toLowerCase();
            rows = rows.stream().filter(r -> (
                    str(r.get("name")) + str(r.get("unit")) + str(r.get("code")))
                    .toLowerCase().contains(k)).toList();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("projects", rows);
        return out;
    }

    @GetMapping("/api/project")
    public Map<String, Object> project(@RequestParam String id, HttpServletRequest req) {
        Map<String, Object> u = requirePerm(req, "contracts");
        Map<String, Object> p = repo.project(Long.parseLong(id));
        if (p == null) {
            throw new ApiException("找不到这个项目", 404);
        }
        requireOwnProject(p, u);
        return Map.of("ok", true, "project", p);
    }

    /** 普通用户只能碰自己创建的项目 */
    private void requireOwnProject(Map<String, Object> p, Map<String, Object> u) {
        if (SessionService.isAdmin(u)) {
            return;
        }
        Object owner = p.get("owner_id");
        long oid = owner == null ? 0 : ((Number) owner).longValue();
        if (oid != uid(u)) {
            throw new ApiException("这个项目不是你创建的，看不到也改不了", 403);
        }
    }

    /** 按项目编号取并校验归属（合同、开票、付款都用这个） */
    private Map<String, Object> projectOf(long pid, Map<String, Object> u) {
        Map<String, Object> p = repo.project(pid);
        if (p == null) {
            throw new ApiException("找不到这个项目", 404);
        }
        requireOwnProject(p, u);
        return p;
    }

    @PostMapping("/api/project/save")
    public Map<String, Object> saveProject(@RequestBody Map<String, Object> body,
                                           HttpServletRequest req) {
        Map<String, Object> u = requirePerm(req, "contracts");
        Object pid = body.get("id");
        if (pid instanceof Number n) {
            projectOf(n.longValue(), u);      // 改别人的项目直接 403
        }
        long id = repo.saveProject(body, uid(u));
        log(uid(u), str(u.get("username")), "save_project", id, str(body.get("name")));
        return Map.of("ok", true, "id", id, "project", repo.project(id));
    }

    @PostMapping("/api/project/delete")
    public Map<String, Object> deleteProject(@RequestBody Map<String, Object> body,
                                             HttpServletRequest req) {
        Map<String, Object> u = requirePerm(req, "contracts");
        long id = ContractRepo.asLong(body.get("id"), "缺少项目编号");
        Map<String, Object> p = repo.project(id);
        if (p == null) {
            throw new ApiException("找不到这个项目", 404);
        }
        requireOwnProject(p, u);
        repo.deleteProject(id);
        log(uid(u), str(u.get("username")), "delete_project", id, str(p.get("name")));
        return Map.of("ok", true);
    }

    // ---------------------------------------------------------------- 合同

    @PostMapping("/api/contract/save")
    public Map<String, Object> saveContract(@RequestBody Map<String, Object> body,
                                            HttpServletRequest req) {
        Map<String, Object> u = requirePerm(req, "contracts");
        long pid = ContractRepo.asLong(body.get("project_id"), "缺少项目编号");
        projectOf(pid, u);
        Object cid = body.get("id");
        if (cid instanceof Number n) {
            Map<String, Object> old = repo.contract(n.longValue());
            if (old != null) {
                projectOf(((Number) old.get("project_id")).longValue(), u);
            }
        }
        long id = repo.saveContract(body);
        Map<String, Object> c = repo.contract(id);
        log(uid(u), str(u.get("username")), "save_contract", id,
                str(c.get("contract_no")) + " " + str(c.get("kind")));
        return Map.of("ok", true, "id", id, "contract", c);
    }

    @PostMapping("/api/contract/delete")
    public Map<String, Object> deleteContract(@RequestBody Map<String, Object> body,
                                              HttpServletRequest req) {
        Map<String, Object> u = requirePerm(req, "contracts");
        long id = ContractRepo.asLong(body.get("id"), "缺少合同编号");
        Map<String, Object> c = repo.contract(id);
        if (c == null) {
            throw new ApiException("找不到这份合同", 404);
        }
        projectOf(((Number) c.get("project_id")).longValue(), u);
        // 合同上的扫描件一起删
        deleteFile(str(c.get("file_rel")));
        repo.deleteContract(id);
        log(uid(u), str(u.get("username")), "delete_contract", id,
                str(c.get("contract_no")));
        return Map.of("ok", true);
    }

    // ---------------------------------------------------------------- 开票 / 回款

    @PostMapping("/api/billing/save")
    public Map<String, Object> saveBilling(@RequestBody Map<String, Object> body,
                                           HttpServletRequest req) {
        Map<String, Object> u = requirePerm(req, "contracts");
        long cid = ContractRepo.asLong(body.get("contract_id"), "缺少合同编号");
        Map<String, Object> c = repo.contract(cid);
        if (c == null) {
            throw new ApiException("找不到这份合同", 404);
        }
        long id = repo.saveBilling(body);
        repo.billing(id);
        log(uid(u), str(u.get("username")),
                "invoice".equals(str(body.get("kind"))) ? "contract_invoice" : "contract_pay",
                cid, str(body.get("amount")));
        return Map.of("ok", true, "id", id,
                "project", repo.project(((Number) c.get("project_id")).longValue()));
    }

    @PostMapping("/api/billing/delete")
    public Map<String, Object> deleteBilling(@RequestBody Map<String, Object> body,
                                             HttpServletRequest req) {
        Map<String, Object> u = requirePerm(req, "contracts");
        long id = ContractRepo.asLong(body.get("id"), "缺少记录编号");
        Map<String, Object> b = repo.billing(id);
        if (b == null) {
            throw new ApiException("找不到这条记录", 404);
        }
        long cid = ((Number) b.get("contract_id")).longValue();
        repo.deleteBilling(id);
        Map<String, Object> c = repo.contract(cid);
        log(uid(u), str(u.get("username")), "contract_billing_delete", cid, str(id));
        return Map.of("ok", true,
                "project", repo.project(((Number) c.get("project_id")).longValue()));
    }
    // ---------------------------------------------------------------- 扫描件

    @PostMapping("/api/contract/upload")
    public Map<String, Object> upload(@RequestParam("id") long id,
                                      @RequestParam("file") MultipartFile file,
                                      HttpServletRequest req) throws IOException {
        Map<String, Object> u = requirePerm(req, "contracts");
        Map<String, Object> c = repo.contract(id);
        if (c == null) {
            throw new ApiException("找不到这份合同", 404);
        }
        projectOf(((Number) c.get("project_id")).longValue(), u);
        String name = file.getOriginalFilename() == null ? "contract.pdf"
                : file.getOriginalFilename();
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            ext = name.substring(dot).toLowerCase();
        }
        if (!OK_EXT.contains(ext)) {
            throw new ApiException("只能上传 PDF / OFD / 图片");
        }

        Path dir = props.dataDir().resolve("合同存档")
                .resolve(String.valueOf(LocalDate.now().getYear()))
                .resolve(String.valueOf(LocalDate.now().getMonthValue()));
        Files.createDirectories(dir);
        String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        Path target = dir.resolve(System.currentTimeMillis() + "_" + safe);
        file.transferTo(target.toFile());

        String rel = props.base().relativize(target).toString().replace("\\", "/");
        deleteFile(str(c.get("file_rel")));
        jdbc.update("UPDATE contracts SET file_rel = ?, file_name = ?, updated_at = ? "
                        + "WHERE id = ?",
                rel, name, LocalDateTime.now().format(TS), id);
        log(uid(u), str(u.get("username")), "upload_contract", id, name);
        return Map.of("ok", true, "file_rel", rel, "file_name", name);
    }

    /** 看扫描件：浏览器里直接打开 */
    @GetMapping("/api/contract/file")
    public ResponseEntity<byte[]> file(@RequestParam String id, HttpServletRequest req)
            throws IOException {
        requirePerm(req, "contracts");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT file_rel, file_name FROM contracts WHERE id = ?",
                Long.parseLong(id));
        if (rows.isEmpty() || rows.get(0).get("file_rel") == null) {
            throw new ApiException("这份合同还没有扫描件", 404);
        }
        String rel = str(rows.get(0).get("file_rel"));
        Path base = props.base().toAbsolutePath().normalize();
        Path f = props.base().resolve(rel.replace("/", java.io.File.separator))
                .toAbsolutePath().normalize();
        if (!f.startsWith(base) || !Files.isRegularFile(f)) {
            throw new ApiException("文件不在了", 404);
        }
        String ext = rel.substring(rel.lastIndexOf('.') + 1).toLowerCase();
        MediaType type = switch (ext) {
            case "pdf" -> MediaType.APPLICATION_PDF;
            case "ofd" -> MediaType.parseMediaType("application/ofd");
            case "png" -> MediaType.IMAGE_PNG;
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
        String encoded = URLEncoder.encode(str(rows.get(0).get("file_name")),
                StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.noCache())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''" + encoded)
                .body(Files.readAllBytes(f));
    }

    // ---------------------------------------------------------------- 工具

    private void deleteFile(String rel) {
        if (rel == null || rel.isBlank()) {
            return;
        }
        try {
            Path f = props.base().resolve(rel.replace("/", java.io.File.separator))
                    .toAbsolutePath().normalize();
            if (f.startsWith(props.base().toAbsolutePath().normalize())) {
                Files.deleteIfExists(f);
            }
        } catch (IOException ignored) {
            // 删不掉就算了
        }
    }

    private void log(long userId, String username, String action, long targetId,
                     String detail) {
        jdbc.update("INSERT INTO audit_logs (user_id, username, action, target_type,"
                        + " target_id, detail, created_at) VALUES (?,?,?,?,?,?,?)",
                userId, username, action, "contract", targetId, detail,
                LocalDateTime.now().format(TS));
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
