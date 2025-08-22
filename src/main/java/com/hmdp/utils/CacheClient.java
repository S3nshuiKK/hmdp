package com.hmdp.utils;


import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    public void setWithLogicalExpire(String key,Object value,Long time,TimeUnit unit){
        //设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入Redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    /**
     * 解决缓存穿透
     * @param keyPrefix
     * @param id
     * @param type
     * @param dbFallback
     * @param time
     * @param unit
     * @return
     * @param <R>
     * @param <ID>
     */
    public <R , ID> R queryWithPassThrough(
            String keyPrefix , ID id , Class<R> type , Function<ID,R> dbFallback,Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1.从Redis中查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在,如果为空值标识符 则直接结束
        if ("null".equals(json)){
            //return Result.fail("店铺不存在");
            return null;
        }
        if (StrUtil.isNotBlank(json)) {
            //3.存在,直接返回
            return JSONUtil.toBean(json, type);
        }
        //4.不存在,根据id查询数据库
        R r = dbFallback.apply(id);
        //5.数据库中不存在,返回错误;同时为了防止缓存穿透,给redis中返回一个空值标识符 此处为"null"
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key,"null",CACHE_NULL_TTL,TimeUnit.MINUTES);
            //return Result.fail("店铺不存在");
            return null;
        }
        //6.数据库中存在,写入Redis
        this.set(key,r,time,unit);
        //7.返回
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 逻辑过期解决缓存击穿
     * @param id
     * @return
     */
    public <R , ID> R queryWithLogicalExpire(String keyPrefix , ID id , Class<R> type , Function<ID,R> dbFallback,Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1.从Redis中查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isBlank(json)) {
            //3.不存在,返回空
            return null;
        }
        //存在,把Json反序列化为对象然后判断缓存是否过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期,返回商铺信息
            return r;
        }
        //过期,尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //判断是否获取互斥锁
        if (isLock) {
            //获取,并开启独立线程重建缓存
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //先查数据库
                    R apply = dbFallback.apply(id);
                    //重建缓存
                    this.setWithLogicalExpire(key,apply,time,unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });
        }
        //无论是否获取成功,都需要返回旧商铺信息
        return r;
    }

    /**
     * 获取锁
     * @param key
     * @return
     */
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     * @param key
     */
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
}
