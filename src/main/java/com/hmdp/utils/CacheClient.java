package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

@Component
@Slf4j
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * * 方法1：将任意Java对象序列化为json并存储在string类型的key中，并且可以设置TTL过期时间
     * * 方法2：将任意Java对象序列化为json并存储在string类型的key中，并且可以设置逻辑过期时间，用于处理缓存击穿问题
     * * 方法3：根据指定的key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
     * * 方法4：根据指定的key查询缓存，并反序列化为指定类型，需要利用逻辑过期解决缓存击穿问题将逻辑进行封装
     */
    // 方法1
    public void set(String key ,Object value , Long time , TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }
    // 方法2
    public void setWithLogicalExpire(String key , Object value , Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        String jsonStr = JSONUtil.toJsonStr(redisData);
        stringRedisTemplate.opsForValue().set(key,jsonStr);
    }
    // 方法3 解决缓存穿透，即访问不存在的值直接缓存空值，避免被多次访问数据库
    public <R,ID> R queryWithPassThrough( String KeyPrefix,ID id, Class<R> type
            , Function<ID,R> callBack,Long time ,TimeUnit unit){
        String key = KeyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 判断是否存在
        if(StrUtil.isNotBlank(json)){
           return JSONUtil.toBean(json,type);
        }
        // 不存在的话判断判断是否为“ ”，为的话说明这个值是数据库没有的，直接返回null
        if(json!=null){
            return null;
        }
        // 为null【说明redis没有】，则根据id查数据库
        R r = callBack.apply(id);
        // 数据库没有，则将当前值放入缓存中，以便下次再遇到的话可以直接返回null，有的话直接存入缓存中
        if(r==null){
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        this.set(key,JSONUtil.toJsonStr(r),time,unit);
        return r;
    }
    // 方法4 利用逻辑过期解决缓存击穿，即要用到线程池，互斥锁，以及RedisData对象进行逻辑时间的封装
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public <ID,R> R queryWithLogicalExpire(String KeyPrefix , ID id , Class<R> type ,
                                           Function<ID,R> callBack,Long time , TimeUnit unit){
        String key = KeyPrefix+id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isBlank(json)){
            return null;
        }
        RedisData redisData = JSONUtil.toBean(json ,RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.1获取旧数据
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        // 6.判断是否过期，不管过期与否，最后都是返回旧数据，过期的话多了一个拿锁开线程的操作
        // 未过期，这个旧数据就是新数据
        if (expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }
        // 过期了，就要进行缓存重建
        String lockKey = LOCK_SHOP_KEY+id;
        boolean isLock = tryLock(lockKey);
        if(isLock){
            if(expireTime.isAfter(LocalDateTime.now())){
                String json2 = stringRedisTemplate.opsForValue().get(key);
                RedisData redisData2 = JSONUtil.toBean(json2, RedisData.class);
                JSONObject data1 = (JSONObject) redisData2.getData();
                return JSONUtil.toBean(data1,type);
            }
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    R r1 = callBack.apply(id);
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unLock(lockKey);
                }
            });
        }
        return r;
        }

    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return flag!=null && flag;
    }
    private void unLock (String key){
        stringRedisTemplate.delete(key);
    }

}
