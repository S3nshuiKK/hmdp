package com.hmdp.utils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;


//拦截器
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //前置拦截器
        if (UserHolder.getUser() == null){
            response.setStatus(401);
            //拦截
            return false;
        }
        //8.放行
        return true;
    }
}
