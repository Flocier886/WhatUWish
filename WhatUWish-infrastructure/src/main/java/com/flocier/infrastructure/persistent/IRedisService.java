package com.flocier.infrastructure.persistent;

import org.redisson.api.RAtomicLong;
import org.redisson.api.RDeque;
import org.redisson.api.RLock;

import java.util.concurrent.TimeUnit;

public interface IRedisService {
    <T> void setValue(String key, T value);

    <T> T getValue(String key);

    <T> RDeque<T> getDeque(String key);

    RAtomicLong getAtomicLong(String key);

    RLock getLock(String key);

    boolean expire(String key, long timeout, TimeUnit timeUnit);
}
