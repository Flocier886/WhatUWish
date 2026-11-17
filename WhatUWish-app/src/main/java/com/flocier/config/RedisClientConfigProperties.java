package com.flocier.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "redis.sdk.config", ignoreInvalidFields = true)
public class RedisClientConfigProperties {
    /** host ip */
    private String host;
    /** port */
    private int port;
    /** password */
    private String password;
    /** pool size */
    private int poolSize = 64;
    /** min idle size */
    private int minIdleSize = 10;
    /** idle timeout(ms) */
    private int idleTimeout = 10000;
    /** connect timeout(ms) */
    private int connectTimeout = 10000;
    /** retry attempts */
    private int retryAttempts = 3;
    /** retry interval(ms) */
    private int retryInterval = 1000;
    /** ping interval(ms) */
    private int pingInterval = 0;
    /** keep alive */
    private boolean keepAlive = true;
}
