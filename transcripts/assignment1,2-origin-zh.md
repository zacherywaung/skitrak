# CS6650分布式系统assignment

## User

OK，我们新的一次作业来了，你先了解一下作业，我们后续讨论

## Claude

我读完了。这次作业把 Assignment 1 里的“假”服务器变成了一条真正的分布式流水线。总结一下我的理解，以及几个后面需要重点讨论的地方。

## 整体架构

```
客户端（A1 的 client）
   │ POST swipe                       ▲ WebSocket 推送真实排行榜
   ▼                                  │
 ALB ──▶ server-v2 × N（ingest + WebSocket endpoint）
             │ 发布消息                ▲ 订阅排行榜更新
             ▼                        │
       RabbitMQ（单独的 EC2）          │
       skitrak.exchange（topic）       │
       resort.1 … resort.10           │
             │                        │
             ▼                        │
       Consumer（单独的 EC2）           │
       聚合：每个 skier 的 vertical    │
       维护：每个 resort 的 top-N ─────┘（backplane 或 co-location）
```

## 四个部分分别要做什么

**Part 1：服务端变成生产者。** 收到合法的 swipe 后，查表得到这个 lift 的 vertical，组装成规定格式的消息，发布到 RabbitMQ。重点是 **ChannelPool**：整个服务端只用一个 Connection，channel 预先创建好放进池里，每次发布时借出、用完归还，绝对不能每次发布都新开一个 channel。此外还要处理 RabbitMQ 连接断开后的重连。这和 A1 里 HTTP 连接池的思路完全一样，只是这次需要你自己实现这个池。

**Part 2：消费者、聚合和真实的排行榜。** 这是整个作业的核心，包含三个难点：

- **聚合**：累加每个 skier 当天的 vertical，并维护每个 resort 的 top-N 排名。
- **连接位置问题**：WebSocket 连接在某一台 server-v2 上，但计算出新排行榜的是另一台机器上的 consumer，它无法直接推送给订阅者。你需要在 backplane（广播）和 co-location（按 resort 分区）之间选择一种方案，并说明理由。
- **投递保证**：至少一次投递（处理完再手动 ack）、按 eventId 去重实现幂等、保证同一个 resort 内的消息按顺序处理。

**Part 3：负载均衡。** 用 ALB 把流量分发到多台 server-v2，配置 `/health` 健康检查、开启 WebSocket 的粘性会话，并把空闲超时设置为大于 60 秒。你在 A1 写的 health endpoint 在这里正式派上用场。

**Part 4：调优和测试。** 目标是队列深度稳定在 1000 以下、consumer 延迟小于 100ms、不丢消息。需要分别测试 1 台、2 台、4 台服务器，最后还要做一次 100 万条的压力测试。

## 我注意到的几个需要后面仔细讨论的地方

**1. 顺序要求和 consumer 线程数之间存在矛盾。** 作业要求测试 10、20、40、80 个 consumer 线程，但同时又要求同一个 resort 的消息不能被多个线程并发消费，以免打乱顺序。而 resort 队列一共只有 10 个。也就是说，在严格保证顺序的前提下，最多只有 10 个线程能真正在消费，多出来的线程要么闲着，要么需要别的设计。这一点值得好好想清楚。

另外还有一个有意思的问题：vertical 的累加是满足交换律的，先加 A 再加 B，和先加 B 再加 A，结果完全一样。所以顺序对最终的总数其实没有影响，那它到底在什么场景下才重要？这种需要你自己推理的问题，正好是 AI 使用反思里要求写的内容。

**2. 排行榜广播的频率。** 作业要求“top-N 一旦变化，就推送一次”。但在每秒几千条 swipe 的压力下，top-N 可能非常频繁地变化，导致推送量爆炸，把 backplane 和 WebSocket 都压垮。可能需要做一些合并，比如每个 resort 最多每几百毫秒推送一次。

**3. 去重集合的内存。** 按 eventId 去重，意味着要记住已经处理过的 ID。100 万条的压力测试就有 100 万个 UUID，需要考虑这个集合的上限和过期策略。

**4. 发布消息和返回 201 的时机。** 是消息一发出就立即返回 201，还是等 RabbitMQ 确认收到之后（publisher confirms）再返回？前者更快，后者更可靠，这会直接影响你的吞吐量和“不丢消息”的目标。

**5. 成本。** 这次最多需要 RabbitMQ、consumer、4 台 server-v2，再加上可能运行客户端的机器，一共六七台 EC2，外加一个 ALB。免费额度是所有实例合计每月 750 小时，很容易超出，ALB 也可能单独收费。测试时最好集中进行，结束后及时停掉实例。

**6. AI 使用反思（5 分，必须提交）。** 这次要求写 `AI_USAGE.md`，至少列出两条你采纳的建议和两条你拒绝或修改过的建议，还要说明一个 AI 出错、或者你自己推理决定的地方。所以接下来讨论时，你可以有意识地记录下自己判断和取舍的过程。我们上次对话中，我自己纠正了 `backoffMs` 的线程安全问题，你也指出过我没理解你的意思的地方，这些都是可以写的真实素材。

## 建议的推进顺序

1. 在 EC2 上搭建 RabbitMQ，创建 exchange 和 10 个队列。
2. 在 server-v2 里实现 ChannelPool 和消息发布，确认消息能进入队列。
3. 写 consumer，先只做聚合，不管 WebSocket，确认计算结果正确。
4. 实现 backplane，让真实的排行榜推送到订阅者。
5. 用单台服务器跑一遍 A1 的客户端。
6. 配置 ALB，测试 2 台和 4 台服务器。
7. 调优，整理测试结果和文档。

截止日期是 11 月 8 日，从今天算起大约还有两周半。你想从哪里开始？如果没有特别的想法，我建议先从第 1 步搭建 RabbitMQ 开始，它是后面所有步骤的基础。

## User

等一下，我来说说我的理解，现在期待的成果是，客户端不变，现在给alb服务器发送swipe，服务端是分布式的，中间有负载均衡，这个负载均衡器是alb自带的，还是我在代码层面需要设计的，然后server收到swipe之后，交给rabbitmq，并返回201，rabbitmq交给消费者，消费者对所有swipe进行计算？然后生成leaderboard，再还给rabbitmq，rabbitmq通过广告板的形式，也就是fanout，通知给所有的server，server根据更新内容将榜单推送给对应resort的客户端，是这样吗？如果有补充或者错误，请指出

## Claude

你的理解基本正确。下面补充几个细节，有两处需要纠正。

## 1. 负载均衡器是 AWS 提供的，不需要你写代码

ALB（Application Load Balancer）是 AWS 的托管服务。你在 AWS 控制台里**配置**它就行，不需要写任何负载均衡的代码。需要配置的内容有：

- **目标组（target group）**：把你的几台 server-v2 加进去。
- **健康检查**：路径设为 `/health`，间隔、超时等参数按作业要求填写。
- **粘性会话**：开启 stickiness。
- **空闲超时**：设置为大于 60 秒。

你的代码几乎不需要为此做任何修改，只要每台 server-v2 都能独立地处理请求，并且有 `/health` 就可以了。

客户端也基本不用改，只需要把服务器地址从某台 EC2 的 IP 换成 **ALB 的 DNS 名称**。POST 请求和 WebSocket 连接都通过 ALB 进入。

## 2. 服务端：验证 → 查表 → 发布 → 返回 201

这部分理解正确。补充一个细节：服务端是把消息发布到 **exchange**，而不是直接发到某个队列。具体来说，消息发布到 `skitrak.exchange`，routing key 设为 `resort.7`，RabbitMQ 再根据 routing key 把消息路由到 `resort.7` 这个队列。

至于是“一发布就返回 201”，还是“等 RabbitMQ 确认收到后再返回”，这是后面要做的一个设计决定。

## 3. 消费者：是逐条增量计算，而不是对所有 swipe 一起计算

这里需要纠正一下表述。消费者**订阅**队列之后，RabbitMQ 会持续把消息推给它。消费者每收到一条 swipe，就只做一次增量更新：

```
收到一条 swipe（skier 123，vertical 200）
  → skier 123 的累计 vertical += 200
  → 检查这个 skier 是否因此进入 top-N，或者在 top-N 里的名次是否改变了
  → 如果 top-N 发生了变化 → 发布新的排行榜
  → ack 这条消息
```

它不会把所有 swipe 收集起来再统一计算，而是来一条处理一条，排行榜始终保持最新状态。另外，每个消费者线程只负责其中几个 resort 的队列，而不是处理所有的 swipe。

## 4. Backplane：方向正确，但有一个关键细节

首先纠正一个名词：backplane 的中文是“**背板**”，不是“广告板”。它原本指的是计算机机箱里连接各个板卡的那块总线板，任何一张卡发出的信号，所有的卡都能收到。用在这里，意思是“所有服务器共享的一条广播通道”。不过你把它理解成“公告板”也说得通：任何人贴出来的通知，所有人都能看到。

整体流程你理解得没错：消费者把新的排行榜发布到一个 **fanout exchange**（比如 `leaderboard.fanout`，这是和 `skitrak.exchange` 不同的另一个 exchange），每台服务器都会收到，然后只推送给自己本地订阅了这个 resort 的客户端。

**关键细节在于：每台服务器必须有自己专属的队列。**

```
错误的做法：所有服务器共用一个队列
  leaderboard.fanout → [共享队列] → server-1、server-2 轮流取消息
  结果：每条消息只会被其中一台服务器拿到，连在另一台上的订阅者就收不到更新

正确的做法：每台服务器各有一个队列
  leaderboard.fanout ─┬→ [server-1 的队列] → server-1
                      ├→ [server-2 的队列] → server-2
                      └→ [server-3 的队列] → server-3
  结果：每条消息会被复制到每个队列，所有服务器都能收到
```

这是 RabbitMQ 的一个基本规则：**fanout 会把消息复制给每个绑定的队列；但同一个队列里的消息，只会交给其中一个消费者**（多个消费者是竞争关系）。所以通常的做法是，每台服务器启动时声明一个临时队列（exclusive、auto-delete，名字由服务器自动生成），绑定到 fanout exchange 上。服务器关闭时，这个队列也会被自动删除。

另外要注意，backplane 只是两种方案之一，另一种是 co-location。作业要求你选择其中一种，并说明理由。根据你的描述，你倾向于 backplane，我也认为它更适合这次的架构，后面我们再详细讨论这两种方案的取舍。

## 5. A1 里的定时推送要去掉

server-v2 依然负责 WebSocket 连接，但 A1 里每 500ms 推送一次假数据的定时器不再需要了。现在的推送是由 backplane 收到的消息触发的：收到一条更新，就推送一次。

## 修正后的完整流程

```
客户端 ──POST──▶ ALB ──▶ 某台 server-v2
                          验证 → 查 vertical → 发布到 skitrak.exchange（resort.7）→ 返回 201
                                     │
                                     ▼
                        RabbitMQ：路由到 resort.7 队列
                                     │
                                     ▼
                        Consumer：累加 vertical → 更新 top-N → 如有变化，发布到 leaderboard.fanout → ack
                                     │
                                     ▼
                        RabbitMQ：fanout，复制到每台 server-v2 各自的队列
                                     │
                                     ▼
                        每台 server-v2：找到本机上订阅了 resort 7 的 session → 推送排行榜
                                     │
客户端 ◀──WebSocket（经过 ALB）──────┘
```

## User

backplane和co-location有什么区别？你讲讲你的见解

## Claude

两种方案要解决的是同一个问题：**计算出新排行榜的 consumer，和持有订阅者 WebSocket 连接的 server，往往不在同一台机器上。** 它们的思路正好相反：

- **Backplane**：不管订阅者连在哪台服务器上，**把更新广播给所有服务器**，每台服务器自己判断要推送给谁。
- **Co-location**：**提前约定好**，让同一个 resort 的计算和订阅者都集中在同一台机器上，这样根本就不需要跨机器传递更新。

## Backplane：让消息去找连接

```
                    Consumer（单独的机器，处理所有 resort）
                           │ 发布 resort 7 的新排行榜
                           ▼
                  leaderboard.fanout
              ┌────────┼────────┐
              ▼        ▼        ▼
          server-1  server-2  server-3     ← 每台都收到
          (无人订阅 7) (2 个订阅者) (1 个订阅者)
            丢弃      推送给 2 个  推送给 1 个
```

订阅者可以连接到任意一台服务器，ALB 怎么分配都可以。

## Co-location：让连接去找数据

```
     节点 A（负责 resort 1-5）            节点 B（负责 resort 6-10）
     ├─ 消费 resort.1 … resort.5         ├─ 消费 resort.6 … resort.10
     ├─ 在内存中计算排行榜                ├─ 在内存中计算排行榜
     └─ 持有 resort 1-5 的所有订阅者       └─ 持有 resort 6-10 的所有订阅者
              ↑                                    ↑
      ALB 必须把 /leaderboard/1~5 路由到这里   ALB 必须把 /leaderboard/6~10 路由到这里
```

计算完成后，直接在本机内存里找到订阅者推送，不需要经过任何网络中转。

## 两者的对比

| | Backplane | Co-location |
|---|---|---|
| 订阅者可以连到哪台服务器 | 任意一台 | 必须是负责该 resort 的那台 |
| 推送延迟 | 多一跳：consumer → RabbitMQ → server | 最低，直接在本机内存里推送 |
| 消息量 | 放大：每条更新 × 服务器数量 | 不放大 |
| 增加服务器 | 简单，加进 ALB 就能用 | 需要重新划分 resort，迁移状态 |
| 扩展的上限 | 服务器数量没有限制 | 最多 10 个节点（因为只有 10 个 resort） |
| 热点问题 | 不存在 | 某个 resort 特别热门，负责它的节点就会过载 |
| 节点宕机 | 只影响连在上面的订阅者，它们重连到其他服务器即可 | 这几个 resort 的计算和订阅都会中断，直到有其他节点接管 |
| 实现复杂度 | 中等：每台服务器都要订阅 backplane | 看起来简单，但故障转移和重新分区很复杂 |

## 我的看法：这次作业选 backplane 更合适

**理由一：作业的部署要求本身就更适合 backplane。** 作业明确要求 consumer **运行在单独的 EC2 上**。而 co-location 的本质，是让计算和连接处在同一个节点上，这就和独立的 consumer 层相冲突了。如果要做 co-location，就得把 consumer 合并进 server-v2，这偏离了作业给出的部署架构。

**理由二：测试需要不断增加服务器。** 你要分别用 1 台、2 台、4 台服务器进行测试。用 backplane，增加服务器只需要把它注册到 ALB 里；用 co-location，每次改变服务器的数量，都要重新划分 resort 的归属，同时修改 ALB 的路由规则。

**理由三：在这个规模下，backplane 的缺点几乎可以忽略。** 它最主要的代价是消息放大，但可以算一下：10 个 resort，假设每个 resort 每秒最多推送 5 次排行榜，服务器有 4 台，那么 backplane 上每秒的消息量是：

```
10 × 5 × 4 = 200 条/秒
```

这对 RabbitMQ 来说微不足道。真正的压力在 ingest 那边（每秒几千条 swipe），而不在排行榜这边。前提是你要**限制排行榜推送的频率**，也就是前面提到的合并推送。如果每处理一条 swipe 就推送一次，这个数字会膨胀几百倍。

**理由四：co-location 的优势在这里体现不出来。** co-location 真正有价值的场景，是更新量极大、或者对延迟要求极高的系统，比如大型聊天系统按房间分片。而对一个排行榜来说，多一跳带来的几毫秒延迟，用户根本感知不到。

## 两个可以进一步改进的地方

**1. 用 topic exchange 代替 fanout，减少无用的消息。** 纯粹的 fanout 会让每台服务器收到所有 resort 的更新，即使本机没有任何人订阅某个 resort。改进的做法是：用 topic exchange，routing key 设为 `leaderboard.7`；每台服务器只在本机**有人订阅 resort 7 时**，才绑定 `leaderboard.7`。这样每台服务器只会收到自己需要的更新。代价是要在订阅者第一次连接和最后一个断开时，动态地绑定和解绑。对于这次作业，用纯 fanout 也完全足够，这一条可以作为设计文档里的扩展讨论。

**2. 新订阅者连接时，立刻发送一份最新的排行榜。** 在 backplane 方案里，一个新的订阅者连接进来之后，要等到下一次排行榜发生变化，才能收到第一条消息。如果每台服务器都把 backplane 收到的**每个 resort 的最新排行榜缓存一份**，在 `onOpen` 时直接发送给新连接的订阅者，体验会好很多。

## 写进设计文档时

作业要求你说明选择的理由和取舍。可以这样组织：说明选择 backplane 的原因（和独立的 consumer 层、需要弹性增加服务器的要求相契合），坦诚它的代价（多一跳的延迟和消息放大），再说明你用什么方法控制了这个代价（限制推送频率，以及可选的按 resort 订阅），最后说明在什么情况下 co-location 会是更好的选择。

这一段也正好可以作为 AI 使用反思的素材，但前提是你**确实经过了自己的思考**，比如你认同了哪些理由，对哪些理由有不同的看法。

## User

怎么在我的代码中引入rabbitMQ？是不是要加依赖

## Claude

是的，需要加依赖。RabbitMQ 官方提供的 Java 客户端库叫 `amqp-client`，它负责建立连接、创建 channel、发布和消费消息。

## 1. 添加依赖

在 **server-v2** 和 **consumer** 两个项目的 `pom.xml` 里都要添加：

```xml
<dependency>
    <groupId>com.rabbitmq</groupId>
    <artifactId>amqp-client</artifactId>
    <version>5.21.0</version>
</dependency>
```

版本号只是示例，可以去 Maven Central 查一下 5.x 的最新稳定版。

两个项目都需要它，是因为：

- **server-v2**：既要把 swipe **发布**到 `skitrak.exchange`，又要从 backplane **接收**排行榜的更新。
- **consumer**：要从 resort 队列**消费** swipe，再把排行榜**发布**到 backplane。

**注意：这里不要写 `<scope>provided</scope>`。** 这一点和 servlet-api、websocket-api 正好相反。那两个库在运行时由 Tomcat 提供，所以不用打进 WAR；而 Tomcat 并不自带 RabbitMQ 的客户端，所以要使用默认的 compile scope。这样 `mvn package` 时，它会被打包进 WAR 的 `WEB-INF/lib` 目录。consumer 是独立运行的程序，和 A1 的客户端一样，需要打成 fat jar。

## 2. 顺便处理一个日志警告

`amqp-client` 依赖 SLF4J 日志接口。如果项目里没有具体的日志实现，启动时会打印一段 `SLF4J: No SLF4J providers were found` 的警告。它不影响功能，但看起来不太舒服。可以加一个最简单的实现：

```xml
<dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-simple</artifactId>
    <version>2.0.13</version>
</dependency>
```

## 3. 最基本的用法

先看一个最简单的例子，了解整个流程。后面的 ChannelPool 就是在这个基础上把 channel 池化：

```java
ConnectionFactory factory = new ConnectionFactory();
factory.setHost("<RabbitMQ 的私有 IP>");
factory.setPort(5672);
factory.setUsername("skitrak");
factory.setPassword("<密码>");

Connection connection = factory.newConnection();   // 一个 TCP 连接，整个服务共享
Channel channel = connection.createChannel();      // 在连接上开一个 channel

channel.exchangeDeclare("skitrak.exchange", "topic", true);   // 声明 exchange，durable
channel.basicPublish("skitrak.exchange", "resort.7", null, json.getBytes(StandardCharsets.UTF_8));
```

这里的层级关系是：**一个 Connection 对应一条 TCP 连接，可以在上面开很多个 Channel。** 发布和消费消息都是通过 channel 进行的。Connection 是重量级的、线程安全的，整个应用共享一个；Channel 是轻量级的，但**不能在多个线程之间共享**。这正是作业要求你实现 ChannelPool 的原因。

## 4. 在 Tomcat 里，连接应该在哪里创建

server-v2 运行在 Tomcat 里，没有 main 方法。那么 Connection 和 ChannelPool 应该在什么时候创建？答案是：**应用启动时创建一次，应用关闭时关闭**，而不是在 `doPost` 里每次请求都创建。

Servlet 规范提供了一个专门用来监听应用启动和关闭的接口，叫 `ServletContextListener`：

```java
@WebListener
public class RabbitMQInitializer implements ServletContextListener {
    @Override
    public void contextInitialized(ServletContextEvent e) {
        // WAR 部署完成时，Tomcat 调用这里：创建 Connection 和 ChannelPool
        // 保存到 e.getServletContext().setAttribute("channelPool", pool)
    }

    @Override
    public void contextDestroyed(ServletContextEvent e) {
        // WAR 卸载或 Tomcat 关闭时调用：关闭所有 channel 和 connection
    }
}
```

之后 SkierServlet 就可以在 `init()` 里通过 `getServletContext().getAttribute("channelPool")` 拿到这个池。这和 A1 里讲过的“Tomcat 负责调用你的代码”是同一个思路，只是这次被调用的是应用的启动和关闭事件。

## 5. RabbitMQ 服务器那边要注意的两点

**1. 默认的 `guest` 用户只允许从 localhost 登录。** 你的 server-v2 和 consumer 都在其他机器上，用 guest 是连不上的，错误信息通常是 `ACCESS_REFUSED`。需要在 RabbitMQ 上创建一个新用户并授予权限：

```bash
sudo rabbitmqctl add_user skitrak <密码>
sudo rabbitmqctl set_user_tags skitrak administrator
sudo rabbitmqctl set_permissions -p / skitrak ".*" ".*" ".*"
```

**2. 安全组需要开放两个端口。**

- **5672**：AMQP 协议端口，server-v2 和 consumer 连接 RabbitMQ 时使用。只对你自己的那几台 EC2 开放即可。
- **15672**：管理界面端口。开启 management 插件后，可以在浏览器里查看队列深度、消息速率等信息。作业要求提交的队列截图就来自这里，只需对你自己的 IP 开放。

你的 RabbitMQ 已经在 EC2 上装好了吗？还没有的话，我们可以先从安装和创建 exchange、队列开始。

## User

建立好连接后，我需要做的是在初始化时，开是个resort对应的queue，是吗

## Claude

对，但需要澄清一个概念：队列不是“打开”的，而是“**声明**”（declare）的。

## 队列存在于 RabbitMQ 服务器上

Connection 和 Channel 是你的程序和 RabbitMQ 之间的**连接**，程序关闭了，它们也就消失了。而 exchange、队列、绑定关系是**创建在 RabbitMQ 服务器上的对象**，就像数据库里的表一样。它们只需要创建一次，之后会一直存在（声明为 durable 时，RabbitMQ 重启后也依然存在），和你的程序是否在运行没有关系。

所以，“初始化时声明 10 个队列”的真正含义是：**确保这些对象在 RabbitMQ 上存在**。如果已经存在，声明操作什么都不会做；如果不存在，就创建它们。这个操作是**幂等**的，可以放心地重复调用。

## 需要声明的完整结构

不只是 10 个队列，还包括 exchange，以及它们之间的绑定关系：

```
skitrak.exchange（topic 类型）
   ├── routing key = resort.1  ──绑定──▶ 队列 resort.1
   ├── routing key = resort.2  ──绑定──▶ 队列 resort.2
   │   ……
   └── routing key = resort.10 ──绑定──▶ 队列 resort.10
```

如果漏掉了绑定，消息发布到 exchange 之后找不到任何匹配的队列，就会被 RabbitMQ **直接丢弃**，而且不会报错。这是一个很常见的、悄无声息的丢消息原因。

## 代码

```java
channel.exchangeDeclare("skitrak.exchange", BuiltinExchangeType.TOPIC, true);   // durable

Map<String, Object> qArgs = new HashMap<>();
qArgs.put("x-message-ttl", 3_600_000);          // 消息最长保留 1 小时
qArgs.put("x-max-length", 1_000_000);           // 队列最多保存 100 万条消息
qArgs.put("x-overflow", "reject-publish");      // 队列满了就拒绝新消息，而不是丢弃旧消息

for (int i = 1; i <= 10; i++) {
    String name = "resort." + i;
    channel.queueDeclare(name, true, false, false, qArgs);   // durable, 非 exclusive, 不自动删除
    channel.queueBind(name, "skitrak.exchange", name);       // routing key 和队列名相同
}
```

`queueDeclare` 的几个参数依次是：队列名、是否 durable（RabbitMQ 重启后是否保留）、是否 exclusive（是否只属于当前连接）、是否 auto-delete（没有消费者时是否自动删除），以及附加参数。

## 作业要求的 TTL 和队列上限，有两个坑要注意

**1. TTL 设得太短会丢消息。** 消息在队列里停留超过 TTL 的时间后，会被 RabbitMQ 自动删除。如果 consumer 一时处理不过来，队列积压了一段时间，消息就会悄悄过期，这直接违反了“不丢失 swipe”的目标。所以 TTL 应该设得足够长（比如 1 小时），它的作用只是防止异常情况下消息无限期地堆积。

**2. 队列满了之后的默认行为是丢弃最旧的消息。** `x-max-length` 的默认溢出策略是 `drop-head`：队列满了以后，新消息照常进入，而最早的消息被**悄悄丢弃**，生产者对此毫不知情。改成 `reject-publish` 之后，队列满了就会拒绝新消息。如果再配合 publisher confirms，生产者就能知道这条消息没有发布成功，从而可以重试或者返回错误。

也要注意区分两个数字：作业的目标“队列深度稳定在 1000 以下”是**性能指标**，表示 consumer 跟得上生产速度；而 `x-max-length` 是**安全上限**，防止内存被耗尽。如果把它设成 1000，一旦出现短暂的流量高峰，就会立刻开始拒绝消息。

## 应该由谁来声明

有两种常见的做法：

- **在代码里声明**：server-v2 和 consumer 启动时都执行一次声明。因为操作是幂等的，4 台 server-v2 都执行也不会有问题，而且启动顺序无所谓，谁先启动谁就负责创建。
- **用脚本提前创建**：用 `rabbitmqadmin` 命令行工具写一个初始化脚本，放在作业要求的 `/deployment` 目录里。

我建议**两者结合**：把声明的代码写成一个公共的方法，server-v2 和 consumer 共用；同时在 `/deployment` 里也提供一份脚本。

使用公共方法有一个重要的原因：**如果对同一个队列的两次声明，参数不一致**（比如 server-v2 设置的 TTL 是 1 小时，而 consumer 设置的是 2 小时），RabbitMQ 会报 `PRECONDITION_FAILED` 错误（错误码 406），并直接关闭这个 channel。用同一个方法来声明，就能保证参数始终一致。如果你在调试过程中修改了队列的参数，需要先在管理界面里删掉旧的队列，再重新声明。

## User

deadletter是不是要单独设置exchange和queue？

## Claude

对。Dead letter 的机制是：**你自己创建一个普通的 exchange 和队列，然后告诉业务队列“有消息变成死信时，把它们送到这个 exchange”**。RabbitMQ 并不会自动帮你创建这些对象。

## 结构

```
skitrak.exchange ──▶ resort.1 … resort.10（业务队列）
                          │ 消息变成死信时
                          ▼
                     skitrak.dlx（死信 exchange，就是一个普通的 exchange）
                          │
                          ▼
                     skitrak.dlq（死信队列，就是一个普通的队列）
```

所谓“死信 exchange”，本身和普通的 exchange 没有任何区别，只是被某个队列**指定**为接收死信的地方。这是通过业务队列的一个参数 `x-dead-letter-exchange` 来实现的。

## 代码

```java
// 1. 先声明死信 exchange 和死信队列，并绑定
channel.exchangeDeclare("skitrak.dlx", BuiltinExchangeType.TOPIC, true);
channel.queueDeclare("skitrak.dlq", true, false, false, null);
channel.queueBind("skitrak.dlq", "skitrak.dlx", "#");   // "#" 匹配所有 routing key

// 2. 在业务队列的参数里，指定死信的去向
qArgs.put("x-dead-letter-exchange", "skitrak.dlx");
// 然后照常 queueDeclare("resort.N", ..., qArgs)
```

消息变成死信后，默认会**保留原来的 routing key**，比如 `resort.7`。所以死信 exchange 用 topic 类型并绑定 `#`，一个死信队列就能接收所有 resort 的死信。之后从死信队列里取出消息，仍然可以根据 routing key 知道它原本属于哪个 resort。

## 什么情况下消息会变成死信

| 原因 | 说明 |
|---|---|
| consumer 拒绝消息，并且不重新入队 | `basicNack(tag, false, false)` 或 `basicReject(tag, false)` |
| 消息超过了 TTL | 过期的消息不会被直接删除，而是进入死信队列 |
| 队列超过了长度上限 | **只在 `drop-head` 策略下**，被挤出的旧消息才会变成死信 |

第三点需要特别注意。上一轮我们建议用 `reject-publish` 策略，在这个策略下，被拒绝的新消息是**退回给生产者**的，不会进入死信队列。如果你希望被拒绝的消息也能进入死信队列，可以使用 `reject-publish-dlx` 策略。

## 它在作业里的作用：处理“毒消息”

作业要求实现“Failed-delivery retry logic”。假设 consumer 处理某条消息时总是失败（比如消息格式有问题，解析时总会抛异常），如果每次失败都用 `basicNack(tag, false, true)` 让它**重新入队**，这条消息就会被反复投递、反复失败，陷入无限循环，还会阻塞这个队列后面的所有消息。这种消息就叫“毒消息”（poison message）。

有了死信队列，就可以这样处理：

```
处理失败 → 在本地重试几次（比如 3 次）
         → 还是失败 → basicNack(tag, false, false)，不重新入队
         → 消息进入 skitrak.dlq，业务队列继续处理后面的消息
```

之后可以查看死信队列里有哪些消息，分析失败的原因。最后报告“死信队列里有 0 条消息”，也是证明“没有丢失 swipe”的有力证据。

关于重试次数怎么统计：对于普通的 classic 队列，RabbitMQ 只会告诉你一条消息“是否被重新投递过”（`redelivered` 标志），不会告诉你具体投递了几次。所以最简单的做法是在 consumer 的**本地**重试，而不是依赖重新入队。如果使用 quorum 队列，可以通过 `x-delivery-limit` 参数让 RabbitMQ 自动统计次数，超过上限后自动转为死信。

## 一个实践中的提醒：已经存在的队列不能直接修改参数

如果你已经声明过 `resort.1` 到 `resort.10`，现在再加上 `x-dead-letter-exchange` 参数重新声明，就会遇到上一轮提到的 `PRECONDITION_FAILED` 错误，因为参数和已有的队列不一致。有两种解决办法：

- **删除旧队列再重新声明**：在开发阶段最简单。在管理界面里删除，或者执行 `rabbitmqctl delete_queue resort.1`。
- **改用 policy 来设置这些参数**：TTL、长度上限、死信 exchange 都可以通过 policy 来配置，而不用写在队列的声明参数里。policy 的好处是**随时可以修改，不需要重新创建队列**，这也是 RabbitMQ 官方推荐的方式：

```bash
sudo rabbitmqctl set_policy skitrak-resorts "^resort\." \
  '{"message-ttl":3600000,"max-length":1000000,"overflow":"reject-publish","dead-letter-exchange":"skitrak.dlx"}' \
  --apply-to queues
```

这条命令表示：对所有名字以 `resort.` 开头的队列，应用这些设置。用 policy 的话，代码里的 `queueDeclare` 就可以不传附加参数了，同时也避免了多个程序声明参数不一致的问题。这条命令也很适合放进 `/deployment` 目录的初始化脚本里。

## User

那我如果要对我的broker设置这些，具体是怎么设置的，我已经建立起connection了，是直接用这个connection进行对话吗？

## Claude

不是直接用 Connection。**所有的操作都要通过 Channel 进行**：声明 exchange、声明队列、绑定、发布消息、消费消息，这些都是 Channel 上的方法。Connection 只负责维持那条 TCP 连接，本身不提供这些操作。

所以流程是：**从已有的 Connection 开一个 Channel，用它完成所有设置，然后关闭这个 Channel。**

## 完整的设置代码

```java
public static void declareTopology(Connection connection) throws IOException, TimeoutException {
    try (Channel ch = connection.createChannel()) {          // 临时开一个 channel，用完自动关闭

        // 1. 死信 exchange 和死信队列
        ch.exchangeDeclare("skitrak.dlx", BuiltinExchangeType.TOPIC, true);
        ch.queueDeclare("skitrak.dlq", true, false, false, null);
        ch.queueBind("skitrak.dlq", "skitrak.dlx", "#");

        // 2. 业务 exchange
        ch.exchangeDeclare("skitrak.exchange", BuiltinExchangeType.TOPIC, true);

        // 3. 业务队列的参数
        Map<String, Object> args = new HashMap<>();
        args.put("x-message-ttl", 3_600_000);
        args.put("x-max-length", 1_000_000);
        args.put("x-overflow", "reject-publish");
        args.put("x-dead-letter-exchange", "skitrak.dlx");

        // 4. 10 个业务队列，以及它们和 exchange 的绑定
        for (int i = 1; i <= 10; i++) {
            String q = "resort." + i;
            ch.queueDeclare(q, true, false, false, args);
            ch.queueBind(q, "skitrak.exchange", q);
        }
    }
}
```

`Channel` 实现了 `AutoCloseable` 接口，所以可以放在 try-with-resources 里，代码块结束时会自动关闭。

## 为什么用一个单独的临时 channel

你也可以从 ChannelPool 里借一个 channel 来做这些设置，但单独开一个更好，原因有两个：

1. **职责清晰**：设置只在启动时执行一次，执行完就不再需要这个 channel 了。池里的 channel 是专门用来发布消息的。
2. **出错不会污染池**：如果声明时参数不一致，触发了 `PRECONDITION_FAILED` 错误，RabbitMQ 会**关闭这个 channel**，但不会关闭 Connection。用临时 channel 的话，坏掉的只是这一个临时的；如果用的是池里的 channel，你就会把一个已经关闭的 channel 还回池里，后面借到它的线程一发布消息就会报错。

## 在哪里调用

- **server-v2**：在 `ServletContextListener` 的 `contextInitialized` 里，创建 Connection 之后、初始化 ChannelPool 之前调用。
- **consumer**：在 main 方法里，建立连接之后、开始消费之前调用。

两边调用的是同一个方法，参数自然就是一致的。可以把它放在一个公共的类里，两个项目各复制一份，或者抽成一个共享的模块。

## 另一种方式：policy

上一轮提到的 policy 方式，**不能通过 Channel 来设置**。AMQP 协议本身只能声明 exchange、队列和绑定，policy 属于 RabbitMQ 的管理功能，只能通过下面三种途径设置：

- **在 RabbitMQ 所在的机器上**，执行 `rabbitmqctl set_policy ...` 命令。
- **管理界面**：浏览器打开 `http://<RabbitMQ IP>:15672`，进入 Admin → Policies 添加。
- **管理 HTTP API**：通过 15672 端口发送 HTTP 请求，适合写成脚本。

如果你决定使用 policy，代码里的 `queueDeclare` 就不需要传 args 参数了（传 `null` 即可），TTL、长度上限、死信这些设置都交给 policy 负责。这两种方式选一种就好，**不要同时使用**，否则排查问题时很难判断到底是哪边的设置在生效。对于这次作业来说，直接在代码里传参数更简单直观；policy 的好处是修改时不需要删除和重建队列，更适合在调优阶段反复调整参数。

## 设置完之后怎么确认

打开管理界面：

- **Exchanges** 页面应该能看到 `skitrak.exchange` 和 `skitrak.dlx`。
- **Queues** 页面应该能看到 `resort.1` 到 `resort.10`，以及 `skitrak.dlq`。点开任意一个 `resort.N`，在 Features 一栏可以看到 TTL、Lim（长度上限）、DLX 等标记，在 Bindings 一栏可以看到它和 `skitrak.exchange` 的绑定关系。

确认无误之后，就可以开始写 ChannelPool 了。

## User

这个规则里的key是rabbitMQ 规定必须这么写的是吧

## Claude

对，args 里的这些 key 都是 RabbitMQ 规定好的名字，必须完全一致地写，包括 `x-` 前缀和中间的连字符。

## 常用的队列参数

| key | 值的类型 | 含义 |
|---|---|---|
| `x-message-ttl` | 整数，单位毫秒 | 消息在队列中最长保留多久 |
| `x-max-length` | 整数 | 队列最多容纳多少条消息 |
| `x-max-length-bytes` | 整数 | 队列最多容纳多少字节 |
| `x-overflow` | 字符串：`drop-head`、`reject-publish`、`reject-publish-dlx` | 队列满了之后怎么处理 |
| `x-dead-letter-exchange` | 字符串，exchange 的名字 | 死信发送到哪个 exchange |
| `x-dead-letter-routing-key` | 字符串 | 死信使用的 routing key（不设置则保留原来的） |
| `x-expires` | 整数，单位毫秒 | 队列在多久没被使用后自动删除 |
| `x-queue-type` | 字符串：`classic`、`quorum`、`stream` | 队列的类型 |

**值的格式也是规定好的。** 比如 `x-overflow` 的值只能是上表里列出的那几个字符串之一；TTL 必须是整数，如果写成字符串 `"3600000"`，RabbitMQ 会因为类型不对而报 `PRECONDITION_FAILED` 错误。

## 最容易踩的坑：key 写错了，不会报错

如果你把 key 拼错了，比如写成 `x-message-tll` 或者 `message-ttl`（漏了 `x-`），RabbitMQ **不会报任何错误**，它只会把这个参数当作一个它不认识的附加信息保存下来，然后忽略它。队列可以正常创建，但你以为设置好的 TTL 实际上根本没有生效。

所以声明完之后，一定要在管理界面上确认一下：打开 Queues 页面，查看每个队列的 Features 一栏。TTL、Lim、DLX 这些标记如果没有出现，就说明对应的 key 写错了。

## 哪些名字是你自己起的

需要区分一下，下面这些名字是**你自己决定的**，RabbitMQ 并不关心叫什么：

- exchange 的名字：`skitrak.exchange`、`skitrak.dlx`
- 队列的名字：`resort.1`、`skitrak.dlq`
- routing key：`resort.7`

这次作业里，`skitrak.exchange` 和 `resort.{resortID}` 的命名格式是作业规定的，所以要按作业的要求来写。而 topic 绑定里的通配符 `#`（匹配零个或多个单词）和 `*`（恰好匹配一个单词），则是 RabbitMQ 规定的语法。

## 如果改用 policy，key 的名字不一样

policy 里使用的 key **没有 `x-` 前缀**：

| 队列参数（写在代码里） | policy 里的写法 |
|---|---|
| `x-message-ttl` | `message-ttl` |
| `x-max-length` | `max-length` |
| `x-overflow` | `overflow` |
| `x-dead-letter-exchange` | `dead-letter-exchange` |

这也是一个容易混淆的地方：如果在 policy 里写了 `x-message-ttl`，或者在代码里写了 `message-ttl`，都会因为不认识而被静默地忽略掉。

## User

我怎么验证这一套成功了？是运行tomcat后可以在localhost上看到具体的变化吗

## Claude

不是在 Tomcat 的 `localhost:8080` 上看。Tomcat 那边只能看到你的 servlet 是否正常运行，**RabbitMQ 上发生的变化要去 RabbitMQ 的管理界面查看**。验证可以分成四步，逐步确认。

## 第一步：打开 RabbitMQ 的管理界面

在 RabbitMQ 所在的 EC2 上启用管理插件（只需要执行一次）：

```bash
sudo rabbitmq-plugins enable rabbitmq_management
```

然后在浏览器里打开 `http://<RabbitMQ 的公网 IP>:15672`，用你创建的那个用户登录（不能用 guest）。如果打不开，检查一下安全组是否对你的 IP 开放了 15672 端口。

如果你是在自己电脑上用 Docker 运行的 RabbitMQ，地址就是 `http://localhost:15672`。

## 第二步：确认 server-v2 成功连接并完成了声明

启动 Tomcat 之后，先看 Tomcat 的日志（`logs/catalina.out`），确认没有报错。建议在 `contextInitialized` 里，声明完成后打印一行日志，比如 `RabbitMQ topology declared`，这样很容易确认它是否执行成功了。

要特别注意：**如果 `contextInitialized` 里抛出了异常，整个 WAR 都会部署失败**，所有的 endpoint 都会返回 404，连 `/health` 也不例外。所以如果你发现 `/health` 突然访问不了，首先去查这里的日志。

然后在管理界面里逐个页面检查：

| 页面 | 应该看到的内容 |
|---|---|
| **Connections** | 一条来自 server-v2 所在 IP 的连接，说明连接已经成功建立 |
| **Exchanges** | `skitrak.exchange` 和 `skitrak.dlx`，类型都是 topic，Features 一栏有 `D`（表示 durable） |
| **Queues** | `resort.1` 到 `resort.10`，以及 `skitrak.dlq`。业务队列的 Features 一栏应该有 `D`、`TTL`、`Lim`、`DLX` 这几个标记 |
| 点开 `resort.7` 查看 **Bindings** | 一条来自 `skitrak.exchange`、routing key 为 `resort.7` 的绑定 |

如果 Features 一栏缺少某个标记，说明对应的参数 key 写错了，也就是上一轮讲到的“写错了不会报错”的那种情况。

## 第三步：手动发一条消息，测试路由是否正确

在写发布代码之前，可以先用管理界面手动测试路由：

1. 打开 **Exchanges** → 点击 `skitrak.exchange` → 展开 **Publish message**。
2. Routing key 填 `resort.7`，Payload 随便写一段内容，比如 `test`，然后点击 Publish。
3. 回到 **Queues** 页面，`resort.7` 的 **Ready** 一栏应该变成 1，其他队列仍然是 0。

如果点击 Publish 之后，界面提示 **Message published, but not routed**，说明绑定关系没有建立好，消息被直接丢弃了。

## 第四步：测试死信队列

接着上一步继续操作：

1. 打开 `resort.7` 队列的详情页 → 展开 **Get messages**。
2. Ack Mode 选择 **Reject requeue false**，然后点击 Get Message(s)。
3. 这条消息会被拒绝，并且不重新入队。回到 Queues 页面，`resort.7` 变回 0，而 `skitrak.dlq` 变成 1。

这就证明死信机制在正常工作。顺便说一下，Ack Mode 如果选择 **Nack message requeue true**，可以只查看消息的内容，而不把消息从队列里取走。

## 之后：用真实的请求测试

写好发布代码后，用 curl 向 server-v2 发送一条 swipe：

```bash
curl -i -X POST -H "Content-Type: application/json" \
  -d '{"time":217,"liftID":21}' \
  http://<server-v2>:8080/<app>/skiers/7/seasons/2026/days/1/skiers/123
```

你应该能看到返回 201，同时管理界面里 `resort.7` 的消息数加 1。再用 Get messages 查看这条消息的内容，确认它的格式和作业规定的完全一致：包括 eventId、vertical、serverId 等字段。

## 不想用浏览器的话，也可以用命令行

在 RabbitMQ 所在的机器上执行：

```bash
sudo rabbitmqctl list_exchanges name type durable
sudo rabbitmqctl list_queues name messages arguments
sudo rabbitmqctl list_bindings source_name destination_name routing_key
```

这三条命令分别列出所有的 exchange、队列（包括消息数和参数）、绑定关系，和管理界面上看到的是同样的信息。

## User

关于connection给出的channelpool, 用什么底层数据结构？array + roundrobin？

## Claude

Array 加 round-robin **不适合**这个场景。关键原因在于作业里的这条要求：**一个 channel 同一时刻只能被一个线程使用。**

## Round-robin 的问题

Round-robin 是“轮流分配”：第 1 个请求拿 channel 0，第 2 个拿 channel 1……分到最后一个之后再从头开始。它只负责决定“下一个轮到谁”，**并不知道这个 channel 现在是不是正在被别人使用**。

```
池里有 10 个 channel，同时有 50 个请求到达：
  请求 1  → channel 0
  请求 11 → channel 0   ← 请求 1 可能还没用完，两个线程同时使用同一个 channel
```

Tomcat 默认最多有 200 个工作线程，同时到达的请求数量很容易超过池的大小。即使请求数没有超过，只要某个线程用得慢一点，下一轮的线程就会撞上它。多个线程同时在一个 channel 上发布消息，会导致帧交错，channel 被 RabbitMQ 关闭，或者 publisher confirms 的确认对应错乱。

你可以给每个 channel 加一把锁来补救，但这样又会带来新的问题：线程被分配到一个正忙的 channel 时，只能**等待这一个 channel**，即使旁边有其他空闲的 channel 也用不上。

## 正确的做法：BlockingQueue，借出和归还

池里的语义应该是：**空闲的 channel 放在池里；借走的就不在池里了，用完再还回去。** 这样，在池里的 channel 一定是空闲的，任何一个都可以直接使用。

BlockingQueue 正好就是这个语义，这也是老师给的骨架代码里用的结构：

```
借出：take()   → 从队列中取出一个空闲的 channel；如果全部被借走了，就等待
归还：offer()  → 把 channel 放回队列，等待中的线程立刻就能拿到它
```

你对它已经很熟悉了：A1 里的生产者和消费者用的就是同样的结构，只是这一次队列里放的不再是 swipe，而是 channel。

## 代码骨架

```java
public class ChannelPool {
    private final BlockingQueue<Channel> pool;
    private final Connection connection;

    public ChannelPool(Connection connection, int size) throws IOException {
        this.connection = connection;
        this.pool = new ArrayBlockingQueue<>(size);
        for (int i = 0; i < size; i++) {
            pool.offer(connection.createChannel());   // 预先创建好所有 channel
        }
    }

    public Channel borrowChannel(long timeoutMs) throws InterruptedException {
        return pool.poll(timeoutMs, TimeUnit.MILLISECONDS);   // 超时返回 null，不会无限等待
    }

    public void returnChannel(Channel ch) {
        if (ch != null && ch.isOpen()) {
            pool.offer(ch);              // 正常的 channel：放回池里
        } else {
            // 已经关闭的 channel 不能放回池里，需要创建一个新的补上
        }
    }
}
```

使用时：

```java
Channel ch = pool.borrowChannel(1000);
if (ch == null) {
    // 1 秒内没有借到 channel：返回 503，让客户端稍后重试
}
try {
    ch.basicPublish(...);
} finally {
    pool.returnChannel(ch);   // 无论成功与否，一定要归还，否则池会逐渐被耗尽
}
```

## 几个需要注意的细节

**1. 用 `poll(timeout)` 而不是 `take()`。** `take()` 在池被借空时会一直等待下去。如果 RabbitMQ 变慢，所有的 Tomcat 线程都会被卡在这里，连 `/health` 都无法响应，ALB 就会把这台服务器判定为不健康。设置一个超时时间，超时后返回 503，就能把问题控制在一定范围内。

**2. 归还一定要写在 `finally` 里。** 只要有一次发布抛出异常却没有归还 channel，池里就会永久少一个。积累下去，池最终会被耗尽。

**3. 归还时检查 channel 是否还可用。** 如果发布时出错，RabbitMQ 可能已经关闭了这个 channel。把关闭的 channel 放回池里，下一个借到它的线程就会直接失败。所以归还前要检查 `isOpen()`；如果已经关闭，就丢弃它，并创建一个新的补上，让池的大小保持不变。

**4. 池的大小不需要和 Tomcat 的线程数一样多。** 发布一条消息通常只需要很短的时间，所以 channel 被借出的时间很短，几十个 channel 就足以支撑 200 个 Tomcat 线程。具体的数值是后面调优阶段的一个参数（作业的 Tuning Parameters 里也列出了 connection pool size）。可以从 20 左右开始测试。

**5. 和 publisher confirms 的关系。** 如果你决定使用 publisher confirms，可以在**创建 channel 时**调用一次 `confirmSelect()`，而不是每次发布时都调用。因为确认模式是 channel 级别的设置，开启之后就一直有效。

## 其他可选的方案（了解即可）

- **ThreadLocal\<Channel\>**：让每个 Tomcat 工作线程拥有一个自己专属的 channel。这样完全不需要借出和归还，也不存在争用。但 channel 的数量会和线程数一样多（最多 200 个），而且在连接断开、需要重连时，很难统一地重建这些分散在各个线程里的 channel。最重要的是，作业明确要求实现借出和归还的池。
- **Apache Commons Pool2**：一个成熟的通用对象池库，自带空闲检测、失效对象剔除等功能。在实际工作中很常用，但这次作业的目的就是让你自己实现池，所以不建议使用。

## User

我有点不是很理解，为什么我们采用array的方法会导致多线程同时拿到同一个channel，加锁的话有什么缺点？

## Claude

可以用一个具体的时间线来看。关键在于：**round-robin 只负责“轮到谁”，并不知道“谁现在有空”。**

## 为什么会拿到同一个 channel

假设池里有 3 个 channel，用一个计数器轮流分配：

```java
Channel[] channels = new Channel[3];
AtomicInteger next = new AtomicInteger(0);

Channel get() {
    return channels[next.getAndIncrement() % 3];   // 0, 1, 2, 0, 1, 2, ...
}
```

有 4 个请求几乎同时到达：

```
时间 →   0ms         5ms         10ms        15ms
线程 A   拿到 ch0 ─────── 正在发布（用时 20ms）────────┐
线程 B           拿到 ch1 ── 发布完成
线程 C                       拿到 ch2 ── 发布完成
线程 D                                   拿到 ch0  ← A 还在使用 ch0！
```

线程 D 来的时候，计数器正好又转回了 0，于是它拿到了 ch0。但这时线程 A 还没有用完 ch0。数组里的 ch0 从来没有被“拿走”过，它一直待在那里，计数器只是一个“指针”，指向谁，谁就被分配出去。

可以用一个类比来理解：有 3 个洗手间隔间，管理员按 1、2、3、1、2、3 的顺序给排队的人发号。第 4 个人拿到了 1 号，但 1 号隔间里的人还没有出来。管理员只会按顺序发号，并不会去看隔间里有没有人。

这里还有一个很容易混淆的地方：用了 `AtomicInteger`，只能保证**计数器本身**是线程安全的，也就是两个线程不会拿到同一个序号。但它**不能保证 channel 是独占的**。“分配的序号不重复”和“同一时刻只有一个线程在使用这个 channel”是两件完全不同的事。

## 加锁可以解决正确性问题，但有三个缺点

给每个 channel 配一把锁，用之前先加锁，用完再释放：

```java
Channel ch = channels[i];
synchronized (ch) {
    ch.basicPublish(...);
}
```

这样确实不会再有两个线程同时使用同一个 channel 了，**结果是正确的**。但它有以下几个缺点：

**缺点一：会等在忙碌的 channel 上，而旁边的 channel 却空闲着。** 这是最主要的问题。继续用上面的例子：

```
线程 D 被分配到 ch0 → ch0 被 A 锁着 → D 只能等待
此时 ch1 和 ch2 都已经空闲了，但 D 用不上它们
```

round-robin 在分配时完全不考虑 channel 是否忙碌。如果某个 channel 恰好比较慢（比如在等待 RabbitMQ 的确认），被分配到它的线程就只能排队等待，而其他 channel 却闲着。回到洗手间的类比：你被告知只能用 1 号隔间，于是只能在 1 号门口排队，哪怕 2 号和 3 号都空着。

**缺点二：坏掉的 channel 很难安全地替换。** 如果 ch0 因为出错被 RabbitMQ 关闭了，你需要创建一个新的 channel 来替换它。但这时可能有好几个线程正在等待 ch0 的锁，你要在不影响它们的前提下替换数组里的这个元素，同时还要保证它们拿到锁之后用的是新的 channel，这样的逻辑会变得很复杂。

**缺点三：你最终会重新发明一个更差的池。** 为了解决缺点一，你可能会想：“那就不要死等，用 `tryLock` 依次尝试每一个 channel，找到一个空闲的就用它；如果全都忙，就稍等一会儿再试。”这样做确实能解决问题，但它本质上就是在手动实现一个“找空闲 channel、没有就等待”的池，而且效率更低：每次都要逐个扫描，全部都忙时还要不停地轮询。

## BlockingQueue 为什么没有这些问题

```
空闲的 channel 在队列里；借走的就不在队列里了

线程 D 来借 channel：
  队列里有 ch1 和 ch2 → 立刻拿到其中一个，不需要等待
  队列为空（全部被借走了）→ 等待，任何一个 channel 被归还时，立刻被唤醒并拿到它
```

还是用洗手间来类比：门口挂着一排钥匙，钥匙在墙上就说明对应的隔间空着。你随便拿一把就能用，用完再挂回去。钥匙全部被拿走了，就在门口等着，任何一个人出来挂回钥匙，你就能拿到。

总结一下：

| | Round-robin | Round-robin + 锁 | BlockingQueue |
|---|---|---|---|
| 解决的问题 | 下一个轮到谁 | 下一个轮到谁，并保证独占 | **谁现在有空** |
| 会不会两个线程同时使用同一个 channel | 会 | 不会 | 不会 |
| 会不会在有空闲 channel 时还要等待 | — | 会 | 不会 |

我们需要回答的问题是“谁现在有空”，而 BlockingQueue 正是为回答这个问题而设计的数据结构。

## User

就是bq里面存未使用的channel，线程用完push进bq，是吗

## Claude

对，就是这样：

- **BlockingQueue 里只存放当前空闲的 channel。**
- 线程要用时，用 `poll(timeout)` 从队列里**取出**一个。这时它就不在队列里了，其他线程不可能再拿到它。
- 用完之后，用 `offer()` 把它**放回**队列，其他线程就可以继续使用。
- 如果队列是空的，说明所有 channel 都被借走了，来借的线程会等待，直到有人归还。

有一个小细节：在 Java 的 BlockingQueue 里，“放回”用的是 `offer()` 或 `put()`，而不是 `push()`。`push()` 是栈（Deque）的方法。因为我们的队列容量正好等于 channel 的总数，归还时一定有空位，所以用 `offer()` 就行。

再强调两点：

1. **归还要写在 `finally` 里**，保证无论成功还是出错都会还回去。
2. **归还前检查 `isOpen()`**。已经关闭的 channel 不要放回去，而是创建一个新的放进去，这样池的大小就能保持不变。

## User

server把一次swipe event拿到，发给rabbit MQ就不管了是吗

## Claude

从业务流程上说，是的。server 的职责到“把消息交给 RabbitMQ”就结束了，它不会等待 consumer 处理完这条 swipe，也不关心排行榜什么时候更新。这正是引入消息队列的意义：**把“接收请求”和“处理请求”解耦**，server 可以很快地响应客户端，处理工作交给后面的 consumer 慢慢完成。

但“交给 RabbitMQ”到底是什么时候算完成，这里有一个需要你做的决定。

## `basicPublish` 返回了，不代表 RabbitMQ 收到了

默认情况下，`basicPublish` 只是把消息写进了本地的网络缓冲区就立刻返回，**不会等待 RabbitMQ 的任何回应**。如果这时网络断了，或者 RabbitMQ 恰好出了问题，这条消息就丢失了。而 server 已经给客户端返回了 201，客户端以为成功了，也就不会重试。

所以有三种做法可以选择：

**方案一：发出去就不管（fire-and-forget）**

```java
ch.basicPublish(exchange, routingKey, props, body);
// 立即返回 201
```

速度最快，但存在丢消息的风险。

**方案二：每条消息都等待确认（同步 publisher confirms）**

```java
ch.confirmSelect();                       // 创建 channel 时调用一次
ch.basicPublish(exchange, routingKey, props, body);
ch.waitForConfirmsOrDie(5000);            // 等 RabbitMQ 确认已经收到；超时或者被拒绝会抛异常
// 确认之后才返回 201；如果抛了异常，就返回 503
```

这是最可靠的做法：只有 RabbitMQ 明确表示收到了，server 才告诉客户端成功了。如果失败，客户端会收到 503，然后按照 A1 里写好的重试逻辑重新发送。代价是每次发布都要多等一次 server 到 RabbitMQ 的网络往返。在同一个 VPC 内，这大约是 1 毫秒左右。

**方案三：批量确认或异步确认**

先连续发布多条消息，再一次性等待确认；或者注册一个回调函数，异步接收确认结果。吞吐量更高，但实现复杂得多。而且如果在确认之前就返回 201，同样存在丢消息的风险。

## 我的建议

**先用方案二。** 作业的目标里明确写了 “No swipe loss under load”，而方案二是唯一能保证“返回 201 就意味着消息一定已经到达 RabbitMQ”的做法。先把正确性做好，测出它的吞吐量，到了调优阶段，再考虑是否需要换成方案三。

使用方案二时，有一点要注意：**channel 被借出的时间变长了**。原来只需要发出消息的那一瞬间，现在还要加上等待确认的时间。所以池里需要更多的 channel，这个数值需要在调优时测试确定。

## 再补充两个和可靠性相关的设置

- **消息持久化**：发布时使用 `MessageProperties.PERSISTENT_TEXT_PLAIN`，RabbitMQ 会把消息写入磁盘，即使 RabbitMQ 重启，消息也不会丢失。这需要和 durable 队列配合使用。代价是会稍微慢一点。
- **mandatory 标志**：`basicPublish` 有一个参数 `mandatory`。设为 true 后，如果消息无法路由到任何队列（比如 routing key 写错了），RabbitMQ 会把消息退回给你，而不是悄悄丢弃。在调试阶段，这个设置很有帮助。

## 一个可以写进设计文档的思考

严格来说，server 返回 201 的时候，这条 swipe 还**没有被处理**，只是被“接受”了。HTTP 里其实有一个更准确的状态码：**202 Accepted**，意思是“请求已接受，但尚未处理完成”。这正是异步处理架构的标准语义。

不过，你的 A1 客户端是按照 201 来判断成功与否的，作业也没有要求修改状态码，所以继续返回 201 是完全合理的。你可以在设计文档里提一句这个区别，说明你理解了同步处理和异步处理在语义上的不同。

## User

confirmSelect方法是什么意思？你解释一下

## Claude

`confirmSelect()` 的作用是：**把这个 channel 切换到“发布确认”模式**（publisher confirms）。开启之后，你通过这个 channel 发布的每一条消息，RabbitMQ 都会回复一个确认，告诉你“这条消息我收到了，并且已经负责处理了”。

## 用寄信来类比

- **没有开启 confirm**：相当于把信投进邮筒就走了。信有没有被邮局收走、会不会在路上丢失，你都不知道。
- **开启了 confirm**：相当于寄挂号信。邮局收下之后会给你一张回执，拿到回执，你才能确定这封信已经由邮局负责了。

## 开启之后具体发生了什么

```
ch.confirmSelect();          // 告诉 RabbitMQ：这个 channel 上发的消息，都请给我回执

ch.basicPublish(...);        // 第 1 条消息，RabbitMQ 内部给它编号为 1
ch.basicPublish(...);        // 第 2 条消息，编号为 2

RabbitMQ → ack(1)            // 回执：第 1 条已经收到
RabbitMQ → ack(2)            // 回执：第 2 条已经收到
```

- **编号**：开启 confirm 之后，这个 channel 上发布的每条消息都会按顺序从 1 开始编号。编号是 channel 级别的，不同的 channel 各自独立计数。可以用 `ch.getNextPublishSeqNo()` 查看下一条消息的编号是多少。
- **ack**：表示 RabbitMQ 已经接管了这条消息。对于发往 durable 队列的持久化消息，RabbitMQ 会等到消息**写入磁盘之后**才发送 ack。所以拿到 ack，就意味着即使 RabbitMQ 马上重启，这条消息也不会丢失。
- **nack**：表示 RabbitMQ 因为内部错误没能接收这条消息。这种情况很少见，但一旦发生，你就知道需要重试。

## 怎么接收回执

`confirmSelect()` 只是开启了这个模式，接收回执需要另外调用方法，有两种方式：

**同步等待**（上一轮推荐的方案二）：

```java
ch.basicPublish(...);
ch.waitForConfirmsOrDie(5000);   // 阻塞，等待回执
// 收到 ack → 方法正常返回
// 收到 nack 或者 5 秒内没有收到回执 → 抛出异常
```

还有一个类似的方法 `waitForConfirms()`，它返回 boolean 而不是抛异常。用 `OrDie` 版本更好，因为失败时会直接抛出异常，你不会忘记处理。

需要注意的是，`waitForConfirms` 等待的是**这个 channel 上所有尚未确认的消息**，而不只是最后一条。不过在你的设计里，一个 channel 每次只被一个线程借出，发布一条消息就等待确认，所以等待的正好就是刚刚发出的这一条。

**异步回调**（方案三）：

```java
ch.addConfirmListener(
    (seqNo, multiple) -> { /* 收到 ack */ },
    (seqNo, multiple) -> { /* 收到 nack */ }
);
```

发布之后不需要等待，回执到达时会自动调用回调函数。`multiple` 为 true 时，表示“编号小于等于 seqNo 的所有消息都已确认”，RabbitMQ 有时会这样批量确认来提高效率。

## 几个需要知道的规则

1. **每个 channel 只需要调用一次。** 一旦开启，这个 channel 会一直保持在确认模式，而且**无法关闭**。所以最合适的时机是在 ChannelPool 创建 channel 的时候就调用它，而不是每次发布时都调用。
2. **不能和事务模式同时使用。** RabbitMQ 还有一种更老的可靠性机制叫事务（`txSelect`），它比 confirm 慢得多。同一个 channel 只能选择其中一种。
3. **为什么默认不开启？** 因为 RabbitMQ 需要为每条消息记录状态并发送回执，这会带来额外的开销。对于丢失几条也无所谓的场景（比如日志、监控数据），就不需要开启。而你的 swipe 要求“不丢失”，所以需要开启。

## User

老师的意思是不是每个lift的vertical放在lift_vertical.json里，程序自动加载进内存？

## Claude

对，这是最合理的理解：**在程序启动时把这个文件读进内存一次，之后每次请求直接在内存里查表**，而不是每处理一个 swipe 都去读一次文件。不过我看不到 starter repo 里这个文件的具体内容，下面的代码是按一种常见格式写的，你需要根据实际的格式调整解析部分。

## 为什么要提前加载

- **速度**：读文件是磁盘 I/O，每个请求都读一次，会严重拖慢吞吐。而内存查表只需要几纳秒。
- **数据不会变**：40 个 lift 的 vertical 是固定的，运行期间不会改变，所以加载一次就够了。
- **线程安全**：这张表加载完成后**只读不写**，多个 Tomcat 线程同时读取也不会有任何问题，不需要加锁，也不需要 ConcurrentHashMap。

## 用什么数据结构存

liftID 的范围是 1 到 40，是连续的整数，所以**用数组最合适**：

```java
int[] verticals = new int[41];   // 下标 0 不用，verticals[21] 就是 lift 21 的 vertical
```

查询就是 `verticals[liftID]`，比 HashMap 更快，也更节省内存。

## 文件放在哪里，怎么读

把文件放在 `src/main/resources/` 目录下。打包时，Maven 会把它放进 WAR 里的 `WEB-INF/classes/` 目录。读取时要通过 **classpath** 来读，而不是通过文件路径：

```java
// 正确：从 classpath 读取
InputStream in = getClass().getClassLoader().getResourceAsStream("lift_vertical.json");

// 错误：按文件路径读取
new File("lift_vertical.json");
```

文件路径的方式在 IDE 里运行时可能碰巧能用，但部署到 Tomcat 之后就会找不到文件。因为这时程序的工作目录是 Tomcat 的 `bin` 目录，而不是你的项目目录。

## 加载代码

假设文件的格式是 `{"1": 120, "2": 85, ...}`，用 Gson 解析：

```java
public static int[] loadVerticals() {
    InputStream in = LiftVerticals.class.getClassLoader().getResourceAsStream("lift_vertical.json");
    if (in == null) throw new IllegalStateException("lift_vertical.json not found on classpath");

    Map<String, Integer> raw = new Gson().fromJson(
            new InputStreamReader(in, StandardCharsets.UTF_8),
            new TypeToken<Map<String, Integer>>() {}.getType());

    int[] verticals = new int[41];
    for (int lift = 1; lift <= 40; lift++) {
        Integer v = raw.get(String.valueOf(lift));
        if (v == null) throw new IllegalStateException("missing vertical for lift " + lift);
        verticals[lift] = v;
    }
    return verticals;
}
```

如果文件是其他格式，比如 `[{"liftID": 1, "vertical": 120}, ...]` 这样的数组，只需要修改中间解析的部分，后面填充数组的逻辑不变。

## 在哪里调用

和 RabbitMQ 的连接一样，在应用启动时加载一次：

- 放在 `ServletContextListener` 的 `contextInitialized` 里，加载完成后存进 ServletContext；或者
- 放在 SkierServlet 的 `init()` 方法里，存成一个 `final` 成员变量。

两种方式都可以。代码里的校验是有意为之的：**如果文件缺失或者数据不完整，在启动时就直接失败**，而不是等到某个请求查不到 vertical 时才出错。这和我们在 SwipeProducer 的构造函数里做参数校验是同样的道理，叫做“fail fast”。

如果你的文件格式和上面的例子不一样，把文件的前几行贴给我，我帮你调整解析代码。

## User

我有点没理解，老师的意思是每个resort的lift都遵从那份数据吗？就是每个resort现在都用一样的lift？

## Claude

根据作业的描述，是的。原文是：*“each of the 40 lifts has a fixed vertical drop in the 60–400 ft range”*，意思是 **40 个 lift，每个都有一个固定的 vertical**。这里只提到了 liftID，没有提到 resort，所以这张表应该是按 liftID 查询、所有 resort 共用的。也就是说，resort 1 的 lift 21 和 resort 7 的 lift 21，vertical 是一样的。

这当然和现实不符，现实中每个滑雪场的缆车都不一样。但这是作业为了模拟而做的简化，和 A1 的数据生成方式也是一致的：A1 里的 liftID 就是在 1 到 40 之间随机生成的，和 resortID 没有任何关联。这个作业的重点在于消息队列、聚合和推送的架构，而不是模拟出真实的滑雪场数据，所以这样简化是合理的。

不过，最终还是以**文件本身的结构**为准，你打开看一眼就能确定：

- 如果文件里只有 40 条记录，每条只有 liftID 和 vertical，那就是所有 resort 共用一张表，按 `verticals[liftID]` 查询即可。
- 如果文件是按 resort 分组的，比如 `{"1": {"1": 120, "2": 85, ...}, "2": {...}}`，或者每条记录里都带有 resortID，那就是每个 resort 有自己的一张表，需要按 (resortID, liftID) 这两个值来查询，数据结构也要改成二维数组 `int[11][41]`。

如果看了文件还是不确定，可以在课程论坛上问一下老师或者助教。对于你的设计来说，这两种情况的区别只在于查表时用一个 key 还是两个 key，其他部分完全不受影响。

## User

java里怎么生成UUID？

## Claude

用 `java.util.UUID` 类就可以，一行代码：

```java
String eventId = UUID.randomUUID().toString();
// 例如：3f2b8c1e-9a4d-4e7b-b1c2-5d6e7f8a9b0c
```

- `randomUUID()` 生成的是第 4 版 UUID，它的 128 位里有 122 位是随机数，两次生成出相同值的概率可以忽略不计。
- `toString()` 会把它转换成标准的 36 个字符的格式，即 8-4-4-4-12 位的十六进制数，中间用连字符隔开。
- 它是**线程安全**的，多个 Tomcat 线程可以同时调用，不需要任何额外的处理。

它在内部使用 `SecureRandom` 来生成随机数。在每秒几千次的调用频率下，性能完全没有问题。

## 一个值得想清楚的问题：eventId 在哪里生成

按照作业的消息格式，eventId 应该是 **server 收到 swipe 时生成**的。这样做，去重能防止的是 **RabbitMQ 重复投递**的情况：consumer 处理完消息但还没来得及 ack 就崩溃了，RabbitMQ 会把这条消息重新投递一次，这时 eventId 是同一个，去重就能识别出来。

但有一种情况它防不住：**客户端重试**。

```
客户端发送 swipe → server 生成 eventId = A，发布到 RabbitMQ 成功
                → 返回 201 的途中，网络超时了
客户端没有收到响应，于是重试 → server 又生成了一个新的 eventId = B，再发布一次
结果：同一个 swipe 进入了队列两次，eventId 不同，去重无法识别 → vertical 被重复计算
```

要防止这种情况，ID 必须在**客户端**生成，并且每次重试都携带同一个 ID。这就是业界常说的**幂等键**（idempotency key）：客户端在请求头里带上一个 ID，server 直接用它作为 eventId，而不是自己生成一个新的。

对于这次作业：

- 作业的消息格式和评分标准针对的是 RabbitMQ 重复投递的情况，所以在 server 端生成 eventId 是符合要求的。
- 客户端重试导致的重复，在实际测试中只有在网络超时时才会出现，次数非常少。
- 但这是一个很好的设计讨论点。你可以在设计文档里说明“去重的范围只覆盖了 broker 的重复投递”，再提出用客户端幂等键作为改进方案。这也是 AI 使用反思里“需要你自己推理决定的地方”的好素材。

## User

201和503在response里分别怎么设置

## Claude

在 servlet 里，状态码是通过 `HttpServletResponse` 的 `setStatus()` 方法设置的：

```java
response.setStatus(HttpServletResponse.SC_CREATED);               // 201
response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);   // 503
```

`SC_CREATED` 和 `SC_SERVICE_UNAVAILABLE` 是 `HttpServletResponse` 里预先定义好的常量，值分别是 201 和 503。你也可以直接写 `setStatus(201)`，但用常量的可读性更好。

## 放在 doPost 里的完整流程

```java
// 校验失败 → 400（你 A1 里已经写好的逻辑）

Channel ch = null;
try {
    ch = channelPool.borrowChannel(1000);
    if (ch == null) {                                  // 1 秒内没有借到 channel
        sendJson(response, 503, "{\"message\":\"server busy, retry later\"}");
        return;
    }
    ch.basicPublish("skitrak.exchange", "resort." + resortID,
            MessageProperties.PERSISTENT_TEXT_PLAIN, body);
    ch.waitForConfirmsOrDie(5000);                     // 等待 RabbitMQ 确认

    sendJson(response, 201, "{\"message\":\"swipe accepted\"}");

} catch (Exception e) {                                // 发布失败或者确认超时
    sendJson(response, 503, "{\"message\":\"queue unavailable, retry later\"}");
} finally {
    channelPool.returnChannel(ch);
}
```

```java
private void sendJson(HttpServletResponse res, int status, String json) throws IOException {
    res.setStatus(status);
    res.setContentType("application/json");
    res.getWriter().write(json);
}
```

这里的 `catch (Exception e)` 只是为了让示例保持简洁，实际代码里最好分别捕获具体的异常类型，并记录日志。`InterruptedException` 也应该单独处理，记得恢复中断标志。

## 几个细节

**1. 要先设置状态码，再写入 body。** 一旦开始向 `getWriter()` 写入内容，而且内容足够多、触发了缓冲区刷新，响应就会被“提交”（committed）。状态行和响应头会先被发送出去，之后再调用 `setStatus()` 就不会再生效了。所以顺序始终应该是：状态码 → 响应头 → body。

**2. 503 可以加一个 `Retry-After` 响应头**，告诉客户端多久之后再重试：

```java
response.setHeader("Retry-After", "1");   // 单位是秒
```

这是 HTTP 标准里配合 503 使用的响应头。你的 A1 客户端遇到 5xx 时本来就会用指数退避重试，所以这个头不是必需的，但加上它会让 API 更加规范。

**3. 不要用 `sendError()`。** 你可能会在网上看到 `response.sendError(503, "...")` 的写法。它也能设置状态码，但 Tomcat 会把响应体替换成一个默认的 **HTML 错误页面**。你的 API 返回的是 JSON，所以用 `setStatus()` 加上自己写入 body 的方式更合适。

**4. 为什么是 503，而不是 500？** 500 Internal Server Error 表示“服务器内部出了 bug”；503 Service Unavailable 表示“服务器暂时无法处理，稍后再试可能会成功”。这里的情况是 RabbitMQ 暂时不可用，或者 channel 暂时都被占用了，属于暂时性的问题，所以 503 更准确。这个区分对客户端也有实际意义：你的 A1 客户端会对 5xx 进行重试，而这里返回的 503 正好会触发重试，等 RabbitMQ 恢复之后，这条 swipe 就能成功发布，不会丢失。

## User

你能帮我理一下每条swipe由服务器发给rabbit MQ的流程，需要做什么吗？

## Claude

可以分成两部分来看：**启动时只做一次的准备工作**，和**每条 swipe 都要走一遍的流程**。

## 一、启动时（ServletContextListener，只执行一次）

```
① 加载 lift_vertical.json → int[] verticals
② 生成本机的 serverId
③ 创建 RabbitMQ Connection
④ 声明拓扑：exchange、10 个队列、绑定关系、死信 exchange 和死信队列
⑤ 创建 ChannelPool：预先创建 N 个 channel，每个 channel 都调用一次 confirmSelect()
⑥ 把 verticals、serverId、channelPool 存进 ServletContext，供 SkierServlet 使用
```

关于 **serverId**：它用来标识这条消息是由哪台服务器发布的。可以使用主机名（`InetAddress.getLocalHost().getHostName()`），或者通过环境变量传入一个名字，比如 `server-1`。它在 Part 3 会很有用：统计不同 serverId 的消息数量，就能直接验证 ALB 是否把请求均匀地分配到了各台服务器上。

## 二、每条 swipe 的处理流程（doPost）

```
请求到达
  │
  ├─ 1. 解析并校验 URL 路径 ──────────── 失败 → 400
  ├─ 2. 解析并校验 JSON body ──────────── 失败 → 400
  │
  ├─ 3. 查表：vertical = verticals[liftID]
  ├─ 4. 组装消息
  ├─ 5. 序列化成 JSON 字节数组
  │
  ├─ 6. 借 channel（带超时）──────────── 超时 → 503
  ├─ 7. 发布到 skitrak.exchange，routing key 为 resort.{resortID}
  ├─ 8. 等待 RabbitMQ 确认 ──────────── 失败或超时 → 503
  ├─ 9. 返回 201
  │
  └─ finally：归还 channel（如果已经关闭，就换一个新的）
```

### 第 1、2 步：校验

这部分和 A1 完全一样，直接沿用你已经写好的代码。**校验必须放在最前面**，不合法的请求直接返回 400，根本不会去借 channel，也不会占用任何 RabbitMQ 的资源。

### 第 3、4 步：查表和组装消息

各个字段的来源如下：

| 字段 | 来源 |
|---|---|
| `eventId` | `UUID.randomUUID().toString()`，每条 swipe 生成一个新的 |
| `resortID`、`dayID`、`skierID` | 从 URL 路径中解析出来 |
| `liftID`、`time` | 从请求 body 中解析出来 |
| `vertical` | `verticals[liftID]`，查表得到 |
| `timestamp` | `Instant.now().toString()`，server 收到请求的时间 |
| `serverId` | 启动时生成好的，所有请求都使用同一个值 |

**注意作业规定的字段类型。** `resortID`、`dayID`、`skierID`、`liftID`、`serverId` 都是**字符串**（JSON 里带引号），只有 `vertical` 和 `time` 是**数字**。如果你定义一个消息类，再用 Gson 来序列化，Java 字段的类型要和这里保持一致，否则 consumer 解析时会出问题。

seasonID 不在消息格式里。如果需要的话，可以和老师确认一下，或者在设计文档里说明一下。

### 第 5 步：序列化

```java
byte[] body = gson.toJson(message).getBytes(StandardCharsets.UTF_8);
```

`Gson` 对象是线程安全的，在 servlet 里创建一个，作为成员变量供所有请求共用即可，不需要每次请求都 new 一个。

### 第 6～9 步：发布

这就是上一轮写的那段代码：借 channel → `basicPublish`（使用持久化消息）→ `waitForConfirmsOrDie` → 返回 201。任何一个环节失败都返回 503，让客户端去重试。

### finally：归还 channel

无论成功还是失败，都要归还 channel。如果 channel 已经关闭了（发布出错时可能会发生这种情况），就丢弃它，并创建一个新的放回池里，让池的大小保持不变。

## 三、关闭时（contextDestroyed）

```
关闭 ChannelPool 里的所有 channel → 关闭 Connection
```

## 还没有覆盖到的部分：连接断开

上面的流程处理了单条消息发布失败的情况，但如果 RabbitMQ 的**整条连接**都断开了（比如 RabbitMQ 重启了），池里所有的 channel 都会失效。作业要求必须处理这种情况。

`amqp-client` 5.x 默认开启了**自动恢复**（automatic recovery）功能：连接断开后，它会自动重新连接，并重建原来的 channel。但这个功能和你自己的 ChannelPool 之间存在一些需要注意的交互问题：比如在恢复期间，从池里借出的 channel 是否可用，归还时 `isOpen()` 的检查结果会怎样。这部分比较复杂，建议在发布流程跑通之后，再单独讨论。

## User

难道rabbitMQ这种成熟工具没有断线重连机制吗？

## Claude

有。RabbitMQ 的 Java 客户端从 4.0 版本开始，**默认开启了自动恢复**（automatic recovery）功能。你之前问的那段需要“单独讨论”的内容，主要就是围绕它展开的。

## 自动恢复能做什么

连接断开后，客户端会自动完成三件事：

1. **恢复连接**：每隔一段时间尝试重新连接，默认间隔是 5 秒。
2. **恢复 channel**：原来的 Channel 对象不会被丢弃，连接恢复之后，**同一个对象**会重新变成可用状态。这是因为 `createChannel()` 返回的实际上是一个包装过的 `AutorecoveringChannel`。
3. **恢复拓扑**：自动重新声明这个连接上声明过的 exchange、队列、绑定关系，以及 consumer。

相关的配置：

```java
factory.setAutomaticRecoveryEnabled(true);    // 默认就是 true
factory.setNetworkRecoveryInterval(2000);     // 重连的间隔，单位是毫秒
factory.setTopologyRecoveryEnabled(true);     // 是否恢复拓扑，默认 true
```

## 它不能解决的问题

自动恢复负责的是“连接断开之后把它重新连上”，但有几件事它管不了，仍然需要你自己处理：

**1. 恢复期间的请求。** 从连接断开到重新连上，中间有几秒钟的空档。这期间调用 `basicPublish` 会抛出 `AlreadyClosedException`。你需要捕获这个异常并返回 503，让客户端稍后重试。

**2. 启动时第一次连接就失败。** 自动恢复只在**已经成功连接过**之后才会起作用。如果 Tomcat 启动时 RabbitMQ 恰好不可用，`newConnection()` 会直接抛出异常，自动恢复根本不会介入。所以启动时的连接需要你自己写重试逻辑。

**3. 断开那一刻还没收到确认的消息。** 如果一条消息已经发出，但在收到 ack 之前连接断开了，`waitForConfirmsOrDie` 会抛出异常。这时你无法知道 RabbitMQ 到底有没有收到这条消息，只能返回 503，让客户端重试。如果 RabbitMQ 其实已经收到了，就会产生一条重复的消息。这正好就是 consumer 端需要按 eventId 去重的原因之一，两个机制在这里配合起来了。

**4. 单个 channel 因为错误被关闭。** 如果某个 channel 是因为协议错误被 RabbitMQ 关闭的（比如前面提到的 406 `PRECONDITION_FAILED`），而连接本身仍然正常，那么自动恢复**不会**恢复这个 channel，因为它只在连接故障时才会启动。这种情况下，ChannelPool 仍然需要自己创建一个新的 channel 来替换它。

## 需要纠正我前面的一个建议

在讲 ChannelPool 时，我建议你在归还 channel 时检查 `isOpen()`，如果已经关闭就丢弃它，并创建一个新的。**在开启了自动恢复的情况下，这个做法会出问题**：

```
RabbitMQ 断开 → 所有 channel 的 isOpen() 都返回 false
→ 每个归还的 channel 都被当作坏掉的 channel 丢弃
→ 尝试创建新的 channel → 但连接此时也断着，创建失败
→ 池被逐渐清空
```

而实际上，这些被丢弃的 channel，本来在几秒钟之后就会被自动恢复。

更准确的判断方法是：**区分是“连接断开了”，还是“只是这个 channel 坏了”。**

```java
if (!ch.isOpen() && connection.isOpen()) {
    // 连接正常，只是这个 channel 被关闭了 → 丢弃它，创建一个新的
} else {
    // 连接断开了（正在恢复中），或者 channel 本身正常 → 放回池里，等待自动恢复
}
```

这是一个你可以写进 AI 使用反思的真实例子：AI 先给出了一个在简单情况下正确、但和库的另一项功能相冲突的建议，后来在讨论中被发现并修正了。

## 关键问题：作业希望你自己实现重连吗？

再看一下作业里的这句话：

> *“with RabbitMQ you implement connection and channel pooling and reconnection yourself, which is the whole point of this assignment”*

这句话明确地说，**自己实现重连**是这次作业的重点之一。所以完全依赖库的自动恢复，可能不完全符合作业的意图。你有两种选择：

**方案 A：使用库的自动恢复，自己处理它覆盖不到的部分。** 也就是上面列出的四点：恢复期间返回 503、启动时的重试、未确认消息的处理、坏 channel 的替换。这种方案代码量少，而且是实际工作中最常见的做法。但你需要在设计文档里清楚地说明哪些是库负责的、哪些是你自己实现的。

**方案 B：关闭自动恢复，完全自己实现。** 设置 `setAutomaticRecoveryEnabled(false)`，在 Connection 上注册一个 `ShutdownListener`，在连接断开时收到通知，然后用指数退避的方式重新建立连接，并重建整个 ChannelPool。这种方案的代码量更多，但完全符合作业的字面要求，而且你可以清楚地解释其中每一步的设计。

我的建议是：**先在课程论坛上问一下老师，使用库自带的自动恢复是否可以接受。** 如果得不到明确的答复，选择方案 B 更稳妥，因为作业的原文已经写得很明确了。而且你在 A1 里已经实现过 WebSocket 的断线重连，指数退避和状态管理的思路是完全一样的。

无论选择哪种方案，最后都要**实际测试一次**：在客户端运行的过程中，SSH 到 RabbitMQ 所在的机器上执行 `sudo systemctl restart rabbitmq-server`，然后观察：短时间内出现少量的 503，客户端重试之后成功，连接恢复后吞吐量也恢复正常，并且最终的 swipe 总数没有丢失。

## User

我们先把框架搭出来，再去处理指数退避重连问题，我说说我对消费者的理解，消费者需要从broker中取出所有swipe，然后解析成结构化对象，检查UUID保证idempotent，然后对不同滑雪者进行当天vertical累加，并更新维护排名表，把更新的排名表推送给broker，再有broker交给websocket，我的问题：1. 消费者的线程架构怎么思考？是否需要多线程？设置多少线程合适？2. 不同滑雪者的信息在这一个阶段用什么数据结构存储？数据库是assignment3的工作吧

## Claude

你的理解整体是对的。先纠正两个小地方，再回答你的两个问题。

## 两处小的修正

**1. 不是“取出所有 swipe”，而是订阅队列，由 broker 持续推送。** consumer 调用 `basicConsume` 订阅某个队列之后，RabbitMQ 会主动把消息推过来，consumer 每收到一条就处理一条。推送的速度由 **prefetch** 参数控制：它表示 consumer 最多可以同时持有多少条还没有 ack 的消息。

**2. ack 要放在最后。** 正确的顺序是：解析 → 去重检查 → 累加 vertical → 更新排名 →（如果排名有变化）发布到 backplane → **ack**。只有在处理全部完成之后才 ack，这就是作业要求的“至少一次投递”：如果 consumer 在中途崩溃了，这条消息还没有被 ack，RabbitMQ 就会把它重新投递给别的 consumer。

## 问题 1：消费者的线程架构

### 先理解 RabbitMQ 客户端是怎么分配线程的

在 RabbitMQ 的 Java 客户端里，你**不需要自己创建线程**去循环拉取消息。你做的事情是：在某个 channel 上调用 `basicConsume`，注册一个回调函数。之后每来一条消息，客户端就会在它内部的线程池里调用你的回调。

其中有一条很关键的规则：**同一个 channel 上的消息，回调是按顺序、一条接一条执行的**，不会并发执行。所以，“一个消费者线程”在这里实际上就对应**一个 channel**。

### 线程数受到顺序要求的限制

作业要求同一个 resort 的消息必须按顺序处理。最直接的实现方式就是：**每个 resort 队列只由一个 channel 来消费**。

```
channel 1 ── 消费 resort.1
channel 2 ── 消费 resort.2
……
channel 10 ── 消费 resort.10
```

这样，10 个 resort 可以被 10 个线程并行处理，而每个 resort 内部依然是严格按顺序的。

如果你把 channel 的数量配置为 K（K 小于 10），就把 resort 平均分给它们：resort i 分配给第 `i % K` 个 channel。这就是作业里说的“每个 consumer 负责一部分 resort，并且分配均匀”。

问题在于，**K 超过 10 之后，多出来的线程就没有事情可做了**，因为只有 10 个队列。这就是我们之前提到的那个矛盾：作业要求测试 10、20、40、80 个 consumer 线程。这个问题可以留到调优阶段再解决，但先给你几个思路：

- **直接说明**：在严格保证 resort 内顺序的前提下，超过 10 个线程并不能增加并行度，然后用测试数据来证明这一点。这本身就是一个有价值的分析。
- **更细粒度地分区**：例如按 skierID 的哈希值把每个 resort 再拆成几个子队列。这样，同一个 skier 的消息依然是有序的，而累加 vertical 实际上只需要保证同一个 skier 内部有序。但代价是，同一个 resort 的排行榜会被多个线程同时更新，需要额外的同步。
- **拆分处理阶段**：消费线程只负责接收和去重，计算交给另一组线程。这样会复杂很多。

### 实际上需要多少线程

聚合的计算量非常小：更新一次 HashMap、检查一下 top-N，每条消息只需要几微秒。所以**单个线程每秒就能处理几万条消息**。consumer 真正的瓶颈通常不在计算上，而在于：

- **prefetch 设置得太小**：如果 prefetch=1，consumer 每处理完一条消息，都要等 RabbitMQ 送来下一条，中间要多等一个网络往返的时间，吞吐量会非常低。一般设置为 100 到 500。
- **ack 太频繁**：每条消息都单独 ack 一次，就会多一次网络通信。可以使用批量 ack：`basicAck(tag, true)` 中第二个参数 `multiple=true`，表示一次确认这个编号及之前的所有消息。

所以，**一开始用 10 个 channel，每个 resort 一个**，把 K 和 prefetch 做成可配置的参数就足够了，等调优时再根据测试结果调整。

## 问题 2：数据结构

对，这次作业全部**保存在内存里**，持久化到数据库是 Assignment 3 的工作。

### skier 的累计 vertical

```java
ConcurrentHashMap<String, Long> skierVertical;   // key = "resortID:dayID:skierID"
skierVertical.merge(key, vertical, Long::sum);   // 原子地累加，返回累加后的新值
```

`merge` 是一个原子操作：如果 key 不存在，就放入 vertical；如果已经存在，就把旧值和 vertical 相加。它会返回累加之后的结果，你下一步更新排名时正好需要这个值。

这里可以进一步思考一个问题：如果每个 resort 只由**一个线程**来处理，那么这个 resort 的所有 skier 数据实际上只会被这一个线程访问。在这种情况下，你可以为每个 resort 使用一个普通的 HashMap，这就是 A1 里讲过的“线程封闭”。作业给出的是 ConcurrentHashMap，用它也完全没有问题，只是要理解它为什么是安全的，以及在什么情况下其实并不需要它。

### 每个 resort 的 top-N

这里有一个非常有用的性质：**vertical 只会增加，不会减少。** 因此：

- 一个**已经在** top-N 里的 skier，只可能往上升，不可能被挤出去。
- 一个**不在** top-N 里的 skier，只有当他的新总数**超过当前 top-N 中的最小值**时，才可能进入 top-N。

所以，你根本不需要对所有 skier 进行排序，只需要维护一个大小为 N 的小列表：

```
收到 skier X 的新总数 total：
  X 已经在列表里   → 更新他的值，重新排序 → 排名可能变化了
  X 不在，并且列表还没满 → 直接加入，排序
  X 不在，且 total > 列表中的最小值 → 替换掉最小值的那个 skier，排序
  否则             → 排名没有变化，什么都不做
```

N 等于 10 时，在 10 个元素里查找和排序的开销可以忽略不计。用一个简单的 ArrayList 就足够了，不需要 TreeMap 或者 PriorityQueue。其中 PriorityQueue 尤其不合适，因为它不能高效地更新一个**已经在队列里的**元素的值。

### 去重集合

```java
Set<String> seenEvents = ConcurrentHashMap.newKeySet();
```

需要注意内存。100 万个 UUID 字符串，每个连同哈希表的开销大约占一百多字节，加起来是 100 到 150 MB。而 t2.micro 只有 1 GB 内存，这是一个实实在在的风险。

一个常见的折中方案是**只记住最近的一批 eventId**。因为 RabbitMQ 的重复投递通常发生在短时间内（比如 consumer 刚刚崩溃，RabbitMQ 就重新投递了），很早以前的消息基本不会再被重新投递。可以用 `LinkedHashMap` 配合 `removeEldestEntry` 实现一个固定大小的 LRU 集合，比如每个 resort 保留最近的 10 万个。代价是，如果一条消息在很久之后才被重新投递，就可能无法被识别出来。这个取舍可以写进设计文档。

**还要注意去重的时机**：先检查是否已经见过 → 如果见过，就直接 ack 并跳过；如果没见过，就先完成聚合，**再**把 eventId 记为已处理，最后 ack。如果先记录、后聚合，一旦聚合过程中出错，这条消息就会被永远当作“已处理过”，导致数据丢失。

### 计数器

```java
LongAdder swipesProcessed;
```

用来统计处理的总数，供监控和计算 consumer 的处理速度使用。

### 一个需要注意的地方：resortSubscribers 不在 consumer 里

作业列出的数据结构里包括 `resortSubscribers`（每个 resort 的 WebSocket session 集合）。但在 backplane 方案下，**consumer 是不持有任何 WebSocket 连接的**，它只负责把排行榜发布到 backplane。真正持有 session 的是 server-v2，所以这个结构应该放在 server-v2 的 LeaderboardEndpoint 里，你在 A1 里已经写过这个 Map 了。

作业之所以把四个结构列在一起，是因为它们是**整个系统**需要的状态，并没有规定一定要放在 consumer 里。这也正好体现了你选择 backplane 方案带来的结果：状态被分成了两部分，计算相关的状态在 consumer，连接相关的状态在 server。这一点可以写进设计文档里。

## User

我们的consumer如果无法解析message，怎么通知broker错误？broker需要尝试几次才进入deadletterqueue？

## Claude

## 1. 怎么通知 broker：nack，并且不重新入队

在 consumer 的回调里，通过 `basicNack` 或 `basicReject` 告诉 RabbitMQ 这条消息处理失败了：

```java
channel.basicNack(deliveryTag, false, false);
//                             ↑      ↑
//                    multiple=false  requeue=false
```

- **deliveryTag**：这条消息在这个 channel 上的编号，从回调参数 `envelope.getDeliveryTag()` 中获取。
- **multiple=false**：只拒绝这一条消息。
- **requeue=false**：不要把它放回队列。因为业务队列配置了 `x-dead-letter-exchange`，所以这条消息会被转到 `skitrak.dlx`，最终进入 `skitrak.dlq`。

如果 `requeue=true`，消息会被放回原来的队列，之后再被重新投递。

`basicReject(deliveryTag, false)` 的作用完全一样，只是不支持 multiple 参数，一次只能拒绝一条。

## 2. broker 会尝试几次？答案是：由你决定

这一点很重要：**对于普通的 classic 队列，RabbitMQ 不会统计重试次数，也不会在达到某个次数后自动把消息转为死信。**

- 你 `requeue=true` 拒绝一次，它就重新投递一次。如果你一直这样拒绝，它就会**无限次地**重新投递下去。
- 只有当你用 `requeue=false` 拒绝时，消息才会进入死信队列。

所以，“尝试几次之后进入死信队列”这个逻辑，需要**你自己在 consumer 里实现**。RabbitMQ 只会在消息被重新投递时设置一个 `redelivered` 标志（`envelope.isRedeliver()`），告诉你“这条消息之前投递过”，但不会告诉你具体是第几次。

如果你希望由 RabbitMQ 来统计次数，可以使用 **quorum 队列**，并设置 `x-delivery-limit` 参数，比如 3。超过这个次数之后，RabbitMQ 会自动把消息转为死信。RabbitMQ 4.0 及以上版本的 quorum 队列，默认就有一个投递次数的上限。但 quorum 队列需要重新声明队列（设置 `x-queue-type: quorum`），而且它的性能特征和 classic 队列也不一样。对这次作业来说，使用 classic 队列，在 consumer 里自己处理就足够了。

## 3. 关键是区分两类错误

这和 A1 里区分 4xx 和 5xx 是同一个道理：

| 错误类型 | 例子 | 重试有没有用 | 处理方式 |
|---|---|---|---|
| **永久性错误** | JSON 格式错误、缺少字段、字段类型不对 | 没用，重试一万次结果也一样 | **直接** nack，`requeue=false`，进入死信队列 |
| **暂时性错误** | 发布到 backplane 时网络抖动 | 有用，稍后可能就成功了 | 在本地重试几次 |

**无法解析的消息属于永久性错误。** 这条消息本身就是坏的，再投递多少次也不可能解析成功。如果用 `requeue=true`，它就会变成我们之前讲过的“毒消息”，在队列里无限循环，还会阻塞这个 resort 后面所有的消息。所以应该**立刻**把它转入死信队列，然后继续处理后面的消息。

```java
SwipeEvent event;
try {
    event = gson.fromJson(new String(body, StandardCharsets.UTF_8), SwipeEvent.class);
    validate(event);   // 检查必需的字段是否存在、值是否在合法范围内
} catch (Exception e) {
    log.warn("Unparseable message, dead-lettering: " + e.getMessage());
    channel.basicNack(deliveryTag, false, false);   // 直接进入死信队列
    return;
}
// 解析成功，继续处理：去重、聚合……
```

另外要注意，Gson 解析时如果遇到缺失的字段，**不会报错**，只会把对应的字段设为 null 或者 0。所以解析之后，还需要自己检查一遍关键的字段，比如 eventId 不能为 null，vertical 必须大于 0，否则一条格式残缺的消息会被当作正常消息参与聚合。

## 4. 一个值得思考的问题：发布排行榜失败了，要不要让这条 swipe 重新投递？

假设一条 swipe 已经成功完成了聚合，但发布到 backplane 时失败了。这时应该 nack 让它重新投递吗？

**不应该。** 原因在于：

- 聚合已经完成了，这条 swipe 的 vertical 已经累加进去了。
- 排行榜是**从聚合结果推导出来的状态**。下一条 swipe 触发排行榜更新时，发布的会是包含了这条 swipe 在内的最新排行榜。
- 如果让这条 swipe 重新投递，去重机制会识别出它已经处理过了，于是什么也不做。这样一来，重新投递完全没有意义。

所以正确的做法是：在本地重试几次发布；如果依然失败，就记录日志，**照常 ack 这条 swipe**。排行榜会在下一次更新时自然恢复正确。

这种“需要自己推理才能得出结论”的地方，是 AI 使用反思里很好的素材。

## 5. 一个必须注意的陷阱：回调里不能抛出异常

如果你的回调函数 `handleDelivery` 里抛出了一个没有被捕获的异常，RabbitMQ Java 客户端默认的异常处理器会**关闭这个 channel**。后果是：

- 这个 channel 上所有尚未 ack 的消息，会被 RabbitMQ 放回队列。
- 这个 channel 上注册的 consumer 也随之消失，**这个 resort 的队列从此没有人消费了**。
- 队列会不断积压，而你可能很长时间都察觉不到。

所以，回调函数的最外层一定要用 try-catch 包住，**任何异常都不能逃出回调**。在 catch 块里，根据错误的类型决定是 nack 进入死信队列，还是照常 ack。

## 6. 关于重新入队和顺序的一个提醒

如果你在某些情况下使用 `requeue=true`，要知道它会**破坏 resort 内的顺序**。当 prefetch 大于 1 时，consumer 手里已经持有了这条消息后面的几条消息。这条消息被放回队列后，后面那几条会先被处理，它要等到之后才被重新投递。

这也是为什么推荐**在本地重试，而不是依赖 requeue**：本地重试时，这条消息一直留在当前线程里，后面的消息会等它处理完再处理，顺序就不会被打乱。

## User

关于具体的对swipe进行分析，更新榜单，我觉得有细节不太明确，我现在对想法是，我们维护一张map，key为雪场：天：skierid，这样就可以保存并更新所有人的信息，然后我们去设计一张排行榜数据结构，为了方便映射，依然采用map, 因为每个雪场每天都维护一张表单，所以再维护一个"雪场：天" -> 排行表单的映射，排行表单内部就是skierid-> vertical，然后每次更新完一个滑雪者信息，都去对应排行榜查表，如果在表单内，就更新，不在，就跟最后一名比较判断是否入榜，然后表单如果有更新就把新表单推送返还给consumer？或者直接推送给broker？

## Claude

你的设计整体是正确的，而且和作业给出的结构（`skierVertical` 和 `resortLeaderboards`）完全对应。下面逐个确认细节，再回答你最后的问题。

## 1. 两层 Map 的设计：没问题

```
skierVertical:       "7:1:40213" → 1350          （所有 skier 的累计值）
resortLeaderboards:  "7:1"       → TopN 对象     （每个 resort 每天一张排行榜）
```

每条 swipe 的处理步骤：

```
① total = skierVertical.merge("7:1:40213", 200, Long::sum)   // 累加，并拿到新的总数
② board = resortLeaderboards.computeIfAbsent("7:1", k -> new TopN(10))
③ changed = board.update(40213, total)                       // 返回排行榜是否发生了变化
④ 如果 changed → 发布新的排行榜
```

## 2. 排行榜内部用 Map：可以用，但要清楚它的代价

你的想法是在 TopN 内部用 `skierID → vertical` 的 Map。我们来看看三个操作分别需要什么：

| 操作 | Map | 按顺序排列的 List |
|---|---|---|
| 判断某个 skier 是否在榜上 | O(1)，直接 `containsKey` | O(N)，需要遍历 |
| 找出最后一名 | O(N)，**需要遍历**，因为 Map 没有顺序 | O(1)，就是最后一个元素 |
| 生成按排名排列的榜单用于发布 | 每次都要排序 | 已经排好了，直接使用 |

Map 和 List 各有一个操作需要 O(N)。但 N 只有 10，在 10 个元素里遍历一次只需要几十纳秒，所以**两种选择在性能上没有区别，选哪种都可以**。

如果你更喜欢 Map 的直观性，就继续用 Map，在需要时遍历找出最后一名、在发布时排序。如果想让代码更简洁，可以用一个按 vertical 降序排列的小 ArrayList，每次更新之后重新排序。关键是要清楚你的选择各自需要付出什么代价，这在设计文档里也值得写一句。

## 3. 判断“有更新”时的几个边界情况

```
update(skierID, total):
  在榜上           → 更新他的值 → 返回 true（他的 vertical 变了，排名也可能变了）
  不在榜上，榜还没满 → 直接加入   → 返回 true
  不在榜上，total > 最后一名 → 替换最后一名 → 返回 true
  其他情况         → 返回 false
```

有几点需要事先定好规则：

- **平局**：如果 total 正好**等于**最后一名的值，要不要入榜？建议规定为**严格大于才入榜**，这样已经在榜上的人不会被同分的人挤掉。
- **排名的顺序**：两个人的 vertical 相同时，谁排在前面？建议按 skierID 从小到大排序作为次要规则。这样相同的数据总是会得到相同的排行榜，测试时更容易验证结果。
- **“在榜上的人值发生了变化”也算作更新**：因为榜单里会显示每个人的 vertical，值变了，发布出去的内容自然也就不同了。

## 4. 推送给谁：直接发布给 broker

“返还给 consumer”这个说法需要澄清一下：**聚合逻辑本身就运行在 consumer 程序里面**，它们是同一个进程，不存在“返还”的概念。consumer 内部可以分成几个组件：

```
consumer 进程
  ├─ 消息回调：接收 swipe → 解析 → 去重
  ├─ Aggregator：更新 skierVertical 和 TopN
  └─ Broadcaster：把新的排行榜发布到 leaderboard.fanout   ← 直接发布给 broker
```

Broadcaster 直接通过一个 channel 发布到 RabbitMQ 的 `leaderboard.fanout`，然后由 RabbitMQ 分发给每台 server-v2。

**用哪个 channel 来发布？** channel 不能在多个线程之间共享，而你有多个 consumer 线程（每个负责一部分 resort）。最简单的做法是：**每个 consumer 线程拥有两个专属的 channel**，一个用来消费 swipe，一个用来发布排行榜。每个线程只使用自己的 channel，就不会产生冲突。

另外，发布排行榜**不需要开启 publisher confirms**。原因和上一轮讨论的一样：排行榜是推导出来的状态，即使丢了一次，下一次更新也会带上最新的完整榜单。这和 swipe 不同，swipe 丢了就真的丢了。

## 5. 推送频率：需要加以控制

这一点前面提过，现在可以具体地看一下。你的 A1 客户端生成的 skierID 是 1 到 100000 之间的随机数，resortID 是 1 到 10 之间的随机数，所以 (resort, skier) 的组合大约有 100 万种。而一共只有 50 万条 swipe，**平均每个组合还不到一条**。这意味着：

- 测试刚开始时，几乎每条 swipe 都会让某个 skier 进入榜单，排行榜会**非常频繁**地变化。
- 到了后期，榜单上的人基本都有两三条 swipe，变化会慢慢变少。

如果每次变化都立即推送，测试初期每秒可能会产生**几千次**排行榜发布，这些消息还要乘以服务器的数量。

推荐的做法是**合并推送**：

```
Aggregator 线程：排行榜变化时，不立即发布，
               而是生成一份排好序的快照，存起来，并标记这个 resort 为“有更新”

Broadcaster 定时器：每隔 200ms 检查一次，
               只发布被标记为“有更新”的 resort 的最新快照，然后清除标记
```

这样，每个 resort 每秒最多只会推送 5 次，而推送出去的永远是最新的榜单，中间的那些变化被自然地合并了。

这里有一个线程安全方面的细节：快照是由 Aggregator 线程写入、由定时器线程读取的，所以要用 `AtomicReference` 来保存，或者用 `volatile` 修饰。而且快照必须是**一份独立的副本**（比如复制出一个新的 List），而不是直接把 TopN 内部的那个结构交出去，否则定时器读取时，它可能正在被修改。

## 6. 排行榜消息里的时间戳

A1 里的 `serverTimestamp` 是 server 发送消息的时间。现在排行榜是由 consumer 生成的，所以这个时间戳应该在 **consumer 发布时**打上。这样客户端测量的推送延迟，就变成了从 consumer 发布到客户端收到的完整时间，包括 consumer → RabbitMQ → server-v2 → 客户端这几跳。这正好可以展示 backplane 方案多了一跳所带来的实际代价，也是设计文档里很好的分析数据。

## User

consumer 去重、累加、top 20、手动 ack 都测过，多线程和 Broadcaster 写完还没验证，状态全在内存里，重启就没了。下一步让 LeaderboardEndpoint 从 fanout 收榜单推出去，想单独开个 channel 收，但同一个 session 并发发送会不会报错还没想好，新订阅者连上要不要先推一份缓存的榜单也在纠结。

## Claude

进度很不错。“状态全在内存里，重启就没了”这一点，在 A2 里是可以接受的，持久化是 A3 的工作。但要在设计文档里明确写出来：consumer 一旦重启，已经 ack 的消息就再也拿不回来了，所以累计值和排行榜会从零开始，无法重建。这本身也正好说明了为什么下一个作业需要数据库。

下面依次回答你的三个问题。

## 1. 单独开一个 channel 接收 fanout：方向正确

几个要点：

```java
Channel ch = connection.createChannel();             // 专门用来接收排行榜，不放进 ChannelPool
ch.exchangeDeclare("leaderboard.fanout", BuiltinExchangeType.FANOUT, true);
String q = ch.queueDeclare().getQueue();             // 不传参数：由服务器命名，exclusive，auto-delete
ch.queueBind(q, "leaderboard.fanout", "");
ch.basicConsume(q, true, deliverCallback, tag -> {});   // autoAck=true
```

- **`queueDeclare()` 不传任何参数**，得到的正好是我们之前说的“每台服务器专属的临时队列”：名字由 RabbitMQ 自动生成，只属于当前这条连接，连接断开后自动删除。4 台 server-v2 会各自得到一个独立的队列，这样每条排行榜消息都会被复制给每一台服务器。
- **用 autoAck=true 就可以。** 排行榜是推导出来的状态，丢了一条，下一条也会带上完整的最新榜单，不需要手动 ack 带来的可靠性保证。
- **一个小技巧**：fanout exchange 在路由时会忽略 routing key，但消息里**依然会带着它**。如果 consumer 发布排行榜时把 routing key 设为 resortID，server 在回调里就能直接通过 `envelope.getRoutingKey()` 知道这是哪个 resort 的榜单，不需要先解析整个 JSON。
- **在 `ServletContextListener` 里启动这个 consumer**，和发布用的 ChannelPool 放在一起初始化。回调里需要访问 LeaderboardEndpoint 的订阅者 Map，所以可以给 LeaderboardEndpoint 提供一个静态方法，比如 `broadcast(resortID, json)`，供回调调用。

## 2. 同一个 session 并发发送：会报错，而且比你想象的更容易触发

Tomcat 的 `getAsyncRemote().sendText()` 有一个规则：**上一条异步消息还没发送完成时，再发送下一条，就会抛出 `IllegalStateException`**，错误信息里会出现 `TEXT_FULL_WRITING`。我们在 A1 写 `pushAll` 时提到过这个问题。

很多人以为“只有一个线程在发送，就不会有问题”，但实际上：

- **即使只有一个线程也会触发。** fanout 的回调确实是一条接一条执行的，但 `sendText` 是**异步**的，调用之后立刻就返回了，此时消息还在后台发送。下一个回调紧接着到来，再次调用 `sendText`，如果上一条还没写完（比如这个客户端读取得比较慢），就会报错。
- **一旦加入缓存推送，就会变成真正的多线程。** `onOpen` 运行在 Tomcat 的线程上，而广播运行在 RabbitMQ 的回调线程上。这两个线程可能同时向同一个 session 发送消息。

### 不推荐的做法：加锁 + 同步发送

```java
synchronized (session) { session.getBasicRemote().sendText(json); }
```

这样确实不会再报错了，但 `getBasicRemote()` 是**阻塞**的。只要有一个客户端读取得很慢，RabbitMQ 的回调线程就会被卡住，**所有 resort 的所有订阅者都收不到更新了**。

### 推荐的做法：每个 session 配一个“只保留最新消息”的发送器

利用排行榜的一个特点：**只有最新的一份才有意义**，中间的版本可以直接丢弃。所以每个 session 只需要一个发送状态和一个“待发送”的槽位：

```java
class SessionSender {
    private final Session session;
    private final AtomicReference<String> pending = new AtomicReference<>();
    private final AtomicBoolean sending = new AtomicBoolean(false);

    SessionSender(Session session) { this.session = session; }

    void send(String json) {
        pending.set(json);          // 覆盖掉还没来得及发送的旧版本
        drain();
    }

    private void drain() {
        if (!sending.compareAndSet(false, true)) return;   // 已经有一条在发送了，等它完成后会再来检查
        String msg = pending.getAndSet(null);
        if (msg == null || !session.isOpen()) {
            sending.set(false);
            if (msg == null && pending.get() != null) drain();   // 防止漏掉刚刚放进来的消息
            return;
        }
        try {
            session.getAsyncRemote().sendText(msg, result -> {
                sending.set(false);
                drain();            // 这一条发送完成后，检查有没有新的待发送消息
            });
        } catch (Exception e) {
            sending.set(false);
        }
    }
}
```

它的效果是：

- **同一时刻，每个 session 最多只有一条消息在发送**，永远不会触发 `TEXT_FULL_WRITING`。
- **发送线程永远不会被阻塞**，慢的客户端只影响它自己。
- **慢的客户端会自动跳过中间版本**，只接收最新的榜单，不会越积越多。

`drain()` 里那一行 `pending.get() != null` 的再次检查是有必要的。如果没有它，可能会出现这样的情况：另一个线程刚好在“取出 null”和“把 sending 设回 false”之间放进了一条新消息，但那个线程尝试获取发送权时失败了，而当前线程又没有再检查一次，这条消息就会一直停留在 pending 里，没有人去发送。

### 怎么接入

把 `Map<String, Set<Session>>` 中的 `Set<Session>` 改为 `Map<Session, SessionSender>`；或者在 `onOpen` 时创建一个 SessionSender，存到 `session.getUserProperties()` 里。**所有发往这个 session 的消息，都必须经过它的 SessionSender**，包括下面要讲的缓存推送，不能有任何地方绕过它直接调用 `sendText`。

## 3. 新订阅者连接时，要不要先推送一份缓存的榜单：建议推送

**理由：**

- **测试后期，榜单的变化会越来越少**（上一轮分析过这个原因）。如果不推送缓存，一个在后期连接进来的订阅者，可能要等很久才能收到第一份榜单。
- **你的 A1 客户端有断线重连功能。** 重新连接之后能够立刻拿到当前的状态，而不是什么都看不到，这才是真正的“优雅地重连”。
- **代价很小。** 只需要在每台 server 上维护一个 `ConcurrentHashMap<resortID, 最新的 JSON>`，在 fanout 回调里每收到一份榜单就更新一下。

**实现的顺序：**

```
广播线程（fanout 回调）：先更新缓存 → 再逐个调用订阅者的 sender.send()
onOpen：                先把 session 注册进订阅者 Map → 再读取缓存，通过 sender.send() 发送
```

两边都“先做一件事，再做另一件事”，这样可以保证新订阅者不会漏掉注册过程中到来的更新。

### 但这里还有一个竞态问题

即使按照上面的顺序，依然可能出现这样的交错：

```
onOpen：  注册 session
onOpen：  读取缓存 → 得到 v1
广播：    缓存更新为 v2 → sender.send(v2)
onOpen：  sender.send(v1)    ← 旧版本覆盖了 pending 里的新版本
```

结果是新订阅者最后收到的是旧的 v1，而且要等到下一次更新才能纠正过来。

**解决方法是给榜单加上版本号。** 每个 resort 只由一个 consumer 线程负责，所以可以由这个线程为每个 resort 维护一个递增的序号，放进排行榜消息里。SessionSender 记录它已经接收过的最大版本号，**只接受比它更新的版本**，旧的版本直接丢弃。这样，无论消息以什么顺序到达，订阅者都不会看到榜单“倒退”。

这个版本号还有一个附带的用处：客户端可以检查收到的序号是否连续，从而估算出合并推送跳过了多少个中间版本。

## 关于你还没验证的多线程部分

Broadcaster 和多线程部分，有一个比较简单的验证方法：**用相同的输入，分别以单线程和多线程运行一次，比较最终的 top-N 是否完全一致。** 只要每个 resort 都严格由一个线程来处理，两次的结果就应该完全相同。如果出现了差异，就说明某个地方存在线程之间的竞争或者顺序问题。

## User

版本号那块我有点疑问。序号是 consumer 线程自己递增的，那 consumer 一重启就又从 0 开始了，server 的 SessionSender 还记着之前的最大版本，之后的新榜单不就全被当成旧的丢掉了？能不能直接拿 serverTimestamp 来比，反正它是 consumer 发布时打的，重启也不会回退。

## Claude

你说得对，这是我上一轮方案里的一个真实漏洞。consumer 重启后，序号会从 0 重新开始，而 SessionSender 还记着重启前的最大值，于是之后所有的新榜单都会被当作“旧版本”丢弃。订阅者会一直停留在重启前的那份榜单上，直到新的序号追上旧的最大值，而这可能需要很长时间，甚至永远追不上。

顺便说一句，这正好是 AI 使用反思里“AI 给出的方案有问题、被你自己发现”的典型例子，值得如实记下来。

## 用 serverTimestamp 来比较：可行，但有三个风险

你的思路的优点很明确：时间戳不依赖 consumer 的内存状态，重启后也不会回退，而且不需要任何额外的字段。但它依赖于**墙上时钟**，所以有几个需要了解的风险：

**1. 墙上时钟偶尔会往回调。** 前面讲 `Instant.now()` 时提到过，NTP 校时可能会调整系统时间。Amazon Linux 上的 chrony 平时是逐渐微调的，但在开机后的最初几次同步中，如果偏差较大，可能会**直接跳变**。如果时钟往回跳了，接下来一段时间的榜单就会被丢弃。在测试期间这种情况很少见，但并非不可能。

**2. 时间相同的情况。** 毫秒精度下，同一个 resort 的两份榜单可能会有相同的时间戳。在你的设计里，合并推送是每 200ms 一次，所以同一个 resort 不太可能在同一毫秒内产生两份榜单。但比较时要想清楚，是用“大于”还是“大于等于”。

**3. 不同机器之间的时钟偏差。** 如果 consumer 重启后运行在**另一台机器**上，而这台机器的时钟比原来那台慢 2 秒，那么它在最初 2 秒内发布的榜单都会被丢弃。有 NTP 同步时，偏差一般只有几毫秒，影响很小，但这个依赖是确实存在的。

对于这次作业的规模来说，这三个风险的实际影响都很小。所以**使用时间戳是可以接受的**，在设计文档里说明它依赖于 NTP 同步就可以了。

## 更稳妥的做法：启动纪元 + 序号

如果你想同时避免重启的问题和时钟的问题，有一个常见的模式：**版本号由两部分组成**。

```
版本号 = (epoch, seq)
  epoch：consumer 启动时的时间戳，在整个运行期间固定不变
  seq：  在这个 epoch 内，每个 resort 从 0 开始递增

比较规则：先比较 epoch，epoch 大的更新；epoch 相同时，再比较 seq
```

- **consumer 重启后**，会得到一个新的、更大的 epoch，所以新的榜单一定会被接受。这解决了你指出的问题。
- **同一次运行期间**，比较的是 seq，完全不依赖时钟，不会因为 NTP 调整而出错，也不会出现相同的版本号。
- 它依然依赖时钟，但**只在重启的那一刻**才需要：只要求“重启后的时钟比上次启动时更晚”，而这个要求比“每一次发布的时钟都要单调递增”宽松得多。

这个思路在分布式系统中很常见，通常叫做 generation、epoch 或者 fencing token。

## 退一步想：其实可以不需要版本号

回过头再看，我们引入版本号，是为了解决 **`onOpen` 推送缓存时与广播之间的竞态问题**。而这个竞态，其实可以直接从结构上消除：**让同一个 resort 的“广播”和“新订阅者注册”互斥地执行。**

```java
private static final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

static void broadcast(String resortID, String json) {
    synchronized (locks.computeIfAbsent(resortID, k -> new Object())) {
        cache.put(resortID, json);
        for (SessionSender s : sendersOf(resortID)) s.send(json);
    }
}

@OnOpen
public void onOpen(Session session, @PathParam("resortID") String resortID) {
    SessionSender sender = new SessionSender(session);
    synchronized (locks.computeIfAbsent(resortID, k -> new Object())) {
        register(resortID, session, sender);
        String cached = cache.get(resortID);
        if (cached != null) sender.send(cached);
    }
}
```

这样，对于同一个 resort，“更新缓存并广播”和“注册并发送缓存”不可能交错执行。新订阅者要么在广播之前完成注册，收到缓存后再收到这次的广播；要么在广播之后完成注册，读到的已经是最新的缓存。无论哪种情况，都不会出现旧版本覆盖新版本的问题。

我在上一轮讨论 channel pool 时说过，加锁的缺点是“等在一个忙碌的资源上”。为什么这里加锁没有问题？因为锁里面执行的 `sender.send()` 是**非阻塞**的：它只是设置了 pending，并启动一次异步发送，立刻就会返回。所以持有锁的时间非常短，即使有很多订阅者，也只需要几微秒。而且广播本来就只在一个 RabbitMQ 回调线程上执行，唯一会和它争用这把锁的，只有偶尔发生的 `onOpen`。

剩下的问题是：消息到达 server 的**顺序本身**会不会被打乱？在你的设计里，同一个 resort 的榜单只由一个 consumer 线程发布，经过同一个 fanout exchange，进入同一个队列，再由同一个 channel 消费。RabbitMQ 保证在这样一条单一的路径上，消息是按顺序传递的。所以，只要不出现“同一个 resort 同时由两个 consumer 负责”的情况，就不需要版本号。

## 建议

**用每个 resort 一把锁的方案，去掉版本号。** 它最简单，不依赖时钟，也不存在重启的问题。

如果以后出现了需要多个 consumer 同时负责同一个 resort 的情况（比如为了故障转移，或者前面讨论的进一步细分区），消息就可能通过不同的路径到达，顺序也就无法保证了。到那时，再引入 (epoch, seq) 版本号。在设计文档里可以简单提一句：当前的设计依赖于“每个 resort 只有一个发布者”这一前提，如果扩展到多个发布者，就需要引入版本号。

## User

作业要求榜单的内容是我发出的刷卡决定的，发了什么刷卡，榜单就相应地变成什么样，我如何设计证明这件事？尽量轻量级一点

## Claude

可以分成两层来做：**一个几分钟就能完成的手动冒烟测试**，用来截图作为证据；**一个在压测结束后自动执行的对账**，用来证明在 50 万条的规模下结果依然完全正确。两者都不需要引入新的组件。

## 第一层：手动冒烟测试（最轻量，适合截图）

思路是：**发送几条你能心算出结果的 swipe，然后看订阅者收到的榜单是否和你算的一样。**

1. 用 wscat 订阅 resort 7：`wscat -c ws://<ALB>/<app>/leaderboard/7`
2. 选择一个压测**不会使用**的 dayID（比如 99），这样这张榜单是全新的，不会受到其他数据的干扰。
3. 用 curl 发送几条 swipe，比如：

```
skier 111：lift 1 刷 3 次   → 3 × v1
skier 222：lift 40 刷 1 次  → 1 × v40
skier 333：lift 1 刷 1 次   → 1 × v1
```

根据 lift_vertical.json 里的数值，提前算出期望的排名和每个人的 vertical。

4. 每发送一条，就观察 wscat 收到的榜单如何变化：skier 111 的 vertical 一步一步增加，排名随之调整，最终的结果和你算出来的完全一致。

把 curl 的命令和 wscat 的输出放在一起截图，就是“榜单由发送的 swipe 决定”最直观的证据。整个过程只需要几分钟，不需要写任何新代码。

## 第二层：压测结束后自动对账（证明大规模下也正确）

你的 A1 客户端**知道自己发送了哪些 swipe**，也有订阅者在接收榜单。所以只需要让客户端自己在本地算一遍期望的结果，再和最终收到的榜单对比即可。

**客户端需要额外做的事情：**

```
发送端：每条 swipe 收到 201 之后，在本地累加
        expected[resortID][skierID] += vertical[liftID]
订阅端：记录每个 resort 最后收到的那份榜单
压测结束后：等榜单稳定下来 → 用 expected 算出每个 resort 的 top-N → 和最后收到的榜单逐条对比
```

```java
// 发送端：201 之后调用（每个 resort 一个 Map，或者用 ConcurrentHashMap）
expected.get(resortID).merge(skierID, (long) verticals[liftID], Long::sum);

// 压测结束后，对每个 resort：
List<Entry> want = topN(expected.get(r), N);   // 使用和 consumer 完全相同的排序规则
List<Entry> got  = lastBoard.get(r);
System.out.println("resort " + r + ": " + (want.equals(got) ? "MATCH" : "MISMATCH"));
```

最终打印出 10 行 `MATCH`，就证明了 50 万条 swipe 经过 ALB、server、RabbitMQ、consumer、backplane 这一整条链路之后，最终的榜单和发送的数据完全吻合。

内存方面，大约 40 万个 (resort, skier) 组合，用 HashMap 存储大约需要几十 MB，对客户端来说没有问题。

## 要让对账结果可信，有几个前提

**1. 每次测试使用新的 dayID，或者先重启 consumer。** 这一点最容易被忽略。consumer 的状态一直保存在内存里，如果你用相同的 dayID 连续运行两次，第二次的榜单会包含第一次的累计值，对账一定会失败。最简单的做法是，每次运行时让客户端使用一个新的 dayID。

**2. 等待榜单稳定之后再对比。** ingest 结束时，队列里可能还有没处理完的消息，合并推送也还有最后一次没有发出。所以要等到“连续几秒钟都没有收到新的榜单”，再取最后一份进行对比，之后再关闭订阅者。

**3. 排序规则必须和 consumer 完全一致。** 包括平局时按 skierID 排序、第 N 名同分时谁能入榜，这些规则只要有一点不同，就会在边界上出现差异。最稳妥的做法是，把 TopN 的排序逻辑提取成一个小类，consumer 和客户端共用同一份代码。

**4. 只累加收到 201 的 swipe。** 最终失败的 swipe 不应该计入期望值。

**5. 客户端也需要 lift_vertical.json。** 把同一个文件复制到客户端的 resources 目录里即可。

## 如果出现了 MISMATCH，它本身也很有价值

对账失败通常只有几种原因，每一种都对应着一个真实的系统问题：

- **期望值比实际值小**：某条 swipe 被重复计算了。最可能的原因是我们之前讨论过的情况：客户端在超时后重试，server 生成了新的 eventId，去重机制无法识别。
- **期望值比实际值大**：有 swipe 丢失了，比如 server 返回了 201，但消息实际上没有进入队列，或者 consumer 没有处理它。
- **只在最后一名附近不一致**：排序规则不一致。

所以对账不仅能证明系统是正确的，在出问题时也能直接告诉你问题出在哪个环节。如果在正式测试中真的遇到了重复计算导致的 MISMATCH，如实地写进报告，并用前面讨论的幂等键方案来解释原因，这会比一个完美的 MATCH 更能展示你对系统的理解。

## User

请问我怎么进行部署，比如，我目前broker，server， consumer，client全在本机上，未来需要把broker，server，consumer全分到不同的aws机器上吗？具体怎么实现，请说的详细一点，因为我并没有这方面经验

## Claude

是的，最终要把它们分开部署到不同的 EC2 上，这也是作业的明确要求：RabbitMQ 和 consumer 各自运行在单独的 EC2 上，server-v2 运行 1、2、4 个实例并放在 ALB 后面。分开部署的原因是让每个组件**独占自己的 CPU 和内存**。如果全部放在一台机器上，它们会互相争抢资源，测出来的吞吐量和瓶颈都不能反映真实的分布式系统。

下面按“先理解概念，再按顺序动手”的方式，一步一步讲。

## 一、目标架构

```
                    你的电脑（客户端）
                          │ HTTP / WebSocket
                          ▼
                ┌──────── ALB ─────────┐  （公网可以访问，端口 80）
                ▼          ▼           ▼
          server-v2 #1  server-v2 #2  ……     （Tomcat，端口 8080）
                │  发布 swipe   ▲ 接收排行榜
                ▼              │
           ┌──────── RabbitMQ ────────┐      （端口 5672，管理界面 15672）
                │  消费 swipe   ▲ 发布排行榜
                ▼              │
              consumer（独立的 Java 程序）
```

全部机器都放在 **us-west-2 区域**，使用同一个默认 VPC。

## 二、先理解两个核心概念

### 1. 公网 IP 和私有 IP

每台 EC2 都有两个 IP 地址：

- **公网 IP**：从互联网访问它时使用，比如你 SSH 登录、在浏览器里打开管理界面。**每次 stop 再 start 实例，公网 IP 都会改变**（除非你绑定了 Elastic IP）。
- **私有 IP**：在同一个 VPC 内部，机器之间互相访问时使用，形如 `172.31.x.x`。**stop 和 start 之后不会改变**。

所以有一条重要的原则：**机器之间互相连接时，一律使用私有 IP。** 比如 server-v2 和 consumer 连接 RabbitMQ 时，配置的应该是 RabbitMQ 的私有 IP。这样做有三个好处：重启实例后配置依然有效；流量不经过公网，延迟更低；同一个可用区内的流量是免费的。

### 2. 安全组（Security Group）

安全组就是每台机器的**防火墙规则**，决定了“谁可以访问我的哪个端口”。在 A1 里，你只用到了“允许我的 IP 访问 8080 和 22”。这次机器多了，最好的做法是**为每一类角色创建一个安全组**，并且**用安全组作为访问来源**，而不是填写具体的 IP 地址：

| 安全组 | 入站规则 | 说明 |
|---|---|---|
| `sg-alb` | 80 ← 你的 IP（或 0.0.0.0/0） | 客户端访问 ALB |
| `sg-server` | 8080 ← `sg-alb`；22 ← 你的 IP；8080 ← 你的 IP（调试用） | 只有 ALB 可以把流量转发进来 |
| `sg-rabbit` | 5672 ← `sg-server` 和 `sg-consumer`；15672 ← 你的 IP；22 ← 你的 IP | 只有 server 和 consumer 能连接 RabbitMQ |
| `sg-consumer` | 22 ← 你的 IP | consumer 只需要主动连接别人，不需要被别人访问 |

“8080 ← `sg-alb`”的意思是：**所有属于 `sg-alb` 这个安全组的机器**都可以访问 8080 端口。这样，无论你以后增加多少台 server、它们的 IP 是什么，都不需要修改规则。

出站规则保持默认的“全部允许”即可。

## 三、部署之前：把配置从代码里拿出来

这是从“本机运行”过渡到“分布式部署”最关键的一步。现在你的代码里，RabbitMQ 的地址很可能写的是 `localhost`。部署之后，每台机器需要连接的地址都不一样，**不能再写死在代码里**，而应该从**环境变量**中读取：

```java
String rabbitHost = System.getenv().getOrDefault("RABBIT_HOST", "localhost");
String rabbitUser = System.getenv().getOrDefault("RABBIT_USER", "guest");
String rabbitPass = System.getenv().getOrDefault("RABBIT_PASS", "guest");
```

这样做的效果是：**同一个 WAR 包、同一个 jar 包，在本机和 AWS 上都能直接运行**。在本机不设置环境变量，就使用默认值 localhost；在 AWS 上通过环境变量指定 RabbitMQ 的私有 IP。

serverId 建议直接使用**主机名**（`InetAddress.getLocalHost().getHostName()`），而不是通过配置文件指定。原因在后面的第 4 步会讲到。

## 四、按顺序部署

建议严格按照这个顺序进行，**每完成一步都要先验证，然后再进行下一步**。

### 第 1 步：部署 RabbitMQ

**创建实例**：t2.micro，Amazon Linux 2023，安全组选择 `sg-rabbit`。

**用 Docker 安装**，这是最简单的方式，可以避免手动安装 Erlang 时的版本问题：

```bash
sudo dnf install -y docker
sudo systemctl enable --now docker

sudo docker run -d --name rabbitmq --restart unless-stopped \
  -p 5672:5672 -p 15672:15672 \
  -e RABBITMQ_DEFAULT_USER=skitrak \
  -e RABBITMQ_DEFAULT_PASS=<你的密码> \
  rabbitmq:4-management
```

- `--restart unless-stopped`：机器重启后，RabbitMQ 会自动启动。
- `-management` 版本的镜像自带管理界面，不需要再执行 `rabbitmq-plugins enable`。
- 通过环境变量创建的用户**可以从其他机器登录**，不存在 guest 只能本机登录的限制。

以后需要执行 `rabbitmqctl` 命令时，在前面加上 `docker exec`：

```bash
sudo docker exec rabbitmq rabbitmqctl list_queues name messages
```

**验证**：在浏览器里打开 `http://<RabbitMQ 公网 IP>:15672`，能用 skitrak 用户登录就说明成功了。记下这台机器的**私有 IP**，后面要用。

### 第 2 步：部署 consumer

**创建实例**：t2.micro，Amazon Linux 2023，安全组选择 `sg-consumer`。

**安装 Java**，版本要不低于你编译时使用的版本：

```bash
sudo dnf install -y java-17-amazon-corretto-headless
```

**在本机打包并上传**：consumer 是独立运行的程序，需要用 `maven-shade-plugin` 打成包含所有依赖的 fat jar，这一点和 A1 的客户端一样。

```bash
mvn clean package
scp -i <key>.pem target/consumer.jar ec2-user@<consumer 公网 IP>:~
```

**用 systemd 让它在后台运行，并在崩溃后自动重启。** 作业里要求的 “auto-restart on failure” 就是通过这个实现的。创建文件 `consumer.service`：

```ini
[Unit]
Description=SkiTrak Consumer
After=network-online.target

[Service]
User=ec2-user
Environment=RABBIT_HOST=<RabbitMQ 的私有 IP>
Environment=RABBIT_USER=skitrak
Environment=RABBIT_PASS=<你的密码>
ExecStart=/usr/bin/java -Xmx512m -jar /home/ec2-user/consumer.jar
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
sudo cp consumer.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now consumer     # 启动，并设置为开机自动启动
journalctl -u consumer -f                # 实时查看日志
```

- `Restart=always` 和 `RestartSec=5`：程序因为任何原因退出后，5 秒后自动重新启动。
- `-Xmx512m`：限制 JVM 最多使用 512 MB 内存。t2.micro 一共只有 1 GB 内存，要给操作系统留出空间。

**验证**：在 RabbitMQ 管理界面的 Connections 页面，应该能看到一条来自 consumer 私有 IP 的连接；在 Queues 页面，每个 resort 队列的 Consumers 一栏应该显示 1。

### 第 3 步：部署第一台 server-v2

**创建实例**：t2.micro，安全组选择 `sg-server`。安装 Java 和 Tomcat 9 的过程和 A1 完全一样。

**设置环境变量**：创建 `<TOMCAT_HOME>/bin/setenv.sh` 文件，Tomcat 启动时会自动读取它：

```bash
export RABBIT_HOST=<RabbitMQ 的私有 IP>
export RABBIT_USER=skitrak
export RABBIT_PASS=<你的密码>
```

如果你的 Tomcat 是通过 systemd 启动的，就要把这些变量写在 Tomcat 的 systemd 单元文件里，用 `Environment=` 的形式，和 consumer 的写法一样。

**还要确认两件事：**

- `conf/server.xml` 里设置了 `maxKeepAliveRequests="-1"`，这是我们在 A1 里讨论过的。
- **Tomcat 要通过 systemd 设置成开机自动启动**，而不是在 screen 里手动启动。下一步会讲到为什么这一点很重要。

**部署 WAR**，方式和 A1 一样。

**验证**：直接访问这台机器（先不经过 ALB）：

```bash
curl -i http://<server 公网 IP>:8080/<app>/health
curl -i -X POST -H "Content-Type: application/json" -d '{"time":217,"liftID":21}' \
  http://<server 公网 IP>:8080/<app>/skiers/7/seasons/2026/days/99/skiers/123
```

返回 201 之后，在 RabbitMQ 管理界面里应该能看到消息被发布到 resort.7 队列，并被 consumer 消费掉。**到这里，整条链路就第一次在 AWS 上跑通了。** 这时可以用 A1 的客户端直接压测这台机器，作为“单实例”的测试结果。

### 第 4 步：复制出更多的 server-v2

第一台 server 配置好之后，不需要再手动配置第二台、第三台。用 **AMI**（机器镜像）把它“拍一张快照”，然后直接用快照启动新的实例：

1. EC2 控制台 → 选中第一台 server → **Actions → Image and templates → Create image**。
2. 等待 AMI 创建完成（在左侧的 AMIs 页面查看，状态变成 available 即可）。
3. **Launch instance from AMI**，数量填 3，安全组同样选择 `sg-server`。

新启动的机器上已经有了安装好的 Tomcat、部署好的 WAR，以及 setenv.sh 中的配置。

这就是前面两个设计决定的原因：

- **Tomcat 必须设置成开机自动启动**，否则新机器启动之后，Tomcat 不会运行。
- **serverId 使用主机名**，因为每台新机器的主机名都不一样，这样 AMI 复制出来的所有机器就可以完全一样，不需要逐台修改配置。

**验证**：分别对新的每台机器执行一次第 3 步的 curl 测试。

### 第 5 步：创建 ALB

**5.1 创建目标组（Target Group）**

EC2 控制台 → 左侧 **Target Groups** → Create target group：

- Target type：**Instances**
- Protocol 和 Port：**HTTP，8080**
- 健康检查的路径：**`/<app>/health`**。一定要带上 context path，否则健康检查会因为 404 而失败，所有的机器都会被判定为不健康。
- 健康检查的参数，按作业的要求设置：Interval 30 秒，Timeout 5 秒，Healthy threshold 2，Unhealthy threshold 3。
- 注册目标：把 server-v2 的实例加进来。测试单实例时只加 1 台，之后再逐渐增加到 2 台、4 台。

创建完成后，进入这个目标组的 **Attributes → Edit**，开启 **Stickiness**，类型选择 **Load balancer generated cookie**。

**5.2 创建 ALB**

左侧 **Load Balancers** → Create → **Application Load Balancer**：

- Scheme：**Internet-facing**
- Network mapping：**至少选择两个不同可用区的子网**。这是 ALB 的硬性要求，即使你的 server 都在同一个可用区里也必须这样做。
- 安全组：**`sg-alb`**
- Listener：**HTTP:80**，转发到上一步创建的目标组。

创建完成后，进入 ALB 的 **Attributes → Edit**，把 **Connection idle timeout** 设置为大于 60 秒，比如 120 秒。ALB 原生就支持 WebSocket，不需要额外打开任何开关。

**验证**：在目标组的 Targets 页面，所有机器的状态都应该显示为 **healthy**。然后通过 ALB 的 DNS 名称访问：

```bash
curl -i http://<ALB 的 DNS 名称>/<app>/health
wscat -c ws://<ALB 的 DNS 名称>/<app>/leaderboard/7
```

### 第 6 步：客户端连接 ALB

把客户端的服务器地址改为：

```
http://<ALB 的 DNS 名称>/<app>
ws://<ALB 的 DNS 名称>/<app>/leaderboard/{resortID}
```

注意端口变成了 80，所以 URL 里不需要再写 `:8080`。

**这里有一个很容易踩的坑**：如果你的客户端使用的是 Apache HttpClient 5，它**默认会保存 cookie**。ALB 开启粘性会话之后，会在第一个响应里返回一个 `AWSALB` cookie，客户端保存它之后，后续的所有 POST 都会携带这个 cookie，于是**所有的请求都会被固定发送到同一台 server 上**。这样，你的“4 台服务器”测试实际上只有 1 台在工作，负载完全不均衡。

解决方法是在 ingest 用的 HttpClient 上**关闭 cookie 管理**：

```java
HttpClients.custom().disableCookieManagement()...
```

粘性会话真正需要发挥作用的是 WebSocket。实际上，WebSocket 连接一旦建立，它本身就是一条固定的 TCP 连接，会一直保持在同一台 server 上。所以对于 ingest 的 POST 请求，关闭 cookie 管理，让 ALB 把请求均匀地分配到各台 server 上，才是你想要的效果。

## 五、费用和常见的坑

**1. 费用。** 这次一共需要 RabbitMQ、consumer、4 台 server，一共 6 台 t2.micro，再加上一个 ALB。免费额度是**所有实例合计**每个月 750 小时，6 台机器同时运行，大约 5 天就会用完。**ALB 只要存在就会按小时计费**，即使没有任何流量。建议：

- 在 AWS Billing 里设置一个**预算警报**，比如超过 5 美元就发邮件通知你。
- 不测试的时候 **stop** 掉所有实例。
- 先用 1 台 server 把功能调试好，**最后再集中地**进行 2 台和 4 台的测试。
- 全部测试完成并截图之后，删除 ALB。

**2. t2.micro 的 CPU 积分。** t2 系列属于“可突发”实例，平时只有 10% 的基准 CPU 性能，只有在消耗 CPU 积分时才能满速运行。长时间的压测会耗尽积分，之后性能会**突然下降**，吞吐量也随之骤降。如果你在测试中途看到吞吐量突然下降，可以在 CloudWatch 里查看 `CPUCreditBalance` 指标。在报告里解释这种现象，也是很好的分析素材。

**3. RabbitMQ 的内存警报。** RabbitMQ 默认在内存使用量达到系统内存的一定比例（根据版本不同，大约是 40% 到 60%）时触发**内存警报**，并暂停接收所有的发布。在只有 1 GB 内存的 t2.micro 上，如果队列积压得很多，就很容易触发这个警报。触发之后，server 发布消息时会被阻塞，你的 `waitForConfirmsOrDie` 就会超时，从而返回 503。在管理界面的 Overview 页面，如果内存一栏变成了红色，说明已经触发了警报。

**4. 公网 IP 会变。** stop 再 start 实例之后，SSH 和访问管理界面所用的公网 IP 都会变化，需要到 EC2 控制台重新查看。而机器之间的配置使用的是私有 IP，所以不会受影响，这就是前面强调使用私有 IP 的原因。

## 六、`/deployment` 目录里放什么

作业要求提交 `/deployment` 目录，正好把上面这些内容整理进去：

```
deployment/
├── rabbitmq/setup.sh          # docker run 命令，以及 policy 的设置
├── consumer/consumer.service  # systemd 单元文件
├── server/setenv.sh           # Tomcat 的环境变量（密码用占位符代替）
├── alb/README.md              # 目标组、ALB 的配置参数和截图
└── security-groups.md         # 四个安全组的规则
```

注意，**不要把真实的密码提交到 Git**，用占位符代替即可。

如果你希望把这份部署步骤保存成一个文件，方便你一边操作一边对照，我可以帮你整理成一份文档。

## User

作业要求Consumer lag < 100 ms是什么意思？如何定义边界？是一条swipe从server发出的时刻开始，一直到它的信息被记录进consumer的表中吗？还是在成功合并，榜单刷新之后？如何界定？

## Claude

作业里并没有严格定义这个指标，所以**你需要自己给出一个明确的定义，并在报告里说明理由**。先看几个可能的边界，再说我推荐的定义。

## 三个可能的边界

```
server 收到请求并发布消息 ─▶ RabbitMQ 队列中排队 ─▶ consumer 收到消息 ─▶ 聚合完成 ─▶ 排行榜推送出去
        T0                                        T1            T2           T3

方案 A：T0 → T1   消息在队列里等了多久
方案 B：T0 → T2   从发布到状态更新完成，一共用了多久
方案 C：T0 → T3   从发布到排行榜被推送出去，一共用了多久
```

**方案 C 不适合作为 consumer lag。** 它包含了合并推送的等待时间。我们设计的是每 200ms 推送一次，单是这一项就可能让一条 swipe 等待接近 200ms。这样，这个指标**从设计上就不可能**低于 100ms。而且，它衡量的已经不是“consumer 是否跟得上”，而是整条链路的“新鲜度”了。它是一个很有价值的指标，但应该单独命名，比如叫做 swipe-to-leaderboard latency，并单独报告。

**方案 A 和方案 B 的差别很小。** 聚合只需要几微秒，所以 T1 和 T2 几乎是同一时刻。

## 推荐的定义：方案 B

> **Consumer lag = 从 server 发布一条 swipe，到 consumer 完成这条 swipe 的聚合（状态已更新、准备 ack）所经过的时间。**

选择这个定义的理由：

1. **它衡量的正是“consumer 是否跟得上生产速度”。** 如果 consumer 的处理速度跟不上，消息就会在队列里积压，这个时间就会越来越长。这和作业的意图完全一致。
2. **终点选择在“聚合完成”，而不是“收到消息”。** 这样才能体现出这条 swipe 真正影响到系统状态的时刻：从这一刻起，查询这个 skier 的 vertical，就已经包含了这条 swipe。
3. **它不包含推送的设计选择。** 合并推送的间隔是一个独立的参数，不应该和 consumer 的处理能力混在一起。

## 它和“队列深度 < 1000”的关系

这两个目标其实是**同一件事的两个侧面**，可以用 Little's Law 联系起来：

```
队列里的平均消息数 = 消息的到达速率 × 每条消息的平均等待时间
```

假设 swipe 的到达速率是每秒 5000 条，lag 是 100ms：

```
5000 条/秒 × 0.1 秒 = 500 条
```

也就是说，lag 低于 100ms 时，队列里大约只有 500 条消息，正好在 1000 条的目标以内。你可以在报告里用实测的数据验证一下这个关系：比如实测平均 lag 是多少，实测平均队列深度是多少，两者是否满足 Little's Law。这会是一个很有说服力的分析。

## 怎么测量

消息里本身就有 `timestamp` 字段（server 发布时打上的），所以只需要在 consumer 聚合完成之后计算一下：

```java
// 聚合完成之后、ack 之前
long lagMs = System.currentTimeMillis() - Instant.parse(event.timestamp).toEpochMilli();
lagRecorder.record(lagMs);
```

几点注意事项：

**1. server 要在发布之前的那一刻再打时间戳。** 如果时间戳是在收到请求时就打上的，lag 就会包含 server 自己处理请求的时间，比如校验、借 channel 等待的时间。打时间戳的位置越接近 `basicPublish`，测出来的就越是 consumer 端的延迟。

**2. 不要使用 AMQP 自带的 timestamp 属性。** RabbitMQ 的消息属性里也有一个 `timestamp` 字段，但它的**精度只有秒**，用来测量 100ms 级别的延迟完全不够。所以要使用消息体里的那个毫秒精度的字段。

**3. 存在跨机器的时钟偏差。** 时间戳是 server 的时钟打的，而计算 lag 用的是 consumer 的时钟，这和 A1 里推送延迟的问题完全一样。两台 EC2 都通过 Amazon Time Sync Service 同步时钟，偏差通常在 1 毫秒以内，相对于 100ms 的目标可以忽略。但在报告里应该说明这一点。

**4. prefetch 缓冲区里的等待时间也会被计入。** prefetch 设置得较大时，消息会先被发送到 consumer 的本地缓冲区里排队，然后才被处理。这段时间也属于 lag，因为这条 swipe 确实还没有被处理。这一点在调优时很有用：如果 prefetch 设得过大，lag 也会随之增加。

**5. 记录的是分布，而不是单个数字。** 50 万个 long 只占大约 4 MB 内存，可以全部保存下来，最后计算 mean、p95、p99、max。还建议**每 10 秒打印一次**这个时间段内的 lag，这样就能画出 lag 随时间变化的曲线。如果 consumer 跟不上了，你会看到 lag 持续上升，这和作业里描述的“锯齿形”队列曲线是对应的。

## 在报告里怎么写

用一两句话明确你的定义，再说明为什么这样定义：

> We define consumer lag as the time from when the ingest server publishes a swipe (the message `timestamp`) to when the consumer has applied it to the aggregate state. This excludes the leaderboard broadcast interval, which is a separate design parameter reported as end-to-end latency. Measurements span two hosts synchronized via Amazon Time Sync Service (sub-millisecond skew).

然后给出 lag 的分布（mean、p95、p99）、lag 随时间变化的曲线，以及它与队列深度之间的 Little's Law 验证，最后再单独报告从 swipe 到排行榜推送的端到端延迟。

## User

swipe的到来是大量的，你这样直接每一条都统计计算吗？如何实现？

## Claude

是的，每一条都统计，但**统计的方式要选对**。关键在于：计算本身很便宜，真正可能拖慢系统的是**存储和汇总的方式**。

## 先算一下每条消息的开销

| 操作 | 大约的耗时 |
|---|---|
| `System.currentTimeMillis()` | 几十纳秒 |
| `Instant.parse(...)` 解析时间戳 | 约 1 微秒 |
| 在一个计数器上加一 | 几到几十纳秒 |

加起来大约 1 微秒。即使每秒处理 1 万条消息，总共也只占用大约 1% 的 CPU 时间。所以**每条都计算是完全可以承受的**，不需要抽样。

真正需要避免的是这些做法：

- **每条消息都打印一行日志或写一次文件**：I/O 比计算慢几个数量级。
- **把每个值放进一个共享的、加了锁的 List**：所有 consumer 线程都会抢同一把锁。
- **使用 `ConcurrentLinkedQueue<Long>` 这类容器**：每个 long 都要装箱成 Long 对象，50 万个对象会给 GC 带来不小的压力。

## 推荐的做法：固定分桶的直方图

思路是：**不保存每一个具体的值，而只统计“落在每个毫秒区间里的消息有多少条”。**

```
桶 0：lag 为 0ms 的消息数量
桶 1：lag 为 1ms 的消息数量
……
桶 999：lag 为 999ms 的消息数量
桶 1000：lag ≥ 1000ms 的消息数量（溢出桶）
```

- **记录一个值**：找到它对应的桶，计数加一。时间复杂度是 O(1)。
- **内存是固定的**：无论处理多少条消息，都只占用 1001 个计数器，不会随着消息量而增长。
- **精度为 1ms**：对于 100ms 的目标来说完全足够。
- **计算百分位数**：从桶 0 开始依次累加计数，当累加值第一次达到总数的 q（比如 99%）时，那个桶的编号就是 p99。

```java
public class LagHistogram {
    private static final int MAX = 1000;
    private final LongAdder[] buckets = new LongAdder[MAX + 1];
    private final LongAccumulator max = new LongAccumulator(Long::max, 0);
    private final LongAdder sum = new LongAdder();

    public LagHistogram() {
        for (int i = 0; i <= MAX; i++) buckets[i] = new LongAdder();
    }

    public void record(long lagMs) {
        int b = (int) Math.max(0, Math.min(lagMs, MAX));   // 负数归入 0，过大的值归入溢出桶
        buckets[b].increment();
        sum.add(lagMs);
        max.accumulate(lagMs);
    }

    public long[] snapshot() {                          // 当前每个桶的累计计数
        long[] s = new long[MAX + 1];
        for (int i = 0; i <= MAX; i++) s[i] = buckets[i].sum();
        return s;
    }

    public static long percentile(long[] counts, double q) {
        long total = 0;
        for (long c : counts) total += c;
        long target = (long) Math.ceil(q * total), acc = 0;
        for (int i = 0; i < counts.length; i++) {
            acc += counts[i];
            if (acc >= target) return i;
        }
        return counts.length - 1;
    }
}
```

**为什么使用 LongAdder？** 多个 consumer 线程会同时调用 `record`，而大部分消息的 lag 都很接近，会集中落在少数几个桶里。如果用普通的 `AtomicLong`，这几个热门的桶会被多个线程激烈地争抢。`LongAdder` 内部会把计数分散到多个单元里，专门为“多个线程频繁累加同一个计数器”的场景而设计，这和我们在 A1 里统计成功次数时用它的原因是一样的。

**负数的 lag 归入桶 0**，这是因为时钟偏差可能导致出现小的负值。如果你想如实地反映时钟偏差，可以另外统计一下负数出现的次数。

## 每 10 秒打印一次：用快照相减

如果想画出 lag 随时间变化的曲线，就需要知道**每个 10 秒的区间内**的分布情况，而不仅仅是累计的总体分布。

**不要在打印之后把计数器清零**：在“读取”和“清零”之间，其他线程可能又记录了一些值，这些值就会丢失。

正确的做法是：**计数器只增不减，每次打印时，用这一次的快照减去上一次的快照**：

```java
long[] prev = new long[1001];

scheduler.scheduleAtFixedRate(() -> {
    long[] now = hist.snapshot();
    long[] interval = new long[now.length];
    for (int i = 0; i < now.length; i++) interval[i] = now[i] - prev[i];
    prev = now;     // 注意：prev 需要定义为字段，或者用数组包装，lambda 里才能修改它

    System.out.printf("lag p50=%dms p99=%dms%n",
            LagHistogram.percentile(interval, 0.50),
            LagHistogram.percentile(interval, 0.99));
}, 10, 10, TimeUnit.SECONDS);
```

这个定时任务**只在一个线程里执行**，所以 `prev` 只由这一个线程读写，不存在线程安全的问题。测试结束时，对最后一次的累计快照计算百分位数，就得到了整个测试的总体分布。

## 可选的进一步优化：避免解析字符串

`Instant.parse` 是每条消息里开销最大的部分。如果想把它也省掉，可以让 server 在发布消息时，额外在 AMQP 的 **headers** 里放一个毫秒数：

```java
// server 发布时
Map<String, Object> headers = Map.of("publishedAtMs", System.currentTimeMillis());
AMQP.BasicProperties props = MessageProperties.PERSISTENT_TEXT_PLAIN.builder().headers(headers).build();

// consumer 读取时
long publishedAt = (Long) properties.getHeaders().get("publishedAtMs");
```

这样不需要修改作业规定的消息体格式，consumer 也可以直接读取一个 long，而不需要解析字符串。不过正如前面算过的，`Instant.parse` 本身的开销只有 1% 左右，所以这一步不是必需的。

## 如果你想用现成的库

业界常用的是 **HdrHistogram**。它是专门用来记录延迟分布的库，精度可以配置，内存占用固定，还提供了一个 `Recorder` 类，可以线程安全地获取每个时间区间的快照，正好对应上面“每 10 秒打印一次”的需求。如果你不想自己实现，可以直接使用它。不过自己实现上面这个简单的版本，代码只有几十行，而且你可以清楚地解释其中的每一行，对于作业来说也完全足够了。

## User

用桶的形式精度受限制吧？是否可以用list直接存下，用排序解决？你帮我算下内存开销

## Claude

你说得对，分桶确实会损失精度：同一个桶里的值，最终只能被当作同一个值来看待。不过在我们这个具体的场景里，**1ms 宽的桶实际上几乎不会损失任何精度**，原因在下面会讲到。先回答内存的问题。

## 内存开销的计算

按 50 万条（常规测试）和 100 万条（压力测试）分别计算：

| 存储方式 | 每个元素占用 | 50 万条 | 100 万条 |
|---|---|---|---|
| `long[]` 基本类型数组 | 8 字节 | 约 4 MB | 约 8 MB |
| `int[]`（lag 的值用 int 就足够存储） | 4 字节 | 约 2 MB | 约 4 MB |
| `ArrayList<Long>` | 约 28 字节 | 约 14 MB | 约 28 MB |
| 分桶直方图（1001 个 LongAdder） | 固定大小 | 几十 KB | 几十 KB |

`ArrayList<Long>` 为什么是 28 字节？以 64 位 JVM 默认开启指针压缩的情况为例：

```
List 里保存的引用：  4 字节
Long 对象本身：      12 字节对象头 + 8 字节 long 值 = 20 字节，按 8 字节对齐后为 24 字节
合计：               约 28 字节
```

另外，ArrayList 在扩容时会把容量增加到原来的 1.5 倍，所以在扩容的那一刻，实际占用的内存可能会更高。

这里还有一个有意思的细节：Java 会**缓存 -128 到 127 之间的 Long 对象**，自动装箱时会直接复用这些缓存的对象。而你的 lag 大部分都在 100ms 以内，所以实际上，大部分元素只会占用 4 字节的引用。不过这只是一个巧合，不应该在设计中依赖它。

**结论：用 `long[]` 或 `int[]` 全部保存下来，内存是完全可以承受的。** 作为对比，consumer 的 `skierVertical` 有大约 40 万个条目，每个条目连同 String 类型的 key 和哈希表的开销，大约要占用几十 MB；去重集合也要占用上百 MB。相比之下，几 MB 的 lag 数据可以忽略不计。

排序的开销也很小：用 `Arrays.sort` 对 50 万个 long 进行排序，大约只需要几十毫秒，而且只在测试结束时执行一次。

## 为什么 1ms 的桶几乎不损失精度

关键在于，**你输入的数据本身就只有毫秒精度**：

```java
long lagMs = System.currentTimeMillis() - publishedAtMs;
```

两个时间戳都是以毫秒为单位的整数，相减得到的 lag 也必然是一个**整数毫秒**。所以 lag 的取值只可能是 0、1、2、3……，**每一个可能的取值都恰好对应一个桶**。在这种情况下，分桶计数和保存所有原始值之后再排序，得到的百分位数是**完全一样的**。

唯一会损失精度的地方，是**溢出桶**：所有大于等于 1000ms 的值都被归入了同一个桶。这个问题有两种解决办法：一是把桶的范围扩大，比如设置到 10 秒，也就是 10001 个桶，内存依然只有几百 KB；二是像前面的代码那样，单独记录最大值。

那么，能不能把精度提高到亚毫秒级呢？意义不大。因为 lag 是跨两台机器测量的，而两台机器之间的时钟偏差本身就在亚毫秒级。即使你把测量精度提高到了微秒，测出来的微秒部分，大多也只是时钟偏差带来的噪声。

## 如果用数组存储，要注意这两点

**1. 每个线程使用自己的数组。** 多个 consumer 线程同时向同一个 List 添加元素，就需要加锁。更好的做法是让每个 consumer 线程拥有一个**自己专属的数组**，这和我们反复用到的“线程封闭”是同一个思路。由于事先不知道每个线程会处理多少条消息，可以预先分配一个足够大的容量，或者在容量不够时，像 ArrayList 那样自己手动扩容。

**2. 在合适的时机汇总。** 测试结束时，需要先**停止所有的 consumer 线程**，再把它们各自的数组合并起来进行排序。如果在 consumer 线程仍然在写入的时候，从另一个线程读取它们的数组，就会遇到我们在讲 `volatile` 时提到的可见性问题：读到的数据可能是不完整的。

## 两种方式怎么选

| | 保存所有原始值再排序 | 分桶直方图 |
|---|---|---|
| 最终结果的精度 | 精确 | 在毫秒整数的输入下，同样精确（溢出部分除外） |
| 内存 | 随消息数量增长 | 固定不变 |
| 每 10 秒打印一次区间统计 | 比较麻烦，需要按时间区间分别保存 | 很简单，用快照相减即可 |
| 实现的难度 | 低 | 低 |

如果你**只需要在测试结束时输出一份总体统计**，那么用数组保存所有值再排序是最直观的做法，你完全可以这样实现。

如果你还想**画出 lag 随时间变化的曲线**（这对证明 consumer 是否跟得上生产速度非常有用），那么直方图会更方便。

你也可以**两者同时使用**：用直方图每 10 秒打印一次区间统计，用数组在最后计算精确的总体统计。两者的内存开销加起来也只有几 MB。

## User

这是我的consumer的pom.xml，如果要单独在ec2上启动，如何操作

## Claude

你的 pom.xml 好像没有传上来，我这边只看到了之前那张 Postman 的截图。你可以直接把内容粘贴到对话里，我帮你逐行检查。下面先说一下通用的操作步骤，你可以对照着看自己的 pom.xml 缺了什么。

## 整体流程

```
本机：在 pom.xml 中配置打包插件 → mvn clean package → 在本机试运行
  ↓ scp 上传 jar
EC2：安装 Java → 前台试运行 → 配置 systemd，让它在后台持续运行
```

## 第 1 步：在 pom.xml 里配置 fat jar 打包

consumer 依赖了 `amqp-client`、Gson 等库。普通的 `mvn package` 打出来的 jar **不包含这些依赖**，放到 EC2 上运行时会报 `ClassNotFoundException`。所以需要用 `maven-shade-plugin` 把所有依赖一起打进一个 jar 里。

在 `<build><plugins>` 里加入：

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-shade-plugin</artifactId>
    <version>3.5.1</version>
    <executions>
        <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>
                <transformers>
                    <!-- 指定 main 方法所在的类，java -jar 运行时从这里启动 -->
                    <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                        <mainClass>com.skitrak.consumer.ConsumerApp</mainClass>
                    </transformer>
                    <!-- 合并 META-INF/services 文件，SLF4J 2.x 依赖它来找到日志实现 -->
                    <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
                </transformers>
                <filters>
                    <!-- 删除依赖库自带的签名文件，否则运行时可能报签名校验错误 -->
                    <filter>
                        <artifact>*:*</artifact>
                        <excludes>
                            <exclude>META-INF/*.SF</exclude>
                            <exclude>META-INF/*.DSA</exclude>
                            <exclude>META-INF/*.RSA</exclude>
                        </excludes>
                    </filter>
                </filters>
            </configuration>
        </execution>
    </executions>
</plugin>
```

需要根据你的项目修改的地方：

- **`<mainClass>`**：改成你的 consumer 里包含 `main` 方法的那个类，**要写完整的包名**。
- **Java 版本**：检查 pom.xml 里 `maven.compiler.release`（或者 `source` 和 `target`）的值。EC2 上安装的 Java 版本**必须大于等于**这个值。比如你用 17 编译，EC2 上装的是 11，运行时就会报 `UnsupportedClassVersionError`。

## 第 2 步：打包，并在本机试运行

```bash
mvn clean package
```

完成后，`target/` 目录下会有两个 jar：

- `consumer-1.0-SNAPSHOT.jar`：**包含所有依赖的 fat jar**，上传的就是这个。
- `original-consumer-1.0-SNAPSHOT.jar`：不含依赖的原始 jar，可以忽略。

具体的文件名取决于你 pom.xml 里的 `artifactId` 和 `version`。

**上传之前，一定要先在本机用 `java -jar` 运行一次**，确认打包没有问题：

```bash
RABBIT_HOST=localhost java -jar target/consumer-1.0-SNAPSHOT.jar
```

在 IDE 里能运行，**不代表**打出来的 jar 能运行，因为 IDE 会自动帮你处理 classpath。只要在本机能用 `java -jar` 正常启动，并且能连上本机的 RabbitMQ，放到 EC2 上就基本不会有问题了。

## 第 3 步：上传到 EC2

```bash
scp -i <key>.pem target/consumer-1.0-SNAPSHOT.jar ec2-user@<consumer 公网 IP>:~/consumer.jar
```

上传时顺便把它改名为 `consumer.jar`，这样之后更新版本时，systemd 的配置就不需要修改了。

## 第 4 步：在 EC2 上安装 Java，并在前台试运行

```bash
ssh -i <key>.pem ec2-user@<consumer 公网 IP>
sudo dnf install -y java-17-amazon-corretto-headless    # 版本号和你编译时使用的保持一致
java -version
```

**先在前台运行一次**，确认能连上 RabbitMQ：

```bash
RABBIT_HOST=<RabbitMQ 私有 IP> RABBIT_USER=skitrak RABBIT_PASS=<密码> java -jar consumer.jar
```

这时日志会直接打印在终端上，出了问题能马上看到。同时打开 RabbitMQ 的管理界面，确认 Connections 页面出现了一条来自 consumer 私有 IP 的连接，Queues 页面每个 resort 队列的 Consumers 一栏都显示 1。确认没问题之后，按 `Ctrl+C` 停止它。

如果连接失败，最常见的原因有：

- **超时（Connection timed out）**：安全组 `sg-rabbit` 没有允许 `sg-consumer` 访问 5672 端口。
- **ACCESS_REFUSED**：用户名或密码错误，或者用了 guest 用户。
- **连接被拒绝（Connection refused）**：IP 地址填错了，或者 RabbitMQ 没有在运行。

## 第 5 步：用 systemd 在后台持续运行

前台运行的问题是，一旦关闭 SSH，程序就会随之退出。所以确认可以正常运行之后，使用上一轮的 `consumer.service` 文件，把它交给 systemd 来管理：

```bash
sudo cp consumer.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now consumer
sudo systemctl status consumer          # 查看运行状态
journalctl -u consumer -f               # 实时查看日志
```

## 以后更新代码时

```bash
# 本机
mvn clean package
scp -i <key>.pem target/consumer-1.0-SNAPSHOT.jar ec2-user@<IP>:~/consumer.jar

# EC2
sudo systemctl restart consumer
```

注意，**重启 consumer 会清空内存中的所有状态**。所以在正式测试的过程中不要更新代码，或者每次更新之后都换一个新的 dayID 来测试，原因我们在讲对账时说过。

你把 pom.xml 粘贴过来之后，我可以帮你检查一下 mainClass、Java 版本和依赖的配置是否正确。

## User

```
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>skitrak</groupId>
    <artifactId>consumer</artifactId>
    <version>1.0-SNAPSHOT</version>

    <properties>
        <maven.compiler.source>11</maven.compiler.source>
        <maven.compiler.target>11</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <dependencies>
        <dependency>
            <groupId>com.rabbitmq</groupId>
            <artifactId>amqp-client</artifactId>
            <version>5.21.0</version>
        </dependency>
        <dependency>
            <groupId>com.google.code.gson</groupId>
            <artifactId>gson</artifactId>
            <version>2.10.1</version>
        </dependency>
    </dependencies>
</project>
```

## Claude

你的 pom.xml 本身没有错误，但缺少打包成可执行 jar 的配置，所以现在打出来的 jar 在 EC2 上是无法运行的。我在修改后的 `pom.xml` 里做了四处改动：

**1. 加入了 `maven-shade-plugin`（最关键的改动）。** 它会把 amqp-client、Gson 等所有依赖一起打进同一个 jar，并在 jar 里写明 main 方法所在的类，这样 `java -jar` 才能直接运行。

**2. 用 `<main.class>` 属性指定启动类。⚠️ 这一处你必须修改。** 我填写的 `skitrak.consumer.ConsumerApp` 只是一个占位符，要改成你实际包含 `main` 方法的那个类的**完整类名**（包名加类名）。如果写错了，运行时会报 `Could not find or load main class`。

**3. 加入了日志的实现。** amqp-client 依赖 SLF4J 的日志接口，但你没有添加具体的实现，运行时会打印一段 SLF4J 的警告，RabbitMQ 客户端自己的日志（比如连接断开、自动恢复的信息）也都会被丢弃。我同时声明了 `slf4j-api` 和 `slf4j-simple`，并让它们**使用同一个版本**。如果只添加 `slf4j-simple`，Maven 可能会选用 amqp-client 带来的另一个版本的 `slf4j-api`，版本不匹配时，日志实现依然无法生效。

**4. 两处小的改进。**

- `source/target` 改成了 `release`。只用 `source/target` 时，如果你用 JDK 17 编译，代码里无意中调用了 Java 17 才有的 API，编译依然会通过，但到了 Java 11 上运行时才会报错。`release` 会在编译时就检查出这类问题。
- 加了 `<finalName>consumer</finalName>`，打出来的文件名直接就是 `target/consumer.jar`，上传和配置 systemd 时都更方便。

## 接下来的操作

```bash
# 1. 本机打包，并试运行（RabbitMQ 也在本机时）
mvn clean package
RABBIT_HOST=localhost java -jar target/consumer.jar

# 2. 上传到 EC2
scp -i <key>.pem target/consumer.jar ec2-user@<consumer 公网 IP>:~

# 3. 在 EC2 上安装 Java（11 或 17 都可以，只要不低于 11）
sudo dnf install -y java-17-amazon-corretto-headless

# 4. 先在前台运行，确认能连上 RabbitMQ
RABBIT_HOST=<RabbitMQ 私有 IP> RABBIT_USER=skitrak RABBIT_PASS=<密码> java -jar consumer.jar

# 5. 确认没问题之后，用 systemd 让它在后台持续运行（使用前面的 consumer.service 文件）
```

还有一个前提需要确认：你的代码里是否已经通过 `System.getenv("RABBIT_HOST")` 来读取 RabbitMQ 的地址了？如果代码里还写死的是 `localhost`，那么第 4 步设置的环境变量不会产生任何作用，consumer 在 EC2 上依然会去连接它自己本机上的 RabbitMQ，从而连接失败。

## User

rabbitMQ在ec2上是否自带

## Claude

不自带。EC2 本质上就是一台只装了操作系统的“空”虚拟机（比如 Amazon Linux 2023），除了系统自带的基本工具，其他的软件都需要你自己安装，这和你在 A1 里自己安装 Java 和 Tomcat 是一样的。

## AWS 确实有托管的 RabbitMQ，但这次作业不能用

AWS 提供了一个叫 **Amazon MQ** 的服务，它可以替你运行和管理 RabbitMQ。但作业里写得很明确：

> *“This assignment uses RabbitMQ — not a managed queue”*
> *“Deploy RabbitMQ on a separate EC2 instance”*

作业的目的就是让你自己运维 RabbitMQ、自己处理连接和重连。而且 Amazon MQ 不在免费额度之内，所以这次要在一台 EC2 上**自己安装**。

## 推荐使用 Docker 安装

在 Amazon Linux 2023 上直接安装 RabbitMQ，需要先安装特定版本的 Erlang，版本对不上就会出问题，比较麻烦。用 Docker 的话，镜像里已经包含了所有需要的东西：

```bash
# 1. 安装并启动 Docker
sudo dnf install -y docker
sudo systemctl enable --now docker

# 2. 运行 RabbitMQ（带管理界面）
sudo docker run -d --name rabbitmq --restart unless-stopped \
  -p 5672:5672 -p 15672:15672 \
  -e RABBITMQ_DEFAULT_USER=skitrak \
  -e RABBITMQ_DEFAULT_PASS=<你的密码> \
  rabbitmq:4-management

# 3. 确认它正在运行
sudo docker ps
sudo docker logs rabbitmq        # 看到 "Server startup complete" 就说明启动成功了
```

第一次运行时，Docker 会从网上下载 RabbitMQ 的镜像，需要等待一两分钟。`--restart unless-stopped` 可以保证这台 EC2 重启之后，RabbitMQ 也会自动启动。

启动之后，在浏览器里打开 `http://<这台 EC2 的公网 IP>:15672`，用 skitrak 用户登录。记得先在安全组里对你的 IP 开放 15672 端口。

## 如果不想用 Docker

也可以选择 **Ubuntu** 的 AMI 来创建这台 EC2，然后按照 RabbitMQ 官网的安装文档，通过 apt 添加官方的软件源，安装 Erlang 和 `rabbitmq-server`。Ubuntu 有官方支持的安装步骤，比在 Amazon Linux 上直接安装顺利得多。不过这种方式的步骤更多，而且之后执行 `rabbitmqctl` 等命令的方式也和 Docker 版本略有不同。

除非你有特别的理由，否则用 Docker 就好。它最简单，而且在本机开发时也可以用完全相同的命令运行一个 RabbitMQ，让本机环境和 EC2 环境保持一致。

## User

我本地打包好了consumer.jar，请问我的consumer ec2怎么进行部署？

## Claude

下面是从零开始的完整步骤，按顺序执行即可。

## 第 0 步：开始之前，确认三件事

1. **consumer 的 EC2 实例已经创建好了**：t2.micro，Amazon Linux 2023，区域为 us-west-2，安全组的入站规则至少允许你的 IP 访问 22 端口。
2. **RabbitMQ 的安全组 `sg-rabbit` 允许 consumer 访问 5672 端口。** 入站规则的来源选择 consumer 所属的安全组，或者 consumer 的私有 IP。漏掉这一条，consumer 连接 RabbitMQ 时会一直超时。
3. **记下 RabbitMQ 那台机器的私有 IP**，形如 `172.31.x.x`，在 EC2 控制台里该实例的详情页面可以找到。

## 第 1 步：上传 jar（在本机执行）

```bash
scp -i <key>.pem target/consumer.jar ec2-user@<consumer 公网 IP>:~
```

## 第 2 步：登录并安装 Java（在 EC2 上执行）

```bash
ssh -i <key>.pem ec2-user@<consumer 公网 IP>

sudo dnf install -y java-17-amazon-corretto-headless
java -version          # 确认安装成功
ls -lh ~/consumer.jar  # 确认 jar 已经上传
```

## 第 3 步：在前台试运行

```bash
RABBIT_HOST=<RabbitMQ 私有 IP> RABBIT_USER=skitrak RABBIT_PASS=<密码> java -jar ~/consumer.jar
```

确认以下几点：

- 终端里没有出现报错，能看到你的程序打印的启动日志。
- 在 RabbitMQ 管理界面的 **Connections** 页面，出现了一条来自 consumer 私有 IP 的连接。
- 在 **Queues** 页面，`resort.1` 到 `resort.10` 的 **Consumers** 一栏都变成了 1。

确认无误之后，按 `Ctrl+C` 停止它。

## 第 4 步：配置 systemd，让它在后台持续运行

直接在 EC2 上创建服务文件：

```bash
sudo tee /etc/systemd/system/consumer.service > /dev/null << 'EOF'
[Unit]
Description=SkiTrak Consumer
After=network-online.target
Wants=network-online.target

[Service]
User=ec2-user
Environment=RABBIT_HOST=<RabbitMQ 私有 IP>
Environment=RABBIT_USER=skitrak
Environment=RABBIT_PASS=<密码>
ExecStart=/usr/bin/java -Xmx512m -jar /home/ec2-user/consumer.jar
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
```

记得先把里面的 `<RabbitMQ 私有 IP>` 和 `<密码>` 替换成真实的值。

然后启动它：

```bash
sudo systemctl daemon-reload            # 让 systemd 读取新的服务文件
sudo systemctl enable --now consumer    # 立即启动，并设置为开机自动启动
sudo systemctl status consumer          # 应该显示 active (running)
```

## 第 5 步：验证

```bash
journalctl -u consumer -f      # 实时查看日志，按 Ctrl+C 退出（consumer 会继续在后台运行）
```

再次检查 RabbitMQ 管理界面，确认连接和每个队列的 consumer 都在。

**测试自动重启**：

```bash
sudo kill -9 $(pgrep -f consumer.jar)    # 强制杀掉进程
sleep 6
sudo systemctl status consumer           # 应该又变回了 active (running)
```

作业要求的“auto-restart on failure”就是通过这个机制实现的，可以截图作为证据。

**测试开机自动启动**：在 EC2 控制台里 reboot 这台实例，等它启动完成后，再执行一次 `systemctl status consumer`。

## 常用的命令

| 目的 | 命令 |
|---|---|
| 查看状态 | `sudo systemctl status consumer` |
| 查看最近 100 行日志 | `journalctl -u consumer -n 100` |
| 停止 | `sudo systemctl stop consumer` |
| 重启 | `sudo systemctl restart consumer` |
| 修改了服务文件之后 | `sudo systemctl daemon-reload && sudo systemctl restart consumer` |

## 以后更新代码时

```bash
# 本机
mvn clean package
scp -i <key>.pem target/consumer.jar ec2-user@<consumer 公网 IP>:~

# EC2
sudo systemctl restart consumer
```

## 如果出了问题

| 现象 | 可能的原因 |
|---|---|
| 日志里出现 `Connection timed out` | 安全组没有允许 consumer 访问 RabbitMQ 的 5672 端口 |
| 日志里出现 `Connection refused` | IP 地址写错了，或者 RabbitMQ 没有在运行（在 RabbitMQ 那台机器上执行 `sudo docker ps` 检查） |
| 日志里出现 `ACCESS_REFUSED` | 用户名或密码错误 |
| 连接到了 `localhost` | 代码里没有读取 `RABBIT_HOST` 环境变量，仍然写死的是 localhost |
| `UnsupportedClassVersionError` | EC2 上的 Java 版本比编译时使用的版本低 |
| 状态一直在 activating 和 failed 之间反复切换 | 程序启动后立刻就崩溃了，用 `journalctl -u consumer -n 100` 查看具体的报错信息 |

## User

我希望rabbitMQ和consumer能开机自启，如何实现？

## Claude

这需要从两台机器分别来看，而且两者的机制不太一样。

## RabbitMQ：需要两层保障

RabbitMQ 运行在 Docker 容器里，所以开机后要它自动启动，需要两个条件同时满足：

```
机器开机 → ① Docker 服务自动启动 → ② Docker 自动启动 rabbitmq 容器
```

**① 让 Docker 服务开机自启：**

```bash
sudo systemctl enable docker
systemctl is-enabled docker      # 输出 enabled 就说明设置成功了
```

如果你之前执行的是 `enable --now docker`，这一步其实已经完成了。

**② 给容器设置重启策略。** 检查一下当前的设置：

```bash
sudo docker inspect -f '{{.HostConfig.RestartPolicy.Name}}' rabbitmq
```

如果输出的是 `unless-stopped` 或 `always`，就没有问题。如果输出的是 `no`，说明创建容器时没有加 `--restart` 参数，可以直接修改，不需要重新创建：

```bash
sudo docker update --restart unless-stopped rabbitmq
```

这两个值的区别在于：`unless-stopped` 表示，如果你曾经**手动执行过** `docker stop rabbitmq`，那么机器重启后它就不会自动启动；而 `always` 表示无论如何都会自动启动。一般情况下用 `unless-stopped` 就可以了，这样你主动停掉它时，它就不会擅自启动。

### 一个必须注意的坑：数据的持久化

只要还是**同一个容器**，重启后队列、用户和消息都还在。但如果你以后**删除并重新创建**了这个容器（比如升级版本或者修改参数），所有的数据都会丢失，包括通过环境变量创建的用户以外的所有配置，以及 durable 队列里尚未被消费的消息。

原因有两个：一是数据默认保存在容器内部；二是 RabbitMQ 是按照**主机名**来存放数据的，而 Docker 每次创建新容器时都会随机生成一个主机名，即使挂载了同一个目录，新的 RabbitMQ 也找不到原来的数据。

所以，建议现在就按下面的方式**重新创建一次**容器，固定主机名，并把数据目录挂载到一个 Docker volume 上：

```bash
sudo docker rm -f rabbitmq
sudo docker run -d --name rabbitmq --hostname rabbitmq \
  --restart unless-stopped \
  -v rabbitmq-data:/var/lib/rabbitmq \
  -p 5672:5672 -p 15672:15672 \
  -e RABBITMQ_DEFAULT_USER=skitrak \
  -e RABBITMQ_DEFAULT_PASS=<你的密码> \
  rabbitmq:4-management
```

重新创建之后，exchange 和队列会在 server-v2 或 consumer 启动时由代码自动声明，所以不需要手动重建。如果你用了 policy，记得重新执行一次 `set_policy` 命令。

## Consumer：systemd 的 enable 已经做到了

你在服务文件里写了 `WantedBy=multi-user.target`，并且执行了 `systemctl enable`，这就已经实现了开机自启。确认一下：

```bash
systemctl is-enabled consumer    # 输出 enabled
```

### 但这里有一个启动顺序的问题

两台机器是**同时**开机的，而 consumer 和 RabbitMQ 在不同的机器上，systemd 无法让 consumer “等 RabbitMQ 先启动”。所以很可能出现这样的情况：consumer 已经启动了，但 RabbitMQ 还没有准备好，于是 consumer 连接失败。

这时会发生什么，取决于你的代码：

- **如果连接失败后程序直接退出**：systemd 会因为 `Restart=always`，每隔 5 秒重新启动它一次，直到 RabbitMQ 准备好、连接成功为止。这样最终也能连上，只是日志里会出现几次失败的记录。
- **如果连接失败后程序没有退出，而是卡住了**：systemd 认为它还在正常运行，不会重启它。这样它就会永远处于没有连接的状态，这是最糟糕的情况。

为了让第一种情况更加稳妥，建议在服务文件的 `[Unit]` 部分加上一行：

```ini
[Unit]
Description=SkiTrak Consumer
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0
```

systemd 默认有一个“启动频率限制”：如果一个服务在短时间内重启的次数过多，systemd 就会放弃，不再重启它。`StartLimitIntervalSec=0` 的作用是取消这个限制，让它一直重试下去。修改之后执行：

```bash
sudo systemctl daemon-reload
```

**更好的做法是在代码里处理。** 我们在讨论自动恢复时提到过：RabbitMQ 客户端的自动恢复功能**只在第一次连接成功之后**才会生效，启动时的首次连接需要你自己处理。所以可以在 consumer 的 main 方法里，给第一次连接加上一个带指数退避的重试循环：连接失败就等待一段时间再试，直到成功为止。这样即使 RabbitMQ 晚启动了几分钟，consumer 也能自己连上，不需要依赖 systemd 反复重启它。

## 验证

在 EC2 控制台里**同时 reboot** 这两台机器，等它们都启动完成之后：

```bash
# RabbitMQ 那台
sudo docker ps                     # rabbitmq 容器的状态是 Up

# consumer 那台
sudo systemctl status consumer     # active (running)
journalctl -u consumer -b          # 查看本次开机以来的日志，看看是否有连接失败后又重试成功的记录
```

最后打开 RabbitMQ 的管理界面，确认 consumer 的连接已经恢复，每个队列的 Consumers 一栏都是 1。

## 补充一点

这里说的“开机自启”，是指**实例启动之后**，软件会自动运行起来。而 EC2 实例本身被 stop 之后，是不会自己重新启动的，需要你在控制台里手动执行 start。在 stop 再 start 之后，实例的公网 IP 会改变，但私有 IP 不会改变，所以 consumer 里配置的 RabbitMQ 私有 IP 依然有效，不需要修改。

## User

tomcat是不是也需要开机自启？不然我把server一个个手动启动tomcat吗

## Claude

对，Tomcat 也需要开机自启，而且对 server 来说**尤其重要**。原因有两个：

- **用 AMI 复制出来的新机器**，启动之后 Tomcat 必须自动运行，否则你每启动一台新的 server，都要 SSH 上去手动启动 Tomcat。
- **ALB 的健康检查**只会在 Tomcat 运行起来之后才能通过。只要有一台 server 重启后 Tomcat 没有自动运行，它就会一直被 ALB 标记为不健康，也就不会再接收任何流量。

具体的做法取决于你在 Lab 1 里是怎么安装 Tomcat 的。

## 先确认你的 Tomcat 是怎么安装的

```bash
systemctl list-unit-files | grep -i tomcat
```

- **有输出**（比如 `tomcat9.service`）：说明你是通过软件包安装的，系统已经自带了服务文件。直接执行 `sudo systemctl enable --now tomcat9` 就可以了，跳到后面的“验证”部分。
- **没有输出**：说明你是下载压缩包、解压到某个目录（比如 `/opt/tomcat`）后手动启动的，大多数课程实验都是这样安装的。这种情况下，需要自己写一个服务文件，和 consumer 的做法一样。

## 为手动安装的 Tomcat 编写 systemd 服务

### 1. 先停掉现在正在运行的 Tomcat

如果你之前是在 screen 里或者用 `startup.sh` 手动启动的，要先把它停掉，否则 systemd 启动 Tomcat 时，会因为 8080 端口已经被占用而失败：

```bash
/opt/tomcat/bin/shutdown.sh
```

### 2. 确认文件的所有者

如果你之前是用 `sudo` 启动 Tomcat 的，那么它生成的日志、临时文件等就都属于 root。等会儿用 ec2-user 的身份启动时，Tomcat 会因为没有写权限而无法启动。先统一修改一下文件的所有者：

```bash
sudo chown -R ec2-user:ec2-user /opt/tomcat
```

如果你的 Tomcat 不在 `/opt/tomcat`，就把下面所有的路径都替换成你实际的路径。

### 3. 找到 Java 的安装路径

```bash
dirname $(dirname $(readlink -f $(which java)))
# 例如输出：/usr/lib/jvm/java-17-amazon-corretto.x86_64
```

### 4. 创建服务文件

```bash
sudo tee /etc/systemd/system/tomcat.service > /dev/null << 'EOF'
[Unit]
Description=Apache Tomcat 9
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=0

[Service]
User=ec2-user
Environment=JAVA_HOME=<上一步得到的 Java 路径>
Environment=CATALINA_HOME=/opt/tomcat
Environment=CATALINA_BASE=/opt/tomcat
ExecStart=/opt/tomcat/bin/catalina.sh run
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
```

这里用的是 `catalina.sh run`，而不是 `startup.sh`。区别在于：`startup.sh` 启动 Tomcat 之后会立刻退出，让 Tomcat 在后台运行，systemd 需要额外的配置才能追踪到它；而 `catalina.sh run` 会让 Tomcat 在**前台**运行，systemd 可以直接管理这个进程，Tomcat 崩溃时也能正确地重启它。

### 5. 启动并设置开机自启

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now tomcat
sudo systemctl status tomcat       # 应该显示 active (running)
journalctl -u tomcat -f            # 查看实时日志
```

## RabbitMQ 的环境变量放在哪里

我们之前把 `RABBIT_HOST` 等变量写在了 `bin/setenv.sh` 里。`catalina.sh run` 在启动时依然会读取 setenv.sh，所以**不需要修改**，继续使用 setenv.sh 即可。

你也可以选择把它们写进服务文件的 `Environment=` 里，但**只能选择其中一个地方**。如果两个地方都写了，setenv.sh 里的值会覆盖服务文件里的值，以后排查问题时很容易混淆。

## 和 consumer 不同的一个问题：Tomcat 不会因为连不上 RabbitMQ 而重启

server 和 RabbitMQ 同时开机时，同样可能出现 Tomcat 先启动、RabbitMQ 还没准备好的情况。但这时的后果和 consumer **不一样**，而且更严重：

```
Tomcat 启动 → 部署 WAR → contextInitialized 连接 RabbitMQ 失败，抛出异常
→ 这个 WAR 部署失败，所有的 endpoint 都返回 404
→ 但 Tomcat 进程本身仍然在正常运行
→ systemd 认为一切正常，不会重启它
→ 这台 server 会一直处于“Tomcat 在运行、但应用不可用”的状态
```

consumer 连接失败时，进程会退出，systemd 就会重启它；但 Tomcat 的进程不会退出，所以 systemd 的重启机制**帮不上忙**。

解决办法是在代码里处理，而且这正好可以和我们在 A1 讨论 health check 时提到的“就绪检查”（readiness check）联系起来：

1. **`contextInitialized` 不要在连接失败时抛出异常**，而是启动一个后台线程，用指数退避的方式不断尝试连接 RabbitMQ，直到成功为止。这样 WAR 总能部署成功。
2. **在 RabbitMQ 连接成功之前**，`doPost` 直接返回 503，客户端会自动重试。
3. **`/health` 在 RabbitMQ 连接成功之前也返回 503**。这样 ALB 会把这台 server 标记为不健康，暂时不会向它转发流量；等它连接成功、`/health` 返回 200 之后，ALB 才会自动地把它重新加入。

这样一来，/health 就不再只是返回一个固定的 200，而是真正反映了这台 server 是否能够正常工作。这正是我们在 A1 里说的：“到了 Assignment 2，health check 就会检查依赖是否正常。”

## 验证

在 EC2 控制台里 reboot 这台 server，启动完成之后：

```bash
sudo systemctl status tomcat
curl -i http://localhost:8080/<app>/health
```

确认没有问题之后，**再创建 AMI**。这样，用这个 AMI 复制出来的所有 server，启动之后都会自动运行 Tomcat，也都会自动连接 RabbitMQ。

## User

接下来怎么做镜像？

## Claude

做镜像本身只需要点几下，但要先确认第一台 server 已经处于“可以被复制”的状态，否则复制出来的每一台机器都会带着同样的问题。

## 第 0 步：做镜像之前的检查

逐项确认第一台 server：

- [ ] **重启后 Tomcat 能自动运行**，`curl http://localhost:8080/<app>/health` 返回 200。
- [ ] **WAR 是当前最新的版本**。镜像是一张快照，之后对 WAR 的修改不会自动同步到复制出来的机器上。
- [ ] **`setenv.sh` 里使用的是 RabbitMQ 的私有 IP**，而不是公网 IP。私有 IP 在实例重启之后也不会改变。
- [ ] **serverId 来自主机名，而不是写死在配置里**。否则复制出来的 4 台机器都会使用同一个 serverId。
- [ ] **`/health` 在 RabbitMQ 连接成功之前返回 503**（上一轮提到的就绪检查）。这一条不是必须的，但强烈建议在做镜像之前完成。

可选的一步：清空 Tomcat 的旧日志（`rm /opt/tomcat/logs/*`），这样复制出来的机器的日志会从零开始，查看起来更清楚。

## 第 1 步：创建镜像

1. EC2 控制台 → **Instances** → 选中第一台 server。
2. **Actions → Image and templates → Create image**。
3. 填写：
   - **Image name**：比如 `skitrak-server-v2-v1`。建议带上版本号，以后更新时就可以创建 v2、v3。
   - **Reboot instance**：**保持勾选**（这是默认设置）。创建镜像时，AWS 会先重启这台实例，确保磁盘上的数据处于一致的状态。实例会短暂地停止服务一两分钟，然后自动恢复。
   - 存储设置：保持默认。
4. 点击 **Create image**。
5. 在左侧菜单中打开 **AMIs**，等待状态从 `pending` 变为 `available`，一般需要几分钟。

镜像底层是用 EBS 快照来存储的，会产生少量的存储费用（每 GB 每月几美分）。测试全部完成之后，记得在 AMIs 页面里 **Deregister** 这个镜像，并到 **Snapshots** 页面删除对应的快照。

## 第 2 步：用镜像启动新的 server

1. 在 **AMIs** 页面选中刚才的镜像 → **Launch instance from AMI**。
2. 填写：
   - **Name**：比如 `server-v2`。
   - **Instance type**：t2.micro。
   - **Key pair**：选择和之前相同的密钥。
   - **Network settings → Edit**：
     - **Subnet**：选择一个**在你的 ALB 所启用的可用区之内**的子网。这一点非常重要：如果某台 server 所在的可用区不在 ALB 启用的可用区列表里，它在目标组中的状态会显示为 `unused`，永远不会收到任何流量。
     - **Security group**：选择已有的 `sg-server`。
   - 页面右侧的 **Number of instances**：填 **3**。
3. 点击 **Launch instance**。

## 第 3 步：验证每一台新的 server

等每一台实例的 **Status check** 都显示为 `2/2 checks passed` 之后：

**① 直接访问每一台机器：**

```bash
curl -i http://<新机器的公网 IP>:8080/<app>/health
```

前提是 `sg-server` 允许你的 IP 访问 8080 端口。

**② 查看 RabbitMQ 的管理界面**，这一步能同时验证很多事情：

- **Connections** 页面：应该能看到来自 **4 个不同私有 IP** 的连接，也就是每台 server 一条（再加上 consumer 的一条）。
- **Queues** 页面：除了 resort 队列和死信队列之外，还应该看到 **4 个名字形如 `amq.gen-xxxx` 的队列**。它们就是每台 server 为了接收排行榜而创建的专属临时队列。点开 `leaderboard.fanout` 这个 exchange，它的 Bindings 一栏应该能看到这 4 个队列。**这就直接证明了每台 server 都接入了 backplane。**

**③ 确认 serverId 各不相同**：分别向每台机器直接发送一条 swipe，然后在 RabbitMQ 管理界面里用 Get messages 查看消息内容，`serverId` 字段应该是 4 个不同的主机名（形如 `ip-172-31-xx-xx`）。

## 第 4 步：把它们加入目标组

EC2 控制台 → **Target Groups** → 选择你的目标组 → **Targets** 标签页 → **Register targets** → 勾选需要加入的实例，端口填写 8080 → 点击 **Include as pending below** → **Register pending targets**。

等待一两分钟，每台机器的状态都应该变成 **healthy**。

测试单实例时，目标组里只保留 1 台；测试 2 台时注册 2 台；测试 4 台时注册全部 4 台。不需要停止其他的机器，只需要从目标组里**取消注册**（Deregister）就可以了，ALB 就不会再把流量转发给它们。

## 以后更新 WAR 时

修改代码之后，有两种更新方式：

**方式一：逐台重新部署**（适合调试阶段，速度快）。可以写一个小脚本，放进 `/deployment` 目录：

```bash
#!/bin/bash
# deploy-servers.sh：把新的 WAR 部署到所有 server 上
KEY=<key>.pem
WAR=target/<app>.war
SERVERS="<IP1> <IP2> <IP3> <IP4>"

for ip in $SERVERS; do
  echo "Deploying to $ip ..."
  scp -i $KEY $WAR ec2-user@$ip:/opt/tomcat/webapps/
  ssh -i $KEY ec2-user@$ip "sudo systemctl restart tomcat"
done
```

这里的 IP 用的是公网 IP，因为脚本是在你的电脑上运行的。注意，实例 stop 再 start 之后，公网 IP 会改变，脚本里的 IP 也要随之更新。

部署后**重启 Tomcat**，而不是依赖 Tomcat 的热部署。原因是你的代码里有 `static` 的线程池和 RabbitMQ 连接，热部署时，旧版本的线程和连接可能不会被完全清理，从而导致资源泄漏或者奇怪的问题。

**方式二：重新做镜像**（适合版本稳定之后）。把第一台 server 更新好，创建一个新的镜像（比如 `v2`），再用它启动新的实例，替换掉旧的实例。

在调试阶段，用方式一就可以了。等到最终版本确定下来、准备开始正式测试之前，可以再做一次新的镜像，作为一个干净的基准版本。

## User

我有点搞不清这个image到底是什么意思，我们先用本来就设置好的instance创建了“AMI”，然后再用这个AMI创建了3个instance，这三个instance和原版的instance是什么关系？

## Claude

可以把 AMI 理解成一个**模具**，或者说一份**磁盘的完整复印件**。

## AMI 到底是什么

一台 EC2 实例，可以粗略地分成两部分：

```
一台运行中的实例
 ├─ 磁盘：操作系统、安装好的 Java 和 Tomcat、WAR、setenv.sh、systemd 的配置……
 └─ 运行时的状态：内存里的数据、正在运行的进程、IP 地址、实例 ID……
```

**AMI 保存的就是磁盘那一部分**，相当于在某一时刻给整块磁盘拍了一张完整的快照。内存里的东西和正在运行的进程，都**不会**被保存下来。

用 AMI 启动一台新的实例，就是先把这张快照**复制一份**，作为新实例的磁盘，然后从这块磁盘开机。

## 四台机器之间是什么关系

```
                 原来的 server ──（创建 AMI 的那一刻，复印磁盘）──▶ AMI（模具）
                                                                  │
                                         ┌────────────────────────┼────────────────────────┐
                                         ▼                        ▼                        ▼
                                     新 server 1              新 server 2              新 server 3
```

**它们是彼此完全独立的“兄弟”，而不是“本体和分身”的关系。**

- **在创建 AMI 的那一刻**，四台机器磁盘上的内容是一模一样的。
- **从那之后**，它们各自独立地运行，互不影响：
  - 你修改了原来那台机器上的 WAR，另外三台**不会**跟着改变。
  - 你在新 server 1 上删除了一个文件，其他机器也**不会**受到影响。
  - 你把原来那台机器终止（terminate）了，另外三台依然正常运行，AMI 本身也不受影响。
  - 你删除了 AMI，已经启动的三台机器同样不受影响，只是以后不能再用它启动新的机器了。

用你熟悉的 Java 来类比的话，AMI 有点像一个**类**，用它启动的实例像是 `new` 出来的**对象**：每个对象创建时都拥有相同的初始状态，但之后每个对象的字段各自独立地变化。不过这个类比有一点不完全准确：修改类的代码会影响之后创建的新对象，而修改原来那台机器，**连之后**用这个 AMI 启动的机器也不会受到影响。因为 AMI 是一张已经拍好的照片，不会再改变。要让新机器包含新的修改，只能重新创建一个新的 AMI。

## 哪些一样，哪些不一样

| 一样的（磁盘上的内容） | 不一样的（每台实例独有的） |
|---|---|
| 操作系统，安装好的 Java、Tomcat、Docker | 实例 ID |
| 部署好的 WAR | 私有 IP 和公网 IP |
| setenv.sh 里的 RabbitMQ 地址 | **主机名**（由私有 IP 生成，比如 `ip-172-31-5-23`） |
| systemd 的服务文件，以及“开机自启”的设置 | 内存里的数据、正在运行的进程 |
| 可以用来 SSH 登录的密钥 | 实例类型、安全组（可以在启动时重新选择） |

## 这也解释了我们之前做的几个决定

把这张表和之前的步骤对照起来看，你会发现它们都是因为 AMI 的这个特性：

- **为什么 Tomcat 必须设置成开机自启？** 因为 AMI 只复制磁盘，**不复制正在运行的进程**。新机器开机时，Tomcat 并不会“继续运行”，它只能从零开始启动。而“开机时启动 Tomcat”这个设置是保存在磁盘上的，所以会被复制过去。
- **为什么 serverId 要用主机名？** 因为磁盘上的所有配置都是一模一样的，如果把 serverId 写在配置文件里，4 台机器的 serverId 也会一模一样。而主机名是由每台机器自己的私有 IP 生成的，天然就各不相同。
- **为什么 RabbitMQ 的地址可以直接复制？** 因为 4 台 server 要连接的都是**同一个** RabbitMQ，这个配置本来就应该完全一样。
- **为什么更新 WAR 时要逐台部署，或者重新创建 AMI？** 因为这几台机器之间没有任何联系，修改其中一台，不会自动同步到其他机器上。

## User

(base) dlwlrma@dlwlrmadeMacBook-Pro 6650 % curl -i http://$ALB/ski-servlet/health
HTTP/1.1 503 Service Temporarily Unavailable
Server: awselb/2.0
Date: Fri, 06 Nov 2026 02:25:47 GMT
Content-Type: text/html
Content-Length: 162
Connection: keep-alive

<html>
<head><title>503 Service Temporarily Unavailable</title></head>
<body>
<center><h1>503 Service Temporarily Unavailable</h1></center>
</body>
</html>
(base) dlwlrma@dlwlrmadeMacBook-Pro 6650 %

## Claude

从响应的内容可以看出，**这个 503 是 ALB 自己返回的，请求根本没有到达你的 Tomcat。** 判断依据有两个：

- 响应头里的 `Server: awselb/2.0`，表示这个响应是由 AWS 的负载均衡器生成的。
- 响应体是一段 **HTML**，内容是 ALB 默认的错误页面。而你自己的 servlet 返回的是 **JSON**，即使你的 `/health` 在 RabbitMQ 没连上时返回 503，内容也会是 JSON。

所以，问题出在 ALB 和目标组之间，和你的代码无关。

## ALB 返回 503 的含义

ALB 返回 503，通常表示：**它找不到任何可以转发请求的目标。** 最常见的原因按可能性排列如下。

### 原因 1：目标组里没有注册任何实例（最常见）

EC2 控制台 → **Target Groups** → 选择你的目标组 → **Targets** 标签页。如果列表是空的，就说明你还没有注册实例。注册方法是：**Register targets** → 勾选 server 实例 → 端口填写 **8080** → **Include as pending below** → **Register pending targets**。

很多人会在创建目标组时跳过“注册目标”这一步，打算之后再添加，结果忘记了。

### 原因 2：实例的状态显示为 `unused`

如果实例已经注册了，但状态显示为 **unused**，鼠标悬停在状态上，通常会看到提示：*Target is in an Availability Zone that is not enabled for the load balancer*。意思是，这台实例所在的可用区，不在 ALB 启用的可用区列表里。

解决方法有两种：

- ALB → **Network mapping** → **Edit subnets**，把这台实例所在的可用区的子网添加进去。
- 或者，在 ALB 已经启用的可用区里重新启动一台实例。

### 原因 3：监听器转发到了错误的目标组

ALB → **Listeners and rules** 标签页 → 查看 **HTTP:80** 这条监听器的默认动作，确认它转发到的是你注册了实例的那个目标组。如果你创建过多个目标组，很容易选错，而转发到一个空的目标组，就会得到 503。

### 原因 4：状态是 `initial`

刚注册的实例会先进入 `initial` 状态，等待最初几次健康检查的结果。按照作业要求的参数（间隔 30 秒，需要连续 2 次检查成功），从注册到变成 healthy，**至少需要一分钟左右**。稍等一下再试即可。

## 同时，绕过 ALB 直接测试 server

```bash
curl -i http://<server 公网 IP>:8080/ski-servlet/health
```

- 如果**能返回 200**，说明 server 本身是正常的，问题确实出在 ALB 的配置上。
- 如果**也不能访问**，就先解决 server 本身的问题（Tomcat 是否在运行、WAR 是否部署成功、安全组是否允许你的 IP 访问 8080）。

## 以后排查时可以对照这张表

ALB 返回的不同错误码，指向不同的问题：

| 状态码 | 含义 | 常见原因 |
|---|---|---|
| **503** | 没有可用的目标 | 目标组为空、实例状态为 unused、监听器转发到了错误的目标组 |
| **502** | 连接到了目标，但目标的响应有问题 | Tomcat 没有运行（连接被拒绝），或者在返回响应前关闭了连接 |
| **504** | 连接目标超时 | **`sg-server` 没有允许 `sg-alb` 访问 8080 端口**，或者 server 处理得太慢 |
| 返回 JSON 格式的 503 | 请求已经到达了你的代码 | 你的 `/health` 判断 RabbitMQ 还没有连接成功 |

还有一种情况值得了解：如果实例的状态是 **unhealthy**，ALB 通常**不会**返回 503。当一个目标组里**所有**的目标都不健康时，ALB 会采取“fail open”的策略，依然把请求转发给它们，所以你看到的会是 Tomcat 返回的结果，或者 502、504。所以，看到 ALB 自己返回的 503，基本可以确定是“没有注册目标”或者“目标不在启用的可用区内”。

你看一下目标组的 Targets 页面，实例目前是什么状态？如果显示的是 unhealthy，把鼠标悬停在状态上看到的具体原因也一起告诉我。

## User

从client->alb->server->broker基本上走通了，帮我写一个 Python 监控脚本，只用python自带的库。RabbitMQ ，管理接口端口 15672，用户名 skitrak，密码从环境变量 RABBIT_PASS 读，脚本在我的 Mac 上通过公网 IP 访问。每秒用 basic auth 请求一次 /api/queues，把 resort.1 到 resort.10 这 10 个队列的 messages、messages_ready、messages_unacknowledged、message_stats.publish_details.rate、message_stats.ack_details.rate 分别求和（队列空闲时 message_stats 可能不存在，按 0 处理），再单独记录死信队列 skitrak.dlqueue 的 messages，每次采样写一行到 results-v2/queue_时间戳.csv同时打印；命令行参数：--host 必填，--user 默认 skitrak，--password 取 RABBIT_PASS，--interval 默认 1 秒，--out 可选。

## Claude

`queue_monitor.py` 写好了，只用到了 Python 自带的库。我用一个模拟的管理接口测试过：求和结果正确，空闲队列缺少 `message_stats` 时按 0 处理，名字形如 `amq.gen-xxx` 的 backplane 临时队列不会被计入，密码错误和未设置密码时也会给出明确的提示。

## 用法

```bash
export RABBIT_PASS=<你的密码>
python3 queue_monitor.py --host <RabbitMQ 公网 IP>
```

其他参数：`--interval 0.5` 修改采样间隔，`--out xxx.csv` 指定输出文件，`--password` 可以覆盖环境变量里的密码。按 `Ctrl+C` 停止后，会打印一份汇总：队列深度的峰值和平均值、publish 和 ack 速率的峰值和平均值，以及死信队列里的消息数。这些正好对应作业要求收集的 queue metrics（peak/avg depth、producer rate、consumer rate）。

CSV 的每一列依次是：时间、毫秒时间戳、从启动开始经过的秒数、messages、ready、unacked、publish 速率、ack 速率、死信队列的消息数、找到的 resort 队列数。每写一行都会立即刷新到磁盘，所以即使脚本被强制终止，已经采集的数据也不会丢失。

## 几个需要确认的地方

**1. 死信队列的名字。** 你给的名字是 `skitrak.dlqueue`，而我们之前讨论时用的是 `skitrak.dlq`。我在脚本里按你给的名字写了，它是文件开头的一个常量 `DLQ_NAME`。如果脚本启动时提示 `dead-letter queue ... not found`，说明名字和你实际声明的不一致，修改这个常量即可。

**2. 安全组要允许你的 Mac 访问 15672 端口。** 如果 RabbitMQ 的安全组只对 server 和 consumer 开放了 5672，脚本会一直打印 `request failed: timed out`。

**3. 速率是 RabbitMQ 计算的平滑值。** `publish_details.rate` 和 `ack_details.rate` 是 RabbitMQ 自己根据最近几秒钟的数据计算出来的平均速率，并不是两次采样之间的精确差值。所以即使你每秒采样一次，曲线也会显得比较平滑，速率的变化会有几秒钟的滞后。用来画队列曲线和比较测试结果是完全足够的，但在报告里最好说明一下。

**4. ack 速率依赖手动 ack。** 你的 consumer 使用的是手动 ack，所以这一项会正常显示。如果某个队列的 consumer 使用的是 `autoAck=true`，RabbitMQ 不会统计它的 ack，这一列就会一直是 0。

另外，按照作业的要求，这个脚本应该放进仓库的 `/monitoring` 目录。

## User

有一个问题啊，比如我提前 30 秒启动脚本、测试跑了 60 秒、结束后又过了 30 秒才按ctrl C中断，平均深度被稀释了吧

## Claude

对，会被稀释。原来的汇总里，publish 和 ack 的平均值已经只计算了“活跃的样本”，但平均深度用的却是**全部样本**。按你说的情况（前 30 秒空闲、60 秒测试、后 30 秒空闲），空闲的 60 秒都是 0，平均深度会被拉低到真实值的一半左右。更新后的 `queue_monitor.py` 已经修正了这个问题。

## 修改的方法：只统计“活跃窗口”

汇总时，先找到**第一个**和**最后一个**有活动的样本，**只在这两者之间**计算所有的平均值（深度、publish 速率、ack 速率都用同一个窗口，口径保持一致）。

一个样本被认为是“有活动的”，条件是：**队列里有消息，或者 publish、ack 速率中有一个至少达到每秒 1 条**。

我用一组模拟的数据验证过（30 秒空闲 → 60 秒负载，中间有 3 秒的停顿 → 若干秒速率衰减 → 24 秒空闲）：

```
active window      : 30.0s .. 93.0s  (63.0s, 64 samples)
queue depth        : peak 400, avg 356.2
naive avg depth over all samples: 190.0      ← 修改前的算法
```

## 两个设计上的取舍

**1. 测试中间的短暂停顿会被保留。** 比如 warmup 和 main 阶段之间可能有一两秒钟没有任何流量。我选择用“从第一个到最后一个活跃样本”的整段时间，而不是把每个空闲的样本都剔除。因为这段停顿是测试过程的一部分，如果把它剔除，反而会让结果显得比实际情况更好。

**2. 速率衰减的尾巴，只保留了一小部分。** RabbitMQ 的速率是平滑计算出来的，测试结束后，它不会立刻降为 0，而是会在几秒钟内逐渐衰减。`ACTIVE_RATE = 1.0` 这个阈值会把衰减到每秒 1 条以下的那部分尾巴剔除掉。但在那之前，还有几秒钟会被计入窗口里（在上面的例子中是 4 秒），这会让平均值稍微偏低一点。如果你觉得影响太大，可以把文件开头的这个常量调高，比如设为 50。

## 另外修复了一个小问题

在测试中我发现：如果在脚本退出的过程中**又按了一次** Ctrl+C，汇总会因为第二次中断而无法打印出来。现在，收到第一次 Ctrl+C 之后，脚本会忽略后续的中断，保证 CSV 文件被正常关闭，汇总也能完整地打印出来。

## 汇总只是一个方便查看的结果

CSV 里保存的依然是**全部的原始样本**，包括空闲的时间段，`elapsed_s` 这一列也一直保留着。所以，如果你在写报告时想用别的时间窗口重新计算，比如只统计 main 阶段，可以直接根据 `elapsed_s` 截取对应的行再计算，不依赖于脚本打印出来的这份汇总。

## User

就是我client没在跑的时候可以正常访问rabbitMQ，然后一旦跑起来，我想看rabbitMQ，就显示Error: could not connect to server since 2026-11-05 19:05:50. Will retry at 2026-11-05 19:06:25

## Claude

这条提示来自 RabbitMQ **管理界面**：浏览器里的页面会每隔几秒向 15672 端口的管理接口请求一次数据，请求失败或者超时，页面就会显示这句话，并告诉你下一次重试的时间。

所以它说明的是：**管理接口在压测期间没有响应了。** 这不一定意味着 RabbitMQ 已经停止了消息的收发，AMQP（5672 端口）可能依然在正常工作，只是负责统计和展示的那部分已经处理不过来了。你的监控脚本在这时应该也会持续打印 `request failed: timed out`。

“没有负载时正常，一跑起来就无法访问”，这几乎可以肯定是 **RabbitMQ 所在的那台机器在负载下资源耗尽了**。

## 最可能的几个原因

**1. CPU 被占满，或者 CPU 积分耗尽了（最可能）。** t2.micro 只有 1 个 vCPU。压测时，RabbitMQ 要同时处理每秒几千条消息的路由、持久化写盘、publisher confirms，还有 consumer 的投递和 ack。管理接口的统计和请求处理优先级比较低，CPU 一紧张，它最先变得没有响应。如果 CPU 积分已经用完，这台机器只剩下 10% 的基准性能，情况会更糟。

**2. 内存不足。** 机器一共只有 1 GB 内存。如果 consumer 跟不上，队列开始积压，RabbitMQ 的内存占用会快速上升，可能触发**内存警报**（暂停接收所有的发布），在更严重的情况下，Linux 的 OOM killer 甚至会直接杀掉 RabbitMQ 的进程。

**3. 磁盘 I/O。** 持久化消息加上 publisher confirms，意味着 RabbitMQ 要频繁地把数据写入磁盘并等待写入完成。小容量的 EBS 磁盘在持续的高频写入下，I/O 能力也会成为瓶颈。

**4. 监控本身也在增加负载。** 管理界面和监控脚本都在请求管理接口，`/api/queues` 这个请求本身也需要 RabbitMQ 汇总所有队列的统计数据。如果浏览器里的管理页面和每秒一次的监控脚本同时在运行，开销会叠加。

## 在压测期间排查

在压测运行、管理界面无法访问时，SSH 登录到 RabbitMQ 所在的机器上，依次执行：

```bash
top                       # 看 CPU 使用率。重点看第三行的 %st（steal）
```

- 如果 `%Cpu(s)` 接近 100%，说明 CPU 被占满了。
- 如果 **`st`（steal）这一项很高**（比如几十个百分点），说明 CPU 积分已经用完，虚拟机正在被 AWS 限速。
- 如果 **`wa`（I/O wait）这一项很高**，说明瓶颈在磁盘上。

```bash
free -m                                                 # 剩余的可用内存
sudo docker stats rabbitmq --no-stream                  # 容器的 CPU 和内存占用
sudo docker exec rabbitmq rabbitmq-diagnostics alarms   # 是否触发了内存或磁盘警报
sudo docker exec rabbitmq rabbitmqctl list_queues name messages   # 不经过管理接口，直接查看队列深度
```

最后这条命令很有用：`rabbitmqctl` 不经过 HTTP 接口，在管理界面无法访问时，它往往依然可以使用。如果它显示的队列深度在**持续增长**，说明 consumer 跟不上生产的速度，问题会越来越严重。

测试结束后，还可以检查：

```bash
sudo docker ps                                  # STATUS 一栏，看容器是否刚刚重启过
sudo dmesg | grep -i -E "oom|killed process"    # 是否有进程被 OOM killer 杀掉
sudo docker logs rabbitmq 2>&1 | grep -i alarm  # 是否出现过内存警报
```

另外，在 AWS 控制台的 **CloudWatch** 里，查看这台实例在测试期间的 `CPUUtilization` 和 `CPUCreditBalance` 曲线。如果 `CPUCreditBalance` 在测试过程中降到了 0，那就是 CPU 积分耗尽导致的。

## 根据排查结果来解决

| 排查结果 | 解决方法 |
|---|---|
| CPU 占满，或者 steal 很高 | 把 RabbitMQ 换成更大的实例（比如 t3.small 或 t3.medium）。这是最直接的办法，但会产生费用，测试完记得改回去或者停掉 |
| 队列深度持续增长 | 问题出在 consumer 跟不上，需要优化 consumer（调整 prefetch、批量 ack），而不是 RabbitMQ 本身 |
| 触发了内存警报，或者出现了 OOM | 同样先检查队列是否在积压。另外，Docker 容器默认没有内存限制，可以考虑为 RabbitMQ 配置一个合适的内存阈值 |
| I/O wait 很高 | 持久化写盘的开销太大，可以在 EC2 控制台把磁盘类型改成 gp3，或者在报告中说明这是持久化带来的代价 |
| 资源都还有余量，只是管理界面没有响应 | 减少监控带来的负载：监控脚本的间隔改为 `--interval 5`，压测期间关闭浏览器里的管理页面，只保留一种监控方式 |

还有一个容易被忽视的原因：**检查 channel 是否被反复创建和关闭。** 在压测期间（如果还能访问的话），或者在测试刚开始时，查看管理界面里的 Channels 数量。正常情况下，它应该是一个稳定的数字（每台 server 的池大小，再加上 backplane 和 consumer 的 channel）。如果这个数字在不断变化，或者总数在持续增长，说明 ChannelPool 没有正常复用 channel。频繁地创建和关闭 channel 会给 RabbitMQ 带来非常大的额外开销。

## 这本身也是有价值的测试数据

作业要求在每次测试时都收集**系统指标**（CPU、内存、网络），并找出瓶颈所在。“RabbitMQ 所在的 t2.micro 在负载下资源耗尽，连管理接口都无法响应”，正是一个很好的瓶颈分析案例。把 `top` 的截图、CloudWatch 的曲线、队列深度的变化记录下来，在报告里分析瓶颈出在哪里，以及更换实例类型之后的效果对比，会是性能分析部分很有说服力的内容。

你可以在下次压测时执行一下 `top`，把结果告诉我，我们再一起判断具体是哪个原因。

## User

我第一次运行了一下，512线程，然后中间似乎出了一点问题，30-150秒吞吐量没有，你来推测一下可能是什么问题？===== Summary =====
samples            : 308  (written to results-v2/run1_queue.csv)
queue depth        : peak 98, avg 19.0 over 154 active samples
publish rate       : peak 4104.8/s, avg 3192.9/s over 154 active samples
ack (consume) rate : peak 3971.2/s, avg 3156.6/s over 154 active samples
dead-letter queue  : 0 messages at last sample
(base) dlwlrma@dlwlrmadeMacBook-Pro skitrak %

## Claude

从数据来看，这不是一个“容量不足”的问题，而是一次**持续约 110 秒的整体卡死，之后系统又完全恢复了正常**。系统在恢复之后，用同样的 512 个线程稳定地跑出了每秒约 3650 条的吞吐量，比 warmup 阶段还高得多。所以，系统在正常状态下是能够承受这个负载的，问题出在 main 阶段**刚开始的那段时间**。

下面是我根据数据做出的推测，最后附上验证的方法。

## 时间线

| 时间 | 吞吐量 | 发生了什么 |
|---|---|---|
| 0 – 23s | 约 1400/s | warmup 阶段，32 个线程，一切正常 |
| 23 – 30s | 533/s | main 阶段开始，并发数从 32 突然增加到 512 |
| 30 – 140s | **几乎为 0** | 卡死，持续大约 110 秒 |
| 140 – 150s | 313/s | 开始恢复 |
| 150 – 270s | 约 3650/s | 完全恢复，并且一直保持稳定 |

## 数据里的五个线索

**线索 1：卡死期间，没有任何消息到达 RabbitMQ。** 你的监控汇总显示，308 个样本里只有 154 个是“活跃”的。这大致对应着卡死前后那两段正常运行的时间（约 25 秒加上约 130 秒）。同时，队列深度的峰值只有 98，平均值是 19，consumer 完全跟得上。所以 **RabbitMQ 和 consumer 都不是这次问题的原因**。问题出在 RabbitMQ 的上游：要么请求没有到达 server，要么 server 收到了请求，却卡在了发布消息之前或者发布消息的过程中。

**线索 2：最长的一次请求耗时 150 秒。** 这说明有请求在**某处一直挂着、没有得到任何响应**，而不是很快地失败。如果 server 能够快速返回 503，客户端很快就会重试，不会有请求挂上 150 秒。

**线索 3：卡死的时间长度，接近 ALB 空闲超时的时长。** 如果你按照我们之前讨论的，把 ALB 的 idle timeout 设成了 120 秒，那么这个巧合就很值得注意：如果客户端**没有设置**响应超时，那么挂起的请求就会一直等待，直到 ALB 等了 120 秒还没有收到 server 的响应，主动返回 504 并断开连接。这样，卡死的时长就是由 ALB 的超时决定的。

**线索 4：重连了 490 次，几乎每个 main 阶段的连接都出错了一次。** 一共新建了 1002 条连接，等于 32（warmup）+ 约 480（池扩大到 512）+ 490（重连）。这说明在卡死的那段时间里，**几乎所有正在进行的请求都同时出错了**，连接被关闭，之后全部重新建立。这和“一大批请求同时挂起，又同时超时”的情况完全吻合。

**线索 5：排行榜的推送也被延迟了大约 100 秒。** 推送延迟的 P95 是 80 秒，最大值是 107 秒，和卡死的时长基本一致。在卡死期间，consumer 收不到新的 swipe，也就不会生成新的排行榜。所以这些延迟严重的推送，应该是**卡死之前就已经生成的排行榜，在卡死结束之后才被送到客户端**。这说明 server 的 **WebSocket 推送也一起卡住了**，而不仅仅是 ingest 的 HTTP 请求。

## 推测：最可能的原因

把这些线索合在一起：

> 在 main 阶段开始、并发数从 32 猛增到 512 的时候，server-v2 进入了一种“不再响应”的状态：既不处理 HTTP 请求，也不推送 WebSocket 消息。挂起的请求一直到 ALB 或客户端超时才被释放，之后客户端的重试成功，server 也恢复了正常。

为什么 server 会整体卡住，我能想到三种可能，按可能性排列如下：

**可能性 A：Tomcat 的 JVM 内存不足，陷入了频繁的垃圾回收。** 没有特别配置时，JVM 的最大堆内存默认只有物理内存的 1/4，t2.micro 只有 1 GB 内存，算下来只有大约 250 MB。512 个并发请求同时到达，每个请求都要创建对象、序列化 JSON，再加上 RabbitMQ 客户端和 WebSocket 的缓冲区，堆内存很可能会接近上限。这时 JVM 会花费几乎所有的时间来做垃圾回收，**整个进程看起来就像卡死了一样**：HTTP 请求和 WebSocket 推送都会停止。等到请求因为超时被释放、内存压力下降之后，它又会恢复正常。这种可能性是唯一能同时解释“HTTP 请求卡住”和“WebSocket 推送也卡住”的情况。

**可能性 B：ChannelPool 被耗尽，Tomcat 的线程全部卡在等待 channel 上。** 如果池里的 channel 数量远少于 Tomcat 的 200 个工作线程，而你的 `borrowChannel` 使用的是 `take()`（没有超时），那么所有的 Tomcat 线程都会排队等待 channel。如果某些异常路径上没有归还 channel（比如没有写在 `finally` 里），或者关闭的 channel 被丢弃后没有补充新的，池会越来越小，最终所有的线程都被卡住。不过，这种情况不太能解释 WebSocket 推送也被延迟的现象，因为推送不需要 Tomcat 的工作线程。

**可能性 C：RabbitMQ 阻塞了 server 的连接。** RabbitMQ 在触发内存或者磁盘警报时，会阻塞所有正在发布消息的连接，这时 `basicPublish` 会一直挂起，直到警报解除。你上一次遇到的“管理界面无法访问”的问题，说明 RabbitMQ 那台机器的资源确实很紧张。但这次的队列深度很低，所以这个可能性相对较小。

## 怎么验证

**第 1 步：查看卡死期间的请求返回了什么（最有用的一步）。** 你已经有了 `results-v2/allswipes.csv`，统计一下卡死期间（30 到 140 秒之间）各种状态码的数量：

```bash
# 假设第 1 列是时间戳（毫秒），第 4 列是状态码；根据你的 CSV 格式调整
awk -F, 'NR==2{t0=$1} NR>1 && ($1-t0)/1000>=25 && ($1-t0)/1000<=145 {print $4}' \
  results-v2/allswipes.csv | sort | uniq -c
```

| 结果 | 说明 |
|---|---|
| 大量的 **504** | ALB 等待 server 超时，说明 server 收到了请求，但一直没有返回响应。支持可能性 A 或 B |
| 大量的 **503（JSON 格式的）** | server 的代码判断 channel 借不到，或者 RabbitMQ 不可用。支持可能性 B 或 C |
| 大量的 **502** | server 主动断开了连接，或者 Tomcat 崩溃了 |
| 大量的异常，或者 -1 之类的状态码 | 客户端自己的超时触发了，没有收到任何响应 |

**第 2 步：查看 server 的日志。**

```bash
journalctl -u tomcat --since "2026-11-05 19:00" --until "2026-11-05 19:15" | \
  grep -i -E "outofmemory|exception|blocked|timeout" | head -50
```

重点关注：`OutOfMemoryError`（可能性 A）、`TimeoutException` 或者借不到 channel 的日志（可能性 B）、`connection.blocked` 或 `AlreadyClosedException`（可能性 C）。

**第 3 步：查看 CloudWatch 的指标。** 在 EC2 控制台里，查看 server 实例在这个时间段的 `CPUUtilization`；在 ALB 的 **Monitoring** 标签页里，查看 `HTTPCode_ELB_5XX_Count`、`HTTPCode_Target_5XX_Count` 和 `TargetResponseTime`。如果 `TargetResponseTime` 在那段时间里接近 120 秒，就说明 server 确实一直没有响应，直到 ALB 超时为止。

**第 4 步：查看 RabbitMQ 的日志。**

```bash
sudo docker logs rabbitmq 2>&1 | grep -i -E "alarm|blocked"
```

如果没有任何输出，就可以排除可能性 C。

## 下次测试之前，建议先做的修改

1. **给客户端设置响应超时**，比如 10 秒。这样，即使 server 卡住了，客户端也能在 10 秒后就开始重试，而不是等待 ALB 的 120 秒超时。卡死时浪费的时间会大大减少，而且更容易看出问题发生的确切时间。
2. **在 Tomcat 上开启 GC 日志，并明确设置堆的大小**。在 `setenv.sh` 里加上：
   ```bash
   export CATALINA_OPTS="-Xms512m -Xmx512m -Xlog:gc*:file=/opt/tomcat/logs/gc.log:time"
   ```
   如果下次再出现卡死，查看 `gc.log` 就能直接确认或者排除可能性 A。
3. **先用 128 或 256 个线程测试一次**，看看卡死的现象是否还会出现。如果并发数较低时不会出现，说明问题确实和 main 阶段开始时并发数的突然增加有关。

## 顺便发现的两个问题

**1. 推送延迟的中位数是 -119ms，说明你的 Mac 和 EC2 之间存在明显的时钟偏差。** 负数说明你的 Mac 的时钟比 EC2 慢了一百多毫秒。这比 NTP 同步之后通常的偏差大得多，可能是你的 Mac 已经有一段时间没有同步时间了。可以执行 `sudo sntp -sS time.apple.com` 同步一次，然后用我们之前讨论的 Cristian 算法估算一下偏差。否则推送延迟的数据会被这个偏差严重干扰。

**2. 有 37 条 swipe 在重试 5 次后依然失败。** 它们应该都是在卡死期间失败的。如果你打算做榜单的对账，要注意：其中一部分请求，可能 server 已经把消息成功发布到了 RabbitMQ，只是响应没能返回到客户端。所以客户端认为它们失败了，但 consumer 实际上已经把它们累加进去了。这时对账会出现“实际值比期望值大”的差异，这正是我们之前讨论 eventId 时提到的那种情况。

你先做第 1 步，把状态码的统计结果告诉我，基本就能判断是哪种可能性了。

## User

会不会是我的mac连接alb出现问题？

## Claude

很有可能就是这样。而且你贴的这份监控数据里，恰好有一个能支持这个判断的关键证据：**卡死的那段时间里，监控脚本自己也失联了。**

## 关键证据：监控数据中间断了两分多钟

看这两行：

```
2026-11-05T19:31:11.465, ..., elapsed 58.047
2026-11-05T19:33:18.125, ..., elapsed 184.710
```

脚本本来每秒采样一次，但在这两行之间，**整整 127 秒没有任何一条记录**。脚本只会在请求成功时写入一行，所以这段时间里，它对 RabbitMQ 管理接口的请求一直在失败（终端里应该打印了一串 `request failed: timed out`）。

而这个脚本的请求路径是 **Mac → RabbitMQ 的公网 IP**，完全不经过 ALB，也不经过 server。

## 时间完全对得上

把监控的时间和客户端的时间对齐来看：

| 时间点 | 监控数据 | 客户端 |
|---|---|---|
| 19:30:54 | publish 速率开始出现，说明客户端在这时启动 | warmup 开始 |
| 约 19:31:15 | **最后一次成功采样是 19:31:11** | warmup 结束（约 23 秒），**main 阶段开始，一下子新建了大约 480 条连接** |
| 19:31 – 19:33 | **127 秒没有任何数据** | 吞吐量接近 0 |
| 19:33:18 | 采样恢复 | 客户端时间的 140 到 150 秒，吞吐量开始恢复 |

监控失联开始和结束的时间，与客户端卡死开始和结束的时间几乎完全重合。

## 这说明了什么

在同一时刻，你的 Mac 上**两个互不相关的程序**，访问**两个不同的 AWS 地址**（ALB 和 RabbitMQ），同时出现了问题，又同时恢复。

server、ALB 或者 RabbitMQ 中的任何一个单独出问题，都无法解释这个现象。比如，即使 server 完全卡死了，也不会影响监控脚本直接访问 RabbitMQ。最合理的解释是：**问题出在它们共同经过的那一段，也就是你的 Mac 和你所在的网络。**

而触发它的，很可能正是 main 阶段开始时那一瞬间**同时新建的大约 480 条 TCP 连接**。常见的原因有以下几种：

- **家用路由器扛不住瞬间的大量新连接。** 家用路由器的 NAT 连接表容量很小，很多路由器还自带“防 SYN 洪水攻击”之类的保护功能。短时间内突然出现几百条新连接，可能会让路由器的 NAT 表被占满，或者触发它的保护机制，暂时丢弃所有新的流量，过一两分钟后才恢复。
- **Mac 上运行的代理或 VPN 软件。** 如果你的 Mac 上开着系统代理、VPN，或者某种代理工具的 TUN 模式（它会接管所有的流量），那么客户端的这几百条连接，以及监控脚本的请求，都要先经过这个软件。它一旦被瞬间的大量连接压垮，所有的流量都会一起卡住。
- **Wi-Fi 网络本身不稳定**，碰巧发生在这个时间点上。这种可能性比较小，因为时间点和 main 阶段开始的时刻吻合得太好了。

## 我需要纠正前面的两个判断

**1. 上一轮，我推测是 server 卡死**（JVM 内存不足、ChannelPool 被耗尽，或者 RabbitMQ 阻塞了连接）。现在看来，这些可能性都大大降低了。之前的那些线索，用“Mac 的网络中断了大约两分钟”来解释，也同样说得通，而且更加合理：

- **最长的请求耗时 150 秒**：请求在网络中断期间一直挂着，直到超时为止。
- **490 次重连**：网络中断导致所有正在使用的连接都断开了，需要重新建立。
- **排行榜推送延迟了约 100 秒**：WebSocket 连接也是从 Mac 连出去的，推送的消息在网络中断期间被积压在 server 一侧，等到网络恢复后才被送到客户端。

**2. 再往前一次，你说“客户端运行时，管理界面就无法访问”，我推测是 RabbitMQ 那台机器的资源耗尽了。** 但你访问管理界面用的浏览器，同样也在这台 Mac 上。结合这次的数据，那很可能也是**同一个问题**：客户端运行时，你的 Mac 的网络出了状况，而不是 RabbitMQ 本身有问题。这次的监控数据也表明，在网络正常的时候，队列深度只有一二十条，publish 和 ack 的速率基本相同，RabbitMQ 和 consumer 的状态都很健康。

## 怎么确认

**1. 查看 ALB 的 CloudWatch 指标（最直接的证据）。** 在 EC2 控制台 → Load Balancers → 选中你的 ALB → **Monitoring** 标签页，把时间范围调到 19:30 到 19:36，查看 **RequestCount** 和 **HTTPCode_ELB_5XX_Count**：

- 如果 19:31 到 19:33 之间，**RequestCount 降到了接近 0，并且没有出现 5XX 错误**，说明请求根本没有到达 ALB，问题就在客户端这一侧。
- 如果这段时间里出现了大量的 5XX，说明请求到达了 ALB，但是 server 没能正常响应，那就要回到上一轮的那几种可能性。

**2. 下次测试时，同时运行一个“对照组”。** 在另一个终端窗口里，持续 ping 一个和 AWS 无关的地址：

```bash
ping 8.8.8.8
```

如果在客户端卡死时，这个 ping 也同时开始超时，就可以确定是你本地的网络出了问题。

**3. 检查一下有没有开着 VPN 或者代理软件。** 如果有，先关掉它，再测试一次，看看问题是否还会出现。

## 怎么解决

**最推荐的做法：把客户端放到 us-west-2 的一台 EC2 上运行。** 这样做一举多得：

- **完全绕开你本地的网络**，包括路由器、代理软件和 Wi-Fi。
- **吞吐量会显著提高。** 现在稳定在每秒 3650 条时，按照 Little's Law，`512 / 3650 ≈ 140ms`，每个请求的平均延迟有 140 毫秒，其中大部分是跨越公网的网络延迟。客户端放在同一个区域内，这个延迟会降到几毫秒，你测出来的才是**服务器端真正的处理能力**。而作业中 1 台、2 台、4 台 server 的对比测试，比较的正是服务器端的处理能力。
- **时钟偏差也会大大减小。** 上一轮发现的 -119ms 的时钟偏差，主要来自你的 Mac。两台 EC2 都通过 Amazon Time Sync Service 同步时间，偏差通常在 1 毫秒以内，推送延迟的数据也会变得可信。

**如果暂时还想在 Mac 上测试**，可以让 main 阶段的线程**逐步启动**，而不是同时启动 480 个。比如每 100 毫秒增加 16 个线程，几秒钟之内就能达到 512 个。这样，新连接是一批一批建立的，就不会在一瞬间压垮路由器或者代理软件。

最后，监控脚本可以继续在你的 Mac 上运行，它每秒只发送一次请求，本身不会造成问题。只是要记住，如果客户端仍然在 Mac 上运行，一旦你的本地网络出现问题，监控也会跟着失联。

## User

server-1 19.4% 23.96credit  [ec2-user@ip-172-31-47-254 ~]$ free -m
sudo grep -i -E "OutOfMemory|SEVERE" /opt/tomcat9/logs/catalina.out | tail -20
               total        used        free      shared  buff/cache   available
Mem:             913         358          76           0         477         421
Swap:              0           0           0
12-Sep-2026 03:00:45.431 SEVERE [main] org.apache.catalina.valves.RemoteCIDRValve.setAllow .*: The address [.*] is not valid
12-Sep-2026 03:00:45.458 SEVERE [main] org.apache.catalina.startup.HostConfig.deployDirectory Error deploying web application directory [/opt/tomcat9/webapps/manager] ALB： target response time最高120.1ms,  connection error:No data available. Load Balancers ELB 5XXs: 1

## Claude

这组数据基本可以**排除 server 端的问题**，进一步证实了是你本地网络的问题。逐项来看：

| 指标 | 你的数据 | 说明 |
|---|---|---|
| server 的 CPU | 19.4% | 远远没有达到上限 |
| CPU 积分 | 23.96 | 还有剩余，没有被耗尽，所以没有被限速 |
| 内存 | 可用 421 MB | 没有内存压力 |
| catalina.out | 没有 OutOfMemory | JVM 没有出现内存不足 |
| ALB 的 Target Response Time | **最高 120.1 毫秒** | server 处理每个请求最慢也只用了 0.12 秒，**从来没有卡住过** |
| Target Connection Errors | 没有数据（即 0） | ALB 连接 server 时没有出现任何失败 |
| ELB 5XX | 1 | 整个测试期间只有 1 个错误，可以忽略 |

最关键的是 **Target Response Time 的最大值只有 120 毫秒**。如果 server 真的卡住了一两分钟，这个指标应该会出现接近 120 秒的值，因为 ALB 会一直等待 server 的响应。所以，我上一轮提出的 JVM 内存不足、ChannelPool 被耗尽、RabbitMQ 阻塞连接这三种 server 端的可能性，现在都可以排除了。

结合监控脚本那 127 秒的数据空白，结论已经很清楚了：**在那段时间里，请求根本没有从你的 Mac 发出去，或者在到达 ALB 之前就被阻断了。server 和 ALB 自始至终都是正常的。**

如果想得到最后的确认，可以再看一下 ALB 的 **RequestCount** 指标：19:31 到 19:33 之间，它应该会降到接近 0。这能直接说明在那段时间里，请求没有到达 ALB。

## 这组数据还揭示了另一件事

你的系统在稳定运行时，每秒能处理约 3650 条请求，而 server 的 CPU 只用了不到 20%。根据 Little's Law：

```
512 个线程 / 3650 条每秒 ≈ 140 毫秒（每个请求的平均延迟）
```

而 ALB 统计的 server 处理时间，**最慢**的才 120 毫秒，平均值一般只有几毫秒。这说明，这 140 毫秒的大部分时间，都花在了**你的 Mac 和 us-west-2 之间的公网传输上**，而不是在 server 上。

换句话说，**你现在测量的瓶颈是你家的网络，而不是你的系统。** server 还有很大的余量。这也是为什么我建议把客户端放到 EC2 上运行：只有这样，1 台、2 台、4 台 server 之间的对比才有意义。否则，无论有多少台 server，吞吐量都会被同样的网络延迟限制住，测出来的结果会差不多。

## 把客户端放到 EC2 上时要注意

客户端本身也需要资源。512 个线程，加上记录每个请求的数据、写 CSV，在只有 1 个 vCPU 的 t2.micro 上，**客户端自己可能就会成为新的瓶颈**。所以：

- 运行时，在客户端的机器上用 `top` 观察一下。如果 CPU 一直接近 100%，说明瓶颈在客户端，需要换一台更大的实例（比如 t3.medium 或 c5.large，测试完立刻停掉，费用很低）。
- 同时，这也意味着你的线程数需要重新调整。客户端和 server 在同一个区域时，网络延迟很低，可能 64 或 128 个线程就足以达到很高的吞吐量了。作业要求测试 64、128、256、512 个线程，正好可以用来找出新的最佳值。

## 顺便说一下日志里的那两条 SEVERE

这两条报错的时间是 **9 月 12 日**，和这次的测试无关。它们来自你在 Lab 1 里配置的 Tomcat **manager** 应用：`RemoteCIDRValve` 需要的是 CIDR 格式的地址（比如 `0.0.0.0/0`），而配置里写的是正则表达式 `.*`，所以 manager 应用部署失败了。它不影响你的 `ski-servlet`。如果你用不到 manager 应用，可以直接忽略；如果想修复，就把 `webapps/manager/META-INF/context.xml` 里那一行的 `allow` 改成 CIDR 格式的地址。

## User

ALB如果放着不删，放两个月需要多少费用？我总额度50块

## Claude

按 us-west-2 的标准价格估算，**放两个月大约要 47 美元左右，几乎会用光你的 50 美元额度**，所以不建议一直留着。

## 费用的组成

ALB 的费用由三部分组成。前两部分是 ALB 本身的价格：在美国东部区域，ALB 每小时收费 0.0225 美元，相当于每月约 16.43 美元，另外每个 LCU 每小时再收 0.008 美元。us-west-2 的价格一般和美国东部相同，但最好用 AWS 的价格计算器确认一下。第三部分是很多人会忽略的公网 IP 费用：公网 IPv4 地址每个每小时收费 0.005 美元，每个公网负载均衡器在每个可用区各有一个。你的 ALB 至少启用了 2 个可用区，所以就有 2 个公网 IP。

两个月大约是 1464 个小时：

| 项目 | 计算 | 费用 |
|---|---|---|
| ALB 的小时费 | 1464 小时 × 0.0225 美元 | 约 32.9 美元 |
| 2 个公网 IPv4 地址 | 2 × 1464 小时 × 0.005 美元 | 约 14.6 美元 |
| LCU（容量单位） | 空闲时几乎为 0，只在压测的那几个小时里产生少量费用 | 不到 1 美元 |
| **合计** | | **约 47 到 48 美元** |

关键在于，**ALB 即使没有任何流量，也会按小时计费**，只要它存在就会一直收费。

如果你说的 50 块是人民币，那么不到一个月的时间就会超出额度。

## 实际上，你不需要放两个月

今天是 11 月 5 日，作业的截止日期是 **11 月 8 日**，只剩下 3 天了。保留 ALB 到截止日期，大约是 72 小时 × (0.0225 + 0.01) 美元 ≈ **2.3 美元**，完全可以接受。

**建议的做法**：保留它，直到你完成 1 台、2 台、4 台 server 的所有测试，并截好图（ALB 的监控指标、请求的分布情况）。提交作业之后，**立刻删除 ALB**。

如果之后的作业（比如 Assignment 3 或 4）还需要用到 ALB，可以到时候再重新创建，大约只需要 5 分钟。**目标组（target group）本身不收费**，可以保留，重新创建 ALB 时直接选择它即可，健康检查和粘性会话的配置都不需要重新设置。

## 别忘了其他也在持续收费的资源

除了 ALB，你的账户里还有几项资源也在花钱：

- **正在运行的 EC2 实例**：如果你的账户已经不在免费额度范围内，每台 t2.micro 大约每小时 0.0116 美元。6 台机器同时运行，两个月下来大约是 100 美元。而且，每台正在运行的实例的公网 IP，也要按照上面的价格额外收费。**不测试的时候，一定要 stop 掉这些实例。**
- **EBS 磁盘**：实例 stop 之后，**磁盘依然会收费**，每台 8 GB 的磁盘每月大约 0.6 到 0.8 美元。6 台机器加起来每月几美元，金额不大，但不会自动停止。只有 terminate（终止）实例，才会删除它的磁盘。
- **AMI 的快照**：只要镜像存在，它底层的快照就会持续收费。确定不再需要之后，记得先 Deregister 镜像，再删除对应的快照。
- **没有绑定到实例上的 Elastic IP**：如果你创建过 Elastic IP 但没有使用，它也会按小时收费。

## 两件现在就可以做的事

1. **查看你当前已经花了多少钱。** AWS 控制台 → **Billing and Cost Management** → **Bills**。同时查看一下 **Free Tier** 页面，确认你的账户还剩下哪些免费额度。不同时间注册的账户，免费额度的规则也不一样。有些较早注册的账户，在注册后的第一年里，每个月有 750 小时的负载均衡器免费额度，这样 ALB 的小时费就是免费的。
2. **设置一个预算警报。** 在 **Budgets** 页面创建一个预算，比如设定为 30 美元，并设置在达到 80% 时发送邮件通知你。这样即使你忘了删除某个资源，也能在费用失控之前收到提醒。

## User

为什么会让学校的网启动保护？我们的client做了什么？

## Claude

从网络管理者的角度看，你的客户端在那一刻的行为，**和一台被入侵、正在对外发动 DoS 攻击的电脑几乎一模一样**。下面先说客户端具体做了什么，再说学校网络为什么会对此做出反应。

需要先说明一点：我没法直接看到学校网络的配置，下面是根据现象做出的推断。但“这台电脑的**所有**流量突然中断大约两分钟，然后自动恢复”，这种表现和校园网常见的“自动封禁异常主机一段时间”的机制非常吻合。

## 从网络的角度看，你的客户端做了什么

**1. 在不到一秒钟内，发起了大约 480 个新连接。**
warmup 阶段只有 32 个线程，一共 32 条连接，一切正常。main 阶段一开始，512 个线程几乎同时启动，每个线程都需要一条连接，于是连接池在瞬间从 32 条扩大到 512 条。在网络设备看来，就是这台电脑在一秒钟之内，向同一个目标地址的同一个端口，发出了几百个 TCP 建连请求（SYN 包）。这正是 **SYN 洪水攻击**的典型特征。

**2. 持续地向同一个目标发送大量请求。**
在正常运行时，每秒有 1400 到 3600 个 HTTP 请求，全部来自同一台电脑，发往同一个地址。这在网络设备看来就是 **HTTP 洪水攻击**。一个正常人使用浏览器，每秒最多也就发出几十个请求。

**3. 同时保持着 500 多条长连接。**
512 条 HTTP 连接，再加上 10 条 WebSocket 连接。很多校园网会限制**每台设备的并发连接数**（常见的上限是几百到一千左右），一方面是为了防止滥用，另一方面也是为了保护 NAT 设备的连接表不被占满。

**4. 重试时会再次产生突发的连接。**
网络一旦被阻断，几百个线程会**同时**遇到超时，然后按照相同的指数退避间隔**同时**重试。这样，每一轮重试又会产生一次几百个新连接的突发。你看到的 490 次重连就是这么来的。如果网络设备的封禁规则是“检测到异常就延长封禁时间”，这种同步的重试可能还会让封禁持续得更久。

## 为什么学校网络会对此做出反应

大学网络管理员特别担心的一件事是：**校园里的电脑被病毒感染，变成僵尸网络的一部分，对外发动攻击。** 一旦发生这种情况，学校的 IP 地址可能会被外部的服务封禁，学校也可能因此被投诉。所以，校园网的防火墙和入侵检测系统通常会：

- **限制每台设备每秒能够新建的连接数**，超过之后就丢弃多出来的连接请求。
- **限制每台设备同时保持的连接数**，超过之后，新的连接就无法建立。
- **检测类似 DoS 攻击的流量模式**，一旦发现，就**暂时封禁这台设备的所有流量**，过一段时间（比如几分钟）后再自动解除。

第三种情况最符合你遇到的现象：不只是发往 ALB 的请求失败了，连监控脚本访问 RabbitMQ 的请求也一起失败了，而这两个目标的 IP 地址并不相同。这说明被阻断的是**你这台电脑的全部对外流量**，而不只是某一条连接。

本质上，**压力测试就是你自己对自己的系统发起的一次 DoS 攻击**，只是目标是你自己的服务器。网络设备无法分辨你的意图，它只能看到流量的模式。

## 应该怎么做

**最好的做法：把客户端放到 EC2 上运行。** 这样，所有的压测流量都在 AWS 内部传输，完全不会经过学校的网络。从 AWS 的角度来说，对自己账户里的资源进行压力测试是允许的。而且我们前面也分析过，放到 EC2 上之后，测出来的才是你的服务器真正的处理能力。

**如果暂时还需要在学校网络上测试**，至少做到下面这几点：

- **让 main 阶段的线程逐步启动**，比如每 100 毫秒增加 16 个线程，而不是一次性启动 480 个。
- **限制并发连接数**，比如不超过 128 条。
- **给重试的退避时间加上随机抖动**，让各个线程的重试时间错开，避免同时产生大量的重连。这和我们在 A1 里讨论 WebSocket 重连时提到的“惊群效应”是同一个道理。

另外，**尽量不要反复触发校园网的保护机制。** 很多学校的网络使用政策里，明确禁止在校园网上进行压力测试之类的高强度流量活动。一台设备如果多次被检测到异常流量，可能会被封禁更长的时间，甚至被上报给网络管理部门。如果你后续还需要在校园网上进行这类测试，可以先咨询一下学校的 IT 部门。

## User

为什么我今天重开三个instance，现在连ALB连不上了，返回404(base) dlwlrma@dlwlrmadeMacBook-Pro 6650 % ALB=skitrak-alb-865987688.us-west-2.elb.amazonaws.com
(base) dlwlrma@dlwlrmadeMacBook-Pro 6650 % echo $ALB
skitrak-alb-865987688.us-west-2.elb.amazonaws.com
(base) dlwlrma@dlwlrmadeMacBook-Pro 6650 % curl -i http://$ALB/ski-servlet/health
HTTP/1.1 404 
Date: Sat, 07 Nov 2026 23:27:56 GMT
Content-Type: text/html;charset=utf-8
Content-Length: 774
Connection: keep-alive
Set-Cookie: AWSALB=ck+l+VLyv36xLpDTwZXx3299aY/IYg3TkYrethSlsJZatuRbutI6m6CoJcGyKYcPi+0ZRoCcY2//1ZglqviYPQLQE0Ocf0L17+LNuUJbF+TSD5cDqfC6Ru9ogHjc; Expires=Sat, 14 Nov 2026 23:27:56 GMT; Path=/
Set-Cookie: AWSALBCORS=ck+l+VLyv36xLpDTwZXx3299aY/IYg3TkYrethSlsJZatuRbutI6m6CoJcGyKYcPi+0ZRoCcY2//1ZglqviYPQLQE0Ocf0L17+LNuUJbF+TSD5cDqfC6Ru9ogHjc; Expires=Sat, 14 Nov 2026 23:27:56 GMT; Path=/; SameSite=None
Content-Language: en

<!doctype html><html lang="en"><head><title>HTTP Status 404 – Not Found</title><style type="text/css">body {font-family:Tahoma,Arial,sans-serif;} h1, h2, h3, b {color:white;background-color:#525D76;} h1 {font-size:22px;} h2 {font-size:16px;} h3 {font-size:14px;} p {font-size:12px;} a {color:black;} .line {height:1px;background-color:#525D76;border:none;}</style></head><body><h1>HTTP Status 404 – Not Found</h1><hr class="line" /><p><b>Type</b> Status Report</p><p><b>Message</b> The requested resource [&#47;ski-servlet&#47;health] is not available</p><p><b>Description</b> The origin server did not find a current representation for the target resource or is not willing to disclose that one exists.</p><hr class="line" /><h3>Apache Tomcat/9.0.121</h3></body></html>(base) dlwlrma@dlwlrmadeMacBook-Pro 6650 %

## Claude

这次的情况和上次的 503 不一样，从响应的内容可以看出来：**请求已经穿过 ALB，到达了 Tomcat，但 Tomcat 里找不到 `/ski-servlet` 这个应用。**

## 从响应里能看出什么

- **响应里有 `Set-Cookie: AWSALB=...`**：这是 ALB 在转发请求时加上的粘性会话 cookie，说明 ALB 确实把请求转发给了某台 server。
- **响应体是 Tomcat 的错误页面，最后一行是 `Apache Tomcat/9.0.121`**：说明 Tomcat 本身正在运行，并且返回了这个响应。
- **错误信息是 `The requested resource [/ski-servlet/health] is not available`**：Tomcat 找不到这个路径。整个 `/ski-servlet` 都不存在，而不只是 `/health` 找不到，这说明 **WAR 没有部署成功**。

顺便说一下，为什么 ALB 会把请求转发给一台“不健康”的 server？这正是我们之前提到的 **fail open** 机制：健康检查访问 `/ski-servlet/health` 会得到 404，所以所有的 server 都被判定为不健康；而当**所有**目标都不健康时，ALB 依然会把请求转发给它们。所以你看到的是 Tomcat 返回的 404，而不是 ALB 自己返回的 503。

## 最可能的原因：重启之后，Tomcat 比 RabbitMQ 先启动

你今天重新启动了三台实例，我猜应该是 RabbitMQ、consumer 和 server。这正是我们在讨论 Tomcat 开机自启时提到的那个风险：

```
三台机器同时开机
→ server 上的 Tomcat 启动得比较快，开始部署 WAR
→ contextInitialized 尝试连接 RabbitMQ，但 RabbitMQ 的容器还没有准备好，连接失败，抛出异常
→ 整个 WAR 部署失败，/ski-servlet 下的所有路径都返回 404
→ Tomcat 进程本身仍然在正常运行，不会重试部署，systemd 也不会重启它
→ 即使 RabbitMQ 已经启动好了，这台 server 也会一直停留在 404 的状态
```

## 怎么确认

SSH 登录到 server 上，依次执行：

**1. 确认 WAR 文件还在：**

```bash
ls -l /opt/tomcat9/webapps/
```

应该能看到 `ski-servlet.war`，以及解压出来的 `ski-servlet/` 目录。如果连 WAR 文件都不见了，那就是另一个问题，需要重新部署。

**2. 查看部署失败的原因：**

```bash
sudo grep -n -E "SEVERE|startup failed|listener" /opt/tomcat9/logs/catalina.out | tail -20
sudo grep -n -A 20 "SEVERE" /opt/tomcat9/logs/localhost.$(date +%Y-%m-%d).log | head -60
```

如果是我推测的原因，`catalina.out` 里会出现类似 `One or more listeners failed to start` 和 `Context [/ski-servlet] startup failed due to previous errors` 的信息。**具体的异常堆栈**通常记录在第二个文件 `localhost.日期.log` 里，你会在里面看到 `Exception sending context initialized event to listener instance of class [...]`，后面跟着的就是 RabbitMQ 连接失败的异常，比如 `ConnectException: Connection refused`。

注意，日志文件名里的日期用的是 server 本地的时区。如果找不到今天日期的文件，就用 `ls /opt/tomcat9/logs/` 看看有哪些文件。

**3. 确认现在 server 能不能连上 RabbitMQ：**

```bash
timeout 3 bash -c '</dev/tcp/<RabbitMQ 私有 IP>/5672' && echo "5672 open" || echo "cannot connect"
```

如果显示 `cannot connect`，就到 RabbitMQ 那台机器上执行 `sudo docker ps`，看看容器是否在运行。如果容器没有运行，用 `sudo docker start rabbitmq` 启动它，并检查它的重启策略（我们之前讨论开机自启时提到的 `docker update --restart unless-stopped rabbitmq`）。

## 快速修复

确认 RabbitMQ 已经可以连接之后，在**每一台** server 上重启 Tomcat，让它重新部署 WAR：

```bash
sudo systemctl restart tomcat
sleep 15
curl -i http://localhost:8080/ski-servlet/health
```

返回 200 之后，再等一两分钟，让 ALB 的健康检查通过（目标组里的状态变成 healthy），然后重新通过 ALB 访问。

## 长期的修复

明天就要截止了，所以现在先手动重启 Tomcat，保证测试能够进行就好。但这个问题以后每次重启机器都可能再次出现，所以等有时间了，还是要按照我们之前讨论的方法修改代码：

- `contextInitialized` 在连接 RabbitMQ 失败时**不要抛出异常**，而是启动一个后台线程，用指数退避的方式不断重试，直到连接成功。
- 在连接成功之前，`/health` 和 `doPost` 都返回 503。

这样，无论几台机器以什么顺序启动，server 最终都会自动连上 RabbitMQ，ALB 也会在它准备就绪后自动把它加回来。

如果日志显示的原因和我推测的不一样，把 `localhost.日期.log` 里的那段异常贴给我，我们再一起看。

## User

确实如此，那我重启tomcat就行了，现在我想把客户端移到ec2上，学校网不允许压力测试

## Claude

对，换到 EC2 上是最合适的做法。明天就要截止了，下面按照最快的路径来讲。

## 第 1 步：选择客户端的实例类型

客户端要运行 512 个线程，还要记录每个请求的数据、写 CSV，对 CPU 和内存都有一定的要求。t2.micro 只有 1 个 vCPU 和 1 GB 内存，**客户端自己很可能就会成为瓶颈**，导致你测出来的是客户端的上限，而不是 server 的上限。

建议使用 **t3.medium**（2 个 vCPU，4 GB 内存），每小时大约 0.04 美元，用几个小时也只需要几毛钱。测试完立即 terminate 掉。

创建时的设置：区域 **us-west-2**，系统 **Amazon Linux 2023**，安全组新建一个 `sg-client`，入站规则只需要允许你的 IP 访问 22 端口。

## 第 2 步：让 ALB 允许客户端访问

这里有一个容易踩的坑。你的 ALB 是面向公网的（internet-facing），客户端通过 ALB 的公网 DNS 名称访问它时，即使两者在同一个 VPC 里，流量也会经过公网出口，ALB 看到的来源地址是**客户端的公网 IP**，而不是私有 IP。所以，**在 `sg-alb` 里用“来源为 `sg-client`”的方式配置规则是不起作用的。**

在 `sg-alb` 里添加一条入站规则，二选一：

- **来源为客户端的公网 IP**，格式是 `x.x.x.x/32`。最安全，但客户端实例 stop 再 start 之后公网 IP 会改变，需要重新修改。
- **来源为 `0.0.0.0/0`**。最省事，只在测试期间这样设置，提交作业之后删除 ALB 时这条规则也就一起消失了。

## 第 3 步：打包客户端

客户端也需要打成 fat jar。如果你的客户端 pom.xml 里还没有 `maven-shade-plugin`，就照着前面 consumer 的 pom.xml 加上，把 `mainClass` 改成客户端的启动类。

**有一个关键点一定要注意：必须保留 `ServicesResourceTransformer`。** Tyrus（WebSocket 客户端）是通过 `META-INF/services` 里的配置文件，来找到具体的实现类的。如果打包时这个文件没有被正确地合并，`ContainerProvider.getWebSocketContainer()` 在运行时会报错，提示找不到实现类，所有的 WebSocket 订阅者都会连接失败。

如果你的客户端读取了 `lift_vertical.json`（比如用来做榜单的对账），确认它在 `src/main/resources` 里，这样会被一起打包进 jar。

打包之后，**先在本机用 `java -jar` 运行一下**，确认能够正常启动、WebSocket 能够连接成功，然后再按 Ctrl+C 停止。不要在本机跑完整的压测，否则又会触发学校网络的保护机制。

## 第 4 步：上传并安装环境

```bash
# 本机
scp -i <key>.pem target/client.jar ec2-user@<客户端公网 IP>:~

# 客户端 EC2
ssh -i <key>.pem ec2-user@<客户端公网 IP>
sudo dnf install -y java-17-amazon-corretto-headless tmux
```

## 第 5 步：在 tmux 里运行

一次压测要运行好几分钟。如果直接在 SSH 会话里运行，一旦 SSH 断开（比如你的网络波动了一下），客户端也会随之停止。tmux 可以让程序在 SSH 断开之后继续运行：

```bash
tmux new -s test                     # 创建一个名为 test 的会话

# 在 tmux 里运行客户端（参数按照你的客户端实际的格式填写）
java -Xmx2g -jar client.jar http://skitrak-alb-865987688.us-west-2.elb.amazonaws.com/ski-servlet

# 按 Ctrl+B，然后按 D：离开这个会话，客户端会继续在后台运行
# 重新连接：tmux attach -t test
```

`-Xmx2g` 给客户端 2 GB 的堆内存。不指定的话，JVM 默认只会使用物理内存的 1/4，在记录 50 万条请求数据时可能不够用。

压测运行期间，可以**另外开一个 SSH 窗口**，在客户端的机器上执行 `top`。如果 CPU 使用率一直接近 200%（2 个 vCPU 全部占满），说明客户端已经成为瓶颈，需要在报告里说明，或者换一台更大的实例。

## 第 6 步：把结果拉回本机

```bash
# 本机
scp -i <key>.pem -r ec2-user@<客户端公网 IP>:~/results-v2 ./results-v2-ec2
```

监控脚本可以继续在你的 Mac 上运行。它每秒只发送一次请求，流量很小，不会触发学校网络的保护机制。

## 开始测试之前，检查这几件事

这几项和今晚的测试结果直接相关，建议在第一次正式运行之前逐一确认：

- [ ] **ingest 用的 HttpClient 已经关闭了 cookie 管理**（`disableCookieManagement()`）。你上面的 curl 响应里，ALB 确实返回了 `AWSALB` cookie。如果客户端保存了它，所有的 POST 都会被固定发送到同一台 server 上，2 台和 4 台的测试结果就会和 1 台的几乎一样。测完之后，可以用 RabbitMQ 里消息的 `serverId` 分布，或者 ALB 监控里每个目标的请求数，来确认流量确实被分散到了各台 server 上。
- [ ] **每台 server 的 `/ski-servlet/health` 都返回 200**，并且在目标组里显示为 healthy。今天的 404 问题，在每次重启机器之后都可能再次出现。
- [ ] **每次运行时使用一个新的 dayID**，或者在运行前重启 consumer，避免排行榜里混入上一次测试的累计值。
- [ ] **留意 server 的 CPU 积分。** 客户端搬到 EC2 上之后，网络延迟会大幅降低，server 的 CPU 很可能会被真正地压满。之前看到 server-1 只剩下大约 24 个积分。积分一旦耗尽，CPU 只剩下 10% 的基准性能，吞吐量会在测试中途突然下降。测试期间在 CloudWatch 里关注一下 `CPUCreditBalance`，如果看到吞吐量突然下降，先去检查这个指标。

## 线程数也需要重新测试

客户端和 server 都在 us-west-2 之后，网络延迟会从原来的一百多毫秒，降低到几毫秒。根据 Little's Law，在同样的吞吐量下，需要的并发数会大幅减少。作业要求测试 64、128、256、512 个线程，现在正好可以用来找出新的最佳值。很可能 128 或 256 个线程就已经足以压满 server 了，再增加线程只会让延迟变高，吞吐量不会再增加。这组数据本身也很适合写进报告。

## User

AWS内如何查看多台server的负载均衡效果

## Claude

有一个需要先知道的限制：**ALB 自带的 CloudWatch 指标里，并没有“每台 server 各收到了多少请求”这一项。** ALB 的 `RequestCount` 只能按整个负载均衡器、按目标组、或者按可用区来统计；`RequestCountPerTarget` 也只是一个平均值（总请求数除以目标的数量），看不出各台 server 之间的差异。

所以，要展示负载均衡的效果，需要从几个不同的角度组合起来。下面按照从简单到精确的顺序介绍。

## 方法一：目标组的监控页面（展示整体情况）

EC2 控制台 → **Target Groups** → 选择你的目标组 → **Monitoring** 标签页。

这里可以看到：

- **Healthy hosts**：健康的 server 数量。测试 4 台 server 时，应该稳定地显示为 4。
- **Requests**：整个目标组收到的总请求数。
- **Target response time**：server 的响应时间。
- **HTTP 2XX / 4XX / 5XX**：各种状态码的数量。

把时间范围调整到测试的那段时间，截图。它能证明 ALB 在测试期间一直把这几台 server 都视为健康的目标，但**不能**证明请求是平均分配的。

## 方法二：比较每台 server 的 CPU（直观展示分配是否均匀）

CloudWatch 控制台 → **Metrics** → **All metrics** → **EC2** → **Per-Instance Metrics** → 搜索 `CPUUtilization` → **勾选所有 server 实例** → 它们会显示在同一张图表上。

如果负载均衡是均匀的，**这几条曲线应该几乎重合在一起**。如果其中一条明显比其他的高，说明那台 server 收到了更多的请求。也可以用同样的方法查看 `NetworkIn`（每台实例接收的网络流量），它和请求数量的关系更直接。

这张图很适合放进报告，一眼就能看出流量是否被均匀地分配了。

## 方法三：统计每台 server 实际处理的请求数（最精确，最简单）

Tomcat **默认就开启了访问日志**，每处理一个请求就记录一行。所以，只需要分别统计每台 server 的访问日志里有多少个 POST 请求即可：

```bash
# 在你的 Mac 上运行，依次 SSH 到每台 server 上统计
for ip in <server1 公网 IP> <server2 公网 IP> <server3 公网 IP> <server4 公网 IP>; do
  n=$(ssh -i <key>.pem ec2-user@$ip \
      "cat /opt/tomcat9/logs/localhost_access_log.*.txt | grep -c 'POST /ski-servlet/skiers'")
  echo "$ip: $n"
done
```

几点注意事项：

- 访问日志**按天生成新文件**，而且会保留之前所有的记录。所以每次测试之前，最好先清空它们（`sudo rm /opt/tomcat9/logs/localhost_access_log.*`，然后重启 Tomcat），或者在统计时只截取测试那段时间的记录。否则，前几次测试的请求数也会被算进去。
- 统计出来的数字里包括了**重试**的请求。在网络正常的情况下，重试的次数很少，影响可以忽略。

得到每台 server 的请求数之后，可以计算每台所占的比例。比如 4 台 server，理想情况下每台都是 25%。在报告里可以给出一个简单的表格，再加上一个衡量均匀程度的指标，比如“最多的那台和最少的那台之间相差了百分之多少”。

## 方法四：利用消息里的 serverId（完全不需要登录 server）

你在每条消息里都放了 `serverId`，所以 consumer 本来就知道每条 swipe 是由哪台 server 发布的。只需要在 consumer 里加一个 `ConcurrentHashMap<String, LongAdder>`，按 serverId 计数，在日志里定期打印出来（或者在关闭时打印），就能得到每台 server 发布的消息数量。

这个数字和方法三的区别在于：方法三统计的是 server **收到**的请求数，方法四统计的是**成功发布到 RabbitMQ** 的消息数。两者应该非常接近。

## 方法五：RabbitMQ 管理界面（实时查看）

在测试运行期间，打开 RabbitMQ 管理界面的 **Channels** 页面，每个 channel 都会显示它所属的连接（也就是哪台 server 的 IP），以及当前的 **publish 速率**。几台 server 的 channel 的 publish 速率加起来，应该大致相同。

这适合在测试过程中实时观察，也可以截图作为辅助证据。

## 在报告里如何组合

作业要求提供“ALB metrics showing request distribution”，以及证明“even distribution”。建议这样组织：

1. **目标组监控页面的截图**：证明所有的 server 在测试期间都是健康的，并展示整体的请求量。
2. **每台 server 的 CPU 曲线叠加在一起的截图**：直观地展示负载是均匀分配的。
3. **每台 server 处理的请求数的表格**（来自方法三或方法四）：用精确的数字证明分配的均匀程度。

## 如果发现分配不均匀

首先检查的就是**客户端是否保存了 ALB 的 cookie**。如果没有关闭 cookie 管理，客户端会带着 `AWSALB` cookie 发送请求，ALB 就会把所有的请求都转发给同一台 server，表现为一台 server 的 CPU 很高、请求数很多，而其他几台几乎是空闲的。这种情况下，修改客户端的代码，关闭 cookie 管理，然后重新测试即可。

## User

怎么设计failover实验？我的想法是先运行，然后中途关停一台server

## Claude

你的思路是对的：在稳定运行的过程中，人为地让一台 server 停止工作，观察系统怎样检测到故障、怎样把流量转移到其他 server 上，以及恢复之后的表现。在此基础上，有几个细节需要设计好，实验结果才有分析价值。

## 一、先想清楚“怎么关停”

不同的关停方式，会产生不同的现象：

| 方式 | 命令 | 会发生什么 |
|---|---|---|
| **停止 Tomcat**（推荐） | `sudo systemctl stop tomcat` | 8080 端口立即关闭。ALB 再连接这台 server 时，会**立刻**被拒绝。最接近“应用进程崩溃了” |
| 强制杀掉进程 | `sudo kill -9 <pid>` | ⚠️ 你的 systemd 配置了 `Restart=on-failure`，Tomcat 会在 5 秒后**自动重启**。这测的是“自动恢复”，而不是“failover” |
| 在控制台里 stop 实例 | EC2 控制台 → Stop | 整台机器消失。ALB 发出的连接请求得不到任何回应，只能等到超时。故障的表现会更慢、更混乱 |

建议使用第一种方式。注意，用 `systemctl stop` 主动停止的服务，systemd **不会**自动重启它，这正是我们需要的效果。

## 二、要知道 ALB 需要多长时间才能发现故障

按照作业要求的健康检查参数（间隔 30 秒，连续失败 3 次判定为不健康，连续成功 2 次判定为健康）：

```
故障检测时间 ≈ 30 秒 × 3 次 = 最长约 90 秒
恢复检测时间 ≈ 30 秒 × 2 次 = 最长约 60 秒
```

**在故障发生后的这 90 秒里，ALB 依然认为这台 server 是健康的**，还会继续把一部分请求转发给它。这些请求会失败（因为 Tomcat 已经停止，ALB 通常会返回 502），客户端需要靠重试来消化这些失败：重试的请求大概率会被转发到另一台健康的 server 上，从而成功。

这个“检测窗口”正是 failover 实验最值得分析的部分。

## 三、实验设计

**配置**：使用 **2 台 server**。去掉 1 台意味着失去一半的处理能力，现象会比 4 台去掉 1 台（只损失 25%）明显得多。

**测试必须足够长。** 客户端搬到 EC2 之后，吞吐量可能会很高，50 万条请求也许一两分钟就发完了，根本容纳不下 90 秒的检测窗口和 60 秒的恢复窗口。所以建议**发送 100 万条**，或者减少线程数，保证整个测试至少持续 4 到 5 分钟。

**时间线**：

```
T+0s     启动客户端（以及 Mac 上的监控脚本）
T+60s    在 server-2 上执行：date +%T; sudo systemctl stop tomcat
         → 记下精确的时间，后面要用它来对齐各项数据
T+60~150s  ALB 的检测窗口：部分请求失败，客户端重试
T+150s 左右  ALB 把 server-2 标记为 unhealthy，所有的流量都转到 server-1
T+180s   在 server-2 上执行：date +%T; sudo systemctl start tomcat
T+180~240s  Tomcat 启动，健康检查连续成功 2 次后，server-2 重新加入
T+240s 之后  两台 server 重新分担流量，直到测试结束
```

故障阶段和恢复阶段都要包含在内，这样能同时展示 failover（故障转移）和 failback（恢复）。

测试运行期间，在目标组的 **Targets** 页面里，每隔一会儿刷新一次，观察 server-2 的状态变化：`healthy → draining/unhealthy → initial → healthy`。可以在状态变化的时候各截一张图。

## 四、预期会看到什么，以及要收集哪些数据

| 数据来源 | 预期现象 | 能说明什么 |
|---|---|---|
| **客户端每 10 秒的吞吐量** | 停止 Tomcat 后吞吐量下降，server-2 被移除后稳定在一个较低的水平，恢复之后回升 | failover 和 failback 对吞吐量的影响 |
| **客户端记录的每个请求的状态码** | 在检测窗口里出现一批 502，之后消失 | 检测窗口内有多少请求受到了影响 |
| **客户端的重试次数，以及重试 5 次后依然失败的次数** | 重试次数增加，但最终失败的次数**接近 0** | 重试机制成功地消化了这次故障，没有丢失数据 |
| **WebSocket 订阅者的重连次数** | 连接在 server-2 上的订阅者断开后，自动重连到 server-1 | WebSocket 的 failover 也正常工作。因为每台 server 都通过 backplane 接收所有的排行榜，所以订阅者重连到哪一台 server 上，都能继续收到更新；如果你实现了缓存推送，重连之后会立刻收到当前的榜单 |
| **目标组的 Monitoring 页面** | Healthy hosts 从 2 降到 1，之后回到 2；ELB 5XX 出现一个尖峰 | ALB 自身的视角 |
| **Mac 上的监控脚本** | publish 速率出现下降和恢复，但**队列深度没有异常** | 故障只发生在 server 层，RabbitMQ 和 consumer 不受影响 |
| **测试结束后的榜单对账** | 期望结果是 MATCH | 故障期间没有丢失或重复计算任何 swipe |

关于对账，有一种情况需要提前知道：server-2 被停止的那一刻，可能有一些请求**已经发布到了 RabbitMQ，但还没来得及返回响应**。客户端会认为这些请求失败了，然后重试，于是同一条 swipe 会被计算两次（eventId 不同，去重机制无法识别）。如果对账结果显示“实际值比期望值略大”，这正是原因所在，也是我们之前讨论的“客户端幂等键”这个改进方案最好的实际例证。

## 五、在报告里可以分析的内容

1. **故障检测时间**：从停止 Tomcat 到 ALB 把它标记为 unhealthy，实际用了多久，和理论值（最长 90 秒）对比。
2. **故障的影响范围**：检测窗口内出现了多少个错误，最终有多少请求失败了（理想情况下是 0）。
3. **健康检查参数的取舍**：缩短检查间隔、降低失败阈值，可以更快地发现故障，但代价是健康检查的请求更频繁，而且一次短暂的卡顿就可能导致一台正常的 server 被误判为不健康，被移出服务。作业要求的 30 秒和 3 次，是偏向稳定、避免误判的设置。
4. **吞吐量的变化**：只剩一台 server 时，吞吐量下降到了多少，和单台 server 测试的结果是否一致。

## 一个小建议

**在测试开始之前，就把所有的终端窗口准备好**：一个窗口 SSH 到 server-2，把停止命令输入好，到时间直接按回车；另一个窗口准备好启动命令。这样可以准确地按照时间线执行，不会因为临时登录而耽误时间。每个命令前面的 `date +%T` 会打印出执行的精确时间，之后用它来对齐客户端的 CSV、监控脚本的 CSV 和 CloudWatch 的图表。

## User

你列举一个report提纲我看一下

## Claude

下面这份提纲完全按照作业的提交要求来组织，并标注了每一部分应该放什么内容、引用我们之前讨论过的哪些设计和数据。报告本身用英文写，这里的说明用中文。

---

# CS6650 Assignment 2 Report

## 0. Repository

- Git 仓库地址
- 目录说明（一句话介绍每个目录）：`/server-v2`、`/consumer`、`/deployment`、`/monitoring`、`AI_USAGE.md`、`/results-v2`

---

## Part I. Architecture Document（不超过 2 页）

### 1. System Architecture

- **架构图**：Client → ALB → server-v2 × N → RabbitMQ → Consumer → `leaderboard.fanout` → 每台 server-v2 → WebSocket 订阅者
- 在图上标注每个组件运行在哪台 EC2 上、使用的端口，以及两种协议（HTTP POST 和 WebSocket）

### 2. Swipe → Leaderboard Sequence Diagram

时序图依次展示：校验 → 查询 vertical → 发布消息并等待 publisher confirm → 返回 201 → consumer 收到消息 → 去重 → 累加 → 更新 top-N → 合并推送 → 发布到 fanout → 每台 server 更新缓存 → SessionSender 推送给订阅者

### 3. Queue Topology

- `skitrak.exchange`（topic 类型），routing key 为 `resort.{id}`，对应 10 个 durable 队列
- 队列参数：TTL、`x-max-length`，使用 `reject-publish` 策略（并说明为什么不使用默认的 `drop-head`：后者会悄悄丢弃消息）
- 死信 exchange 和死信队列
- `leaderboard.fanout`，每台 server 各有一个 exclusive、auto-delete 的临时队列

### 4. Connection-Locality: Backplane vs Co-location

- **选择**：backplane
- **理由**：作业要求 consumer 运行在独立的 EC2 上；需要能够方便地扩展到 2 台、4 台 server；在这个规模下，消息放大的代价很小（可以给出前面算过的估算）
- **代价**：多了一跳的延迟，以及消息放大
- **控制代价的方法**：合并推送（每个 resort 每 200ms 最多推送一次）；可选的改进是按 resort 绑定 topic
- **什么时候 co-location 更合适**
- **一个隐含的前提**：每个 resort 只有一个发布者，这保证了消息的顺序，所以不需要版本号

### 5. Consumer Threading Model

- 每个 resort 队列对应一个 channel，保证同一个 resort 内的顺序
- 线程数超过 10 时，多出来的线程无法提高并行度（说明作业中“顺序要求”和“测试更多线程”之间的矛盾，以及你的处理方式）
- prefetch、批量 ack
- 内存中的数据结构：`skierVertical`、top-N 的维护方式（利用 vertical 只增不减的性质）、去重集合的内存上限和取舍
- 状态只保存在内存里，consumer 重启后会丢失（这是一个已知的限制，持久化是 A3 的工作）

### 6. Server-side Design

- ChannelPool：一个 Connection，BlockingQueue 实现借出和归还，借出时带超时，归还时检查 channel 是否可用
- Publisher confirms，以及为什么 201 实际上表示“已接受”（可以提一下 202 的语义）
- WebSocket：SessionSender（每个 session 同一时刻只有一条消息在发送，只保留最新的版本）、新订阅者连接时推送缓存的榜单、每个 resort 一把锁来避免竞态

### 7. Load Balancing Configuration

- 目标组、健康检查的参数（30 秒间隔 / 5 秒超时 / 健康阈值 2 / 不健康阈值 3）、粘性会话、空闲超时
- **ingest 客户端关闭了 cookie 管理**（并说明原因：否则所有的 POST 都会被固定到同一台 server 上）

### 8. Failure Handling

- 客户端：重试 5 次，带指数退避
- server：发布失败返回 503；启动时连接 RabbitMQ 失败的处理；连接断开后的重连
- consumer：解析失败的消息直接进入死信队列；发布排行榜失败时依然 ack 这条 swipe（因为排行榜是推导出来的状态）；回调函数里捕获所有的异常
- 幂等性：按 eventId 去重，以及**去重的范围只覆盖 broker 的重复投递**，不能防止客户端重试导致的重复（以及客户端幂等键这个改进方案）
- systemd 和 Docker 的自动重启

---

## Part II. Test Results

### 9. Test Environment

一张表格：每个组件使用的实例类型、所在区域，客户端运行在 EC2 上（并说明原因，见第 15 节）

### 10. Single Server（500K）

- 客户端的输出截图
- 队列的监控曲线：队列深度、publish 速率和 ack 速率
- 系统指标：server 的 CPU、内存

### 11. Load Balanced: 2 Servers and 4 Servers（500K，以及 1M 压力测试）

- 每次测试的客户端输出
- **负载分配的证据**：目标组的 Monitoring 截图、每台 server 的 CPU 曲线叠加图、每台 server 处理的请求数表格

### 12. Throughput Comparison

| Servers | Threads | Throughput | Mean / p99 latency | Peak / avg queue depth | Consumer lag p99 |
|---|---|---|---|---|---|
| 1 | | | | | |
| 2 | | | | | |
| 4 | | | | | |
| 4（1M） | | | | | |

- 每种配置下，不同线程数（64 / 128 / 256 / 512）的对比
- **找出瓶颈**：是 server 的 CPU、CPU 积分、RabbitMQ、consumer，还是客户端本身？

### 13. Queue Profile & Consumer Lag

- 队列深度的曲线，判断它是稳定的平台形还是锯齿形
- consumer lag 的**定义**（从发布到聚合完成，不包括推送的合并间隔）以及它的分布
- 用 Little's Law 验证：平均队列深度 ≈ 到达速率 × 平均 lag

### 14. Real Leaderboard Evidence

- 冒烟测试：使用一个独立的 dayID 发送几条 swipe，截图展示 curl 的命令和 wscat 收到的榜单变化
- 大规模对账：每个 resort 都显示 MATCH（如果出现了 MISMATCH，如实地分析原因）
- 推送延迟（并说明其中包含的时钟偏差）

### 15. Failover Experiment

- 时间线图：吞吐量、错误数量、健康的 host 数量，在图上标注出停止和启动 Tomcat 的时间点
- 实际的故障检测时间和理论值（最长约 90 秒）的对比
- 重试成功消化了多少错误，最终失败的数量
- WebSocket 订阅者的重连
- 健康检查参数的取舍

### 16. Performance Analysis & Lessons Learned

- 各种配置之间的性能提升，以及没有呈线性增长的原因
- **网络环境的教训**：第一次在本机运行时，出现了约 120 秒的停顿。你通过监控数据的空白、ALB 的 Target Response Time，以及 server 的指标，**排除了 server 端的原因**，定位到问题出在本地的网络（校园网的保护机制），于是把客户端迁移到了 EC2 上。同时，用 Little's Law 说明，本地运行时 140 毫秒的延迟大部分来自公网，所以测量的瓶颈其实是网络，而不是系统本身
- 其他发现，比如 Tomcat 在 RabbitMQ 之前启动导致 WAR 部署失败，以及你的解决办法

---

## Part III. AI Usage Reflection（`AI_USAGE.md`，5 分）

- **在哪些地方使用了 AI**（要具体）：ChannelPool、拓扑结构、consumer 的数据结构、backplane、部署、监控脚本、问题排查
- **至少两条采纳的建议**：比如用 BlockingQueue 实现 ChannelPool、选择 backplane 方案、SessionSender 的设计
- **至少两条拒绝或修改过的建议**，这是最能体现你的理解的部分。从我们的对话中，有几个真实的例子：
  - **版本号的问题**：AI 建议用 consumer 自增的序号作为排行榜的版本号，**你指出 consumer 重启后序号会归零，导致之后所有的新榜单都被丢弃**，最后改成了每个 resort 一把锁的方案
  - **ChannelPool 的 `isOpen()` 检查**：AI 最初的建议，和 RabbitMQ 客户端的自动恢复功能相冲突
  - **对卡死原因的误判**：AI 一开始推测是 server 端的问题，**后来根据你提供的监控数据和 ALB 指标，才定位到是本地网络的问题**
  - **监控脚本的平均值被稀释**：你发现平均深度把测试前后的空闲时间也算了进去
- **你自己推理和决定的地方**：比如 consumer lag 的边界如何定义、排行榜发布失败时依然 ack 这条 swipe 的推理过程，或者其他你独立思考后做出的决定
- **对话记录**：附上分享链接，或者导出到 `transcripts/` 目录

**这一部分一定要写你自己真实的经历和判断。** 我上面列出的只是我们对话中发生过的事情，你需要挑选**你确实经过了思考、并且能够解释清楚的**例子来写。作业也明确说了，评分看的是诚实和洞察力，而不是使用 AI 的多少，你也可能会被要求现场解释其中的任何一部分。

---

## 时间紧张时的优先顺序

明天就截止了。如果时间不够，建议按照评分的权重来安排：

1. **必须完成**：架构文档（第 1 到 8 节）、1 台 / 2 台 / 4 台的测试结果和对比（第 10 到 12 节）、负载分配的证据、队列截图、排行榜的证据、`AI_USAGE.md`
2. **很有价值**：consumer lag 和 Little's Law 的分析、第 16 节中网络问题的排查过程
3. **有时间再做**：failover 实验、1M 压力测试、不同线程数的完整对比

## User

```
| Run | Servers | Runtime (s) | Throughput (req/s) | p50 Latency (ms) | Failed / Retries | Peak / Avg Queue Depth | Consume Rate (/s) | Consumer Lag p95 (ms) |
|---|---|---|---|---|---|---|---|---|-------------------------------------------|
| 1 | 1 | 135.11 | 3701 | 95.89 | 0 / 0 | 61 / 18.2 | 3472 | 185 |
| 2 | 2 | 96.40 | 5187 | 24.67 | 0 / 0 | 45 / 26.9 | 4716 | 140 |
| 3 | 4 | 83.01 | 6024 | 37.22 | 0 / 0 | 98 / 59.7 | 5556 | 210 |
| 4 | 4 (1M) | 165.60 | 6039 | 47.13 | 0 / 0 | 124 / 66.8 | 5717 | 217 |
```

看一下这个格式哪里错了，读不出表格形式

## Claude

问题出在**第二行（分隔行）多了一列**。

表头有 9 列：

```
| Run | Servers | Runtime (s) | ... | Consumer Lag p95 (ms) |
```

但分隔行的末尾多出了一段 `-------------------------------------------|`，变成了 10 列。Markdown 要求**分隔行的列数必须和表头完全一致**，否则整段内容都不会被识别为表格，只会显示成普通的文字。

另外，你在外面用 ```` ``` ```` 包了一层代码块。代码块里的内容会**原样显示**，不会被渲染成表格。如果你在 README 或报告里也是这样写的，需要把这对符号去掉。

修改后的版本如下：

```markdown
| Run | Servers | Runtime (s) | Throughput (req/s) | p50 Latency (ms) | Failed / Retries | Peak / Avg Queue Depth | Consume Rate (/s) | Consumer Lag p95 (ms) |
|---|---|---:|---:|---:|---|---|---:|---:|
| 1 | 1 | 135.11 | 3701 | 95.89 | 0 / 0 | 61 / 18.2 | 3472 | 185 |
| 2 | 2 | 96.40 | 5187 | 24.67 | 0 / 0 | 45 / 26.9 | 4716 | 140 |
| 3 | 4 | 83.01 | 6024 | 37.22 | 0 / 0 | 98 / 59.7 | 5556 | 210 |
| 4 | 4 (1M) | 165.60 | 6039 | 47.13 | 0 / 0 | 124 / 66.8 | 5717 | 217 |
```

渲染出来的效果：

| Run | Servers | Runtime (s) | Throughput (req/s) | p50 Latency (ms) | Failed / Retries | Peak / Avg Queue Depth | Consume Rate (/s) | Consumer Lag p95 (ms) |
|---|---|---:|---:|---:|---|---|---:|---:|
| 1 | 1 | 135.11 | 3701 | 95.89 | 0 / 0 | 61 / 18.2 | 3472 | 185 |
| 2 | 2 | 96.40 | 5187 | 24.67 | 0 / 0 | 45 / 26.9 | 4716 | 140 |
| 3 | 4 | 83.01 | 6024 | 37.22 | 0 / 0 | 98 / 59.7 | 5556 | 210 |
| 4 | 4 (1M) | 165.60 | 6039 | 47.13 | 0 / 0 | 124 / 66.8 | 5717 | 217 |

我顺便把数字列改成了右对齐（分隔行里写成 `---:`），这样数字会按位对齐，更容易比较。这一步不是必须的，用 `---` 也完全没有问题。

## 还有一个数据需要你确认一下

**Run 1 的 p50 延迟是 95.89 ms，和之前在 Mac 上运行的那一次（出现 120 秒停顿的那次）的 median 完全相同**，精确到了小数点后两位。两次不同的测试得到完全一样的数值，可能性非常小，建议你检查一下是不是从旧的输出里复制过来的。

其他的数值我粗略验算了一下，吞吐量和运行时间是对得上的，比如 500,000 ÷ 135.11 ≈ 3701，1,000,000 ÷ 165.60 ≈ 6039。

另外，表格里没有**线程数**这一列。如果这几次测试使用的线程数不同，最好加上这一列，否则读者无法判断吞吐量的差异是来自 server 的数量，还是来自线程数。比如 Run 3（4 台 server）的 p50 延迟反而比 Run 2（2 台 server）更高，有了线程数这一列，就更容易解释这个现象。

## User

帮我生成serverv2，clientv2和consumer的readme简单框架

## Claude

三份 README 的框架都写好了，每份四五十行，分别放在 `readme-v2` 下的 `server-v2`、`consumer`、`client-v2` 三个文件夹里，直接复制到仓库对应的目录即可。和之前一样用英文写的，每份的结构如下：

- **server-v2**：三个 endpoint、各个类的职责、消息字段、环境变量、打包部署、Tomcat 先于 RabbitMQ 启动导致 404 的说明，以及测试命令。
- **consumer**：处理流程、线程模型、内存中的数据结构、投递保证、失败的处理方式、已知的限制（重启后状态会丢失），以及用 systemd 运行的方法。
- **client-v2**：相对于 A1 的修改（连接 ALB、关闭 cookie 管理、记录最终的榜单）、在 EC2 上运行的方法、运行参数，以及输出的文件。

需要你对照实际的代码确认或者替换的地方：

- **所有 `<...>` 都是占位符**，包括 ChannelPool 的类名、consumer 的线程数和 prefetch、合并推送的间隔、top-N 的大小（我按照你之前提到的 top 20 写的），以及客户端的命令行参数。
- **类名**是我根据你之前 `git status` 里出现的文件名写的（`RabbitMQManager`、`SessionSender`、`ConsumerApp`、`Broadcaster` 等），`SkierServlet` 和 `HealthServlet` 是按照 A1 的命名推测的，如果不一样要修改。
- **client-v2 里关于对账的那一条**，如果你最终没有实现对账，就把它删掉，避免 README 的描述和代码对不上。
- server-v2 里写的是 `/health` 返回 200。如果你按照我们讨论的，实现了“RabbitMQ 连接成功之前返回 503”，记得把这一点也补充进去。

## User

稍微简单一点，你这个有点复杂

## Claude

三份 README 都精简好了，每份只有 20 行左右，只保留了“这是什么、怎么配置、怎么运行”这三部分：

- **server-v2**：一句话介绍、三个 endpoint、环境变量、打包部署命令。
- **consumer**：一句话介绍、三条关键设计、环境变量、运行命令。
- **client-v2**：一句话介绍、三点和 A1 不同的地方、运行命令。

详细的设计（ChannelPool、SessionSender、去重、合并推送等）放在报告的架构文档里就可以了。使用前，把 `<key>`、`<server-IP>` 这类占位符换成你自己的值，再确认一下 WAR 名和 jar 名和你实际打包出来的一致。

## User

你这生成的质量不太行，我们一个一个来。根目录readme：刷卡发到 ALB 后面的几台 ingest server，server 把每条刷卡发到 RabbitMQ，一个 consumer 读出来算每个人的总落差，每个雪场维护一个前 20 名的排行榜，排行榜再通过 RabbitMQ 的 fanout exchange 发回每台 server，server 推给自己身上的 WebSocket 订阅者。全部在 AWS EC2 us-west-2。assignment2 新加的目录有 server-v2、consumer、client-v2、deployment、monitoring、results-v2，还有 AI_USAGE.md；assignment1 的 server、client-part1、client-part2、results 都还在，没动。把两个 assignment 的目录都列出来，一句话说明每个是干嘛的，最后一句还是每个目录有自己的 README、全部 Java 11 和 Maven。

## Claude

根目录的 `README.md` 按你说的结构写好了：开头一段说明整个系统的数据流向，然后用两张表分别列出 Assignment 2 和 Assignment 1 的目录，每个目录一句话说明用途，最后一句说明每个目录都有自己的 README，以及使用的技术栈。

最后一句我稍微调整了一下措辞。你说的是“全部 Java 11 和 Maven”，但 `monitoring/` 里的监控脚本是 Python 写的，而不是 Java。所以我写成了：所有的 Java 代码都使用 Java 11 和 Maven，`monitoring/` 里的队列监控脚本是一个只用到标准库的 Python 3 脚本。如果你的 `monitoring/` 里换成了别的实现，或者你更想保持原来的说法，告诉我，我再改回去。

另外，`deployment/` 里放的是 shell 脚本和配置文件，也不属于 Java 代码，现在的写法对它也是准确的。

## User

写server-v2的README，简单点，可以用表格。它是Tomcat 9的web app，打包叫ski-servlet.war，接收刷卡发到RabbitMQ，再把排行榜推给自己的WebSocket订阅者。接口三个：POST /ski-servlet/skiers/{resortID}/seasons/{seasonID}/days/{dayID}/skiers/{skierID}，RabbitMQ确认了才回201，参数错回400，发不出去回503；GET /ski-servlet/health，连着RabbitMQ回200，断了回503；WebSocket /ski-servlet/leaderboard/{resortID}，推这个雪场的前20名。类：SkierServlet校验和发布，RabbitMQManager建连接、队列和channel池，ChannelPool是32个channel，LiftVerticals读lift_vertical.json，SwipeInfo是队列消息，LeaderboardEndpoint和SessionSender负责推送，HealthChecker是健康检查。环境变量RABBIT_HOST、RABBIT_USER、RABBIT_PASS、SERVER_ID。构建是mvn clean package，war拷到Tomcat的webapps。

## Claude

server-v2 的 `README.md` 按你给的内容写好了，包括一句话介绍，以及接口、类、环境变量三张表，最后是构建和部署的命令。内容完全按照你的描述，没有额外添加别的东西。

有一点和部署相关，提醒你检查一下：`SERVER_ID` 是通过环境变量设置的。如果它写在 `setenv.sh` 里，而其他几台 server 是用 AMI 复制出来的，那么**所有的 server 都会使用同一个 `SERVER_ID`**。这样，用消息里的 `serverId` 来统计每台 server 的请求数时，就无法区分它们了。可以确认一下每台 server 上的值是否不同，或者在代码里没有设置这个变量时，用主机名作为默认值。

## User

写consumer的README，简单点。独立Java程序，从resort.1到resort.10读刷卡，算每人总落差和每个雪场每天的前20名，榜单变了发到leaderboard.fanout。要点：10个channel每个负责一个队列所以按顺序处理；处理完才ack，prefetch 50，坏消息进死信队列；按eventId去重；排名一样时skierID小的在前；榜单每200毫秒合并发一次；每5秒打印处理数、广播数和消费延迟。类：ConsumerApp、SwipeInfo、Aggregator、TopN、Broadcaster、LagStats，每个一句话。环境变量RABBIT_HOST、RABBIT_USER、RABBIT_PASS、CONSUMER_THREADS默认10、PREFETCH默认50、BROADCAST_INTERVAL_MS默认200、HEALTH_PORT默认8081。构建mvn clean package出来consumer.jar，java -jar跑，健康检查在8081端口，EC2上用systemd跑。

## Claude

consumer 的 `README.md` 写好了：一句话介绍，六条要点，类、环境变量两张表，最后是构建和运行的命令。

有两处需要你对照代码确认一下：

1. **每个类的说明是我根据类名和你描述的功能推断的。** 特别是 `ConsumerApp` 和 `LagStats` 的职责划分：每 5 秒的统计输出是由哪个类负责打印的，健康检查的服务又是在哪个类里启动的。如果和代码不一致，告诉我实际的分工，我再修改。

2. **“10 个 channel，每个负责一个队列”和“`CONSUMER_THREADS` 可以配置”放在一起，读者可能会有疑问**：如果把 `CONSUMER_THREADS` 设成 20 或者 5，会发生什么？如果你的代码在线程数不等于 10 时，会把队列重新分配给线程（比如设为 5 时，每个线程负责 2 个队列），可以在要点里补一句说明。这也正好对应作业里测试不同 consumer 线程数的要求。

## User

写client-v2的README。从client-part2复制改的：地址、条数、线程数从环境变量读，分别是SKITRAK_HOST默认localhost:8080、TOTAL_SWIPES默认500000、THREADS默认1024；关掉了cookie，避免ALB把刷卡都粘到一台server；发完等3秒没新榜单再关订阅者；结果写到results-v2，CSV多了skierID和liftID，还有每个雪场最后的榜单；打包成client.jar。测试时这个jar放在us-west-2的EC2上，用monitoring/run_remote.sh从我电脑启动。

## Claude

client-v2 的 `README.md` 写好了：一句话介绍，和 client-part2 相比的四点修改，环境变量表，构建运行的命令，以及输出文件表。

有三处是我根据之前的信息补充的，需要你确认：

1. **输出文件的名字**（`allswipes.csv`、`throughput_over_time.csv`、`final_leaderboards.txt`）来自你第一次运行时客户端打印的输出。如果后来改过名字，要同步修改。
2. **CSV 的列顺序**，我是在 A1 规定的五列后面接上了 `skierID` 和 `liftID`。如果实际的顺序不一样，以代码为准。
3. **`SKITRAK_HOST` 的格式**：默认值是 `localhost:8080`，看起来只包含主机和端口，那么 `/ski-servlet` 这个路径应该是客户端在代码里自己拼接上去的。示例里我写的是 `SKITRAK_HOST=<ALB-DNS>`，如果你的代码要求带上路径，或者要求带上 `http://`，记得修改示例。

另外，`THREADS` 的默认值是 1024，而你报告里的测试结果表并没有写明使用的线程数。如果正式测试时用的都是 1024，可以在报告的表格里补上这一列，读者就能知道这几次测试的线程数是一样的。

## User

写monitoring的README，就两个东西各一小段加用法。queue_monitor.py每秒调RabbitMQ管理接口，记10个刷卡队列的总深度、发布速率、ack速率和死信数量，写成CSV，Ctrl+C结束时打印峰值和平均值，只用Python标准库，用法是设RABBIT_PASS后跑 python monitoring/queue_monitor.py --host RabbitMQ公网IP --out 文件路径。run_remote.sh通过SSH在客户端EC2上跑压测，输出显示在本地，跑完把结果拷回results-v2对应文件夹，用法是设CLIENT和ALB两个变量后跑 ./monitoring/run_remote.sh 轮次名 线程数 总条数。

## Claude

monitoring 的 `README.md` 写好了，两个工具各一小段说明，后面跟着用法。

有一处需要你确认：`CLIENT` 这个变量应该怎么填，我写的是占位符 `<client-instance>`。如果你的脚本要求的格式是 `ec2-user@<IP>`，而不只是 IP 地址，最好把占位符改成 `ec2-user@<client-IP>`，这样别人照着填就不会出错。

另外，如果想让用法更直观，可以在 `run_remote.sh` 的命令下面加一行实际的例子，比如 `./monitoring/run_remote.sh run3-4servers 1024 500000`。

## User

写results-v2的README，说明数据怎么看。results.md是所有结果表。run1到run3分别是1台、2台、4台server，run4是4台100万条；threads64、threads128、threads256是不同客户端线程数；consumer5、consumer15是不同消费线程数；failover是中途停掉一台server。没特别说明的都是512客户端线程、10消费线程、50万条。每个文件夹里有client_output.txt客户端输出、allswipes.csv每条刷卡、throughput_over_time.csv每10秒吞吐量、final_leaderboards.txt最后的榜单、queue.csv队列监控数据。screenshots里有每轮客户端输出、每轮RabbitMQ曲线、ALB分配、CPU、榜单证据、故障转移和几张配置图，列一下就行。

## Claude

results-v2 的 `README.md` 写好了：先说明 `results.md` 是汇总表，然后用一张表列出每个文件夹对应的测试配置，并说明默认的配置，再用一张表说明每个文件夹里的文件，最后列出 screenshots 里有哪几类截图。

有一个前后不一致的地方需要注意：client-v2 的 README 里写的是 `THREADS` **默认值为 1024**，而这里写的是**默认使用 512 个客户端线程**。如果你每次测试都是通过 `run_remote.sh` 明确传入线程数的，两者其实并不矛盾，但读者可能会感到困惑。可以考虑二选一：要么把客户端代码里的默认值改成 512，和测试配置保持一致；要么在这份 README 的默认配置那一句后面，补充说明“线程数由 `run_remote.sh` 传入”。

## User

写deployment的README，只写README，里面的service文件和脚本已经有了不要生成。全部是Amazon Linux 2023，us-west-2，服务用RabbitMQ私有IP连它。机器：RabbitMQ用Docker跑，consumer和4台server用systemd跑，都是micro；客户端是t3.medium；还有一个ALB。文件说明放哪：rabbitmq-setup.sh在RabbitMQ机器跑一次；consumer.env.example放到/etc/skitrak-consumer.env；skitrak-consumer.service和tomcat.service放到/etc/systemd/system；server-setenv.sh.example放到/opt/tomcat9/bin/setenv.sh；alb.md是ALB配置；security-groups.md是安全组规则。部署顺序：RabbitMQ、consumer、server-1，server-2到4用server-1的AMI复制，再建ALB，最后客户端。重启顺序要写：先开RabbitMQ等它好了再开其他的，server比RabbitMQ先开会部署失败，要重启Tomcat。

## Claude

deployment 的 `README.md` 写好了，只生成了这一个文件，没有动目录里已有的 service 文件和脚本。内容包括：机器表、文件放置位置表、部署顺序和重启顺序，另外在文件表下面补了两行 systemd 的启用命令，在重启顺序里补了一个判断 RabbitMQ 是否已经就绪的方法。

有两处需要你确认：

1. **实例类型我写的是 `micro`**，没有写具体是 t2.micro 还是 t3.micro。建议改成准确的型号，因为两者的 CPU 积分机制不同，而你的报告里可能会讨论 CPU 积分对性能的影响。
2. **启用命令里的 `<service-name>`**，在 consumer 机器上对应的是 `skitrak-consumer`，在 server 上对应的是 `tomcat`。如果你想让读者更容易照着操作，可以把这一行拆成两条具体的命令。

## User

根据作业的要求，架构图需要单独放进repo中，还是我提交pdf在作业提交窗口就可以？

## Claude

按照作业的要求，**架构图放在提交到 Canvas 的 PDF 里就可以了，不需要单独放进 repo。**

作业的提交要求是这样组织的：

> **Submit a PDF to Canvas containing:**
> 1. **Git Repository URL** — new folders: `/server-v2`、`/consumer`、`/deployment`、`/monitoring`、`AI_USAGE.md`
> 2. **Architecture Document (2 pages max)** — System architecture diagram、Swipe → leaderboard sequence diagram、Queue topology……
> 3. **Test Results** ……

可以看到，架构图和时序图属于第 2 项“Architecture Document”，而这份文档是 **PDF 的一部分**。对 repo 的要求只列出了那几个目录和 `AI_USAGE.md`，并没有要求放架构图。

不过有两点值得注意：

1. **`AI_USAGE.md` 是唯一需要特别留意放在哪里的文件。** 作业说可以把它放进 repo，**或者**附在架构文档的后面。你已经计划把它放在 repo 里了，这样就满足要求了。如果你也想在 PDF 里体现它，可以在 PDF 里写一句它在 repo 中的位置。另外，作业建议附上对话记录（分享链接，或者导出到 repo 的 `transcripts/` 目录），这能为你的反思提供证据。

2. **把架构图也放一份到 repo 里，是一个可选的加分项。** 比如把图片放在 `docs/architecture.png`，然后在根目录的 README 里引用它。这样，别人浏览你的 repo 时，不用打开 PDF 就能理解整个系统的结构。这不是作业的要求，如果时间紧张，可以跳过。

## User

我已经有点忘记我们都讨论了什么了，根据作业的AI要求，你罗列一下我们都讨论了什么

## User

从Assignment2往后总结

## Claude

下面按照 AI 使用反思的要求，把从 Assignment 2 开始的讨论整理成两部分：第一部分是按主题列出的讨论内容，第二部分是可以直接用于反思的素材。

需要先说明一点：我只能看到对话里的内容。哪些建议你最终写进了代码，我只能根据你后来发给我的信息（比如 README 的内容、运行输出、`git status`）来判断。没有证据的地方，我会标注“无法确认”，写反思时请以你实际做的为准。

---

## 一、按主题列出的讨论内容

### 1. 理解作业和整体架构

- 我总结了作业的四个部分，并指出了几个需要注意的地方：顺序要求和 consumer 线程数之间的矛盾、排行榜推送的频率、去重集合的内存、返回 201 的时机、成本。
- **你复述了自己对架构的理解**，我做了修正：ALB 是 AWS 托管的服务，只需要配置，不需要写代码；consumer 是逐条增量计算，而不是对所有 swipe 统一计算；backplane 的中文应该是“背板”；以及最关键的一点：**每台 server 必须有自己专属的 fanout 队列**，如果共用一个队列，每条消息只会被其中一台 server 收到。
- **Backplane 和 co-location 的对比**：我建议选择 backplane，理由是作业要求 consumer 运行在独立的 EC2 上、需要方便地扩展到 2 台和 4 台 server，并且在这个规模下消息放大的代价很小。

### 2. RabbitMQ 的基础和拓扑结构

- 添加 `amqp-client` 依赖（不能用 provided scope）、在 `ServletContextListener` 里创建连接、guest 用户只能从本机登录。
- 队列是“声明”的，而且声明操作是幂等的；exchange、队列和绑定关系都需要声明，漏掉绑定会导致消息被悄悄丢弃。
- TTL 和队列长度上限的坑：`drop-head` 会悄悄丢弃消息，所以建议使用 `reject-publish`。
- 死信 exchange 和死信队列的配置方法；队列参数和 policy 两种配置方式的区别；`x-` 开头的参数名写错了**不会报错**，只会被忽略。
- 所有的操作都要通过 Channel 进行，设置拓扑时使用一个临时的 channel。
- 用管理界面验证拓扑：手动发布一条消息测试路由，用 Get messages 测试死信队列。

### 3. Server 端的发布

- **你问 ChannelPool 能不能用数组加轮询**：我解释了轮询只能决定“轮到谁”，无法知道“谁现在有空”，加锁之后又会出现在空闲 channel 存在时依然要等待的问题，所以建议使用 BlockingQueue 实现借出和归还。
- 借出时带超时、在 `finally` 里归还、归还前检查 channel 是否可用。
- Publisher confirms（`confirmSelect` 的含义），以及三种发布方案的对比；返回 201 在语义上其实表示“已接受”（可以提一下 202）。
- 201 和 503 的设置方法；为什么用 503 而不是 500。
- 加载 `lift_vertical.json`（使用数组存储、通过 classpath 读取、启动时校验）；**你问每个雪场是否共用同一套 lift**，我根据作业的原文推断是共用的，并建议你以文件的实际结构为准。
- 生成 UUID；**eventId 由 server 生成，只能防止 broker 的重复投递，不能防止客户端重试导致的重复**，以及客户端幂等键这个改进方案。
- RabbitMQ 客户端的自动恢复功能，以及它覆盖不到的情况；作业原文要求“自己实现重连”，我建议你向老师确认是否可以使用库自带的功能。

### 4. Consumer

- **你描述了自己对 consumer 的理解**，我修正了两点：consumer 是订阅队列，由 broker 推送消息，而不是“取出所有的 swipe”；ack 要放在处理完成之后。
- 线程模型：每个 resort 队列对应一个 channel，以此保证顺序；线程数超过 10 之后无法提高并行度；prefetch 和批量 ack 才是影响吞吐的关键。
- **你设计了两层 Map 的数据结构**（`雪场:天:skierID` 和 `雪场:天` → 排行榜），我确认了这个设计，并讨论了排行榜内部用 Map 还是有序 List 的取舍，以及利用“vertical 只增不减”这个性质简化 top-N 的维护。
- 平局时的规则（严格大于才入榜，同分时按 skierID 排序）；合并推送；用快照来避免线程安全问题。
- 解析失败的处理：classic 队列不会统计重试次数，所以要自己区分永久性错误和暂时性错误；毒消息；**发布排行榜失败时依然 ack 这条 swipe**（因为排行榜是推导出来的状态）；回调函数里的异常会导致 channel 被关闭。
- 去重的时机（先完成聚合，再标记为已处理）；去重集合的内存上限。

### 5. WebSocket 推送

- 单独开一个 channel 接收 fanout 消息，使用由服务器命名的临时队列，autoAck；利用 routing key 传递 resortID。
- **你问同一个 session 并发发送会不会报错**：会的，即使只有一个线程也可能触发（因为发送是异步的）。我建议每个 session 配一个 SessionSender，同一时刻只有一条消息在发送，并且只保留最新的版本。
- **你纠结新订阅者连接时要不要推送缓存的榜单**：我建议推送，然后指出这里存在一个竞态问题，于是提出了用版本号来解决。
- **版本号的问题（见第二部分）**：最后改成了每个 resort 一把锁的方案。

### 6. 验证和测量

- **证明“榜单由发送的 swipe 决定”**：用一个独立的 dayID 做手动冒烟测试，再加上压测结束后的自动对账；以及对账的几个前提条件（每次使用新的 dayID、等待榜单稳定、排序规则必须和 consumer 一致）。
- **你问 consumer lag 的边界如何定义**：我建议定义为从发布到聚合完成，不包括推送的合并间隔；并说明它和“队列深度小于 1000”的目标可以用 Little's Law 联系起来。
- **你问每条都统计的开销**：我介绍了分桶直方图。**你又追问分桶会不会损失精度、用 List 存储需要多少内存**：我计算了内存开销，并说明因为输入本身就是整数毫秒，所以 1 毫秒宽的桶几乎不会损失精度。

### 7. 部署

- 每个组件分别部署到一台 EC2 上；公网 IP 和私有 IP 的区别；按角色划分安全组；把配置从代码里移到环境变量中。
- 用 Docker 运行 RabbitMQ（后来补充了固定主机名和挂载数据卷）；consumer 的 pom.xml（shade 插件、SLF4J 的版本、`release` 参数）；用 systemd 运行 consumer。
- 开机自启：RabbitMQ 需要两层保障；consumer 需要 `StartLimitIntervalSec=0`；Tomcat 的 systemd 配置。
- **Tomcat 比 RabbitMQ 先启动会导致 WAR 部署失败**：Tomcat 的进程不会退出，所以 systemd 也帮不上忙。我建议用后台线程不断重试连接，并让 health 在连接成功之前返回 503。
- AMI 的概念（**你问用 AMI 复制出来的机器和原来的机器是什么关系**）、创建镜像、复制 server、更新 WAR 的脚本。
- ALB 的配置；ALB 返回 503 的排查（目标组为空，或者实例不在启用的可用区内）；ALB 返回 404 的排查（Tomcat 比 RabbitMQ 先启动）；Apache HttpClient 默认保存 cookie，会导致所有请求都被固定到同一台 server 上。
- ALB 的费用（放两个月大约要 47 美元）。

### 8. 监控脚本

- 我按照你的需求写了 `queue_monitor.py`（只用标准库），并用模拟的接口测试过。
- **你发现平均值被测试前后的空闲时间稀释了（见第二部分）**。

### 9. 第一次压测的问题排查

- 512 个线程，中间有大约 110 秒吞吐量为 0。我最初推测是 server 端的问题（JVM 内存不足、ChannelPool 被耗尽、RabbitMQ 阻塞了连接）。
- **你提出可能是你的 Mac 连接 ALB 出了问题**，并提供了监控的 CSV。从中发现监控脚本也同时失联了 127 秒，而且时间正好和 main 阶段开始时同时新建大约 480 条连接的时刻吻合。你又提供了 server 的 CPU、内存以及 ALB 的 Target Response Time（最高只有 120 毫秒），最终确认 server 一直是正常的，问题出在本地网络上（校园网的保护机制）。
- 我解释了客户端在网络设备看来为什么像是一次 DoS 攻击，你决定把客户端迁移到 EC2 上运行。
- 顺便发现的问题：Mac 和 EC2 之间的时钟偏差（推送延迟的中位数是 -119ms）；有 37 条请求最终失败，可能会影响对账。

### 10. 负载均衡、failover 和报告

- 查看负载分配的几种方法（ALB 的指标里没有每个目标的请求数；用 CPU 曲线叠加图和 Tomcat 的访问日志来证明）。
- **你提出 failover 实验的思路（中途停掉一台 server）**，我补充了关停方式的区别（`kill -9` 会被 systemd 自动重启）、ALB 检测故障需要的时间（最长约 90 秒）、需要收集的数据。
- 报告提纲；修复表格的格式（分隔行多了一列），**我还指出 Run 1 的 p50 和之前 Mac 上那次运行的结果完全一样，可能是复制错了**。
- README：**你认为我第一次生成的质量不够好**，之后逐个给出了具体的内容要求。我在写根目录的 README 时指出，`monitoring/` 里的脚本是 Python，不是 Java。

---

## 二、可以用于 AI 使用反思的素材

### 你发现了 AI 的错误，或者否定了 AI 的建议

这些是反思里最有价值的部分。

1. **版本号会在 consumer 重启后归零。** 我建议用 consumer 自增的序号作为排行榜的版本号，**你指出 consumer 重启后序号会从 0 开始，导致之后所有的新榜单都会被当作旧版本丢弃**。你还提出了用 serverTimestamp 代替的方案。我分析了时间戳的风险之后，最终建议用每个 resort 一把锁，从结构上消除这个竞态问题。（你最终采用了哪种方案，我无法确认。）
2. **第一次压测的停顿原因。** 我推测是 server 端的问题，**是你提出可能是本地网络的问题，并提供了数据，最终证明你的判断是对的。** 同样，再往前一次，你说“客户端运行时管理界面无法访问”，我当时归因于 RabbitMQ 的资源耗尽，现在看来也是同一个网络问题。
3. **监控脚本的平均值被稀释了。** 我写的脚本在计算平均深度时，把测试前后的空闲时间也算了进去，**是你发现了这个问题**，之后我改成了只统计活跃窗口。
4. **分桶直方图的精度问题。** 你质疑分桶会损失精度，并要求我计算用 List 存储的内存开销。这是你对 AI 的方案进行了检验，而不是直接接受。
5. **README 的质量。** 你认为我生成的内容质量不够好，之后改为逐个给出明确的要求，由你来主导内容。

### AI 自己纠正的错误

6. **ChannelPool 的 `isOpen()` 检查和自动恢复功能相冲突。** 我最初建议归还 channel 时，如果发现它已经关闭，就丢弃并创建一个新的。后来在讨论自动恢复功能时，我发现这个做法会在连接断开期间清空整个池，于是修正为区分“连接断开了”和“只是这个 channel 坏了”这两种情况。

### 你采纳的建议（根据你后来提供的信息可以确认的）

7. **SessionSender**（`git status` 里出现了 `SessionSender.java`）：每个 session 同一时刻只有一条消息在发送，只保留最新的版本。
8. **Backplane 方案和合并推送**（你的 consumer README 里写了 `leaderboard.fanout`、每 200 毫秒合并发布一次）。
9. **客户端关闭 cookie 管理**（你的 client-v2 README 里写了这一点）。
10. **health 在 RabbitMQ 断开时返回 503**（你的 server-v2 README 里写了这一点）。
11. **把客户端迁移到 EC2 上运行**，以及使用 Docker 和 systemd 进行部署。

### 你自己的判断，或者没有照搬 AI 的地方

12. **SERVER_ID 用环境变量设置。** 我建议使用主机名，你选择了环境变量（我提醒了用 AMI 复制的机器会使用相同的值，你最终是如何处理的，我无法确认）。
13. **failover 实验的基本思路是你提出的**，我只是在细节上做了补充。
14. **consumer lag 的边界。** 是你主动提出了“边界应该如何界定”这个问题；最终采用哪个定义，需要你自己说明理由。
15. **consumer 的数据结构是你先设计的**，我只是确认了它，并讨论了其中的取舍。

### 可以写成“需要自己推理才能得出结论”的问题

这些问题我在讨论中提出过，但最终的结论需要你自己能够解释清楚：

- vertical 的累加满足交换律，那么作业为什么还要求按顺序处理？
- 发布排行榜失败时，为什么依然要 ack 这条 swipe？
- 去重只能覆盖 broker 的重复投递，不能覆盖客户端重试导致的重复。
- 作业要求测试 10 到 80 个 consumer 线程，但只有 10 个队列，你是如何处理这个矛盾的（你测试了 `consumer5` 和 `consumer15`）？

---

## 写反思时的建议

- **从第二部分里挑选你自己真正经历过、并且能够解释清楚的例子。** 作业明确说了，你可能会被要求解释其中的任何一部分。
- **“你发现了 AI 的错误”这一类最有说服力。** 第 1、2、3 条都有具体的对话可以作为证据。
- **附上对话记录。** 作业建议提供分享链接，或者把对话导出到 repo 的 `transcripts/` 目录。这样，你在反思中写到的每一个例子，都能在对话里找到对应的原文。

## User

你先写一个模版我看一下，要求所有分点都用1. 2.3.4.这种，格式不要复杂，要呼应作业的AI usage要求

## Claude

`AI_USAGE.md` 的模板写好了，用英文写，所有的分点都用 1. 2. 3. 编号，没有使用复杂的格式。各个部分和作业的要求一一对应：

1. **Where I Used AI**：对应作业要求的“在哪里、用于什么，要具体”。
2. **Suggestions I Accepted**：对应“至少两条采纳的建议”，我写了四条，每条都说明了原因。
3. **Suggestions I Rejected or Corrected**：对应“至少两条拒绝或修改过的建议”。
4. **What the AI Got Wrong, and What I Reasoned Through Myself**：对应“一个 AI 出错、或者你自己推理决定的地方”。
5. **Reflection**：一两句总结。
6. **Transcripts**：对应作业建议附上的对话记录。

需要你填写或者确认的地方，都用 `<...>` 标了出来：

1. **开头那段**：说明哪些代码是你自己写的、哪些文件是 AI 生成的。这一点要如实写，作业也强调过你需要理解并能够解释提交的每一行代码。
2. **第 3 部分第 1 条**：补上你最终是如何解决版本号问题的。
3. **第 3 部分第 3 条**：SERVER_ID 的那一条，只有在你确实是有意选择环境变量、并且能说出理由时才保留，否则就删掉。
4. **第 4 部分第 2 条**：填写一个你自己做出的设计决定，从括号里的几个例子中挑一个你最能解释清楚的。
5. **第 5 部分和第 6 部分**：你自己的总结，以及对话记录的链接。

另外，第 2、3、4 部分里的每一条，都是根据我们对话中实际发生的事情写的，但写成了第一人称。提交之前，请逐条检查是否符合你真实的经历和代码，如果有不符合的，就修改或者删除。

## User

怎么导出我和你的对话？

## Claude

有两种官方的方法，适合不同的用途。

## 方法一：生成分享链接（最适合这次作业）

作业里建议的就是附上一个分享链接。操作步骤是：点击对话右上角的 "Share" 按钮，然后在弹出的窗口里点击 "Share"，生成一个可分享的链接。

需要知道的几点：

1. **它是一张快照。** 分享的是你点击分享那一刻的对话快照，之后发送的消息会保持私密，除非你更新快照。所以，最好在你**和我的讨论全部结束之后**再生成链接，或者在提交之前更新一次快照，这样链接里才会包含完整的对话。
2. **谁能看到。** 公开链接在 Free、Pro 和 Max 套餐中可用，Team 和 Enterprise 成员只能在组织内部分享对话。如果你用的是学校或者团队的账户，公开链接可能无法使用。这时可以改用邀请的方式：通过邮箱邀请特定的人查看对话，这个功能在所有套餐中都可用。被邀请的人可以查看对话的快照，但不能回复、复制到自己的账户中，也不能继续这段对话。你可以邀请助教或者老师的邮箱。
3. **上传的文件不会被包含。** 如果分享的对话里包含附件，附件本身不会被包含在快照中，仍然保持私密，有链接的人只能看到对话内容和 Claude 的回复。
4. **随时可以取消。** 你可以随时在 "Share" 菜单里，或者在 Settings > Privacy 的 Shared chats 里关闭分享，关闭之后链接就会失效。

详细的说明可以参考官方的帮助文章：[Share and unshare chats](https://support.claude.com/en/articles/10593882-share-and-unshare-chats)

## 方法二：导出账户数据（适合放进 `transcripts/` 目录）

如果你想把对话保存成文件，放进 repo 的 `transcripts/` 目录，可以使用账户数据导出功能：它位于 Settings、Privacy、Export data 下，并且不能在 iOS 或 Android 应用中操作。导出完成后，你会收到一封带有下载链接的邮件。你必须登录账户才能通过这个链接下载数据，链接会在发送后 24 小时内过期；如果过期了，可以重新执行一次导出。

要注意的是，这种方式导出的是你账户里的**全部数据**，格式是 JSON，并不是单独的一份对话。你需要从中找到这一段对话，再整理成可读的格式，相对麻烦一些。

## 对这次作业的建议

**直接使用分享链接就够了**，把它填进 `AI_USAGE.md` 的 Transcripts 部分即可。

生成公开链接之前，建议先浏览一遍对话内容，确认里面没有不想公开的信息。比如这段对话里出现过你的 ALB 的 DNS 名称。好在你提交作业之后就会删除 ALB，到时候这个地址也就失效了。另外，对话里出现过的 RabbitMQ 密码都是用占位符表示的，没有泄露真实的密码。
