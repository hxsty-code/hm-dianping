package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

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
     * 发送邮箱验证码
     * @param email 收件邮箱
     */
    Result sendCode(String email);

    /**
     * 登录功能
     * @param loginForm 登录参数，包含邮箱、验证码；或者邮箱、密码
     */
    Result login(LoginFormDTO loginForm);
}
