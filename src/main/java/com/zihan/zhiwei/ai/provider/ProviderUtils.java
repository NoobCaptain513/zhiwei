package com.zihan.zhiwei.ai.provider;

/**
 * P3-20 修复：Provider 公共工具方法。
 * 统一管理 trimSlash / safeBody / safeMsg，消除多处重复定义。
 */
public final class ProviderUtils {

    private ProviderUtils() {}

    public static String trimSlash(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public static String safeBody(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 300 ? body : body.substring(0, 300);
    }

    public static String safeMsg(Exception e) {
        String msg = e.getMessage();
        return msg == null ? e.getClass().getSimpleName()
                : msg.substring(0, Math.min(msg.length(), 120));
    }
}
