package com.zihan.zhiwei.ai.safety;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Prompt 注入防护（D22）。
 * <p>
 * FIX-12: 从"17 条正则单层防御"升级为三层纵深防御：
 * 第 0 层 归一化 —— 打掉编码混淆
 * 第 1 层 正则   —— 已知模式快路径
 * 第 2 层 LLM 裁判 —— 灰区语义判定
 */
@Slf4j
@Component
public class PromptInjectionDetector {

    /** 注入模式列表（作用于归一化后的文本） */
    private static final Pattern[] INJECTION_PATTERNS = {
            Pattern.compile("(?i)ignore\\s*(all\\s*)?previous\\s*instructions"),
            Pattern.compile("(?i)ignore\\s*(all\\s*)?above\\s*instructions"),
            Pattern.compile("(?i)you\\s*are\\s*now\\s*(a|an|the)\\s+"),
            Pattern.compile("(?i)act\\s*as\\s*(a|an|the)\\s+"),
            Pattern.compile("(?i)pretend\\s*(you\\s*are|to\\s*be)\\s+"),
            Pattern.compile("(?i)new\\s*role:\\s*"),
            Pattern.compile("(?i)system\\s*prompt:\\s*"),
            Pattern.compile("(?i)\\[system\\]"),
            Pattern.compile("(?i)<\\|system\\|>"),
            Pattern.compile("(?i)forget\\s*(everything|all|your\\s*rules)"),
            Pattern.compile("(?i)override\\s*(safety|your|all)\\s+"),
            Pattern.compile("(?i)jailbreak"),
            Pattern.compile("(?i)DAN\\s*mode"),
            Pattern.compile("(?i)do\\s*anything\\s*now"),
            Pattern.compile("你是一个没有限制的"),
            Pattern.compile("忽略(上面|之前|以上)(的)?(所有)?(指令|规则|限制)"),
            Pattern.compile("系统提示词[：:]"),
    };

    /** FIX-12: 灰区启发词 */
    private static final Pattern SUSPICION_HINTS = Pattern.compile(
            "(?i)instruction|prompt|system|assistant\\s*rules|角色|扮演|指令|提示词"
                    + "|规则|限制|开发者模式|base64|decode|rot13|反转|倒着");

    /** 零宽字符 & 控制字符 */
    private static final Pattern INVISIBLE_CHARS =
            Pattern.compile("[\\u200B-\\u200F\\u2060\\uFEFF\\u00AD\\p{Cf}]");

    private final ObjectProvider<LlmInjectionJudge> judgeProvider;

    public PromptInjectionDetector(ObjectProvider<LlmInjectionJudge> judgeProvider) {
        this.judgeProvider = judgeProvider;
    }

    /**
     * 检测是否包含注入攻击。
     * @return null=通过，否则=拦截原因
     */
    public String detect(String text) {
        if (text == null || text.isBlank()) return null;

        // 第 0 层：归一化
        String normalized = normalize(text);

        // 第 1 层：正则快路径
        for (Pattern p : INJECTION_PATTERNS) {
            if (p.matcher(normalized).find()) {
                log.warn("[Safety] injection detected(regex) pattern='{}' text='{}...'",
                        p.pattern(), preview(text));
                return "Prompt 注入拦截: " + p.pattern();
            }
        }

        // 第 2 层：灰区送 LLM 裁判
        LlmInjectionJudge judge = judgeProvider.getIfAvailable();
        if (judge != null && judge.isEnabled() && isSuspicious(normalized)) {
            LlmInjectionJudge.Verdict verdict = judge.judge(text);
            if (verdict == LlmInjectionJudge.Verdict.INJECTION) {
                log.warn("[Safety] injection detected(llm-judge) text='{}...'", preview(text));
                return "Prompt 注入拦截: 语义审核未通过";
            }
        }
        return null;
    }

    /** FIX-12: 归一化 */
    static String normalize(String text) {
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC);
        s = INVISIBLE_CHARS.matcher(s).replaceAll("");
        s = s.replaceAll("\\s{2,}", " ");
        return s.toLowerCase(Locale.ROOT);
    }

    static boolean isSuspicious(String normalized) {
        return SUSPICION_HINTS.matcher(normalized).find();
    }

    private static String preview(String text) {
        return text.substring(0, Math.min(80, text.length()));
    }
}
