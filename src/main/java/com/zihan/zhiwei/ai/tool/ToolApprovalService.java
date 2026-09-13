package com.zihan.zhiwei.ai.tool;

import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Issues short-lived, user-bound approvals for side-effecting tool calls. */
@Component
public class ToolApprovalService {

    private final long ttlMillis;
    private final Map<String, Approval> approvals = new ConcurrentHashMap<>();

    @Autowired
    public ToolApprovalService(@Value("${zhiwei.ai.agent.reliability.tool.approval-ttl-seconds:300}") long ttlSeconds) {
        this(Duration.ofSeconds(Math.max(1L, ttlSeconds)));
    }

    public ToolApprovalService(Duration ttl) {
        this.ttlMillis = Math.max(1L, (ttl == null ? Duration.ofMinutes(5) : ttl).toMillis());
    }

    public String issue(String userId, String toolName, Map<String, Object> params) {
        String id = UUID.randomUUID().toString();
        approvals.put(id, new Approval(userId, toolName, fingerprint(params), System.currentTimeMillis() + ttlMillis));
        return id;
    }

    public boolean validate(String approvalId, String userId, String toolName, Map<String, Object> params) {
        if (approvalId == null || approvalId.isBlank()) {
            return false;
        }
        Approval approval = approvals.get(approvalId);
        if (approval == null || approval.expiresAt < System.currentTimeMillis()) {
            approvals.remove(approvalId);
            return false;
        }
        return approval.userId.equals(userId)
                && approval.toolName.equals(toolName)
                && approval.paramsFingerprint.equals(fingerprint(params));
    }

    private static String fingerprint(Map<String, Object> params) {
        return new TreeMap<>(params == null ? Map.of() : params).toString();
    }

    private record Approval(String userId, String toolName, String paramsFingerprint, long expiresAt) {}
}
