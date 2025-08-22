package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
    /**
     * 锁的名称
     */
    private String name;
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 需要用户传递给我们,所以用构造函数赋值
     * @param name 锁的名称
     * @param stringRedisTemplate redisTemplate操作redis
     */
    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    /**
     * 初始化时加载脚本,避免每次释放锁时才加载造成大量I/O流
     */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    /**
     * 尝试获取锁
     * @param timeoutSec 锁持有的超时时间,过期后自动释放
     */
    @Override
    public boolean tryLock(long timeoutSec) {
        //为value赋值线程标识,此处获取线程标识 将随机生成的UUID拼接上线程id作为线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        //直接返回success涉及到自动拆箱,如果success为null则会出现空指针异常,因此此处和TRUE常量做equals判断
        return Boolean.TRUE.equals(success);
    }

    /**
     * 使用Lua脚本释放锁
     */
    @Override
    public void unlock() {
        //调用Lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId()
        );
    }
    /**
     * 释放锁
     */
    /*@Override
    public void unlock() {
        //获取线程标识
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取锁中的标识
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        //判断标识是否一致
        if(threadId.equals(id)){
            //释放锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }*/
}
