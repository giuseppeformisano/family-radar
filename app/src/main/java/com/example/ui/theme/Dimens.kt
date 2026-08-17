package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Scala di spaziature dell'app. È l'equivalente della spacing scale di Tailwind:
 * un insieme chiuso di valori che si moltiplicano per 4dp.
 *
 * Regola: nelle schermate non scrivere mai `padding(13.dp)` a mano — pesca da qui.
 * Se un valore non c'è, o serve un token nuovo o il layout va ripensato.
 */
object Spacing {
    /** 2dp — separazione fra due righe di testo strettamente legate */
    val xxs: Dp = 2.dp

    /** 4dp — gap dentro un chip o fra icona e label */
    val xs: Dp = 4.dp

    /** 8dp — gap standard fra elementi di una stessa riga */
    val sm: Dp = 8.dp

    /** 12dp — padding interno di componenti compatti */
    val md: Dp = 12.dp

    /** 16dp — padding di schermata e di card. Il default */
    val lg: Dp = 16.dp

    /** 20dp — respiro fra blocchi correlati */
    val xl: Dp = 20.dp

    /** 24dp — separazione fra sezioni */
    val xxl: Dp = 24.dp

    /** 32dp — stacco fra aree logiche della schermata */
    val xxxl: Dp = 32.dp
}

/** Raggi di curvatura. Le forme "vere" stanno in [RadarShapes], questi servono per i casi puntuali. */
object Radius {
    val xs: Dp = 8.dp
    val sm: Dp = 12.dp
    val md: Dp = 16.dp
    val lg: Dp = 20.dp
    val xl: Dp = 28.dp
    val pill: Dp = 999.dp
}

/** Elevazioni. Il tema dark si affida più al contrasto delle superfici che alle ombre. */
object Elevation {
    val none: Dp = 0.dp
    val card: Dp = 1.dp
    val raised: Dp = 3.dp
    val floating: Dp = 8.dp
    val overlay: Dp = 12.dp
}

/** Dimensioni ricorrenti di componenti, così restano identiche ovunque. */
object Sizes {
    val avatarSm: Dp = 32.dp
    val avatarMd: Dp = 44.dp
    val avatarLg: Dp = 56.dp
    val avatarXl: Dp = 72.dp

    val iconSm: Dp = 16.dp
    val iconMd: Dp = 20.dp
    val iconLg: Dp = 24.dp
    val iconXl: Dp = 32.dp

    val touchTarget: Dp = 48.dp
    val fab: Dp = 48.dp

    /**
     * Altezza a riposo del bottom sheet: quanto basta per maniglia + barra dei tab,
     * niente contenuto. Serve a lasciare la mappa il più libera possibile.
     * Se cambi il padding di PanelSelector o SheetHandle, ricalibra questo valore.
     */
    val sheetPeek: Dp = 92.dp

    /** Altezza della barra di navigazione flottante */
    val navBar: Dp = 64.dp
}

val RadarShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.xs),
    small = RoundedCornerShape(Radius.sm),
    medium = RoundedCornerShape(Radius.md),
    large = RoundedCornerShape(Radius.lg),
    extraLarge = RoundedCornerShape(Radius.xl)
)
