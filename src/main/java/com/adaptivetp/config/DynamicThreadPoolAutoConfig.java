package com.adaptivetp.config;

import com.adaptivetp.domain.model.*;
import com.adaptivetp.domain.model.entity.ThreadPoolConfigEntity;
import com.adaptivetp.domain.model.valobj.RegistryEnumVO;
import com.adaptivetp.trigger.job.ThreadPoolDataReportJob;
import com.adaptivetp.registry.IRegistry;
import com.adaptivetp.registry.redis.RedisRegistry;
import com.adaptivetp.trigger.listener.ThreadPoolConfigAdjustListener;
import org.apache.commons.lang.StringUtils;
import org.redisson.Redisson;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.codec.JsonJacksonCodec;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 动态线程池自动配置类
 * <p>
 * Spring Boot 自动装配入口，负责：
 * 1. 创建 Redisson 客户端连接 Redis
 * 2. 发现 Spring 容器中所有 ThreadPoolExecutor Bean 并纳入管理
 * 3. 包装拒绝策略为可计数版本（自适应模式需要统计拒绝次数）
 * 4. 启动时从 Redis 恢复上次保存的线程池参数
 * 5. 注册定时上报任务、RTopic 监听器、自适应调参相关组件
 * <p>
 * 使用方只需引入 starter 依赖 + 配置 Redis 地址即可，零代码侵入。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(DynamicThreadPoolAutoProperties.class)
public class DynamicThreadPoolAutoConfig {

    private final Logger logger = LoggerFactory.getLogger(DynamicThreadPoolAutoConfig.class);

    private String applicationName;

    /** 实例标识（IP:Port），用于多实例场景下 Redis key 隔离 */
    private String instanceId;

    /** 记录每个线程池 @Bean 定义时的原始配置，作为缩容下限（在 Redis 恢复之前记录） */
    private final Map<String, int[]> originalConfigs = new ConcurrentHashMap<>();

    @Bean("dynamicThreadPollService")
    public DynamicThreadPoolService dynamicThreadPoolService(ApplicationContext applicationContext, Map<String, ThreadPoolExecutor> threadPoolExecutorMap, RedissonClient redissonClient) {
        applicationName = applicationContext.getEnvironment().getProperty("spring.application.name");

        if (StringUtils.isBlank(applicationName)) {
            applicationName = "缺省的。。。";
            logger.warn("动态线程池，启动提示。SpringBoot 应用未配置 spring.application.name 无法获取到应用名称！");
        }

        // 生成实例标识：IP:Port，多实例部署时每个实例唯一
        String serverPort = applicationContext.getEnvironment().getProperty("server.port", "8080");
        try {
            String ip = InetAddress.getLocalHost().getHostAddress();
            instanceId = ip + ":" + serverPort;
        } catch (Exception e) {
            instanceId = "unknown:" + serverPort;
            logger.warn("动态线程池，获取本机IP失败，使用默认实例标识: {}", instanceId);
        }
        logger.info("动态线程池，实例标识: {}", instanceId);

        // 包装拒绝策略为可计数版本（自适应模式需要）
        for (Map.Entry<String, ThreadPoolExecutor> entry : threadPoolExecutorMap.entrySet()) {
            ThreadPoolExecutor executor = entry.getValue();
            // 在 Redis 恢复之前，记录 @Bean 定义的原始配置（真正的缩容下限）
            originalConfigs.put(entry.getKey(),
                    new int[]{executor.getCorePoolSize(), executor.getMaximumPoolSize()});
            if (!(executor.getRejectedExecutionHandler() instanceof CountableRejectedHandler)) {
                executor.setRejectedExecutionHandler(
                        new CountableRejectedHandler(executor.getRejectedExecutionHandler(), entry.getKey()));
            }
        }

        // 获取缓存数据，设置本地线程池配置（按实例隔离的 key）
        Set<String> threadPoolKeys = threadPoolExecutorMap.keySet();
        for (String threadPoolKey : threadPoolKeys) {
            try {
                // 优先读本实例的配置，如果没有则回退到旧的无实例 key（兼容升级）
                String instanceKey = RegistryEnumVO.THREAD_POOL_CONFIG_PARAMETER_LIST_KEY.getKey() + "_" + applicationName + "_" + instanceId + "_" + threadPoolKey;
                String legacyKey = RegistryEnumVO.THREAD_POOL_CONFIG_PARAMETER_LIST_KEY.getKey() + "_" + applicationName + "_" + threadPoolKey;
                ThreadPoolConfigEntity threadPoolConfigEntity = redissonClient.<ThreadPoolConfigEntity>getBucket(instanceKey).get();
                if (threadPoolConfigEntity == null) {
                    threadPoolConfigEntity = redissonClient.<ThreadPoolConfigEntity>getBucket(legacyKey).get();
                }
                if (null == threadPoolConfigEntity) continue;
                ThreadPoolExecutor threadPoolExecutor = threadPoolExecutorMap.get(threadPoolKey);
                int newCore = threadPoolConfigEntity.getCorePoolSize();
                int newMax = threadPoolConfigEntity.getMaximumPoolSize();
                // 调参顺序：新core > 旧max时先调max再调core，防止 IllegalArgumentException
                if (newCore > threadPoolExecutor.getMaximumPoolSize()) {
                    threadPoolExecutor.setMaximumPoolSize(newMax);
                    threadPoolExecutor.setCorePoolSize(newCore);
                } else {
                    threadPoolExecutor.setCorePoolSize(newCore);
                    threadPoolExecutor.setMaximumPoolSize(newMax);
                }
            } catch (Exception e) {
                // Redis 中可能存有旧包名序列化的数据，反序列化会失败，跳过即可，下次上报会覆盖
                logger.warn("动态线程池，恢复线程池 {} 配置失败（可能是旧数据格式），跳过: {}", threadPoolKey, e.getMessage());
            }
        }

        return new DynamicThreadPoolService(applicationName, threadPoolExecutorMap);
    }

    @Bean("dynamicThreadRedissonClient")
    public RedissonClient redissonClient(DynamicThreadPoolAutoProperties properties) {
        Config config = new Config();
        // 根据需要可以设定编解码器；https://github.com/redisson/redisson/wiki/4.-%E6%95%B0%E6%8D%AE%E5%BA%8F%E5%88%97%E5%8C%96
        config.setCodec(JsonJacksonCodec.INSTANCE);

        config.useSingleServer()
                .setAddress("redis://" + properties.getHost() + ":" + properties.getPort())
                .setPassword(properties.getPassword())
                .setConnectionPoolSize(properties.getPoolSize())
                .setConnectionMinimumIdleSize(properties.getMinIdleSize())
                .setIdleConnectionTimeout(properties.getIdleTimeout())
                .setConnectTimeout(properties.getConnectTimeout())
                .setRetryAttempts(properties.getRetryAttempts())
                .setRetryInterval(properties.getRetryInterval())
                .setPingConnectionInterval(properties.getPingInterval())
                .setKeepAlive(properties.isKeepAlive())
                .setDatabase(10)
        ;

        RedissonClient redissonClient = Redisson.create(config);

        logger.info("动态线程池，注册器（redis）链接初始化完成。{} {} {}", properties.getHost(), properties.getPoolSize(), !redissonClient.isShutdown());

        return redissonClient;
    }

    @Bean
    public IRegistry redisRegistry(RedissonClient redissonClient) {
        return new RedisRegistry(redissonClient);
    }

    @Bean
    public ThreadPoolDataReportJob threadPoolDataReportJob(IDynamicThreadPoolService dynamicThreadPoolService,
                                                           IRegistry registry,
                                                           MetricsCollector metricsCollector,
                                                           AdaptiveScaler adaptiveScaler,
                                                           ScheduledScaler scheduledScaler,
                                                           DynamicThreadPoolAutoProperties properties,
                                                           Map<String, ThreadPoolExecutor> threadPoolExecutorMap,
                                                           AlertManager alertManager) {
        return new ThreadPoolDataReportJob(dynamicThreadPoolService, registry, metricsCollector,
                adaptiveScaler, scheduledScaler, properties.getAutoTune(), threadPoolExecutorMap, alertManager, instanceId);
    }

    @Bean
    public ThreadPoolConfigAdjustListener threadPoolConfigAdjustListener(IDynamicThreadPoolService dynamicThreadPoolService, IRegistry registry) {
        return new ThreadPoolConfigAdjustListener(dynamicThreadPoolService, registry);
    }

    @Bean(name = "dynamicThreadPoolRedisTopic")
    public RTopic dynamicThreadPoolRedisTopic(RedissonClient redissonClient, ThreadPoolConfigAdjustListener threadPoolConfigAdjustListener) {
        RTopic topic = redissonClient.getTopic(RegistryEnumVO.DYNAMIC_THREAD_POOL_REDIS_TOPIC.getKey() + "_" + applicationName);
        topic.addListener(ThreadPoolConfigEntity.class, threadPoolConfigAdjustListener);
        return topic;
    }

    // ==================== 自适应调参相关 Bean ====================

    @Bean
    public MetricsCollector metricsCollector(DynamicThreadPoolAutoProperties properties) {
        return new MetricsCollector(properties.getAutoTune().getBufferSize());
    }

    @Bean
    public ResourceGuard resourceGuard(DynamicThreadPoolAutoProperties properties,
                                       Map<String, ThreadPoolExecutor> threadPoolExecutorMap) {
        return new ResourceGuard(properties.getAutoTune(), threadPoolExecutorMap);
    }

    @Bean
    public ScheduledScaler scheduledScaler(RedissonClient redissonClient) {
        return new ScheduledScaler(redissonClient, applicationName);
    }

    @Bean
    public AdaptiveScaler adaptiveScaler(DynamicThreadPoolAutoProperties properties,
                                         MetricsCollector metricsCollector,
                                         ResourceGuard resourceGuard,
                                         ScheduledScaler scheduledScaler,
                                         IDynamicThreadPoolService dynamicThreadPoolService,
                                         Map<String, ThreadPoolExecutor> threadPoolExecutorMap) {
        return new AdaptiveScaler(properties.getAutoTune(), metricsCollector, resourceGuard,
                scheduledScaler, dynamicThreadPoolService, threadPoolExecutorMap, originalConfigs);
    }

    @Bean
    public AlertManager alertManager(MetricsCollector metricsCollector,
                                     DynamicThreadPoolAutoProperties properties,
                                     org.springframework.beans.factory.ObjectProvider<JavaMailSender> mailSenderProvider,
                                     RedissonClient redissonClient) {
        AutoTuneProperties tune = properties.getAutoTune();
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        AlertManager alertManager = new AlertManager(metricsCollector, mailSender, applicationName, redissonClient);
        alertManager.setEnabled(tune.isAlertEnabled());
        alertManager.setFromEmail(tune.getAlertFrom());
        alertManager.setToEmail(tune.getAlertTo());
        alertManager.setActiveAlertThreshold(tune.getAlertActiveThreshold());
        alertManager.setQueueAlertThreshold(tune.getAlertQueueThreshold());
        alertManager.setMemoryAlertThreshold(tune.getAlertMemoryThreshold());
        alertManager.setAlertCooldownSeconds(tune.getAlertCooldownSeconds());
        alertManager.setGlobalEmailIntervalSeconds(tune.getGlobalEmailIntervalSeconds());
        return alertManager;
    }

}
