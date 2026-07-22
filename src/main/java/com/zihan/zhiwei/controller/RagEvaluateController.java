package com.zihan.zhiwei.controller;

import com.zihan.zhiwei.ai.rag.RagEvaluateService;
import com.zihan.zhiwei.common.Result;
import com.zihan.zhiwei.pojo.dto.RagEvaluateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/rag")
@Tag(name = "RAG 质量评估")
@RequiredArgsConstructor
public class RagEvaluateController {

    private final RagEvaluateService ragEvaluateService;

    @PostMapping("/evaluate")
    @Operation(summary = "RAG 质量评估（召回率 / 平均排名 / A/B 对比）")
    public Result<com.zihan.zhiwei.pojo.dto.RagEvaluateResponse> evaluate(
            @Valid @RequestBody RagEvaluateRequest request) {

        List<RagEvaluateService.EvalItem> items = request.queries().stream()
                .map(q -> new RagEvaluateService.EvalItem(q.query(), q.expectedSourceId()))
                .toList();

        int topK = request.topK() == null ? 5 : request.topK();
        int candidateK = request.candidateK() == null ? 20 : request.candidateK();

        var result = ragEvaluateService.evaluate(
                request.dataset() != null ? request.dataset() : "default",
                items, topK, candidateK);

        if (request.abB() != null) {
            var comparison = ragEvaluateService.compareAb(
                    items,
                    request.dataset() != null ? request.dataset() : "A", topK, candidateK,
                    request.abB().variantName(),
                    request.abB().topK(), request.abB().candidateK());
            result.setAbComparison(comparison);
        }

        return Result.ok(result);
    }
}
