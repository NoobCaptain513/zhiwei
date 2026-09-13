package com.zihan.zhiwei.controller;

import com.zihan.zhiwei.ai.rag.RagEvaluateService;
import com.zihan.zhiwei.ai.rag.agentic.model.RetrievalStrategy;
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
    @Operation(summary = "RAG 质量评估（HitRate / Recall / Precision / MRR / nDCG）")
    public Result<com.zihan.zhiwei.pojo.dto.RagEvaluateResponse> evaluate(
            @Valid @RequestBody RagEvaluateRequest request) {

        List<RagEvaluateService.EvalItem> items = request.queries().stream()
                .map(this::toEvalItem)
                .toList();

        int topK = request.topK() == null ? 5 : request.topK();
        int candidateK = request.candidateK() == null ? 20 : request.candidateK();

        String dataset = request.dataset() != null ? request.dataset() : "default";
        if (request.abB() != null) {
            RetrievalStrategy strategyA = request.strategy() == null
                    ? RetrievalStrategy.HYBRID : request.strategy();
            RetrievalStrategy strategyB = request.abB().strategy() == null
                    ? RetrievalStrategy.HYBRID : request.abB().strategy();
            var result = ragEvaluateService.compareStrategies(
                    dataset,
                    items,
                    new RagEvaluateService.EvaluationVariant(
                            dataset, strategyA, topK, candidateK),
                    new RagEvaluateService.EvaluationVariant(
                            request.abB().variantName(), strategyB,
                            request.abB().topK(), request.abB().candidateK()));
            return Result.ok(result);
        }

        var result = request.strategy() == null
                ? ragEvaluateService.evaluate(dataset, items, topK, candidateK)
                : ragEvaluateService.evaluate(dataset, items, request.strategy().name(),
                        request.strategy(), topK, candidateK);
        return Result.ok(result);
    }

    private RagEvaluateService.EvalItem toEvalItem(RagEvaluateRequest.EvalQuery query) {
        if (query.relevantDocuments() == null || query.relevantDocuments().isEmpty()) {
            return new RagEvaluateService.EvalItem(query.query(), query.expectedSourceId());
        }
        List<RagEvaluateService.RelevantDocument> judgments = query.relevantDocuments().stream()
                .map(document -> new RagEvaluateService.RelevantDocument(
                        document.sourceId(), document.relevance()))
                .toList();
        return new RagEvaluateService.EvalItem(query.caseId(), query.query(), judgments);
    }
}
