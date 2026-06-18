package com.xingheyuzhuan.shiguangschedule.ui.campus

import androidx.lifecycle.ViewModel
import com.xingheyuzhuan.shiguangschedule.data.network.ScnuScraper
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * 调试验证页面的 ViewModel，仅包装 [ScnuScraper] 以供 Compose 通过 Hilt 获取。
 */
@HiltViewModel
class ScnuVerificationViewModel @Inject constructor(
    val scraper: ScnuScraper
) : ViewModel()
