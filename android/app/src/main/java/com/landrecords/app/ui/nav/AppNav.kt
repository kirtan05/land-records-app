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
import com.landrecords.app.ui.fetch.IrcmsBatchScreen
import com.landrecords.app.ui.fetch.IrcmsCasesScreen
import com.landrecords.app.ui.fetch.IrcmsFetchScreen
import com.landrecords.app.ui.fetch.Vf712FetchScreen
import com.landrecords.app.ui.fetch.VfScansScreen
import com.landrecords.app.ui.fetch.RegenerateScreen
import com.landrecords.app.ui.library.LibraryScreen
import com.landrecords.app.ui.property.AddPropertyScreen
import com.landrecords.app.ui.settings.SettingsScreen
import com.landrecords.app.ui.survey.SurveyDetailScreen
import com.landrecords.app.ui.theme.LandMotion

private object Routes {
    const val LIBRARY = "library"
    const val SURVEY = "survey/{surveyId}?justAdded={justAdded}"
    const val FETCH = "fetch/{surveyId}/{recordType}"
    const val REGEN = "regen/{surveyId}/{recordType}"
    const val ADD = "add_property"
    const val SETTINGS = "settings"
    const val IRCMS_BATCH = "ircms_batch/{propertyId}"
    const val IRCMS_CASES = "ircms_cases/{surveyId}"
    const val VF_SCANS = "vf_scans/{surveyId}"

    fun survey(id: Long, justAdded: String? = null) = "survey/$id?justAdded=${justAdded ?: ""}"
    fun fetch(id: Long, type: RecordType) = "fetch/$id/${type.name}"
    fun regen(id: Long, type: RecordType) = "regen/$id/${type.name}"
    fun ircmsBatch(propertyId: Long) = "ircms_batch/$propertyId"
    fun ircmsCases(surveyId: Long) = "ircms_cases/$surveyId"
    fun vfScans(surveyId: Long) = "vf_scans/$surveyId"
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
                onBatchIrcms = { navController.navigate(Routes.ircmsBatch(it)) },
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
                onRegenerate = { sid, type -> navController.navigate(Routes.regen(sid, type)) },
                onCases = { sid -> navController.navigate(Routes.ircmsCases(sid)) },
                onScans = { sid -> navController.navigate(Routes.vfScans(sid)) },
            )
        }
        composable(
            Routes.REGEN,
            arguments = listOf(
                navArgument("surveyId") { type = NavType.LongType },
                navArgument("recordType") { type = NavType.StringType },
            ),
        ) { entry ->
            val id = entry.arguments?.getLong("surveyId") ?: return@composable
            val type = RecordType.valueOf(entry.arguments?.getString("recordType") ?: return@composable)
            RegenerateScreen(surveyId = id, recordType = type, onDone = { navController.popBackStack() })
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
            val onDoneNav: () -> Unit = {
                navController.navigate(Routes.survey(id, type.name)) { popUpTo(Routes.LIBRARY) }
            }
            when (type) {
                RecordType.IRCMS -> IrcmsFetchScreen(surveyId = id, onBack = { navController.popBackStack() }, onDone = onDoneNav)
                RecordType.VF712 -> Vf712FetchScreen(surveyId = id, onBack = { navController.popBackStack() }, onDone = onDoneNav)
                else -> FetchScreen(
                    surveyId = id,
                    recordType = type,
                    onBack = { navController.popBackStack() },
                    onDone = onDoneNav,
                )
            }
        }
        composable(Routes.ADD) {
            AddPropertyScreen(onBack = { navController.popBackStack() }, onSaved = { navController.popBackStack() })
        }
        composable(
            Routes.IRCMS_BATCH,
            arguments = listOf(navArgument("propertyId") { type = NavType.LongType }),
        ) { entry ->
            val pid = entry.arguments?.getLong("propertyId") ?: return@composable
            IrcmsBatchScreen(
                propertyId = pid,
                onBack = { navController.popBackStack() },
                onDone = { navController.popBackStack() },
            )
        }
        composable(
            Routes.IRCMS_CASES,
            arguments = listOf(navArgument("surveyId") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("surveyId") ?: return@composable
            IrcmsCasesScreen(surveyId = id, onBack = { navController.popBackStack() })
        }
        composable(
            Routes.VF_SCANS,
            arguments = listOf(navArgument("surveyId") { type = NavType.LongType }),
        ) { entry ->
            val id = entry.arguments?.getLong("surveyId") ?: return@composable
            VfScansScreen(surveyId = id, onBack = { navController.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
