package com.zihan.zhiwei.ai.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.ArrayList;
import java.util.List;

/**
 * D12: 工具调用结果收集器。
 * Agent 循环中每调一次工具就把结果收集起来，最后一次性拼入 prompt / 卡片。
 *
 * 修复 P0-1：使用 @RequestScope 确保每次 HTTP 请求拥有独立的实例，
 * 避免多用户并发时共享同一 results 列表导致数据泄漏。
 */
@Slf4j
@Component
@RequestScope
public class ToolResultCollector {

    private final List<ToolCallResult> results = new ArrayList<>();

    /** 追加一条工具结果 */
    public void add(ToolCallResult result) {
        results.add(result);
        log.debug("[ToolCollect] tool={} success={} dataLen={}",
                result.getToolName(), result.isSuccess(),
                result.getData() == null ? 0 : result.getData().length());
    }

    /** 批量追加 */
    public void addAll(List<ToolCallResult> results) {
        for (ToolCallResult r : results) {
            add(r);
        }
    }

    /** 获取所有结果 */
    public List<ToolCallResult> getAll() {
        return List.copyOf(results);
    }

    /** 清空（新会话开始前调用） */
    public void clear() {
        results.clear();
    }

    /** 拼成一段上下文文本，注入 prompt（供模型总结） */
    public String toContextBlock() {
        if (results.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("以下是运维工具调用的结果，请基于这些数据回答用户问题：\n\n");
        for (ToolCallResult r : results) {
            sb.append("## ").append(r.getToolName()).append("\n");
            if (r.isSuccess()) {
                sb.append(r.getData());
            } else {
                sb.append("调用失败: ").append(r.getError());
            }
            sb.append("\n\n");
        }
        return sb.toString();
    }

    /** 获取成功的工具数量 */
    public int successCount() {
        return (int) results.stream().filter(ToolCallResult::isSuccess).count();
    }

    /** 获取失败的工具数量 */
    public int failCount() {
        return (int) results.stream().filter(r -> !r.isSuccess()).count();
    }
}