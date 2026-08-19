package com.example.ui.components

import android.content.Context
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.location.Address
import android.location.Geocoder
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.model.PlaceCategory
import com.example.model.SavedPlace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polygon
import java.util.*
import kotlin.math.roundToInt

@Composable
fun AddPlaceDialog(
    initialLat: Double,
    initialLon: Double,
    onDismiss: () -> Unit,
    onPlaceAdded: (SavedPlace) -> Unit,
    /**
     * Se valorizzato il dialog lavora in modifica: i campi partono precompilati e
     * il luogo restituito conserva id, autore e data di creazione originali.
     * Se null si crea un luogo nuovo, come prima.
     */
    existingPlace: SavedPlace? = null
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val isDark = isSystemInDarkTheme()
    val isEditing = existingPlace != null

    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var searchFeedback by remember { mutableStateOf<String?>(null) }
    var searchSuccess by remember { mutableStateOf(false) }

    var placeName by remember { mutableStateOf(existingPlace?.name ?: "") }
    var selectedCategory by remember { mutableStateOf(existingPlace?.category ?: PlaceCategory.HOME) }
    var radiusMeters by remember { mutableStateOf(existingPlace?.radiusMeters?.toFloat() ?: 100f) }
    var geofenceEnabled by remember { mutableStateOf(existingPlace?.geofenceEnabled ?: true) }

    // In modifica il pin parte dalle coordinate del luogo, non da quelle passate
    // dal chiamante (che sono la posizione corrente dell'utente).
    val startLat = existingPlace?.latitude?.takeIf { it != 0.0 }
        ?: if (initialLat != 0.0) initialLat else 41.9028
    val startLon = existingPlace?.longitude?.takeIf { it != 0.0 }
        ?: if (initialLon != 0.0) initialLon else 12.4964
    var currentPinLat by remember { mutableStateOf(startLat) }
    var currentPinLon by remember { mutableStateOf(startLon) }
    var resolvedAddress by remember { mutableStateOf(context.getString(R.string.map_position_placeholder)) }

    var mapViewInstance by remember { mutableStateOf<MapView?>(null) }

    // Debounced reverse geocoding as user scrolls the map
    LaunchedEffect(currentPinLat, currentPinLon) {
        delay(500)
        try {
            withContext(Dispatchers.IO) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val geocoder = Geocoder(context, Locale.ITALY)
                    geocoder.getFromLocation(currentPinLat, currentPinLon, 1) { addresses ->
                        if (addresses.isNotEmpty()) {
                            val addr = addresses[0]
                            val thoroughfare = addr.thoroughfare ?: addr.featureName ?: ""
                            val locality = addr.locality ?: addr.subAdminArea ?: ""
                            val text = listOf(thoroughfare, locality).filter { it.isNotBlank() }.joinToString(", ")
                            if (text.isNotBlank()) {
                                resolvedAddress = text
                                if (placeName.isBlank()) {
                                    placeName = thoroughfare.ifBlank { locality }
                                }
                            }
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val geocoder = Geocoder(context, Locale.ITALY)
                    val addresses = geocoder.getFromLocation(currentPinLat, currentPinLon, 1)
                    if (!addresses.isNullOrEmpty()) {
                        val addr = addresses[0]
                        val thoroughfare = addr.thoroughfare ?: addr.featureName ?: ""
                        val locality = addr.locality ?: addr.subAdminArea ?: ""
                        val text = listOf(thoroughfare, locality).filter { it.isNotBlank() }.joinToString(", ")
                        if (text.isNotBlank()) {
                            withContext(Dispatchers.Main) {
                                resolvedAddress = text
                                if (placeName.isBlank()) {
                                    placeName = thoroughfare.ifBlank { locality }
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    // Function to search address and pan map
    val executeSearch: () -> Unit = {
        if (searchQuery.isNotBlank()) {
            focusManager.clearFocus()
            isSearching = true
            searchFeedback = null
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val geocoder = Geocoder(context, Locale.ITALY)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        geocoder.getFromLocationName(searchQuery, 1) { results ->
                            coroutineScope.launch(Dispatchers.Main) {
                                isSearching = false
                                if (results.isNotEmpty()) {
                                    val addr = results[0]
                                    val targetPoint = GeoPoint(addr.latitude, addr.longitude)
                                    mapViewInstance?.controller?.animateTo(targetPoint)
                                    mapViewInstance?.controller?.setZoom(17.0)
                                    currentPinLat = addr.latitude
                                    currentPinLon = addr.longitude
                                    searchFeedback = context.getString(R.string.toast_address_found, addr.getAddressLine(0) ?: searchQuery)
                                    searchSuccess = true
                                    if (placeName.isBlank()) {
                                        placeName = addr.featureName ?: addr.thoroughfare ?: searchQuery
                                    }
                                } else {
                                    searchFeedback = context.getString(R.string.toast_address_not_found, searchQuery)
                                    searchSuccess = false
                                }
                            }
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        val results = geocoder.getFromLocationName(searchQuery, 1)
                        withContext(Dispatchers.Main) {
                            isSearching = false
                            if (!results.isNullOrEmpty()) {
                                val addr = results[0]
                                val targetPoint = GeoPoint(addr.latitude, addr.longitude)
                                mapViewInstance?.controller?.animateTo(targetPoint)
                                mapViewInstance?.controller?.setZoom(17.0)
                                currentPinLat = addr.latitude
                                currentPinLon = addr.longitude
                                searchFeedback = "Trovato: ${addr.getAddressLine(0) ?: searchQuery}"
                                if (placeName.isBlank()) {
                                    placeName = addr.featureName ?: addr.thoroughfare ?: searchQuery
                                }
                            } else {
                                searchFeedback = "Nessun indirizzo trovato per '$searchQuery'"
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isSearching = false
                        searchFeedback = context.getString(R.string.toast_search_error, e.localizedMessage)
                        searchSuccess = false
                    }
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // Stesso "chrome" degli altri pannelli (modifica profilo, dettaglio
        // membro): Card con angoli Radius.xl e sfondo surface, senza il bordo
        // che qui lo rendeva diverso da tutti gli altri.
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.AddLocationAlt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column {
                            Text(
                                if (isEditing) stringResource(R.string.dialog_place_edit_title) else stringResource(R.string.dialog_place_new_title),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                if (isEditing) stringResource(R.string.dialog_place_edit_subtitle)
                                else stringResource(R.string.dialog_place_new_subtitle),
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.search_place_hint), fontSize = 14.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.action_search), tint = MaterialTheme.colorScheme.primary)
                    },
                    trailingIcon = {
                        if (isSearching) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = ""; searchFeedback = null; searchSuccess = false }) {
                                Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.action_clear))
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { executeSearch() }),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("address_search_input")
                )

                // Search Feedback Message
                AnimatedVisibility(visible = searchFeedback != null) {
                    Text(
                        text = searchFeedback ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (searchSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Interactive Map View Container with Fixed Center Pin
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            Configuration.getInstance().load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
                            MapView(ctx).apply {
                                setTileSource(TileSourceFactory.MAPNIK)
                                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                                setMultiTouchControls(true)
                                isTilesScaledToDpi = true
                                controller.setZoom(16.5)
                                controller.setCenter(GeoPoint(startLat, startLon))

                                // Dark mode map styling if dark theme
                                if (isDark) {
                                    val darkMatrix = ColorMatrix(floatArrayOf(
                                        -0.80f, 0f, 0f, 0f, 210f,
                                        0f, -0.80f, 0f, 0f, 215f,
                                        0f, 0f, -0.75f, 0f, 225f,
                                        0f, 0f, 0f, 1f, 0f
                                    ))
                                    overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(darkMatrix))
                                }

                                addMapListener(object : MapListener {
                                    override fun onScroll(event: ScrollEvent?): Boolean {
                                        val center = mapCenter
                                        currentPinLat = center.latitude
                                        currentPinLon = center.longitude
                                        return false
                                    }
                                    override fun onZoom(event: ZoomEvent?): Boolean {
                                        val center = mapCenter
                                        currentPinLat = center.latitude
                                        currentPinLon = center.longitude
                                        return false
                                    }
                                })
                                mapViewInstance = this
                            }
                        },
                        update = { mapView ->
                            mapViewInstance = mapView
                            try {
                                if (isDark) {
                                    val darkMatrix = ColorMatrix(floatArrayOf(
                                        -0.80f, 0f, 0f, 0f, 210f,
                                        0f, -0.80f, 0f, 0f, 215f,
                                        0f, 0f, -0.75f, 0f, 225f,
                                        0f, 0f, 0f, 1f, 0f
                                    ))
                                    mapView.overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(darkMatrix))
                                } else {
                                    mapView.overlayManager.tilesOverlay.setColorFilter(null)
                                }
                            } catch (_: Throwable) {}
                        },
                        onRelease = { mapView ->
                            try {
                                mapView.onPause()
                                mapView.onDetach()
                            } catch (_: Throwable) {}
                        }
                    )

                    // Visual Floating Crosshair / Pin Marker centered on map
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(y = (-18).dp),
                        contentAlignment = Alignment.Center
                    ) {
                        // Pin Icon with Glow & Shadow
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = "Punto Selezionato",
                            tint = when (selectedCategory) {
                                PlaceCategory.HOME -> Color(0xFF648AC8)
                                PlaceCategory.WORK -> Color(0xFF6A948D)
                                PlaceCategory.SCHOOL -> Color(0xFFD97706)
                                PlaceCategory.GYM -> Color(0xFFDC2626)
                                PlaceCategory.OTHER -> Color(0xFF64748B)
                            },
                            modifier = Modifier
                                .size(42.dp)
                                .shadow(8.dp, CircleShape)
                        )
                    }

                    // Helper Badge at top of map
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 10.dp),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        tonalElevation = 4.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.TouchApp,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                stringResource(R.string.map_pin_hint),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium)
                            )
                        }
                    }

                    // Bottom Map Address Label & Reset Position Button
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(8.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Default.Place,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = resolvedAddress,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (initialLat != 0.0) {
                                IconButton(
                                    onClick = {
                                        mapViewInstance?.controller?.animateTo(GeoPoint(initialLat, initialLon))
                                        currentPinLat = initialLat
                                        currentPinLon = initialLon
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        Icons.Default.MyLocation,
                                        contentDescription = stringResource(R.string.action_current_location),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Place Name Input
                OutlinedTextField(
                    value = placeName,
                    onValueChange = { placeName = it },
                    label = { Text(stringResource(R.string.place_name_label)) },
                    placeholder = { Text(stringResource(R.string.place_name_placeholder)) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("place_name_input")
                )

                // Category Chips
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.label_category), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
                    // Cinque colonne di uguale larghezza, icona sopra ed etichetta
                    // sotto. Con le FilterChip a icona+testo affiancati la quinta
                    // categoria non ci stava e andava a capo, lasciando una riga
                    // spaiata; impilando i due elementi ogni voce e' abbastanza
                    // stretta da entrare su qualsiasi schermo. Stessa grammatica
                    // della barra di navigazione del foglio.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PlaceCategory.values().forEach { cat ->
                            val isSelected = cat == selectedCategory
                            val icon = when (cat) {
                                PlaceCategory.HOME -> Icons.Default.Home
                                PlaceCategory.WORK -> Icons.Default.Work
                                PlaceCategory.SCHOOL -> Icons.Default.School
                                PlaceCategory.GYM -> Icons.Default.FitnessCenter
                                PlaceCategory.OTHER -> Icons.Default.Place
                            }
                            val container = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            val content = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(container)
                                    .clickable {
                                        selectedCategory = cat
                                        if (placeName.isBlank() || PlaceCategory.values().any { it.label == placeName }) {
                                            placeName = cat.label
                                        }
                                    }
                                    .padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    icon,
                                    contentDescription = cat.label,
                                    tint = content,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = cat.label,
                                    fontSize = 10.sp,
                                    color = content,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // Geofence Radius Slider
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(stringResource(R.string.label_geofence_radius), style = MaterialTheme.typography.labelMedium)
                        Text(
                            "${radiusMeters.roundToInt()} metri",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                    Slider(
                        value = radiusMeters,
                        onValueChange = { radiusMeters = it },
                        valueRange = 50f..500f,
                        steps = 8,
                        enabled = geofenceEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("radius_slider")
                    )
                }

                // Attivazione del geofence
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = if (geofenceEnabled) Icons.Default.NotificationsActive
                        else Icons.Default.NotificationsOff,
                        contentDescription = null,
                        tint = if (geofenceEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.place_alerts_toggle_label),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            if (geofenceEnabled)
                                stringResource(R.string.place_alerts_on_desc)
                            else
                                stringResource(R.string.place_alerts_off_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = geofenceEnabled,
                        onCheckedChange = { geofenceEnabled = it },
                        modifier = Modifier.testTag("geofence_enabled_switch")
                    )
                }

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    Button(
                        onClick = {
                            // In modifica si parte dal luogo esistente con copy(),
                            // così id, createdBy e createdAt restano gli originali.
                            val base = existingPlace ?: SavedPlace()
                            val finalPlace = base.copy(
                                name = placeName.ifBlank { selectedCategory.label },
                                category = selectedCategory,
                                latitude = currentPinLat,
                                longitude = currentPinLon,
                                radiusMeters = radiusMeters.toDouble(),
                                geofenceEnabled = geofenceEnabled
                            )
                            onPlaceAdded(finalPlace)
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1.3f)
                            .testTag("save_place_button")
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isEditing) stringResource(R.string.action_save_changes) else stringResource(R.string.action_save_place))
                    }
                }
            }
        }
    }
}
