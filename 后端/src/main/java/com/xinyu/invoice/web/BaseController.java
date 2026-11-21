package com.xinyu.invoice.web;

import com.xinyu.invoice.auth.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

/** 控制器基类：取当前用户、判断管理员 */
public abstract class BaseController {

    @Autowired
    protected SessionService sessions;

    @Autowired
    protected JdbcTemplate jdbc;

    @SuppressWarnings("unchecked")
    protected Map<String, Object> user(HttpServletRequest req) {
        return (Map<String, Object>) req.getAttribute("user");
    }

    protected Map<String, Object> requireUser(HttpServletRequest req) {
        return sessions.require(user(req));
    }

    protected Map<String, Object> requireAdmin(HttpServletRequest req) {
        Map<String, Object> u = requireUser(req);
        if (!SessionService.isAdmin(u)) {
            throw new com.xinyu.invoice.common.ApiException("这个操作需要管理员权限", 403);
        }
        return u;
    }

    /** 要某个功能权限，没有就 403 */
    protected Map<String, Object> requirePerm(HttpServletRequest req, String key) {
        Map<String, Object> u = requireUser(req);
        com.xinyu.invoice.auth.Perm.require(u, key);
        return u;
    }

    protected static long uid(Map<String, Object> user) {
        return SessionService.id(user);
    }
}
