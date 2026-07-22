package com.zihan.zhiwei.pojo.dto;

import jakarta.validation.constraints.NotBlank;

public record RagSearchRequest(
        @NotBlank String query,
        Integer topK,
        Integer candidateK
) {}
