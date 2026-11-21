package com.xinyu.invoice.web;

import com.xinyu.invoice.config.AppProps;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 前端静态资源（dist/assets 下的 js、css、字体、图片）。
 *
 * 没有用 Spring 的 ResourceHandler：它对根路径和中文路径的处理不好把控，
 * 直接读文件反而更简单、行为也更好预测。文件名带 hash，可以放心长缓存。
 */
@Controller
public class AssetController {

    private static final Map<String, MediaType> TYPES = Map.ofEntries(
            Map.entry("js", MediaType.valueOf("text/javascript;charset=UTF-8")),
            Map.entry("mjs", MediaType.valueOf("text/javascript;charset=UTF-8")),
            Map.entry("css", MediaType.valueOf("text/css;charset=UTF-8")),
            Map.entry("json", MediaType.APPLICATION_JSON),
            Map.entry("svg", MediaType.valueOf("image/svg+xml")),
            Map.entry("png", MediaType.IMAGE_PNG),
            Map.entry("jpg", MediaType.IMAGE_JPEG),
            Map.entry("jpeg", MediaType.IMAGE_JPEG),
            Map.entry("gif", MediaType.IMAGE_GIF),
            Map.entry("ico", MediaType.valueOf("image/x-icon")),
            Map.entry("woff", MediaType.valueOf("font/woff")),
            Map.entry("woff2", MediaType.valueOf("font/woff2")),
            Map.entry("ttf", MediaType.valueOf("font/ttf"))
    );

    private final AppProps props;

    public AssetController(AppProps props) {
        this.props = props;
    }

    @GetMapping("/assets/{*path}")
    public ResponseEntity<byte[]> asset(@PathVariable("path") String path) throws IOException {
        return serve("assets/" + path, true);
    }

    @GetMapping({"/favicon.ico", "/logo.svg"})
    public ResponseEntity<byte[]> root(jakarta.servlet.http.HttpServletRequest req)
            throws IOException {
        return serve(req.getRequestURI().substring(1), false);
    }

    private ResponseEntity<byte[]> serve(String rel, boolean longCache) throws IOException {
        Path root = props.web().toAbsolutePath().normalize();
        Path file = root.resolve(rel.replace("\\", "/").replaceFirst("^/+", "")).normalize();
        // 防目录穿越
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
        MediaType type = TYPES.getOrDefault(ext, MediaType.APPLICATION_OCTET_STREAM);

        ResponseEntity.BodyBuilder b = ResponseEntity.ok().contentType(type);
        if (longCache) {
            b.cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePublic().immutable());
        } else {
            b.cacheControl(CacheControl.noCache());
        }
        return b.body(Files.readAllBytes(file));
    }
}
