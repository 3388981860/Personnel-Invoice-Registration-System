package com.xinyu.invoice.web;

import com.xinyu.invoice.config.AppProps;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 前端静态资源（dist/assets 下的 js/css/图片）。
 *
 * 之前这个职责在 AssetController 里，后来加了「固定资产」功能占了同名类，
 * 把它覆盖掉了，导致 /assets/*.js 全部 404。这里单独拆出来，避免再撞名。
 */
@RestController
public class StaticAssetController {

    private final AppProps props;

    public StaticAssetController(AppProps props) {
        this.props = props;
    }

    @GetMapping("/assets/{file:.+}")
    public ResponseEntity<byte[]> asset(@PathVariable String file) throws IOException {
        Path dir = props.web().resolve("assets").normalize();
        Path target = dir.resolve(file).normalize();
        // 防目录穿越
        if (!target.startsWith(dir) || !Files.isRegularFile(target)) {
            return ResponseEntity.notFound().build();
        }
        String name = target.getFileName().toString().toLowerCase();
        MediaType type = MediaType.APPLICATION_OCTET_STREAM;
        if (name.endsWith(".js")) {
            type = MediaType.valueOf("application/javascript;charset=UTF-8");
        } else if (name.endsWith(".css")) {
            type = MediaType.valueOf("text/css;charset=UTF-8");
        } else if (name.endsWith(".png")) {
            type = MediaType.IMAGE_PNG;
        } else if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            type = MediaType.IMAGE_JPEG;
        } else if (name.endsWith(".svg")) {
            type = MediaType.valueOf("image/svg+xml");
        } else if (name.endsWith(".ico")) {
            type = MediaType.valueOf("image/x-icon");
        } else if (name.endsWith(".woff2")) {
            type = MediaType.valueOf("font/woff2");
        } else if (name.endsWith(".woff")) {
            type = MediaType.valueOf("font/woff");
        } else if (name.endsWith(".ttf")) {
            type = MediaType.valueOf("font/ttf");
        }
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic())
                .body(Files.readAllBytes(target));
    }

    /** 浏览器默认会来要这个 */
    @GetMapping({"/favicon.ico", "/vite.svg"})
    public ResponseEntity<byte[]> rootFiles(jakarta.servlet.http.HttpServletRequest req)
            throws IOException {
        String name = req.getRequestURI().substring(1);
        Path f = props.web().resolve(name).normalize();
        if (!f.startsWith(props.web()) || !Files.isRegularFile(f)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().body(Files.readAllBytes(f));
    }
}
