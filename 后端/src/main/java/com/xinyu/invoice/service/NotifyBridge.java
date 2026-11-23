package com.xinyu.invoice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 站内消息 + 钉钉推送的桥。
 *
 * 业务代码只管调这里，钉钉没配、发送失败都不影响主流程。
 */
@Service
public class NotifyBridge {

    private static final Logger log = LoggerFactory.getLogger(NotifyBridge.class);

    private final DingTalkService ding;

    @Value("${app.dingtalk.web-base:}")
    private String webBase;

    public NotifyBridge(DingTalkService ding) {
        this.ding = ding;
    }

    /**
     * 给几个人推钉钉。
     *
     * @param userIds 系统用户 id
     * @param path    点开消息后跳转到系统里的哪个页面，比如 /ledger
     */
    public void push(List<Long> userIds, String title, String text, String path) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        try {
            String url = webBase.isBlank() ? null
                    : webBase.replaceAll("/+$", "") + (path == null ? "" : path);
            ding.sendToUsers(userIds, title, text, url);
        } catch (Exception e) {
            log.warn("钉钉推送失败（不影响业务）：{}", e.getMessage());
        }
    }

    /** 给所有管理员推 */
    public void pushAdmins(List<Long> adminIds, String title, String text, String path) {
        push(adminIds, title, text, path);
    }
}
