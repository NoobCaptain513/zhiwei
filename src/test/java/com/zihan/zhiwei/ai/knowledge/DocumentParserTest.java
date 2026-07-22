package com.zihan.zhiwei.ai.knowledge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentParser 文档解析器测试")
class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    @Test
    @DisplayName("纯文本直接解析 → 返回 ParseResult")
    void shouldParsePlainText() {
        String content = "Hello World\n\n这是第二段";
        var result = parser.parse(
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                "test.txt");

        assertThat(result.fileName()).isEqualTo("test.txt");
        assertThat(result.text()).isEqualTo("Hello World\n\n这是第二段");
        assertThat(result.mimeType()).isNotNull();
        assertThat(result.sizeBytes()).isGreaterThan(0);
    }

    @Test
    @DisplayName("parseText 跳过 Tika，直接构造 ParseResult")
    void shouldParseTextDirectly() {
        var result = parser.parseText("直接文本内容", "readme.md");

        assertThat(result.text()).isEqualTo("直接文本内容");
        assertThat(result.mimeType()).isEqualTo("text/plain");
        assertThat(result.fileName()).isEqualTo("readme.md");
    }

    @Test
    @DisplayName("null InputStream → 抛异常")
    void shouldThrowForNullStream() {
        assertThatThrownBy(() -> parser.parse(null, "test.pdf"))
                .isInstanceOf(com.zihan.zhiwei.common.exception.BusinessException.class)
                .hasMessageContaining("文件流不能为空");
    }

    @Test
    @DisplayName("空内容 → 抛异常")
    void shouldThrowForEmptyContent() {
        // 空字节数组 → Tika 检测到 application/octet-stream → 不支持 → 抛异常
        assertThatThrownBy(() -> parser.parse(
                new ByteArrayInputStream(new byte[0]), "empty.pdf"))
                .isInstanceOf(com.zihan.zhiwei.common.exception.BusinessException.class);
    }

    @Test
    @DisplayName("parseText 空内容 → 抛异常")
    void shouldThrowForEmptyText() {
        assertThatThrownBy(() -> parser.parseText("", "a.txt"))
                .isInstanceOf(com.zihan.zhiwei.common.exception.BusinessException.class)
                .hasMessageContaining("内容不能为空");
    }

    @Nested
    @DisplayName("MIME 类型判断")
    class MimeTypeTests {

        @Test
        @DisplayName("PDF → 支持")
        void shouldSupportPdf() {
            assertThat(parser.isSupported("application/pdf")).isTrue();
        }

        @Test
        @DisplayName("DOCX → 支持")
        void shouldSupportDocx() {
            assertThat(parser.isSupported("application/vnd.openxmlformats-officedocument.wordprocessingml.document")).isTrue();
        }

        @Test
        @DisplayName("Word → 支持")
        void shouldSupportWord() {
            assertThat(parser.isSupported("application/msword")).isTrue();
        }

        @Test
        @DisplayName("Markdown → 支持")
        void shouldSupportMarkdown() {
            assertThat(parser.isSupported("text/markdown")).isTrue();
        }

        @Test
        @DisplayName("TXT → 支持")
        void shouldSupportText() {
            assertThat(parser.isSupported("text/plain")).isTrue();
        }

        @Test
        @DisplayName("HTML → 支持")
        void shouldSupportHtml() {
            assertThat(parser.isSupported("text/html")).isTrue();
        }

        @Test
        @DisplayName("图片 → 不支持")
        void shouldNotSupportImage() {
            assertThat(parser.isSupported("image/png")).isFalse();
        }

        @Test
        @DisplayName("视频 → 不支持")
        void shouldNotSupportVideo() {
            assertThat(parser.isSupported("video/mp4")).isFalse();
        }

        @Test
        @DisplayName("null → 不支持")
        void shouldNotSupportNull() {
            assertThat(parser.isSupported(null)).isFalse();
        }

        @Test
        @DisplayName("扩展名列表")
        void shouldHaveExtensions() {
            String exts = DocumentParser.supportedExtensions();
            assertThat(exts).contains(".pdf");
            assertThat(exts).contains(".docx");
            assertThat(exts).contains(".md");
            assertThat(exts).contains(".txt");
            assertThat(exts).contains(".html");
        }
    }
}
