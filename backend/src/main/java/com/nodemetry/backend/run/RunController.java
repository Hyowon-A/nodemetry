package com.nodemetry.backend.run;

import com.nodemetry.backend.config.ApiErrorResponse;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/runs")
@Tag(name = "Load Test Results", description = "Virtual-node load-test run registration and result APIs.")
public class RunController {

    private final RunRegistry registry;
    private final VirtualNodeRunRepository repository;

    public RunController(RunRegistry registry, VirtualNodeRunRepository repository) {
        this.registry = registry;
        this.repository = repository;
    }

    @PostMapping
    @Hidden
    public ResponseEntity<VirtualNodeRunResponse> start(@RequestBody StartRunRequest req) {
        VirtualNodeRun run = registry.startRun(req);
        return ResponseEntity.status(201).body(VirtualNodeRunResponse.from(run));
    }

    @PatchMapping("/{runId}/end")
    @Hidden
    public VirtualNodeRunResponse end(
            @PathVariable String runId,
            @RequestBody(required = false) EndRunRequest req
    ) {
        return VirtualNodeRunResponse.from(registry.endRun(runId, req));
    }

    @GetMapping
    @Operation(
            summary = "List virtual load-test runs",
            description = "Returns virtual-node load-test runs ordered by start time, newest first. Expected: configured number of expected messages. Received: messages accepted by the backend. Duplicates: repeated messageId values rejected. Unique received: received - duplicates. Saved: unique messages persisted successfully. Delivery percentage: received / expected × 100. Persistence percentage: saved / unique received × 100."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Runs returned.", content = @Content(array = @ArraySchema(schema = @Schema(implementation = VirtualNodeRunResponse.class)))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error.", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public List<VirtualNodeRunResponse> list() {
        return repository.findAllByOrderByStartedAtDesc()
                .stream()
                .map(VirtualNodeRunResponse::from)
                .toList();
    }
}
