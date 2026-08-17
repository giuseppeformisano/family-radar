package com.example.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition

/**
 * Riproduce un'animazione Lottie da `res/raw`, con degradazione elegante.
 *
 * Il file viene risolto per nome a runtime (`getIdentifier`) e non tramite `R.raw.*`:
 * così il progetto compila anche quando gli asset non ci sono ancora, e l'animazione
 * si accende da sola nel momento in cui il `.json` viene aggiunto alla cartella.
 *
 * Per attivare un'animazione: scarica il `.json` (LottieFiles o altro), rinominalo
 * con il nome passato in [rawResName] e mettilo in `app/src/main/res/raw/`.
 * Attenzione: i nomi delle risorse Android ammettono solo minuscole, cifre e underscore.
 *
 * @param rawResName nome del file senza estensione, es. "radar_scanning". Se null, si va di fallback.
 * @param fallback disegnato quando l'asset manca o non è ancora caricato.
 */
@Composable
fun LottieBox(
    rawResName: String?,
    modifier: Modifier = Modifier,
    iterations: Int = LottieConstants.IterateForever,
    speed: Float = 1f,
    fallback: @Composable BoxScope.() -> Unit
) {
    val context = LocalContext.current
    val isPreview = LocalInspectionMode.current

    val resId = remember(rawResName, isPreview) {
        if (rawResName.isNullOrBlank() || isPreview) {
            0
        } else {
            runCatching {
                context.resources.getIdentifier(rawResName, "raw", context.packageName)
            }.getOrDefault(0)
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (resId == 0) {
            fallback()
            return@Box
        }

        val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(resId))
        if (composition == null) {
            // Prima frame non ancora parsato: teniamo il fallback per evitare uno sfarfallio.
            fallback()
        } else {
            LottieAnimation(
                composition = composition,
                iterations = iterations,
                speed = speed,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
