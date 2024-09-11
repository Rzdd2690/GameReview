package com.hmdp;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.entity.Shop;
import com.hmdp.dto.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
class HmDianPingApplicationTests extends ServiceImpl<ShopTypeMapper, ShopType> {
    @Resource
    CacheClient cacheClient;
    @Resource
    IShopService shopService;
    @Resource
    RedisIdWorker redisIdWorker;

    private ExecutorService es = Executors.newFixedThreadPool(500);
    @Test
    public void test(){
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY+1L,shop,5L, TimeUnit.SECONDS);

    }
    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 30; i++) {
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));
    }

    @Test
    @Transactional
    @Rollback(value = false)
    // Spring 自动提供了@Rollback注解并置为true（无论方法是否抛出异常（检查异常和非检查异常，事务都会回滚））
    public void method1() throws FileNotFoundException {//抛出检查异常
        ShopType shopType = new ShopType();
        shopType.setName("123木头人");
        shopType.setSort(100);
        save(shopType);
        FileInputStream fis = new FileInputStream("D://a.txt");
    }

}
