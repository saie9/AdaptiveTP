package com.adaptivetp.config;

/**
 * 自适应调参配置属性
 * <p>
 * 通过 yml 配置：dynamic.thread.pool.config.auto-tune.*
 * 控制自适应扩缩容的各项阈值、冷却期、缓冲区大小和资源预算。
 * <p>
 * 配置示例：
 * <pre>
 * dynamic:
 *   thread:
 *     pool:
 *       config:
 *         auto-tune:
 *           enabled: true
 *           queue-expand-threshold: 0.8    # 队列使用率超过80%触发扩容判定
 *           active-expand-threshold: 0.9   # 线程活跃率超过90%触发扩容判定
 *           cooldown-seconds: 60           # 调参后冷却60秒
 * </pre>
 */
public class AutoTuneProperties {

    /** 是否启用自适应模式，默认关闭，需要手动开启 */
    private boolean enabled = false;

    /** 队列使用率扩容阈值（0-1），超过此值认为队列压力大 */
    private double queueExpandThreshold = 0.8;

    /** 线程活跃率扩容阈值（0-1），超过此值认为线程池压力大 */
    private double activeExpandThreshold = 0.9;

    /** 队列使用率缩容阈值（0-1），低于此值认为队列空闲 */
    private double queueShrinkThreshold = 0.2;

    /** 线程活跃率缩容阈值（0-1），低于此值认为线程池空闲 */
    private double activeShrinkThreshold = 0.3;

    /** 扩容需要连续满足条件的采集点数（防止瞬时毛刺误触发） */
    private int expandConsecutivePoints = 3;

    /** 缩容需要连续满足条件的采集点数（缩容比扩容更谨慎） */
    private int shrinkConsecutivePoints = 6;

    /** 调参后冷却时间（秒），冷却期内不做任何调整，等待新参数生效 */
    private int cooldownSeconds = 60;

    /** 环形缓冲区大小，保留最近多少个采集点（每20秒一个，30个=10分钟） */
    private int bufferSize = 30;

    /** 全局线程预算上限，所有被管理的线程池的 maximumPoolSize 之和不超过此值 */
    private int totalThreadBudget = 500;

    /** JVM 堆内存使用率告警阈值（0-1），超过此值拒绝扩容 */
    private double memoryAlertThreshold = 0.85;

    // ==================== 报警配置 ====================

    /** 是否启用邮件报警 */
    private boolean alertEnabled = false;

    /** 发件人邮箱地址 */
    private String alertFrom = "";

    /** 收件人邮箱地址（多个用逗号分隔） */
    private String alertTo = "";

    /** 报警-活跃率阈值（0-1），超过此值触发报警 */
    private double alertActiveThreshold = 0.9;

    /** 报警-队列使用率阈值（0-1），超过此值触发报警 */
    private double alertQueueThreshold = 0.8;

    /** 报警-内存使用率阈值（0-1），超过此值触发报警 */
    private double alertMemoryThreshold = 0.85;

    /** 同类型报警冷却时间（秒），冷却期内不重复发送 */
    private int alertCooldownSeconds = 300;

    /** 全局邮件发送间隔（秒），两封邮件之间至少间隔此值 */
    private int globalEmailIntervalSeconds = 60;

    // ==================== 自适应高级参数 ====================

    /** 单个线程池硬上限 = CPU核数 * 此倍数（IO密集型建议20，CPU密集型建议2~4） */
    private int perPoolMaxMultiplier = 20;

    /** 缩容系数，每次缩容到当前值的此比例（0-1），越大缩得越慢 */
    private double shrinkFactor = 0.75;

    /** GC 耗时占 JVM 运行时间的比例阈值，超过则拒绝扩容（0-1） */
    private double gcOverloadThreshold = 0.10;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public double getQueueExpandThreshold() { return queueExpandThreshold; }
    public void setQueueExpandThreshold(double v) { this.queueExpandThreshold = v; }
    public double getActiveExpandThreshold() { return activeExpandThreshold; }
    public void setActiveExpandThreshold(double v) { this.activeExpandThreshold = v; }
    public double getQueueShrinkThreshold() { return queueShrinkThreshold; }
    public void setQueueShrinkThreshold(double v) { this.queueShrinkThreshold = v; }
    public double getActiveShrinkThreshold() { return activeShrinkThreshold; }
    public void setActiveShrinkThreshold(double v) { this.activeShrinkThreshold = v; }
    public int getExpandConsecutivePoints() { return expandConsecutivePoints; }
    public void setExpandConsecutivePoints(int v) { this.expandConsecutivePoints = v; }
    public int getShrinkConsecutivePoints() { return shrinkConsecutivePoints; }
    public void setShrinkConsecutivePoints(int v) { this.shrinkConsecutivePoints = v; }
    public int getCooldownSeconds() { return cooldownSeconds; }
    public void setCooldownSeconds(int v) { this.cooldownSeconds = v; }
    public int getBufferSize() { return bufferSize; }
    public void setBufferSize(int v) { this.bufferSize = v; }
    public int getTotalThreadBudget() { return totalThreadBudget; }
    public void setTotalThreadBudget(int v) { this.totalThreadBudget = v; }
    public double getMemoryAlertThreshold() { return memoryAlertThreshold; }
    public void setMemoryAlertThreshold(double v) { this.memoryAlertThreshold = v; }

    public boolean isAlertEnabled() { return alertEnabled; }
    public void setAlertEnabled(boolean v) { this.alertEnabled = v; }
    public String getAlertFrom() { return alertFrom; }
    public void setAlertFrom(String v) { this.alertFrom = v; }
    public String getAlertTo() { return alertTo; }
    public void setAlertTo(String v) { this.alertTo = v; }
    public double getAlertActiveThreshold() { return alertActiveThreshold; }
    public void setAlertActiveThreshold(double v) { this.alertActiveThreshold = v; }
    public double getAlertQueueThreshold() { return alertQueueThreshold; }
    public void setAlertQueueThreshold(double v) { this.alertQueueThreshold = v; }
    public double getAlertMemoryThreshold() { return alertMemoryThreshold; }
    public void setAlertMemoryThreshold(double v) { this.alertMemoryThreshold = v; }
    public int getAlertCooldownSeconds() { return alertCooldownSeconds; }
    public void setAlertCooldownSeconds(int v) { this.alertCooldownSeconds = v; }
    public int getGlobalEmailIntervalSeconds() { return globalEmailIntervalSeconds; }
    public void setGlobalEmailIntervalSeconds(int v) { this.globalEmailIntervalSeconds = v; }
    public int getPerPoolMaxMultiplier() { return perPoolMaxMultiplier; }
    public void setPerPoolMaxMultiplier(int v) { this.perPoolMaxMultiplier = v; }
    public double getShrinkFactor() { return shrinkFactor; }
    public void setShrinkFactor(double v) { this.shrinkFactor = v; }
    public double getGcOverloadThreshold() { return gcOverloadThreshold; }
    public void setGcOverloadThreshold(double v) { this.gcOverloadThreshold = v; }
}
