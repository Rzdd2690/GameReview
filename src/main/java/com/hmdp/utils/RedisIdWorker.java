package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
@Component
public class RedisIdWorker {
    private static final long BEGIN_TIMESTAMP = 1704067200;
    private static final int COUNT_BITS = 32;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String keyPrefix) {

        // 1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowTime = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowTime-BEGIN_TIMESTAMP;
        // 2.生成序列号
        // 2.1.获取当前日期，精确到天
        // Redis中，冒号表示分层，即最终表示的年月日有三层，便于后续统计日订单，月，甚至年订单
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2.自增长,count为序列号，即在同一个时间戳中下单的数量，自增
        // 这里用Redis的自增，即increment方法，所以要用Redis，做一个工具桥梁
        // 这样别人不能根据两个时间下单，来判断ID之间的差值来判断时间段一共有多少单等隐私信息
        // 下面不会报空指针异常，因为increment方法，如果没有Key，他会默认创建一个Key
        // key末尾加了当前时间，保证了每天的序列化都是从0开始
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        // 3.拼接并返回
        // 当前时间戳左移32位，然后将结果与序列号进行异或运算
        return timeStamp << COUNT_BITS | count;
    }
}
