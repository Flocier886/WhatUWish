package com.flocier.domain.agent.runtime.agent.summary;

import com.flocier.domain.agent.runtime.agent.AbstractAgent;

public abstract class AbstractSummaryAgent extends AbstractAgent {

    protected String fallbackResult(String value) {
        return value == null ? "" : value;
    }
}
