package com.zihan.zhiwei.controller;

import com.zihan.zhiwei.ai.memory.*;
import com.zihan.zhiwei.ai.memory.model.AgentCheckpoint;
import com.zihan.zhiwei.ai.memory.model.MemoryAuditEvent;
import com.zihan.zhiwei.ai.memory.model.MemoryFact;
import com.zihan.zhiwei.ai.memory.model.MemoryFactVersion;
import com.zihan.zhiwei.ai.memory.model.MemoryConflict;
import com.zihan.zhiwei.ai.rag.agentic.AgenticRagOrchestrator;
import com.zihan.zhiwei.ai.rag.agentic.model.AgenticRagResult;
import com.zihan.zhiwei.common.Result;
import com.zihan.zhiwei.common.exception.PreconditionRequiredException;
import com.zihan.zhiwei.pojo.dto.memory.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/memories")
@Validated
public class MemoryController {
    private static final Pattern ETAG = Pattern.compile("\"([1-9][0-9]*)\"");

    private final MemorySummaryService summaries;
    private final CheckpointService checkpoints;
    private final MemoryFactService facts;
    private final MemoryAuditService audits;
    private final Optional<MemoryForgetService> forget;
    private final MemoryOwnerResolver owners;

    @Autowired(required = false)
    private AgenticRagOrchestrator agenticRagOrchestrator;

    public MemoryController(MemorySummaryService summaries, CheckpointService checkpoints,
                            MemoryFactService facts, MemoryAuditService audits,
                            Optional<MemoryForgetService> forget, MemoryOwnerResolver owners) {
        this.summaries = summaries;
        this.checkpoints = checkpoints;
        this.facts = facts;
        this.audits = audits;
        this.forget = forget;
        this.owners = owners;
    }

    @GetMapping("/conversations/{conversationId}/summary")
    public ResponseEntity<Result<MemorySummaryResponse>> getSummary(
            @PathVariable long conversationId, @RequestParam(required = false) String userId,
            Authentication authentication) {
        String owner = owners.resolve(userId, authentication);
        return summaries.get(owner, conversationId)
                .map(value -> versioned(Result.ok(new MemorySummaryResponse(value)), value.version(), HttpStatus.OK))
                .orElseGet(MemoryController::notFound);
    }

    @PutMapping("/conversations/{conversationId}/summary")
    public ResponseEntity<Result<MemorySummaryResponse>> putSummary(
            @PathVariable long conversationId, @Valid @RequestBody MemorySummaryRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            Authentication authentication) {
        String owner = owners.resolve(request.userId(), authentication);
        Long expected = optionalVersion(ifMatch);
        var saved = summaries.save(owner, conversationId, new MemorySummaryService.SummaryWrite(
                request.summary(), request.openLoops(), request.decisions(), request.entities(),
                request.coveredThroughMessageId(), request.sourceMessageCount(), expected,
                request.expiresAt(), request.actorId(), request.reason(), requestId));
        HttpStatus status = expected == null && saved.version() == 1 ? HttpStatus.CREATED : HttpStatus.OK;
        return versioned(Result.ok(new MemorySummaryResponse(saved)), saved.version(), status);
    }

    @DeleteMapping("/conversations/{conversationId}/summary")
    public ResponseEntity<Result<Void>> deleteSummary(
            @PathVariable long conversationId, @Valid MemoryActionRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            Authentication authentication) {
        long expected = requiredVersion(ifMatch);
        String owner = owners.resolve(request.userId(), authentication);
        var summary = summaries.get(owner, conversationId);
        if (summary.isEmpty()) return notFound();
        summaries.delete(owner, summary.get().id(), expected, request.actorId(), request.reason(), requestId);
        return ResponseEntity.ok(Result.ok());
    }

    @PostMapping("/checkpoints")
    public ResponseEntity<Result<CheckpointResponse>> createCheckpoint(
            @Valid @RequestBody CheckpointRequest request,
            @RequestParam String actorId, @RequestParam String reason,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            Authentication authentication) {
        rejectExternallyManagedAgenticCheckpoint(request.checkpointType());
        String owner = owners.resolve(request.userId(), authentication);
        var created = checkpoints.create(new CheckpointService.CreateCommand(owner, request.runId(),
                request.conversationId(), request.checkpointType(), request.nodeName(), request.state(), request.status(),
                request.sequenceNo(), request.resumeAfter(), request.errorCode(), actorId, reason,
                requestId == null ? idempotencyKey : requestId));
        return versioned(Result.ok(new CheckpointResponse(created)), created.version(), HttpStatus.CREATED);
    }

    @GetMapping("/checkpoints/{id}")
    public ResponseEntity<Result<CheckpointResponse>> getCheckpoint(
            @PathVariable long id, @RequestParam(required = false) String userId, Authentication authentication) {
        String owner = owners.resolve(userId, authentication);
        return checkpoints.get(owner, id)
                .map(value -> versioned(Result.ok(new CheckpointResponse(value)), value.version(), HttpStatus.OK))
                .orElseGet(MemoryController::notFound);
    }

    @GetMapping("/checkpoints")
    public Result<List<CheckpointResponse>> listCheckpoints(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) Long conversationId,
            @RequestParam(required = false) AgentCheckpoint.Status status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit,
            Authentication authentication) {
        requireValidLimit(limit);
        rejectUnsupportedCursor(cursor);
        String owner = owners.resolve(userId, authentication);
        return Result.ok(checkpoints.list(owner, conversationId, status, limit).stream().map(CheckpointResponse::new).toList());
    }

    @PatchMapping("/checkpoints/{id}")
    public ResponseEntity<Result<CheckpointResponse>> patchCheckpoint(
            @PathVariable long id, @Valid @RequestBody CheckpointPatchRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            Authentication authentication) {
        long expected = requiredVersion(ifMatch);
        String owner = owners.resolve(request.userId(), authentication);
        var existing = checkpoints.get(owner, id);
        if (existing.isEmpty()) return notFound();
        rejectExternallyManagedAgenticCheckpoint(existing.get().checkpointType());
        var updated = checkpoints.transition(owner, id, expected, request.status(), request.nodeName(), request.state(),
                request.errorCode(), request.resumeAfter(), request.actorId(), request.reason(), requestId);
        return versioned(Result.ok(new CheckpointResponse(updated)), updated.version(), HttpStatus.OK);
    }

    @PostMapping("/checkpoints/{id}/resume")
    public ResponseEntity<Result<CheckpointResponse>> resumeCheckpoint(
            @PathVariable long id, @Valid @RequestBody MemoryActionRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            Authentication authentication) {
        long expected = requiredVersion(ifMatch);
        String owner = owners.resolve(request.userId(), authentication);
        var existing = checkpoints.get(owner, id);
        if (existing.isEmpty()) return notFound();
        rejectExternallyManagedAgenticCheckpoint(existing.get().checkpointType());
        var updated = checkpoints.resume(owner, id, expected, request.actorId(), request.reason(), requestId);
        return versioned(Result.ok(new CheckpointResponse(updated)), updated.version(), HttpStatus.OK);
    }

    @PostMapping("/checkpoints/{id}/resume/agentic-rag")
    public ResponseEntity<Result<AgenticRagResult>> resumeAgenticRagCheckpoint(
            @PathVariable long id, @Valid @RequestBody MemoryActionRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            Authentication authentication) {
        if (agenticRagOrchestrator == null) {
            throw new IllegalStateException("Agentic RAG is disabled");
        }
        requireAuthenticatedExecution(authentication);
        long expected = requiredVersion(ifMatch);
        String owner = owners.resolve(request.userId(), authentication);
        AgenticRagResult result = agenticRagOrchestrator.resume(
                owner, id, expected, request.actorId(), request.reason(), requestId);
        return ResponseEntity.ok(Result.ok(result));
    }

    @PostMapping("/checkpoints/{id}/abandon")
    public ResponseEntity<Result<CheckpointResponse>> abandonCheckpoint(
            @PathVariable long id, @Valid @RequestBody MemoryActionRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            Authentication authentication) {
        long expected = requiredVersion(ifMatch);
        String owner = owners.resolve(request.userId(), authentication);
        if (checkpoints.get(owner, id).isEmpty()) return notFound();
        var updated = checkpoints.transition(owner, id, expected, AgentCheckpoint.Status.ABANDONED,
                null, null, null, null, request.actorId(), request.reason(), requestId);
        return versioned(Result.ok(new CheckpointResponse(updated)), updated.version(), HttpStatus.OK);
    }

    @DeleteMapping("/checkpoints/{id}")
    public ResponseEntity<Result<Void>> deleteCheckpoint(
            @PathVariable long id, @Valid MemoryActionRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            Authentication authentication) {
        long expected = requiredVersion(ifMatch);
        String owner = owners.resolve(request.userId(), authentication);
        if (checkpoints.get(owner, id).isEmpty()) return notFound();
        checkpoints.delete(owner, id, expected, request.actorId(), request.reason(), requestId);
        return ResponseEntity.ok(Result.ok());
    }

    @PostMapping("/facts")
    public ResponseEntity<Result<MemoryFactResponse>> createFact(
            @Valid @RequestBody MemoryFactRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            Authentication authentication) {
        String owner = owners.resolve(request.userId(), authentication);
        var created = facts.create(owner, factWrite(request, requestId == null ? idempotencyKey : requestId));
        return versioned(Result.ok(toResponse(created)), created.fact().version(), HttpStatus.CREATED);
    }

    @GetMapping("/facts/{id}")
    public ResponseEntity<Result<MemoryFactResponse>> getFact(
            @PathVariable long id, @RequestParam(required = false) String userId, Authentication authentication) {
        String owner = owners.resolve(userId, authentication);
        return facts.get(owner, id)
                .map(value -> versioned(Result.ok(toResponse(value)), value.fact().version(), HttpStatus.OK))
                .orElseGet(MemoryController::notFound);
    }

    @GetMapping("/facts")
    public Result<List<MemoryFactResponse>> listFacts(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String namespace,
            @RequestParam(required = false) String subject,
            @RequestParam(required = false) String predicate,
            @RequestParam(required = false) MemoryFact.State state,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit,
            Authentication authentication) {
        requireValidLimit(limit);
        rejectUnsupportedCursor(cursor);
        String owner = owners.resolve(userId, authentication);
        var filter = new MemoryFactService.FactFilter(namespace, subject, predicate, state, limit);
        return Result.ok(facts.list(owner, filter).stream().map(MemoryController::toResponse).toList());
    }

    @PatchMapping("/facts/{id}")
    public ResponseEntity<Result<MemoryFactResponse>> patchFact(
            @PathVariable long id, @Valid @RequestBody MemoryFactRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            Authentication authentication) {
        long expected = requiredVersion(ifMatch);
        String owner = owners.resolve(request.userId(), authentication);
        if (facts.get(owner, id).isEmpty()) return notFound();
        var updated = facts.update(owner, id, expected, factWrite(request, requestId));
        return versioned(Result.ok(toResponse(updated)), updated.fact().version(), HttpStatus.OK);
    }

    @DeleteMapping("/facts/{id}")
    public ResponseEntity<Result<Void>> deleteFact(
            @PathVariable long id, @Valid MemoryActionRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            Authentication authentication) {
        long expected = requiredVersion(ifMatch);
        String owner = owners.resolve(request.userId(), authentication);
        if (facts.get(owner, id).isEmpty()) return notFound();
        facts.delete(owner, id, expected, request.actorId(), request.reason(), requestId);
        return ResponseEntity.ok(Result.ok());
    }

    @PostMapping("/facts/{id}/restore")
    public ResponseEntity<Result<MemoryFact>> restoreFact(
            @PathVariable long id, @Valid @RequestBody MemoryActionRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            Authentication authentication) {
        long expected = requiredVersion(ifMatch);
        String owner = owners.resolve(request.userId(), authentication);
        var restored = facts.restore(owner, id, expected, request.actorId(), request.reason(), requestId);
        return versioned(Result.ok(restored), restored.version(), HttpStatus.OK);
    }

    @GetMapping("/facts/{id}/versions")
    public ResponseEntity<Result<List<MemoryFactVersion>>> versions(@PathVariable long id,
            @RequestParam(required = false) String userId, Authentication authentication) {
        String owner = owners.resolve(userId, authentication);
        if (facts.get(owner, id).isEmpty()) return notFound();
        return ResponseEntity.ok(Result.ok(facts.versions(owner, id)));
    }

    @GetMapping("/facts/{id}/conflicts")
    public ResponseEntity<Result<List<MemoryConflict>>> conflicts(@PathVariable long id,
            @RequestParam(required = false) String userId, Authentication authentication) {
        String owner = owners.resolve(userId, authentication);
        if (facts.get(owner, id).isEmpty()) return notFound();
        return ResponseEntity.ok(Result.ok(facts.conflicts(owner, id)));
    }

    @PostMapping("/conflicts/{conflictId}/resolve")
    public Result<MemoryConflictResponse> resolveConflict(
            @PathVariable long conflictId, @RequestParam(required = false) String userId,
            @Valid @RequestBody ResolveMemoryConflictRequest request,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            Authentication authentication) {
        long expected = requiredVersion(ifMatch);
        String owner = owners.resolve(userId, authentication);
        return Result.ok(new MemoryConflictResponse(facts.resolveConflict(owner, conflictId, expected,
                request.resolution(), request.mergedValue(), request.actorId(), request.reason(), requestId)));
    }

    @GetMapping("/audit")
    public Result<List<MemoryAuditResponse>> audit(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) MemoryAuditEvent.ResourceType resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit,
            Authentication authentication) {
        requireValidLimit(limit);
        rejectUnsupportedCursor(cursor);
        String owner = owners.resolve(userId, authentication);
        var filter = new MemoryAuditService.AuditFilter(resourceType, resourceId, limit);
        return Result.ok(audits.list(owner, filter).stream().map(MemoryAuditResponse::new).toList());
    }

    @PostMapping("/forget")
    public ResponseEntity<Result<MemoryForgetResponse>> forget(
            @Valid @RequestBody MemoryForgetRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId,
            Authentication authentication) {
        String owner = owners.resolve(request.userId(), authentication);
        var service = forget.orElseThrow(() -> new IllegalStateException("memory forget service is not available"));
        var job = service.create(owner, request, idempotencyKey, requestId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Result.ok(new MemoryForgetResponse(job)));
    }

    @GetMapping("/forget/{jobId}")
    public ResponseEntity<Result<MemoryForgetResponse>> getForgetJob(
            @PathVariable String jobId, @RequestParam(required = false) String userId,
            Authentication authentication) {
        String owner = owners.resolve(userId, authentication);
        var service = forget.orElseThrow(() -> new IllegalStateException("memory forget service is not available"));
        return service.get(owner, jobId).map(job -> ResponseEntity.ok(Result.ok(new MemoryForgetResponse(job))))
                .orElseGet(MemoryController::notFound);
    }

    private static MemoryFactService.FactWrite factWrite(MemoryFactRequest request, String requestId) {
        return new MemoryFactService.FactWrite(request.namespace(), request.subject(), request.predicate(), request.value(),
                request.sourceType(), request.sourceRef(), request.confidence(), request.validFrom(), request.validTo(),
                request.actorId(), request.reason(), requestId);
    }

    private static MemoryFactResponse toResponse(MemoryFactService.FactView view) {
        return new MemoryFactResponse(view.fact(), view.currentVersion());
    }

    private static long requiredVersion(String value) {
        if (value == null || value.isBlank()) throw new PreconditionRequiredException("If-Match is required");
        return parseVersion(value);
    }

    private static Long optionalVersion(String value) {
        return value == null || value.isBlank() ? null : parseVersion(value);
    }

    private static long parseVersion(String value) {
        var matcher = ETAG.matcher(value.trim());
        if (!matcher.matches()) throw new IllegalArgumentException("If-Match must be a quoted positive version");
        try { return Long.parseLong(matcher.group(1)); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("If-Match version is too large"); }
    }

    private static void rejectUnsupportedCursor(String cursor) {
        if (cursor != null && !cursor.isBlank()) {
            throw new IllegalArgumentException("cursor pagination is not supported by the current memory service");
        }
    }

    private static void requireValidLimit(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
    }

    private static void requireAuthenticatedExecution(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw new AccessDeniedException("authenticated identity is required for Agentic RAG resume");
        }
    }

    private static void rejectExternallyManagedAgenticCheckpoint(AgentCheckpoint.Type type) {
        if (type == AgentCheckpoint.Type.AGENTIC_RAG) {
            throw new AccessDeniedException("Agentic RAG checkpoints are managed internally");
        }
    }

    private static <T> ResponseEntity<Result<T>> versioned(Result<T> body, Long version, HttpStatus status) {
        return ResponseEntity.status(status).eTag(String.valueOf(version)).body(body);
    }

    private static <T> ResponseEntity<Result<T>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Result.fail(HttpStatus.NOT_FOUND.value(), "resource not found"));
    }
}
