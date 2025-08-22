package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 发送验证码到指定电话号码，并将验证码信息保存到会话中
     *
     * @param phone    接收验证码的电话号码
     * @param session  当前用户的会话，用于保存验证码信息
     * @return 返回一个Result对象，包含验证码发送结果和相关信息
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //如果不符合,返回错误信息
            return Result.fail("手机号格式错误!!!");
        }
        //符合,生成验证码
        //RandomUtil 随机数工具类
        String code = RandomUtil.randomNumbers(6);
        //保存验证码到Redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //发送验证码
        log.debug("发送验证码成功,验证码为:{}",code);
        //返回ok
        return Result.ok();
    }

    /**
     * 处理用户登录请求
     *
     * 该方法接收登录表单数据，验证用户身份，并在验证通过后创建会话
     * 主要功能包括：
     * 1. 接收用户输入的登录信息，如用户名和密码
     * 2. 验证用户身份的合法性
     * 3. 登录成功后，在服务器端创建会话，以维持用户登录状态
     *
     * @param loginForm 登录表单对象，包含用户提交的登录信息，如用户名和密码
     * @param session HTTP会话对象，用于存储用户登录状态
     * @return 返回登录结果对象，包含登录是否成功的信息
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.如果不符合,返回错误信息
            return Result.fail("手机号格式错误!!!");
        }
        //3.从Redis中获取验证码并校验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
            //不一致 报错
            return Result.fail("验证码错误");
        }
        //4.一致 先将使用过的验证码删除,再根据手机号查询用户
        stringRedisTemplate.delete(LOGIN_CODE_KEY + phone);
        User user = query().eq("phone", phone).one();

        //5.判断用户是否存在
        if (user == null){
            //6.不存在 创建新用户并保存
            user = createUserWithPhone(phone);
        }
        //7.保存用户信息到Redis中
        //7.1随机生成Token,作为登录令牌
        String token = UUID.randomUUID().toString(true);
        //7.2将User对象转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue) -> fieldValue.toString()));
        //7.3存储
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        //7.4设置token有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.MINUTES);
        //8.返回Token
        return Result.ok(token);
    }

    /**
     * 签到功能
     * @return 无
     */
    @Override
    public Result sign() {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接Key  sign:userId:202506
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //5.写入Redis SETBIT key offset true
        stringRedisTemplate.opsForValue().setBit(key,dayOfMonth-1,true);
        return Result.ok();
    }

    /**
     * 签到统计功能
     * @return
     */
    @Override
    public Result signCount() {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接Key  sign:userId:202506
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //5.获取本月截至到今天为止的所有签到记录,返回的是一个十进制数字
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0)
        );
        if (result == null || result.isEmpty()){
            //没有签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == null || num == 0) {
            return Result.ok(0);
        }
        //6.循环遍历
        int count = 0;
        while(true){
            //让这个数字与1做与运算,得到数字的最后一位bit位
            if ((num & 1) == 0){
                //如果是0,说明为签到
                break;
            }else {
                //如果不是0,说明已签到
                count++;
            }
            //把数字右移一位,抛弃最后一个bit位,继续下一个bit位的判断
            num >>>= 1;
        }
        return Result.ok(count);
    }

    /**
     * 根据手机号创建用户
     * @param phone
     * @return
     */
    private User createUserWithPhone(String phone) {
        User user = User.builder()
                .phone(phone)
                .nickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10))
                .build();
        save(user);
        return user;
    }
}
