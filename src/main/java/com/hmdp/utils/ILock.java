package com.hmdp.utils;

public interface ILock {
    // 尝试获取类
    boolean tryLock(long timeOut) ;

    // 释放锁
    void unlock();
}
