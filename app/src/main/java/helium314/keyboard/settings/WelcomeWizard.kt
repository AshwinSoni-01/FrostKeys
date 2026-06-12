// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.UncachedInputMethodManagerUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class WizardScreen {
    Splash,
    Setup
}

val InfoIcon: ImageVector = ImageVector.Builder(
    name = "Info",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).path(
    fill = SolidColor(Color.White),
    fillAlpha = 1f,
    stroke = null,
    strokeAlpha = 1f,
    strokeLineWidth = 1f,
    strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Butt,
    strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Miter,
    strokeLineMiter = 1f,
    pathFillType = PathFillType.NonZero
) {
    moveTo(12f, 2f)
    curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
    curveTo(2f, 17.52f, 6.48f, 22f, 12f, 22f)
    curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
    curveTo(22f, 6.48f, 17.52f, 2f, 12f, 2f)
    close()
    moveTo(13f, 17f)
    horizontalLineTo(11f)
    verticalLineTo(11f)
    horizontalLineTo(13f)
    verticalLineTo(17f)
    close()
    moveTo(13f, 9f)
    horizontalLineTo(11f)
    verticalLineTo(7f)
    horizontalLineTo(13f)
    verticalLineTo(9f)
    close()
}.build()


@OptIn(ExperimentalTextApi::class)
@Composable
fun WelcomeWizard(
    close: () -> Unit,
    finish: () -> Unit
) {
    val ctx = LocalContext.current
    val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    var isEnabled by remember { mutableStateOf(UncachedInputMethodManagerUtils.isThisImeEnabled(ctx, imm)) }
    var isCurrent by remember { mutableStateOf(UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm)) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isEnabled = UncachedInputMethodManagerUtils.isThisImeEnabled(ctx, imm)
        isCurrent = UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm)
    }

    val scope = rememberCoroutineScope { Dispatchers.IO }

    LaunchedEffect(isEnabled, isCurrent) {
        if (isEnabled && !isCurrent) {
            scope.launch {
                while (!UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm)) {
                    delay(500)
                    isCurrent = UncachedInputMethodManagerUtils.isThisImeCurrent(ctx, imm)
                }
            }
        }
    }

    val darkTheme = isSystemInDarkTheme()
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    } else {
        if (darkTheme) darkColorScheme() else lightColorScheme()
    }

    val customFont = KeyboardTypeface.customFontFamily()
    val googleSansFlex = remember {
        FontFamily(
            Font(
                resId = R.font.google_sans_flex,
                variationSettings = FontVariation.Settings(
                    FontVariation.Setting("ROND", 100f)
                )
            )
        )
    }
    val fontFamily = customFont ?: googleSansFlex

    val baseTypography = MaterialTheme.typography
    val customTypography = remember(fontFamily) {
        Typography(
            displayLarge = baseTypography.displayLarge.copy(fontFamily = fontFamily),
            displayMedium = baseTypography.displayMedium.copy(fontFamily = fontFamily),
            displaySmall = baseTypography.displaySmall.copy(fontFamily = fontFamily),
            headlineLarge = baseTypography.headlineLarge.copy(fontFamily = fontFamily),
            headlineMedium = baseTypography.headlineMedium.copy(fontFamily = fontFamily),
            headlineSmall = baseTypography.headlineSmall.copy(fontFamily = fontFamily),
            titleLarge = baseTypography.titleLarge.copy(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold),
            titleMedium = baseTypography.titleMedium.copy(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold),
            titleSmall = baseTypography.titleSmall.copy(fontFamily = fontFamily, fontWeight = FontWeight.SemiBold),
            bodyLarge = baseTypography.bodyLarge.copy(fontFamily = fontFamily),
            bodyMedium = baseTypography.bodyMedium.copy(fontFamily = fontFamily),
            bodySmall = baseTypography.bodySmall.copy(fontFamily = fontFamily),
            labelLarge = baseTypography.labelLarge.copy(fontFamily = fontFamily),
            labelMedium = baseTypography.labelMedium.copy(fontFamily = fontFamily),
            labelSmall = baseTypography.labelSmall.copy(fontFamily = fontFamily)
        )
    }

    var currentScreen by rememberSaveable { mutableStateOf(WizardScreen.Splash) }

    val mainScope = rememberCoroutineScope()
    var toastMessage by remember { mutableStateOf<String?>(null) }
    var toastJob by remember { mutableStateOf<Job?>(null) }
    val showToast: (String) -> Unit = { message ->
        toastJob?.cancel()
        toastJob = mainScope.launch {
            toastMessage = message
            delay(3000)
            toastMessage = null
        }
    }

    val isSplash = currentScreen == WizardScreen.Splash
    val buttonText = if (isSplash) "Let's get you started" else "Configure Keyboard"

    val circleBgColor by animateColorAsState(
        targetValue = if (isSplash) {
            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f)
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
        label = "circleBgColor"
    )

    val arrowColor by animateColorAsState(
        targetValue = if (isSplash) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onPrimaryContainer
        },
        label = "arrowColor"
    )

    val buttonOnClick = {
        if (isSplash) {
            currentScreen = WizardScreen.Setup
        } else {
            if (isEnabled && isCurrent) {
                close()
            } else {
                showToast("Please make sure to both Enable and Switch to Frostkeys!")
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = customTypography
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 600.dp)
                ) {
                    val density = LocalDensity.current
                    val slideOffset = remember(density) { with(density) { 30.dp.roundToPx() } }

                    AnimatedContent(
                        targetState = currentScreen,
                        transitionSpec = {
                            if (targetState == WizardScreen.Setup) {
                                (slideInHorizontally(
                                    initialOffsetX = { slideOffset },
                                    animationSpec = spring(stiffness = 120f, dampingRatio = Spring.DampingRatioNoBouncy)
                                ) + fadeIn(animationSpec = tween(300))).togetherWith(
                                    slideOutHorizontally(
                                        targetOffsetX = { -slideOffset },
                                        animationSpec = spring(stiffness = 120f, dampingRatio = Spring.DampingRatioNoBouncy)
                                    ) + fadeOut(animationSpec = tween(300))
                                )
                            } else {
                                (slideInHorizontally(
                                    initialOffsetX = { -slideOffset },
                                    animationSpec = spring(stiffness = 120f, dampingRatio = Spring.DampingRatioNoBouncy)
                                ) + fadeIn(animationSpec = tween(300))).togetherWith(
                                    slideOutHorizontally(
                                        targetOffsetX = { slideOffset },
                                        animationSpec = spring(stiffness = 120f, dampingRatio = Spring.DampingRatioNoBouncy)
                                    ) + fadeOut(animationSpec = tween(300))
                                )
                            }
                        },
                        label = "screenTransition",
                        modifier = Modifier.fillMaxSize()
                    ) { screen ->
                        when (screen) {
                            WizardScreen.Splash -> {
                                SplashScreenView(
                                    fontFamily = fontFamily,
                                    isAnimating = currentScreen == WizardScreen.Splash
                                )
                            }
                            WizardScreen.Setup -> {
                                SetupScreenView(
                                    isEnabled = isEnabled,
                                    isCurrent = isCurrent,
                                    onEnableClicked = {
                                        val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                                        intent.addCategory(Intent.CATEGORY_DEFAULT)
                                        launcher.launch(intent)
                                    },
                                    onSwitchClicked = {
                                        imm.showInputMethodPicker()
                                    },
                                    onBack = { currentScreen = WizardScreen.Splash },
                                    showToast = showToast,
                                    fontFamily = fontFamily
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        MaterialTheme.colorScheme.background.copy(alpha = 0.9f),
                                        MaterialTheme.colorScheme.background
                                    )
                                )
                            )
                            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            onClick = buttonOnClick,
                            modifier = Modifier
                                .wrapContentWidth()
                                .height(64.dp),
                            shape = RoundedCornerShape(32.dp),
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            shadowElevation = 0.dp
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))
                                    .padding(start = 32.dp, end = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AnimatedContent(
                                    targetState = buttonText,
                                    transitionSpec = {
                                        fadeIn(animationSpec = tween(250)) togetherWith fadeOut(animationSpec = tween(250))
                                    },
                                    label = "buttonTextTransition"
                                ) { text ->
                                    Text(
                                        text = text,
                                        style = MaterialTheme.typography.titleMedium.copy(
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            fontFamily = fontFamily
                                        )
                                    )
                                }
                                Surface(
                                    shape = CircleShape,
                                    color = circleBgColor,
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_arrow_left_rounded),
                                            contentDescription = null,
                                            tint = arrowColor,
                                            modifier = Modifier
                                                .size(18.dp)
                                                .rotate(180f)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = toastMessage != null,
                        enter = slideInVertically(
                            initialOffsetY = { it / 2 },
                            animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)
                        ) + fadeIn(),
                        exit = slideOutVertically(
                            targetOffsetY = { it / 2 },
                            animationSpec = tween(durationMillis = 250)
                        ) + fadeOut(),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 112.dp)
                            .padding(horizontal = 24.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.inverseSurface,
                            shadowElevation = 8.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = InfoIcon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = toastMessage ?: "",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = fontFamily
                                    ),
                                    color = MaterialTheme.colorScheme.inverseOnSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SplashScreenView(
    fontFamily: FontFamily,
    isAnimating: Boolean = true
) {
    val infiniteTransition = rememberInfiniteTransition(label = "glowBreathing")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = kotlin.math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2300, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowPhase"
    )
    val glowScale = 0.9f + 0.175f * (kotlin.math.cos(phase) + 1f)
    val glowAlpha = 0.6f + 0.4f * (kotlin.math.cos(phase) + 1f) / 2f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .padding(top = 48.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = "Welcome to",
                style = MaterialTheme.typography.displayMedium.copy(
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Light,
                    lineHeight = 52.sp,
                    fontFamily = fontFamily
                ),
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = "FrostKeys",
                style = MaterialTheme.typography.displayMedium.copy(
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 52.sp,
                    fontFamily = fontFamily
                ),
                color = MaterialTheme.colorScheme.primary
            )
        }

        val isDark = isSystemInDarkTheme()
        val glowColor = if (isDark) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer
        val alphaCenter = if (isDark) 0.45f else 0.9f
        val alphaEdge = if (isDark) 0.18f else 0.7f
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(340.dp),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .scale(1.2f * glowScale)
            ) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            glowColor.copy(alpha = alphaCenter * glowAlpha),
                            glowColor.copy(alpha = alphaEdge * glowAlpha),
                            Color.Transparent
                        ),
                        center = center,
                        radius = size.width / 2f
                    )
                )
            }

            AnimatedFrostKeysIcon(
                modifier = Modifier.size(320.dp),
                isAnimating = isAnimating,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun SetupScreenView(
    isEnabled: Boolean,
    isCurrent: Boolean,
    onEnableClicked: () -> Unit,
    onSwitchClicked: () -> Unit,
    onBack: () -> Unit,
    showToast: (String) -> Unit,
    fontFamily: FontFamily
) {
    var isEssentialExpanded by rememberSaveable { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (isEssentialExpanded) 180f else 0f,
        animationSpec = tween(300),
        label = "chevronRotation"
    )

    BackHandler(enabled = true, onBack = onBack)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                Spacer(modifier = Modifier.height(48.dp))
                Text(
                    text = "Let's get you",
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Light,
                        lineHeight = 52.sp,
                        fontFamily = fontFamily
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "started",
                    style = MaterialTheme.typography.displayMedium.copy(
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 52.sp,
                        fontFamily = fontFamily
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(24.dp))

                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        SegmentedListItem(
                            onClick = onEnableClicked,
                            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 8.dp, bottomEnd = 8.dp),
                            selected = isEnabled,
                            headlineContent = {
                                Text(
                                    text = "Enable Frostkeys",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = fontFamily
                                    )
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = "Check \"Frostkeys\" in your language & input settings.",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 13.sp,
                                        fontFamily = fontFamily
                                    )
                                )
                            },
                            trailingContent = {
                                val checkColor = if (isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(
                                            color = if (isEnabled) checkColor else Color.Transparent,
                                            shape = RoundedCornerShape(4.dp)
                                        )
                                        .border(
                                            width = 2.dp,
                                            color = checkColor,
                                            shape = RoundedCornerShape(4.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isEnabled) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_check_bold),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        )

                        SegmentedListItem(
                            onClick = {
                                if (isEnabled) {
                                    onSwitchClicked()
                                } else {
                                    showToast("Please enable FrostKeys first!")
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            enabled = isEnabled,
                            selected = isCurrent,
                            headlineContent = {
                                Text(
                                    text = "Switch to Frostkeys",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = fontFamily
                                    )
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = "Select \"Frostkeys\" as your active text-input method.",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 13.sp,
                                        fontFamily = fontFamily
                                    )
                                )
                            },
                            trailingContent = {
                                val checkColor = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(
                                            color = if (isCurrent) checkColor else Color.Transparent,
                                            shape = RoundedCornerShape(4.dp)
                                        )
                                        .border(
                                            width = 2.dp,
                                            color = checkColor,
                                            shape = RoundedCornerShape(4.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isCurrent) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_check_bold),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        )

                        SegmentedListItem(
                            onClick = {
                                if (isCurrent) {
                                    isEssentialExpanded = !isEssentialExpanded
                                } else {
                                    showToast("Please switch to Frostkeys first!")
                                }
                            },
                            shape = if (isEssentialExpanded && isCurrent) {
                                RoundedCornerShape(8.dp)
                            } else {
                                RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 28.dp, bottomEnd = 28.dp)
                            },
                            enabled = isCurrent,
                            headlineContent = {
                                Text(
                                    text = "Essential settings",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        fontFamily = fontFamily
                                    )
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = "Configure these essential settings to unlock full potential of Frostkeys.",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontSize = 13.sp,
                                        fontFamily = fontFamily
                                    )
                                )
                            },
                            trailingContent = {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_chevron_down),
                                            contentDescription = null,
                                            tint = if (isCurrent) MaterialTheme.colorScheme.onSurface
                                                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                            modifier = Modifier
                                                .size(24.dp)
                                                .rotate(rotation)
                                        )
                                    }
                                }
                            }
                        )

                        AnimatedVisibility(
                            visible = isEssentialExpanded && isCurrent,
                            enter = expandVertically(animationSpec = tween(350, easing = FastOutSlowInEasing)) + fadeIn(animationSpec = tween(350)),
                            exit = shrinkVertically(animationSpec = tween(350, easing = FastOutSlowInEasing)) + fadeOut(animationSpec = tween(350))
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                SegmentedListItem(
                                    onClick = {
                                        SettingsDestination.navigateTo(SettingsDestination.GestureTyping)
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    headlineContent = {
                                        Text(
                                            text = "Setup Gesture Typing",
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                fontFamily = fontFamily
                                            )
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            text = "Import the gesture library for your CPU architecture.",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontSize = 12.sp,
                                                fontFamily = fontFamily
                                            )
                                        )
                                    },
                                    leadingContent = {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = "1",
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    style = MaterialTheme.typography.titleMedium.copy(
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        fontFamily = fontFamily
                                                    )
                                                )
                                            }
                                        }
                                    },
                                    trailingContent = {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_arrow_left_rounded),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier
                                                .size(18.dp)
                                                .rotate(180f)
                                        )
                                    }
                                )

                                SegmentedListItem(
                                    onClick = {
                                        SettingsDestination.navigateTo(SettingsDestination.Cloud)
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    headlineContent = {
                                        Text(
                                            text = "Setup AI Tools",
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                fontFamily = fontFamily
                                            )
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            text = "Get personal API keys for smart tools & GIF searches.",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontSize = 12.sp,
                                                fontFamily = fontFamily
                                            )
                                        )
                                    },
                                    leadingContent = {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = "2",
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    style = MaterialTheme.typography.titleMedium.copy(
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        fontFamily = fontFamily
                                                    )
                                                )
                                            }
                                        }
                                    },
                                    trailingContent = {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_arrow_left_rounded),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier
                                                .size(18.dp)
                                                .rotate(180f)
                                        )
                                    }
                                )

                                SegmentedListItem(
                                    onClick = {
                                        SettingsDestination.navigateTo(SettingsDestination.Dictionaries)
                                    },
                                    shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 28.dp, bottomEnd = 28.dp),
                                    headlineContent = {
                                        Text(
                                            text = "Add Offline Dictionaries",
                                            style = MaterialTheme.typography.titleMedium.copy(
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                fontFamily = fontFamily
                                            )
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            text = "Download word and emoji suggestion dictionaries.",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontSize = 12.sp,
                                                fontFamily = fontFamily
                                            )
                                        )
                                    },
                                    leadingContent = {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = "3",
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    style = MaterialTheme.typography.titleMedium.copy(
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        fontFamily = fontFamily
                                                    )
                                                )
                                            }
                                        }
                                    },
                                    trailingContent = {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_arrow_left_rounded),
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier
                                                .size(18.dp)
                                                .rotate(180f)
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(128.dp))
            }
        }
    }
}

@Composable
fun SegmentedListItem(
    onClick: () -> Unit,
    shape: androidx.compose.ui.graphics.Shape,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    leadingContent: @Composable (() -> Unit)? = null,
    headlineContent: @Composable () -> Unit,
    supportingContent: @Composable (() -> Unit)? = null,
    trailingContent: @Composable (() -> Unit)? = null
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }

    val headlineColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val supportingColor = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth(),
        shape = shape,
        color = containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingContent != null) {
                leadingContent()
                Spacer(modifier = Modifier.width(16.dp))
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                CompositionLocalProvider(LocalContentColor provides headlineColor) {
                    headlineContent()
                }
                if (supportingContent != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    CompositionLocalProvider(LocalContentColor provides supportingColor) {
                        supportingContent()
                    }
                }
            }
            if (trailingContent != null) {
                Spacer(modifier = Modifier.width(16.dp))
                trailingContent()
            }
        }
    }
}

