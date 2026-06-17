package com.xingheyuzhuan.shiguangschedule.ui.campus

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingheyuzhuan.shiguangschedule.Destination
import com.xingheyuzhuan.shiguangschedule.data.repository.AppSettingsRepository
import com.xingheyuzhuan.shiguangschedule.data.repository.SchoolRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 校园模块 ViewModel。
 *
 * 职责：
 * 1. 持久化同步选项至 DataStore（供 AndroidBridge JS 读取）
 * 2. 查找 SCNU（华南师范大学）适配器并构造 WebView 直达跳转
 */
@HiltViewModel
class CampusViewModel @Inject constructor(
    private val appSettingsRepository: AppSettingsRepository,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    /**
     * 开始同步入口。
     *
     * 先保存用户选择的同步开关，随后从已下发的 school_index 中
     * 匹配华师适配器信息，最终直接跳转至 WebView 页面（硬编码直达）。
     */
    fun onSyncStart(options: SyncOptions, onNavigate: (Destination) -> Unit) {
        viewModelScope.launch {
            // 1. 持久化同步选项
            appSettingsRepository.setSyncOptions(
                courses = options.courses,
                grades = options.grades,
                exams = options.exams
            )

            // 2. 从 school_index 匹配华师适配器（SCNU hardcode）
            val schools = SchoolRepository.getSchools(appContext)
            val scnu = schools.find { school ->
                school.id.contains("scnu", ignoreCase = true) ||
                school.name.contains("华南师范")
            }
            val adapter = scnu?.adapters?.firstOrNull()

            // 3. 直接跳转 WebView（绕过学校选择列表）
            onNavigate(
                Destination.WebView(
                    initialUrl = adapter?.import_url?.takeIf { it.isNotBlank() } ?: "about:blank",
                    assetJsPath = if (scnu != null && adapter != null) {
                        "${scnu.resource_folder}/${adapter.adapter_id}.js"
                    } else null
                )
            )
        }
    }
}
