package com.nodemetry.backend.telemetry;

import com.nodemetry.backend.config.ApiErrorResponse;
import com.nodemetry.backend.node.SensorNodeRepository;
import com.nodemetry.backend.node.SensorNodeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Physical Nodes", description = "Physical sensor node status and fleet views.")
@Tag(name = "Telemetry Readings", description = "Telemetry reading history and latest-reading APIs.")
@Tag(name = "Physical Node Runs", description = "Run identifiers and run-scoped readings for physical nodes.")
public class SensorReadingController {

    private final SensorNodeRepository nodeRepository;
    private final SensorReadingRepository readingRepository;
    private final RunTelemetryService runTelemetryService;

    public SensorReadingController(
            SensorNodeRepository nodeRepository,
            SensorReadingRepository readingRepository,
            RunTelemetryService runTelemetryService
    ) {
        this.nodeRepository = nodeRepository;
        this.readingRepository = readingRepository;
        this.runTelemetryService = runTelemetryService;
    }

    @GetMapping("/nodes")
    @Operation(
            tags = {"Physical Nodes"},
            summary = "List known physical sensor nodes",
            description = "Returns all nodes known to the backend with their latest health metadata."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Nodes returned.",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = SensorNodeResponse.class)))
            ),
            @ApiResponse(responseCode = "500", description = "Unexpected server error.", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public List<SensorNodeResponse> getNodes() {
        return nodeRepository.findAll()
                .stream()
                .map(SensorNodeResponse::from)
                .toList();
    }

    @GetMapping("/nodes/{nodeId}/latest")
    @Operation(
            tags = {"Telemetry Readings"},
            summary = "Get latest reading for a node",
            description = "Returns the latest persisted telemetry reading for the requested node."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Latest reading returned.", content = @Content(schema = @Schema(implementation = SensorReadingResponse.class))),
            @ApiResponse(responseCode = "404", description = "No reading exists for the node.", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error.", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public SensorReadingResponse getLatestReading(
            @Parameter(description = "Node identifier.", example = "node-001")
            @PathVariable String nodeId
    ) {
        return readingRepository
                .findTopByNodeIdOrderByReceivedAtDesc(nodeId)
                .map(SensorReadingResponse::from)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No readings found for node: " + nodeId
                ));
    }

    @GetMapping("/nodes/{nodeId}/readings")
    @Operation(
            tags = {"Telemetry Readings"},
            summary = "Get recent readings for a node",
            description = "Returns up to the latest 100 readings for the requested node, newest first."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Readings returned.", content = @Content(array = @ArraySchema(schema = @Schema(implementation = SensorReadingResponse.class)))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error.", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public List<SensorReadingResponse> getRecentReadings(
            @Parameter(description = "Node identifier.", example = "node-001")
            @PathVariable String nodeId
    ) {
        return readingRepository.findTop100ByNodeIdOrderByReceivedAtDesc(nodeId)
                .stream()
                .map(SensorReadingResponse::from)
                .toList();
    }

    @GetMapping("/nodes/{nodeId}/runs")
    @Operation(
            tags = {"Physical Node Runs"},
            summary = "List runs observed for a node",
            description = "Returns run identifiers seen in telemetry for the requested node, ordered by latest reading."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Run identifiers returned.",
                    content = @Content(
                            array = @ArraySchema(schema = @Schema(implementation = String.class)),
                            examples = @ExampleObject(value = "[\"20260716T120000Z\", \"20260716T110000Z\"]")
                    )
            ),
            @ApiResponse(responseCode = "404", description = "Node not found.", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error.", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public List<String> getRunsForNode(
            @Parameter(description = "Node identifier.", example = "node-001")
            @PathVariable String nodeId
    ) {
        requireKnownNode(nodeId);
        return readingRepository.findRunIdsByNodeIdOrderByLatestReadingDesc(nodeId);
    }

    @GetMapping("/nodes/{nodeId}/runs/{runId}/readings")
    @Operation(
            tags = {"Telemetry Readings", "Physical Node Runs"},
            summary = "Get run-scoped readings for a node",
            description = "Returns readings for a specific node and runId."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Run-scoped readings returned.", content = @Content(array = @ArraySchema(schema = @Schema(implementation = SensorReadingResponse.class)))),
            @ApiResponse(responseCode = "404", description = "Node not found.", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error.", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    public List<SensorReadingResponse> getReadingsForNodeRun(
            @Parameter(description = "Node identifier.", example = "node-001")
            @PathVariable String nodeId,
            @Parameter(description = "Run identifier.", example = "20260716T120000Z")
            @PathVariable String runId
    ) {
        requireKnownNode(nodeId);
        return runTelemetryService.getReadingsForNodeRun(nodeId, runId);
    }

    private void requireKnownNode(String nodeId) {
        if (!nodeRepository.existsByNodeId(nodeId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Node not found: " + nodeId);
        }
    }
}
