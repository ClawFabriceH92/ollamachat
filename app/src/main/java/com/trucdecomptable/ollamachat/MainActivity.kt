package com.trucdecomptable.ollamachat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.trucdecomptable.ollamachat.auth.AuthManager
import com.trucdecomptable.ollamachat.auth.BiometricHelper
import com.trucdecomptable.ollamachat.ui.chat.ChatScreen
import com.trucdecomptable.ollamachat.ui.conversations.ConversationsScreen
import com.trucdecomptable.ollamachat.ui.lock.LockScreen
import com.trucdecomptable.ollamachat.ui.settings.SettingsScreen
import com.trucdecomptable.ollamachat.ui.theme.OllamaChatTheme
import com.trucdecomptable.ollamachat.ui.welcome.WelcomeScreen
import com.trucdecomptable.ollamachat.update.UpdateManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = (application as OllamaChatApp).container

        // Auto-update check at launch (release builds only).
        UpdateManager.start(this)

        setContent {
            val settings = container.settings
            val themePref by settings.theme.collectAsState(initial = "system")
            val firstLaunchDone by settings.firstLaunchDone.collectAsState(initial = false)
            val lockConfig by settings.lockConfig.collectAsState(initial = null)
            val unlocked by AuthManager.unlocked.collectAsState()

            val darkTheme = when (themePref) {
                "light" -> false
                "dark" -> true
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }

            OllamaChatTheme(darkTheme = darkTheme) {
                val navController = rememberNavController()

                var biometricAvailable by remember { mutableStateOf(BiometricHelper.isAvailable(this@MainActivity)) }

                NavHost(
                    navController = navController,
                    startDestination = if (firstLaunchDone) "conversations" else "welcome",
                ) {
                    composable("welcome") {
                        WelcomeScreen(
                            onDone = {
                                navController.navigate("conversations") {
                                    popUpTo("welcome") { inclusive = true }
                                }
                            },
                        )
                    }
                    composable("conversations") {
                        ConversationsScreen(
                            onOpenConversation = { id ->
                                navController.navigate("chat/$id")
                            },
                            onOpenSettings = { navController.navigate("settings") },
                        )
                    }
                    composable(
                        route = "chat/{conversationId}",
                        arguments = listOf(navArgument("conversationId") { type = NavType.LongType }),
                    ) { entry ->
                        val conversationId = entry.arguments?.getLong("conversationId") ?: 0L
                        ChatScreen(
                            conversationId = conversationId,
                            onBack = { navController.popBackStack() },
                            onOpenSettings = { navController.navigate("settings") },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(onBack = { navController.popBackStack() })
                    }
                }

                // Lock gate: full-screen PIN/biometric overlay when enabled & locked.
                val lock = lockConfig
                if (lock?.enabled == true && !unlocked) {
                    LockScreen(
                        expectedHash = lock.pinHash,
                        biometricAvailable = biometricAvailable,
                        onUnlock = { AuthManager.unlock() },
                        onBiometric = {
                            BiometricHelper.authenticate(
                                activity = this@MainActivity,
                                onSuccess = { AuthManager.unlock() },
                            )
                        },
                    )
                }
            }
        }

        // Re-lock when the app goes to background (if enabled).
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !isChangingConfigurations) {
                lifecycleScope.launch {
                    val lock = container.settings.lockConfig.first()
                    if (lock.enabled && lock.lockOnBackground) AuthManager.lock()
                }
            }
        })
    }
}
