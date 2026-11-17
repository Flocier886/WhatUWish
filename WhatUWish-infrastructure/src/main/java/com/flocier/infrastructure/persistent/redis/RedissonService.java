package com.flocier.infrastructure.persistent.redis;

import com.flocier.infrastructure.persistent.IRedisService;
import jakarta.annotation.Resource;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RDeque;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service("redissonService")
public class RedissonService implements IRedisService {
    @Resource
    private RedissonClient redissonClient;

    @Override
    public <T> void setValue(String key, T value) {
        redissonClient.<T>getBucket(key).set(value);
    }

    @Override
    public <T> T getValue(String key) {
        return redissonClient.<T>getBucket(key).get();
    }

    @Override
    public <T> RDeque<T> getDeque(String key) {
        return redissonClient.getDeque(key);
    }

    @Override
    public RAtomicLong getAtomicLong(String key) {
        return redissonClient.getAtomicLong(key);
    }

    @Override
    public RLock getLock(String key) {
        return redissonClient.getLock(key);
    }

    @Override
    public boolean expire(String key, long timeout, TimeUnit timeUnit) {
        return redissonClient.getBucket(key).expire(timeout, timeUnit);
    }
}
