package com.hmdp;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Resource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import com.hmdp.utils.RedisGlobalIdGenerator;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private RedisGlobalIdGenerator redisGlobalIdGenerator;
    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    public void testIdGenerator() throws InterruptedException {
        CountDownLatch countDownLatch = new CountDownLatch(300);
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisGlobalIdGenerator.nextId("order");
                System.out.println("id:" + id);
            }
            countDownLatch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int j = 0; j < 300; j++) {
            es.submit(task);
        }
        countDownLatch.await();
        long end = System.currentTimeMillis();
        System.out.println("time:" + (end - begin));
    }

}
