package com.xinyu.invoice.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 登录校验：只拦 /api/**。
 * 页面由前端单页应用自己判断（它调 /api/me 拿到 401 就跳登录页），
 * 所以这里不重定向页面，避免和前端路由打架。
 */
@Component
public class AuthFilter extends OncePerRequestFilter {

    private final SessionService sessions;
    private final ObjectMapper json;

    public AuthFilter(SessionService sessions, ObjectMapper json) {
        this.sessions = sessions;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp,
                                    FilterChain chain) throws ServletException, IOException {
        String token = cookie(req, "sid");
        Map<String, Object> user = sessions.resolve(token);

        req.setAttribute("user", user);
        req.setAttribute("token", token);

        String path = req.getRequestURI();
        // 钉钉扫码登录：拿授权地址、接收回调，这两个必须在未登录时也能访问
        boolean open = path.equals("/api/login") || path.equals("/api/register")
                || path.equals("/api/ping") || path.equals("/_health")
                || path.equals("/api/dingtalk/login-url")
                || path.equals("/api/dingtalk/callback");

        if (path.startsWith("/api/") && !open && user == null) {
            resp.setStatus(401);
            resp.setContentType("application/json; charset=utf-8");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", false);
            body.put("error", "请先登录");
            resp.getWriter().write(json.writeValueAsString(body));
            return;
        }
        chain.doFilter(req, resp);
    }

    public static String cookie(HttpServletRequest req, String name) {
        Cookie[] cookies = req.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) {
                return c.getValue();
            }
        }
        return null;
    }
}
