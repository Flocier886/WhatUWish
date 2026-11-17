package com.flocier.domain.agent.runtime.agent;

import com.flocier.domain.agent.runtime.agent.react.plan.ReactThinkResult;
import org.springframework.util.StringUtils;

import java.util.UUID;

/**
 * 共享 think/act 模式的轻量骨架。
 * 当前仅负责标准主循环，不承载具体业务能力。
 */
public abstract class AbstractThinkActAgent extends AbstractAgent {

    @Override
    public String run(AgentContext context) {
        initialize(context);

        while (!shouldStop(context)) {
            ReactThinkResult thinkResult = think(context);
            afterThink(context, thinkResult);

            if (thinkResult != null && thinkResult.isCompleted()) {
                return finalizeRun(context, thinkResult);
            }

            act(context, thinkResult);
            advanceStep(context);
        }

        context.setCompleted(Boolean.TRUE);
        if (!StringUtils.hasText(context.getFinishReason())) {
            context.setFinishReason("max_step_reached");
        }
        return finalizeRun(context, null);
    }

    protected void initialize(AgentContext context) {
        if (context.getCurrentStep() == null || context.getCurrentStep() < 1) {
            context.setCurrentStep(1);
        }
        if (context.getCompleted() == null) {
            context.setCompleted(Boolean.FALSE);
        }
        if (!StringUtils.hasText(context.getRequestId())) {
            context.setRequestId(UUID.randomUUID().toString());
        }
        if (!StringUtils.hasText(context.getTraceId())) {
            context.setTraceId(context.getRequestId());
        }
        if (context.getAttribute("runId") == null) {
            context.setAttribute("runId", UUID.randomUUID().toString());
        }
    }

    protected void afterThink(AgentContext context, ReactThinkResult thinkResult) {
        if (thinkResult == null) {
            return;
        }
        context.setLastThought(thinkResult.getThought());
        context.setAttribute("agent.lastThinkResult", thinkResult);
    }

    protected boolean shouldStop(AgentContext context) {
        if (Boolean.TRUE.equals(context.getCompleted())) {
            return true;
        }
        if (context.getMaxStep() == null || context.getCurrentStep() == null) {
            return false;
        }
        return context.getCurrentStep() > context.getMaxStep();
    }

    protected void advanceStep(AgentContext context) {
        context.setCurrentStep((context.getCurrentStep() == null ? 1 : context.getCurrentStep()) + 1);
    }

    protected String finalizeRun(AgentContext context, ReactThinkResult thinkResult) {
        String result = resolveResult(context, thinkResult);
        context.setResult(result);
        emit(context, "result", result);
        return result;
    }

    protected String resolveResult(AgentContext context, ReactThinkResult thinkResult) {
        if (thinkResult != null && StringUtils.hasText(thinkResult.getThought())) {
            return thinkResult.getThought();
        }
        if (StringUtils.hasText(context.getResult())) {
            return context.getResult();
        }
        return StringUtils.hasText(context.getLastObservation()) ? context.getLastObservation() : "";
    }

    protected abstract ReactThinkResult think(AgentContext context);

    protected abstract void act(AgentContext context, ReactThinkResult thinkResult);
}
