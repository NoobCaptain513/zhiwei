package com.zihan.zhiwei.ai.provider;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Provider 公共工具方法。
 * 统一管理 trimSlash / safeBody / safeMsg，消除多处重复定义。
 * <p>
 * FIX-3: 新增 isRetryableOnSameProvider() —— 重试前先判定异常类别。
 */
public final class ProviderUtils {

    private ProviderUtils() {}

    /** 命中即"不可在同 Provider 重试"的 HTTP 状态码 */
    private static final Pattern NON_RETRYABLE_STATUS =
            Pattern.compile("\\b(400|401|403|404|413|422|429)\\b");

    /** 命中即"可重试"的 HTTP 状态码（服务端瞬时故障） */
    private static final Pattern RETRYABLE_STATUS =
            Pattern.compile("\\b(408|500|502|503|504)\\b");

    /** 消息关键词兜底：鉴权/配额/参数类错误，重试无意义 */
    private static final Pattern NON_RETRYABLE_KEYWORDS = Pattern.compile(
            "invalid[ _]?api[ _]?key|unauthorized|forbidden|invalid[ _]?parameter"
                    + "|quota|insufficient|rate[ _]?limit|model[ _]?not[ _]?found",
            Pattern.CASE_INSENSITIVE);

    private static final int MAX_CAUSE_DEPTH = 10;

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

    /**
     * FIX-3: 判定异常是否值得在【同一个 Provider】上重试。
     * 返回 false 只表示"跳过同 Provider 重试"，降级链仍会继续。
     */
    public static boolean isRetryableOnSameProvider(Throwable e) {
        int depth = 0;
        for (Throwable t = e; t != null && depth < MAX_CAUSE_DEPTH; t = t.getCause(), depth++) {
            // 1. 类型判定：典型瞬时网络故障
            if (t instanceof SocketTimeoutException
                    || t instanceof HttpTimeoutException
                    || t instanceof HttpConnectTimeoutException
                    || t instanceof ConnectException
                    || t instanceof UnknownHostException
                    || t instanceof SocketException
                    || t instanceof TimeoutException
                    || t instanceof java.io.InterruptedIOException) {
                return true;
            }

            // 2/3. 消息判定
            String msg = t.getMessage();
            if (msg != null && !msg.isBlank()) {
                String m = msg.toLowerCase(Locale.ROOT);
                if (NON_RETRYABLE_STATUS.matcher(m).find()
                        || NON_RETRYABLE_KEYWORDS.matcher(m).find()) {
                    return false;
                }
                if (RETRYABLE_STATUS.matcher(m).find()
                        || m.contains("timeout") || m.contains("timed out")
                        || m.contains("connection reset") || m.contains("broken pipe")) {
                    return true;
                }
            }

            if (t.getCause() == t) {
                break;
            }
        }
        return true;
    }
}
