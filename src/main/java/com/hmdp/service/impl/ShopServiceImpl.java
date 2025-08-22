package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private CacheClient cacheClient;

    @Override
    public Object queryById(Long id) {
        //缓存穿透
        //Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class, this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);

        //互斥锁解决缓存击穿
        //Shop shop = queryWithMutex(id);

        //逻辑过期解决缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY,id,Shop.class, this::getById,CACHE_SHOP_TTL,TimeUnit.MINUTES);
        if (shop == null) {
            return Result.fail("店铺不存在!!!");
        }
        //7.返回
        return Result.ok(shop);
    }

    /*private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    public Shop queryWithLogicalExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1.从Redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            //3.不存在,返回空
            return null;
        }
        //存在,把Json反序列化为对象然后判断缓存是否过期
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期,返回商铺信息
            return shop;
        }
        //过期,尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //判断是否获取互斥锁
        if (isLock) {
            //获取,并开启独立线程重建缓存
            //DoubleCheck再次检测redis缓存是否过期
            if (expireTime.isAfter(LocalDateTime.now())) {
                //未过期,返回商铺信息
                return shop;
            }
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    this.saveShop2Redis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockKey);
                }
            });

        }
        //无论是否获取成功,都需要返回旧商铺信息
        return shop;
    }


    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }


    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }*/

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空!");
        }
        //1.更新数据库
        updateById(shop);
        //2.删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    /**
     * 防缓存穿透保护逻辑
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1.从Redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在,如果为空值标识符 则直接结束
        if ("null".equals(shopJson)){
            //return Result.fail("店铺不存在");
            return null;
        }
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在,直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //4.不存在,根据id查询数据库
        Shop shop = getById(id);
        //5.数据库中不存在,返回错误;同时为了防止缓存穿透,给redis中返回一个空值标识符 此处为"null"
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(key,"null",CACHE_NULL_TTL,TimeUnit.MINUTES);
            //return Result.fail("店铺不存在");
            return null;
        }
        //6.数据库中存在,写入Redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //7.返回
        return shop;
    }


    /*public Shop queryWithMutex(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1.从Redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在,如果为空值标识符 则直接结束
        if ("null".equals(shopJson)){
            //return Result.fail("店铺不存在");
            return null;
        }
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在,直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //4.开始实现缓存重建
        //4.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            //4.2 判断是否获取成功
            if (!isLock) {
                //4.3 失败,休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //4.4 成功,根据id查询数据库
            //不存在,根据id查询数据库
            shop = getById(id);
            //模拟重建的延迟
            Thread.sleep(5000);
            //5.数据库中不存在,返回错误;同时为了防止缓存穿透,给redis中返回一个空值标识符 此处为"null"
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(key,"null",CACHE_NULL_TTL,TimeUnit.MINUTES);
                //return Result.fail("店铺不存在");
                return null;
            }
            //6.数据库中存在,写入Redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //7.释放互斥锁
            unlock(lockKey);
        }
        //8.返回
        return shop;
    }*/

    public void saveShop2Redis(Long id, Long expireSeconds){
        //1.查询店铺数据
        Shop shop = getById(id);
        //2.封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));
    }
}
