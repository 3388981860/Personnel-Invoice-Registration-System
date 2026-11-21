package com.xinyu.invoice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.nio.file.Paths;

/** 应用配置：路径、会话、限流、查验服务地址 */
@ConfigurationProperties(prefix = "app")
public class AppProps {

    private String baseDir = "..";
    private String webDir = "前端/dist";
    private int sessionDays = 7;
    private int loginFailLimit = 5;
    private int loginFailWindowSeconds = 300;
    private String auditBaseUrl = "";
    private String parseBaseUrl = "http://127.0.0.1:8799";

    /** 项目根目录（绝对路径） */
    public Path base() {
        return Paths.get(baseDir).toAbsolutePath().normalize();
    }

    /** 数据目录 */
    public Path dataDir() {
        return base().resolve("数据");
    }

    /** 前端构建产物目录 */
    public Path web() {
        return base().resolve(webDir).toAbsolutePath().normalize();
    }

    /** 发票存档目录 */
    public Path archiveDir() {
        return base().resolve("发票存档");
    }

    public String getBaseDir() { return baseDir; }
    public void setBaseDir(String baseDir) { this.baseDir = baseDir; }

    public String getWebDir() { return webDir; }
    public void setWebDir(String webDir) { this.webDir = webDir; }

    public int getSessionDays() { return sessionDays; }
    public void setSessionDays(int sessionDays) { this.sessionDays = sessionDays; }

    public int getLoginFailLimit() { return loginFailLimit; }
    public void setLoginFailLimit(int loginFailLimit) { this.loginFailLimit = loginFailLimit; }

    public int getLoginFailWindowSeconds() { return loginFailWindowSeconds; }
    public void setLoginFailWindowSeconds(int v) { this.loginFailWindowSeconds = v; }

    public String getParseBaseUrl() { return parseBaseUrl; }
    public void setParseBaseUrl(String parseBaseUrl) { this.parseBaseUrl = parseBaseUrl; }

    public String getAuditBaseUrl() { return auditBaseUrl; }
    public void setAuditBaseUrl(String auditBaseUrl) { this.auditBaseUrl = auditBaseUrl; }
}
