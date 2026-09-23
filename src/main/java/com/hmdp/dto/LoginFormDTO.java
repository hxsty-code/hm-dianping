package com.hmdp.dto;

import lombok.Data;

@Data
public class LoginFormDTO {
    private String phone;
    /**
     * 登录邮箱，替代原来的手机号
     */
    private String email;
    private String code;
    private String password;
}
