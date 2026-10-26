@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.jnu.hub.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavDestination.Companion.hierarchy
import cn.jnu.hub.core.network.JnuHosts
import cn.jnu.hub.feature.jw.JwWebActivity
import cn.jnu.hub.feature.jw.JwWebApps
import cn.jnu.hub.feature.login.LoginActivity
import cn.jnu.hub.feature.web.WebPageActivity
import cn.jnu.hub.ui.screens.jw.JwCreditsRoute
import cn.jnu.hub.ui.screens.jw.JwExamsRoute
import cn.jnu.hub.ui.screens.jw.JwGradesRoute
import cn.jnu.hub.ui.screens.jw.JwProfileRoute
import cn.jnu.hub.ui.screens.jw.JwTimetableRoute
import cn.jnu.hub.ui.navigation.Routes
import cn.jnu.hub.ui.navigation.TopLevelDest
import cn.jnu.hub.ui.screens.blackboard.BbAnnouncementDetailRoute
import cn.jnu.hub.ui.screens.blackboard.BbCourseDetailRoute
import cn.jnu.hub.ui.screens.blackboard.BbCoursesRoute
import cn.jnu.hub.ui.screens.campusnet.CampusNetRoute
import cn.jnu.hub.ui.screens.faculty.FacultyRoute
import cn.jnu.hub.ui.screens.home.HomeScreen
import cn.jnu.hub.ui.screens.payment.PaymentRoute
import cn.jnu.hub.ui.screens.pe.PeBookingRoute
import cn.jnu.hub.ui.screens.pe.PeExemptRoute
import cn.jnu.hub.ui.screens.pe.PeHomeRoute
import cn.jnu.hub.ui.screens.pe.PeLoginRoute
import cn.jnu.hub.ui.screens.pe.PePrescriptionRoute
import cn.jnu.hub.ui.screens.pe.PeResultRoute
import cn.jnu.hub.ui.screens.pe.PeStandardRoute
import cn.jnu.hub.ui.screens.profile.ProfileScreen
import cn.jnu.hub.ui.screens.services.ServicesScreen
import cn.jnu.hub.ui.screens.session.SessionDebugRoute
import cn.jnu.hub.ui.screens.study.StudyScreen
import cn.jnu.hub.ui.screens.stuit.StuitBasicInfoRoute
import cn.jnu.hub.ui.screens.stuit.StuitEvaluationRoute
import cn.jnu.hub.ui.screens.stuit.StuitGradesRoute
import cn.jnu.hub.ui.screens.stuit.StuitRankingRoute
import cn.jnu.hub.ui.screens.timetable.TimetableRoute
import cn.jnu.hub.ui.screens.utility.UtilityRoute

@Composable
fun JnuHubRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val context = LocalContext.current
    val currentDestination = backStackEntry?.destination

    val showBottomBar = TopLevelDest.entries.any { dest ->
        currentDestination?.hierarchy?.any { it.route == dest.route } == true
    }

    fun openWeb(url: String, title: String) {
        context.startActivity(WebPageActivity.intent(context, url, title))
    }

    fun openLogin() {
        context.startActivity(Intent(context, LoginActivity::class.java))
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TopLevelDest.entries.forEach { dest ->
                        val selected = currentDestination?.hierarchy
                            ?.any { it.route == dest.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    if (selected) dest.selectedIcon else dest.unselectedIcon,
                                    contentDescription = dest.label,
                                )
                            },
                            label = { Text(dest.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDest.Home.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(TopLevelDest.Home.route) {
                HomeScreen(onNavigate = { navController.navigate(it) })
            }
            composable(TopLevelDest.Study.route) {
                StudyScreen(onNavigate = { navController.navigate(it) })
            }
            composable(TopLevelDest.Courses.route) {
                TimetableRoute(onBack = {})
            }
            composable(TopLevelDest.Services.route) {
                ServicesScreen(onNavigate = { navController.navigate(it) })
            }
            composable(TopLevelDest.Profile.route) {
                ProfileScreen(onNavigate = { navController.navigate(it) })
            }

            composable(Routes.UTILITY_QUERY) {
                UtilityRoute(
                    onBack = { navController.popBackStack() },
                    onGoPayment = { navController.navigate(Routes.UTILITY_PAY) { launchSingleTop = true } },
                    onLogin = { openLogin() },
                    onOpenWeb = { url, title -> openWeb(url, title) },
                )
            }
            composable(Routes.UTILITY_PAY) {
                PaymentRoute(
                    onBack = { navController.popBackStack() },
                    onOpenWeb = { url, title -> openWeb(url, title) },
                )
            }
            composable(Routes.CAMPUS_NET) {
                CampusNetRoute(
                    onBack = { navController.popBackStack() },
                    onLogin = { openLogin() },
                )
            }
            composable(Routes.FACULTY) {
                FacultyRoute(onBack = { navController.popBackStack() })
            }

            // 教务：全部原生 UI，数据经 JwDataHarvester 从教务页面旁路抓取
            composable(Routes.JW_TIMETABLE) {
                JwTimetableRoute(onBack = { navController.popBackStack() })
            }
            composable(Routes.JW_GRADES) {
                JwGradesRoute(onBack = { navController.popBackStack() })
            }
            composable(Routes.JW_EXAMS) {
                JwExamsRoute(onBack = { navController.popBackStack() })
            }
            composable(Routes.JW_CREDITS) {
                JwCreditsRoute(onBack = { navController.popBackStack() })
            }
            composable(Routes.JW_PROFILE) {
                JwProfileRoute(onBack = { navController.popBackStack() })
            }

            composable(Routes.ST_EVALUATION) {
                StuitEvaluationRoute(onBack = { navController.popBackStack() })
            }
            composable(Routes.ST_RANKING) {
                StuitRankingRoute(onBack = { navController.popBackStack() })
            }
            composable(Routes.ST_GRADES) {
                StuitGradesRoute(onBack = { navController.popBackStack() })
            }
            composable(Routes.ST_BASIC) {
                StuitBasicInfoRoute(onBack = { navController.popBackStack() })
            }

            composable(Routes.PE_RESULT) {
                PeResultRoute(
                    onBack = { navController.popBackStack() },
                    onNeedLogin = { navController.navigate(Routes.PE_LOGIN) { launchSingleTop = true } },
                )
            }
            composable(Routes.PE_STANDARD) {
                PeStandardRoute(
                    onBack = { navController.popBackStack() },
                    onNeedLogin = { navController.navigate(Routes.PE_LOGIN) { launchSingleTop = true } },
                )
            }
            composable(Routes.PE_LOGIN) {
                PeLoginRoute(
                    onBack = { navController.popBackStack() },
                    onSuccess = { navController.popBackStack() },
                )
            }
            composable(Routes.PE_HOME) {
                PeHomeRoute(
                    onBack = { navController.popBackStack() },
                    onOpenResult = { navController.navigate(Routes.PE_RESULT) { launchSingleTop = true } },
                    onOpenStandard = { navController.navigate(Routes.PE_STANDARD) { launchSingleTop = true } },
                    onOpenBooking = { navController.navigate(Routes.PE_BOOKING) { launchSingleTop = true } },
                    onOpenExempt = { navController.navigate(Routes.PE_EXEMPT) { launchSingleTop = true } },
                    onOpenPrescription = { navController.navigate(Routes.PE_PRESCRIPTION) { launchSingleTop = true } },
                    onNeedLogin = { navController.navigate(Routes.PE_LOGIN) { launchSingleTop = true } },
                )
            }
            composable(Routes.PE_BOOKING) {
                PeBookingRoute(
                    onBack = { navController.popBackStack() },
                    onNeedLogin = { navController.navigate(Routes.PE_LOGIN) { launchSingleTop = true } },
                )
            }
            composable(Routes.PE_EXEMPT) {
                PeExemptRoute(
                    onBack = { navController.popBackStack() },
                    onNeedLogin = { navController.navigate(Routes.PE_LOGIN) { launchSingleTop = true } },
                )
            }
            composable(Routes.PE_PRESCRIPTION) {
                PePrescriptionRoute(
                    onBack = { navController.popBackStack() },
                    onNeedLogin = { navController.navigate(Routes.PE_LOGIN) { launchSingleTop = true } },
                )
            }

            composable(Routes.BB_HOME) {
                BbCoursesRoute(
                    onOpenCourse = { id, _ -> navController.navigate(Routes.bbCourse(id)) },
                    onLogin = { openLogin() },
                    onBack = { navController.popBackStack() },
                    onOpenBrowser = { openWeb(JnuHosts.BLACKBOARD + "/", "Blackboard") },
                    showTopBarBack = true,
                )
            }
            composable(
                route = Routes.BB_COURSE,
                arguments = listOf(
                    navArgument(Routes.BB_COURSE_ARG) { type = NavType.StringType },
                ),
            ) { entry ->
                val id = entry.arguments?.getString(Routes.BB_COURSE_ARG).orEmpty()
                BbCourseDetailRoute(
                    onBack = { navController.popBackStack() },
                    courseId = id,
                    onLogin = { openLogin() },
                    onOpenAnnouncement = { annId ->
                        navController.navigate(Routes.bbAnnouncement(id, annId))
                    },
                    onOpenWeb = { url, title -> openWeb(url, title) },
                )
            }
            composable(
                route = Routes.BB_ANNOUNCEMENT,
                arguments = listOf(
                    navArgument(Routes.BB_COURSE_ARG) { type = NavType.StringType },
                    navArgument(Routes.BB_ANN_ARG) { type = NavType.StringType },
                ),
            ) { entry ->
                BbAnnouncementDetailRoute(
                    onBack = { navController.popBackStack() },
                    courseId = entry.arguments?.getString(Routes.BB_COURSE_ARG).orEmpty(),
                    announcementId = entry.arguments?.getString(Routes.BB_ANN_ARG).orEmpty(),
                    onLogin = { openLogin() },
                    onOpenWeb = { url, title -> openWeb(url, title) },
                )
            }

            composable(
                route = Routes.WEB,
                arguments = listOf(
                    navArgument("url") { type = NavType.StringType },
                    navArgument("title") {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                ),
            ) { entry ->
                val url = entry.arguments?.getString("url").orEmpty()
                val title = entry.arguments?.getString("title").orEmpty().ifBlank { "网页" }
                LaunchedEffect(url) { openWeb(url, title) }
            }
            composable(Routes.SESSION_DEBUG) {
                SessionDebugRoute(onBack = { navController.popBackStack() })
            }
        }
    }
}