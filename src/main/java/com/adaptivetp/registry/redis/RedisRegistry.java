package com.adaptivetp.registry.redis;

import com.adaptivetp.domain.model.entity.ThreadPoolConfigEntity;
import com.adaptivetp.domain.model.valobj.RegistryEnumVO;
import com.adaptivetp.registry.IRegistry;
import org.redisson.api.RBucket;
import org.redisson.api.RList;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.List;

public class RedisRegistry implements IRegistry {

    private final RedissonClient redissonClient;

    public RedisRegistry(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }


    @Override
    public void reportThreadPool(List<ThreadPoolConfigEntity> threadPoolEntities) {
        RList<ThreadPoolConfigEntity> list = redissonClient.getList(RegistryEnumVO.THREAD_POOL_CONFIG_LIST_KEY.getKey());
        list.delete();
        list.addAll(threadPoolEntities);
    }

    @Override
    public void reportThreadPoolConfigParameter(ThreadPoolConfigEntity threadPoolConfigEntity) {
        String cacheKey = RegistryEnumVO.THREAD_POOL_CONFIG_PARAMETER_LIST_KEY.getKey() + "_" + threadPoolConfigEntity.getAppName() + "_" + threadPoolConfigEntity.getThreadPoolName();
        RBucket<ThreadPoolConfigEntity> bucket = redissonClient.getBucket(cacheKey);
        bucket.set(threadPoolConfigEntity, Duration.ofDays(30));
    }

    @Override
    public void reportThreadPoolConfigParameter(ThreadPoolConfigEntity threadPoolConfigEntity, String instanceId) {
        // 按实例隔离的 key：THREAD_POOL_CONFIG_PARAMETER_LIST_KEY_{appName}_{instanceId}_{poolName}
        String cacheKey = RegistryEnumVO.THREAD_POOL_CONFIG_PARAMETER_LIST_KEY.getKey() + "_"
                + threadPoolConfigEntity.getAppName() + "_" + instanceId + "_" + threadPoolConfigEntity.getThreadPoolName();
        RBucket<ThreadPoolConfigEntity> bucket = redissonClient.getBucket(cacheKey);
        bucket.set(threadPoolConfigEntity, Duration.ofDays(30));
    }

    @Override
    public void reportThreadPool(List<ThreadPoolConfigEntity> threadPoolEntities, String instanceId) {
        // 按实例隔离的列表 key
        String listKey = RegistryEnumVO.THREAD_POOL_CONFIG_LIST_KEY.getKey() + "_" + instanceId;
        RList<ThreadPoolConfigEntity> list = redissonClient.getList(listKey);
        list.delete();
        list.addAll(threadPoolEntities);
    }
}
