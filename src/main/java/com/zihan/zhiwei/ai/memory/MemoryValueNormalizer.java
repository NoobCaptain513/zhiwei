package com.zihan.zhiwei.ai.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;

@Component
@RequiredArgsConstructor
public class MemoryValueNormalizer {
    private final ObjectMapper objectMapper;

    public String canonicalJson(JsonNode value) {
        if (value == null) {
            throw new IllegalArgumentException("memory value is required");
        }
        try {
            return objectMapper.writeValueAsString(canonicalize(value));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("memory value is not serializable", e);
        }
    }

    public String sha256(JsonNode value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private JsonNode canonicalize(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            var sorted = new TreeMap<String, JsonNode>();
            value.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), entry.getValue()));
            sorted.forEach((key, child) -> result.set(key, canonicalize(child)));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            value.forEach(child -> result.add(canonicalize(child)));
            return result;
        }
        if (value.isBigDecimal() || value.isFloatingPointNumber()) {
            BigDecimal normalized = value.decimalValue().stripTrailingZeros();
            return JsonNodeFactory.instance.numberNode(normalized);
        }
        return value.deepCopy();
    }
}
