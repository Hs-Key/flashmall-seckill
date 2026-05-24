# FlashMall 项目亮点解析（面试版）

> 高并发秒杀商城。Spring Boot 3.3 + Java 17 + MySQL 8 + Redis 7 + RabbitMQ 3。
> 代码量约 3.4K 行，核心模块：用户/JWT、商品缓存、秒杀、订单、AOP 公共能力。

---

## 一、项目定位与核心难点

秒杀场景的工程问题不是"卖货"，而是在 **瞬时高并发 + 库存有限 + 强一致性** 三个相互冲突的约束下找平衡。本项目需要同时回答四个问题：

| 难点 | 本项目的回答 |
| --- | --- |
| 千 QPS 写库扛不住怎么办？ | Redis 预扣 + MQ 异步落库（削峰填谷） |
| 怎么保证不超卖？ | 三层防线串联：本地标记 → Redis Lua → DB 乐观锁 |
| 同一用户重复点击怎么办？ | Lua 用户集合 + 幂等 Token + 幂等键唯一索引 三重幂等 |
| 待支付订单 30 分钟不付款怎么办？ | RabbitMQ TTL + 死信队列（不用定时任务） |

下面挑出 6 个最有讲头的设计点，每个都拆到"为什么 / 怎么做 / 边界场景"三层。

---

## 二、亮点 1：三层防超卖串联设计

**位置**：`SeckillServiceImpl#doSeckill` + `RedisLuaScript.SECKILL_SCRIPT` + `SeckillActivityMapper#decreaseStockWithVersion`

### 设计理念
每一层都不是孤立的"加锁"，而是按 **延迟从低到高、过滤强度从粗到细** 排列，越靠前的层挡住的请求越多，越靠后的层精度越高。

```
请求 ──► [L1 本地 ConcurrentHashMap]  纳秒级，售罄后单机直接拒
        ──► [L2 Redis + Lua]          毫秒级，集群级原子扣减
              ──► [L3 DB 乐观锁]      最终兜底，version + stock>0
```

### 关键点拆解

**L1 本地内存标记（`emptyStockMap`）**
- 售罄后单机标记 `true`，后续请求 **连 Redis 都不打**。
- 用 `ConcurrentHashMap` 而非 `HashMap`：多线程并发读写下 HashMap 在 JDK8 后虽然不死循环但仍可能丢数据；`ConcurrentHashMap` 用分段/CAS 保证可见性。
- **缺陷我会主动讲**：多实例部署时每台机器各自维护一份标记 → 没问题，因为它只是优化项，丢了也会被 L2 拦住；不需要跨实例同步。

**L2 Redis Lua 脚本（最核心的一层）**
脚本里做了三件事：`sismember 判断重复` → `get 库存检查` → `decr + sadd`。三件事必须在 **同一 Lua 调用** 里完成。
为什么不能拆开？比如先 `get` 后 `decr`：
```
T1: get stock = 1
T2: get stock = 1     ← 两个线程读到同一个值
T1: decr → 0          ← 都扣减
T2: decr → -1         ← 超卖
```
Redis 单线程 + Lua 原子执行 = 单机版乐观锁。返回值用 `-1 / 0 / 1` 三态区分**重复购买、库存不足、扣减成功**，让上层针对性处理。

**L3 数据库乐观锁**
```sql
UPDATE t_seckill_activity
SET stock = stock - 1, version = version + 1
WHERE id = ? AND version = ? AND stock > 0
```
- 正常路径下 L2 已经把请求量裁剪到 ≤ 库存数，到 L3 几乎不冲突。
- L3 的真正价值是**数据一致性兜底**：Redis 宕机重启、运维误操作 reload 库存、消费者重复消费时，DB 都是最终真相。
- 为什么没用 MyBatis-Plus 的 `@Version`？因为还要同时校验 `stock > 0`，自己写 SQL 更直观也更可控。

### 面试官常追问

> Q: Lua 脚本失败了会怎样？
> A: 由 `RedisTemplate.execute` 抛异常，被 `GlobalExceptionHandler` 兜底；用户层面只感知到"系统繁忙"。Redis 的 `decr` 是原子的，要么成功要么完全没执行，不会出现"扣了一半"。

> Q: 三层都有，是不是重复？删掉某一层行不行？
> A: 删 L1：单机压力增大但功能不受损；删 L2：DB 直接被打穿；删 L3：Redis 一旦数据丢失就会超卖。**L2 是性能关键，L3 是正确性兜底，L1 是性能优化** —— 三者职责不同，不能合并。

---

## 三、亮点 2：MQ 异步下单 + 多重幂等

**位置**：`SeckillOrderProducer` → `seckill.order.queue` → `SeckillOrderConsumer` → `OrderServiceImpl#createSeckillOrder`

### 链路全景

```
用户点秒杀
   │
   ▼ 同步路径（毫秒级返回）
[Controller] → Lua 扣 Redis → 投 MQ → 返回 idempotentKey
   │                                       │
   │                                       ▼ 前端轮询
   │                                  GET /order/by-key/{key}
   ▼ 异步路径（消费者串行）
[Consumer] → DB 乐观锁扣库存 → 写订单 → 投延迟队列（30min TTL）
```

### 为什么异步？
秒杀瞬间 5000 QPS 同步写库会把 MySQL 打挂（典型 InnoDB 单实例 ~3000 TPS）。MQ 缓冲后，**消费者用 `prefetch=1` 串行处理**，DB 压力被均摊到分钟级别。代价是用户感知延迟（从"立即出单"变为"排队中→轮询出单"），但秒杀场景用户对延迟的容忍度本来就比电商常规高。

### 三重幂等怎么搭

| 层级 | 机制 | 防什么 |
| --- | --- | --- |
| 接入层 | `@Idempotent` + Redis Token "查到即删"（Lua） | 用户连点、网络重发 |
| 业务层 | Redis Set `seckill:users:{activityId}` | 同一用户多次发起秒杀 |
| 存储层 | `t_order.idempotent_key` 唯一索引 | MQ 消息重复投递 |

最巧妙的是 **MQ 消息可能因 broker 重传被消费两次** 这件事，消费者侧的兜底：
```java
} catch (DuplicateKeyException e) {
    log.warn("重复消息，幂等键已存在");
    channel.basicAck(deliveryTag, false);  // 直接 ACK，不重试
}
```
不靠 Redis、不靠业务校验，**直接让数据库唯一索引说话**：这是分布式幂等最简单也最可靠的兜底——数据库的唯一约束在跨进程、跨网络、跨重启时都成立。

### 消息可靠性的"两次 ACK"
- **生产侧**：`publisher-confirm-type: correlated` + `publisher-returns` + `mandatory: true` 三件套
  - confirm 回调：消息到达 Exchange 后 Broker 确认
  - return 回调：消息到了 Exchange 但路由不到 Queue 时回调（路由配错才会触发）
  - CorrelationData：每条消息带 UUID，confirm 回调里能定位是哪条
- **消费侧**：`acknowledge-mode: manual`，处理成功才 ACK；失败 `basicNack(requeue=false)` 转死信，**绝不无限重投**
  - 为什么不 requeue？如果 bug 永远抛异常，消息会卡死队列，让所有后续消息都饿死

### 面试官常追问

> Q: 为什么前端轮询而不是 WebSocket / SSE 推送？
> A: 工程权衡。轮询实现简单（一个 GET 接口 + setInterval），秒杀场景轮询周期 1 秒最多查 5 次就出结果，浪费的请求量可控。WebSocket 需要保持长连接，对网关、负载均衡、运维都是额外负担，秒杀业务体量不需要。

> Q: 消息消费失败后转死信，业务上怎么补救？
> A: 项目预留了 `commonDeadLetterQueue`，可对接告警系统（钉钉/企微）；运维人工排查后，要么修复数据要么手工补单。**承认无法 100% 兜底是工程态度** —— 不要承诺你做不到的事。

---

## 四、亮点 3：订单超时自动取消（死信队列模式）

**位置**：`RabbitMQConfig#orderDelayQueue` + `OrderTimeoutConsumer`

### 设计选型

需求：订单创建 30 分钟未支付，自动取消并回滚库存。

| 方案 | 缺陷 |
| --- | --- |
| 定时任务扫表（XXL-Job 等） | 大表全扫性能差；延迟最多到调度间隔；扫到一半挂掉要解决幂等重启 |
| Redis ZSet 用 score 存到期时间，定时 pop | 自己实现可靠性；轮询频率 vs 精度的权衡 |
| RabbitMQ 延迟插件（rabbitmq_delayed_message_exchange） | 需要装插件，单条消息精度高但不是所有运维环境都允许 |
| **死信队列（本项目）** | 队列级 TTL 统一，所有订单同样的超时时间正合适；零额外依赖 |

### 实现要点
```java
@Bean
public Queue orderDelayQueue() {
    Map<String, Object> args = new HashMap<>();
    args.put("x-dead-letter-exchange", ORDER_DLX_EXCHANGE);
    args.put("x-dead-letter-routing-key", ORDER_DLX_ROUTING_KEY);
    args.put("x-message-ttl", 30 * 60 * 1000);  // 30 分钟
    return QueueBuilder.durable(ORDER_DELAY_QUEUE).withArguments(args).build();
}
```
关键链路：**消息进入 `order.delay.queue`（无消费者）→ TTL 到期变死信 → 路由到 `order.dlx.exchange` → `order.dlx.queue` → 消费者 → 调用 `timeoutCancelOrder`**

### 容易踩的坑（也是面试加分点）

1. **死信不是"立刻到期"**：RabbitMQ 只在队头消息检查 TTL（懒过期），如果队头消息 TTL 是 30 分钟，后面排队的消息哪怕 1 分钟也得等队头先过期。**本项目所有订单 TTL 一致，所以没问题**；如果要支持每个订单不同 TTL，必须用插件或者分桶队列。

2. **死信路由失败会消失**：如果死信 exchange 配错了，RabbitMQ 默认丢弃，所以本项目还配了 `commonDeadLetterExchange` 作为终极兜底。

3. **取消时要把 Redis 用户集合的成员也删掉**（`OrderServiceImpl#timeoutCancelOrder`），不然用户没付钱却被永久 ban 出该活动：
   ```java
   redisTemplate.opsForSet().remove(usersKey, String.valueOf(order.getUserId()));
   ```
   这种边界 case 是面试很容易追问的点：**取消订单不仅要回滚库存，还要回滚"已购标记"**。

---

## 五、亮点 4：缓存三大问题的完整解法

**位置**：`ProductServiceImpl#getProductById`

把 **穿透 / 击穿 / 雪崩** 三个经典问题完整实现在 80 行代码里：

### 防穿透：Guava BloomFilter
- 系统启动（`@PostConstruct`）把所有 productId 灌进 BloomFilter
- 查询前先问 BloomFilter："这个 ID 可能存在吗？" 不可能 → 直接返回 null
- BloomFilter 特性：**说"不在"一定不在，说"在"可能误判**，所以适合做"拦截不存在的查询"
- 内存估算：10000 个元素 1% 误判率 ≈ 9.6KB —— 几乎免费
- **生产改进点**（主动暴露给面试官）：单机 BloomFilter 不能在线增删，新加商品要重启。生产应用 Redis BitMap 或 Redisson 的 RBloomFilter 共享。

### 防击穿：Redisson 分布式锁 + Double Check
```java
boolean locked = lock.tryLock(3, 10, TimeUnit.SECONDS);
// ...
// 拿到锁后再次检查缓存（A 释放锁前已写回了缓存，B 不该再查 DB）
product = (Product) redisTemplate.opsForValue().get(cacheKey);
if (product != null) return product;
```
两个细节有讲究：
- `tryLock(3, 10, ...)`：**等锁最多 3s（避免无限等待）**，**持锁最多 10s（避免业务挂掉导致死锁）** —— Redisson 还自带 watchdog 续期，但显式指定上限更稳。
- **Double Check**：拿到锁后必须再读一遍缓存。否则当 A 释放锁后，B 拿到锁但缓存已经有了，B 还去查 DB 就白干一次。这是 DCL（Double-Checked Locking）思想在缓存里的应用。

### 防雪崩：随机 TTL
```java
long ttl = BASE_CACHE_TTL + RANDOM.nextLong(RANDOM_TTL_OFFSET);
// 30 分钟 + 0~5 分钟随机
```
看似一行代码，作用是 **把"集中过期"摊到一个时间窗口里**。如果所有商品 TTL 都是 30 分钟，第 30 分钟瞬间所有缓存失效，DB QPS 暴涨；加 0~5 分钟随机后，过期点被打散到 5 分钟区间，DB 压力曲线平缓。

---

## 六、亮点 5：基于 AOP 的限流 + 幂等公共能力

**位置**：`@AccessLimit` / `@Idempotent` + `AccessLimitAspect` / `IdempotentAspect`

### 为什么走 AOP？

业务侧只标注解，逻辑零侵入：
```java
@PostMapping("/do/{activityId}")
@AccessLimit(seconds = 5, maxCount = 3, msg = "操作过于频繁")
public Result<String> doSeckill(@PathVariable Long activityId) { ... }
```
横切关注点（限流/幂等/日志/审计）和业务代码解耦 —— 这是 AOP 在业务系统的标准用法。

### 限流的 key 设计有讲究
```java
if (accessLimit.needLogin() && userId != null) {
    return uri + ":" + userId;       // 登录用户：精准到人
}
return uri + ":" + getClientIp(request);  // 未登录：粗到 IP
```
- 已登录按用户限：防 **个人刷接口**
- 未登录按 IP 限：用户级别拿不到，退化用 IP
- IP 提取处理了 `X-Forwarded-For` 多级代理（取第一个），考虑了 Nginx 反向代理场景

### 限流算法选型
项目用的是 **固定窗口计数器**（`INCR` + `EXPIRE`），优点是简单、性能高；缺点是窗口边界突刺（最后 1 秒 + 下一窗口前 1 秒可能 2 倍速率）。
面试时可以延伸：
- **滑动窗口**：用 ZSet 存时间戳，精确但内存占用高
- **漏桶/令牌桶**：用 Redisson 的 `RRateLimiter`（基于令牌桶），平滑放行
- 项目当前实现对秒杀场景足够（不要求绝对平滑），后续如果有 SLA 要求再升级

### 幂等 Token 的"查到即删"
为什么要原子地"查询 + 删除"？设想：
```
T1: EXISTS token → true
T2: EXISTS token → true   ← 两个线程都看到存在
T1: DEL token            ← 删了
T2: 通过校验             ← 幂等失效！
```
解法：Lua 脚本一次性 `if EXISTS then DEL` —— 原子的"取走令牌"语义，跟"信号量减 1"一样。

---

## 七、亮点 6：订单状态机 + JWT 双 Token 无感续期

### 订单状态机（`OrderStatusEnum`）

```java
public boolean canTransitionTo(OrderStatusEnum target) {
    return switch (this) {
        case PENDING_PAY -> target == PAID || target == CANCELLED;
        case PAID        -> target == SHIPPED || target == CANCELLED;
        case SHIPPED     -> target == COMPLETED;
        default          -> false;
    };
}
```
所有状态变更前必须问一句 `canTransitionTo`，从代码上**杜绝非法跃迁**（已完成的订单被误改回待支付、已取消的订单被支付等）。状态机用枚举 + switch 表达，可读性远胜散落的 `if-else`。

### JWT 双 Token + 无感续期

- **accessToken（2 小时）**：每次请求带，存活短，泄露损失小
- **refreshToken（7 天）**：长期凭证，**存 Redis**，登出能立即吊销
- **无感续期**：accessToken 剩余 < 30 分钟时，`JwtAuthFilter` 在响应头里塞个新的 accessToken，前端拦截器替换本地存储 —— 用户全程无感知

```java
if (jwtUtil.isAboutToExpire(token)) {
    String newToken = jwtUtil.generateAccessToken(...);
    response.setHeader("Authorization", "Bearer " + newToken);
    response.setHeader("Access-Control-Expose-Headers", "Authorization");
}
```
`Access-Control-Expose-Headers` 是跨域必备：CORS 默认前端只能读到一小撮安全响应头，自定义/非标准头必须显式 expose，否则前端 JS 拿不到。

### ThreadLocal 上下文 + 清理

```java
finally {
    UserContext.clear();   // 关键！
}
```
Tomcat 线程池复用线程，**忘记 clear 会导致下一个请求读到上一个用户的数据**（极其难排查的越权 bug）。`OncePerRequestFilter` 的 `finally` 块是清理的唯一安全位置。

---

## 八、可以主动暴露的"不足"（面试加分）

> 面试官最怕的是你说项目完美无缺。主动讲局限性体现工程成熟度。

1. **库存预热依赖手动**：`@PostConstruct` 启动时一次性加载，运行期新建活动需要管理员手动调 `/admin/seckill/activity/{id}/start`。生产应该用配置中心 + 事件总线自动同步。
2. **BloomFilter 单机化**：上面已讲，生产应换 Redis BitMap 或 Redisson RBloomFilter。
3. **未做读写分离**：MySQL 单实例，秒杀活动展示页和订单查询都打主库。引入只读副本能进一步提性能。
4. **缺乏熔断 / 降级**：Redis 挂了整个秒杀就废了。生产应接入 Sentinel/Resilience4j，Redis 失败时短期降级为"系统繁忙稍后再试"而不是雪崩到 DB。
5. **没做接口签名**：限流只防普通用户连点，挡不住恶意脚本伪造请求。生产应加 HMAC 签名 + 时间戳防重放。
6. **没引入分布式追踪**：异步链路（Controller → Lua → MQ → Consumer → 死信）很难排查，应该用 OpenTelemetry/SkyWalking 串起来。
7. **死信处理仅记日志**：`commonDeadLetterQueue` 没接告警，只是被监听。生产应接入告警平台 + 一键补单后台。

---

## 九、技术选型综合表

| 维度 | 选型 | 为什么 |
| --- | --- | --- |
| 框架 | Spring Boot 3.3 + Java 17 | 最新 LTS，virtual thread 储备 |
| ORM | MyBatis-Plus 3.5 | 通用 CRUD 注解化，复杂 SQL 仍可手写 |
| 缓存/锁 | Redis 7 + Redisson 3.29 | Redisson 提供分布式锁、看门狗续期、限流器 |
| 消息 | RabbitMQ 3 | 死信队列原生支持，运维门槛比 Kafka 低 |
| 认证 | Spring Security + JJWT 0.12 | 无状态 + 方法级权限 |
| 工具 | Hutool (雪花 ID) + Guava (BloomFilter) | 减少自研 |
| 文档 | SpringDoc OpenAPI 2.6 | Swagger 3 替代 |
| 部署 | Docker Compose | 一键拉起 MySQL/Redis/RabbitMQ |

---

## 十、一句话总结（面试结尾用）

> "这个项目我从功能上把秒杀做完了，但更重要的是把 **超卖、幂等、削峰、缓存三大问题、消息可靠性** 这些电商高并发的核心问题都用最朴素、最可靠的方式回答了一遍 —— 不依赖花哨的组件，依靠 Redis 单线程、Lua 原子性、数据库唯一索引、RabbitMQ 死信这些底层基础设施的保证，组合出工程上够用的方案。剩下的不足，比如熔断降级、分布式追踪，是引入新组件就能解决的事，不是设计上的窟窿。"
