package com.flocier.test.domain.llm;

import com.flocier.Application;
import com.flocier.domain.agent.runtime.llm.LLM;
import com.flocier.domain.agent.runtime.llm.message.LlmMessage;
import com.flocier.domain.agent.runtime.llm.message.LlmMessageRole;
import com.flocier.domain.agent.runtime.llm.message.LlmRequest;
import com.flocier.domain.agent.runtime.llm.response.LlmResponse;
import com.flocier.domain.agent.runtime.llm.support.LlmResolvedModel;
import com.flocier.domain.agent.runtime.llm.support.LlmSettingsRegistry;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.util.StringUtils;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 新 LLM 运行时真实调用烟雾测试。
 * 该测试直接使用 application-dev.yml 中配置的真实 baseUrl/apiKey。
 */
@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest(classes = Application.class)
@ActiveProfiles("dev")
public class LlmRuntimeLiveTest {

    @Autowired
    private LLM llm;

    @Autowired
    private LlmSettingsRegistry llmSettingsRegistry;

    @Test
    public void shouldResolveConfiguredDefaultModel() {
        LlmResolvedModel resolvedModel = llmSettingsRegistry.resolve(null);

        assertNotNull(resolvedModel);
        assertEquals("default-chat", resolvedModel.getAlias());
        assertEquals("gpt-4o-mini", resolvedModel.getConfig().getModel());
        assertTrue(StringUtils.hasText(resolvedModel.getConfig().getBaseUrl()));
        assertTrue(StringUtils.hasText(resolvedModel.getConfig().getApiKey()));
    }

    @Test
    public void shouldCallRemoteModelThroughNewLlmRuntime() {
        String configuredModel = llmSettingsRegistry.resolve(null).getConfig().getModel();

        LlmResponse response = llm.call(LlmRequest.builder()
                .messages(List.of(
                        LlmMessage.builder()
                                .role(LlmMessageRole.SYSTEM)
                                .content("你是一个简洁的助手，只用一句中文回复。")
                                .build(),
                        LlmMessage.builder()
                                .role(LlmMessageRole.USER)
                                .content("请回复：LLM运行时测试成功")
                                .build()
                ))
                .maxTokens(128)
                .temperature(0.2)
                .build());

        assertNotNull(response);
        assertEquals("gpt-4o-mini", configuredModel);
        assertTrue("真实响应模型名应匹配配置模型前缀",
                StringUtils.hasText(response.getModel()) && response.getModel().startsWith(configuredModel));
        assertEquals("default-openai", response.getProvider());
        assertTrue("真实调用返回文本不应为空", StringUtils.hasText(response.getText()));
        assertNotNull("开启 extract-usage 后 usage 不应为空", response.getUsage());

        log.info("LLM live test response: {}", response.getText());
    }
}
