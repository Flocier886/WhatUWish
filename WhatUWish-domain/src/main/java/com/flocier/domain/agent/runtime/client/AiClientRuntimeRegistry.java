package com.flocier.domain.agent.runtime.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class AiClientRuntimeRegistry {

    private final Map<String, ChatClient> chatClientMap = new ConcurrentHashMap<>();

    public void register(String clientId, ChatClient chatClient) {
        chatClientMap.put(clientId, chatClient);
        log.info("registered runtime chat client: {}", clientId);
    }

    public ChatClient getRequiredChatClient(String clientId) {
        ChatClient chatClient = chatClientMap.get(clientId);
        if (chatClient == null) {
            throw new IllegalStateException("ChatClient not registered for clientId: " + clientId);
        }
        return chatClient;
    }
}
