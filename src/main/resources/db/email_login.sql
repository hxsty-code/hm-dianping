-- 把登录方式从「手机号 + 短信验证码」改成「QQ邮箱 + 邮件验证码」，需要改两处：
--   1. 新增 email 字段，并加唯一索引（登录时按它查用户，唯一索引保证一个邮箱一个账号）
--   2. 把 phone 放开为可空（以后新注册的用户不再填手机号）
-- 注意：MySQL 的唯一索引允许多个 NULL 值，所以老的手机号用户不受影响。
ALTER TABLE `tb_user`
    MODIFY COLUMN `phone` varchar(11) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '手机号码',
    ADD COLUMN `email` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '登录邮箱' AFTER `phone`,
    ADD UNIQUE INDEX `uk_unique_key_email`(`email`) USING BTREE;
