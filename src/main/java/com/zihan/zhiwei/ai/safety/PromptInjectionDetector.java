package com.zihan.zhiwei.ai.safety;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Prompt 注入防护（D22）。
 * 检测常见注入模式：system prompt 篡改、角色切换、越权指令等。
 */
@Slf4j
@Component
public class PromptInjectionDetector {

    /** 注入模式列表 */
    private static final Pattern[] INJECTION_PATTERNS = {
            Pattern.compile("(?i)ignore\\s+(all\\s+)?previous\\s+instructions"),
            Pattern.compile("(?i)ignore\\s+(all\\s+)?above\\s+instructions"),
            Pattern.compile("(?i)you\\s+are\\s+now\\s+(a|an|the)\\s+"),
            Pattern.compile("(?i)act\\s+as\\s+(a|an|the)\\s+"),
            Pattern.compile("(?i)pretend\\s+(you\\s+are|to\\s+be)\\s+"),
            Pattern.compile("(?i)new\\s+role:\\s*"),
            Pattern.compile("(?i)system\\s*prompt:\\s*"),
            Pattern.compile("(?i)\\[system\\]"),
            Pattern.compile("(?i)<\\|system\\|>"),
            Pattern.compile("(?i)forget\\s+(everything|all|your\\s+rules)"),
            Pattern.compile("(?i)override\\s+(safety|your|all)\\s+"),
            Pattern.compile("(?i)jailbreak"),
            Pattern.compile("(?i)DAN\\s+mode"),
            Pattern.compile("(?i)do\\s+anything\\s+now"),
            Pattern.compile("(?i)你是一个没有限制的"),
            Pattern.compile("(?i)忽略(上面|之前|以上)(的)?(所有)?(指令|规则|限制)"),
            Pattern.compile("(?i)系统提示词[：:]"),
    };

    /**
     * 检测是否包含注入攻击。
     * @return null=通过，否则=拦截原因
     */
    public String detect(String text) {
        if (text == null || text.isBlank()) return null;

        for (Pattern p : INJECTION_PATTERNS) {
            if (p.matcher(text).find()) {
                String reason = "Prompt 注入拦截: " + p.pattern();
                log.warn("[Safety] injection detected pattern='{}' text='{}...'",
                        p.pattern(), text.substring(0, Math.min(80, text.length())));
                return reason;
            }
        }
        return null;
    }
}