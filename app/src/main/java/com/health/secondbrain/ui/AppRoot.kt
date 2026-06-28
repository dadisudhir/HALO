package com.health.secondbrain.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.health.secondbrain.data.HaloHealthRepository
import com.health.secondbrain.data.HealthBackendMode
import com.health.secondbrain.health.HealthDashboardUiState
import com.health.secondbrain.ui.screens.ChatScreen
import com.health.secondbrain.ui.screens.HomeScreen
import com.health.secondbrain.ui.screens.OrganDetailScreen

object Routes {
    const val Home = "home"
    const val Detail = "detail/{organId}"
    const val Chat = "chat/{organId}"
    fun detail(id: String) = "detail/$id"
    fun chat(id: String) = "chat/$id"
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val repository = remember(context) {
        HaloHealthRepository(context.applicationContext)
    }
    var mode by remember { mutableStateOf(HealthBackendMode.Fake) }
    val dashboard by produceState(initialValue = HealthDashboardUiState.Loading, repository, mode) {
        value = repository.loadDashboard(mode)
    }
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.Home) {
        composable(Routes.Home) {
            HomeScreen(
                mode = mode,
                organs = dashboard.organs,
                overviewStats = dashboard.overviewStats,
                overviewNote = dashboard.overviewNote,
                overviewLine = dashboard.overviewLine,
                backendStatus = dashboard.backendStatus,
                footerPrimary = dashboard.footerPrimary,
                footerSecondary = dashboard.footerSecondary,
                onModeChange = { mode = it },
                onOrganTap = { nav.navigate(Routes.detail(it)) },
            )
        }
        composable(
            Routes.Detail,
            arguments = listOf(navArgument("organId") { type = NavType.StringType })
        ) {
            val id = it.arguments?.getString("organId") ?: "heart"
            OrganDetailScreen(
                organId = id,
                organ = dashboard.organById(id),
                onBack = { nav.popBackStack() },
                onOpenChat = { nav.navigate(Routes.chat(id)) }
            )
        }
        composable(
            Routes.Chat,
            arguments = listOf(navArgument("organId") { type = NavType.StringType })
        ) {
            val id = it.arguments?.getString("organId") ?: "heart"
            ChatScreen(
                organId = id,
                organ = dashboard.organById(id),
                mode = mode,
                repository = repository,
                onBack = { nav.popBackStack() },
            )
        }
    }
}
