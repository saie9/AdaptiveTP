package com.adaptivetp.domain.model;

import com.adaptivetp.domain.model.entity.ThreadPoolSnapshot;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 指标采集器 + 环形缓冲区
 * <p>
 * 为每个线程池维护一个固定大小的环形缓冲区（LinkedList），存储最近 N 个采集周期的快照。
 * 当缓冲区满时，自动淘汰最老的数据（FIFO）。
 * <p>
 * 提供滑动窗口判定能力：
 * - 扩容判定：最近连续 N 个点是否都超过阈值
 * - 缩容判定：最近连续 N 个点是否都低于阈值
 * - 速率计算：活跃率的变化斜率，用于决定扩容幅度
 * <p>
 * 线程安全：每个缓冲区的读写通过 synchronized 保护
 */
public class MetricsCollector {

    /** 每个线程池的环形缓冲区 */
    private final Map<String, LinkedList<ThreadPoolSnapshot>> buffers = new ConcurrentHashMap<>();

    /** 每个线程池的累计拒绝次数（上一次采集时的值，用于计算增量） */
    private final Map<String, Long> lastRejectCounts = new ConcurrentHashMap<>();

    private final int bufferSize;

    public MetricsCollector(int bufferSize) {
        this.bufferSize = bufferSize;
    }

    /**
     * 记录一个快照到环形缓冲区
     */
    public void record(ThreadPoolSnapshot snapshot) {
        buffers.computeIfAbsent(snapshot.getPoolName(), k -> new LinkedList<>());
        LinkedList<ThreadPoolSnapshot> buffer = buffers.get(snapshot.getPoolName());
        synchronized (buffer) {
            buffer.addLast(snapshot);
            while (buffer.size() > bufferSize) {
                buffer.removeFirst();
            }
        }
    }

    /**
     * 获取最近 N 个采集点
     */
    public List<ThreadPoolSnapshot> getRecentSnapshots(String poolName, int count) {
        LinkedList<ThreadPoolSnapshot> buffer = buffers.get(poolName);
        if (buffer == null) return Collections.emptyList();
        synchronized (buffer) {
            int size = buffer.size();
            int from = Math.max(0, size - count);
            return new ArrayList<>(buffer.subList(from, size));
        }
    }

    /**
     * 获取最新的一个快照
     */
    public ThreadPoolSnapshot getLatest(String poolName) {
        LinkedList<ThreadPoolSnapshot> buffer = buffers.get(poolName);
        if (buffer == null || buffer.isEmpty()) return null;
        synchronized (buffer) {
            return buffer.getLast();
        }
    }

    /**
     * 检查最近连续 N 个点是否都满足扩容条件
     * <p>
     * 扩容条件：队列使用率 >= 阈值 或 线程活跃率 >= 阈值
     * 必须连续 N 个点都满足，防止瞬时毛刺误触发扩容
     *
     * @param poolName          线程池名称
     * @param consecutivePoints 需要连续满足的点数
     * @param queueThreshold    队列使用率阈值
     * @param activeThreshold   线程活跃率阈值
     * @return true=需要扩容
     */
    public boolean isExpandNeeded(String poolName, int consecutivePoints,
                                  double queueThreshold, double activeThreshold) {
        List<ThreadPoolSnapshot> recent = getRecentSnapshots(poolName, consecutivePoints);
        if (recent.size() < consecutivePoints) return false;

        for (ThreadPoolSnapshot s : recent) {
            if (s.getQueueUsageRate() < queueThreshold && s.getActiveRate() < activeThreshold) {
                return false; // 有一个点不满足就不触发
            }
        }
        return true;
    }

    /**
     * 检查最近连续 N 个点是否都满足缩容条件
     * <p>
     * 缩容条件：队列使用率 <= 阈值 且 线程活跃率 <= 阈值
     * 缩容比扩容更谨慎，需要更多连续点数（默认6个=120秒）
     *
     * @param poolName          线程池名称
     * @param consecutivePoints 需要连续满足的点数
     * @param queueThreshold    队列使用率阈值
     * @param activeThreshold   线程活跃率阈值
     * @return true=需要缩容
     */
    public boolean isShrinkNeeded(String poolName, int consecutivePoints,
                                  double queueThreshold, double activeThreshold) {
        List<ThreadPoolSnapshot> recent = getRecentSnapshots(poolName, consecutivePoints);
        if (recent.size() < consecutivePoints) return false;

        for (ThreadPoolSnapshot s : recent) {
            if (s.getQueueUsageRate() > queueThreshold || s.getActiveRate() > activeThreshold) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检查最新快照是否有拒绝发生（紧急扩容触发条件）
     */
    public boolean hasReject(String poolName) {
        ThreadPoolSnapshot latest = getLatest(poolName);
        return latest != null && latest.getRejectCount() > 0;
    }

    /**
     * 计算活跃率变化速率（线性回归斜率的简化版）
     * <p>
     * 取最近 N 个采集点的首尾活跃率之差除以间隔数，得到每个采集周期的活跃率变化量。
     * 正值表示活跃率在上升，负值表示在下降。
     * <p>
     * 用途：根据速率大小决定扩容幅度
     * - 速率 < 5%/周期  → 温和扩容（*1.25）
     * - 速率 5%-15%    → 正常扩容（*1.5）
     * - 速率 15%-25%   → 激进扩容（*2.0）
     * - 速率 > 25%     → 紧急扩容（直接拉满）
     *
     * @param poolName 线程池名称
     * @param points   取最近多少个点计算
     * @return 每个采集周期的活跃率变化量
     */
    public double calcActiveRateVelocity(String poolName, int points) {
        List<ThreadPoolSnapshot> recent = getRecentSnapshots(poolName, points);
        if (recent.size() < 2) return 0;
        double first = recent.get(0).getActiveRate();
        double last = recent.get(recent.size() - 1).getActiveRate();
        return (last - first) / (recent.size() - 1);
    }

    public Set<String> getTrackedPools() {
        return buffers.keySet();
    }
}
