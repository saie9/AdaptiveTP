package com.adaptivetp.registry;


import com.adaptivetp.domain.model.entity.ThreadPoolConfigEntity;

import java.util.List;


public interface IRegistry {

    void reportThreadPool(List<ThreadPoolConfigEntity> threadPoolEntities);

    void reportThreadPoolConfigParameter(ThreadPoolConfigEntity threadPoolConfigEntity);

    /** 按实例隔离上报线程池配置参数 */
    void reportThreadPoolConfigParameter(ThreadPoolConfigEntity threadPoolConfigEntity, String instanceId);

    /** 按实例隔离上报线程池列表 */
    void reportThreadPool(List<ThreadPoolConfigEntity> threadPoolEntities, String instanceId);

}
