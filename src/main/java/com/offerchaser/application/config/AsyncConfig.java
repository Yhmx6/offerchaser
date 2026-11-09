package com.offerchaser.application.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 异步任务线程池配置。
 * <p>
 * 按“同一时刻只跑一个投递任务”设计：核心线程 2、最大 5、队列 100；
 * 队列满时回退到提交线程执行（CallerRunsPolicy），保证任务不会被静默丢弃。
 * 应用关闭时等待在途任务结束，最多等 60 秒。
 */
@Configuration
@Slf4j
public class AsyncConfig implements AsyncConfigurer {

    private static final int CORE_THREADS = 2;
    private static final int MAX_THREADS = 5;
    private static final int PENDING_QUEUE = 100;
    private static final int IDLE_KEEP_ALIVE_SECONDS = 60;
    private static final int SHUTDOWN_AWAIT_SECONDS = 60;
    private static final String THREAD_NAME = "Boss-Task-";

    /**
     * Spring 默认 {@code @Async} 执行器；方法名/别名保持 {@code taskExecutor}。
     */
    @Override
    @Bean(name = "taskExecutor")
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = buildExecutor();
        executor.initialize();
        log.info("异步任务执行器配置完成 - 核心线程数: {}, 最大线程数: {}, 队列容量: {}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());
        return executor;
    }

    /** 组装线程池参数；不在工厂方法里 initialize，便于测试/调用方控制生命周期。 */
    private ThreadPoolTaskExecutor buildExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORE_THREADS);
        executor.setMaxPoolSize(MAX_THREADS);
        executor.setQueueCapacity(PENDING_QUEUE);
        executor.setThreadNamePrefix(THREAD_NAME);
        executor.setKeepAliveSeconds(IDLE_KEEP_ALIVE_SECONDS);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(SHUTDOWN_AWAIT_SECONDS);
        return executor;
    }
}
