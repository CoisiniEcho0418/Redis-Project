package com.hmdp;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import javax.annotation.Resource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisGlobalIdGenerator;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private RedisGlobalIdGenerator redisGlobalIdGenerator;

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

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

    @Test
    void testsavaRedisData() {
        shopService.savaRedisData(1L, 100L);
    }

    @Test
    void loadShopData() {
        // 1.查询店铺信息
        List<Shop> shopList = shopService.list();
        // 2.把店铺分组，按照typeId分组，typeId一致的放到一个集合
        Map<Long, List<Shop>> shopListMap = shopList.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 3.分批完成写入Redis
        for (Map.Entry<Long, List<Shop>> mapEntry : shopListMap.entrySet()) {
            // 3.1.获取类型id
            Long typeId = mapEntry.getKey();
            String key = SHOP_GEO_KEY + typeId;
            // 3.2.获取同类型的店铺的集合
            List<Shop> shops = mapEntry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(shops.size());
            // 3.3.写入redis GEOADD key 经度 纬度 member
            for (Shop shop : shops) {
                locations.add(
                    new RedisGeoCommands.GeoLocation<>(shop.getId().toString(), new Point(shop.getX(), shop.getY())));
            }
            stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }
}
