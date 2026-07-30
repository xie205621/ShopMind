package com.shopmind.knowledge.adapter;

import com.shopmind.knowledge.port.EmbeddingProviderPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Mock Embedding 适配器 — 用于开发和测试环境。
 * <p>
 * 基于 SHA-256 哈希将文本转换为固定维度伪向量（256维），
 * 确保相同文本产生相同向量（支持缓存命中测试）。
 * <p>
 * 生产环境应替换为 DashScopeEmbeddingAdapter 或 OpenAIEmbeddingAdapter。
 */
@Component
@Profile("!prod & !qwen")
public class MockEmbeddingAdapter implements EmbeddingProviderPort {

    /** 模拟向量维度（SHA-256 = 32 字节 → 256 维） */
    private static final int VECTOR_DIM = 256;

    @Override
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return new float[VECTOR_DIM];
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return bytesToFloatVector(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * 将字节数组映射为 [-1, 1] 区间的浮点向量。
     */
    private float[] bytesToFloatVector(byte[] bytes) {
        float[] vector = new float[VECTOR_DIM];
        for (int i = 0; i < VECTOR_DIM; i++) {
            // 将 byte (-128 ~ 127) 归一化到 [-1, 1]
            vector[i] = (bytes[i % bytes.length] & 0xFF) / 128.0f - 1.0f;
        }
        return vector;
    }
}
