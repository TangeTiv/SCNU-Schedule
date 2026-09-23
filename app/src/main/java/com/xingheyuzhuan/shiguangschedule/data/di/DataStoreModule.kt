package com.xingheyuzhuan.shiguangschedule.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.xingheyuzhuan.shiguangschedule.data.model.schedule_style.ScheduleGridStyleProto
import com.xingheyuzhuan.shiguangschedule.data.repository.scheduleGridStyleDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

// 定义 AppSettings Preferences DataStore 委托
private val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")
// 定义 SchoolHistory Preferences DataStore 委托
private val Context.schoolHistoryDataStore: DataStore<Preferences> by preferencesDataStore(name = "school_history")
// 定义教务凭据 Preferences DataStore 委托（独立文件，便于一键清除）
private val Context.authCredentialsDataStore: DataStore<Preferences> by preferencesDataStore(name = "auth_credentials")
// 定义 AI 设置 Preferences DataStore 委托（独立文件，理由见 provideAiSettingsDataStore）
private val Context.aiSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "ai_settings")

@Module
@InstallIn(SingletonComponent::class)
@Suppress("unused")
object DataStoreModule {

    /**
     * 提供课表网格样式 DataStore (Proto 模式)
     */
    @Provides
    @Singleton
    fun provideScheduleStyleDataStore(@ApplicationContext context: Context): DataStore<ScheduleGridStyleProto> {
        return context.scheduleGridStyleDataStore
    }

    /**
     * 提供学校选择历史 DataStore
     */
    @Provides
    @Singleton
    @Named("SchoolHistory")
    fun provideSchoolHistoryDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.schoolHistoryDataStore
    }

    /**
     * 提供全局设置 DataStore，并集成从 Room 到 DataStore 的单次自动迁移逻辑。
     * 原 Room 迁移逻辑已随版本 5 的物理删表操作一并移除。
     */
    @Provides
    @Singleton
    @Named("AppSettings")
    fun provideAppSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.appSettingsDataStore

    /**
     * 提供教务凭据 DataStore（Keystore 密文 + 失败计数/锁定时间）。
     *
     * 单独一个文件而不是塞进 `app_settings`：
     * - 「一键清除凭据」可以直接清空整个文件，不会误伤设置项
     * - 凭据的读写频率与设置项完全不同，分开避免相互干扰
     */
    @Provides
    @Singleton
    @Named("AuthCredentials")
    fun provideAuthCredentialsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.authCredentialsDataStore

    /**
     * 提供 AI 设置 DataStore（API Key 的 Keystore 密文 + 厂商配置 + 隐私同意标记）。
     *
     * 单独一个文件，与 `auth_credentials` **完全隔离**，理由是双向的：
     * - 账号页的「一键清除教务凭据」清空的是 `auth_credentials`，
     *   不该顺手把用户自己充了钱的 API Key 也删掉；
     * - 反过来，AI 设置页的「清除 API Key」也不该影响教务登录态。
     *
     * 两者混在一起时，任一处的"清除"按钮都会变成一票否决式的误伤。
     */
    @Provides
    @Singleton
    @Named("AiSettings")
    fun provideAiSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.aiSettingsDataStore
}