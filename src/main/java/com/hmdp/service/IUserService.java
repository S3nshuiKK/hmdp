package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import jakarta.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    /**
     * 发送验证码到指定电话号码，并将验证码信息保存到会话中
     *
     * @param phone    接收验证码的电话号码
     * @param session  当前用户的会话，用于保存验证码信息
     * @return 返回一个Result对象，包含验证码发送结果和相关信息
     */
    Result sendCode(String phone, HttpSession session);

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
    Result login(LoginFormDTO loginForm, HttpSession session);

    Result sign();

    Result signCount();

}
