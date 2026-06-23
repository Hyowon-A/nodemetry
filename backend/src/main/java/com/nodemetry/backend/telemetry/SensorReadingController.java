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

    public SensorReadingController(
            SensorNodeRepository nodeRepository,
            SensorReadingRepository readingRepository
    ) {
        this.nodeRepository = nodeRepository;
        this.readingRepository = readingRepository;
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
}