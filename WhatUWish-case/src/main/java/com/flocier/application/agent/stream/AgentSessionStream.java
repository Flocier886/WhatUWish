package com.flocier.application.agent.stream;

public interface AgentSessionStream {

    void send(String eventType, Object payload) throws Exception;

    void complete();

    void error(String message, Throwable throwable);
}
