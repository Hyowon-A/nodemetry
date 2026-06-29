package com.nodemetry.backend.node;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class NodeService {

    record StatusEvent(String nodeId, String status) {}

    private final SensorNodeRepository nodeRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public NodeService(SensorNodeRepository nodeRepository, SimpMessagingTemplate messagingTemplate) {
        this.nodeRepository = nodeRepository;
        this.messagingTemplate = messagingTemplate;
    }

    @Transactional
    public boolean processStatusUpdate(String nodeId, String status) {
        SensorNode node = nodeRepository
                .findByNodeId(nodeId)
                .orElse(null);

        if (node == null) {
            return false;
        }

        if ("online".equals(status)) {
            node.markOnline();
        } else if ("offline".equals(status)) {
            node.markOffline();
        } else {
            return false;
        }

        nodeRepository.save(node);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                messagingTemplate.convertAndSend("/topic/nodes/status", new StatusEvent(nodeId, status));
            }
        });

        return true;
    }

    @Transactional
    public int markStaleNodesOffline(Duration threshold) {
        Instant cutoff = Instant.now().minus(threshold);
        List<SensorNode> staleNodes = nodeRepository.findByLastSeenAtBeforeAndStatusNot(cutoff, "offline");

        for (SensorNode node : staleNodes) {
            node.markOffline();
        }

        if (!staleNodes.isEmpty()) {
            nodeRepository.saveAll(staleNodes);
            List<String> offlineNodeIds = staleNodes.stream().map(SensorNode::getNodeId).toList();
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    for (String nodeId : offlineNodeIds) {
                        messagingTemplate.convertAndSend("/topic/nodes/status", new StatusEvent(nodeId, "offline"));
                    }
                }
            });
        }

        return staleNodes.size();
    }
}
