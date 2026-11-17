package com.flocier.application.agent.stream;

import com.flocier.domain.agent.runtime.printer.Printer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class AgentSessionPrinter implements Printer {

    private final AgentSessionStream stream;

    @Override
    public void send(String type, Object content) {
        try {
            stream.send(type, content);
        } catch (Exception e) {
            log.error("printer send failed", e);
            stream.error("stream send failed", e);
        }
    }
}
