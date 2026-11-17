package com.flocier.infrastructure.adapter.port;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentBrowserHttpClientConfig {
    @Bean
    public HttpClient agentBrowserHttpClient() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    }
}
