package de.connect2x.tammy

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import de.connect2x.messenger.compose.view.common.deriveFromHue
import de.connect2x.messenger.compose.view.common.hue
import de.connect2x.messenger.compose.view.theme.DefaultAccentColor
import de.connect2x.messenger.compose.view.theme.ThemeDarkColorScheme
import de.connect2x.messenger.compose.view.theme.ThemeLightColorScheme
import org.koin.dsl.module

fun tammyModule() = module {
    single<DefaultAccentColor> {
        object : DefaultAccentColor {
            override val value = accentColor
        }
    }
    single<ThemeLightColorScheme> {
        object : ThemeLightColorScheme {
            override fun create(accentColor: Color): ColorScheme {
                val accentHue = accentColor.hue
                return lightColorScheme(
                    primary = accentColor,
                    onPrimary = md_theme_light_onPrimary,
                    primaryContainer = md_theme_light_primaryContainer.deriveFromHue(accentHue),
                    onPrimaryContainer = md_theme_light_onPrimaryContainer,
                    secondary = md_theme_light_secondary.deriveFromHue(accentHue),
                    onSecondary = md_theme_light_onSecondary,
                    secondaryContainer = md_theme_light_secondaryContainer,
                    onSecondaryContainer = md_theme_light_onSecondaryContainer,
                    tertiary = md_theme_light_tertiary,
                    onTertiary = md_theme_light_onTertiary,
                    tertiaryContainer = md_theme_light_tertiaryContainer,
                    onTertiaryContainer = md_theme_light_onTertiaryContainer,
                    error = md_theme_light_error,
                    errorContainer = md_theme_light_errorContainer,
                    onError = md_theme_light_onError,
                    onErrorContainer = md_theme_light_onErrorContainer,
                    background = md_theme_light_background,
                    onBackground = md_theme_light_onBackground,
                    surface = md_theme_light_surface,
                    onSurface = md_theme_light_onSurface,
                    surfaceVariant = md_theme_light_surfaceVariant.deriveFromHue(accentHue),
                    onSurfaceVariant = md_theme_light_onSurfaceVariant,
                    outline = md_theme_light_outline.deriveFromHue(accentHue),
                    inverseOnSurface = md_theme_light_inverseOnSurface.deriveFromHue(accentHue),
                    inverseSurface = md_theme_light_inverseSurface.deriveFromHue(accentHue),
                    inversePrimary = md_theme_light_inversePrimary.deriveFromHue(accentHue),
                    surfaceTint = md_theme_light_surfaceTint,
                    outlineVariant = md_theme_light_outlineVariant,
                    scrim = md_theme_light_scrim,
                    surfaceDim = md_theme_light_surfaceDim.deriveFromHue(accentHue),
                    surfaceBright = md_theme_light_surfaceBright.deriveFromHue(accentHue),
                    surfaceContainerLowest = md_theme_light_surfaceContainerLowest.deriveFromHue(accentHue),
                    surfaceContainerLow = md_theme_light_surfaceContainerLow.deriveFromHue(accentHue),
                    surfaceContainer = md_theme_light_surfaceContainer.deriveFromHue(accentHue),
                    surfaceContainerHigh = md_theme_light_surfaceContainerHigh.deriveFromHue(accentHue),
                    surfaceContainerHighest = md_theme_light_surfaceContainerHighest.deriveFromHue(accentHue),
                )
            }
        }
    }
    single<ThemeDarkColorScheme> {
        object : ThemeDarkColorScheme {
            override fun create(accentColor: Color): ColorScheme {
                val accentHue = accentColor.hue
                return darkColorScheme(
                    primary = accentColor,
                    onPrimary = md_theme_dark_onPrimary,
                    primaryContainer = md_theme_dark_primaryContainer.deriveFromHue(accentHue),
                    onPrimaryContainer = md_theme_dark_onPrimaryContainer,
                    secondary = md_theme_dark_secondary.deriveFromHue(accentHue),
                    onSecondary = md_theme_dark_onSecondary,
                    secondaryContainer = md_theme_dark_secondaryContainer,
                    onSecondaryContainer = md_theme_dark_onSecondaryContainer,
                    tertiary = md_theme_dark_tertiary,
                    onTertiary = md_theme_dark_onTertiary,
                    tertiaryContainer = md_theme_dark_tertiaryContainer,
                    onTertiaryContainer = md_theme_dark_onTertiaryContainer,
                    error = md_theme_dark_error,
                    errorContainer = md_theme_dark_errorContainer,
                    onError = md_theme_dark_onError,
                    onErrorContainer = md_theme_dark_onErrorContainer,
                    background = md_theme_dark_background,
                    onBackground = md_theme_dark_onBackground,
                    surface = md_theme_dark_surface,
                    onSurface = md_theme_dark_onSurface,
                    surfaceVariant = md_theme_dark_surfaceVariant.deriveFromHue(accentHue),
                    onSurfaceVariant = md_theme_dark_onSurfaceVariant,
                    outline = md_theme_dark_outline.deriveFromHue(accentHue),
                    inverseOnSurface = md_theme_dark_inverseOnSurface,
                    inverseSurface = md_theme_dark_inverseSurface.deriveFromHue(accentHue),
                    inversePrimary = md_theme_dark_inversePrimary.deriveFromHue(accentHue),
                    surfaceTint = md_theme_dark_surfaceTint.deriveFromHue(accentHue),
                    outlineVariant = md_theme_dark_outlineVariant,
                    scrim = md_theme_dark_scrim,
                    surfaceDim = md_theme_dark_surfaceDim.deriveFromHue(accentHue),
                    surfaceBright = md_theme_dark_surfaceBright.deriveFromHue(accentHue),
                    surfaceContainerLowest = md_theme_dark_surfaceContainerLowest.deriveFromHue(accentHue),
                    surfaceContainerLow = md_theme_dark_surfaceContainerLow.deriveFromHue(accentHue),
                    surfaceContainer = md_theme_dark_surfaceContainer.deriveFromHue(accentHue),
                    surfaceContainerHigh = md_theme_dark_surfaceContainerHigh.deriveFromHue(accentHue),
                    surfaceContainerHighest = md_theme_dark_surfaceContainerHighest.deriveFromHue(accentHue),
                )
            }
        }
    }
}