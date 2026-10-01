package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    /**
     * 根据id查询商铺信息
     * 【缓存击穿演示：互斥锁方案】
     * 热点key失效瞬间，只放行一个线程去查库重建缓存；
     * 其余线程获取锁失败后休眠一小会再重试，避免大量请求同时打到数据库。
     * @param id 商铺id
     * @return 商铺信息
     */
    @Override
    public Result queryById(Long id) {
        // 1、判断缓存中是否存在
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        if(StrUtil.isNotBlank(shopJson)){
            // 2、如果存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }

        // 3、判断缓存中是否存在空值（防穿透与防击穿叠加：空值直接拦截）
        if(shopJson != null){       // "" != null，说明之前已确认不存在，直接返回失败
            return Result.fail("店铺不存在");
        }

        // 4、缓存未命中，尝试获取互斥锁，保证只有一个线程去查库重建缓存
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        if(!isLock){
            // 获取锁失败：说明已有线程在查库重建缓存，休眠一小会再重试
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();     // 恢复中断标记，不吞掉中断信号
                return Result.fail("查询被中断，请重试");
            }
            return queryById(id);   // 递归重试
        }
        // 5、获取锁成功：查库重建缓存，无论结果如何，最后都要释放锁
        try {
            Shop shop = getById(id);
            if(shop == null){
                // 数据库不存在：写空值防穿透
                return handleCachePenetration(id);
            }
            // 6、数据库存在，写入缓存并返回结果
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + shop.getId(), JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
            return Result.ok(shop);
        } finally {
            // 7、释放锁
            unLock(lockKey);
        }
    }

    /**
     * 根据id查询商铺信息
     * 【缓存穿透完整演示：缓存空对象方案】
     * 流程：查缓存命中直接返回；命中空值说明该id之前已确认不存在，直接拦截；
     * 未命中才查数据库：数据库不存在则把空值写进缓存，存在则写正常缓存。
     * 与 queryById（互斥锁防击穿）对照复习两种方案的差异。
     * @param id 商铺id
     * @return 商铺信息
     */
    public Result queryWithPassThrough(Long id) {
        // 1、判断缓存中是否存在
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        if(StrUtil.isNotBlank(shopJson)){
            // 2、如果存在，直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }

        // 3、判断缓存中是否存在空值
        if(shopJson != null){       // 第二次访问："" != null，直接返回失败，不再查询数据库
            return Result.fail("店铺不存在");
        }

        // 4、缓存未命中，查询数据库
        Shop shop = getById(id);
        if(shop == null){
            // 5、如果数据库不存在，将空值写入redis，返回错误
            return handleCachePenetration(id);
        }
        // 6、如果数据库存在，写入缓存并返回结果
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + shop.getId(), JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(shop);
    }

    /**
     * 缓存穿透处理：数据库不存在该商铺时，将空值写入 redis 并返回失败。
     * 空值本身只缓存很短的时间（CACHE_NULL_TTL 分钟）即可起到保护作用，
     * 期间相同 id 的请求会直接命中空值，不再打到数据库；
     * 过期时间短也能减少“空值期间数据又被创建出来”导致的两端不一致窗口。
     * @param id 商铺id
     * @return 店铺不存在的失败结果
     */
    private Result handleCachePenetration(Long id) {
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
        return Result.fail("店铺不存在");
    }

    /**
     * 尝试获取锁
     * @param key 锁的key
     * @return 是否获取成功
     */
    public boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", LOCK_SHOP_TTL, TimeUnit.MINUTES);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     * @param key 锁的key
     */
    public void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    /**
     * 更新商铺信息
     * @param shop 商铺信息
     * @return 更新结果
     */
    @Override
    @Transactional
    public Result update(Shop shop) {

        Long shopId = shop.getId();
        if (shopId == null) {
            return Result.fail("店铺id不能为空");
        }
        // 1、更新数据库
        updateById(shop);

        // 2、删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shopId);
        return Result.ok();
    }


}
