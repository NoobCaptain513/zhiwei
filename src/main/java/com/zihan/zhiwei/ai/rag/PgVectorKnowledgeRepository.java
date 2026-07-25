package com.zihan.zhiwei.ai.rag;

import com.zihan.zhiwei.ai.rag.dto.KnowledgeChunk;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * D10: pgvector 知识库仓储（余弦检索 + 关键词检索）
 */
@Slf4j
@Repository
@ConditionalOnProperty(prefix = "zhiwei.ai.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PgVectorKnowledgeRepository {

    private final JdbcTemplate jdbcTemplate;

    public PgVectorKnowledgeRepository(@Qualifier("pgvectorJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long insert(Long documentId,
                       String sourceId,
                       String title,
                       String content,
                       float[] embedding,
                       int tokenCount) {
        return insert(documentId, sourceId, title, content, embedding, tokenCount, "embedding");
    }

    /**
     * D23: 支持指定向量列名（embedding / embedding_ollama）
     */
    public long insert(Long documentId,
                       String sourceId,
                       String title,
                       String content,
                       float[] embedding,
                       int tokenCount,
                       String column) {
        String sql = """
                INSERT INTO ai_knowledge_chunk
                    (document_id, source_id, title, content, %s, token_count)
                VALUES (?, ?, ?, ?, ?::vector, ?)
                RETURNING id
                """.formatted(column);
        Long id = jdbcTemplate.queryForObject(
                sql,
                Long.class,
                documentId,
                sourceId,
                title,
                content,
                toVectorLiteral(embedding),
                tokenCount
        );
        return id == null ? 0L : id;
    }

    /** 余弦相似度召回：score = 1 - cosine_distance */
    public List<ScoredChunk> searchByCosine(float[] queryEmbedding, int candidateK) {
        return searchByCosine(queryEmbedding, candidateK, "embedding");
    }

    /**
     * D23: 支持指定向量列名（embedding / embedding_ollama）
     */
    public List<ScoredChunk> searchByCosine(float[] queryEmbedding, int candidateK, String column) {
        int k = Math.max(1, candidateK);
        String sql = """
                SELECT id, document_id, source_id, title, content, token_count, create_time,
                       (1 - (%s <=> ?::vector)) AS vector_score
                FROM ai_knowledge_chunk
                ORDER BY %s <=> ?::vector
                LIMIT ?
                """.formatted(column, column);
        String literal = toVectorLiteral(queryEmbedding);
        return jdbcTemplate.query(sql, new ScoredChunkMapper(), literal, literal, k);
    }

    /**
     * D30: 关键词检索。
     * 查询分词后用 ILIKE 匹配 content，按匹配词占比打分。
     */
    public List<ScoredChunk> searchByKeyword(String query, int limit) {
        Set<String> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            return List.of();
        }

        List<String> searchTokens = tokens.stream()
                .filter(t -> t.length() >= 2)
                .sorted(Comparator.comparingInt(String::length).reversed())
                .limit(5)
                .toList();

        if (searchTokens.isEmpty()) {
            return List.of();
        }

        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<>();
        for (int i = 0; i < searchTokens.size(); i++) {
            if (i > 0) where.append(" OR ");
            where.append("content ILIKE ?");
            params.add("%" + searchTokens.get(i) + "%");
        }

        String sql = "SELECT id, document_id, source_id, title, content, token_count, create_time"
                + " FROM ai_knowledge_chunk WHERE " + where
                + " ORDER BY id DESC LIMIT ?";
        params.add(limit);

        RowMapper<ScoredChunk> keywordMapper = (rs, rowNum) -> {
            Timestamp ts = rs.getTimestamp("create_time");
            KnowledgeChunk chunk = new KnowledgeChunk(
                    rs.getLong("id"),
                    (Long) rs.getObject("document_id"),
                    rs.getString("source_id"),
                    rs.getString("title"),
                    rs.getString("content"),
                    rs.getInt("token_count"),
                    ts == null ? null : ts.toLocalDateTime()
            );
            String content = rs.getString("content");
            int matched = 0;
            if (content != null) {
                String lowerContent = content.toLowerCase(Locale.ROOT);
                for (String t : searchTokens) {
                    if (lowerContent.contains(t.toLowerCase(Locale.ROOT))) {
                        matched++;
                    }
                }
            }
            double score = (double) matched / searchTokens.size();
            return new ScoredChunk(chunk, score);
        };
        return jdbcTemplate.query(sql, keywordMapper, params.toArray());
    }

    public long count() {
        Long n = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM ai_knowledge_chunk", Long.class);
        return n == null ? 0L : n;
    }

    public void deleteByDocumentId(Long documentId) {
        jdbcTemplate.update("DELETE FROM ai_knowledge_chunk WHERE document_id = ?", documentId);
    }

    public static String toVectorLiteral(float[] embedding) {
        if (embedding == null || embedding.length == 0) {
            throw new IllegalArgumentException("embedding empty");
        }
        StringBuilder sb = new StringBuilder(embedding.length * 8);
        sb.append('[');
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(String.format(Locale.ROOT, "%.8f", embedding[i]));
        }
        sb.append(']');
        return sb.toString();
    }

    private static final Pattern TOKEN_SPLIT =
            Pattern.compile("[\\s\\p{Punct}，。！？；：、\"\"''（）【】《》]+");

    static Set<String> tokenize(String text) {
        Set<String> set = new HashSet<>();
        if (text == null || text.isBlank()) {
            return set;
        }
        String normalized = text.toLowerCase(Locale.ROOT).trim();
        String compact = TOKEN_SPLIT.matcher(normalized).replaceAll("");
        if (compact.length() >= 2) {
            for (int i = 0; i < compact.length() - 1; i++) {
                set.add(compact.substring(i, i + 2));
            }
        } else if (!compact.isEmpty()) {
            set.add(compact);
        }
        for (String part : TOKEN_SPLIT.split(normalized)) {
            if (part != null && !part.isBlank()) {
                set.add(part);
            }
        }
        return set;
    }

    public record ScoredChunk(KnowledgeChunk chunk, double vectorScore) {}

    private static final class ScoredChunkMapper implements RowMapper<ScoredChunk> {
        @Override
        public ScoredChunk mapRow(ResultSet rs, int rowNum) throws SQLException {
            Timestamp ts = rs.getTimestamp("create_time");
            KnowledgeChunk chunk = new KnowledgeChunk(
                    rs.getLong("id"),
                    (Long) rs.getObject("document_id"),
                    rs.getString("source_id"),
                    rs.getString("title"),
                    rs.getString("content"),
                    rs.getInt("token_count"),
                    ts == null ? null : ts.toLocalDateTime()
            );
            return new ScoredChunk(chunk, rs.getDouble("vector_score"));
        }
    }
}