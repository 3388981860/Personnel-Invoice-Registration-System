package com.xinyu.invoice.auth;

import com.xinyu.invoice.common.ApiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 功能权限。
 *
 * 角色只有两种（管理员 / 普通用户），但「能用哪些功能」按人配：
 * 管理员永远全开；普通用户看自己被勾了哪几项。
 */
public final class Perm {

    /** 功能清单：key → 中文名（前端菜单和处理都按这个走） */
    public static final Map<String, String> ALL = new LinkedHashMap<>();

    static {
        ALL.put("contracts", "合同台账（含新建项目、改金额、开票回款、上传扫描件）");
        ALL.put("review", "审核报销单（通过 / 退回）");
        ALL.put("audit", "发票查验");
        ALL.put("export", "导出 Excel");
        ALL.put("stats", "数据总览");
        ALL.put("users", "用户管理");
        ALL.put("logs", "操作日志");
        ALL.put("tools", "工具箱维护（增删改工具卡片）");
        ALL.put("notice", "发布公告");
        ALL.put("attendance", "考勤看板");
    }

    /** 普通用户的默认权限：保持现在能看到的东西 */
    public static final List<String> DEFAULT = List.of("contracts");

    private Perm() {
    }

    public static Set<String> keys() {
        return ALL.keySet();
    }

    /** 把库里存的 JSON 数组解析成字符串列表，坏数据一律当空 */
    public static List<String> parse(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        String s = String.valueOf(raw).trim();
        if (s.isEmpty() || "null".equals(s)) {
            return out;
        }
        if (s.startsWith("[")) {
            for (String part : s.substring(1, s.length() - 1).split(",")) {
                String k = part.trim().replace("\"", "");
                if (ALL.containsKey(k)) {
                    out.add(k);
                }
            }
        } else {
            for (String part : s.split(",")) {
                String k = part.trim();
                if (ALL.containsKey(k)) {
                    out.add(k);
                }
            }
        }
        return out;
    }

    /** 存成 JSON 数组字符串 */
    public static String serialize(List<String> keys) {
        StringBuilder sb = new StringBuilder("[");
        for (String k : keys) {
            if (!ALL.containsKey(k)) {
                continue;
            }
            if (sb.length() > 1) {
                sb.append(",");
            }
            sb.append('"').append(k).append('"');
        }
        return sb.append(']').toString();
    }

    /** 这个人有哪些权限（管理员 = 全部） */
    public static List<String> of(Map<String, Object> user) {
        if (user == null) {
            return List.of();
        }
        if (SessionService.isAdmin(user)) {
            return new ArrayList<>(ALL.keySet());
        }
        // null = 从来没设过，用默认（合同台账）；
        // "[]" = 管理员明确清空了，就是什么都不能用
        Object raw = user.get("perms");
        if (raw == null) {
            return new ArrayList<>(DEFAULT);
        }
        return parse(raw);
    }

    public static boolean has(Map<String, Object> user, String key) {
        return of(user).contains(key);
    }

    public static void require(Map<String, Object> user, String key) {
        if (!has(user, key)) {
            String name = ALL.getOrDefault(key, key);
            throw new ApiException("没有「" + name + "」权限，请联系管理员开通", 403);
        }
    }
}
