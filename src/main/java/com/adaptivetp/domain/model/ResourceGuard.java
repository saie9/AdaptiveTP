package com.adaptivetp.domain.model;

import com.adaptivetp.config.AutoTuneProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 资源边界守卫
 * <p>
 * 扩容前的最后一道防线，检查服务器物理资源是否允许扩容。
 * 防止自适应模式无限扩容导致 OOM 或 CPU 上下文切换过载。
 * <p>
 * 检查项：
 * 1. JVM 堆内存使用率 → 超过阈值（默认85%）拒绝扩容
 * 2. 全局线程预算 → 所有线程池的 max 之和不超过预算上限
 * 3. GC 健康度 → GC 累计耗时占 JVM 运行时间超过10%认为过载
 * <p>
 * 如果扩容被拒绝，说明单机已到极限，应该发告警通知运维扩容机器
 */
public class ResourceGuard {

    private static final Logger logger = LoggerFactory.getLogger(ResourceGuard.class);

    private final AutoTuneProperties properties;
    private final Map<String, ThreadPoolExecutor> threadPoolExecutorMap;

    public ResourceGuard(AutoTuneProperties properties, Map<String, ThreadPoolExecutor> threadPoolExecutorMap) {
        this.properties = properties;
        this.threadPoolExecutorMap = threadPoolExecutorMap;
    }

    /**
     * 检查是否允许扩容
     *
     * @param poolName    要扩容的线程池名称
     * @param targetCore  目标核心线程数
     * @param targetMax   目标最大线程数
     * @return 允许扩容返回 true
     */
    public boolean canExpand(String poolName, int targetCore, int targetMax) {
        // 1. JVM 内存检查
        if (isMemoryTight()) {
            logger.warn("[资源守卫] JVM 内存使用率超过 {}%，拒绝线程池 {} 扩容，建议扩容机器",
                    (int) (properties.getMemoryAlertThreshold() * 100), poolName);
            return false;
        }

        // 2. 全局线程预算检查
        int currentTotal = getCurrentTotalThreads();
        ThreadPoolExecutor executor = threadPoolExecutorMap.get(poolName);
        if (executor == null) return false;

        int currentPoolMax = executor.getMaximumPoolSize();
        int delta = targetMax - currentPoolMax; // 本次扩容新增的线程数
        if (delta > 0 && (currentTotal + delta) > properties.getTotalThreadBudget()) {
            int available = properties.getTotalThreadBudget() - currentTotal;
            logger.warn("[资源守卫] 全局线程预算不足，当前总线程数={}，预算上限={}，可用={}，线程池 {} 请求新增 {} 个",
                    currentTotal, properties.getTotalThreadBudget(), Math.max(0, available), poolName, delta);
            return false;
        }

        // 3. GC 频率检查（简化版：看 GC 耗时占比）
        if (isGcOverloaded()) {
            logger.warn("[资源守卫] GC 压力过大，拒绝线程池 {} 扩容", poolName);
            return false;
        }

        return true;
    }

    /**
     * 在全局预算限制下，计算实际可扩容到的最大线程数
     * <p>
     * 当目标值超过预算时，不是直接拒绝，而是截断到预算允许的最大值。
     * 公式：available = totalBudget - 其他线程池的max之和
     *
     * @param poolName  线程池名称
     * @param targetMax 期望的最大线程数
     * @return 预算允许的最大线程数（可能小于 targetMax）
     */
    public int clampByBudget(String poolName, int targetMax) {
        int currentTotal = getCurrentTotalThreads();
        ThreadPoolExecutor executor = threadPoolExecutorMap.get(poolName);
        if (executor == null) return targetMax;

        int currentPoolMax = executor.getMaximumPoolSize();
        int available = properties.getTotalThreadBudget() - currentTotal + currentPoolMax;
        return Math.min(targetMax, available);
    }

    /** JVM 堆内存使用率是否超过阈值 */
    private boolean isMemoryTight() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
        long used = heapUsage.getUsed();
        long max = heapUsage.getMax();
        if (max <= 0) return false;
        double usageRate = (double) used / max;
        return usageRate > properties.getMemoryAlertThreshold();
    }

    /** 当前所有被管理的线程池的最大线程数总和 */
    private int getCurrentTotalThreads() {
        return threadPoolExecutorMap.values().stream()
                .mapToInt(ThreadPoolExecutor::getMaximumPoolSize)
                .sum();
    }

    /** 
     * GC 是否过载
     * <p>
     * 简化判断：如果 GC 累计耗时超过 JVM 运行时间的 10%，认为 GC 压力大。
     * 此时继续创建线程会加剧内存压力，导致更频繁的 GC，形成恶性循环。
     */
    private boolean isGcOverloaded() {
        long totalGcTime = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(gc -> gc.getCollectionTime())
                .sum();
        // 如果 GC 累计耗时超过 JVM 运行时间的 10%，认为 GC 压力大
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
        if (uptime <= 0) return false;
        return (double) totalGcTime / uptime > properties.getGcOverloadThreshold();
    }
}
