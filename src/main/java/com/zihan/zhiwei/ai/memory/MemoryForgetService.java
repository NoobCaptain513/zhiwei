package com.zihan.zhiwei.ai.memory;

import com.zihan.zhiwei.ai.memory.model.MemoryForgetJob;
import com.zihan.zhiwei.pojo.dto.memory.MemoryForgetRequest;

import java.util.Optional;

public interface MemoryForgetService {
    MemoryForgetJob create(String userId, MemoryForgetRequest request, String idempotencyKey, String requestId);

    Optional<MemoryForgetJob> get(String userId, String jobId);

    PurgeResult purgeExpired(int batchSize);

    record PurgeResult(int summaries, int checkpoints, int facts) {
        public int total() { return summaries + checkpoints + facts; }
    }
}
