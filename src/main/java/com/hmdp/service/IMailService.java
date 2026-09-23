package com.hmdp.service;

/**
 * 邮件发送服务
 */
public interface IMailService {

    /**
     * 发送登录验证码邮件
     *
     * @param email 收件人邮箱
     * @param code  验证码
     * @return true:发送成功，false:发送失败
     */
    boolean sendLoginCode(String email, String code);
}
