package com.nodemetry.backend.run;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/runs")
public class RunController {

    private final RunRegistry registry;
    private final VirtualNodeRunRepository repository;

    public RunController(RunRegistry registry, VirtualNodeRunRepository repository) {
        this.registry = registry;
        this.repository = repository;
    }

    @PostMapping
    public ResponseEntity<VirtualNodeRunResponse> start(@RequestBody StartRunRequest req) {
        VirtualNodeRun run = registry.startRun(req);
        return ResponseEntity.status(201).body(VirtualNodeRunResponse.from(run));
    }

    @PatchMapping("/{runId}/end")
    public VirtualNodeRunResponse end(
            @PathVariable String runId,
            @RequestBody(required = false) EndRunRequest req
    ) {
        return VirtualNodeRunResponse.from(registry.endRun(runId, req));
    }

    @GetMapping
    public List<VirtualNodeRunResponse> list() {
        return repository.findAllByOrderByStartedAtDesc()
                .stream()
                .map(VirtualNodeRunResponse::from)
                .toList();
    }
}
