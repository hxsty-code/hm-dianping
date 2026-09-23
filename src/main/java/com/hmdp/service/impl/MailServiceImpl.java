package com.hmdp.service.impl;

import com.hmdp.service.IMailService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.mail.internet.MimeMessage;

import static com.hmdp.utils.RedisConstants.LOGIN_CODE_TTL;

/**
 * 基于 Spring 的 JavaMailSender 发送邮件，SMTP 参数都在 application.yaml 的 spring.mail 下
 */
@Slf4j
@Service
public class MailServiceImpl implements IMailService {

    @Resource
    private JavaMailSender mailSender;

    /**
     * 发件人，就是配置里的 spring.mail.username。
     * QQ邮箱要求 From 必须和登录的账号一致，否则会被拒收。
     */
    @Value("${spring.mail.username}")
    private String from;

    @Override
    public boolean sendLoginCode(String email, String code) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            // 第二个参数 false 表示不带附件，第三个参数指定编码，避免中文标题乱码
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(email);
            helper.setSubject("黑马点评登录验证码");
            helper.setText("您的登录验证码是 " + code + "，" + LOGIN_CODE_TTL + " 分钟内有效，请勿泄露给他人。", false);
            mailSender.send(message);
            return true;
        } catch (Exception e) {
            // 这里一定要吞掉异常并返回false，交给业务方决定怎么处理，不能让SMTP抖动把接口打挂
            log.error("发送登录验证码邮件失败，收件人：{}", email, e);
            return false;
        }
    }
}
