package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    StringRedisTemplate stringRedisTemplate;
    @Resource
    CacheClient cacheClient;
    @Override
    public Result queryById(Long id) throws InterruptedException {
        //Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id,Shop.class,
        //        this::getById,CACHE_NULL_TTL,TimeUnit.MINUTES);
    Shop shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY,id,Shop.class,
            this::getById,CACHE_SHOP_TTL,TimeUnit.SECONDS);
        // 缓存穿透
        // Shop shop = QueryWithPassThrough(id);
        //  缓存击穿——互斥锁
        //  Shop shop = QueryWithMutex(id);
        //  Shop shop = QueryWithLogicExpire(id);
        if(shop == null){
          return Result.fail("店铺不存在");
      }
        return Result.ok(shop);
    }

  /*  /**
     * 缓存击穿——逻辑过期解决方案
     * @param id
     * @return
     * @throws InterruptedException
     */
    // 线程池，如果是每次都自己新建一个性能不好
  /*  private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public Shop QueryWithLogicExpire(Long id) throws InterruptedException {
        String key = CACHE_SHOP_KEY + "id";
        // 1.从Redis查询数据
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 2.判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            // 3.未命中，则直接返回null，说明不是热点数据，无所谓
            return null;
        }
        // 4.命中则需要反序列化为原对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        // 5.获取逻辑过期时间
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.1获取旧数据
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        // 6.判断是否过期，不管过期与否，最后都是返回旧数据，过期的话多了一个拿锁开线程的操作
        if (expireTime.isAfter(LocalDateTime.now())) {
            return shop;
        }
        // 7.逻辑过期的话获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2.判断是否获取锁成功
        if (isLock) {
            // 拿到锁之后，还要再次判断缓存是否逻辑过期，防止一种情况：
            // 这边刚释放锁，修改了缓存，另一边拿到了锁，这要会对数据库多访问一次。
            if(expireTime.isBefore(LocalDateTime.now())){
                String shopJson2 = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY+id);
                RedisData redisData2 = JSONUtil.toBean(shopJson2, RedisData.class);
                JSONObject data1 = (JSONObject) redisData2.getData();
                return JSONUtil.toBean(data1, Shop.class);
            }
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);
                }
            });
        }
        return shop;
    }
    /**
     * 缓存击穿——互斥锁解决方案
     * @param id
     * @return
     * @throws InterruptedException
     */
   /* public Shop QueryWithMutex(Long id) throws InterruptedException {
        String key = CACHE_SHOP_KEY +"id";
        // 1.从Redis查询数据
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 2.判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
        // 3.缓存中有，则直接返回
        // 存在的话将查询到的Json对象转化为对象，并返回
            return JSONUtil.toBean(shopJson,Shop.class);
        }
        if (shopJson!=null){
            return null;
        }
        // 4.实现缓存重建
        // 4.1获取互斥锁
        String lockKey = LOCK_SHOP_KEY+id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            if (!isLock) {
                Thread.sleep(50);
                return QueryWithMutex(id);
            }
            // 拿到了锁还是要重新检查一下缓存是否存在，防止一种情况：
            // 这边刷新了缓存释放了锁，那边又拿到了锁，这样会对数据库进行二次访问
            if(StrUtil.isNotBlank(stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY+id))){
                String shopJson2 = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY+id);
                return JSONUtil.toBean(shopJson2, Shop.class);
            }
            // 3.不存在则查询数据库
             shop = getById(id);
            // 4.没有则返回404
            if (shop == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            // 5.有则添加至Redis，并且返回
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        }
        catch(InterruptedException e){
            throw new RuntimeException(e);
        }
        finally {
            // 释放互斥锁
            unLock(lockKey);
        }
        return shop;
    }*/

    /**
     * 缓存穿透，即，如果请求的是不存在的东西，直接返回null，同时将这个null缓存起来
     * @param
     * @return
     */
    /*public Shop QueryWithPassThrough(Long id){
        String key = CACHE_SHOP_KEY +"id";
        // 1.从Redis查询数据
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if(StrUtil.isNotBlank(shopJson)){
            // 缓存中有，则直接返回
            //isNotBlank 在值为null以及“”,以及换行符都是false,即只有对象为真是的数据时才为true，其他均为false
            // 存在的话将查询到的Json对象转化为对象，并返回
          return JSONUtil.toBean(shopJson,Shop.class);
        }
        //命中的是否为空值 ”“，为空值说明这个值已经缓存过了，直接返回，不需要查数据库
        if (shopJson!=null){
            return null;
        }
        // 3.不存在则查询数据库
        Shop shop = getById(id);
        // 4.没有则返回404
        if(shop==null){
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        // 5.有则添加至Redis，并且返回
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL,TimeUnit.MINUTES);
        return shop;
    }

    private boolean tryLock(String key){
          Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
          return flag!=null && flag;
    }
    private void unLock (String key){
        stringRedisTemplate.delete(key);
    }
    public void saveShop2Redis(Long id , Long expireTime){
        Shop shop = getById(id);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }*/
    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id==null){
            return Result.fail("商家id不能为空");
        }
        updateById(shop);
        stringRedisTemplate.delete(CACHE_SHOP_KEY+ shop.getId());
        return Result.ok();
    }
}
