package com.adaptivetp.domain.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 可计数的拒绝策略包装器
 * <p>
 * 包装原有的 RejectedExecutionHandler，在拒绝时累加计数器。
 * 自适应模式通过读取计数器判断是否需要紧急扩容。
 */
public class CountableRejectedHandler implements RejectedExecutionHandler {

    private static final Logger logger = LoggerFactory.getLogger(CountableRejectedHandler.class);

    private final RejectedExecutionHandler delegate;
    private final AtomicLong rejectCount = new AtomicLong(0);
    private final String poolName;

    public CountableRejectedHandler(RejectedExecutionHandler delegate, String poolName) {
        this.delegate = delegate;
        this.poolName = poolName;
    }

    @Override
    public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
        long count = rejectCount.incrementAndGet();
        logger.warn("[拒绝策略] 线程池 {} 触发拒绝，累计拒绝次数: {}", poolName, count);
        // 委托给原始的拒绝策略
        delegate.rejectedExecution(r, executor);
    }

    public long getRejectCount() {
        return rejectCount.get();
    }

    /** 获取并重置计数（用于每次采集后清零，只关心采集间隔内是否有新拒绝） */
    public long getAndReset() {
        return rejectCount.getAndSet(0);
    }
}
