package com.trucdecomptable.ollamachat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
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
import com.trucdecomptable.ollamachat.ui.update.UpdateDialog
import com.trucdecomptable.ollamachat.ui.welcome.WelcomeScreen
import com.trucdecomptable.ollamachat.update.UpdateManager
import com.trucdecomptable.ollamachat.util.PinUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = (application as OllamaChatApp).container
        val settings = container.settings



        setContent {
            val themePref by settings.theme.collectAsState(initial = "system")
            val firstLaunchDone by settings.firstLaunchDone.collectAsState(initial = false)
            val lockConfig by settings.lockConfig.collectAsState(initial = null)
            val dynamicColor by settings.dynamicColor.collectAsState(initial = false)
            val updateState by UpdateManager.state.collectAsState()

            // Checked once per launch, and never re-offering a version the
            // user already put off.
            LaunchedEffect(Unit) {
                UpdateManager.checkAtLaunch(settings.skippedUpdate.first())
            }
            val unlocked by AuthManager.unlocked.collectAsState()

            val darkTheme = when (themePref) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }

            // Ask once for notifications: without it the update notice and the
            // "generating" foreground notice are silently dropped on Android 13+.
            val notificationLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { }
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            // Keep conversations out of the recent-apps preview while the
            // lock is on — a lock that only covers the running app is theatre.
            val lockEnabled = lockConfig?.active == true
            LaunchedEffect(lockEnabled) {
                if (lockEnabled) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }

            OllamaChatTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
                val navController = rememberNavController()
                val biometricAvailable = remember {
                    mutableStateOf(BiometricHelper.isAvailable(this@MainActivity))
                }

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
                            onOpenConversation = { id -> navController.navigate("chat/$id") },
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

                UpdateDialog(
                    state = updateState,
                    context = this@MainActivity,
                    onSkip = { version ->
                        lifecycleScope.launch { settings.setSkippedUpdate(version) }
                        UpdateManager.dismiss()
                    },
                    onDismiss = { UpdateManager.dismiss() },
                )

                // Lock gate: full-screen PIN/biometric overlay when enabled & locked.
                val lock = lockConfig
                if (lock != null && lock.active && !unlocked) {
                    LockScreen(
                        biometricAvailable = biometricAvailable.value,
                        lockedUntil = lock.lockedUntil,
                        onSubmit = { pin ->
                            // PBKDF2 is intentionally slow: never on the main thread.
                            val ok = withContext(Dispatchers.Default) {
                                PinUtils.verify(pin, lock.pinHash)
                            }
                            if (ok) {
                                if (PinUtils.needsUpgrade(lock.pinHash)) {
                                    val upgraded = withContext(Dispatchers.Default) { PinUtils.hash(pin) }
                                    settings.setPinHash(upgraded)
                                }
                                settings.clearFailedUnlocks()
                                AuthManager.unlock()
                            } else {
                                settings.recordFailedUnlock()
                            }
                            ok
                        },
                        onBiometric = {
                            BiometricHelper.authenticate(
                                activity = this@MainActivity,
                                onSuccess = {
                                    lifecycleScope.launch { settings.clearFailedUnlocks() }
                                    AuthManager.unlock()
                                },
                            )
                        },
                    )
                }
            }
        }

        // A cold start is not the only moment an update matters: check again
        // when the app comes back, throttled to once an hour by UpdateManager.
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                lifecycleScope.launch {
                    UpdateManager.checkAtLaunch(settings.skippedUpdate.first())
                }
            }
        })

        // Re-lock when the app goes to background (if enabled).
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !isChangingConfigurations) {
                lifecycleScope.launch {
                    val lock = settings.lockConfig.first()
                    if (lock.active && lock.lockOnBackground) AuthManager.lock()
                }
            }
        })
    }
}
