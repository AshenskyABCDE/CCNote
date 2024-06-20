package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements  ILock {
    private String name;
    private StringRedisTemplate stringRedisTemplate;
    private  static  final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true);

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    @Override
    public boolean tryLock(long timeOut) {
        // 获取线程的id
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁
        boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId,timeOut, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(flag);
    }
    @Override
    public void unlock() {
        // 获取线程标志
        String threadId = KEY_PREFIX + Thread.currentThread().getId();
        // 获取锁种的标识
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        if(threadId.equals(id)) {
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }
}
