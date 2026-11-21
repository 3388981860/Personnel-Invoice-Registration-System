package com.xinyu.invoice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinyu.invoice.common.ApiException;
import com.xinyu.invoice.config.AppProps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调用 Python 查验小服务（工具/查验服务.py）。
 *
 * 查验要开浏览器、下验证码、跑 OCR，这套逻辑重写到 Java 风险太大，
 * 保持 HTTP 边界：Java 负责权限和转发，Python 负责真干活。
 */
@Service
public class AuditProxyService {

    private static final Logger log = LoggerFactory.getLogger(AuditProxyService.class);

    private final AppProps props;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    public AuditProxyService(AppProps props, ObjectMapper json) {
        this.props = props;
        this.json = json;
    }

    public boolean enabled() {
        return props.getAuditBaseUrl() != null && !props.getAuditBaseUrl().isBlank();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> status() {
        Map<String, Object> r = call("GET", "/audit/status", null, 10);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("state", r.getOrDefault("state", emptyState()));
        out.put("config", r.getOrDefault("config", Map.of()));
        return out;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> start(List<Long> ids) {
        Map<String, Object> payload = Map.of("ids", ids);
        Map<String, Object> r = call("POST", "/audit/start", payload, 30);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("count", r.getOrDefault("count", ids.size()));
        out.put("state", r.getOrDefault("state", emptyState()));
        return out;
    }

    public Map<String, Object> stop() {
        Map<String, Object> r = call("POST", "/audit/stop", Map.of(), 15);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("state", r.getOrDefault("state", emptyState()));
        return out;
    }

    // ---------------------------------------------------------------- 内部

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(String method, String path, Object body, int timeoutSec) {
        if (!enabled()) {
            throw new ApiException("查验服务没有配置（app.audit-base-url）", 503);
        }
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(
                            URI.create(props.getAuditBaseUrl() + path))
                    .timeout(Duration.ofSeconds(timeoutSec));
            if ("POST".equals(method)) {
                String payload = json.writeValueAsString(body == null ? Map.of() : body);
                b.header("Content-Type", "application/json; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
            } else {
                b.GET();
            }
            HttpResponse<String> r = http.send(b.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Map<String, Object> data = json.readValue(r.body(), Map.class);
            if (r.statusCode() != 200 || !Boolean.TRUE.equals(data.get("ok"))) {
                throw new ApiException(String.valueOf(
                        data.getOrDefault("error", "查验服务返回异常")), r.statusCode());
            }
            return data;
        } catch (ApiException e) {
            throw e;
        } catch (java.net.ConnectException e) {
            throw new ApiException("连不上查验服务，请确认 工具/查验服务.py 已经启动", 503);
        } catch (Exception e) {
            log.error("调用查验服务失败", e);
            throw new ApiException("查验服务异常：" + e.getClass().getSimpleName(), 500);
        }
    }

    public static Map<String, Object> emptyState() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("running", false);
        s.put("total", 0);
        s.put("done", 0);
        s.put("ok", 0);
        s.put("fail", 0);
        s.put("skip", 0);
        s.put("current", "");
        s.put("log", List.of());
        return s;
    }
}
