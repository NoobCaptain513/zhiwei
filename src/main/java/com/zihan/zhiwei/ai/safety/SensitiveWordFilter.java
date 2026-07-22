package com.zihan.zhiwei.ai.safety;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 敏感词过滤器（D22）。
 * 支持：精确匹配 + 正则模式。
 */
@Slf4j
@Component
public class SensitiveWordFilter {

    /** 内置敏感词（可扩展为配置文件） */
    private static final Set<String> BUILTIN_BLOCKED = Set.of(
            "内部密码", "数据库密码", "root密码", "admin密码",
            "secret_key", "private_key", "ssh私钥",
            "DROP TABLE", "DELETE FROM", "TRUNCATE"
    );

    /** 正则模式：信用卡号、手机号、身份证号 */
    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("\\b\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}\\b"),  // 信用卡
            Pattern.compile("\\b1[3-9]\\d{9}\\b"),                                   // 手机号
            Pattern.compile("\\b\\d{17}[\\dXx]\\b")                                   // 身份证
    );

    @Value("${zhiwei.ai.safety.extra-blocked-words:}")
    private List<String> extraBlockedWords;

    /**
     * 检查是否包含敏感内容。
     * @return null=通过，否则=被拦截的原因
     */
    public String check(String text) {
        if (text == null || text.isBlank()) return null;

        String upper = text.toUpperCase();

        // 精确匹配
        Set<String> blocked = new java.util.HashSet<>(BUILTIN_BLOCKED);
        if (extraBlockedWords != null) {
            blocked.addAll(extraBlockedWords.stream()
                    .filter(w -> w != null && !w.isBlank())
                    .collect(Collectors.toList()));
        }

        for (String word : blocked) {
            if (upper.contains(word.toUpperCase())) {
                String reason = "敏感词拦截: [" + word + "]";
                log.warn("[Safety] blocked word='{}' in text='{}...'", word, text.substring(0, Math.min(50, text.length())));
                return reason;
            }
        }

        // 正则匹配
        for (Pattern p : PATTERNS) {
            if (p.matcher(text).find()) {
                String reason = "敏感内容拦截: 匹配模式 " + p.pattern();
                log.warn("[Safety] pattern matched in text='{}...'", text.substring(0, Math.min(50, text.length())));
                return reason;
            }
        }

        return null;
    }
}