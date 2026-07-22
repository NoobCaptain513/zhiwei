package com.zihan.zhiwei.controller;

import com.zihan.zhiwei.ai.rag.AiRagService;
import com.zihan.zhiwei.ai.rag.PgVectorKnowledgeRepository;
import com.zihan.zhiwei.ai.rag.dto.RagHit;
import com.zihan.zhiwei.common.Result;
import com.zihan.zhiwei.pojo.dto.RagSearchRequest;
import com.zihan.zhiwei.pojo.dto.RagSearchResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/rag")
@Tag(name = "RAG 知识检索")
@RequiredArgsConstructor
public class RagController {

    private final AiRagService aiRagService;
    private final PgVectorKnowledgeRepository repository;

    @PostMapping("/index")
    @Operation(summary = "写入一条知识片段")
    public Result<Map<String, Object>> index(@Valid @RequestBody IndexRequest request) {
        long id = aiRagService.upsertChunk(
                request.documentId(), request.sourceId(), request.title(), request.content());
        return Result.ok(Map.of("id", id, "total", aiRagService.count()));
    }

    @PostMapping("/search")
    @Operation(summary = "RAG 检索")
    public Result<RagSearchResponse> search(@Valid @RequestBody RagSearchRequest request) {
        int topK = request.topK() == null ? 5 : request.topK();
        int candidateK = request.candidateK() == null ? 20 : request.candidateK();
        List<RagHit> hits = aiRagService.search(request.query(), topK, candidateK);
        List<RagSearchResponse.Item> items = new ArrayList<>();
        for (RagHit hit : hits) {
            items.add(new RagSearchResponse.Item(
                    hit.chunk().id(), hit.chunk().documentId(), hit.chunk().sourceId(),
                    hit.chunk().title(), hit.chunk().content(),
                    hit.vectorScore(), hit.lexicalScore(), hit.finalScore()));
        }
        return Result.ok(new RagSearchResponse(request.query(), topK, candidateK, items));
    }

    @PostMapping("/rebuild")
    @Operation(summary = "按 documentId 重建")
    public Result<Map<String, Object>> rebuild(@Valid @RequestBody RebuildRequest request) {
        if (request.documentId() != null) {
            repository.deleteByDocumentId(request.documentId());
        }
        List<Long> ids = new ArrayList<>();
        for (RebuildChunk chunk : request.chunks()) {
            long id = aiRagService.upsertChunk(
                    request.documentId(), chunk.sourceId(), chunk.title(), chunk.content());
            ids.add(id);
        }
        return Result.ok(Map.of(
                "documentId", request.documentId() == null ? -1 : request.documentId(),
                "rebuilt", ids.size(), "ids", ids, "total", aiRagService.count()));
    }

    public record IndexRequest(Long documentId, String sourceId, String title, @NotBlank String content) {}
    public record RebuildRequest(Long documentId, List<RebuildChunk> chunks) {}
    public record RebuildChunk(String sourceId, String title, @NotBlank String content) {}
}
