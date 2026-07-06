package com.nodemetry.backend.telemetry;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RunTelemetryService {

    private static final int MAX_READINGS_PER_NODE_RUN = 60;

    private final SensorReadingRepository readingRepository;

    public RunTelemetryService(SensorReadingRepository readingRepository) {
        this.readingRepository = readingRepository;
    }

    public List<SensorReadingResponse> getReadingsForNodeRun(String nodeId, String runId) {
        long totalReadings = readingRepository.countByNodeIdAndRunId(nodeId, runId);
        List<SensorReading> readings = totalReadings <= MAX_READINGS_PER_NODE_RUN
                ? readingRepository.findByNodeIdAndRunIdOrderByReceivedAtDesc(nodeId, runId)
                : readingRepository.findTop60ByNodeIdAndRunIdOrderByReceivedAtDesc(nodeId, runId);

        return readings.stream()
                .map(SensorReadingResponse::from)
                .toList();
    }
}
