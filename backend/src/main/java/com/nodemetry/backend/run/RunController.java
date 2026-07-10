package com.nodemetry.backend.run;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/runs")
public class RunController {

    private final RunRegistry registry;
    private final TestRunRepository repository;

    public RunController(RunRegistry registry, TestRunRepository repository) {
        this.registry = registry;
        this.repository = repository;
    }

    @PostMapping
    public ResponseEntity<TestRunResponse> start(@RequestBody StartRunRequest req) {
        TestRun run = registry.startRun(req);
        return ResponseEntity.status(201).body(TestRunResponse.from(run));
    }

    @PatchMapping("/{runId}/end")
    public TestRunResponse end(
            @PathVariable String runId,
            @RequestBody(required = false) EndRunRequest req
    ) {
        return TestRunResponse.from(registry.endRun(runId, req));
    }

    @GetMapping
    public List<TestRunResponse> list() {
        return repository.findAllByOrderByStartedAtDesc()
                .stream()
                .map(TestRunResponse::from)
                .toList();
    }
}
