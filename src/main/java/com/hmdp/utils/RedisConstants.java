package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 5L;
    /**
     * 验证码发送频率限制的key，同一个邮箱在 LOGIN_CODE_LIMIT_TTL 秒内只能发一次
     */
    public static final String LOGIN_CODE_LIMIT_KEY = "login:code:limit:";
    public static final Long LOGIN_CODE_LIMIT_TTL = 60L;
    /**
     * 验证码错误次数限制：同一个邮箱连续错 LOGIN_FAIL_MAX 次，就锁定 LOGIN_FAIL_TTL 分钟
     */
    public static final String LOGIN_FAIL_KEY = "login:fail:";
    public static final int LOGIN_FAIL_MAX = 5;
    public static final Long LOGIN_FAIL_TTL = 10L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    /**
     * 逻辑过期方案专用缓存key：与 CACHE_SHOP_KEY 物理隔离，
     * 避免“纯 Shop JSON”和“RedisData 包装 JSON”两种结构互相覆盖导致 NPE / 空壳对象
     */
    public static final String CACHE_SHOP_LOGICAL_KEY = "cache:shop:logical:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    /**
     * 逻辑过期方案专用互斥锁key：与 LOCK_SHOP_KEY 隔离，避免两套方案争同一把锁
     */
    public static final String LOCK_SHOP_LOGICAL_KEY = "lock:shop:logical:";

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
    public static final String FOLLOW_KEY = "follows:";
}
