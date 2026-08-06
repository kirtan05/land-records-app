package com.landrecords.app.ui.nav

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.landrecords.app.data.model.RecordType
import com.landrecords.app.ui.fetch.FetchScreen
import com.landrecords.app.ui.library.LibraryScreen
import com.landrecords.app.ui.property.AddPropertyScreen
import com.landrecords.app.ui.settings.SettingsScreen
import com.landrecords.app.ui.survey.SurveyDetailScreen
import com.landrecords.app.ui.theme.LandMotion

private object Routes {
    const val LIBRARY = "library"
    const val SURVEY = "survey/{surveyId}?justAdded={justAdded}"
    const val FETCH = "fetch/{surveyId}/{recordType}"
    const val ADD = "add_property"
    const val SETTINGS = "settings"

    fun survey(id: Long, justAdded: String? = null) = "survey/$id?justAdded=${justAdded ?: ""}"
    fun fetch(id: Long, type: RecordType) = "fetch/$id/${type.name}"
}

@Composable
fun AppNav(navController: NavHostController = rememberNavController()) {
    val enter = fadeIn(tween(LandMotion.screenEnterMs)) + slideInVertically(tween(LandMotion.screenEnterMs)) { it / 24 }
    val exit = fadeOut(tween(LandMotion.screenEnterMs / 2))

    NavHost(
        navController = navController,
        startDestination = Routes.LIBRARY,
        enterTransition = { enter },
        exitTransition = { exit },
        popEnterTransition = { enter },
        popExitTransition = { exit },
    ) {
        composable(Routes.LIBRARY) {
            LibraryScreen(
                onOpenSurvey = { navController.navigate(Routes.survey(it)) },
                onFetch = { id, type -> navController.navigate(Routes.fetch(id, type)) },
                onAddProperty = { navController.navigate(Routes.ADD) },
                onSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(
            Routes.SURVEY,
            arguments = listOf(
                navArgument("surveyId") { type = NavType.LongType },
                navArgument("justAdded") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val id = entry.arguments?.getLong("surveyId") ?: return@composable
            val justAdded = entry.arguments?.getString("justAdded").orEmpty()
                .takeIf { it.isNotBlank() }?.let { runCatching { RecordType.valueOf(it) }.getOrNull() }
            SurveyDetailScreen(
                surveyId = id,
                justAddedType = justAdded,
                onBack = { navController.popBackStack() },
                onFetch = { sid, type -> navController.navigate(Routes.fetch(sid, type)) },
                onView = {},
                onShare = {},
                onRegenerate = {},
            )
        }
        composable(
            Routes.FETCH,
            arguments = listOf(
                navArgument("surveyId") { type = NavType.LongType },
                navArgument("recordType") { type = NavType.StringType },
            ),
        ) { entry ->
            val id = entry.arguments?.getLong("surveyId") ?: return@composable
            val type = RecordType.valueOf(entry.arguments?.getString("recordType") ?: return@composable)
            FetchScreen(
                surveyId = id,
                recordType = type,
                onBack = { navController.popBackStack() },
                onDone = {
                    navController.navigate(Routes.survey(id, type.name)) {
                        popUpTo(Routes.LIBRARY)
                    }
                },
            )
        }
        composable(Routes.ADD) {
            AddPropertyScreen(onBack = { navController.popBackStack() }, onSaved = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
