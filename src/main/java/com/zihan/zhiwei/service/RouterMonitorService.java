package com.zihan.zhiwei.service;

import com.zihan.zhiwei.pojo.dto.RouterMetricsDetail;
import com.zihan.zhiwei.pojo.dto.RouterStatus;

import java.util.List;

/**
 * 路由监控服务。
 */
public interface RouterMonitorService {

    /** 全量路由状态（含熔断 + 指标 + 事件） */
    RouterStatus fullStatus();

    /** 单 Provider 详情 */
    RouterMetricsDetail providerDetail(String providerName);

    /** 所有 Provider 详情列表 */
    List<RouterMetricsDetail> allProviderDetails();
}