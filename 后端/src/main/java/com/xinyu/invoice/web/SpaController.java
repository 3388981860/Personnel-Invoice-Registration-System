package com.xinyu.invoice.web;

import com.xinyu.invoice.config.AppProps;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * 单页应用入口。
 *
 * 前端的路由（/entry、/ledger、/contracts…）在浏览器里刷新时会直接请求后端，
 * 后端必须把这些地址都返回 index.html，否则就是一片 {"ok":false,"error":"未知地址"}。
 *
 * 做法：列一份已知路由 + 一个「不带点、不是 api/assets」的兜底，
 * 这样以后加新页面（比如 /contracts）不用改后端也不会 404。
 */
@Controller
public class SpaController {

    /** 已知的前端路由 */
    private static final Set<String> ROUTES = Set.of(
            "login", "entry", "ledger", "pending", "contracts",
            "stats", "users", "logs", "account");

    private final AppProps props;

    public SpaController(AppProps props) {
        this.props = props;
    }

    @GetMapping({"/", "/index.html"})
    public ResponseEntity<byte[]> root() throws IOException {
        return index();
    }

    /**
     * 兜底：任何不带扩展名、不是 api/ assets/ 开头的 GET 都交给前端路由。
     * 带点的（.js/.css/.png）是静态文件，不在这里处理，免得把 404 吞掉。
     */
    @GetMapping("/{path:[^\\.]*}")
    public ResponseEntity<byte[]> spa(@PathVariable String path) throws IOException {
        if (path.startsWith("api") || path.startsWith("assets")) {
            return ResponseEntity.notFound().build();
        }
        return index();
    }

    private ResponseEntity<byte[]> index() throws IOException {
        Path file = props.web().resolve("index.html");
        if (!Files.isRegularFile(file)) {
            return ResponseEntity.status(404)
                    .contentType(MediaType.TEXT_HTML)
                    .body("<h1>前端还没构建</h1><p>请先在 前端 目录执行 npm run build</p>"
                            .getBytes(StandardCharsets.UTF_8));
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .cacheControl(CacheControl.noStore())
                .body(Files.readAllBytes(file));
    }
}
