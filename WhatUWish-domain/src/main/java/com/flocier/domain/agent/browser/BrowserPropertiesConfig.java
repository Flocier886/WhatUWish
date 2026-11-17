package com.flocier.domain.agent.browser;

import com.flocier.types.agent.config.AgentBrowserProperties;
import com.flocier.types.agent.config.BrowserRelayProperties;
import com.flocier.types.agent.config.OpenCliProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 将三组浏览器配置注册为 Spring 属性对象，供工具、Relay 和 Kernel 共用。 */
@Configuration
@EnableConfigurationProperties({OpenCliProperties.class, BrowserRelayProperties.class, AgentBrowserProperties.class})
public class BrowserPropertiesConfig { }
