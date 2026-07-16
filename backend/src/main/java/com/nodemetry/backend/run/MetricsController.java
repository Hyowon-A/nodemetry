package com.nodemetry.backend.run;

import com.nodemetry.backend.config.ApiErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/metrics")
@Tag(name = "Metrics", description = "Persisted ingestion metrics for physical-node runs.")
@Tag(name = "Physical Node Runs", description = "Per-node run metrics for physical sensor nodes.")
public class MetricsController {

    private final PhysicalNodeRunRepository repository;

    public MetricsController(PhysicalNodeRunRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/ingestion")
    @Operation(
            tags = {"Metrics", "Physical Node Runs"},
            summary = "Query physical-node ingestion metrics",
            description = "Returns persisted ingestion metrics. Filter by runId, nodeId, both, or neither. Expected: configured number of expected messages. Received: messages accepted by the backend. Duplicates: repeated messageId values rejected. Unique received: received - duplicates. Saved: unique messages persisted successfully. Delivery percentage: received / expected × 100. Persistence percentage: saved / unique received × 100."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Metrics returned.", content = @Content(array = @ArraySchema(schema = @Schema(implementation = PhysicalNodeRunResponse.class)))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error.", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public List<PhysicalNodeRunResponse> ingestion(
            @Parameter(description = "Optional run identifier filter.", example = "20260716T120000Z")
            @RequestParam(required = false) String runId,
            @Parameter(description = "Optional node identifier filter.", example = "node-001")
            @RequestParam(required = false) String nodeId
    ) {
        List<PhysicalNodeRun> rows;
        if (runId != null && nodeId != null) {
            rows = repository.findByRunIdAndNodeId(runId, nodeId).map(List::of).orElse(List.of());
        } else if (runId != null) {
            rows = repository.findByRunIdOrderByNodeIdAsc(runId);
        } else if (nodeId != null) {
            rows = repository.findByNodeIdOrderByLastMessageAtDesc(nodeId);
        } else {
            rows = repository.findAllByOrderByLastMessageAtDesc();
        }
        return rows.stream().map(PhysicalNodeRunResponse::from).toList();
    }
}
