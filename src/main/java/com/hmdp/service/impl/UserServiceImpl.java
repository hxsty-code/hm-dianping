package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {



    /**
     * 发送手机验证码
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1、校验手机号格式是否正确
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式无效");
        }

        // 2、生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 3、保存验证码到Session（会话）
        session.setAttribute("code", code);

        // 4、发送验证码
        log.debug("发送短信验证码成功，验证码：{}",code);

        // 5、返回ok
        return Result.ok();
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {

        // 1、校验手机号格式是否正确
        if (RegexUtils.isPhoneInvalid(loginForm.getPhone())) {
            return Result.fail("手机号格式无效");
        }

        // 2、校验验证码
        Object cacheCode = session.getAttribute("code");
        if(RegexUtils.isCodeInvalid(loginForm.getCode())){
            return Result.fail("验证码格式无效");
        }
        if (cacheCode == null || !cacheCode.equals(loginForm.getCode())) {
            return Result.fail("验证码错误");
        }

        // 3、根据手机号查询用户
        User user = query().eq("phone", loginForm.getPhone()).one();
            // 如果用户不存在，则创建一个新用户
        if (user == null){
            user = createUserWithPhone(loginForm);
        }

        // 4、保存用户到Session（只保存DTO，避免把手机号、密码等敏感字段存进会话）
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        session.setAttribute("user", userDTO);

        // 5、返回ok
        return Result.ok();
    }

    private User createUserWithPhone(LoginFormDTO loginForm) {
        User user;
        user = new User();
        user.setPhone(loginForm.getPhone());
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}
