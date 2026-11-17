package com.flocier.application.agent.stream;

import com.alibaba.fastjson.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

@Slf4j
@RequiredArgsConstructor
public class ResponseBodyEmitterAgentSessionStream implements AgentSessionStream {

    private final ResponseBodyEmitter emitter;

    @Override
    public void send(String eventType, Object payload) throws Exception {
        AgentStreamEvent event = AgentStreamEvent.builder()
                .eventType(eventType)
                .payload(payload)
                .build();
        emitter.send("data: " + JSON.toJSONString(event) + "\n\n");
    }

    @Override
    public void complete() {
        emitter.complete();
    }

    @Override
    public void error(String message, Throwable throwable) {
        try {
            send("error", message);
        } catch (Exception e) {
            log.error("send stream error failed", e);
        } finally {
            emitter.complete();
        }
    }
}
