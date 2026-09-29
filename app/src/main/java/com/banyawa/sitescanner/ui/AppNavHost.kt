package com.banyawa.sitescanner.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.banyawa.sitescanner.ui.floorplan.FloorPlanScreen
import com.banyawa.sitescanner.ui.project.ProjectDetailScreen
import com.banyawa.sitescanner.ui.projects.ProjectListScreen
import com.banyawa.sitescanner.ui.viewer.ViewerScreen

private object Routes {
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
    val idArgs = listOf(
        navArgument("projectId") { type = NavType.StringType },
        navArgument("scanId") { type = NavType.StringType },
    )
    NavHost(navController = nav, startDestination = Routes.PROJECTS) {
        composable(Routes.PROJECTS) {
            ProjectListScreen(onOpenProject = { nav.navigate(Routes.project(it)) })
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
