package com.adaptivetp.domain.model;

import com.adaptivetp.domain.model.entity.SchedulePlan;
import com.adaptivetp.domain.model.valobj.RegistryEnumVO;
import com.alibaba.fastjson.JSON;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 计划模式调度器
 * <p>
 * 从 Redis 读取每个线程池的时间计划，根据当前时间返回应该生效的基线参数。
 * 计划值作为自适应模式的动态下限（地板值）。
 * <p>
 * Redis key 格式：THREAD_POOL_SCHEDULE_{appName}_{poolName}
 * value：SchedulePlan 数组的 JSON
 */
public class ScheduledScaler {

    private static final Logger logger = LoggerFactory.getLogger(ScheduledScaler.class);

    private final RedissonClient redissonClient;
    private final String applicationName;

    /** 本地缓存，避免每次都读 Redis。定时刷新。 */
    private final Map<String, List<SchedulePlan>> planCache = new ConcurrentHashMap<>();

    public ScheduledScaler(RedissonClient redissonClient, String applicationName) {
        this.redissonClient = redissonClient;
        this.applicationName = applicationName;
    }

    /**
     * 从 Redis 刷新计划到本地缓存
     */
    public void refreshPlans(Set<String> poolNames) {
        for (String poolName : poolNames) {
            try {
                String key = "THREAD_POOL_SCHEDULE_" + applicationName + "_" + poolName;
                RBucket<String> bucket = redissonClient.getBucket(key);
                String json = bucket.get();
                if (json != null && !json.isBlank()) {
                    List<SchedulePlan> plans = JSON.parseArray(json, SchedulePlan.class);
                    // 按时间排序
                    plans.sort(Comparator.comparingInt(SchedulePlan::toMinuteOfDay));
                    planCache.put(poolName, plans);
                }
            } catch (Exception e) {
                logger.warn("[计划模式] 刷新线程池 {} 的计划失败: {}", poolName, e.getMessage());
            }
        }
    }

    /**
     * 获取当前时间应该生效的计划基线值
     * 返回 null 表示没有计划，使用初始配置值
     */
    public SchedulePlan getCurrentBaseline(String poolName) {
        List<SchedulePlan> plans = planCache.get(poolName);
        if (plans == null || plans.isEmpty()) return null;

        LocalDateTime now = LocalDateTime.now();
        int currentMinute = now.getHour() * 60 + now.getMinute();
        int dayOfWeek = now.getDayOfWeek().getValue(); // 1=周一...7=周日

        // 从后往前找，找到第一个 <= 当前时间且星期匹配的计划
        SchedulePlan matched = null;
        for (int i = plans.size() - 1; i >= 0; i--) {
            SchedulePlan plan = plans.get(i);
            if (plan.toMinuteOfDay() <= currentMinute && isDayMatch(plan, dayOfWeek)) {
                matched = plan;
                break;
            }
        }

        // 如果当前时间在所有计划之前，取最后一个（昨天的最后一个计划延续到今天）
        if (matched == null) {
            for (int i = plans.size() - 1; i >= 0; i--) {
                if (isDayMatch(plans.get(i), dayOfWeek)) {
                    matched = plans.get(i);
                    break;
                }
            }
        }

        return matched;
    }

    /**
     * 检查是否到了新的计划切换点（用于主动触发计划调参）
     */
    public SchedulePlan checkScheduleTrigger(String poolName, long lastCheckTimestamp) {
        List<SchedulePlan> plans = planCache.get(poolName);
        if (plans == null || plans.isEmpty()) return null;

        LocalDateTime lastCheck = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(lastCheckTimestamp), java.time.ZoneId.systemDefault());
        LocalDateTime now = LocalDateTime.now();

        int lastMinute = lastCheck.getHour() * 60 + lastCheck.getMinute();
        int currentMinute = now.getHour() * 60 + now.getMinute();
        int dayOfWeek = now.getDayOfWeek().getValue();

        // 检查是否有计划点落在 (lastMinute, currentMinute] 区间内
        for (SchedulePlan plan : plans) {
            int planMinute = plan.toMinuteOfDay();
            if (planMinute > lastMinute && planMinute <= currentMinute && isDayMatch(plan, dayOfWeek)) {
                return plan;
            }
        }
        return null;
    }

    private boolean isDayMatch(SchedulePlan plan, int dayOfWeek) {
        if (plan.getDaysOfWeek() == null || plan.getDaysOfWeek().length == 0) {
            return true; // 没配星期限制，每天生效
        }
        for (int d : plan.getDaysOfWeek()) {
            if (d == dayOfWeek) return true;
        }
        return false;
    }

    public boolean hasPlan(String poolName) {
        List<SchedulePlan> plans = planCache.get(poolName);
        return plans != null && !plans.isEmpty();
    }
}
