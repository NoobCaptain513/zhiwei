package com.zihan.zhiwei.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import java.util.HashMap;
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
        Map<String, Object> result = handleSingleUpload(file, userId);
        if (Boolean.FALSE.equals(result.get("success"))) {
            return Result.fail(400, String.valueOf(result.get("message")));
        }
        return Result.ok(result);
    }

    @PostMapping("/upload/batch")
    @Operation(summary = "批量上传文档 → 逐个 document表(PENDING) → 发MQ → 异步解析+分块+入库")
    public Result<Map<String, Object>> uploadBatch(
            @RequestParam("files") MultipartFile[] files,
            @RequestParam(value = "userId", defaultValue = "user-001") String userId) {

        if (files == null || files.length == 0) {
            return Result.fail(400, "文件列表不能为空");
        }

        List<Map<String, Object>> results = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;
        for (MultipartFile file : files) {
            String fileName = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
            try {
                if (file.isEmpty()) {
                    results.add(Map.of("fileName", fileName, "success", false, "message", "文件为空"));
                    failCount++;
                    continue;
                }
                Map<String, Object> r = handleSingleUpload(file, userId);
                results.add(r);
                if (Boolean.TRUE.equals(r.get("success"))) {
                    successCount++;
                } else {
                    failCount++;
                }
            } catch (Exception e) {
                log.warn("[UploadBatch] failed file={}: {}", fileName, e.getMessage());
                results.add(Map.of("fileName", fileName, "success", false,
                        "message", e.getMessage() == null ? "上传失败" : e.getMessage()));
                failCount++;
            }
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("total", files.length);
        resp.put("successCount", successCount);
        resp.put("failCount", failCount);
        resp.put("results", results);
        return Result.ok(resp);
    }

    /**
     * 单文件上传核心逻辑：入库 PENDING → 发 MQ → 预解析统计分块数。
     * 返回 Map 含 success 标记，供单文件与批量接口共用。
     */
    private Map<String, Object> handleSingleUpload(MultipartFile file, String userId) throws Exception {
        String fileName = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        String ext = DocumentParser.getExtension(fileName);
        if (!documentParser.isSupported(DocumentParser.mimeTypeFromExtension(ext))) {
            Map<String, Object> fail = new HashMap<>();
            fail.put("fileName", fileName);
            fail.put("success", false);
            fail.put("message", "不支持的文件类型: " + ext + "，支持 " + DocumentParser.supportedExtensions());
            return fail;
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

        // P0-2 修复：读取文件字节携带在 MQ 消息中，使 Consumer 可执行异步处理
        byte[] fileBytes = file.getBytes();
        pipelineProducer.sendDocumentMessage(doc.getId(), userId, fileName, fileBytes);

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

        Map<String, Object> ok = new HashMap<>();
        ok.put("documentId", doc.getId());
        ok.put("fileName", fileName);
        ok.put("success", true);
        ok.put("status", "PENDING");
        ok.put("totalChunks", totalChunks);
        ok.put("message", "文档已提交，正在异步处理");
        return ok;
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
        Map<String, Object> map = new HashMap<>();
        map.put("id", doc.getId());
        map.put("fileName", doc.getFileName());
        map.put("status", doc.getStatus());
        map.put("totalChunks", doc.getTotalChunks());
        map.put("indexedChunks", doc.getIndexedChunks());
        map.put("errorMessage", doc.getErrorMessage());
        map.put("createTime", doc.getCreateTime());
        map.put("updateTime", doc.getUpdateTime());
        return Result.ok(map);
    }

    @GetMapping("/documents")
    @Operation(summary = "文档列表（P2-19 修复：接入 MyBatis-Plus 真正分页）")
    public Result<Map<String, Object>> documents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<KnowledgeDocument> p = new Page<>(page + 1, size); // MyBatis-Plus 页码从 1 开始
        var pageResult = documentMapper.selectPage(p,
                new LambdaQueryWrapper<KnowledgeDocument>()
                        .orderByDesc(KnowledgeDocument::getCreateTime));
        List<Map<String, Object>> list = new ArrayList<>();
        for (KnowledgeDocument d : pageResult.getRecords()) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", d.getId());
            map.put("fileName", d.getFileName());
            map.put("status", d.getStatus());
            map.put("totalChunks", d.getTotalChunks());
            map.put("indexedChunks", d.getIndexedChunks());
            map.put("createTime", d.getCreateTime());
            list.add(map);
        }
        return Result.ok(Map.of(
                "total", pageResult.getTotal(),
                "page", page,
                "size", size,
                "records", list));
    }

    public record ParseTextRequest(Long documentId, String sourceName, @NotBlank String content) {}
}
