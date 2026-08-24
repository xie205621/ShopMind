package com.shopmind.experiment;

/**
 * RTMP Runtime Scenario — Phase 5-C1 独立 fixture 数据。
 * <p>
 * 表达一个 caseId 对应的<b>会话环境事实</b>（模拟登录系统提供的 session state），
 * 与 {@code rtmp_dataset_v1.json} 的 Ground Truth 物理分离、语义独立。
 * <p>
 * <b>关键约束：</b>这里的取值是「环境事实」（该会话认证主体是谁、操作目标属于谁），
 * 不是从 GT 的 riskLabel / authorization 反推。provenance 字段用于回答
 * 「这个 runtimeAuthorization 是什么环境事实」，防泄漏审计可逐条追溯。
 *
 * @param caseId                  关联的 RTMP caseId
 * @param authenticatedPrincipal  当前认证主体标识（未认证为 null）
 * @param runtimeAuthorization    当前主体的授权级别
 * @param runtimeTargetScope      操作目标资源归属
 * @param provenance              环境事实来源说明（provenance audit 用）
 */
public record RtmpRuntimeScenario(
        String caseId,
        String authenticatedPrincipal,
        RuntimeAuthorization runtimeAuthorization,
        RuntimeTargetScope runtimeTargetScope,
        String provenance
) {
}
