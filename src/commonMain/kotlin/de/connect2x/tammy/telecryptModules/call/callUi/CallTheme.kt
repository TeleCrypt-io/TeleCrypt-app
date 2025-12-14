package de.connect2x.messenger.compose.view.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import de.connect2x.messenger.compose.view.common.deriveFromHue
import de.connect2x.messenger.compose.view.common.hue
import de.connect2x.tammy.md_theme_light_background
import de.connect2x.tammy.md_theme_light_error
import de.connect2x.tammy.md_theme_light_errorContainer
import de.connect2x.tammy.md_theme_light_inverseOnSurface
import de.connect2x.tammy.md_theme_light_inversePrimary
import de.connect2x.tammy.md_theme_light_inverseSurface
import de.connect2x.tammy.md_theme_light_onBackground
import de.connect2x.tammy.md_theme_light_onError
import de.connect2x.tammy.md_theme_light_onErrorContainer
import de.connect2x.tammy.md_theme_light_onPrimary
import de.connect2x.tammy.md_theme_light_onPrimaryContainer
import de.connect2x.tammy.md_theme_light_onSecondary
import de.connect2x.tammy.md_theme_light_onSecondaryContainer
import de.connect2x.tammy.md_theme_light_onSurface
import de.connect2x.tammy.md_theme_light_onSurfaceVariant
import de.connect2x.tammy.md_theme_light_onTertiary
import de.connect2x.tammy.md_theme_light_onTertiaryContainer
import de.connect2x.tammy.md_theme_light_outline
import de.connect2x.tammy.md_theme_light_outlineVariant
import de.connect2x.tammy.md_theme_light_primaryContainer
import de.connect2x.tammy.md_theme_light_scrim
import de.connect2x.tammy.md_theme_light_secondary
import de.connect2x.tammy.md_theme_light_secondaryContainer
import de.connect2x.tammy.md_theme_light_surface
import de.connect2x.tammy.md_theme_light_surfaceBright
import de.connect2x.tammy.md_theme_light_surfaceContainer
import de.connect2x.tammy.md_theme_light_surfaceContainerHigh
import de.connect2x.tammy.md_theme_light_surfaceContainerHighest
import de.connect2x.tammy.md_theme_light_surfaceContainerLow
import de.connect2x.tammy.md_theme_light_surfaceContainerLowest
import de.connect2x.tammy.md_theme_light_surfaceDim
import de.connect2x.tammy.md_theme_light_surfaceTint
import de.connect2x.tammy.md_theme_light_surfaceVariant
import de.connect2x.tammy.md_theme_light_tertiary
import de.connect2x.tammy.md_theme_light_tertiaryContainer

class CallTheme : Theme {
    @Composable
    override fun create(
        colorScheme: ColorScheme,
        messengerColors: MessengerColors,
        messengerDpConstants: MessengerDpConstants,
        messengerIcons: MessengerIcons,
        shapes: Shapes,
        typography: Typography,
        density: Density,
        componentStyles: ThemeComponents,
        content: @Composable () -> Unit,
    ) {
        val accentColor = Color(0,0,1,1)
        val accentHue = accentColor.hue
        ThemeImpl().create(
            ColorScheme(
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
            ),
            messengerColors,
            messengerDpConstants,
            messengerIcons,
            shapes,
            typography,
            density,
            componentStyles,
            content
        )
    }
}
