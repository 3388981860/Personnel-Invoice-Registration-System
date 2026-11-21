package com.xinyu.invoice.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/** 统一错误输出：始终是 {"ok":false,"error":"..."}，与旧版 Python 接口一致 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> onApi(ApiException e, HttpServletRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", e.getMessage());
        if (e.getField() != null) {
            body.put("field", e.getField());
        }
        return ResponseEntity.status(e.getStatus()).body(body);
    }

    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> onNoResource(Exception e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", "未知地址");
        return ResponseEntity.status(404).body(body);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Map<String, Object>> onNoHandler(NoHandlerFoundException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", "未知地址");
        return ResponseEntity.status(404).body(body);
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> onDb(DataAccessException e) {
        log.error("数据库错误", e);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", "数据库错误：" + rootMessage(e));
        return ResponseEntity.status(500).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> onAny(Exception e) {
        log.error("服务端错误", e);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("error", "服务端错误（" + e.getClass().getSimpleName() + "）");
        return ResponseEntity.status(500).body(body);
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
