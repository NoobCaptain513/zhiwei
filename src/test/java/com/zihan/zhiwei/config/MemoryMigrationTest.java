package com.zihan.zhiwei.config;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

class MemoryMigrationTest {
    @Test
    void v4DefinesAllMemoryTablesAndConcurrencyConstraints() throws Exception {
        Path migration = Path.of("src/main/resources/db/migration/V4__agent_memory.sql");
        assertThat(migration).exists();
        String sql = Files.readString(migration).toLowerCase();
        assertThat(sql).contains("create table conversation_memory_summary")
                .contains("create table agent_checkpoint")
                .contains("create table memory_fact")
                .contains("create table memory_fact_version")
                .contains("create table memory_conflict")
                .contains("create table memory_audit_event")
                .contains("create table memory_forget_job")
                .contains("unique key uk_summary_conversation")
                .contains("unique key uk_checkpoint_run_sequence")
                .contains("unique key uk_fact_identity_hash")
                .contains("unique key uk_fact_version")
                .contains("state_json json not null")
                .contains("value_json json not null")
                .contains("metadata_json json");
    }
}
