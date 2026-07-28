package com.zihan.zhiwei.ai.knowledge;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * FIX-9: 统一 token 计数器。
 * 主路径：jtokkit cl100k_base BPE。初始化失败时回退字符启发式。
 */
@Slf4j
@Component
public class TokenCounter {

    private final Encoding encoding;

    public TokenCounter() {
        Encoding enc = null;
        try {
            enc = Encodings.newDefaultEncodingRegistry()
                    .getEncoding(EncodingType.CL100K_BASE);
            log.info("[TokenCounter] jtokkit cl100k_base loaded");
        } catch (Throwable t) {
            log.warn("[TokenCounter] jtokkit init failed, fallback to heuristic: {}", t.getMessage());
        }
        this.encoding = enc;
    }

    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        if (encoding != null) {
            try {
                return encoding.countTokens(text);
            } catch (Exception e) {
                log.debug("[TokenCounter] countTokens failed, heuristic fallback: {}", e.getMessage());
            }
        }
        return heuristic(text);
    }

    /** 文本平均"每 token 字符数"，用于把 token 窗口换算成字符窗口 */
    public double charsPerToken(String text) {
        if (text == null || text.isEmpty()) {
            return 2.0;
        }
        int tokens = count(text);
        return tokens <= 0 ? 2.0 : (double) text.length() / tokens;
    }

    static int heuristic(String text) {
        int cn = 0, en = 0, other = 0;
        for (char c : text.toCharArray()) {
            if (c > 0x4E00 && c < 0x9FFF || c > 0x3400 && c < 0x4DBF) {
                cn++;
            } else if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') {
                en++;
            } else if (!Character.isWhitespace(c)) {
                other++;
            }
        }
        return cn + (int) Math.ceil(en / 4.0) + (int) Math.ceil(other / 4.0);
    }
}
