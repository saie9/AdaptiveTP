package com.adaptivetp.config;

import com.adaptivetp.trigger.DashboardController;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 动态线程池监控面板自动配置
 * <p>
 * 将 starter jar 包内的前端页面映射到 /dynamic-thread-pool/dashboard/** 路径。
 * 任何引入了 starter 的项目，启动后访问 /dynamic-thread-pool/dashboard/index.html 即可打开监控面板。
 * <p>
 * 原理：Spring Boot 的 ResourceHandler 支持从 classpath 加载静态资源，
 * 即使资源在依赖的 jar 包内也能正常访问。
 */
@Configuration
public class DashboardAutoConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/dtp/**")
                .addResourceLocations("classpath:/dynamic-thread-pool-dashboard/");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/dtp/**")
                .allowedOriginPatterns("*")
                .allowedMethods("*");
    }

    @Bean
    public DashboardController dashboardController(Map<String, ThreadPoolExecutor> threadPoolExecutorMap,
                                                    com.adaptivetp.domain.model.MetricsCollector metricsCollector,
                                                    RedissonClient redissonClient,
                                                    com.adaptivetp.domain.model.AlertManager alertManager,
                                                    org.springframework.context.ApplicationContext applicationContext) {
        String appName = applicationContext.getEnvironment().getProperty("spring.application.name", "unknown");
        // 生成实例标识，与 DynamicThreadPoolAutoConfig 保持一致
        String serverPort = applicationContext.getEnvironment().getProperty("server.port", "8080");
        String instanceId;
        try {
            String ip = java.net.InetAddress.getLocalHost().getHostAddress();
            instanceId = ip + ":" + serverPort;
        } catch (Exception e) {
            instanceId = "unknown:" + serverPort;
        }
        return new DashboardController(threadPoolExecutorMap, metricsCollector, redissonClient, appName, instanceId, alertManager);
    }
}
