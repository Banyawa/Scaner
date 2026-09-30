package com.banyawa.sitescanner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.banyawa.sitescanner.ui.floorplan.FloorPlanScreen
import com.banyawa.sitescanner.ui.guide.GuidePrefs
import com.banyawa.sitescanner.ui.guide.GuideScreen
import com.banyawa.sitescanner.ui.guide.IntroScreen
import com.banyawa.sitescanner.ui.project.ProjectDetailScreen
import com.banyawa.sitescanner.ui.projects.ProjectListScreen
import com.banyawa.sitescanner.ui.viewer.ViewerScreen

private object Routes {
    /** First-launch walkthrough; finishing or skipping it leads to the project list. */
    const val INTRO = "intro"
    /** The same walkthrough opened again from the user guide; finishing it goes back. */
    const val INTRO_REPLAY = "intro/replay"
    const val GUIDE = "guide"
    const val PROJECTS = "projects"
    const val PROJECT = "project/{projectId}"
    const val VIEWER = "viewer/{projectId}/{scanId}"
    const val PLAN = "plan/{projectId}/{scanId}"

    fun project(id: String) = "project/$id"
    fun viewer(projectId: String, scanId: String) = "viewer/$projectId/$scanId"
    fun plan(projectId: String, scanId: String) = "plan/$projectId/$scanId"
}

@Composable
fun AppNavHost() {
    val nav = rememberNavController()
    val context = LocalContext.current
    // Decided once per activity: the intro opens before the project list until it was finished or skipped.
    val startDestination = remember { if (GuidePrefs.introDone(context)) Routes.PROJECTS else Routes.INTRO }
    val idArgs = listOf(
        navArgument("projectId") { type = NavType.StringType },
        navArgument("scanId") { type = NavType.StringType },
    )
    NavHost(navController = nav, startDestination = startDestination) {
        composable(Routes.INTRO) {
            IntroScreen(onDone = {
                GuidePrefs.setIntroDone(context, true)
                nav.navigate(Routes.PROJECTS) { popUpTo(Routes.INTRO) { inclusive = true } }
            })
        }
        composable(Routes.INTRO_REPLAY) {
            IntroScreen(onDone = { nav.popBackStack() })
        }
        composable(Routes.GUIDE) {
            GuideScreen(onBack = { nav.popBackStack() }, onReplayIntro = { nav.navigate(Routes.INTRO_REPLAY) })
        }
        composable(Routes.PROJECTS) {
            ProjectListScreen(
                onOpenProject = { nav.navigate(Routes.project(it)) },
                onOpenGuide = { nav.navigate(Routes.GUIDE) },
            )
        }
        composable(Routes.PROJECT, arguments = idArgs.take(1)) { entry ->
            val projectId = entry.arguments?.getString("projectId").orEmpty()
            ProjectDetailScreen(
                projectId = projectId,
                onBack = { nav.popBackStack() },
                onOpenViewer = { scanId -> nav.navigate(Routes.viewer(projectId, scanId)) },
                onOpenPlan = { scanId -> nav.navigate(Routes.plan(projectId, scanId)) },
            )
        }
        composable(Routes.VIEWER, arguments = idArgs) { entry ->
            ViewerScreen(
                projectId = entry.arguments?.getString("projectId").orEmpty(),
                scanId = entry.arguments?.getString("scanId").orEmpty(),
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.PLAN, arguments = idArgs) { entry ->
            FloorPlanScreen(
                projectId = entry.arguments?.getString("projectId").orEmpty(),
                scanId = entry.arguments?.getString("scanId").orEmpty(),
                onBack = { nav.popBackStack() },
            )
        }
    }
}
