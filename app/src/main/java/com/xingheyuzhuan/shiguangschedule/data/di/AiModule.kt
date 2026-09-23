package com.xingheyuzhuan.shiguangschedule.data.di

import com.xingheyuzhuan.shiguangschedule.data.ai.AiProvider
import com.xingheyuzhuan.shiguangschedule.data.ai.OpenAiCompatibleProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import javax.inject.Named
import javax.inject.Singleton

/**
 * AI（P-AI）依赖注入。
 *
 * 这里只做一件事：把 [OpenAiCompatibleProvider] 绑定到 [AiProvider] 抽象上。
 * 消费方（`AgentLoop`）注入的是**接口**而不是实现类 —— 将来接官方 AI 网关时，
 * 只需要在这里换一行绑定，`AgentLoop` 与上下文构建一行都不用改
 * （方案文档 §9.1.1 第 5 条）。
 *
 * Ktor client 与 Json 的提供放在 [NetworkModule]，与 scnu 链路并排 ——
 * 那两处的安全边界差异（信任所有证书 vs 平台默认校验）必须放在一起才看得清。
 */
@Module
@InstallIn(SingletonComponent::class)
@Suppress("unused")
object AiModule {

    @Provides
    @Singleton
    fun provideAiProvider(
        @Named("ai") client: HttpClient,
        @Named("ai") json: Json
    ): AiProvider = OpenAiCompatibleProvider(client, json)
}
