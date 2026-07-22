package com.zihan.zhiwei.ai.knowledge;

import com.zihan.zhiwei.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.mime.MimeType;
import org.apache.tika.mime.MimeTypes;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 文档解析器（Apache Tika）。
 * 支持：PDF / Word（docx）/ Markdown / TXT / HTML
 * 输出：纯文本内容 + 原始文件名
 */
@Slf4j
@Component
public class DocumentParser {

    private final Tika tika = new Tika();

    /**
     * 解析结果
     */
    public record ParseResult(String fileName, String text, String mimeType, long sizeBytes) {}

    /**
     * 从 InputStream 解析文档为纯文本。
     *
     * @param inputStream 文件流（调用方负责关闭）
     * @param fileName    原始文件名（用于记录和 MIME 判断）
     */
    public ParseResult parse(InputStream inputStream, String fileName) {
        if (inputStream == null) {
            throw new BusinessException("文件流不能为空");
        }
        try {
            // Tika 自动检测 MIME
            byte[] bytes = inputStream.readAllBytes();
            String mimeType = tika.detect(bytes, fileName);

            log.info("[DocParser] file={} mimeType={} size={}B", fileName, mimeType, bytes.length);

            // 是否支持的类型
            if (!isSupported(mimeType)) {
                throw new BusinessException("不支持的文件类型: " + mimeType + "（" + fileName + "）");
            }

            // 提取文本
            String text = tika.parseToString(new java.io.ByteArrayInputStream(bytes));
            if (text == null || text.isBlank()) {
                throw new BusinessException("文件内容为空: " + fileName);
            }

            return new ParseResult(fileName, text.trim(), mimeType, bytes.length);
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException("文件解析失败: " + e.getMessage());
        } catch (Exception e) {
            throw new BusinessException("文件解析异常: " + e.getMessage());
        }
    }

    /**
     * 从纯文本字符串直接构造（Markdown / TXT 可跳过 Tika）。
     */
    public ParseResult parseText(String content, String fileName) {
        if (content == null || content.isBlank()) {
            throw new BusinessException("内容不能为空");
        }
        return new ParseResult(fileName, content.trim(), "text/plain", content.getBytes(StandardCharsets.UTF_8).length);
    }

    /**
     * 判断是否为支持的 MIME 类型。
     */
    public boolean isSupported(String mimeType) {
        if (mimeType == null) return false;
        String m = mimeType.toLowerCase(Locale.ROOT);
        return m.contains("pdf")
                || m.contains("word") || m.contains("docx") || m.contains("msword")
                || m.contains("markdown") || m.contains("text/plain")
                || m.contains("html");
    }

    /**
     * 获取支持的文件扩展名列表。
     */
    public static String supportedExtensions() {
        return ".pdf, .docx, .doc, .md, .txt, .html";
    }

    public static String getExtension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(dot).toLowerCase() : "";
    }

    public static String mimeTypeFromExtension(String ext) {
        return switch (ext) {
            case ".pdf"  -> "application/pdf";
            case ".docx", ".doc" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case ".md"   -> "text/markdown";
            case ".txt"  -> "text/plain";
            case ".html" -> "text/html";
            default -> "";
        };
    }
}