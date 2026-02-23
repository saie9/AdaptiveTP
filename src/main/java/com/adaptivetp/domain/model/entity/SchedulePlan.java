package com.adaptivetp.domain.model.entity;

/**
 * 计划模式 - 单条时间计划
 * 表示在某个时间点将线程池调整到指定参数
 */
public class SchedulePlan {

    /** 小时（0-23） */
    private int hour;
    /** 分钟（0-59） */
    private int minute;
    /** 目标核心线程数 */
    private int corePoolSize;
    /** 目标最大线程数 */
    private int maximumPoolSize;
    /** 星期几生效（1=周一...7=周日），null表示每天 */
    private int[] daysOfWeek;

    public SchedulePlan() {}

    public SchedulePlan(int hour, int minute, int corePoolSize, int maximumPoolSize, int[] daysOfWeek) {
        this.hour = hour;
        this.minute = minute;
        this.corePoolSize = corePoolSize;
        this.maximumPoolSize = maximumPoolSize;
        this.daysOfWeek = daysOfWeek;
    }

    /** 转换为当天的分钟数，用于排序和比较 */
    public int toMinuteOfDay() {
        return hour * 60 + minute;
    }

    public int getHour() { return hour; }
    public void setHour(int hour) { this.hour = hour; }
    public int getMinute() { return minute; }
    public void setMinute(int minute) { this.minute = minute; }
    public int getCorePoolSize() { return corePoolSize; }
    public void setCorePoolSize(int corePoolSize) { this.corePoolSize = corePoolSize; }
    public int getMaximumPoolSize() { return maximumPoolSize; }
    public void setMaximumPoolSize(int maximumPoolSize) { this.maximumPoolSize = maximumPoolSize; }
    public int[] getDaysOfWeek() { return daysOfWeek; }
    public void setDaysOfWeek(int[] daysOfWeek) { this.daysOfWeek = daysOfWeek; }
}
