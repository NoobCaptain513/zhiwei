package com.zihan.zhiwei.pojo.dto.memory;
import com.zihan.zhiwei.ai.memory.model.MemoryFact;
import com.zihan.zhiwei.ai.memory.model.MemoryFactVersion;
public record MemoryFactResponse(MemoryFact fact, MemoryFactVersion currentVersion) {}