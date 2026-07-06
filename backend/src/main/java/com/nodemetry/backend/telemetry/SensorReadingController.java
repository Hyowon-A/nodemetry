package com.nodemetry.backend.telemetry;

import com.nodemetry.backend.node.SensorNodeRepository;
import com.nodemetry.backend.node.SensorNodeResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
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
    public List<SensorNodeResponse> getNodes() {
        return nodeRepository.findAll()
                .stream()
                .map(SensorNodeResponse::from)
                .toList();
    }

    @GetMapping("/nodes/{nodeId}/latest")
    public SensorReadingResponse getLatestReading(@PathVariable String nodeId) {
        return readingRepository
                .findTopByNodeIdOrderByReceivedAtDesc(nodeId)
                .map(SensorReadingResponse::from)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No readings found for node: " + nodeId
                ));
    }

    @GetMapping("/nodes/{nodeId}/readings")
    public List<SensorReadingResponse> getRecentReadings(@PathVariable String nodeId) {
        return readingRepository.findTop100ByNodeIdOrderByReceivedAtDesc(nodeId)
                .stream()
                .map(SensorReadingResponse::from)
                .toList();
    }

    @GetMapping("/nodes/{nodeId}/runs")
    public List<String> getRunsForNode(@PathVariable String nodeId) {
        requireKnownNode(nodeId);
        return readingRepository.findRunIdsByNodeIdOrderByLatestReadingDesc(nodeId);
    }

    @GetMapping("/nodes/{nodeId}/runs/{runId}/readings")
    public List<SensorReadingResponse> getReadingsForNodeRun(
            @PathVariable String nodeId,
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
