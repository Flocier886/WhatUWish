package com.flocier.domain.agent.runtime.printer;

public interface Printer {

    void send(String type, Object content);
}
