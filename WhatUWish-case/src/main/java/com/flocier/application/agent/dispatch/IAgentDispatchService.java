package com.flocier.application.agent.dispatch;

import com.flocier.application.agent.execute.AgentExecuteCommand;
import com.flocier.application.agent.stream.AgentSessionStream;

public interface IAgentDispatchService {

    void dispatch(AgentExecuteCommand request, AgentSessionStream stream) throws Exception;
}
