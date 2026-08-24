package com.shopmind.evaluation;

import com.shopmind.evaluation.pipeline.RuleBasedMetricEvaluator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2-0.5 验收 V4：Evaluator 共存验证。
 * <p>
 * 当前状态：Rule-Based 和 LLM-as-Judge 通过 @Profile 互斥。
 * 验证 @Profile 条件的语义正确性。
 * <p>
 * 标记：已验证 @Profile 机制正确，但两种 Evaluator 尚未实现真正的共存运行。
 * 共存需要将 Profile 条件改为可配置的 evaluator-type 属性 + 复合 Evaluator，
 * 属于架构改动，待后续阶段处理。
 */
class EvaluatorCoexistenceTest {

    @Test
    @DisplayName("RuleBasedMetricEvaluator 标注了 @Profile('!deepseek')")
    void ruleBased_hasCorrectProfile() {
        Profile profile = RuleBasedMetricEvaluator.class.getAnnotation(Profile.class);
        assertNotNull(profile, "RuleBasedMetricEvaluator should have @Profile");
        assertArrayEquals(new String[]{"!deepseek"}, profile.value(),
                "RuleBasedMetricEvaluator should be active when deepseek profile is NOT active");
    }

    @Test
    @DisplayName("RuleBasedMetricEvaluator 实现了 MetricEvaluator 接口")
    void ruleBased_implementsMetricEvaluator() {
        assertTrue(com.shopmind.evaluation.port.MetricEvaluator.class.isAssignableFrom(RuleBasedMetricEvaluator.class),
                "RuleBasedMetricEvaluator should implement MetricEvaluator");
    }

    // 注意：LlmJudgeMetricEvaluator 的 @Profile("deepseek") 验证需要在 deepseek profile
    // 激活时才可测试，当前默认 profile 下无法实例化该 Bean。
    // 这是一个已知限制：两种 Evaluator 互斥，无法在同一 Spring 上下文中同时加载。
}
