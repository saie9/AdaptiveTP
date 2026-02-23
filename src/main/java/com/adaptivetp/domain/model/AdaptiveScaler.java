package com.adaptivetp.domain.model;

import com.adaptivetp.config.AutoTuneProperties;
import com.adaptivetp.domain.model.entity.SchedulePlan;
import com.adaptivetp.domain.model.entity.ThreadPoolConfigEntity;
import com.adaptivetp.domain.model.entity.ThreadPoolSnapshot;
import com.adaptivetp.trigger.DashboardController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 自适应扩缩容决策引擎（核心）
 * <p>
 * 整合自适应模式和计划模式，每个采集周期对每个线程池执行一次决策。
 * <p>
 * 完整决策流程：
 * 1. 检查计划模式是否有切换点到达 → 执行计划调参，进入冷却期
 * 2. 检查是否有任务被拒绝 → 紧急扩容（跳过冷却期和连续点判定）
 * 3. 检查是否在冷却期内 → 是则跳过本轮判定
 * 4. 获取当前基线值（计划值 or 初始配置值）作为缩容下限
 * 5. 滑动窗口判定：连续 N 个点超阈值 → 扩容；连续 M 个点低于阈值 → 缩容
 * 6. 根据活跃率变化速率决定扩容幅度（温和/正常/激进/紧急）
 * 7. 资源边界检查（JVM内存、全局线程预算、GC健康度）
 * 8. 执行调参（保证 core/max 调整顺序正确，防止 IllegalArgumentException）
 * <p>
 * 两种模式结合规则：
 * - 计划值作为自适应的动态地板，缩容不会低于当前时段的计划值
 * - 自适应扩容可以超过计划值（应对突发流量），但不超过硬上限
 * - 没有计划时，初始配置值作为缩容下限
 */
public class AdaptiveScaler {

    private static final Logger logger = LoggerFactory.getLogger(AdaptiveScaler.class);

    private final AutoTuneProperties properties;
    private final MetricsCollector metricsCollector;
    private final ResourceGuard resourceGuard;
    private final ScheduledScaler scheduledScaler;
    private final IDynamicThreadPoolService threadPoolService;
    private final Map<String, ThreadPoolExecutor> threadPoolExecutorMap;

    /** 每个线程池的初始配置（启动时记录），无计划模式时作为缩容下限 */
    private final Map<String, int[]> initialConfigs = new ConcurrentHashMap<>();

    /** 每个线程池上次调参的时间戳，用于冷却期判定 */
    private final Map<String, Long> lastAdjustTime = new ConcurrentHashMap<>();

    /** 每个线程池上次检查的时间戳，用于计划模式切换点检测 */
    private final Map<String, Long> lastCheckTime = new ConcurrentHashMap<>();

    /** 单个线程池的硬上限：CPU核数 * 配置倍数（IO密集型场景的经验值） */
    private final int perPoolMaxUpperBound;

    public AdaptiveScaler(AutoTuneProperties properties, MetricsCollector metricsCollector,
                          ResourceGuard resourceGuard, ScheduledScaler scheduledScaler,
                          IDynamicThreadPoolService threadPoolService,
                          Map<String, ThreadPoolExecutor> threadPoolExecutorMap,
                          Map<String, int[]> originalConfigs) {
        this.properties = properties;
        this.metricsCollector = metricsCollector;
        this.resourceGuard = resourceGuard;
        this.scheduledScaler = scheduledScaler;
        this.threadPoolService = threadPoolService;
        this.threadPoolExecutorMap = threadPoolExecutorMap;
        this.perPoolMaxUpperBound = Runtime.getRuntime().availableProcessors() * properties.getPerPoolMaxMultiplier();

        // 使用传入的原始配置（@Bean 定义时的值，不受 Redis 恢复影响）
        this.initialConfigs.putAll(originalConfigs);
    }

    /**
     * 对单个线程池执行一次自适应判定（每个采集周期调用一次）
     */
    public void evaluate(String poolName) {
        ThreadPoolExecutor executor = threadPoolExecutorMap.get(poolName);
        if (executor == null) return;

        long now = System.currentTimeMillis();

        // 1. 检查计划模式切换点
        Long lastCheck = lastCheckTime.getOrDefault(poolName, now);
        SchedulePlan trigger = scheduledScaler.checkScheduleTrigger(poolName, lastCheck);
        lastCheckTime.put(poolName, now);
        if (trigger != null) {
            logger.info("[计划模式] 线程池 {} 到达计划切换点 {}:{} → core={}, max={}",
                    poolName, trigger.getHour(), trigger.getMinute(),
                    trigger.getCorePoolSize(), trigger.getMaximumPoolSize());
            doAdjust(poolName, trigger.getCorePoolSize(), trigger.getMaximumPoolSize(), "SCHEDULE");
            return; // 计划切换后进入冷却期，本轮不再做自适应判定
        }

        // 2. 紧急扩容：检测到拒绝，跳过冷却期
        if (metricsCollector.hasReject(poolName)) {
            int emergencyMax = (int) (perPoolMaxUpperBound * 0.8);
            int emergencyCore = (int) (emergencyMax * 0.6);
            emergencyMax = resourceGuard.clampByBudget(poolName, emergencyMax);
            emergencyCore = Math.min(emergencyCore, emergencyMax);
            logger.warn("[紧急扩容] 线程池 {} 检测到任务拒绝！直接扩容到 core={}, max={}",
                    poolName, emergencyCore, emergencyMax);
            if (resourceGuard.canExpand(poolName, emergencyCore, emergencyMax)) {
                doAdjust(poolName, emergencyCore, emergencyMax, "EMERGENCY");
            } else {
                logger.error("[紧急扩容] 线程池 {} 资源不足，无法扩容，需要扩容机器！", poolName);
            }
            return;
        }

        // 3. 冷却期检查
        Long lastAdjust = lastAdjustTime.get(poolName);
        if (lastAdjust != null && (now - lastAdjust) < properties.getCooldownSeconds() * 1000L) {
            return; // 冷却中
        }

        // 4. 获取当前基线值（计划值 or 初始值）
        int[] baseline = getBaseline(poolName);
        int baselineCore = baseline[0];
        int baselineMax = baseline[1];

        int currentCore = executor.getCorePoolSize();
        int currentMax = executor.getMaximumPoolSize();

        // 5. 扩容判定
        if (metricsCollector.isExpandNeeded(poolName, properties.getExpandConsecutivePoints(),
                properties.getQueueExpandThreshold(), properties.getActiveExpandThreshold())) {

            double velocity = metricsCollector.calcActiveRateVelocity(poolName, properties.getExpandConsecutivePoints());
            double factor = calcExpandFactor(velocity);

            int targetCore = Math.min((int) (currentCore * factor), perPoolMaxUpperBound);
            int targetMax = Math.min((int) (currentMax * factor), perPoolMaxUpperBound);
            targetMax = resourceGuard.clampByBudget(poolName, targetMax);
            targetCore = Math.min(targetCore, targetMax);

            if (targetMax > currentMax && resourceGuard.canExpand(poolName, targetCore, targetMax)) {
                logger.info("[自适应扩容] 线程池 {} 速率={} 系数={} → core: {}→{}, max: {}→{}",
                        poolName, String.format("%.3f", velocity), String.format("%.2f", factor),
                        currentCore, targetCore, currentMax, targetMax);
                doAdjust(poolName, targetCore, targetMax, "ADAPTIVE_EXPAND");
            }
            return;
        }

        // 6. 缩容判定
        if (metricsCollector.isShrinkNeeded(poolName, properties.getShrinkConsecutivePoints(),
                properties.getQueueShrinkThreshold(), properties.getActiveShrinkThreshold())) {

            int targetCore = Math.max((int) (currentCore * properties.getShrinkFactor()), baselineCore);
            int targetMax = Math.max((int) (currentMax * properties.getShrinkFactor()), baselineMax);
            targetCore = Math.min(targetCore, targetMax);

            if (targetCore < currentCore || targetMax < currentMax) {
                logger.info("[自适应缩容] 线程池 {} → core: {}→{}, max: {}→{} (基线: core={}, max={})",
                        poolName, currentCore, targetCore, currentMax, targetMax, baselineCore, baselineMax);
                doAdjust(poolName, targetCore, targetMax, "ADAPTIVE_SHRINK");
            }
        }
    }

    /**
     * 根据活跃率变化速率计算扩容系数
     * <p>
     * 速率越大说明流量爬升越快，需要更激进的扩容。
     * - 速率 > 25%/周期 → 暴涨，系数 2.0（线程数翻倍）
     * - 速率 15%-25%    → 快涨，系数 1.75
     * - 速率 5%-15%     → 正常，系数 1.5
     * - 速率 < 5%       → 慢涨，系数 1.25（温和扩容）
     *
     * @param velocity 每个采集周期的活跃率变化量
     * @return 扩容系数，乘以当前值得到目标值
     */
    private double calcExpandFactor(double velocity) {
        if (velocity > 0.25) return 2.0;    // 暴涨：激进扩容
        if (velocity > 0.15) return 1.75;   // 快涨
        if (velocity > 0.05) return 1.5;    // 正常
        return 1.25;                         // 慢涨：温和扩容
    }

    /**
     * 获取当前时段的基线值（缩容下限 / 动态地板）
     * <p>
     * 有计划模式时：返回当前时段的计划值（如 8:00-12:00 → core=20, max=50）
     * 无计划模式时：返回启动时记录的初始配置值
     * <p>
     * 自适应缩容时，线程池参数不会低于此基线值，
     * 防止低谷期过度缩容导致下一波高峰来时响应不及时
     */
    private int[] getBaseline(String poolName) {
        SchedulePlan plan = scheduledScaler.getCurrentBaseline(poolName);
        if (plan != null) {
            return new int[]{plan.getCorePoolSize(), plan.getMaximumPoolSize()};
        }
        int[] initial = initialConfigs.get(poolName);
        return initial != null ? initial : new int[]{1, 1};
    }

    /**
     * 执行调参（统一入口）
     * <p>
     * 关键：调参顺序必须正确！
     * - 如果新 corePoolSize > 旧 maximumPoolSize，必须先调 max 再调 core
     * - 否则 JDK 会抛 IllegalArgumentException（core 不能大于 max）
     * - 反之，先调 core 再调 max
     *
     * @param poolName   线程池名称
     * @param targetCore 目标核心线程数
     * @param targetMax  目标最大线程数
     * @param reason     调参原因（SCHEDULE/EMERGENCY/ADAPTIVE_EXPAND/ADAPTIVE_SHRINK）
     */
    private void doAdjust(String poolName, int targetCore, int targetMax, String reason) {
        ThreadPoolExecutor executor = threadPoolExecutorMap.get(poolName);
        if (executor == null) return;

        int oldCore = executor.getCorePoolSize();
        int oldMax = executor.getMaximumPoolSize();

        // 调参顺序：如果新 core > 旧 max，先调 max 再调 core；否则先调 core 再调 max
        if (targetCore > oldMax) {
            executor.setMaximumPoolSize(targetMax);
            executor.setCorePoolSize(targetCore);
        } else {
            executor.setCorePoolSize(targetCore);
            executor.setMaximumPoolSize(targetMax);
        }

        lastAdjustTime.put(poolName, System.currentTimeMillis());

        // 记录调参历史到 Dashboard
        try { DashboardController.recordAdjust(poolName, reason, oldCore, targetCore, oldMax, targetMax); } catch (Exception ignored) {}

        logger.info("[调参完成] 线程池 {}, 原因={}, core: {}→{}, max: {}→{}",
                poolName, reason, oldCore, targetCore, oldMax, targetMax);
    }
}
