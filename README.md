# 闪购营销中心 · Marketing Center

为**简选商城**提供秒杀能力的高并发服务：秒杀请求在网关被限流，库存走 Redis 原子扣减，
下单动作通过 RocketMQ 异步落库，业务数据与商城共用 `jianxuan_mall` 库。

定位上它是商城的**服务提供方**，不是第二个商城——商城承担日常交易，营销中心承担瞬时高并发。

## 服务与端口

| 模块 | 端口 | 职责 |
|---|---|---|
| `mc-gateway` | 8081 | Spring Cloud Gateway，统一入口、JWT 鉴权、路由、令牌桶限流 |
| `mc-seckill` | 8082 | 活动管理、库存预热、Lua 原子扣减、投递 MQ、本地消息表补偿 |
| `mc-order` | 8083 | 消费 MQ、订单幂等落库、结果查询 |
| `mc-common` | — | 统一返回、全局异常、JWT/签名工具、Redis key 与 Topic 常量 |

中间件：Nacos 8848（注册中心）、RocketMQ 9876 / 10911、Redis 6379、MySQL 3306。

**身份链路**：客户端带 `Authorization: Bearer <jwt>` → 网关验签得到 userId 与 role →
网关删除客户端自带的 `X-User-Id` / `X-User-Role` 并写入可信值 + HMAC 签名（**签名覆盖角色**）→
下游服务验签后才认这个身份。直连 8082/8083 自行伪造 `X-User-Id` 或 `X-User-Role: ADMIN` 都会被拒 401。

**授权链路**：普通用户的 token 只能抢购与查询；创建 / 发布 / 预热活动、库存对账视图、
待复核列表都属于管理操作，由 `@RequireAdmin` 标注、`AdminInterceptor` 在业务校验之前拦截，
不满足直接 403。**权限判断先于业务校验**——普通用户请求一个不存在的活动 id 得到的是 403 而不是 404，
不泄露资源是否存在。

## 版本组合（不要随意升级）

```
Spring Boot 3.2.12  ↔  Spring Cloud 2023.0.3  ↔  Spring Cloud Alibaba 2023.0.3.2
Nacos 2.5.1（服务端）  /  RocketMQ 5.3.4（服务端） + rocketmq-client 5.3.1
```

这套组合是官方兼容性验证过的。**踩过的坑**：

- `D:\vj-demo\SpringCloudStudy\nacos` 是 **Nacos 3.2.4**，它移除了 v1 REST API（实测返回 501），
  而 SCA 2023.0.x 的客户端依赖这套协议，注册会失败。且 3.x 的 Console 单独占 8080，
  会和商城后端冲突。→ 改用 Nacos 2.5.1。
- 学习项目 `SpringCloudStudy` 用的是 **Spring Boot 4.1.1**，SCA 对该版本支持不完整，
  不要拿它当模板。

## 环境启动顺序

MySQL 和 Redis 已注册为 Windows 服务，开机自启，无需手动启动。

**1. RocketMQ NameServer**（`D:\vj-demo\mq\rocketmq-all-5.3.4-bin-release`）

```cmd
set ROCKETMQ_HOME=D:\vj-demo\mq\rocketmq-all-5.3.4-bin-release
cd /d %ROCKETMQ_HOME%\bin
mqnamesrv.cmd
```

**2. RocketMQ Broker**（另开一个窗口）

```cmd
set ROCKETMQ_HOME=D:\vj-demo\mq\rocketmq-all-5.3.4-bin-release
cd /d %ROCKETMQ_HOME%\bin
mqbroker.cmd -n 127.0.0.1:9876 -c %ROCKETMQ_HOME%\conf\broker.conf
```

`broker.conf` 里已补上三项，缺一项就会出问题：

| 配置 | 为什么必须写 |
|---|---|
| `brokerIP1 = 127.0.0.1` | 本机有 WSL/Hyper-V 虚拟网卡（172.30.144.1），不指定会注册成虚拟网卡 IP，客户端连不上 |
| `storePathRootDir = D:/vj-demo/mq/store` | 默认写到用户目录，清理时容易漏 |
| `autoCreateTopicEnable = true` | 开发期免手动建 Topic |

**3. Nacos**

```cmd
set JAVA_HOME=D:\vj-demo\jdk-17
cd /d D:\vj-demo\nacos-2.5.1\nacos\bin
startup.cmd -m standalone
```

**4. 业务服务**（三个窗口，或依次后台启动）

```cmd
set DB_PASSWORD=<见本地配置>
set DB_USERNAME=root
set JWT_SECRET=<32 字节以上的随机串，三个服务必须一致>
:: sql\05_reservation_id.sql 只能执行一次；当前数据库已完成，不要重复执行
:: 新库还需执行 sql\06、sql\07、sql\08；当前数据库均已完成，不要重复执行 08
:: 模拟登录只在 dev profile 下开启；不写这一行，/api/auth/token 就是 404
java -jar mc-seckill\target\mc-seckill-1.0.0.jar --spring.profiles.active=dev
java -jar mc-order\target\mc-order-1.0.0.jar
java -jar mc-gateway\target\mc-gateway-1.0.0.jar
```

**模拟登录的开关放在 `application-dev.yml` 里，基础配置恒为 `false`。**
上一版把 `mock-login-enabled: true` 直接写在基础配置里（第三轮评审 P0）——
那等于**所有环境默认全开**：任何人 POST 一个 `{"userId":1,"role":"ADMIN"}` 就能拿到管理员 token，
角色权限体系彻底失效。「默认不配置即关闭」这个说法在配置文件里明晃晃写着 `true` 时是不成立的，
评审看的是那行字，不是设计意图。现在把危险开关与基础配置物理隔开，评审只需看一个文件。

**`JWT_SECRET` 没有默认值，缺了直接启动失败**（`Could not resolve placeholder`）。
第一版写的是 `${JWT_SECRET:mc-dev-only-...}`，看起来开箱即用，实际上是给生产留了一把
人人可见的钥匙：忘了设环境变量服务照样起，攻击者拿这个公开默认值就能签出任意用户的 token。
现在把「配置事故」改成「部署时立刻发现」。本地可以这样生成一个：

```cmd
python -c "import secrets,base64;print(base64.b64encode(secrets.token_bytes(32)).decode())"
```

## 自检

```bash
# 健康检查（不需要 token）
curl http://127.0.0.1:8082/api/seckill/ping
curl http://127.0.0.1:8083/api/order/ping
curl "http://127.0.0.1:8848/nacos/v1/ns/service/list?pageNo=1&pageSize=10"

# 业务接口必须走网关 + token，直连 8082/8083 会返回 401
TOKEN=$(curl -s -X POST http://127.0.0.1:8081/api/auth/token \
  -H "Content-Type: application/json" -d '{"userId":1001}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])")
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8081/api/seckill/activity/list
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8081/api/auth/me
```

## 接口一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/token` | 模拟登录换 JWT（**仅联调，上线前删除**） |
| GET | `/api/auth/me` | 回显网关注入的用户身份 |
| POST | `/api/seckill/activity` | 创建活动（秒杀价 < 原价、库存 ≤ 商品库存、时间窗合法） |
| POST | `/api/seckill/activity/{id}/publish` | 发布（商品行锁串行化 + 时间重叠校验 + 乐观锁） |
| POST | `/api/seckill/activity/{id}/warmup` | 预热库存（仅未开始的活动，SETNX 幂等） |
| GET | `/api/seckill/activity/list` | 活动分页 |
| GET | `/api/seckill/activity/{id}` | 活动详情（带商品名与折扣） |
| GET | `/api/seckill/activity/{id}/stock` | 对账视图：总库存 / DB 剩余 / Redis 剩余 / 已抢人数 |
| POST | `/api/seckill/order/{activityId}` | 抢购，返回 QUEUED / SOLD_OUT / REPEATED |
| GET | `/api/order/result?activityId=` | 查结果：SUCCESS（带订单号）/ QUEUING / NOT_RESERVED |
| GET | `/api/seckill/admin/outbox/review` | **管理员**：待人工处理的本地消息（待复核 4 + 卡住的回滚执行中 5） |
| POST | `/api/seckill/admin/outbox/{id}/rollback` | **管理员**：人工确认后回滚库存（执行前再查一次订单） |
| POST | `/api/seckill/admin/outbox/{id}/recover` | **管理员**：把卡在「回滚执行中(5)」的记录放回待复核（确认原请求已死之后才调） |

## 进度

- [x] Day 1 环境：RocketMQ 单机跑通、Nacos 2.5.1 启动、模块骨架编译通过、注册成功
- [x] Day 2 网关路由 + 活动管理
- [x] Day 3 库存预热 + Lua 原子扣减
- [x] Day 4 MQ 消费落库 + 结果轮询（含本地消息表、补偿重投、对账）
- [x] Day 5 网关 JWT 鉴权 + 下游签名校验 + 按用户令牌桶限流 + 异常映射
- [x] Day 6 经网关的并发压测（200 并发抢 50 库存，绕过网关直连已被禁）
- [x] Day 6.5 第二轮代码评审修复（P0 崩溃窗口 + 5 条 P1，含授权体系与孤儿对账）
- [x] Day 6.6 第三轮代码评审修复（2×P0 + 4×P1/P2，含原子认领、回滚三态、行锁互斥）
- [x] Day 6.7 第四轮修复：每次扣减唯一预订号、消费中状态、卡死状态恢复、自动化测试
- [x] Day 6.8 第五轮修复：累计库存校验、请求 ID 限长、孤儿按状态恢复、对账上限、SQL 幂等
- [x] Day 6.9 第六轮完善：商品库存真实预留、活动结束后安全释放、outbox 复用计数重置
- [x] Day 6.10 第七轮修复：取消状态 5 按时间自动回收、补活动维度索引、投递标记带预订号、生产者自愈
- [ ] Day 7 面试问答整理

## 代码评审修复 · 第一轮（P0/P1/P2）

第一轮外部评审提出的问题与修复方式：

| 级别 | 问题 | 修复 |
|---|---|---|
| P0 | 订单服务是空骨架，抢购只返回 QUEUED 查不到订单 | 补齐 `OrderConsumer`（RocketMQ 并发消费）、`OrderPersistService`（单事务完成插单+扣商品库存+扣活动账本）、`/api/order/result` 轮询接口 |
| P1 | 秒杀接口直接信任 `X-User-Id`，换 ID 可绕过一人一单 | 网关 `AuthGlobalFilter` 校验 JWT 后**先删客户端自带的身份头再写自己的**，并附 HMAC 签名；下游 `GatewayAuthFilter` 用同一密钥验签 + 时间窗防重放 |
| P1 | 网关无限流，与文档描述不符 | `RequestRateLimiter` + 按 userId 的 `KeyResolver`（20/s，burst 20） |
| P1 | MQ 发送失败立即回滚，但 broker 可能已收到 → 超卖 | 改为**本地消息表**：先落 `seckill_outbox` 再投递；发送失败不回滚，由补偿任务重投；重试耗尽先查订单表再决定是否回滚 |
| P1 | `SendResult` 未校验状态 | 显式判断 `SendStatus.SEND_OK`，`FLUSH_DISK_TIMEOUT` 等一律视为未确认 |
| P1 | 预热把「开始后 1 分钟」也算未开始，可能用旧库存回灌 | 严格限定 `start_time > now`；新增 `warmup_at` 区分「从未预热」与「预热后丢失」 |
| P1 | 并发发布重叠活动存在竞态 | 发布时先 `SELECT ... FOR UPDATE` 锁商品行，事务隔离级别显式设 `READ_COMMITTED`（**RR 下普通 SELECT 走旧快照，加锁等于白加**） |
| P2 | JSON 错误 / 路径参数类型错被兜底成 500 | 补齐 `HttpMessageNotReadable` / `MethodArgumentTypeMismatch` / `MissingRequestHeader` / `NoResourceFound` / `HttpRequestMethodNotSupported` 等分支 |
| P2 | MQ 超时 3s×3 次 > 网关 5s 响应超时 | 发送超时改 1s、重试 1 次，最坏 2s，留足余量 |
| P2 | Lua 跨两个 key，Redis Cluster 会 CROSSSLOT | key 改为 `seckill:{activityId}:stock` / `seckill:{activityId}:bought`，同活动强制同槽 |

## 代码评审修复 · 第二轮（P0 + 5×P1）

第二轮评审提了 6 条，全部修复并逐条实测。这一轮的重点是「故障窗口」与「授权」——
上一轮把大部分正确性补上了，剩下的是「进程崩溃」和「权限边界」这两类平时测不出来的问题。

| 级别 | 问题 | 修复 |
|---|---|---|
| P0 | **扣 Redis 与写 outbox 之间进程退出**，产生「库存已扣、用户在买家集合、无 outbox 无订单」的永久悬挂记录，且没有任何对账任务能发现它 | 扣减的 Lua 里同时 `ZADD seckill:{id}:pending`（score=毫秒时间戳），落库成功后 `ZREM`。新增 `OrphanDeductionTask` 每 30 秒 SCAN 一次，把「留在集合里超过 60 秒」的记录查一遍 outbox：**有记录只清标记，没记录才幂等回滚** |
| P1 | 重试耗尽后按「此刻查不到订单」自动回滚，若消息早已投递成功、消费者只是迟到，会形成**库存已还 + 订单照样落库 = 超卖** | 新增 `OutboxStatus.NEEDS_REVIEW(4)`：耗尽后**保留库存、不自动回滚**，打 error 等人工。`reviewPending` 每轮复查，迟到落库的自动转 `CONSUMED`；真无订单的走管理接口人工回滚 |
| P1 | 回滚脚本无条件 `INCR`，同一用户被重复回滚时库存会**增加多次**；`rollback` 吞掉异常，调度任务却照样标记已回滚 | `rollback.lua` 改为**只有 `SREM` 返回 1 才 `INCR`**，并检查 stock key 是否存在（活动结束后 key 已过期，无条件 INCR 会造出没有 TTL 的脏 key）。`StockService.rollback` 返回 boolean，调用方能知道到底改没改 |
| P1 | 补偿锁租期 20 秒，而单批 100 条 × 最坏 2 秒 = 200 秒，broker 故障时锁会提前过期，多实例同时跑会重复投递与重复回滚 | 批次 100 → 20，并**处理每条前用 Lua 续租**（先比对 value 再 `PEXPIRE`，续租失败立即中止本轮）。缩小批次只是降低概率，续租才是根治 |
| P1 | `DuplicateKeyException` 一律当重复消费确认，若 Redis 发号器丢过、序列重置撞上 `uk_order_no`，**订单会被静默丢弃** | 捕获后必须查 `seckill_order`：确实存在该用户该活动的订单才确认，否则视为撞号，返回 `RECONSUME_LATER` 让它重新取号。**「查库确认」代替「凭异常类型猜语义」** |
| P1 | 只有认证没有授权：普通 token 能建活动/发布/预热；`/api/auth/token` 可任意签发；`JWT_SECRET` 有公开默认值 | JWT 增加 `role` claim，网关透传 `X-User-Role` 且**纳入 HMAC 签名**（只签身份不签权限，直连塞 ADMIN 就能提权）；`@RequireAdmin` + `AdminInterceptor` 在业务校验前拦截；模拟登录加 `mock-login-enabled` 开关默认关闭；三个服务的 `JWT_SECRET` 去掉默认值 |

**几条值得单独说的**：

- **孤儿扣减为什么不用分布式事务**：跨 Redis 与 MySQL 的 2PC 成本极高，而且 Redis 侧的回滚本身也不保证。
  更实用的是让状态可被反向识别：扣减时留痕、落库后擦除，剩下的就是嫌疑记录。
  这和 MQ 的 outbox 是同一个思路——**用「可对账的中间态」代替「不可能失败的原子操作」**。
- **为什么只扫 pending ZSet 而不比对全部买家**：正常路径 ZADD/ZREM 成对，ZSet 通常是空的，
  成本只和悬挂数相关；若拿 bought 集合与数据库做全量差集，十万人的活动就是十万次查询。
- **孤儿任务为什么不加锁**：回滚本身幂等（`SREM` 的返回值就是凭据），多实例同时扫到也只有一个能把库存加回去。
  操作幂等之后，并发就不再是正确性问题，最多多打几行日志。
- **NEEDS_REVIEW 不是死路**：复查任务会自动收敛迟到落库的；真需要人的只有「确实没落库」那几条，
  而且管理接口在回滚前**强制再查一次订单**，把「人工误操作」这个最危险的口子堵上。

## 代码评审修复 · 第三轮（2×P0 + 4×P1/P2）

这一轮评审最狠的两条都指向同一个主题：**「我检查过了」和「我动手时它还是那样」之间的时间差**。
分布式系统里所有「先查后改」的写法都有这个洞，区别只在于窗口有几毫秒。

| 级别 | 问题 | 修复 |
|---|---|---|
| P0 | 模拟登录开关写在基础配置里（`mock-login-enabled: true`），所有环境默认全开，任何人都能签 ADMIN token | 基础配置恒为 `false`，`true` 挪进 `application-dev.yml`。**实测**：不带 profile 启动，`/api/auth/token` 返回 404；带 `--spring.profiles.active=dev` 才 200 |
| P0 | 孤儿兜底任务「查 outbox → 回滚」两步不原子。一个只是**慢**的请求（GC 停顿 / 连接池排队超过 60 秒宽限期）会被判成孤儿：任务归还库存，原请求随后恢复、写 outbox、发 MQ，订单照样落库 → **超卖** | 引入**原子认领 + 租约**：兜底任务必须先 `SET NX` 拿到 `orphan-claim:{userId}`（带 30 秒 TTL）才能动手，认领动作与「是否超期」判断在同一个 Lua 里；原请求在发 MQ 前调用 `confirmDeduct`，它会检查 claim —— 在，说明已被接管，立刻把 outbox 行标记为 `ABORTED(6)` 墓碑 + 回滚库存 + **不再投递**（用墓碑不用删除：删掉后消费者查不到记录会照常建单，而库存已还） |
| P1 | `rollback` 返回 boolean，把「用户不在买家集合」和「Redis 故障」混成一个 false；管理接口据此照样标成「已回滚」，于是出现「状态说已还、库存一件没还」的脏记录 | 改成三态 `RESTORED / NOTHING_TO_DO / FAILED`：Lua 用 `SREM` 返回值区分 0（重复回滚）与 -1（库存 key 已过期），异常才是 FAILED。管理接口只在非 FAILED 时写 ROLLED_BACK，FAILED 退回 NEEDS_REVIEW 并返回 503 |
| P1 | 人工回滚「查订单 → 回滚 Redis → 改状态」三步各有窗口；消费者可能在中途落库，复查任务也可能同时改状态 | 引入过渡态 `ROLLING_BACK(5)`：管理接口先 `UPDATE ... SET status=5 WHERE id=? AND status=4` 抢独占；订单落库事务里 `SELECT ... FROM seckill_outbox WHERE activity_id=? AND user_id=? FOR UPDATE` 锁同一行，读到 3/5 直接拒绝落库（抛 `OrderRejectedException`，消费者确认消费而不是空转重试） |
| P2 | `reviewPending()` 没续租；`relayPending()` 丢锁后只退自己，后续对账/复查照跑；状态更新无条件，会跨状态覆盖 | 三个子任务返回 boolean，丢锁即中止后续；复查循环每条前续租；所有状态流转改 `casStatus(id, from, to)` |
| P2 | 被人工回滚过的用户再抢购会撞 `uk_user_activity`，被当成「重复购买」拒绝，可他其实什么都没买到 | 撞唯一键时先查那一行的状态：`ROLLED_BACK` 就复用该行（CAS 3→0、刷新 payload 与重试计数）继续下单，其余状态才返回 REPEATED |

**这一轮最值得讲的三件事**：

- **「先查后改」必须有一个数据库内的互斥点。** 孤儿任务和慢请求之间靠 Redis 的 claim key 互斥；
  人工回滚和迟到订单之间靠 MySQL 的行锁互斥。两者都是「把一个判断变成一次原子状态转移」，
  而不是「把检查写得更仔细一点」——检查写得再仔细，中间那几毫秒也还在。
- **认领为什么用独立的 key 而不是复用 pending 的 score**：score 只能存数字，表达不了「被谁认领」。
  释放时要判断「这个 claim 是不是我加的」，否则任务超时后会把别的实例的 claim 删掉，
  互斥就退化成「谁都能删谁的锁」。这和分布式锁的 unlock 必须比对 value 是同一个道理。
- **回滚失败为什么必须保留待复核**：`FAILED` 意味着「库存状态未知」，既不能当成成功，
  也不能当成无需操作。这类记录唯一的正确处理是留在队列里等人重试——
  自动给它一个结论，等于把一次可恢复的抖动变成一条永远查不出来的坏账。

## 实测数据（全部经 8081 网关）

```
鉴权：无 token → 401；直连 8082/8083 伪造 X-User-Id → 401
授权：普通用户建活动/发布/查运维列表 → 403；管理员过授权层后 → 404（先鉴权后校验）
      直连伪造 X-User-Role: ADMIN → 401（角色已纳入签名）；role=ROOT → 400（白名单校验）
限流：单用户突发 60 次 → 通过 20、429 共 40（replenishRate=20/s, burst=20）
客户端错误：非法 JSON/类型错/缺参数 → 400；不存在路径 → 404；方法不支持 → 405

并发：200 人抢 50 件，耗时 0.75s，QPS≈268
结果：{"QUEUED": 50, "SOLD_OUT": 150}
延迟：P50=496ms P99=692ms max=700ms
对账：totalStock=50 / dbAvailableStock=0 / redisStock=0 / boughtCount=50 / 订单 50 笔
      outbox 50 条全部 status=1；pending 集合残留 0；orphan-claim 残留 0
```

> 第三轮把 P50 从 302ms 抬到 496ms，代价是真实的：订单落库事务里多了一次
> `SELECT ... FOR UPDATE`（为了和人工回滚互斥），热路径多了一次认领检查。
> 这个延迟换的是「不会超卖」，不亏——秒杀系统里 300ms 和 600ms 都是「点完等一下」，
> 而超卖要赔钱。真要压回去，正确的做法是让消费端批量化处理，而不是去掉那把锁。

**MQ 故障演练**（停掉 broker 后抢购，再拉起 broker）：

```
broker 停机时抢购 → HTTP 200 QUEUED（18ms），Redis 库存 5→4（未回滚）
                  → outbox 记录 status=0，订单数 0
broker 恢复后     → 补偿任务重投成功，retry_count=1，status=1
                  → 订单落库 SK20261124000101，结果接口返回 SUCCESS
```

这一步证明了修复的核心：旧逻辑在「发送失败」时立刻回滚库存，如果 broker 其实收到了消息，
就会出现「库存还给用户 + 订单照样创建」的超卖。现在发送结果未知时不回滚，
凭本地消息表 + 消费端唯一索引保证**最终恰好成交**。

## 故障演练 · 第二轮（每一条都对应一个修复）

**1. 孤儿扣减（P0）** —— 直接构造崩溃窗口的现场，不依赖真的杀进程：

```
构造前           stock=20  bought=0   pending=0
手动造悬挂       DECR stock → 19
                SADD bought 99999         （模拟「扣减成功」）
                ZADD pending <2分钟前> 99999 （模拟「扣完就崩，没落库」）
等 30 秒         OrphanDeductionTask 扫到 score 超过 60 秒宽限
结果             stock=20  bought=0   pending=0
```

库存、买家标记、待确认标记三样全部还原。这条记录如果没人兜底，
会永久占着一件库存，该用户也永远抢不到第二件——**且任何基于数据库的对账都发现不了**。

**2. 回滚幂等（P1）** —— 同一个用户连续回滚两次：

```
第 1 次  EVAL rollback.lua → 返回 1，stock 20 → 21
第 2 次  EVAL rollback.lua → 返回 0，stock 仍 21
```

关键在 `SREM` 的返回值：它天然就是「我到底改没改状态」的凭据。
旧脚本无条件 `INCR`，重复回滚会让库存虚增——**库存多了就是超卖，比少卖严重得多**。

**3. 重试耗尽不回滚（P1）** —— 构造一条 `retry_count=8` 的本地消息：

```
构造      扣减 1 件（stock 20→19），插入 outbox retry_count=8 status=0
等 5 秒   补偿任务判定耗尽 → 查订单（无）→ 标记 NEEDS_REVIEW
结果      status=4，Redis stock 仍 19  ← 没有自动回滚
人工复核  POST /admin/outbox/152/rollback → stockRestored=true，stock 19→20，status=3
重复点击  再次调用 → 409「当前状态=3」，stock 仍 20
有订单时  对已成交的记录调用 → 409「该用户已有订单，已转为已消费」，库存不动
```

**4. 订单号撞号（P1）** —— 预置一条占住下一个流水号的订单，再让用户抢购：

```
预置      seq=151，插入 order_no=SK20261124000152（activity_id=99，占号不占用户）
抢购      用户 88888 抢活动 8 → 取号 152 → 撞 uk_order_no
旧逻辑    捕获 DuplicateKeyException → 当重复消费确认 → 订单永久丢失，日志只有一行「重复消息」
实际结果  查订单表发现 88888 没有该活动订单 → 返回 RECONSUME_LATER
重试      重新取号 153 → 落库成功 SK20261124000153
```

验证点不是「最终有一单」（旧逻辑下也可能靠别的路补上），而是
**「冲突时先查库确认，而不是凭异常类型猜语义」**。

## 故障演练 · 第三轮（每条都对应一个「先查后改」的窗口）

**1. 孤儿认领的互斥（P0）** —— 直接对 Lua 打四组边界：

```
A 刚扣减（score=now），cutoff=now-1s          → claim=0   还没到宽限期，不许动
B 记录已 2 分钟，cutoff=now-60s               → claim=1   认领成功
  B 的第二个实例再认领                        → claim=0   SET NX 挡住
C 已被认领时，原请求 confirm                  → 0         必须中止投递
  C 之后 pending 仍在                        → 1 条      认领方还没下结论，不能删
D 兜底回滚完成（pending 已 ZREM、claim 已释放）→ confirm=0 慢请求恢复也只能中止
E 正常路径（pending 在、无认领）              → confirm=1 且 pending 被清掉
```

关键在 D：**「记录不在了」和「记录还是我的」必须能被原请求区分开**。
旧版只有一个 ZREM，返回 0 时无法判断是「重复请求确认过」还是「兜底已经回滚了」，
于是慢请求会继续发 MQ —— 库存已还、订单照建。

被判定中止的 outbox 行不会被删掉，而是留一条 `status=6` 的墓碑：

```
outbox status=6 + 直接投一条合法消息 → 消费者读到 6 → 拒绝落库（日志 outboxStatus=6），订单 0 笔
同一用户再抢购                      → HTTP 200 QUEUED，复用该行（6→0→1），订单落库
```

墓碑不是洁癖：撤销与投递之间还有一个删不掉的窗口——补偿任务可能刚好读到 status=0 并发了出去。
行被删掉之后消费者查不到任何记录，就会照常建单，而库存已经还了。留一条 6，这个窗口才真正闭合。

**2. 回滚三态（P1）** —— 两次真实故障：

```
用户不在买家集合         → outcome=NOTHING_TO_DO，status 4→3（可以收尾）
停掉 Redis 再点回滚      → HTTP 503，status 仍是 4（没有标成已回滚）
Redis 恢复后再点        → outcome=RESTORED，stock 19→20，status 4→3
再点一次                → 409「当前状态=3」
```

中间那一步是这轮的重点：旧版这里会返回 200 并把状态写成 3，
界面上看起来「已回滚」，实际库存一件没还，而且要等到月末对账才会发现。

**3. 人工回滚与迟到订单（P1）** —— 用 `mqadmin` 直接投一条合法消息，制造「消息晚到」：

```
outbox status=5（回滚执行中）→ 消费者 FOR UPDATE 读到 5 → 拒绝落库，确认消费，订单 0 笔
outbox status=4（仅待复核）  → 同一份消息再投一次      → 订单落库 SK20261124000154
```

同一份消息、同一个用户，只有 outbox 状态不同，结果就不同——
这就是「落库事务里检查预留状态」的直接证据。旧版消费者不看这张表，
两条消息都会建单，而第一条的库存已经被人工还回去了。

**4. 并发点两次回滚（P1）**：

```
两个请求同时 POST /admin/outbox/159/rollback
→ 一个 200 RESTORED，一个 409「只有待人工复核的记录才能执行回滚，当前状态=3」
→ 库存只加回一件（stock 17→18，不是 19）
```

**5. 回滚后可重新参与（P2）**：

```
用户 75001 的 outbox 记录 status=3（被人工回滚过），不在买家集合
→ 再次抢购：HTTP 200 QUEUED（旧版这里返回 REPEATED）
→ 复用同一行 outbox（id 157），状态 3→0→1，订单 SK20261124000155 落库
```

对照：用户 74001 已有订单、outbox 未回滚 → 再次抢购仍返回 REPEATED。
**判据是那一行的状态，不是「撞了唯一键」这个事实。**

## 代码评审修复 · 第四轮

- 每次抢购生成 `reservationId`，同时写入 Redis pending、outbox 和 MQ，孤儿恢复不再按旧的
  `userId + activityId` 做模糊判断。
- 订单消费者事务内先把 outbox 从 `PENDING/SENT/NEEDS_REVIEW` 认领为 `CONSUMING(7)`，
  订单、商品库存和活动库存提交后再改为 `CONSUMED(2)`；入口墓碑因此不会覆盖已进入消费的消息。
- 状态 `ROLLING_BACK(5)` 超过 120 秒未收敛时自动退回待复核，解决进程崩溃后永久卡死。
- 发布活动时重新校验商品当前价格和库存；到期活动自动转为 `FINISHED`。
- 新增 JWT/签名/预订号/订单落库状态机测试；`concurrent_seckill.py` 改为经网关鉴权，
  `e2e_check.py` 改为有截止时间的轮询。

## 代码评审修复 · 第五轮

这一轮的三条 P1 有个共同点：**都不报错，只是结果不对**——校验漏了不抛异常，
状态判错了不抛异常，字段超长了要等到写库才炸。所以修完必须配测试钉住。

| 问题 | 修法 | 为什么这样修 |
|---|---|---|
| 同一商品多场未来活动的预分配库存可以累加超额 | 发布时按商品汇总 `SUM(available_stock)`（`sumAllocatedStock`），要求「已占用 + 本场 ≤ 商品库存」 | 商品库存只有一份，Redis 闸门却是每场一个。汇总 `available_stock` 而不是 `total_stock`，因为已售部分两边同步减少，用 `total_stock` 会变成「卖得越多越发布不了」 |
| `X-Request-Id` 无长度限制 | 入口拒绝 > 64 字符；`payload` 落库前再做一次容量保护；消费者侧对 `request_id` 兜底截断 | 拒绝而不是截断：截断会把两个不同请求 ID 变同一个，幂等键直接失效。落库侧可以截断，因为那里的幂等靠 `uk_user_activity`，不靠这个字段 |
| 孤儿恢复只看「outbox 有没有记录」，把 3/6 当有效预留 | 改读状态：只有 `null` 或 3/6 才回滚，其余只清标记 | 「写 outbox → 改 6 → 回滚前崩溃」会留下一条存在的墓碑记录。只清标记的话库存永久少一件、用户还被判「已抢过」 |
| 已投递但消费端永久故障时，对账会 `SENT ⇄ PENDING` 无限循环 | 新增 `reconcile_count`，打回 3 次后 CAS 到 `NEEDS_REVIEW` | 和「重投耗尽转待复核」同一个思路：自动流程必须有终点，否则问题永远等不到人来看 |
| `03` 脚本声称可重复执行，但 `ADD KEY` 第二次必失败 | 用 `information_schema` 判断后拼动态 SQL；`06` 同样写法 | 迁移脚本的注释就是运维的操作依据，写错了比不写更糟 |
| 活动 7 账本显示卖出 1 件，但无订单无 outbox | `sql/07_reconcile_activity7.sql` 把 `available_stock` 还原为 `total_stock` | 订单事务的顺序是「插单 → 扣商品库存」，没有订单行 ⇒ 商品库存没被扣过，所以只修活动账本，不动 `product.stock` |

**顺带收掉的两个口子**：`/api/seckill/ping` 与 `/api/order/ping` 加入网关白名单（此前经网关探活必 401，
监控只能绕过网关直连）；`/api/seckill/mq-test` 加 `@RequireAdmin`（此前任何登录用户都能往 topic 灌消息）。

**测试从 11 个加到 36 个**，新增用例覆盖库存预留、释放和 outbox 复用边界：

| 测试类 | 覆盖 |
|---|---|
| `ActivityServiceImplTest`（7） | 过去开始时间被拒、单场超库存被拒、累计超额发布被拒、库存预留失败被拒、刚好排得下时放行 |
| `InventoryReservationReleaseServiceImplTest`（4） | 未售库存只释放一次、重复释放被拒、活动态 outbox 未结束时等待、历史活动不释放 |
| `OrphanDeductionTaskTest`（8） | 状态 6/3 走回滚、状态 1/7 只清标记、无记录走回滚、认领失败零动作、回滚失败与异常都释放认领 |
| `SeckillControllerTest`（5） | 65 字符拒绝、64 字符放行、空串按未传、前后空白裁剪、缺用户标识拒绝 |

### 实测（2026-11-24 01:30–01:50，经 8081 网关）

```
# 1) 超长请求 ID
X-Request-Id 200 字符  → 400 {"code":400,"message":"X-Request-Id 长度不能超过 64 个字符"}
X-Request-Id 64 字符   → 200（放行，业务结果照常）
不带 X-Request-Id      → 200

# 2) 开始时间在过去
创建  → 400 "开始时间必须晚于当前时间"

# 3) 累计超额（商品 3 库存 87）
活动 A 预分配 60 → 发布 200
活动 B 预分配 40 → 发布 409 "当前库存 87 件，未来已发布活动已占用 60 件，本场最多可分配 27 件"

# 4) 孤儿恢复按状态分流（活动 15，库存 60，两条超时的 pending）
构造 user 9201 status=1、user 9202 status=6，各扣 1 件 → stock=58、pending=2
一个兜底周期后 → stock=59（只还了 status=6 那件）、bought={9201}、pending=0、无 claim 残留

# 5) 正常链路回归
活动 17（90 秒后开始）→ QUEUED → 订单 SK20261124000259，request_id=round5-regression-0001

# 6) 探活与权限
经网关 /api/seckill/ping、/api/order/ping → 200
普通用户 /api/seckill/mq-test → 403；管理员 → 200
Nacos：count=3（mc-gateway / mc-seckill / mc-order 各 1 个健康实例）
```

结论：`mvn package` → **36/36 测试通过，四个模块 BUILD SUCCESS**；数据库 12 个活动的
`total_stock - available_stock` 与订单数**逐条相等**（活动 7 已还原为 5/5、0 单）。

## 代码评审修复 · 第六轮

前五轮把「累计超额分配」拦在了发布入口，但拦得再严也只是**校验**——
商品库存仍然是各场活动共享的同一个数字，校验通过之后，货到底归谁并没有记账。
这一轮把它变成真正的预留。

| 改动 | 落点 | 为什么必须这么做 |
|---|---|---|
| 发布时原子预留商品库存 | `ProductMapper.reserveStock`（`stock = stock - n WHERE stock >= n`）+ `seckill_activity.reserved_stock` | 校验能挡住「明显不够」，挡不住「并发下刚刚够」。判断与扣减写在同一条 UPDATE 里，才不会两个运营各自看到 100 件、各自发布 80 件 |
| 已预留活动的订单不再扣商品库存 | `OrderPersistService` 先读 `reserved_stock`，>0 则跳过 `deductProductStock` | 货在发布时就已经从 `product.stock` 扣走了，落库再扣一次就是**双扣**——商品页库存会比真实少卖件数那么多 |
| 历史活动保持旧逻辑 | `reserved_stock = 0` 走逐单扣减 | 迁移前发布的活动没有预留过，若也跳过扣减，它们的订单就完全不再影响商品库存，等于超卖 |
| 活动结束后只归还未售部分 | `InventoryReservationReleaseService`：`available_stock` 即未售，加回 `product.stock`，并写 `reservation_released_at` 防重复 | 不归还 → 商品永久少卖已结束活动的剩余量；归还早了 → 迟到订单用的库存已经给了下一场，同一件货卖两次 |
| 归还的前置条件：outbox 全部终态 | `findReleasableReservations` 要求不存在 `status IN (0,1,4,5,7)` | 只要还有待投递/待消费/待人工记录，就仍可能有订单落库。**「可能还会卖」就不能把货还回去** |
| 复用 `ROLLED_BACK/ABORTED` 记录时清零 `reconcile_count` | `SeckillOutboxMapper.resetAbandoned` | 不清零的话，一条被回滚又复用的记录带着旧的对账次数，第一次对账就直接被判「超上限」转人工 |

**关键判断：预留在发布时做，不在创建时做。** 草稿不占库存，运营可以随便改；
真正有副作用的是发布。这和第五轮把「累计校验」放在 publish 而不是 create 是同一个理由。

**代价（写清楚，别假装没有）**：预留一旦发生，商品在那场活动结束并释放之前就少了这批货。
如果某场活动的 outbox 卡在 `NEEDS_REVIEW(4)` 永远不收敛（消费者永久故障且人工不处理），
这批预留就再也不会归还，商品库存会永久偏低。这是有意选的方向——
少卖是可用性问题，超卖是资损，两者不对等。卡住的记录本身会打 `【需人工复核】` 的 error 日志。

**另外补了一处被新任务放大的旧隐患**：`spring.task.scheduling.pool.size` 原来没配，
默认只有 1 个线程，而本轮把定时任务加到了 4 个（补偿 5s / 孤儿 30s / 预热 30s / 释放 30s）。
broker 不可用时补偿任务单轮最坏 40 秒，这期间预热和释放全在排队——
一个中间件故障会被放大成「新活动灌不进 Redis + 已结束活动不归还库存」。
已显式配置 `pool.size: 4`。

### 实测（2026-11-24 02:12，经 8081 网关）

```
商品 2 初始库存 250
发布活动 18（预留 2 件）           → product.stock 250 → 248
用户抢购 1 件、订单落库            → product.stock 仍 248（没有第二次扣减）
活动结束、outbox 全部终态          → 未售 1 件归还，248 → 249
活动 18 账本：total=2 available=1 reserved=2 released=1 订单=1
```

## 代码评审修复 · 第七轮

第七轮评审提了 6 条，**逐条核实后 5 条属实、1 条方向对但触发条件比描述的窄**。
这一轮没有新功能，只有一件事：**把「按时间猜」的地方改成「拿凭据判断」**。

| 级别 | 问题 | 核实结论 | 修复 |
|---|---|---|---|
| P0 | 人工回滚进入状态 5 后没有所有权/心跳，120 秒后被恢复任务无条件改回 4 | **属实**（比描述的更严重，见下） | 删掉 `recoverStaleRollingBack`；卡住的 5 进管理端列表（`state=ROLLING_BACK_STALLED`），由 `POST /{id}/recover` 显式放回 4 |
| P1 | `countActiveByActivity` 与 `findReleasableReservations` 的 `NOT EXISTS` 没有以 `activity_id` 打头的索引 | **属实**，EXPLAIN 实测走 `idx_status_retry`（status 基数极低）| 新增 `sql/09_outbox_activity_index.sql`：`idx_activity_status(activity_id, status)`，幂等 |
| P2 | `markSent` 只按 `activity_id + user_id` 更新，记录被复用后旧线程会误标新预订 | **属实**（后果是延迟 5 分钟投递，不是丢消息） | where 加 `reservation_id`，接口签名同步改 |
| P2 | 生产者启动异常被吞掉后没有后台重启路径 | **机制存在，但触发条件比描述窄**（见下） | 仿消费者的 `ensureStarted()`：每 30 秒检查，失败就新建实例重启 |
| P2 | e2e 脚本声称检查商品库存，实际只查了活动账本/Redis/买家数/订单数 | **属实**（脚本头的第 5 条判定标准就是空头支票） | 对账视图加 `productStock/reservedStock/reservationReleased`，脚本补三段断言 |
| P2 | 只有 Mockito 单元测试，没有启动 Spring、跑真实 SQL、接 Redis/MQ 的集成测试；迁移是 9 个手工 SQL | **属实**，但不在这一轮改（理由见下） | 记为后续项，不假装解决 |

**P0 为什么比评审说的更严重。** 评审担心的是「原线程很慢 + 消费者抢到 7 并落单」。
实际读代码后发现，状态 5 本身就是那把锁：消费者落库事务里读到 5 会直接
`OrderRejectedException`（见 `mc-order` 的 `OrderPersistService`），
所以「持有 5」= 「此刻这一单没人能动」。定时任务把 5 改回 4，等于**替一个可能还活着的线程
把锁撬了**：一旦放回 4，消费者就会照常落库，而原线程随后仍会执行 Redis 回滚，
最终「库存已还 + 订单已建」——正是 `OutboxAdminController` 整个类存在的意义所在
（它前 100 行注释都在论证怎么避免这个结局）。

`finally` 拦不住这种回收：finally 只保证「我这个线程退出时把状态改回去」，
而记录已经被别的线程改过，CAS 会更新 0 行——那一刻它连自己失去了所有权都不知道。

**修法选了「交给人」这条。** 评审给了两个方向：owner token + 租约续期 + fencing，
或者取消自动回收。选后者，理由是**时间无法证明持有者已死**：
只要还用「N 秒没动静就当它死了」，就还是同一个赌局，只是把窗口从 120 秒缩到 30 秒。
租约 + fencing 能真正解决，但要在 `seckill_outbox` 上加 owner/lease 两列、
给回滚接口加心跳线程、给 Redis 回滚脚本加 fencing token——为了一个**只在管理端手工触发**
的低频操作，成本明显不划算。

现在的处置链是：卡住的 5 出现在 `GET /review`（`state=ROLLING_BACK_STALLED`），
运维确认「那次请求确实没了」（服务重启过 / 日志里已结束）再调 `POST /{id}/recover`。
最坏结果是这批货暂时少卖、人工退掉；比超卖轻一个量级——和「重试耗尽不自动回滚」是同一条原则。

**P2 生产者那条要说清楚。** 机制确实缺：`@PostConstruct` 里 `producer.start()` 失败后
只有一行 error 日志，之后所有 `send()` 都返回 false，直到服务重启。
但我实测了「把 NameServer 停掉再启动服务」——**`start()` 依然成功**，
因为 `DefaultMQProducer.start()` 不依赖 NameServer 可达，它只初始化客户端；
真正失败的是之后的 `send()`（`No route info of this topic`），
而那本来就该由本地消息表兜住（实测：NameServer 恢复后第一次 `send` 失败、第二次成功，
中间没有任何代码重启）。所以这条修复是**防御性**的：为「客户端初始化异常」
（配置非法、client 实例创建失败）留一条自愈路径，不是修一个已知必现的故障。
照抄消费者的做法，顺便还修正了一个隐患：**每次重试都新建 producer 实例**——
`DefaultMQProducer.start()` 失败后内部状态可能停在不可恢复的位置，
对同一个对象再 `start()` 会抛 `service state not OK`，那「重启路径」本身也永远起不来。

**P2 商品库存断言这条，是本轮唯一能顺手补上的「测试缺口」。** 上一轮用人工操作验证了
「250 → 248 → 249」，但脚本里没有这一步，等于回归时没人能自动发现双扣。
现在对账视图一次返回四组数（活动账本 / Redis / 已抢人数 / `product.stock`），
脚本按「发布前记基线」的方式断言三段：
发布后 `-STOCK`、落库后不变、结束后只 `+未售`。这条断言必须**一次请求取全**，
分两次请求拿到的是两个时刻的库存，比较就没有意义了。

**测试与部署（不做，但要说明理由）。** 引入 Flyway 的前提是把 9 个手工 SQL 变成
「只跑一次、有版本历史」的迁移集，而现在这些脚本里 `07_reconcile_activity7.sql`
是一次性数据修复、`03`/`09` 靠 `information_schema` 手写幂等——直接塞进 Flyway
会把「一次性修复」变成「每次新环境都执行的迁移」，语义反而更糟。Testcontainers 需要 Docker，这台开发机上没有，且项目依赖
Nacos/RocketMQ 两个重中间件，容器化的收益低于「一个能复现的 e2e 脚本」。
所以本轮的取舍是：**把这唯一能自动化的一段（商品库存）补进 e2e 脚本**，其余记为后续项。

### 实测（2026-11-24 03:05，新 jar + 全部服务重启）

```
索引：EXPLAIN 前 key=idx_status_retry(filtered 10%) → 后 key=idx_activity_status(Using index, filtered 100%)
      NOT EXISTS 子查询：前 range 扫 → 后 ref + Using index + Not exists
状态 5：插入一条 updated_at=5 分钟前的 status=5 记录，25 秒（补偿任务 5 轮）后仍是 5（旧代码这里会变 4）
        GET /review → state=ROLLING_BACK_STALLED
        POST /recover → 200 {status:4}；再调 → 409；不存在的 id → 404
e2e 3 人抢 5 件（活动 22，商品 10）：
        基线 255 → 发布 250（预留 5）→ 3 单落库后仍 250（无二次扣减）
        → 结束后归还未售 2 件 → 252
        账本 total=5 available=2；Redis 库存 2；已抢 3；订单 3
e2e 20 人抢 5 件（活动 21）：5 单全部落库，product.stock 260 → 255 全程只扣一次
producer 自愈：停 NameServer 起服务，start() 仍成功（说明这条是防御性修复）；
        NameServer 恢复后 send 第 1 次失败、第 2 次成功，未重启服务
测试：42 个（新增 OutboxAdminControllerTest 6 个）全绿
```

## 关键设计

**库存存两份，职责不同**：DB 的 `available_stock` 是账本（对账、可恢复），
Redis 的库存是闸门（承接瞬时流量）。两者不一致时以 DB 为准——Redis 丢了可以重新预热，
DB 丢了就是资损。

**一人一单有三道防线**：网关身份（换不了 ID）→ Redis Lua 原子去重（挡住 99% 重复）
→ `uk_user_activity` 唯一索引（最终正确性）。前两道是性能优化，最后一道才是保证。

**投递语义是「至少一次」**：本地消息表 + 重投保证消息不丢，消费端唯一索引保证重复无害。
只用其中一半都不成立：只重投会重复下单，只幂等会丢消息。

**为什么下单不用 MQ、秒杀用 MQ**：日常下单 QPS 低，同步落库能立刻返回订单号、强一致更简单；
秒杀瞬时 QPS 差两个数量级，同步落库会把数据库打穿，所以先扣 Redis 库存、异步落库、
前端轮询结果。这也是被迫的取舍：异步就意味着「不能立刻给订单号」。

