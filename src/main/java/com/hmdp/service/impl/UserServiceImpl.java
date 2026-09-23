package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IMailService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
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

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IMailService mailService;

    /**
     * 发送邮箱验证码
     */
    @Override
    public Result sendCode(String email) {
        // 1、校验邮箱格式是否正确
        email = normalizeEmail(email);
        if (RegexUtils.isEmailInvalid(email)) {
            return Result.fail("邮箱格式无效");
        }

        // 2、发送频率限制：同一个邮箱 60 秒内只能发一次
        //    setIfAbsent 就是 Redis 的 SET NX，返回 false 说明刚才已经发过了
        Boolean first = stringRedisTemplate.opsForValue()
                .setIfAbsent(LOGIN_CODE_LIMIT_KEY + email, "1", LOGIN_CODE_LIMIT_TTL, TimeUnit.SECONDS);
        if (Boolean.FALSE.equals(first)) {
            return Result.fail("验证码发送过于频繁，请稍后再试");
        }

        // 3、生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 4、先发邮件，发成功了再把验证码写进redis
        //    顺序反过来会出现「发信失败但验证码已生效」的诡异状态
        if (!mailService.sendLoginCode(email, code)) {
            // 邮件没发出去，把限流标记删掉，否则用户还要干等60秒
            stringRedisTemplate.delete(LOGIN_CODE_LIMIT_KEY + email);
            return Result.fail("验证码发送失败，请稍后重试");
        }
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + email, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        // 注意：日志里只记录邮箱，不要打印验证码
        log.debug("向邮箱 {} 发送登录验证码成功", email);

        // 5、返回ok
        return Result.ok();
    }

    /**
     * 登录功能
     * @param loginForm 登录参数，包含邮箱、验证码；或者邮箱、密码
     */
    @Override
    public Result login(LoginFormDTO loginForm) {
        // 1、校验邮箱格式是否正确
        String email = normalizeEmail(loginForm.getEmail());
        if (RegexUtils.isEmailInvalid(email)) {
            return Result.fail("邮箱格式无效");
        }

        // 2、校验验证码格式
        String code = loginForm.getCode();
        if(RegexUtils.isCodeInvalid(code)){
            return Result.fail("验证码格式无效");
        }

        // 3、从redis中获取验证码并比对
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + email);
        if (cacheCode == null || !cacheCode.equals(code)) {
            return Result.fail("验证码错误");
        }

        // 4、验证码是一次性的：校验通过就立刻删除，防止同一个码被反复使用
        stringRedisTemplate.delete(LOGIN_CODE_KEY + email);

        // 5、根据邮箱查询用户，不存在则注册新用户
        User user = query().eq("email", email).one();
        if (user == null){
            user = createUserWithEmail(email);
        }

        // 6、保存用户到redis（只保存DTO，避免把邮箱、密码等敏感字段存进会话）
        // 6.1 随机生成token作为登录令牌
        String token = UUID.randomUUID().toString();
        // 6.2 将User对象转为HashMap
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        // 6.3 存储用户信息到redis
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);

        // 7、设置token的过期时间
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 8、返回token
        return Result.ok(token);
    }

    private User createUserWithEmail(String email) {
        User user = new User();
        user.setEmail(email);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        try {
            save(user);
        } catch (DuplicateKeyException e) {
            // 并发场景：两个请求同时登录同一个新邮箱，另一个已经插入成功了
            // 这里重查一次即可，不能直接抛异常让用户登录失败
            user = query().eq("email", email).one();
        }
        return user;
    }

    /**
     * 邮箱统一去掉首尾空格并转小写。
     * 因为 A@QQ.com 和 a@qq.com 是同一个人，不统一的话会建出两个账号。
     */
    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}
