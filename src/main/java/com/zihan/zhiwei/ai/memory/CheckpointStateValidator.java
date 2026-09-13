package com.zihan.zhiwei.ai.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.pojo.dto.memory.CheckpointState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class CheckpointStateValidator {
    private static final Set<String> SENSITIVE = Set.of("apikey", "password", "authorization", "token");
    private final ObjectMapper objectMapper;
    private final MemoryProperties properties;

    public String validate(CheckpointState state) {
        if (state == null) throw new IllegalArgumentException("checkpoint state is required");
        if (state.schemaVersion() < 1) throw new IllegalArgumentException("schemaVersion must be at least 1");
        if (state.currentNode() == null || state.currentNode().isBlank()) throw new IllegalArgumentException("currentNode is required");
        try {
            JsonNode tree = objectMapper.valueToTree(state);
            rejectSensitiveKeys(tree);
            String json = objectMapper.writeValueAsString(tree);
            if (json.getBytes(StandardCharsets.UTF_8).length > properties.getCheckpointMaxBytes()) {
                throw new IllegalArgumentException("checkpoint state exceeds size limit");
            }
            return json;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("checkpoint state is not serializable", e);
        }
    }

    private void rejectSensitiveKeys(JsonNode node) {
        if (node == null) return;
        if (node.isObject()) {
            Iterator<String> names = node.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                String normalized = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                if (SENSITIVE.stream().anyMatch(s -> normalized.equals(s) || normalized.endsWith(s))) {
                    throw new IllegalArgumentException("checkpoint state contains sensitive key: " + name);
                }
                rejectSensitiveKeys(node.get(name));
            }
        } else if (node.isArray()) {
            node.forEach(this::rejectSensitiveKeys);
        }
    }
}
