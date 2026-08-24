package com.shopmind.evaluation.pipeline;

import com.shopmind.evaluation.domain.BenchmarkConfig;

/**
 * Benchmark 配置持有器 — P2-0.5C 单一事实源机制。
 * <p>
 * 使用 ThreadLocal 在 BenchmarkRunnerImpl 的 flatMap 链中传递 BenchmarkConfig，
 * 使 ChatModelAdapter 的 buildRequestBody() 可以读取到真实的实验参数，
 * 替代此前硬编码的 temperature 和 application.yml 中独立配置的 seed/model。
 * <p>
 * <b>生命周期：</b>
 * <ol>
 *   <li>{@link BenchmarkRunnerImpl#executeAndCollectTrace} 开始时 {@link #set(BenchmarkConfig)}</li>
 *   <li>ChatModelAdapter 在 {@code buildRequestBody()} 中调用 {@link #get()}</li>
 *   <li>flatMap 链结束时 {@link #clear()}</li>
 * </ol>
 * <p>
 * <b>线程安全：</b>ThreadLocal 天然线程隔离。Reactor flatMap 中每个元素
 * 在单一线程上完成，不会跨线程共享。
 * <p>
 * <b>降级：</b>当 get() 返回 null 时（非 Benchmark 环境），Adapter 应使用
 * application.yml 的默认值，保证常规运行时不受影响。
 */
public final class BenchmarkConfigHolder {

    private static final ThreadLocal<BenchmarkConfig> CURRENT = new ThreadLocal<>();

    private BenchmarkConfigHolder() {
        // 工具类，禁止实例化
    }

    /**
     * 设置当前线程的 BenchmarkConfig。
     * 由 BenchmarkRunnerImpl 在每次 executeAndCollectTrace 开始时调用。
     */
    public static void set(BenchmarkConfig config) {
        CURRENT.set(config);
    }

    /**
     * 获取当前线程的 BenchmarkConfig。
     * 由 ChatModelAdapter 在 buildRequestBody() 中调用。
     *
     * @return 当前 BenchmarkConfig，如果不在 Benchmark 上下文中则返回 null
     */
    public static BenchmarkConfig get() {
        return CURRENT.get();
    }

    /**
     * 清除当前线程的 BenchmarkConfig。
     * 由 BenchmarkRunnerImpl 在 flatMap 链的 doFinally 中调用。
     */
    public static void clear() {
        CURRENT.remove();
    }
}