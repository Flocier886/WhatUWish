package com.flocier.config;

import com.flocier.domain.agent.reactor.config.ReactorConfig;
import com.flocier.domain.agent.runtime.skill.SkillRuntimeOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Skill Runtime 相关配置。
 * 将 ReactorConfig 中解析出的 SkillRuntimeOptions 显式注册为 Spring Bean，
 * 供 SkillRegistry、SkillExecutor 等运行时组件统一注入使用。
 */
@Configuration
public class SkillRuntimeConfiguration {

    @Bean
    public SkillRuntimeOptions skillRuntimeOptions(ReactorConfig reactorConfig) {
        return reactorConfig.getSkillRuntimeOptions();
    }
}
