# FlashMall

高并发秒杀商城，Spring Boot 3.3 + Java 17 实现。聚焦秒杀场景下的**防超卖、防重复下单、削峰填谷、超时取消**四个核心问题。

> 想看面试角度的设计取舍，移步 [`INTERVIEW_HIGHLIGHTS.md`](./INTERVIEW_HIGHLIGHTS.md)。

## 技术栈

| 组件 | 版本 | 用途 |
|---|---|---|
| Spring Boot | 3.3.4 | Web / Security / AOP / AMQP |
| Java | 17 | 语言 |
| MySQL | 8.0 | 主存储（用户、商品、订单、活动） |
| Redis | 7 | 库存预扣、用户去重、JWT 黑名单、幂等 Token、限流计数 |
| RabbitMQ | 3 | 异步下单 + TTL 延迟队列做超时取消 |
| MyBatis-Plus | 3.5.7 | ORM |
| Redisson | 3.29 | 分布式锁（可选） |
| JJWT | 0.12 | JWT 签发/校验 |
| Thymeleaf | - | 内置最小可用前端页面 |

## 模块结构

```
src/main/java/com/flashmall/
├── FlashMallApplication.java       入口
├── common/                         公共能力
│   ├── annotation/                 @Idempotent / @AccessLimit
│   ├── aop/                        幂等切面 / 限流切面
│   ├── config/                     Spring Security / Redisson / MyBatis-Plus
│   ├── constant/                   Redis key / MQ 队列名
│   ├── enums/                      OrderStatusEnum (状态机) / ResultCode
│   ├── exception/                  全局异常处理 + BusinessException
│   ├── filter/                     JwtAuthFilter
│   ├── result/                     统一返回 Result<T>
│   └── util/                       JwtUtil / UserContext / RedisLuaScript
├── user/                           注册、登录、JWT、退出
├── product/                        商品 CRUD + 缓存
├── seckill/                        秒杀核心（活动、Lua 扣减、MQ 生产）
├── order/                          订单创建（MQ 消费）、支付、取消、超时
└── admin/                          后台管理接口（hasRole("ADMIN")）
```

## 核心设计

### 1. 三层防超卖

请求按 **延迟从低到高、过滤强度从粗到细** 依次过：

```
L1 本地 ConcurrentHashMap    售罄后单机直接拒，不打 Redis
L2 Redis + Lua               原子完成 "活动校验 + 用户去重 + 扣减库存"
L3 DB stock>0 兜底           最后一道，行级原子的"按值校验+扣减"
```

- L2 `RedisLuaScript.SECKILL_SCRIPT`：一个脚本里查活动元数据 / 校验时间 / 判 SECKILL_USERS / DECR 库存，整段在 Redis 单线程里原子执行。
- L3 SQL: `UPDATE t_seckill_activity SET stock=stock-1, version=version+1 WHERE id=? AND stock>0`。`stock>0` 这个 WHERE 本身就保证原子，无需重试。

### 2. 三重幂等

| 层级 | 实现 | 防的是 |
|---|---|---|
| 业务级"一人一单" | Lua 里 `SADD seckill:users:{aid}` | 用户重复点击 |
| 请求级幂等 | `@Idempotent` 注解 + `IdempotentAspect` + 一次性 Token | 网络重试导致的重复提交 |
| MQ 消费幂等 | `t_order.idempotent_key` UNIQUE 索引 | RabbitMQ 重投 |

**幂等 Token 流程**：前端先 `GET /api/order/token` 拿一次性 token → 下单时放 `Idempotent-Token` 请求头 → 切面通过 Lua 原子"校验+删除"，避免并发场景下两个请求都通过校验。

> **注意**：`idempotent_key` 由 `SeckillServiceImpl#doSeckill` 用 UUID 生成，**绝不能**复用 `userId_activityId`，否则订单取消后再次秒杀会与历史订单 idempotent_key 冲突，新订单永远写不进 DB。

### 3. 削峰填谷

秒杀请求只做"Redis Lua 扣减 + 发 MQ 消息"，立即返回幂等键。前端拿到键后**轮询** `GET /api/order/by-key/{key}` 查询订单是否落库。下单这个重活由 MQ 消费者异步做，DB 写入速率可控。

### 4. 超时取消（30 分钟）

订单创建后发一条延迟消息到 `ORDER_DELAY_QUEUE`，30 分钟未消费就变成死信进入死信队列，由 `OrderDelayConsumer` 消费触发 `timeoutCancelOrder`。

取消订单（手动或超时）会**同时**：
- 回滚 Redis 库存（让别人能继续抢）
- 从 `SECKILL_USERS` 集合移除该用户（让该用户能再次抢）

抽象在 `OrderServiceImpl#releaseSeckillSlot`，两条取消路径共用一份逻辑。

### 5. JWT 鉴权

- 登录返回 `accessToken` (短) + `refreshToken` (长)。
- `JwtAuthFilter` 拦截非白名单请求，解析 token 注入 `UserContext`。
- 退出登录把当前 token 加进 Redis 黑名单，TTL = token 剩余有效期。

## 快速开始

### 1. 起依赖

```bash
docker compose up -d        # 拉起 MySQL / Redis / RabbitMQ
```

首次启动 MySQL 会自动执行 `src/main/resources/db/init.sql`。

### 2. 跑应用

```bash
mvn spring-boot:run
# 或在 IDE 里跑 com.flashmall.FlashMallApplication
```

启动后访问：
- 首页：http://localhost:8080/
- Swagger：http://localhost:8080/swagger-ui.html
- RabbitMQ 管理台：http://localhost:15672 （guest/guest）

### 3. 注册账号

`init.sql` 不预置任何用户。**首次使用请先到 `/login` 页面切到「注册」 Tab 自助注册**（或直接调 `POST /api/user/register`）。

如需 ADMIN 权限访问 `/api/admin/**`，注册后到 DB 手动改一下：

```sql
UPDATE t_user SET role = 'ADMIN' WHERE username = '你的用户名';
```

### 4. 环境变量（可选）

`application.yml` 里所有连接配置都用 `${VAR:default}` 形式，本地开发直接用默认值即可。生产部署用环境变量覆盖：

```
MYSQL_HOST / MYSQL_PORT / MYSQL_DATABASE / MYSQL_USERNAME / MYSQL_PASSWORD
REDIS_HOST / REDIS_PORT / REDIS_PASSWORD / REDIS_DATABASE
RABBITMQ_HOST / RABBITMQ_PORT / RABBITMQ_USERNAME / RABBITMQ_PASSWORD / RABBITMQ_VHOST
JWT_SECRET / JWT_ACCESS_TTL / JWT_REFRESH_TTL
```

⚠️ `JWT_SECRET` 必须在生产环境覆盖，长度至少 32 字节。

### 5. 主要接口

| Method | 路径 | 说明 |
|---|---|---|
| POST | `/api/user/register` | 注册 |
| POST | `/api/user/login` | 登录，返回 accessToken |
| GET  | `/api/seckill/list` | 秒杀活动列表（公开） |
| GET  | `/api/order/token` | 拿一次性幂等 token（下单前调用） |
| POST | `/api/seckill/do/{activityId}` | 执行秒杀，需 `Idempotent-Token` 头 |
| GET  | `/api/order/by-key/{key}` | 用幂等键轮询订单创建结果 |
| GET  | `/api/order/list` | 我的订单 |
| POST | `/api/order/{orderId}/pay` | 模拟支付 |
| POST | `/api/order/{orderId}/cancel` | 取消订单 |

## 并发压测

`scripts/loadtest_seckill.py` —— 批量注册用户、缓存 token、并发打秒杀。

```bash
cd scripts
pip install requests
python3 loadtest_seckill.py              # 100 用户 × 活动 ID=1
python3 loadtest_seckill.py -n 500 -a 2 -w 100
```

参数：
- `-n` 总用户数 / 总请求数（每个用户一次）
- `-w` 线程池大小（瞬时并发量，建议 ≥ 库存数才能制造真实争抢）
- `-a` 秒杀活动 ID

Token 缓存在 `scripts/.tokens.json`，过期删掉重跑即可。

**预期**：库存 10 跑 `-n 200 -w 100`，结果应该是约 10 个秒杀成功，其余被 Redis Lua 挡在"库存不足/重复购买"。DB 订单数 = 10，最终 `t_seckill_activity.stock = 0`，Redis `seckill:stock:{id} = 0`。

## Redis Key 约定

```
seckill:activity:{aid}     Hash   活动元数据 (startTime/endTime/status/productId)
seckill:stock:{aid}        String 库存（支持原子 DECR）
seckill:users:{aid}        Set    已购用户 ID 集合（业务级一人一单）
idempotent:token:{token}   String 一次性幂等 token，TTL=5min
jwt:blacklist:{jti}        String JWT 黑名单（退出登录用）
access_limit:{path}:{uid}  String 滑动计数器（@AccessLimit 限流）
```

## 配置文件

`src/main/resources/application.yml`：默认连本机 docker-compose 起的中间件。生产环境改成对应地址即可。
