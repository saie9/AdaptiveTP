package com.adaptivetp.domain.model.entity;

/**
 * 线程池运行时快照
 * 每次采集时生成一个，存入环形缓冲区用于自适应判定
 */
public class ThreadPoolSnapshot {

    private final long timestamp;
    private final String poolName;
    private final int corePoolSize;
    private final int maximumPoolSize;
    private final int activeCount;
    private final int poolSize;
    private final int queueSize;
    private final int queueCapacity;  // queueSize + remainingCapacity
    private final long rejectCount;

    public ThreadPoolSnapshot(long timestamp, String poolName, int corePoolSize, int maximumPoolSize,
                              int activeCount, int poolSize, int queueSize, int queueCapacity, long rejectCount) {
        this.timestamp = timestamp;
        this.poolName = poolName;
        this.corePoolSize = corePoolSize;
        this.maximumPoolSize = maximumPoolSize;
        this.activeCount = activeCount;
        this.poolSize = poolSize;
        this.queueSize = queueSize;
        this.queueCapacity = queueCapacity;
        this.rejectCount = rejectCount;
    }

    /** 队列使用率 */
    public double getQueueUsageRate() {
        return queueCapacity > 0 ? (double) queueSize / queueCapacity : 0;
    }

    /**
     * 线程活跃率（基于 corePoolSize）
     * <p>
     * 用 core 而非 max 作为基数，语义更准确：
     * - activeCount/core > 1.0 说明核心线程已不够用，需要扩容
     * - 如果用 max 作为基数，扩容后 max 变大，活跃率反而降低，难以触发后续扩容
     */
    public double getActiveRate() {
        return corePoolSize > 0 ? (double) activeCount / corePoolSize : 0;
    }

    public long getTimestamp() { return timestamp; }
    public String getPoolName() { return poolName; }
    public int getCorePoolSize() { return corePoolSize; }
    public int getMaximumPoolSize() { return maximumPoolSize; }
    public int getActiveCount() { return activeCount; }
    public int getPoolSize() { return poolSize; }
    public int getQueueSize() { return queueSize; }
    public int getQueueCapacity() { return queueCapacity; }
    public long getRejectCount() { return rejectCount; }
}
