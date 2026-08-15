package com.zihan.zhiwei.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zihan.zhiwei.pojo.entity.AiUsageLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * AI 用量日志 Mapper。
 */
@Mapper
public interface AiUsageLogMapper extends BaseMapper<AiUsageLog> {

    /** 全局合计（排除 FAILED） */
    @Select("SELECT COUNT(*) AS requests, " +
            "COALESCE(SUM(total_tokens), 0) AS tokens, " +
            "COALESCE(SUM(cost), 0) AS cost " +
            "FROM ai_usage_log WHERE status != 'FAILED'")
    Map<String, Object> selectTotalStats();

    /** 按 Provider 聚合（排除 FAILED，按请求量降序） */
    @Select("SELECT provider, COUNT(*) AS requests, " +
            "COALESCE(SUM(total_tokens), 0) AS tokens, " +
            "COALESCE(SUM(cost), 0) AS cost, " +
            "COALESCE(AVG(latency_ms), 0) AS avgLatency " +
            "FROM ai_usage_log WHERE status != 'FAILED' " +
            "GROUP BY provider ORDER BY requests DESC")
    List<Map<String, Object>> selectStatsByProvider();

    /** 按天聚合（最近 N 天，含所有 status） */
    @Select("SELECT DATE(create_time) AS day, COUNT(*) AS requests, " +
            "COALESCE(SUM(total_tokens), 0) AS tokens, " +
            "COALESCE(SUM(cost), 0) AS cost " +
            "FROM ai_usage_log " +
            "GROUP BY DATE(create_time) ORDER BY day DESC LIMIT #{days}")
    List<Map<String, Object>> selectStatsByDay(@Param("days") int days);
}