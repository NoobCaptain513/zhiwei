package com.zihan.zhiwei.controller;

import com.zihan.zhiwei.ai.knowledge.DocumentParseService;
import com.zihan.zhiwei.ai.knowledge.DocumentParser;
import com.zihan.zhiwei.ai.knowledge.SmartChunker;
import com.zihan.zhiwei.ai.knowledge.pipeline.KnowledgePipelineProducer;
import com.zihan.zhiwei.common.Result;
import com.zihan.zhiwei.mapper.KnowledgeDocumentMapper;
import com.zihan.zhiwei.pojo.entity.KnowledgeDocument;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/rag")
@Tag(name = "文档上传管道")
@RequiredArgsConstructor
public class DocumentUploadController {

    private final DocumentParseService documentParseService;
    private final DocumentParser documentParser;
    private final SmartChunker smartChunker;
    private final KnowledgeDocumentMapper documentMapper;
    private final KnowledgePipelineProducer pipelineProducer;

    @PostMapping("/upload")
    @Operation(summary = "上传文档 → document表(PENDING) → 发MQ → 异步解析+分块+入库")
    public Result<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "userId", defaultValue = "user-001") String userId) throws Exception {

        if (file.isEmpty()) {
            return Result.fail(400, "文件不能为空");
        }

        String fileName = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        String ext = DocumentParser.getExtension(fileName);
        if (!documentParser.isSupported(DocumentParser.mimeTypeFromExtension(ext))) {
            return Result.fail(400, "不支持的文件类型: " + ext + "，支持 " + DocumentParser.supportedExtensions());
        }

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setUserId(userId);
        doc.setFileName(fileName);
        doc.setFileSize(file.getSize());
        doc.setMimeType(DocumentParser.mimeTypeFromExtension(ext));
        doc.setStatus("PENDING");
        doc.setTotalChunks(0);
        doc.setIndexedChunks(0);
        doc.setCreateTime(LocalDateTime.now());
        doc.setUpdateTime(LocalDateTime.now());
        doc.setIsDeleted(0);
        documentMapper.insert(doc);

        log.info("[Upload] document created id={} file={}", doc.getId(), fileName);

        pipelineProducer.sendDocumentMessage(doc.getId(), userId, fileName);

        int totalChunks = 0;
        try {
            var parsed = documentParser.parse(file.getInputStream(), fileName);
            var chunks = smartChunker.chunk(parsed.text(), doc.getId(), fileName);
            totalChunks = chunks.size();
            doc.setTotalChunks(totalChunks);
            documentMapper.updateById(doc);
        } catch (Exception e) {
            log.warn("[Upload] pre-parse failed: {}", e.getMessage());
        }

        return Result.ok(Map.of(
                "documentId", doc.getId(),
                "fileName", fileName,
                "status", "PENDING",
                "totalChunks", totalChunks,
                "message", "文档已提交，正在异步处理"));
    }

    @PostMapping("/preview")
    @Operation(summary = "预览分块（不入库）")
    public Result<Map<String, Object>> preview(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "maxTokens", defaultValue = "512") int maxTokens) throws Exception {
        String fileName = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        DocumentParser.ParseResult parsed = documentParser.parse(file.getInputStream(), fileName);
        var chunks = smartChunker.chunk(parsed.text(), -1L, fileName);
        List<Map<String, Object>> preview = new ArrayList<>();
        for (var c : chunks) {
            preview.add(Map.of(
                    "index", c.getChunkIndex(),
                    "tokenCount", c.getTokenCount(),
                    "preview", c.getContent().length() > 200 ? c.getContent().substring(0, 200) + "..." : c.getContent()));
        }
        return Result.ok(Map.of(
                "fileName", fileName,
                "textLength", parsed.text().length(),
                "chunkCount", preview.size(),
                "maxTokens", maxTokens,
                "overlapTokens", 64,
                "chunks", preview));
    }

    @PostMapping("/parse-text")
    @Operation(summary = "纯文本直接分块 + 入库")
    public Result<Map<String, Object>> parseText(@Valid @RequestBody ParseTextRequest request) {
        int indexed = documentParseService.processText(
                request.content(), request.documentId(), request.sourceName());
        return Result.ok(Map.of(
                "sourceName", request.sourceName(),
                "documentId", request.documentId() == null ? -1 : request.documentId(),
                "indexed", indexed));
    }

    @GetMapping("/document/{id}")
    @Operation(summary = "查询文档处理状态")
    public Result<Map<String, Object>> documentStatus(@PathVariable Long id) {
        KnowledgeDocument doc = documentMapper.selectById(id);
        if (doc == null) {
            return Result.fail(404, "文档不存在");
        }
        return Result.ok(Map.of(
                "id", doc.getId(), "fileName", doc.getFileName(),
                "status", doc.getStatus(), "totalChunks", doc.getTotalChunks(),
                "indexedChunks", doc.getIndexedChunks(), "errorMessage", doc.getErrorMessage(),
                "createTime", doc.getCreateTime(), "updateTime", doc.getUpdateTime()));
    }

    @GetMapping("/documents")
    @Operation(summary = "文档列表")
    public Result<List<Map<String, Object>>> documents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var docs = documentMapper.selectList(null);
        List<Map<String, Object>> list = new ArrayList<>();
        for (KnowledgeDocument d : docs) {
            list.add(Map.of(
                    "id", d.getId(), "fileName", d.getFileName(),
                    "status", d.getStatus(), "totalChunks", d.getTotalChunks(),
                    "indexedChunks", d.getIndexedChunks(), "createTime", d.getCreateTime()));
        }
        return Result.ok(list);
    }

    public record ParseTextRequest(Long documentId, String sourceName, @NotBlank String content) {}
}
