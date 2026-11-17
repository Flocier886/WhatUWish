package com.flocier.application.agent.execute;

import com.flocier.application.agent.stream.AgentSessionStream;

public interface IExecuteStrategy {

    void execute(AgentExecuteCommand request, AgentSessionStream stream) throws Exception;
}
