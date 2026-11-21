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
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 调用 Python 解析小服务（工具/解析服务.py）。
 *
 * 为什么不在 Java 里重写：PDF/OFD 的字段提取依赖 PyMuPDF、二维码解码和
 * 一堆针对票面版式的规则，重写风险远大于收益。保持一个 HTTP 边界最稳。
 */
@Service
public class ParseService {

    private static final Logger log = LoggerFactory.getLogger(ParseService.class);

    private final AppProps props;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    public ParseService(AppProps props, ObjectMapper json) {
        this.props = props;
        this.json = json;
    }

    public boolean enabled() {
        return props.getParseBaseUrl() != null && !props.getParseBaseUrl().isBlank();
    }

    public boolean healthy() {
        if (!enabled()) {
            return false;
        }
        try {
            HttpResponse<String> r = http.send(
                    HttpRequest.newBuilder(URI.create(props.getParseBaseUrl() + "/_health"))
                            .timeout(Duration.ofSeconds(3)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return r.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /** 解析一个文件，返回字段表 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> parse(Path file) {
        if (!enabled()) {
            throw new ApiException("解析服务没有配置（app.parse-base-url）", 503);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("path", file.toAbsolutePath().toString());
        payload.put("name", file.getFileName().toString());

        try {
            HttpResponse<String> r = http.send(
                    HttpRequest.newBuilder(URI.create(props.getParseBaseUrl() + "/parse"))
                            .timeout(Duration.ofSeconds(120))
                            .header("Content-Type", "application/json; charset=utf-8")
                            .POST(HttpRequest.BodyPublishers.ofString(
                                    json.writeValueAsString(payload), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            Map<String, Object> body = json.readValue(r.body(), Map.class);
            if (r.statusCode() != 200 || !Boolean.TRUE.equals(body.get("ok"))) {
                throw new ApiException("识别失败：" + body.getOrDefault("error", "未知原因"), 500);
            }
            Object fields = body.get("fields");
            return fields instanceof Map ? (Map<String, Object>) fields : new LinkedHashMap<>();
        } catch (ApiException e) {
            throw e;
        } catch (java.net.ConnectException e) {
            throw new ApiException("连不上解析服务，请确认 工具/解析服务.py 已经启动", 503);
        } catch (Exception e) {
            log.error("调用解析服务失败", e);
            throw new ApiException("识别失败：" + e.getClass().getSimpleName(), 500);
        }
    }
}
