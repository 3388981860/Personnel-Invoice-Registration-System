package com.xinyu.invoice.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * 大文件（扫描件 PDF）的取件工具。
 *
 * 和原来的 writeAllBytes 比，三点不同：
 * 1. 边读边发（FileSystemResource），不再把十几兆整个读进内存；
 * 2. 带 ETag / Last-Modified，浏览器第二次打开直接 304，不重新下载；
 * 3. 支持 Range 断点续传，PDF 阅读器可以边下边看前几页。
 */
public final class FileServe {

    /** 单次最多吐 8MB，防止 Range 请求一次要整个文件 */
    private static final long MAX_CHUNK = 8L * 1024 * 1024;

    private FileServe() {
    }

    public static ResponseEntity<?> serve(Path file, MediaType type, String fileName,
                                          HttpServletRequest req) throws IOException {
        long size = Files.size(file);
        long lastModified = Files.getLastModifiedTime(file).toMillis();
        String etag = "\"" + Long.toHexString(size) + "-" + Long.toHexString(lastModified) + "\"";

        // 浏览器带了同样的 ETag：内容没变，直接 304
        String ifNoneMatch = req.getHeader(HttpHeaders.IF_NONE_MATCH);
        if (ifNoneMatch != null && ifNoneMatch.contains(etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .lastModified(lastModified)
                    .build();
        }

        String disposition = fileName == null || fileName.isBlank() ? "inline"
                : "inline; filename*=UTF-8''" + fileName;

        // 断点续传
        String range = req.getHeader(HttpHeaders.RANGE);
        if (range != null && range.startsWith("bytes=") && size > 0) {
            long start = 0;
            long end = size - 1;
            String spec = range.substring("bytes=".length()).trim();
            int dash = spec.indexOf('-');
            try {
                if (dash > 0) {
                    start = Long.parseLong(spec.substring(0, dash).trim());
                    if (dash < spec.length() - 1) {
                        end = Long.parseLong(spec.substring(dash + 1).trim());
                    }
                } else if (dash == 0 && spec.length() > 1) {
                    // bytes=-500 → 最后 500 字节
                    long tail = Long.parseLong(spec.substring(1).trim());
                    start = Math.max(0, size - tail);
                }
            } catch (NumberFormatException e) {
                start = 0;
                end = size - 1;
            }
            if (start < 0) {
                start = 0;
            }
            if (end >= size) {
                end = size - 1;
            }
            if (start > end || start >= size) {
                return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                        .header(HttpHeaders.CONTENT_RANGE, "bytes */" + size)
                        .build();
            }
            long len = Math.min(end - start + 1, MAX_CHUNK);
            end = start + len - 1;
            byte[] chunk = new byte[(int) len];
            try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                raf.seek(start);
                raf.readFully(chunk);
            }
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .contentType(type)
                    .contentLength(len)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + size)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                    .eTag(etag)
                    .lastModified(lastModified)
                    .body(chunk);
        }

        // 整份：交给 Spring 边读边发
        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .contentType(type)
                .contentLength(size)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .eTag(etag)
                .lastModified(Instant.ofEpochMilli(lastModified))
                .cacheControl(org.springframework.http.CacheControl
                        .maxAge(Duration.ofHours(6)).cachePrivate())
                .body(resource);
    }
}
