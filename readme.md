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
