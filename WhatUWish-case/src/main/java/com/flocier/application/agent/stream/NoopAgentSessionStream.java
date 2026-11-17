package com.flocier.application.agent.stream;

/** 新 Runtime Event v1 入口不消费 legacy 帧时使用的兼容空输出。 */
public class NoopAgentSessionStream implements AgentSessionStream {
    @Override public void send(String eventType, Object payload) { }
    @Override public void complete() { }
    @Override public void error(String message, Throwable throwable) { }
}
