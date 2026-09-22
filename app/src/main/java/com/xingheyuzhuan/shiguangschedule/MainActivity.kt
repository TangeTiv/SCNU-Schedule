package com.xingheyuzhuan.shiguangschedule

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.get
import androidx.navigation3.runtime.metadata
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.xingheyuzhuan.shiguangschedule.data.model.StartScreen
import com.xingheyuzhuan.shiguangschedule.ui.account.AccountScreen
import com.xingheyuzhuan.shiguangschedule.ui.campus.AcademicScreen
import com.xingheyuzhuan.shiguangschedule.ui.campus.CampusScreen
import com.xingheyuzhuan.shiguangschedule.ui.campus.CourseSelectionScreen
import com.xingheyuzhuan.shiguangschedule.ui.campus.CourseSelectionViewModel
import com.xingheyuzhuan.shiguangschedule.ui.campus.ExamScreen
import com.xingheyuzhuan.shiguangschedule.ui.campus.GradeScreen
import com.xingheyuzhuan.shiguangschedule.ui.campus.ScnuVerificationScreen
import com.xingheyuzhuan.shiguangschedule.ui.campus.SyncSelectionScreen
import com.xingheyuzhuan.shiguangschedule.ui.schedule.WeeklyScheduleScreen
import com.xingheyuzhuan.shiguangschedule.ui.schoolselection.list.AdapterSelectionScreen
import com.xingheyuzhuan.shiguangschedule.ui.schoolselection.list.SchoolSelectionListScreen
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.xingheyuzhuan.shiguangschedule.ui.schoolselection.web.WebViewScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.SettingsScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.SettingsViewModel
import com.xingheyuzhuan.shiguangschedule.ui.settings.additional.MoreOptionsScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.additional.OpenSourceLicensesScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.contribution.ContributionScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.conversion.CourseTableConversionScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.course.AddEditCourseScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.coursemanagement.CourseInstanceListScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.coursemanagement.CourseNameListScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.coursetables.ManageCourseTablesScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.notification.NotificationSettingsScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.quickactions.QuickActionsScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.quickactions.delete.QuickDeleteScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.quickactions.tweaks.TweakScheduleScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.style.StyleSettingsScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.themesettings.ThemeSettingsScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.time.TimeSlotManagementScreen
import com.xingheyuzhuan.shiguangschedule.ui.settings.update.UpdateRepoScreen
import com.xingheyuzhuan.shiguangschedule.ui.components.OnboardingDialog
import com.xingheyuzhuan.shiguangschedule.ui.theme.SCNUScheduleTheme
import com.xingheyuzhuan.shiguangschedule.ui.today.TodayScheduleScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val viewModel: SettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()

            if (state.isReady) {
                // 首次启动引导弹窗
                if (!state.appSettings.onboardingCompleted) {
                    OnboardingDialog(onDismiss = { viewModel.onOnboardingCompleted() })
                }

                SCNUScheduleTheme(settings = state.appSettings) {
                    val startDest = remember(state.appSettings.startScreen) {
                        when (state.appSettings.startScreen) {
                            StartScreen.COURSE_SCHEDULE -> Destination.CourseSchedule
                            StartScreen.TODAY_SCHEDULE -> Destination.TodaySchedule
                            StartScreen.CAMPUS -> Destination.Campus
                        }
                    }
                    AppNavigation(startDestination = startDest)
                }
            }else {
                Surface(modifier = Modifier.fillMaxSize()) {}
            }
        }
    }
}

@Composable
fun AppNavigation(startDestination: Destination) {
    val backStack = rememberNavBackStack(startDestination)

    // ── 选课模块的 ViewModel 提升到 NavDisplay 之上 ──
    //
    // 必要性：NavDisplay 使用 rememberViewModelStoreNavEntryDecorator，ViewModel
    // 的作用域是**单个 NavEntry**。若在 CampusScreen 内部创建，导航到选课页时
    // Destination.Campus 的 entry 退出组合 → ViewModel 被销毁 → 登录态与类别缓存
    // 丢失，选课页会再次索要密码。
    //
    // 提升到此处后，【校园】页的登录对话框与选课页共用同一个实例，
    // 登录成功导航过去即可直接使用，无需二次登录。
    val courseSelectionViewModel: CourseSelectionViewModel = hiltViewModel()

    val onNavigate: (Destination) -> Unit = remember(backStack) {
        { dest ->
            if (dest.isMainScreen) {
                if (backStack.lastOrNull() != dest) {
                    backStack.clear()
                    backStack.add(dest)
                }
            } else {
                if (backStack.lastOrNull() != dest) {
                    backStack.add(dest)
                }
            }
        }
    }

    val onBack: () -> Unit = remember(backStack) {
        {
            if (backStack.size > 1) {
                backStack.removeAt(backStack.lastIndex)
            }
        }
    }

    val animSpec = tween<IntOffset>(300)

    NavDisplay(
        backStack = backStack,
        onBack = onBack,
        transitionSpec = {
            val fromMain = initialState.metadata[ShiguangNavMetadata.IsMainScreenKey] ?: false
            val toMain = targetState.metadata[ShiguangNavMetadata.IsMainScreenKey] ?: false

            if (fromMain && toMain) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                slideInHorizontally(initialOffsetX = { it }, animationSpec = animSpec) togetherWith
                        slideOutHorizontally(targetOffsetX = { -it / 3 }, animationSpec = animSpec) + fadeOut()
            }
        },
        popTransitionSpec = {
            val fromMain = initialState.metadata[ShiguangNavMetadata.IsMainScreenKey] ?: false
            val toMain = targetState.metadata[ShiguangNavMetadata.IsMainScreenKey] ?: false

            if (fromMain && toMain) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                slideInHorizontally(initialOffsetX = { -it / 3 }, animationSpec = animSpec) + fadeIn() togetherWith
                        slideOutHorizontally(targetOffsetX = { it }, animationSpec = animSpec)
            }
        },
        predictivePopTransitionSpec = {
            slideInHorizontally(initialOffsetX = { -it / 3 }, animationSpec = animSpec) + fadeIn() togetherWith
                    slideOutHorizontally(targetOffsetX = { it }, animationSpec = animSpec)
        },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator()
        )
    ) { key ->
        val destination = key as Destination

        NavEntry(
            key = key,
            metadata = metadata {
                put(ShiguangNavMetadata.IsMainScreenKey, destination.isMainScreen)
            }
        ) {
            Surface(modifier = Modifier.fillMaxSize()) {
                ScreenContent(
                    targetDest = destination,
                    onNavigate = onNavigate,
                    onBack = onBack,
                    courseSelectionViewModel = courseSelectionViewModel
                )
            }
        }
    }
}

@Composable
fun ScreenContent(
    targetDest: Destination,
    onNavigate: (Destination) -> Unit,
    onBack: () -> Unit,
    /**
     * 选课模块共享的 ViewModel。
     *
     * 在 [AppNavigation] 中创建（位于 NavDisplay 之上）并透传，
     * 使【校园】页的登录对话框与选课页可共用登录态，详见 [AppNavigation] 注释。
     */
    courseSelectionViewModel: CourseSelectionViewModel
) {
    when (targetDest) {
        Destination.CourseSchedule -> WeeklyScheduleScreen(onNavigate, onBack)
        Destination.Campus -> CampusScreen(onNavigate, onBack, courseSelectionViewModel = courseSelectionViewModel)
        Destination.Settings -> SettingsScreen(onNavigate, onBack)
        Destination.TodaySchedule -> TodayScheduleScreen(onNavigate, onBack)
        Destination.TimeSlotSettings -> TimeSlotManagementScreen(onNavigate, onBack)
        Destination.ManageCourseTables -> ManageCourseTablesScreen(onNavigate, onBack)
        Destination.SchoolSelectionListScreen -> SchoolSelectionListScreen(onNavigate, onBack)
        Destination.CourseTableConversion -> CourseTableConversionScreen(onNavigate, onBack)
        Destination.NotificationSettings -> NotificationSettingsScreen(onBack)
        Destination.MoreOptions -> MoreOptionsScreen(onNavigate, onBack)
        Destination.OpenSourceLicenses -> OpenSourceLicensesScreen(onBack)
        Destination.UpdateRepo -> UpdateRepoScreen(onBack)
        Destination.QuickActions -> QuickActionsScreen(onNavigate, onBack)
        Destination.TweakSchedule -> TweakScheduleScreen(onBack)
        Destination.ContributionList -> ContributionScreen(onBack)
        Destination.CourseManagementList -> CourseNameListScreen(onNavigate, onBack)
        Destination.StyleSettings -> StyleSettingsScreen(onBack)
        Destination.QuickDelete -> QuickDeleteScreen(onBack)
        Destination.ThemeSettings -> ThemeSettingsScreen(onBack = onBack)
        Destination.ScnuVerification -> ScnuVerificationScreen(onNavigate, onBack)
        Destination.Account -> AccountScreen(onBack = onBack)
        Destination.Grades -> GradeScreen(onBack = onBack)
        Destination.Exams -> ExamScreen(onBack = onBack)
        Destination.Academic -> AcademicScreen(onBack = onBack)
        Destination.CourseSelection -> CourseSelectionScreen(
            onNavigate = onNavigate,
            onBack = onBack,
            viewModel = courseSelectionViewModel
        )
        Destination.SyncSelection -> {
            SyncSelectionScreen(
                onNavigate = onNavigate,
                onBack = onBack
            )
        }

        // 处理 data class (带参数的目的地)
        is Destination.AdapterSelection -> AdapterSelectionScreen(
            onNavigate, onBack, targetDest.schoolId, targetDest.schoolName, targetDest.categoryNumber, targetDest.resourceFolder
        )
        is Destination.WebView -> WebViewScreen(
            onNavigate, onBack, targetDest.initialUrl, targetDest.assetJsPath
        )
        is Destination.AddEditCourse -> AddEditCourseScreen(
            onBack, targetDest.courseId
        )
        is Destination.CourseManagementDetail -> CourseInstanceListScreen(
            targetDest.courseName, onBack, onNavigate
        )
    }
}