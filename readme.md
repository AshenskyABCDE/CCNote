# 智林笔记——来找到和你兴趣志同道合的人吧!

# 介绍

本项目通过学习黑马程序员redis的讲解来实现，并举一反三实现而成，列入我在原本的功能上加入了帖子回复和分页查询回复的功能，这是原来没有的。此外还有自己运用对redis的理解 对帖子评论加上redis分布式锁来实现判断是否有过回复，从而在高并发的情况下大大减轻对数据库造成的压力。



如图 前端发送后 通过后端处理存到数据库

![Description](https://raw.githubusercontent.com/AshenskyABCDE/CCNote/main/images/010.png)





如图 通过后端对数据库进行分页处理

<img src="https://github.com/AshenskyABCDE/CCNote/blob/main/images/011.png?raw=true">



为了不占用过多篇幅，我将在下面的实现逻辑说我编写项目的历程。

具体界面图在images文件夹，由于图片过大，便不在此页面展示，本页面只对项目进行实现讲解。

# 实现逻辑：

## 1.短信登录的实现

### 1.1 实现逻辑简述

对于浏览器登录来说，是通过session缓存数据的，而用户名可以存放在我们的数据库里，因此 当我们生成验证码的时候可以把验证码先存放在session里再发送。之后我们可以用用户填写的验证码和session里的比较来判断填写是否正确。

当然对于错误的填写，我们要发送相关的提示，对登录也要有拦截功能。



### 1.2 登录校验的逻辑

这里通过session获取user 也就是用户，如果用户不存在对登录进行拦   截，之后无论是新注册的还是之前有的都存放到ThreadLocal里

### 1.3 解决session共享问题

问题出现：多台tomcat并不共享session存储空间，当请求切换到不同tomcat服务器导致数据丢失。

应该从 数据共享，内存存储，key-value结构方面考虑。

因此我们可以在生成验证码的时候，保存验证码到redis中。

我们可以将手机号作为key 验证码作为value，就不用带着信息来取，只需要在redis去get 手机号就行。同时我们也将随机token作为key来存储用户数量。其中用String在内存 性能上会有问题，我们可以考虑用Hash实现



### 1.3 改进

（1） 在session中的user 实际上只有少数是有用的属性，如果每次传参的属性都是user的话，会造成大量的内存浪费，以及在安全上出现一些问题，所以封装成userDTO。

（2） 如果token的有效期只有三十分钟，用户在浏览的情况下，三十分钟后也要再登录，所以我们应该再设置一个拦截器。

解决办法 就是设置两个拦截器，第一个拦截器拦截所有路径，在这里有存储用户信息和刷新token的作用，第二个拦截器拦截登录页面从而判断是否要登录，这样只要访问一个页面就会刷新token。

### 1.4 小结

对需要登录的时候，拦截器会选获取token，然后查询redis的用户是否存在，将信息保存到ThreadLocal，并刷新token的有效期。



## 2.查询缓存

### 2.1 模型逻辑

在查询的时候，首先是在浏览器缓存里查找，然后是tomcat缓存再是数据库缓存，也就是客户端和数据库的交互查询，这个时候可以用redis作为中间件，客户端->redis->数据库来减轻对数据库的查询的压力。

### 2.2 改进

（1） 对于每个分类里面具体的进行优化

（2） 对分类列表进行优化



### 2.3 缓存更新策略

内存淘汰： 内容类型查询的缓存

超时删去：

业务删去：内容详情查询



对于操作缓存，我们最好用删除缓存：每次更新数据库都更新缓存，无效写的操作较多

查询的缓存增加超市删去和主动更新：

用id查询时，如果缓存未命中，则查询数据库，将数据库结果写入缓存，并设置超时时间

根据id修改时，先修改数据库，再删除缓存。

### 2.4 redis可能遇到的问题

#### 缓存穿透

指的是客户端请求的数据在缓存中和数据库都不存在，这些缓存不会生效，这些请求会打到数据库。

解决方法: 

1.记录null空缓存（1.会导致额外的内存消耗 2.可能造成短期的不一致，比如说一开始数据库没有，但是后来数据库加上了该数据，就会短时期不一致）

我们有null空值的可以先用redis缓存起来，就不用多次去查询数据库

2.布隆过滤器 

3. id设置复杂一些，降低猜到的概率
4. 做好数据的基本格式的校验
5. 增加用户的权限校验

#### 缓存雪崩

缓存雪崩指在同一时段大量key同时失效或者Redis服务宕机，导致大量数据到达数据库，带来巨大压力

解决方案：

1.对key设置随机的TTL

2.分布式集群搭建Redis



#### 缓存击穿

也叫热点Key问题，就是一个被高并发访问并且缓存重建业务较复杂的key突然失效了，无数请求会给数据库带来巨大的冲击。

![002](.\images\002.png)

解决方法：加入互斥锁

![001](.\images\001.png)



另外一种方法是设置逻辑超时，判断命中缓存之后是否逻辑超时

### 2.5 基于互斥锁解决缓存击穿的问题

在这个业务逻辑，我们要实现的是 当查询缓存未命中的时候，应该先获取互斥锁，我们这个时候要先等待一会儿，再去数据库进行查询，查询完之后再释放。

```java
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
```



### 2.6 基于逻辑过期方式解决缓存击穿的问题

对于每一个key设置一个TTL，如果缓冲命中超过了时期，就要对数据进行重建

```java
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
```



### 2.7 bug记录

（1） 修改了潜在的逻辑问题 : 在修改数据库时，应该先判断是否为空，不应该先修改数据库。

（2） 修改时，参数header应该带上token，否则会出现401错误



### 2.8 总结



## 3.优惠券

### 3.1 优惠券的背景和设计

对于一些网站都存在着优惠券限时抢购的活动，作为智林日记也会有这样的操作。

但是对于id来说不能设置的太规律，这样会让别人看出来优惠券的销售情况

但是对于数据库来说，又必须作为全局唯一的id。

采取策略 id可以用 时间戳+自增来组成，比如说前32位是当前时间戳，后32位是商品自增的数量。

### 3.2 实现优惠券秒杀的下单功能

首先，要判断这个时间是否是优惠券的开始和结束的范围之内

之后再判断库存是否足够，否则无法下单。

具体代码实现就是根据Iservice 通过优惠券Id来获取优惠券的信息，之后通过判断库存和时间来判断是否可以进行抢购。

### 3.3并发多线程的问题

使用jmeter用200个线程进行模拟，发现库存减为负数，这是因为当一个线程在即将更改的时候，另外一个线程正在判断，从而发生误判，解决方法应该上锁。

我们可以用乐观锁的思想，对其增加版本号

```
查询时 版本号为1
修改时 版本号仍然为1 说明之前没有被修改过
如果版本号是2 说明进行了修改
```

当然可以只对stock的数值进行判断 之前有没有被修改过，这就是CAS法，其思路和版本号差不多。

最初修改是在判断这里判断stock是否和之前一样

```java
boolean state = seckillVoucherService.update()
        .setSql("stock = stock - 1") // set stock = stock - 1
        .eq("voucher_id",voucherId).eq("stock", voucher.getStock()) // where id = ? and stock = ?
        .update();
```

这样安全性大大提高，但是抢购的数量也就大大减小，这是因为很多用户的情况下 前后的数量发生了修改

思考后，stock只要>0就可以去进行减少，不一定非得要前后相同，因此进行了优化

```java
        boolean state = seckillVoucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id",voucherId).gt("stock", 0) // where id = ? and stock > 0
                .update();
```



此外我们要实现一人一单，应该基于userId来判断，具体可以判断数据库里面当前用户的数量。

```java
    public  Result createVoucherOrder(Long voucherId) {
        // 进行一人一单的判断
        Long userId = UserHolder.getUser().getId();

            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            if (count > 0) {
                return Result.fail("该用户已经买过一个了");
            }
            boolean state = seckillVoucherService.update()
                    .setSql("stock = stock - 1") // set stock = stock - 1
                    .eq("voucher_id", voucherId).gt("stock", 0) // where id = ? and stock > 0
                    .update();
            if (!state) {
                return Result.fail("库存不足");
            }
            // 创建订单 优惠券Id 用户Id 购物Id
            VoucherOrder voucherOrder = new VoucherOrder();
            long Id = redisIdWorker.nextId("order");
            voucherOrder.setId(Id);
            voucherOrder.setUserId(UserHolder.getUser().getId());
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);
            // 返回订单id
        return Result.ok(voucherId);
    }
```

但是应当对这整个流程进行加锁，这个锁不可以放到程序里面，这样的话就会出现并发上的问题，如有些用户提交的快，这些数据没有写到数据库里。

应该对这个函数进行加锁

```java
        Long userId = UserHolder.getUser().getId();
        synchronized (userId.toString().intern()) { // 应该把这一个函数都上锁
            // 获取代理对象
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
```

当然这里只能处理单机的并发，因为对于一台JVM有相应的锁监视器，所以遇到分布式的集群的时候，第二台JVM是认为当前没有锁的，所以可能会出现业务逻辑上的错误，这个时候我们便要实行分布式锁，也就是redis上的分布式锁。

### 3.4基于Redis的分布式锁

获取锁：

采取互斥的手段，确保只能有一个线程获取锁

```
SETNX lock thread1
EXPIRE lock 5 // 对锁加上时限，从而避免服务器宕机引起的死锁

可能会遇到SET之后还没有EXPIRE 就发生宕机，因此应该在SET时就加上时限
SET lock thread1 EX 10 NX
```

释放锁:

手动释放

```
DEL KEY
```

因此对于分布式锁的业务逻辑是：

上锁->如果上成功锁->释放锁

如果没上成功锁->等待业务超时->释放锁



总结：

对于一台电脑的用户上锁，我们应该封装函数对这个整个上锁，但是由于一个JVM 锁监视器只有一个，所以进而引出我们的分布式锁去解决业务问题。

```java
        // 获取用户信息
        Long userId = UserHolder.getUser().getId();
        // 将其主动上分布式锁
        SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);

        boolean islock = lock.tryLock(1200);
        if(islock) {
           return Result.fail("一个人只允许下一单");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            lock.unlock();
        }
```

### 3.5分布式锁误删问题

![004](.\images\004.png)



当然上述也会存在一些逻辑的问题，比如说线程1处于业务阻塞状态，超时了 redis会自动释放锁，但是这个时候线程1如果业务完成 按逻辑来说会释放锁，这个时候redis会误以为当前阶段的线程业务完成所以进行锁的释放，此时出现了当前redis锁释放与线程不匹配的问题。![005](D:\智林笔记\images\005.png)



解决方法就是判断当前redis是否和要解决的线程是否一致

也可能出现原子性的问题：比如说当我们遇到垃圾回收导致业务超时的时候可能会导致锁释放，而线程事务应该是一个原子性操作，我们应该对其进行处理。

对于原子性，我们可以用lua脚本来进行解决，因为lua可以在事务结束之前对其他进程进行堵塞

lua脚本判断锁的标识和进程的标识是否相同

```lua
---
--- 比较线程标识和锁的标识是否一样
if(redis.call('get',KEYS[1]) == ARGV[1]) then
    return redis.call('del',KEYS[1])
end
return 0
```

调用一行代码就不会出现原子性问题

```java
    @Override
    public void unlock() {
        // 用lua脚本来实现原子性
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                                    Collections.singletonList(KEY_PREFIX + name),
                                            ID_PREFIX+ Thread.currentThread().getId());

    }
```

总结就是

现用setnx 获取锁满足互斥性，并设置过期时间，保存线程表示

释放锁的时候要保证线程的标识和自己是否一致，一致则删除锁。



### 3.6Redisson

首先要有一个配置类

```java
@Configuration
public class RedissonConfig {
    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://192.168.245.129:6379").setPassword("123456");
        return Redisson.create(config);
    }
}
```



之后就可以用redisson进行分布式锁

```java
         RLock lock = redissonClient.getLock("lock:order:" + voucherId + userId);
        boolean islock = lock.tryLock();
        if(!islock) {
           return Result.fail("一个人只允许下一单");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            if (lock.isLocked()) {
                lock.unlock();
            }
        }
```

#### 不可重复读的问题

如果一个线程里A函数调用B函数，而AB函数均要上锁，那么就会拒绝上锁。这个用到juc的思想ReentrantReadWriteLock。![006](D:\智林笔记\images\006.png)

其原理就是增加一个锁计数，每次调用的时候判断是不是当前标识和自己一样（同一个线程），如果是同一个线程，在函数开始的时候锁计数加1，在函数结束的时候锁计数-1。

### 3.7优化秒杀

![007](D:\智林笔记\images\007.png)

我们可以将判断库存、创建订单放到一个阻塞队列里，这样每次订单可以从这个队列里进行查询。



创建优惠券的时候把优惠券的信息写到redis里

基于lua脚本，判断库存数量，一人一单，秒杀是否有效

如果成功，把优惠券id和用户id封装放入阻塞队列里

开启线程任务，不断从阻塞队列里获取信息，完成下单任务。

#### 阻塞队列优化总结：

思路转换同步为异步，将创建和下单分开执行，就不需要等待。

存在问题：

内存限制问题：队列的数量大小

数据安全问题：取出来发生异常，则会出现数据丢失问题

### 3.8 消息队列解决阻塞队列异常的问题

1.基于redis list的消息队列

2.pubsub实现消息队列

3.Stream



流程:

1.采用Stream类型的消息队列

2.下单创建订单之后，有购买资格的放入消息队列里

3.采用线程任务，不断读取消息队列里面的优惠券信息。

```shell
XGROUP CREATE stream.orders g1 0 MKSTREAM
## 创建g1消费者组 MKSTREAM是如果没有则创建
XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS streams.order
## 表示消费者c1从消费者组g1获取数量为1的元素
```



### 3.9注意

我们对一个用户上锁的时候，key应该只和id有关，value可以用uuid，如果key加上uuid那么就会对一个用户上锁无效。



## 4.笔记功能

### 4.1 发送文章

在这里我们模拟去发送图片和文章内容，在发布的时候，将文章的标题内容保存至数据库，图片我们保存至前端，这样前端读取的时候就从本地存储中获取。

### 4.2 查看文章

点赞文章很好处理，只需要用sql语句进行加的操作即可，问题主要是判断一个人对一个帖子只能点一次赞，如果点过赞了 再点就是取消赞。

主要实现可以用redis，无论是用集合set还是正常的String数据结构思路都是一样的

SET思路

```java
    @Override
    public  Result likeBlog(Long id) {
        // 获取登录用户
        Long userId = UserHolder.getUser().getId();
        String key = "blog:liked1:" + id;
        // 判断是否点赞过
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        if(BooleanUtil.isFalse(isMember)) {
            // update tb_blog set like = like -1 where id = "id"
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id",id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForSet().add(key, userId.toString());
            }
        } else {
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id",id).update();
            if (isSuccess) {
                stringRedisTemplate.opsForSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }
```

String判断的方法

```java
    @Override
    public Result likeBlog2(Long id) {
        Long userId = UserHolder.getUser().getId();
        String value = stringRedisTemplate.opsForValue().get("blog:liked2:" + userId + id);
        if(value == null || value == "remove") {
            Boolean isSuccess = update().setSql("like = like + 1").eq("id",id).update();
            if(isSuccess) {
                stringRedisTemplate.opsForValue().set("blog:liked2:" + userId +id, "add");
            }
        } else {
            Boolean isSuccess = update().setSql("like = like - 1").eq("id",id).update();
            if(isSuccess) {
                stringRedisTemplate.opsForValue().set("blog:liked2:" + userId +id, "remove");
            }
        }
        return Result.ok();
    }
```

### 4.3 点赞排行榜

由于设置排行榜的功能，改用zset类型进行存储。

### 4.4 好友关注

这里比较好处理，简单就是点击关注的时候会将你的user_id和关注的user_id放到数据库里，而按钮是否关注和没有关注是放到前端里去进行的，在之后版块讲解前端代码会提到。

#### 共同关注

在接口时创建方法

```java
    @GetMapping("/common/{id}")
    public  Result followCommons(@PathVariable("id") Long id) {
        return followService.followCommons(id);
    }
```

在这个方法里实现，只需要返回两个set的交集即可，这里从Set转到List的映射很精髓。

```java
    @Override
    public  Result followCommons(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        String key1 = "follows:" + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key1);
        System.out.println(intersect);
        if(intersect == null || intersect.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user-> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
```

#### 关注推送（待整理）

之前的方式是想搜索东西是通过查找，有了抖音之后我们的娱乐方式就发生了改变。

模仿抖音实现投喂，可以无限往下拉刷到类似的作品。

实现思路：

新增探店笔记的业务，在保存blog到数据库的同时，推送到粉丝的收件箱。 收件箱需要满足时间戳的排序，用redis进行实现。查询收件箱的时候，可以分页查询。

#### 4.5 附近功能

redis的GEO可以存储地理坐标，我们根据经纬度进行检索。

其本质是通过坐标的距离检索，返回出与其他坐标的距离。

```shell
GEODIST g1 A B
## 返回A和B的距离

```

代码实现部分有点多，首先是通过geo的距离进行排序 然后对这些数据进行分页，然后要解析出商店的id，之后将distance和商家的id一一进行对应。

```java
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
```

#### 4.6 用户签到

如果要用mysql建表的话，这个数据是很大的，一个人签到10次，一百万人就是签到一千万次。我们可以通过二进制的思想，在一个月中某天签到就对应是二进制的某位上为1，可以用bitmap实现。

```
setbit
bitcount

```

记录当前的日期然后存到bitmap里

```java
    @Override
    public Result sign() {
        // 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 获取日期
        LocalDateTime now = LocalDateTime.now();
        // 拼接key
        String keySuf = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = "sign:" + userId + keySuf;
        // 今天是本月的第几天
        int day = now.getDayOfMonth();
        // 写入redis SETBIT key offset
        stringRedisTemplate.opsForValue().setBit(key, day - 1, true);
        return Result.ok();
    }
```

查询连续签到

补签

```shell
setbit key days 1
```

获取某一天的签到情况

```
BITFIELD key GET u<day> 0
```

获取连续签到情况

将二进制转为十进制，然后判断末尾有连续多少的天数

```java
    @Override
    public  Result signCount() {
        // 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 获取日期
        LocalDateTime now = LocalDateTime.now();
        // 拼接key
        String keySuf = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = "sign:" + userId + keySuf;
        // 今天是本月的第几天
        int day = now.getDayOfMonth();
        // 获取本月截止今天为止的签到记录
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key, BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(day)).valueAt(0)
        );

        if(result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        Long num = result.get(0);
        if(num == null || num == 0) {
            return Result.ok(0);
        }
        System.out.println(key);
        int count = 0;
        while (true) {
            if((num & 1) == 0) {
                // 未签到
                break;
            } else {
                count ++;
            }
            num = num >> 1;
        }
        return Result.ok(count);
    }
```

