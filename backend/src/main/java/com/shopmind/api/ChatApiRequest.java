package com.shopmind.api;

/**
 * POST /api/chat 请求体。
 *
 * @param memoryId 会话/租户唯一标识，为空时由服务端自动生成
 * @param query    用户输入文本
 */
public record ChatApiRequest(String memoryId, String query) {
}
