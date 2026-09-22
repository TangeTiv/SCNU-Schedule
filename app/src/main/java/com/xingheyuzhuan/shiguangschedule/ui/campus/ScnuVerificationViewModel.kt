package com.xingheyuzhuan.shiguangschedule.ui.campus

import androidx.lifecycle.ViewModel
import com.xingheyuzhuan.shiguangschedule.data.auth.ScnuAuthManager
import com.xingheyuzhuan.shiguangschedule.data.network.ScnuScraper
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * 调试验证页面的 ViewModel。
 *
 * 仅包装 [ScnuScraper]（爬取）与 [ScnuAuthManager]（登录）以供 Compose 通过 Hilt 获取。
 *
 * > v1.7.0：登录已从 [ScnuScraper] 迁出，本类改为注入 [ScnuAuthManager]。
 */
@HiltViewModel
class ScnuVerificationViewModel @Inject constructor(
    val scraper: ScnuScraper,
    val authManager: ScnuAuthManager
) : ViewModel()
