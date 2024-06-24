智林笔记——用他人的经验来让您少走弯路



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
