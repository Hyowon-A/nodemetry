package com.nodemetry.backend.telemetry;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RunTelemetryService {

    private final SensorReadingRepository readingRepository;

    public RunTelemetryService(SensorReadingRepository readingRepository) {
        this.readingRepository = readingRepository;
    }

    public List<SensorReadingResponse> getReadingsForNodeRun(String nodeId, String runId) {
        return readingRepository.findByNodeIdAndRunIdOrderByReceivedAtDesc(nodeId, runId)
                .stream()
                .map(SensorReadingResponse::from)
                .toList();
    }
}
