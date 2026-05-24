// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.utils

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.latin.R

@Composable
fun Theme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val customFontFamily = KeyboardTypeface.customFontFamily()
    val material3 = Typography()
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(LocalContext.current)
        else dynamicLightColorScheme(LocalContext.current)
    } else {
        // todo (later): more colors
        if (dark) darkColorScheme(
            primary = colorResource(R.color.accent),
        )
        else lightColorScheme(
            primary = colorResource(R.color.accent)
        )
    }

    val typography = Typography(
        displayLarge = material3.displayLarge.copy(fontFamily = customFontFamily),
        displayMedium = material3.displayMedium.copy(fontFamily = customFontFamily),
        displaySmall = material3.displaySmall.copy(fontFamily = customFontFamily),
        headlineLarge = headlineLarge(material3, customFontFamily),
        headlineMedium = headlineMedium(material3, customFontFamily),
        headlineSmall = headlineSmall(material3, customFontFamily),
        titleLarge = material3.titleLarge.copy(fontFamily = customFontFamily, fontWeight = FontWeight.Bold),
        titleMedium = material3.titleMedium.copy(fontFamily = customFontFamily, fontWeight = FontWeight.Bold),
        titleSmall = material3.titleSmall.copy(fontFamily = customFontFamily, fontWeight = FontWeight.Bold),
        bodyLarge = material3.bodyLarge.copy(fontFamily = customFontFamily),
        bodyMedium = material3.bodyMedium.copy(fontFamily = customFontFamily),
        bodySmall = material3.bodySmall.copy(fontFamily = customFontFamily),
        labelLarge = material3.labelLarge.copy(fontFamily = customFontFamily),
        labelMedium = material3.labelMedium.copy(fontFamily = customFontFamily),
        labelSmall = material3.labelSmall.copy(fontFamily = customFontFamily)
    )

    val shapes = Shapes(
        extraSmall = RoundedCornerShape(12.dp),
        small = RoundedCornerShape(16.dp),
        medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(28.dp)
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        shapes = shapes,
        content = content
    )
}

private fun headlineLarge(typography: Typography, fontFamily: FontFamily?) = typography.headlineLarge.copy(fontFamily = fontFamily)
private fun headlineMedium(typography: Typography, fontFamily: FontFamily?) = typography.headlineMedium.copy(fontFamily = fontFamily)
private fun headlineSmall(typography: Typography, fontFamily: FontFamily?) = typography.headlineSmall.copy(fontFamily = fontFamily)

const val previewDark = true
