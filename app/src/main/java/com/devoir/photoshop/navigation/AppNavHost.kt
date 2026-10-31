package com.devoir.photoshop.navigation

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.devoir.photoshop.AppViewModel
import com.devoir.photoshop.core.project.ProjectMath
import com.devoir.photoshop.data.InMemoryProjectRepository
import com.devoir.photoshop.data.ProjectRepository
import com.devoir.photoshop.engine.image.ImageLoader
import com.devoir.photoshop.feature.editor.EditorScreen
import com.devoir.photoshop.feature.home.HomeScreen
import com.devoir.photoshop.feature.newproject.NewProjectScreen
import com.devoir.photoshop.feature.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val NEW_PROJECT = "new_project"
    const val EDITOR = "editor/{projectId}?importUri={importUri}"
    const val SETTINGS = "settings"
    fun editor(projectId: String, importUri: Uri? = null) =
        "editor/$projectId" + (importUri?.let { "?importUri=${Uri.encode(it.toString())}" } ?: "")
}

/** New screen = one Routes constant + one composable() block. */
@Composable
fun AppNavHost(
    appVm: AppViewModel,
    repo: ProjectRepository = InMemoryProjectRepository,
    nav: NavHostController = rememberNavController(),
) {
    val context = LocalContext.current
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                projects = repo.recent(),
                onNewProject = { nav.navigate(Routes.NEW_PROJECT) },
                onOpenProject = { nav.navigate(Routes.editor(it.id)) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onImportImage = { uri -> importAsNewProject(context, repo, nav, uri) },
            )
        }
        composable(Routes.NEW_PROJECT) {
            NewProjectScreen(
                onBack = { nav.popBackStack() },
                onCreate = { name, config ->
                    val project = repo.create(name, config)
                    nav.navigate(Routes.editor(project.id)) { popUpTo(Routes.NEW_PROJECT) { inclusive = true } }
                },
            )
        }
        composable(
            Routes.EDITOR,
            arguments = listOf(
                navArgument("projectId") { type = NavType.StringType },
                navArgument("importUri") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) {
            EditorScreen(onBack = { nav.popBackStack() }, onOpenSettings = { nav.navigate(Routes.SETTINGS) })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(appVm, onBack = { nav.popBackStack() })
        }
    }
}

/** Returns an error message, or null on success. */
private suspend fun importAsNewProject(
    context: Context, repo: ProjectRepository, nav: NavHostController, uri: Uri,
): String? {
    val loader = ImageLoader(context)
    val size = loader.readSize(uri) ?: return "Couldn't read this image."
    val config = ProjectMath.configFromImageSize(size.width, size.height)
    val name = loader.displayName(uri)?.substringBeforeLast('.')?.ifBlank { null } ?: "Imported Image"
    val project = repo.create(name, config)
    nav.navigate(Routes.editor(project.id, uri))
    return null
}
