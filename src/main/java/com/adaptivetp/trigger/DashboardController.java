package com.adaptivetp.trigger;

import com.adaptivetp.domain.model.AlertManager;
import com.adaptivetp.domain.model.MetricsCollector;
import com.adaptivetp.domain.model.entity.SchedulePlan;
import com.adaptivetp.domain.model.entity.ThreadPoolSnapshot;
import com.alibaba.fastjson.JSON;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.web.bind.annotation.*;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/dtp/api")
public class DashboardController {

    private final Map<String, ThreadPoolExecutor> threadPoolExecutorMap;
    private final MetricsCollector metricsCollector;
    private final RedissonClient redissonClient;
    private final String applicationName;
    private final String instanceId;
    private final AlertManager alertManager;
    private final AtomicBoolean sustainedLoad = new AtomicBoolean(false);
    private volatile Thread loadThread;

    /** 调参历史记录（内存中保留最近100条） */
    private static final List<Map<String, Object>> adjustHistory = new CopyOnWriteArrayList<>();
    private static final int MAX_HISTORY = 100;

    public DashboardController(Map<String, ThreadPoolExecutor> threadPoolExecutorMap,
                               MetricsCollector metricsCollector,
                               RedissonClient redissonClient,
                               String applicationName,
                               String instanceId,
                               AlertManager alertManager) {
        this.threadPoolExecutorMap = threadPoolExecutorMap;
        this.metricsCollector = metricsCollector;
        this.redissonClient = redissonClient;
        this.applicationName = applicationName;
        this.instanceId = instanceId;
        this.alertManager = alertManager;
    }

    /** 由 AdaptiveScaler 等组件调用，记录调参历史 */
    public static void recordAdjust(String poolName, String reason, int oldCore, int newCore, int oldMax, int newMax) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("time", new SimpleDateFormat("HH:mm:ss").format(new Date()));
        record.put("pool", poolName);
        record.put("reason", reason);
        record.put("oldCore", oldCore);
        record.put("newCore", newCore);
        record.put("oldMax", oldMax);
        record.put("newMax", newMax);
        adjustHistory.add(0, record);
        while (adjustHistory.size() > MAX_HISTORY) adjustHistory.remove(adjustHistory.size() - 1);
    }

    // ==================== 状态与快照 ====================

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, ThreadPoolExecutor> entry : threadPoolExecutorMap.entrySet()) {
            result.put(entry.getKey(), poolInfo(entry.getValue()));
        }
        return result;
    }

    @GetMapping("/snapshots")
    public Map<String, Object> snapshots(@RequestParam(defaultValue = "5") int count) {
        Map<String, Object> result = new LinkedHashMap<>();
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        for (String poolName : metricsCollector.getTrackedPools()) {
            List<ThreadPoolSnapshot> recent = metricsCollector.getRecentSnapshots(poolName, count);
            List<Map<String, Object>> list = new ArrayList<>();
            for (ThreadPoolSnapshot s : recent) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("time", sdf.format(new Date(s.getTimestamp())));
                m.put("core", s.getCorePoolSize());
                m.put("max", s.getMaximumPoolSize());
                m.put("active", s.getActiveCount());
                m.put("activeRate", String.format("%.1f%%", s.getActiveRate() * 100));
                m.put("queueSize", s.getQueueSize());
                m.put("queueCapacity", s.getQueueCapacity());
                m.put("queueUsage", String.format("%.1f%%", s.getQueueUsageRate() * 100));
                m.put("rejectCount", s.getRejectCount());
                list.add(m);
            }
            result.put(poolName, list);
        }
        return result;
    }

    /** 获取所有快照的原始数值（供趋势图使用） */
    @GetMapping("/trend")
    public Map<String, Object> trend(@RequestParam(defaultValue = "30") int count) {
        Map<String, Object> result = new LinkedHashMap<>();
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        for (String poolName : metricsCollector.getTrackedPools()) {
            List<ThreadPoolSnapshot> recent = metricsCollector.getRecentSnapshots(poolName, count);
            Map<String, Object> poolData = new LinkedHashMap<>();
            List<String> times = new ArrayList<>();
            List<Double> activeRates = new ArrayList<>();
            List<Double> queueRates = new ArrayList<>();
            List<Integer> cores = new ArrayList<>();
            List<Integer> maxes = new ArrayList<>();
            for (ThreadPoolSnapshot s : recent) {
                times.add(sdf.format(new Date(s.getTimestamp())));
                activeRates.add(Math.round(s.getActiveRate() * 1000.0) / 10.0);
                queueRates.add(Math.round(s.getQueueUsageRate() * 1000.0) / 10.0);
                cores.add(s.getCorePoolSize());
                maxes.add(s.getMaximumPoolSize());
            }
            poolData.put("times", times);
            poolData.put("activeRates", activeRates);
            poolData.put("queueRates", queueRates);
            poolData.put("cores", cores);
            poolData.put("maxes", maxes);
            result.put(poolName, poolData);
        }
        return result;
    }

    // ==================== 手动调参 ====================

    @PostMapping("/adjust")
    public String adjust(@RequestParam String pool, @RequestParam int core, @RequestParam int max) {
        ThreadPoolExecutor executor = threadPoolExecutorMap.get(pool);
        if (executor == null) return "线程池不存在: " + pool;
        if (core > max) return "core 不能大于 max";
        if (core < 1 || max < 1) return "参数必须大于0";
        int oldCore = executor.getCorePoolSize();
        int oldMax = executor.getMaximumPoolSize();
        if (core > oldMax) { executor.setMaximumPoolSize(max); executor.setCorePoolSize(core); }
        else { executor.setCorePoolSize(core); executor.setMaximumPoolSize(max); }
        recordAdjust(pool, "MANUAL", oldCore, core, oldMax, max);
        return String.format("%s 调参完成: core %d→%d, max %d→%d", pool, oldCore, core, oldMax, max);
    }

    // ==================== 调参历史 ====================

    @GetMapping("/history")
    public List<Map<String, Object>> history() { return adjustHistory; }

    // ==================== 报警记录 ====================

    @GetMapping("/alerts")
    public List<Map<String, Object>> alerts() { return AlertManager.getAlertHistory(); }

    @GetMapping("/alert-config")
    public Map<String, Object> getAlertConfig() { return alertManager.getConfig(); }

    @PostMapping("/alert-config")
    public String updateAlertConfig(@RequestBody Map<String, Object> config) {
        alertManager.updateConfig(config);
        return "报警配置已更新";
    }

    // ==================== JVM 资源信息 ====================

    @GetMapping("/jvm")
    public Map<String, Object> jvm() {
        Map<String, Object> m = new LinkedHashMap<>();
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        m.put("heapUsedMB", heap.getUsed() / 1024 / 1024);
        m.put("heapMaxMB", heap.getMax() / 1024 / 1024);
        m.put("heapUsageRate", heap.getMax() > 0 ? Math.round((double) heap.getUsed() / heap.getMax() * 1000.0) / 10.0 : 0);
        long totalGcTime = ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
        long uptime = ManagementFactory.getRuntimeMXBean().getUptime();
        m.put("gcTimeRate", uptime > 0 ? Math.round((double) totalGcTime / uptime * 1000.0) / 10.0 : 0);
        m.put("gcTotalMs", totalGcTime);
        m.put("uptimeSeconds", uptime / 1000);
        int totalThreads = threadPoolExecutorMap.values().stream().mapToInt(ThreadPoolExecutor::getMaximumPoolSize).sum();
        m.put("totalManagedThreads", totalThreads);
        m.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        m.put("threadCount", Thread.activeCount());
        return m;
    }

    @GetMapping("/pools")
    public Set<String> pools() { return threadPoolExecutorMap.keySet(); }

    /** 获取当前实例标识（多实例部署时用于区分） */
    @GetMapping("/instance")
    public Map<String, String> instance() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("applicationName", applicationName);
        m.put("instanceId", instanceId);
        return m;
    }

    // ==================== 计划模式管理 ====================

    @GetMapping("/schedule/{poolName}")
    public List<SchedulePlan> getSchedule(@PathVariable String poolName) {
        String key = "THREAD_POOL_SCHEDULE_" + applicationName + "_" + poolName;
        String json = (String) redissonClient.getBucket(key).get();
        if (json == null || json.isBlank()) return Collections.emptyList();
        List<SchedulePlan> plans = JSON.parseArray(json, SchedulePlan.class);
        plans.sort(Comparator.comparingInt(SchedulePlan::toMinuteOfDay));
        return plans;
    }

    @PostMapping("/schedule/{poolName}")
    public String saveSchedule(@PathVariable String poolName, @RequestBody List<SchedulePlan> plans) {
        plans.sort(Comparator.comparingInt(SchedulePlan::toMinuteOfDay));
        String key = "THREAD_POOL_SCHEDULE_" + applicationName + "_" + poolName;
        redissonClient.getBucket(key).set(JSON.toJSONString(plans));
        return "已保存 " + poolName + " 的 " + plans.size() + " 条计划";
    }

    @DeleteMapping("/schedule/{poolName}")
    public String deleteSchedule(@PathVariable String poolName) {
        String key = "THREAD_POOL_SCHEDULE_" + applicationName + "_" + poolName;
        redissonClient.getBucket(key).delete();
        return "已清除 " + poolName + " 的计划";
    }

    // ==================== 压测控制 ====================

    @PostMapping("/pressure")
    public String pressure(@RequestParam String pool, @RequestParam(defaultValue = "20") int count, @RequestParam(defaultValue = "3000") int sleepMs) {
        ThreadPoolExecutor executor = threadPoolExecutorMap.get(pool);
        if (executor == null) return "线程池不存在: " + pool;
        int submitted = 0, rejected = 0;
        for (int i = 0; i < count; i++) {
            try { executor.execute(() -> { try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }); submitted++; }
            catch (Exception e) { rejected++; }
        }
        return String.format("提交 %d 个任务（耗时%dms），成功 %d，拒绝 %d。队列积压: %d", count, sleepMs, submitted, rejected, executor.getQueue().size());
    }

    @PostMapping("/sustained-start")
    public String sustainedStart(@RequestParam String pool, @RequestParam(defaultValue = "5") int rate, @RequestParam(defaultValue = "3000") int sleepMs) {
        if (sustainedLoad.get()) return "已经在持续加压中，先停止";
        ThreadPoolExecutor executor = threadPoolExecutorMap.get(pool);
        if (executor == null) return "线程池不存在: " + pool;
        sustainedLoad.set(true);
        loadThread = new Thread(() -> {
            while (sustainedLoad.get()) {
                for (int i = 0; i < rate; i++) {
                    try { executor.execute(() -> { try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }); } catch (Exception ignored) {}
                }
                try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
            }
        }, "dashboard-load-thread");
        loadThread.setDaemon(true);
        loadThread.start();
        return String.format("开始持续加压：%s池 %d/s %dms", pool, rate, sleepMs);
    }

    @PostMapping("/sustained-stop")
    public String sustainedStop() {
        sustainedLoad.set(false);
        if (loadThread != null) { loadThread.interrupt(); loadThread = null; }
        return "已停止持续加压";
    }

    private Map<String, Object> poolInfo(ThreadPoolExecutor executor) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("corePoolSize", executor.getCorePoolSize());
        m.put("maximumPoolSize", executor.getMaximumPoolSize());
        m.put("activeCount", executor.getActiveCount());
        m.put("poolSize", executor.getPoolSize());
        m.put("queueSize", executor.getQueue().size());
        m.put("queueRemainingCapacity", executor.getQueue().remainingCapacity());
        m.put("completedTaskCount", executor.getCompletedTaskCount());
        return m;
    }
}
