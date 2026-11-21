package com.xinyu.invoice.web;

import com.xinyu.invoice.config.AppProps;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 单页应用入口。
 *
 * 根路径交给 Spring 的资源处理器时走不到自定义解析器（空路径会被提前拦下），
 * 所以这里显式返回 index.html。前端路由（/entry、/ledger…）由 WebConfig 的兜底处理。
 */
@Controller
public class SpaController {

    private final AppProps props;

    public SpaController(AppProps props) {
        this.props = props;
    }

    @GetMapping({"/", "/index.html", "/login", "/entry", "/ledger", "/pending", "/stats", "/users", "/logs"})
    public ResponseEntity<byte[]> index() throws IOException {
        Path file = props.web().resolve("index.html");
        if (!Files.isRegularFile(file)) {
            return ResponseEntity.status(404)
                    .contentType(MediaType.TEXT_HTML)
                    .body("<h1>前端还没构建</h1><p>请先在 前端 目录执行 npm run build</p>"
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .cacheControl(CacheControl.noStore())
                .body(Files.readAllBytes(file));
    }
}
