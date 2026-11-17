package com.flocier.domain.agent.service.armory.business.data;

import com.flocier.domain.agent.model.entity.ArmoryCommandEntity;
import com.flocier.domain.agent.service.armory.node.factory.DefaultWarmupStrategyFactory;

public interface ILoadDataStrategy {
    void loadData(ArmoryCommandEntity armoryCommandEntity, DefaultWarmupStrategyFactory.DynamicContext dynamicContext);
}
