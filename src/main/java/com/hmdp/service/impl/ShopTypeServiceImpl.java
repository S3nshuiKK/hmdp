package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOPTYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ShopType> queryType() {
        //1.查询Redis
        List<String> shopType = stringRedisTemplate.opsForList().range(CACHE_SHOPTYPE_KEY, 0, -1);
        //2.存在,返回
        if (shopType != null && !shopType.isEmpty()) {
            List<ShopType> typeList = new ArrayList<>();
            for (String s : shopType) {
                ShopType bean = JSONUtil.toBean(s, ShopType.class);
                typeList.add(bean);
            }
            return typeList;
        }
        //3.不存在,查询数据库
        List<ShopType> typeList = query().orderByAsc("sort").list();
        //4.数据库中不存在,报错

        // 5. 如果数据库中有数据，保存到 Redis
        List<String> jsonList = new ArrayList<>();
        for (ShopType shopTypeObj : typeList) {
            jsonList.add(JSONUtil.toJsonStr(shopTypeObj));
        }
        stringRedisTemplate.opsForList().rightPushAll(CACHE_SHOPTYPE_KEY, jsonList.toArray(new String[0]));
        //6.返回
        return typeList;
    }
}
