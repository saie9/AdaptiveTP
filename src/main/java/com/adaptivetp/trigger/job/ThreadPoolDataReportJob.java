package com.adaptivetp.trigger.job;

import com.adaptivetp.config.AutoTuneProperties;
import com.adaptivetp.domain.model.*;
import com.adaptivetp.domain.model.entity.ThreadPoolConfigEntity;
import com.adaptivetp.domain.model.entity.ThreadPoolSnapshot;
import com.adaptivetp.registry.IRegistry;
import com.alibaba.fastjson.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 线程池数据上报 + 自适应调参 + 报警检查任务
 * <p>
 * 每 20 秒执行一次：
 * 1. 上报线程池信息到 Redis（原有功能）
 * 2. 采集快照到环形缓冲区
 * 3. 刷新计划模式配置
 * 4. 执行自适应判定（如果启用）
 * 5. 执行报警检查（如果启用）
 */
public class ThreadPoolDataReportJob {

    private final Logger logger = LoggerFactory.getLogger(ThreadPoolDataReportJob.class);

    private final IDynamicThreadPoolService dynamicThreadPoolService;
    private final IRegistry registry;
    private final MetricsCollector metricsCollector;
    private final AdaptiveScaler adaptiveScaler;
    private final ScheduledScaler scheduledScaler;
    private final AutoTuneProperties autoTuneProperties;
    private final Map<String, ThreadPoolExecutor> threadPoolExecutorMap;
    private final AlertManager alertManager;
    private final String instanceId;

    public ThreadPoolDataReportJob(IDynamicThreadPoolService dynamicThreadPoolService, IRegistry registry,
                                   MetricsCollector metricsCollector, AdaptiveScaler adaptiveScaler,
                                   ScheduledScaler scheduledScaler, AutoTuneProperties autoTuneProperties,
                                   Map<String, ThreadPoolExecutor> threadPoolExecutorMap,
                                   AlertManager alertManager, String instanceId) {
        this.dynamicThreadPoolService = dynamicThreadPoolService;
        this.registry = registry;
        this.metricsCollector = metricsCollector;
        this.adaptiveScaler = adaptiveScaler;
        this.scheduledScaler = scheduledScaler;
        this.autoTuneProperties = autoTuneProperties;
        this.threadPoolExecutorMap = threadPoolExecutorMap;
        this.alertManager = alertManager;
        this.instanceId = instanceId;
    }

    @Scheduled(cron = "0/20 * * * * ?")
    public void execReportThreadPoolList() {
        // 1. 上报线程池信息（按实例隔离 + 兼容旧 key）
        List<ThreadPoolConfigEntity> threadPoolConfigEntities = dynamicThreadPoolService.queryThreadPoolList();
        registry.reportThreadPool(threadPoolConfigEntities);
        registry.reportThreadPool(threadPoolConfigEntities, instanceId);
        logger.info("动态线程池，上报线程池信息 [{}]：{}", instanceId, JSON.toJSONString(threadPoolConfigEntities));

        for (ThreadPoolConfigEntity entity : threadPoolConfigEntities) {
            registry.reportThreadPoolConfigParameter(entity);
            registry.reportThreadPoolConfigParameter(entity, instanceId);
        }

        // 2. 如果自适应模式未启用，到此结束
        if (!autoTuneProperties.isEnabled()) return;

        // 3. 采集快照到环形缓冲区
        for (Map.Entry<String, ThreadPoolExecutor> entry : threadPoolExecutorMap.entrySet()) {
            String poolName = entry.getKey();
            ThreadPoolExecutor executor = entry.getValue();

            long rejectCount = 0;
            if (executor.getRejectedExecutionHandler() instanceof CountableRejectedHandler) {
                rejectCount = ((CountableRejectedHandler) executor.getRejectedExecutionHandler()).getAndReset();
            }

            int queueSize = executor.getQueue().size();
            int queueCapacity = queueSize + executor.getQueue().remainingCapacity();

            ThreadPoolSnapshot snapshot = new ThreadPoolSnapshot(
                    System.currentTimeMillis(), poolName,
                    executor.getCorePoolSize(), executor.getMaximumPoolSize(),
                    executor.getActiveCount(), executor.getPoolSize(),
                    queueSize, queueCapacity, rejectCount);

            metricsCollector.record(snapshot);
        }

        // 4. 刷新计划模式配置
        scheduledScaler.refreshPlans(threadPoolExecutorMap.keySet());

        // 5. 对每个线程池执行自适应判定
        for (String poolName : threadPoolExecutorMap.keySet()) {
            try {
                adaptiveScaler.evaluate(poolName);
            } catch (Exception e) {
                logger.error("线程池 {} 自适应判定异常: {}", poolName, e.getMessage(), e);
            }
        }

        // 6. 报警检查
        try {
            alertManager.check();
        } catch (Exception e) {
            logger.error("报警检查异常: {}", e.getMessage(), e);
        }
    }
}
