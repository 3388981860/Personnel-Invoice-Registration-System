package com.xinyu.invoice.web;

import com.xinyu.invoice.config.AppProps;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 健康检查 + 版本信息（不需要登录） */
@RestController
public class HealthController extends BaseController {

    private final AppProps props;

    public HealthController(AppProps props) {
        this.props = props;
    }

    @GetMapping("/_health")
    public String health() {
        try {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM invoices", Integer.class);
            return "ok invoices=" + (n == null ? 0 : n);
        } catch (Exception e) {
            return "degraded " + e.getClass().getSimpleName();
        }
    }

    @GetMapping("/api/ping")
    public Map<String, Object> ping(jakarta.servlet.http.HttpServletRequest req) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("app", "renshu-admin");
        out.put("platform", System.getProperty("os.name", "").toLowerCase()
                .contains("win") ? "win32" : "linux");
        out.put("can_open_local", System.getProperty("os.name", "").toLowerCase()
                .contains("win"));
        out.put("version", "3.0");
        out.put("build", "20261220");
        out.put("engine", "spring-boot");
        out.put("logged_in", req.getAttribute("user") != null);
        return out;
    }
}
