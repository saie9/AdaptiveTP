package com.adaptivetp.domain.model;

import com.adaptivetp.domain.model.entity.ThreadPoolSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.redisson.api.RedissonClient;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * 报警管理器
 * <p>
 * 每个采集周期检查所有线程池的健康状态，触发报警规则时发送邮件通知。
 * 支持前端动态配置：收件人、报警类型开关、各阈值。
 * <p>
 * 报警类型：
 * 1. REJECT      - 检测到任务拒绝（CRITICAL）
 * 2. ACTIVE_HIGH - 活跃率超过阈值（WARNING）
 * 3. QUEUE_HIGH  - 队列使用率超过阈值（WARNING）
 * 4. MEMORY_HIGH - JVM堆内存超过阈值（CRITICAL）
 */
public class AlertManager {

    private static final Logger logger = LoggerFactory.getLogger(AlertManager.class);

    private final MetricsCollector metricsCollector;
    private final JavaMailSender mailSender;
    private final String applicationName;
    private final RedissonClient redissonClient;

    // 总开关
    private volatile boolean enabled;
    // 邮箱
    private volatile String fromEmail = "";
    private volatile String toEmail = "";
    // 各类型报警开关（默认全开）
    private volatile boolean rejectAlertEnabled = true;
    private volatile boolean activeAlertEnabled = true;
    private volatile boolean queueAlertEnabled = true;
    private volatile boolean memoryAlertEnabled = true;
    // 阈值
    private volatile double activeAlertThreshold = 0.9;
    private volatile double queueAlertThreshold = 0.8;
    private volatile double memoryAlertThreshold = 0.85;
    // 冷却
    private volatile int alertCooldownSeconds = 300;
    /** 全局邮件发送间隔（秒），不管什么类型，两封邮件之间至少间隔这么久 */
    private volatile long lastGlobalEmailTime = 0;
    private volatile int globalEmailIntervalSeconds = 60;

    /** 报警去重：key = poolName:alertType, value = 上次报警时间 */
    private final Map<String, Long> lastAlertTime = new ConcurrentHashMap<>();

    /** 报警历史记录（内存保留最近200条） */
    private static final List<Map<String, Object>> alertHistory = new CopyOnWriteArrayList<>();
    private static final int MAX_ALERT_HISTORY = 200;

    public AlertManager(MetricsCollector metricsCollector, JavaMailSender mailSender, String applicationName, RedissonClient redissonClient) {
        this.metricsCollector = metricsCollector;
        this.mailSender = mailSender;
        this.applicationName = applicationName;
        this.redissonClient = redissonClient;
    }

    // ==================== getter/setter ====================

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public String getFromEmail() { return fromEmail; }
    public void setFromEmail(String v) { this.fromEmail = v != null ? v : ""; }
    public String getToEmail() { return toEmail; }
    public void setToEmail(String v) { this.toEmail = v != null ? v : ""; }
    public boolean isRejectAlertEnabled() { return rejectAlertEnabled; }
    public void setRejectAlertEnabled(boolean v) { this.rejectAlertEnabled = v; }
    public boolean isActiveAlertEnabled() { return activeAlertEnabled; }
    public void setActiveAlertEnabled(boolean v) { this.activeAlertEnabled = v; }
    public boolean isQueueAlertEnabled() { return queueAlertEnabled; }
    public void setQueueAlertEnabled(boolean v) { this.queueAlertEnabled = v; }
    public boolean isMemoryAlertEnabled() { return memoryAlertEnabled; }
    public void setMemoryAlertEnabled(boolean v) { this.memoryAlertEnabled = v; }
    public double getActiveAlertThreshold() { return activeAlertThreshold; }
    public void setActiveAlertThreshold(double v) { this.activeAlertThreshold = v; }
    public double getQueueAlertThreshold() { return queueAlertThreshold; }
    public void setQueueAlertThreshold(double v) { this.queueAlertThreshold = v; }
    public double getMemoryAlertThreshold() { return memoryAlertThreshold; }
    public void setMemoryAlertThreshold(double v) { this.memoryAlertThreshold = v; }
    public int getAlertCooldownSeconds() { return alertCooldownSeconds; }
    public void setAlertCooldownSeconds(int v) { this.alertCooldownSeconds = v; }
    public int getGlobalEmailIntervalSeconds() { return globalEmailIntervalSeconds; }
    public void setGlobalEmailIntervalSeconds(int v) { this.globalEmailIntervalSeconds = v; }

    public static List<Map<String, Object>> getAlertHistory() { return alertHistory; }

    /** 获取当前完整配置（供前端展示） */
    public Map<String, Object> getConfig() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("enabled", enabled);
        c.put("fromEmail", fromEmail);
        c.put("toEmail", toEmail);
        c.put("rejectAlertEnabled", rejectAlertEnabled);
        c.put("activeAlertEnabled", activeAlertEnabled);
        c.put("queueAlertEnabled", queueAlertEnabled);
        c.put("memoryAlertEnabled", memoryAlertEnabled);
        c.put("activeAlertThreshold", activeAlertThreshold);
        c.put("queueAlertThreshold", queueAlertThreshold);
        c.put("memoryAlertThreshold", memoryAlertThreshold);
        c.put("alertCooldownSeconds", alertCooldownSeconds);
        c.put("globalEmailIntervalSeconds", globalEmailIntervalSeconds);
        return c;
    }

    /** 从前端提交的配置更新（动态生效，不需要重启） */
    public void updateConfig(Map<String, Object> config) {
        if (config.containsKey("enabled")) this.enabled = Boolean.TRUE.equals(config.get("enabled"));
        if (config.containsKey("toEmail")) this.toEmail = String.valueOf(config.get("toEmail"));
        if (config.containsKey("fromEmail")) this.fromEmail = String.valueOf(config.get("fromEmail"));
        if (config.containsKey("rejectAlertEnabled")) this.rejectAlertEnabled = Boolean.TRUE.equals(config.get("rejectAlertEnabled"));
        if (config.containsKey("activeAlertEnabled")) this.activeAlertEnabled = Boolean.TRUE.equals(config.get("activeAlertEnabled"));
        if (config.containsKey("queueAlertEnabled")) this.queueAlertEnabled = Boolean.TRUE.equals(config.get("queueAlertEnabled"));
        if (config.containsKey("memoryAlertEnabled")) this.memoryAlertEnabled = Boolean.TRUE.equals(config.get("memoryAlertEnabled"));
        if (config.containsKey("activeAlertThreshold")) this.activeAlertThreshold = toDouble(config.get("activeAlertThreshold"), 0.9);
        if (config.containsKey("queueAlertThreshold")) this.queueAlertThreshold = toDouble(config.get("queueAlertThreshold"), 0.8);
        if (config.containsKey("memoryAlertThreshold")) this.memoryAlertThreshold = toDouble(config.get("memoryAlertThreshold"), 0.85);
        if (config.containsKey("alertCooldownSeconds")) this.alertCooldownSeconds = toInt(config.get("alertCooldownSeconds"), 300);
        if (config.containsKey("globalEmailIntervalSeconds")) this.globalEmailIntervalSeconds = toInt(config.get("globalEmailIntervalSeconds"), 60);
    }

    private double toDouble(Object v, double def) {
        try { return v instanceof Number ? ((Number) v).doubleValue() : Double.parseDouble(v.toString()); }
        catch (Exception e) { return def; }
    }
    private int toInt(Object v, int def) {
        try { return v instanceof Number ? ((Number) v).intValue() : Integer.parseInt(v.toString()); }
        catch (Exception e) { return def; }
    }

    /** 每个采集周期调用一次，检查所有报警规则 */
    public void check() {
        if (!enabled) {
            logger.debug("[报警] 报警未启用，跳过检查");
            return;
        }
        long now = System.currentTimeMillis();
        logger.debug("[报警] 开始检查，tracked pools: {}, mailSender={}, from={}, to={}",
                metricsCollector.getTrackedPools(), mailSender != null ? "OK" : "NULL", fromEmail, toEmail);

        for (String poolName : metricsCollector.getTrackedPools()) {
            ThreadPoolSnapshot latest = metricsCollector.getLatest(poolName);
            if (latest == null) continue;

            if (rejectAlertEnabled && latest.getRejectCount() > 0) {
                fireAlert(poolName, "REJECT", "CRITICAL",
                        String.format("线程池 %s 发生任务拒绝！拒绝次数: %d, core=%d, max=%d, 队列积压=%d",
                                poolName, latest.getRejectCount(),
                                latest.getCorePoolSize(), latest.getMaximumPoolSize(), latest.getQueueSize()), now);
            }
            if (activeAlertEnabled) {
                double ar = latest.getActiveRate();
                if (ar >= activeAlertThreshold) {
                    fireAlert(poolName, "ACTIVE_HIGH", "WARNING",
                            String.format("线程池 %s 活跃率过高: %.1f%% (阈值: %.0f%%), core=%d, max=%d, active=%d",
                                    poolName, ar * 100, activeAlertThreshold * 100,
                                    latest.getCorePoolSize(), latest.getMaximumPoolSize(), latest.getActiveCount()), now);
                }
            }
            if (queueAlertEnabled) {
                double qr = latest.getQueueUsageRate();
                if (qr >= queueAlertThreshold) {
                    fireAlert(poolName, "QUEUE_HIGH", "WARNING",
                            String.format("线程池 %s 队列使用率过高: %.1f%% (阈值: %.0f%%), 队列=%d, 容量=%d",
                                    poolName, qr * 100, queueAlertThreshold * 100,
                                    latest.getQueueSize(), latest.getQueueCapacity()), now);
                }
            }
        }

        if (memoryAlertEnabled) {
            MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            double memRate = heap.getMax() > 0 ? (double) heap.getUsed() / heap.getMax() : 0;
            if (memRate >= memoryAlertThreshold) {
                fireAlert("JVM", "MEMORY_HIGH", "CRITICAL",
                        String.format("JVM堆内存使用率过高: %.1f%% (阈值: %.0f%%), 已用=%dMB, 最大=%dMB",
                                memRate * 100, memoryAlertThreshold * 100,
                                heap.getUsed() / 1024 / 1024, heap.getMax() / 1024 / 1024), now);
            }
        }
    }

    private void fireAlert(String poolName, String alertType, String level, String message, long now) {
        // 同类型去重（per pool + type）
        String key = poolName + ":" + alertType;
        Long last = lastAlertTime.get(key);
        if (last != null && (now - last) < alertCooldownSeconds * 1000L) return;
        lastAlertTime.put(key, now);

    // 记录到内存历史（不受邮件频率限制）
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("time", new SimpleDateFormat("MM-dd HH:mm:ss").format(new Date(now)));
        record.put("pool", poolName);
        record.put("type", alertType);
        record.put("level", level);
        record.put("message", message);
        record.put("emailed", false);
        alertHistory.add(0, record);
        while (alertHistory.size() > MAX_ALERT_HISTORY) alertHistory.remove(alertHistory.size() - 1);

        logger.warn("[报警] {} - {} - {}", level, alertType, message);

        // 全局邮件频率限制：两封邮件之间至少间隔 globalEmailIntervalSeconds
        // 多实例场景：通过 Redis SETNX 做跨实例去重，只有一个实例发邮件
        if (mailSender != null && fromEmail != null && toEmail != null && !fromEmail.isBlank() && !toEmail.isBlank()) {
            if ((now - lastGlobalEmailTime) < globalEmailIntervalSeconds * 1000L) {
                logger.debug("[报警邮件] 本地频率限制，跳过发送（距上次发送 {}s < {}s）",
                        (now - lastGlobalEmailTime) / 1000, globalEmailIntervalSeconds);
                record.put("emailed", "throttled");
                return;
            }
            // Redis 分布式锁：同一应用同一报警类型，冷却期内只有一个实例发邮件
            boolean acquired = true;
            if (redissonClient != null) {
                String lockKey = "DTP_ALERT_LOCK_" + applicationName + "_" + key;
                acquired = redissonClient.getBucket(lockKey).trySet("1", alertCooldownSeconds, TimeUnit.SECONDS);
                if (!acquired) {
                    logger.debug("[报警邮件] 跨实例去重，其他实例已发送此报警: {}", key);
                    record.put("emailed", "throttled");
                    return;
                }
            }
            try {
                SimpleMailMessage mail = new SimpleMailMessage();
                mail.setFrom(fromEmail);
                mail.setTo(toEmail.split(","));
                mail.setSubject(String.format("[动态线程池报警] %s - %s - %s", level, applicationName, alertType));
                mail.setText(buildMailBody(poolName, alertType, level, message, now));
                mailSender.send(mail);
                lastGlobalEmailTime = now;
                record.put("emailed", true);
                logger.info("[报警邮件] 已发送到 {}", toEmail);
            } catch (Exception e) {
                logger.error("[报警邮件] 发送失败: {}", e.getMessage());
                record.put("emailError", e.getMessage());
            }
        }
    }

    private String buildMailBody(String poolName, String alertType, String level, String message, long now) {
        return "=== 动态线程池报警通知 ===\n\n" +
                "应用: " + applicationName + "\n" +
                "时间: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(now)) + "\n" +
                "级别: " + level + "\n" +
                "类型: " + alertType + "\n" +
                "线程池: " + poolName + "\n\n" +
                "详情: " + message + "\n\n" +
                "---\n此邮件由动态线程池监控系统自动发送，请勿回复。\n";
    }
}
