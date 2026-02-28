# AdaptiveTP Spring Boot Starter

一个功能完整的动态线程池管理中间件，基于 Spring Boot Starter 机制实现零代码侵入接入。支持运行时动态调参、自适应扩缩容、计划模式、邮件报警、内置可视化监控面板。

## 核心功能

### 1. 动态调参
- 运行时通过 Redis 发布/订阅动态修改线程池 `corePoolSize`、`maximumPoolSize`
- 启动时自动从 Redis 恢复上次保存的配置（调参顺序安全，防止 `IllegalArgumentException`）
- 支持前端面板手动调参，立即生效

### 2. 自适应扩缩容
- **滑动窗口判定**：基于环形缓冲区采集快照，连续 N 个点超阈值才触发，防止毛刺误判
- **紧急扩容**：检测到任务拒绝时跳过冷却期，直接扩容到安全水位
- **速率感知扩容**：根据活跃率变化速率决定扩容幅度（温和 1.25x / 正常 1.5x / 激进 1.75x / 暴涨 2.0x）
- **安全缩容**：缩容不低于基线值（计划值或初始配置），防止过度缩容
- **资源边界保护**：JVM 内存、全局线程预算、GC 健康度三重检查

### 3. 计划模式
- 按时间段预设线程池参数（如早高峰 8:00 扩容，凌晨 2:00 缩容）
- 支持按星期配置（工作日/周末不同策略）
- 计划值作为自适应的"动态地板"，缩容不低于当前时段计划值
- 配置存储在 Redis，通过前端面板管理

### 4. 邮件报警系统
- **4 种报警规则**：任务拒绝（CRITICAL）、活跃率过高（WARNING）、队列过高（WARNING）、JVM 内存过高（CRITICAL）
- **每种类型独立开关**，可在前端动态启用/禁用
- **收件人管理**：前端 tag 式管理，支持多个收件人独立添加/删除
- **双重频率限制**：同类型冷却期 + 全局邮件发送间隔，防止邮件轰炸
- **报警历史**：内存保留最近 200 条记录，前端实时展示

### 5. 内置监控面板
引入 starter 后访问 `http://localhost:{port}/dtp/index.html` 即可打开，无需额外部署前端。

面板功能：
- 线程池状态卡片（动态数量，按实际线程池生成）
- JVM 资源概览（堆内存、GC、线程预算进度条）
- Canvas 实时趋势图（活跃率、队列使用率）
- 压测控制（持续加压 / 单次提交）
- 手动调参
- 计划模式管理（CRUD）
- 报警配置（收件人、类型开关、阈值、频率）
- 调参历史 / 采集快照 / 报警记录
- 操作日志

## 快速开始

### 1. 引入依赖

```xml
<dependency>
    <groupId>com.dynamictp</groupId>
    <artifactId>adaptiveTP-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
<!-- 如需邮件报警 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-mail</artifactId>
</dependency>
```

### 2. 配置 application.yml

```yaml
spring:
  application:
    name: my-app
  mail:  # 邮件报警需要（可选）
    host: smtp.qq.com
    port: 587
    username: your-email@qq.com
    password: your-smtp-authorization-code  # QQ邮箱SMTP授权码
    properties:
      mail.smtp.auth: true
      mail.smtp.starttls.enable: true

dynamic:
  thread:
    pool:
      config:
        enable: true
        host: 127.0.0.1       # Redis 地址
        port: 6379
        auto-tune:
          enabled: true        # 启用自适应模式
          # 扩容阈值
          queue-expand-threshold: 0.7
          active-expand-threshold: 0.8
          # 缩容阈值
          queue-shrink-threshold: 0.3
          active-shrink-threshold: 0.3
          # 判定点数
          expand-consecutive-points: 3
          shrink-consecutive-points: 6
          # 冷却与缓冲
          cooldown-seconds: 60
          buffer-size: 30
          # 资源限制
          total-thread-budget: 200
          memory-alert-threshold: 0.85
          # 邮件报警
          alert-enabled: true
          alert-from: your-email@qq.com
          alert-to: admin@company.com
          alert-active-threshold: 0.9
          alert-queue-threshold: 0.8
          alert-memory-threshold: 0.85
          alert-cooldown-seconds: 300
```

### 3. 定义线程池 Bean

```java
@Configuration
public class ThreadPoolConfig {

    @Bean("orderProcessPool")
    public ThreadPoolExecutor orderProcessPool() {
        return new ThreadPoolExecutor(5, 10, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(50),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean("messagePushPool")
    public ThreadPoolExecutor messagePushPool() {
        return new ThreadPoolExecutor(3, 8, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(30),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
```

### 4. 访问监控面板

启动应用后访问：`http://localhost:8080/dtp/index.html`

## 架构设计

```
┌─────────────────────────────────────────────────────┐
│                    Application                       │
│  ┌──────────────┐  ┌──────────────┐                 │
│  │ orderPool    │  │ messagePushPool│  ...           │
│  └──────┬───────┘  └──────┬───────┘                 │
├─────────┼──────────────────┼────────────────────────┤
│         │   DynamicTP Starter  │                        │
│  ┌──────▼──────────────────▼───────┐                │
│  │     DynamicThreadPoolService     │  ← 发现并管理  │
│  └──────┬──────────────────────────┘                │
│         │                                            │
│  ┌──────▼──────────┐  ┌────────────────┐            │
│  │ MetricsCollector │  │ RedisRegistry   │           │
│  │ (环形缓冲区采集)  │  │ (上报+恢复配置) │           │
│  └──────┬──────────┘  └────────────────┘            │
│         │                                            │
│  ┌──────▼──────────┐  ┌────────────────┐            │
│  │ AdaptiveScaler   │  │ ScheduledScaler │           │
│  │ (自适应决策引擎)  │  │ (计划模式)      │           │
│  └──────┬──────────┘  └────────────────┘            │
│         │                                            │
│  ┌──────▼──────────┐  ┌────────────────┐            │
│  │ ResourceGuard    │  │ AlertManager    │           │
│  │ (资源边界保护)    │  │ (邮件报警)      │           │
│  └─────────────────┘  └────────────────┘            │
│                                                      │
│  ┌──────────────────────────────────────┐            │
│  │ DashboardController + index.html     │            │
│  │ (内置监控面板，/dtp/**)               │            │
│  └──────────────────────────────────────┘            │
└─────────────────────────────────────────────────────┘
```

## 核心类说明

| 类 | 职责 |
|---|---|
| `DynamicThreadPoolAutoConfig` | 自动装配入口，发现线程池 Bean、包装拒绝策略、Redis 恢复配置 |
| `MetricsCollector` | 环形缓冲区采集快照，提供滑动窗口判定和速率计算 |
| `AdaptiveScaler` | 自适应决策引擎，整合扩容/缩容/紧急扩容/计划模式 |
| `ScheduledScaler` | 计划模式，按时间段预设参数，从 Redis 加载计划配置 |
| `ResourceGuard` | 资源边界保护，JVM 内存 + 全局线程预算 + GC 健康度 |
| `AlertManager` | 报警管理器，规则检查 + 去重 + 频率限制 + 邮件发送 |
| `CountableRejectedHandler` | 可计数拒绝策略包装器，统计拒绝次数供自适应使用 |
| `DashboardController` | 监控面板 REST API（状态/快照/趋势/调参/计划/报警/压测） |
| `DashboardAutoConfig` | 面板自动配置，静态资源映射 + CORS |
| `ThreadPoolDataReportJob` | 定时任务（20s），上报 Redis + 采集快照 + 自适应判定 + 报警检查 |

## 自适应决策流程

每 20 秒执行一次：

```
采集快照 → 检查计划切换点 → 检查任务拒绝（紧急扩容）
                                    ↓
                              检查冷却期
                                    ↓
                    获取基线值（计划值 or 初始配置）
                                    ↓
                  ┌─ 连续N点超阈值 → 计算速率 → 扩容
                  │
                  └─ 连续M点低阈值 → 缩容（不低于基线）
                                    ↓
                            资源边界检查
                                    ↓
                          执行调参（顺序安全）
                                    ↓
                            报警规则检查
```

## 报警规则

| 类型 | 级别 | 触发条件 | 说明 |
|------|------|---------|------|
| `REJECT` | CRITICAL | 检测到任务被拒绝 | 最严重，说明线程池和队列都满了 |
| `ACTIVE_HIGH` | WARNING | 活跃率 ≥ 阈值（默认 90%） | 线程池压力大，可能即将拒绝 |
| `QUEUE_HIGH` | WARNING | 队列使用率 ≥ 阈值（默认 80%） | 队列积压严重 |
| `MEMORY_HIGH` | CRITICAL | JVM 堆内存 ≥ 阈值（默认 85%） | 全局告警，影响所有线程池 |

频率限制：
- 同一线程池同一类型：冷却期内不重复（默认 300 秒）
- 全局邮件间隔：两封邮件之间至少间隔 N 秒（默认 60 秒）

## API 接口

所有接口前缀：`/dtp/api`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/status` | 所有线程池实时状态 |
| GET | `/pools` | 线程池名称列表 |
| GET | `/snapshots?count=5` | 最近 N 个采集快照 |
| GET | `/trend?count=30` | 趋势数据（供图表使用） |
| GET | `/jvm` | JVM 资源信息 |
| GET | `/history` | 调参历史记录 |
| GET | `/alerts` | 报警历史记录 |
| GET | `/alert-config` | 获取报警配置 |
| POST | `/alert-config` | 更新报警配置（动态生效） |
| POST | `/adjust?pool=x&core=5&max=10` | 手动调参 |
| GET | `/schedule/{poolName}` | 获取计划配置 |
| POST | `/schedule/{poolName}` | 保存计划配置 |
| DELETE | `/schedule/{poolName}` | 清空计划配置 |
| POST | `/pressure?pool=x&count=20&sleepMs=3000` | 单次压测 |
| POST | `/sustained-start?pool=x&rate=5&sleepMs=3000` | 开始持续加压 |
| POST | `/sustained-stop` | 停止持续加压 |

## 技术栈

- Spring Boot 3.x
- Redisson（Redis 客户端，配置存储 + 发布订阅）
- Spring Boot Starter Mail（邮件报警，可选）
- 原生 HTML/CSS/JS（内置监控面板，无框架依赖）


## 多实例部署

支持多实例部署时 Redis 数据隔离，每个实例通过 `spring.application.name` 自动隔离配置和计划数据，无需额外配置。

## License

[Apache License 2.0](LICENSE)

## Contributing

欢迎提交 Issue 和 Pull Request，作者：cyx
