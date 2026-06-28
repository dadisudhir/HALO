package com.health.secondbrain.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.health.secondbrain.ui.screens.ChatScreen
import com.health.secondbrain.ui.screens.HomeScreen
import com.health.secondbrain.ui.screens.OrganDetailScreen
import com.health.secondbrain.ui.screens.ScanFocusScreen

object Routes {
    const val Home = "home"
    const val Scan = "scan/{organId}"
    const val Detail = "detail/{organId}"
    const val Chat = "chat/{organId}"
    fun scan(id: String) = "scan/$id"
    fun detail(id: String) = "detail/$id"
    fun chat(id: String) = "chat/$id"
}

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.Home) {
        composable(Routes.Home) {
            HomeScreen(onOrganTap = { nav.navigate(Routes.scan(it)) })
        }
        composable(
            Routes.Scan,
            arguments = listOf(navArgument("organId") { type = NavType.StringType })
        ) {
            val id = it.arguments?.getString("organId") ?: "heart"
            ScanFocusScreen(
                organId = id,
                onClose = { nav.popBackStack() },
                onOpenDetails = { nav.navigate(Routes.detail(id)) }
            )
        }
        composable(
            Routes.Detail,
            arguments = listOf(navArgument("organId") { type = NavType.StringType })
        ) {
            val id = it.arguments?.getString("organId") ?: "heart"
            OrganDetailScreen(
                organId = id,
                onBack = { nav.popBackStack() },
                onOpenChat = { nav.navigate(Routes.chat(id)) }
            )
        }
        composable(
            Routes.Chat,
            arguments = listOf(navArgument("organId") { type = NavType.StringType })
        ) {
            val id = it.arguments?.getString("organId") ?: "heart"
            ChatScreen(organId = id, onBack = { nav.popBackStack() })
        }
    }
}
