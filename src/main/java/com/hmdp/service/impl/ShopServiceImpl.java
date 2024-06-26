package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import io.netty.util.internal.StringUtil;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author ashensky
 * @since 2024-6-20
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;
    @Override
    public Result queryById(Long id) {
        // 解决缓存穿透
        // Shop shop = quetyWithPassThrough(id);
        // 互斥锁解决缓存击穿
        // Shop shop = queryWithMutex(id);
        // 逻辑延迟 + 互斥锁解决缓存击穿
        Shop shop = queryWithPassLogicalExpire(id);
        if(shop == null) {
            return Result.fail("店铺不存在");
        }
        // 互斥锁解决缓存击穿
        return Result.ok(shop);
    }
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    public Shop queryWithPassLogicalExpire(Long id) {
        // 首先是判断是否命中
        // 命中了则看是否过期
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            return null;
        }
        // 命中是否过期->也就是要反序列化
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();

        // 判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            return shop;
        }
        // 已过期，需要缓存重建
        String lockkey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockkey);
        if(isLock) {
            // 开启独立线程
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unlock(lockkey);
                }
            });
        }
        // 数据库存在
        return shop;
    }
    public Shop queryWithMutex(Long id) {
        // 先查询redis是否命中
        // 未命中尝试获取互斥锁
        // 获得互斥锁之后查询数据库
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 存在 直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 判断是否命中的空值
        if(shopJson != null) {
            // 返回错误信息
            return null;
        }

        // 实现缓存重建
        // （1） 获得互斥锁
        String lockKey = "lock:shop:" + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            // （2） 判断是否获取成功
            if(!isLock) {
                // （3） 失败休眠并且进行重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 不存在 通过id查询数据库
            shop = getById(id);
            // 模拟延迟
            Thread.sleep(200);
            // 数据库不存在
            if(shop == null) {
                // 将空值写入redis
                stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "",CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop));
            // 数据库存在
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        // 释放互斥锁
        unlock(lockKey);
        return shop;
    }
    public Shop queryWithPassThrough(Long id) {
        String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 存在 直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        // 判断是否命中的空值
        if(shopJson != null) {
            // 返回错误信息
            return null;
        }
        // 不存在 通过id查询数据库
        Shop shop = getById(id);
        // 数据库不存在
        if(shop == null) {
            // 将空值写入redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "",CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop));
        // 数据库存在
        return shop;
    }

    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        // 查询数据
        Shop shop = getById(id);
        Thread.sleep(200);
        // 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(redisData));

    }
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result update(Shop shop) {
        // 更新数据库
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("id不要输入空");
        }
        updateById(shop);
        // 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1、判断需不需要进行坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，直接进行数据库查询
            // 根据店铺类型id进行分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        // 2、计算分页参数（page，pagesize）
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3、完成三个操作：查询redis、按照距离排序、分页。结果内容：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000), // 周围5000米范围
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4、获取集合，解析出shopId
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent(); // 获取集合
        // 判断集合和分页头的大小，如果list小于from，skip跳过后就没内容了，就会报空指针异常
        if (list.size() <= from) {
            // 没有下一页，不能滚动翻页了，直接返回
            return Result.ok(Collections.emptyList());
        }
        // 4.1、截取从from ~ end的部分(subList、或者stream流都可以，这里用stream流的方式：不需要拷贝集合，只是跳过，更加节省内存)
        // 通过list列表去收集后面的店铺id
        List<Long> ids = new ArrayList<>(list.size());
        // 通过Map集合让id和distance一一对应(key: id字符串，value：Distance)
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> { // 跳过再遍历
            // 4.2、获取店铺id
            String shopIdStr = result.getContent().getName();
            // 4.3、将id转换为Long型，存入用于后面查询获得对象
            ids.add(Long.valueOf(shopIdStr));
            // 4.4、获取店铺到当前用户的距离
            Distance distance = result.getDistance();
            // 4.5、id和distance做匹配，一起返回
            distanceMap.put(shopIdStr, distance);

        });
        // 5、根据shopId查询数据库获得shop对象
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        // 6、将店铺和距离一一对应合并在一起
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 7、返回
        return Result.ok(shops);
    }
}
