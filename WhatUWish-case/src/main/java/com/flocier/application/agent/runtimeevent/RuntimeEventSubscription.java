package com.flocier.application.agent.runtimeevent;

public interface RuntimeEventSubscription extends AutoCloseable {
    @Override
    void close();
}
