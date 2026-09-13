package com.zihan.zhiwei.ai.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zihan.zhiwei.ai.memory.model.AgentCheckpoint;
import com.zihan.zhiwei.ai.memory.model.MemoryFact;
import com.zihan.zhiwei.pojo.dto.memory.CheckpointState;
import com.zihan.zhiwei.pojo.dto.memory.MemoryFactRequest;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void enumsUseStrictNamedJsonValues() throws Exception {
        assertThat(mapper.readValue("\"PAUSED\"", AgentCheckpoint.Status.class))
                .isEqualTo(AgentCheckpoint.Status.PAUSED);
        assertThatThrownBy(() -> mapper.readValue("\"paused\"", AgentCheckpoint.Status.class))
                .hasMessageContaining("paused");
    }

    @Test
    void factRequestEnforcesRequiredFieldsAndLengths() {
        var validator = Validation.buildDefaultValidatorFactory().getValidator();
        var invalid = new MemoryFactRequest("u", "x".repeat(65), "", "p", null,
                MemoryFact.SourceType.USER, null, null, null, null, "actor", "reason", null);
        assertThat(validator.validate(invalid)).extracting(v -> v.getPropertyPath().toString())
                .contains("namespace", "subject", "value");
    }

    @Test
    void checkpointStateAlwaysCarriesPositiveSchemaVersion() {
        var validator = Validation.buildDefaultValidatorFactory().getValidator();
        var state = new CheckpointState(0, "node", null, null, null, null, null);
        assertThat(validator.validate(state)).extracting(v -> v.getPropertyPath().toString())
                .contains("schemaVersion");
    }

    @Test
    void propertiesHaveSafeDefaults() {
        var properties = new MemoryProperties();
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getSummary().getMessageThreshold()).isEqualTo(8);
        assertThat(properties.getSummary().getTokenThreshold()).isEqualTo(3000);
        assertThat(properties.getSummary().getRecentMessagesToKeep()).isEqualTo(4);
        assertThat(properties.getFactLimit()).isEqualTo(10);
        assertThat(properties.getCheckpointTtl()).isEqualTo(java.time.Duration.ofDays(7));
        assertThat(properties.getSoftDeleteRetention()).isEqualTo(java.time.Duration.ofDays(30));
    }
}
