package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.PhoneInTalk
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.security.configureAppCheck
import com.example.ui.MainViewModel
import com.example.ui.screens.AssistantScreen
import com.example.ui.screens.CommandHubScreen
import com.example.ui.screens.ServiceAndCallsScreen
import com.example.ui.screens.SetupScreen
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.DeepPurple
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.PlasmaGlow
import com.example.ui.theme.TanjitAiTheme
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.VoidBlack

private enum class TanjitDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
) {
    ASSISTANT(
        route = "assistant",
        label = "অ্যাসিস্ট্যান্ট",
        selectedIcon = Icons.Filled.GraphicEq,
        unselectedIcon = Icons.Outlined.GraphicEq,
        testTag = "nav_assistant"
    ),
    HUB(
        route = "command_hub",
        label = "কমান্ড",
        selectedIcon = Icons.Filled.Apps,
        unselectedIcon = Icons.Outlined.Apps,
        testTag = "nav_hub"
    ),
    SERVICE(
        route = "service_calls",
        label = "সার্ভিস",
        selectedIcon = Icons.Filled.PhoneInTalk,
        unselectedIcon = Icons.Outlined.PhoneInTalk,
        testTag = "nav_service"
    ),
    SETUP(
        route = "setup_voice",
        label = "সেটআপ",
        selectedIcon = Icons.Filled.Tune,
        unselectedIcon = Icons.Outlined.Tune,
        testTag = "nav_setup"
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Configure Firebase App Check with launch intent before rendering UI or ViewModels
        configureAppCheck(this, intent)

        enableEdgeToEdge()
        setContent {
            TanjitAiTheme {
                TanjitAppShell()
            }
        }
    }
}

@Composable
private fun TanjitAppShell(viewModel: MainViewModel = viewModel()) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val orbState by viewModel.orbState.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isBackgroundRunning by viewModel.isBackgroundServiceRunning.collectAsStateWithLifecycle()
    val incomingCallState by viewModel.incomingCallState.collectAsStateWithLifecycle()
    val callEvents by viewModel.callAssistEvents.collectAsStateWithLifecycle()
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val permissionSummary by viewModel.permissionSummary.collectAsStateWithLifecycle()
    val isTorchOn by viewModel.isTorchOn.collectAsStateWithLifecycle()
    val hasFlashlight by viewModel.hasFlashlight.collectAsStateWithLifecycle()
    val rmsLevel by viewModel.rmsLevel.collectAsStateWithLifecycle()
    val partialSpeechText by viewModel.partialSpeechText.collectAsStateWithLifecycle()
    val isSpeaking by viewModel.isSpeaking.collectAsStateWithLifecycle()
    val isBengaliVoiceMissing by viewModel.isBengaliVoiceMissing.collectAsStateWithLifecycle()
    val availableVoices by viewModel.availableVoices.collectAsStateWithLifecycle()
    val activeVoiceLabel by viewModel.activeVoiceLabel.collectAsStateWithLifecycle()

    val navigateToRoute: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(VoidBlack)
    ) {
        val useNavRail = maxWidth >= 600.dp

        if (useNavRail) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                NavigationRail(
                    containerColor = ObsidianSurface,
                    contentColor = PlasmaGlow
                ) {
                    TanjitDestination.entries.forEach { dest ->
                        val selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true
                        NavigationRailItem(
                            selected = selected,
                            onClick = { navigateToRoute(dest.route) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                    contentDescription = dest.label
                                )
                            },
                            label = { Text(dest.label, style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = CyberCyan,
                                selectedTextColor = PlasmaGlow,
                                indicatorColor = DeepPurple,
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary
                            ),
                            modifier = Modifier.testTag(dest.testTag)
                        )
                    }
                }

                Box(modifier = Modifier.weight(1f)) {
                    TanjitNavHost(
                        navController = navController,
                        orbState = orbState,
                        rmsLevel = rmsLevel,
                        partialSpeechText = partialSpeechText,
                        isSpeaking = isSpeaking,
                        isTorchOn = isTorchOn,
                        hasFlashlight = hasFlashlight,
                        isBackgroundRunning = isBackgroundRunning,
                        permissionSummary = permissionSummary,
                        messages = messages,
                        contacts = contacts,
                        settings = settings,
                        incomingCallState = incomingCallState,
                        callEvents = callEvents,
                        isBengaliVoiceMissing = isBengaliVoiceMissing,
                        availableVoices = availableVoices,
                        activeVoiceLabel = activeVoiceLabel,
                        viewModel = viewModel,
                        onNavigateToSetup = { navigateToRoute(TanjitDestination.SETUP.route) }
                    )
                }
            }
        } else {
            Scaffold(
                containerColor = VoidBlack,
                contentWindowInsets = WindowInsets.statusBars,
                bottomBar = {
                    NavigationBar(
                        containerColor = ObsidianSurface,
                        contentColor = PlasmaGlow,
                        windowInsets = WindowInsets.navigationBars
                    ) {
                        TanjitDestination.entries.forEach { dest ->
                            val selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateToRoute(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = dest.label
                                    )
                                },
                                label = {
                                    Text(
                                        text = dest.label,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = CyberCyan,
                                    selectedTextColor = PlasmaGlow,
                                    indicatorColor = DeepPurple,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                ),
                                modifier = Modifier.testTag(dest.testTag)
                            )
                        }
                    }
                }
            ) { innerPadding ->
                TanjitNavHost(
                    navController = navController,
                    orbState = orbState,
                    rmsLevel = rmsLevel,
                    partialSpeechText = partialSpeechText,
                    isSpeaking = isSpeaking,
                    isTorchOn = isTorchOn,
                    hasFlashlight = hasFlashlight,
                    isBackgroundRunning = isBackgroundRunning,
                    permissionSummary = permissionSummary,
                    messages = messages,
                    contacts = contacts,
                    settings = settings,
                    incomingCallState = incomingCallState,
                    callEvents = callEvents,
                    isBengaliVoiceMissing = isBengaliVoiceMissing,
                    availableVoices = availableVoices,
                    activeVoiceLabel = activeVoiceLabel,
                    viewModel = viewModel,
                    onNavigateToSetup = { navigateToRoute(TanjitDestination.SETUP.route) },
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }
}

@Composable
private fun TanjitNavHost(
    navController: androidx.navigation.NavHostController,
    orbState: com.example.data.OrbState,
    rmsLevel: Float,
    partialSpeechText: String,
    isSpeaking: Boolean,
    isTorchOn: Boolean,
    hasFlashlight: Boolean,
    isBackgroundRunning: Boolean,
    permissionSummary: com.example.data.PermissionSummary,
    messages: List<com.example.data.ConversationMessage>,
    contacts: List<com.example.data.ContactMatch>,
    settings: com.example.data.AssistantSettings,
    incomingCallState: com.example.data.IncomingCallState,
    callEvents: List<com.example.data.CallAssistEvent>,
    isBengaliVoiceMissing: Boolean,
    availableVoices: List<com.example.data.VoiceProfile>,
    activeVoiceLabel: String,
    viewModel: MainViewModel,
    onNavigateToSetup: () -> Unit,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = TanjitDestination.ASSISTANT.route,
        modifier = modifier
    ) {
        composable(TanjitDestination.ASSISTANT.route) {
            AssistantScreen(
                orbState = orbState,
                rmsLevel = rmsLevel,
                partialSpeechText = partialSpeechText,
                isSpeaking = isSpeaking,
                isTorchOn = isTorchOn,
                isBackgroundRunning = isBackgroundRunning,
                permissionSummary = permissionSummary,
                messages = messages,
                onOrbClick = {
                    if (!permissionSummary.microphone) {
                        onNavigateToSetup()
                    } else {
                        viewModel.toggleListening()
                    }
                },
                onStopSpeaking = viewModel::stopSpeaking,
                onToggleFlashlight = viewModel::toggleFlashlight,
                onSubmitCommand = viewModel::submitTextCommand,
                onClearChat = viewModel::clearConversation,
                onNavigateToSetup = onNavigateToSetup
            )
        }

        composable(TanjitDestination.HUB.route) {
            CommandHubScreen(
                isTorchOn = isTorchOn,
                hasFlashlight = hasFlashlight,
                contacts = contacts,
                permissionSummary = permissionSummary,
                onOpenYouTube = viewModel::openYouTube,
                onOpenFacebook = viewModel::openFacebook,
                onOpenWebsite = viewModel::openWebsite,
                onToggleFlashlight = viewModel::toggleFlashlight,
                onOpenCamera = viewModel::openCamera,
                onCallContact = viewModel::callContact,
                onNavigateToSetup = onNavigateToSetup
            )
        }

        composable(TanjitDestination.SERVICE.route) {
            ServiceAndCallsScreen(
                isBackgroundRunning = isBackgroundRunning,
                settings = settings,
                incomingCallState = incomingCallState,
                callEvents = callEvents,
                permissionSummary = permissionSummary,
                onToggleBackgroundService = { enable -> viewModel.toggleBackgroundService(enable) },
                onToggleWakeWordOnly = viewModel::setWakeWordOnly,
                onToggleIncomingAnnounce = viewModel::setIncomingCallAnnounce,
                onToggleIncomingVoiceControl = viewModel::setIncomingCallVoiceControl,
                onAnswerCall = viewModel::answerIncomingCall,
                onDeclineCall = viewModel::declineIncomingCall,
                onNavigateToSetup = onNavigateToSetup
            )
        }

        composable(TanjitDestination.SETUP.route) {
            SetupScreen(
                permissionSummary = permissionSummary,
                settings = settings,
                activeVoiceLabel = activeVoiceLabel,
                isBengaliVoiceMissing = isBengaliVoiceMissing,
                availableVoices = availableVoices,
                onPermissionsUpdated = viewModel::refreshPermissions,
                onOpenAppSettings = viewModel::openAppPermissionSettings,
                onSelectDialect = viewModel::setSpeechLocale,
                onPitchChange = viewModel::setMalePitch,
                onRateChange = viewModel::setSpeechRate,
                onSelectVoice = viewModel::setPreferredVoice,
                onTestMaleVoice = viewModel::testMaleVoice,
                onOpenTtsSettings = viewModel::openTtsSettings
            )
        }
    }
}
