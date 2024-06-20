package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements  ILock {
    private String name;
    private StringRedisTemplate stringRedisTemplate;
    private  static  final String KEY_PREFIX = "lock:";

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    @Override
    public boolean tryLock(long timeOut) {
        // 获取线程的id
        long threadId = Thread.currentThread().getId();
        String value = threadId + "";
        // 获取锁
        boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, value,timeOut, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(flag);
    }
    @Override
    public void unlock() {
        stringRedisTemplate.delete(KEY_PREFIX + name);
    }
}
