package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {
    // 开始的时间戳
    private  static final long BEGIN_TIMESTAMP = 1704067200L;
    // 序列号的位数
    private static  final int Count_BITS = 32;
    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String keyPrefix) {
        // 基于Redis的自增长策略
        // 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long second = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = second - BEGIN_TIMESTAMP;

        // 生成序列号
        String data = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue().increment("icr:"+keyPrefix+":"+data);
        return timeStamp << Count_BITS | count;
    }

//    public  static void main(String[] args) {
//        LocalDateTime time = LocalDateTime.of(2024,1,1,0,0,0);
//        long second = time.toEpochSecond(ZoneOffset.UTC);
//        System.out.println("second = " + second);
//    }
}
