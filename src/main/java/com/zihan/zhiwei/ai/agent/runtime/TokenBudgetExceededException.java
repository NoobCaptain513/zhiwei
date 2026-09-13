package com.zihan.zhiwei.ai.agent.runtime;

public class TokenBudgetExceededException extends RuntimeException {

    private final String reason;

    public TokenBudgetExceededException(String reason, String message) {
        super(reason + ": " + message);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
