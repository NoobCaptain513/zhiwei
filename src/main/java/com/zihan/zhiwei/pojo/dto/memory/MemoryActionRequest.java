package com.zihan.zhiwei.pojo.dto.memory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record MemoryActionRequest(
        @Size(max = 64) String userId,
        @NotBlank @Size(max = 64) String actorId,
        @NotBlank @Size(max = 500) String reason) {
}
