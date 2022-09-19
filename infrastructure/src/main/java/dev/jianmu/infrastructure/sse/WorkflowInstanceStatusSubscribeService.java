package dev.jianmu.infrastructure.sse;

import dev.jianmu.event.Event;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WorkflowInstanceStatusSubscribeService {
    private final Map<String, List<SseEmitter>> workflowMap = new ConcurrentHashMap<>();

    public SseEmitter newSseEmitter(String workflowRef) {
        var sseEmitter = new SseEmitter(0L);
        sseEmitter.onError(e -> this.clearByWorkflowAndSseEmitter(workflowRef, sseEmitter));
        this.workflowMap.putIfAbsent(workflowRef, Collections.synchronizedList(new ArrayList<>()));
        this.workflowMap.get(workflowRef).add(sseEmitter);
        return sseEmitter;
    }

    public void sendMessageByWorkflowRef(String workflowRef, Event event) {
        var sseEmitters = this.workflowMap.get(workflowRef);
        if (sseEmitters == null) {
            return;
        }
        sseEmitters.forEach(sseEmitter -> {
            try {
                sseEmitter.send(SseEmitter.event()
                        .id(workflowRef)
                        .data(event));
            } catch (IOException e) {
                sseEmitter.completeWithError(e);
            }
        });
    }

    public void clearByWorkflow(String workflowRef) {
        this.workflowMap.remove(workflowRef);
    }

    public void clearByWorkflowAndSseEmitter(String workflowRef, SseEmitter sseEmitter) {
        var sseEmitters = this.workflowMap.get(workflowRef);
        if (sseEmitters == null) {
            return;
        }
        sseEmitters.remove(sseEmitter);
        if (sseEmitters.isEmpty()) {
            this.workflowMap.remove(workflowRef);
        }
    }
}
