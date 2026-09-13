-- Agent memory foundation. MySQL is the authoritative store; audit/version rows are append-only.
CREATE TABLE conversation_memory_summary (
 id BIGINT NOT NULL AUTO_INCREMENT, conversation_id BIGINT NOT NULL, user_id VARCHAR(64) NOT NULL,
 summary TEXT NOT NULL, open_loops JSON NULL, decisions JSON NULL, entities JSON NULL,
 covered_through_message_id BIGINT NOT NULL, source_message_count INT NOT NULL, version BIGINT NOT NULL DEFAULT 1,
 status VARCHAR(16) NOT NULL, expires_at DATETIME NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, deleted_at DATETIME NULL,
 is_deleted TINYINT NOT NULL DEFAULT 0, PRIMARY KEY(id), UNIQUE KEY uk_summary_conversation(conversation_id),
 KEY idx_summary_owner(user_id, conversation_id), KEY idx_summary_expires(expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE agent_checkpoint (
 id BIGINT NOT NULL AUTO_INCREMENT, run_id VARCHAR(64) NOT NULL, conversation_id BIGINT NOT NULL,
 user_id VARCHAR(64) NOT NULL, checkpoint_type VARCHAR(32) NOT NULL, node_name VARCHAR(64) NOT NULL,
 state_json JSON NOT NULL, status VARCHAR(16) NOT NULL, sequence_no INT NOT NULL, version BIGINT NOT NULL DEFAULT 1,
 resume_after DATETIME NULL, error_code VARCHAR(64) NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, expires_at DATETIME NULL,
 deleted_at DATETIME NULL, is_deleted TINYINT NOT NULL DEFAULT 0, PRIMARY KEY(id),
 UNIQUE KEY uk_checkpoint_run_sequence(run_id, sequence_no),
 KEY idx_checkpoint_owner_state(user_id, conversation_id, status, updated_at), KEY idx_checkpoint_expires(expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE memory_fact (
 id BIGINT NOT NULL AUTO_INCREMENT, user_id VARCHAR(64) NOT NULL, namespace VARCHAR(64) NOT NULL,
 subject VARCHAR(255) NOT NULL, predicate VARCHAR(128) NOT NULL, identity_hash CHAR(64) NOT NULL,
 current_version_id BIGINT NULL, state VARCHAR(16) NOT NULL, version BIGINT NOT NULL DEFAULT 1,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 deleted_at DATETIME NULL, is_deleted TINYINT NOT NULL DEFAULT 0, PRIMARY KEY(id),
 UNIQUE KEY uk_fact_identity_hash(identity_hash), KEY idx_fact_owner_lookup(user_id, namespace, subject, predicate, state)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE memory_fact_version (
 id BIGINT NOT NULL AUTO_INCREMENT, fact_id BIGINT NOT NULL, version_no INT NOT NULL,
 value_json JSON NOT NULL, normalized_value_hash CHAR(64) NOT NULL, source_type VARCHAR(24) NOT NULL,
 source_ref VARCHAR(255) NULL, confidence DECIMAL(5,4) NULL, valid_from DATETIME NULL, valid_to DATETIME NULL,
 recorded_at DATETIME NOT NULL, created_by VARCHAR(64) NOT NULL, change_reason VARCHAR(500) NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(id),
 UNIQUE KEY uk_fact_version(fact_id, version_no), KEY idx_fact_version_hash(fact_id, normalized_value_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE memory_conflict (
 id BIGINT NOT NULL AUTO_INCREMENT, fact_id BIGINT NOT NULL, base_version_id BIGINT NULL,
 candidate_version_id BIGINT NOT NULL, type VARCHAR(32) NOT NULL, status VARCHAR(16) NOT NULL,
 resolution VARCHAR(24) NULL, resolved_version_id BIGINT NULL, resolved_by VARCHAR(64) NULL,
 reason VARCHAR(500) NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, resolved_at DATETIME NULL,
 PRIMARY KEY(id), KEY idx_conflict_fact_status(fact_id, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE memory_audit_event (
 event_id CHAR(36) NOT NULL, user_id VARCHAR(64) NOT NULL, resource_type VARCHAR(32) NOT NULL,
 resource_id VARCHAR(64) NOT NULL, action VARCHAR(32) NOT NULL, old_version BIGINT NULL, new_version BIGINT NULL,
 actor_type VARCHAR(16) NOT NULL, actor_id VARCHAR(64) NOT NULL, request_id VARCHAR(64) NULL,
 reason VARCHAR(500) NULL, payload_hash CHAR(64) NULL, metadata_json JSON NULL,
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(event_id),
 KEY idx_audit_owner_resource(user_id, resource_type, resource_id, created_at, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE memory_forget_job (
 job_id CHAR(36) NOT NULL, user_id VARCHAR(64) NOT NULL, scope_type VARCHAR(24) NOT NULL,
 scope_id VARCHAR(255) NULL, status VARCHAR(16) NOT NULL, requested_by VARCHAR(64) NOT NULL,
 reason VARCHAR(500) NOT NULL, requested_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 completed_at DATETIME NULL, result_json JSON NULL, PRIMARY KEY(job_id),
 KEY idx_forget_owner_status(user_id, status, requested_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
