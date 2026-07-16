package com.nodemetry.backend.run;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/metrics")
public class MetricsController {

    private final PhysicalNodeRunRepository repository;

    public MetricsController(PhysicalNodeRunRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/ingestion")
    public List<PhysicalNodeRunResponse> ingestion(
            @RequestParam(required = false) String runId,
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
