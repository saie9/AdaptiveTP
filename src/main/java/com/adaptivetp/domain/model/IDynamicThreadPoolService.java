package com.adaptivetp.domain.model;


import com.adaptivetp.domain.model.entity.ThreadPoolConfigEntity;

import java.util.List;


public interface IDynamicThreadPoolService {

    List<ThreadPoolConfigEntity> queryThreadPoolList();

    ThreadPoolConfigEntity queryThreadPoolConfigByName(String threadPoolName);

    void updateThreadPoolConfig(ThreadPoolConfigEntity threadPoolConfigEntity);

}
