package com.flocier.domain.agent.runtime.memory;

import java.util.Map;

/** 面向 Agent 的消息级旧会话检索，不负责修改工作记忆。 */
public interface SessionSearchService {
    Map<String, Object> search(SessionSearchRequest request);
}
