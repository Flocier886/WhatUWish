package com.flocier.domain.agent.adapter.port;

import com.flocier.types.event.BaseEvent;

public interface IEventPublisher {

    void publish(String topic, BaseEvent.EventMessage<?> eventMessage);

    void publish(String topic, String eventMessageJSON);
}
