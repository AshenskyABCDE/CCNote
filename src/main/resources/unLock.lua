---
--- Generated by EmmyLua(https://github.com/EmmyLua)
--- Created by hp.
--- DateTime: 2024/6/21 10:58
---
--- 比较线程标识和锁的标识是否一样
if(redis.call('get',KEYS[1]) == ARGV[1]) then
    return redis.call('del',KEYS[1])
end
return 0