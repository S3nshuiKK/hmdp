package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.utils.RegexUtils;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

//@SpringBootTest
public class isPhoneTest {

    //@Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    public void test(){
        String phone = "0001549849";
        if (RegexUtils.isPhoneInvalid(phone)) {
            System.out.println("true");
        } else {
            System.out.println("false");
        }
    }

    @Test
    public void test2(){
        List list = new ArrayList<>();
        list.add("a");
        list.add("b");
        list.add("c");
        list.add("d");
        list.add("e");
        list.add("f");
        list.add("g");
        stringRedisTemplate.opsForList().rightPushAll("Test1",list);
        List<String> retrievedList = stringRedisTemplate.opsForList().range("Test1", 0, -1);
        // 打印获取到的列表
        System.out.println("Retrieved List: " + retrievedList);
        // 断言获取到的列表与原始列表相同
        assertEquals(list, retrievedList);
    }

    @Test
    public void testSortedSet(){
        ShopType Type1 = ShopType.builder()
                .id(1L)
                .name("美食")
                .sort(1)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        ShopType Type2 = ShopType.builder()
                .id(2L)
                .name("KTV")
                .sort(3)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();
        ShopType Type3 = ShopType.builder()
                .id(3L)
                .name("酒吧")
                .sort(2)
                .createTime(LocalDateTime.now())
                .updateTime(LocalDateTime.now())
                .build();

    }
}
