package com.ddf.vodsystem.configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig implements AsyncConfigurer {
    private static final Logger logger = LoggerFactory.getLogger(AsyncConfig.class);

    /**
     * Provides the bounded thread pool that runs ffmpeg-backed {@code @Async} media tasks.
     *
     * @return the configured {@link Executor} registered as {@code ffmpegTaskExecutor}
     */
    @Bean(name = "ffmpegTaskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("ffmpegExecutor-");
        executor.initialize();
        return executor;
    }

    /**
     * Supplies the handler that logs uncaught exceptions from void-returning {@code @Async} methods.
     *
     * @return the async uncaught-exception handler
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) ->
                logger.error("Async task '{}' failed: {}", method.getName(), ex.getMessage(), ex);
    }
}
