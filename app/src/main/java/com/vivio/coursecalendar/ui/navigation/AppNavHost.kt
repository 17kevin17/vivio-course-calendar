package com.vivio.coursecalendar.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vivio.coursecalendar.ui.import.ImportScreen
import com.vivio.coursecalendar.ui.schedule.ScheduleConfigScreen
import com.vivio.coursecalendar.ui.history.BatchHistoryScreen
import com.vivio.coursecalendar.ui.home.HomeScreen

object Routes {
    const val HOME = "home"
    const val IMPORT = "import"
    const val SCHEDULE = "schedule"
    const val HISTORY = "history"
}

@Composable
fun AppNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(onNavigate = { route -> navController.navigate(route) }) }
        composable(Routes.IMPORT) { ImportScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.SCHEDULE) { ScheduleConfigScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.HISTORY) { BatchHistoryScreen(onBack = { navController.popBackStack() }) }
    }
}
