package com.example.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.location.Location
import android.util.Log
import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.model.PlaceCategory
import com.example.model.PlaceSnapshot
import com.example.model.PlaceSnapshotCluster
import com.example.model.SavedPlace
import com.example.model.Trip
import com.example.model.TripPoint
import com.example.model.UserLocation
import org.osmdroid.views.overlay.Polyline
import com.example.util.ImageUtils
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polygon
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import kotlin.math.min

// In-memory cache for generated marker drawables to ensure 60 FPS layer switching
private val markerDrawableCache = LruCache<String, Drawable>(120)

@Composable
fun OsmMapView(
    locations: List<UserLocation>,
    places: List<SavedPlace>,
    snapshots: List<PlaceSnapshot> = emptyList(),
    currentUserId: String,
    targetFocusPoint: Pair<Double, Double>? = null,
    /**
     * Incrementalo per ri-centrare sullo stesso [targetFocusPoint].
     * Senza questo token la LaunchedEffect non riscatterebbe, perché una Pair con
     * le stesse coordinate è strutturalmente uguale alla precedente: premere due
     * volte "centra su di me" non farebbe nulla la seconda.
     */
    focusToken: Int = 0,
    /**
     * Bersaglio del Follow Mode. Finché non è null la mappa si riposiziona a ogni
     * cambio di coordinate, cioè a ogni nuovo fix GPS del membro inseguito.
     */
    followPoint: Pair<Double, Double>? = null,
    /** Tocco singolo sulla mappa, senza trascinamento. */
    onMapTap: () -> Unit = {},
    /** Trascinamento manuale: chi chiama lo usa per interrompere il Follow Mode. */
    onUserPan: () -> Unit = {},
    trips: List<Trip> = emptyList(),
    activeTripPoints: List<TripPoint> = emptyList(),
    selectedTripId: String? = null,
    onMemberSelected: (UserLocation) -> Unit,
    onPlaceSelected: (SavedPlace) -> Unit,
    onSnapshotClusterSelected: (PlaceSnapshotCluster) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // I lambda vengono catturati dentro il blocco `factory`, che gira una sola
    // volta: senza rememberUpdatedState resterebbero congelati alla prima
    // composizione e il Follow Mode non si spegnerebbe più.
    val currentOnMapTap by rememberUpdatedState(onMapTap)
    val currentOnUserPan by rememberUpdatedState(onUserPan)
    // La mappa segue il tema dell'app: il filtro di inversione sui tile viene
    // applicato o rimosso nel blocco update, non solo alla creazione.
    val isDark = com.example.ui.theme.RadarTheme.palette.isDark
    var mapViewInstance by remember { mutableStateOf<MapView?>(null) }
    var isMapInitialized by remember { mutableStateOf(false) }

    // Layer Visibility States (Instant in-memory filtering)
    var showMembers by remember { mutableStateOf(true) }
    var showSnapshots by remember { mutableStateOf(true) }
    var showPlaces by remember { mutableStateOf(true) }

    // Floating Expandable Layer Menu State
    var isLayerMenuExpanded by remember { mutableStateOf(false) }

    // Pre-allocated overlay cache lists
    val memberOverlays = remember { mutableListOf<Overlay>() }
    val placeOverlays = remember { mutableListOf<Overlay>() }
    val snapshotOverlays = remember { mutableListOf<Overlay>() }
    val tripOverlays = remember { mutableListOf<Overlay>() }

    // Compute snapshot clusters
    val snapshotClusters = remember(snapshots) {
        clusterSnapshots(snapshots)
    }

    // Function to apply active overlays to MapView instantly and call invalidate()
    fun refreshMapOverlays(mapView: MapView) {
        try {
            mapView.overlays.clear()
            mapView.overlays.addAll(tripOverlays)
            if (showPlaces) {
                mapView.overlays.addAll(placeOverlays)
            }
            if (showSnapshots) {
                mapView.overlays.addAll(snapshotOverlays)
            }
            if (showMembers) {
                mapView.overlays.addAll(memberOverlays)
            }
            mapView.invalidate()
        } catch (t: Throwable) {
            Log.w("OsmMapView", "Error refreshing overlays: ${t.message}")
        }
    }

    // Handle focus navigation
    // Follow Mode: la chiave e' la coordinata stessa, quindi l'effetto riparte a
    // ogni nuovo fix del bersaglio. Non si tocca lo zoom, per non combattere con
    // l'utente che sta pizzicando la mappa mentre segue qualcuno.
    LaunchedEffect(followPoint) {
        followPoint?.let { (lat, lon) ->
            try {
                if (lat != 0.0 && lon != 0.0 && !lat.isNaN() && !lon.isNaN()) {
                    mapViewInstance?.controller?.animateTo(GeoPoint(lat, lon))
                }
            } catch (t: Throwable) {
                Log.w("OsmMapView", "Follow mode animateTo fallito: ${t.message}")
            }
        }
    }

    LaunchedEffect(targetFocusPoint, focusToken) {
        targetFocusPoint?.let { (lat, lon) ->
            try {
                if (lat != 0.0 && lon != 0.0 && !lat.isNaN() && !lon.isNaN()) {
                    mapViewInstance?.controller?.animateTo(GeoPoint(lat, lon))
                    mapViewInstance?.controller?.setZoom(17.0)
                }
            } catch (t: Throwable) {
                Log.w("OsmMapView", "Error animating to focus point: ${t.message}")
            }
        }
    }

    // Instant layer switch effect (60 FPS without data re-fetch)
    LaunchedEffect(showMembers, showSnapshots, showPlaces) {
        mapViewInstance?.let { mapView ->
            refreshMapOverlays(mapView)
        }
    }

    LaunchedEffect(trips, activeTripPoints, selectedTripId) {
        mapViewInstance?.let { refreshMapOverlays(it) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                Configuration.getInstance().load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
                MapView(ctx).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
                    setMultiTouchControls(true)
                    isTilesScaledToDpi = true
                    if (isDark) {
                        val darkMatrix = ColorMatrix(floatArrayOf(
                            -0.80f, 0f, 0f, 0f, 210f,
                            0f, -0.80f, 0f, 0f, 215f,
                            0f, 0f, -0.75f, 0f, 225f,
                            0f, 0f, 0f, 1f, 0f
                        ))
                        overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(darkMatrix))
                    }
                    controller.setZoom(15.5)
                    val initialCenter = if (locations.isNotEmpty() && locations[0].latitude != 0.0 && !locations[0].latitude.isNaN()) {
                        GeoPoint(locations[0].latitude, locations[0].longitude)
                    } else {
                        GeoPoint(41.9028, 12.4964)
                    }
                    controller.setCenter(initialCenter)

                    // Distingue il tocco secco dal trascinamento confrontando lo
                    // spostamento del dito con il touch slop di sistema. Serve
                    // perche' i due gesti hanno effetti diversi: il tap richiude il
                    // pannello, il trascinamento spegne il Follow Mode.
                    // Il listener restituisce false: la mappa continua a gestire
                    // normalmente pan e zoom.
                    val touchSlop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
                    var downX = 0f
                    var downY = 0f
                    var dragging = false

                    setOnTouchListener { view, event ->
                        when (event.actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN -> {
                                downX = event.x
                                downY = event.y
                                dragging = false
                            }

                            android.view.MotionEvent.ACTION_MOVE -> {
                                if (!dragging) {
                                    val dx = event.x - downX
                                    val dy = event.y - downY
                                    if (kotlin.math.hypot(dx, dy) > touchSlop) {
                                        dragging = true
                                        currentOnUserPan()
                                    }
                                }
                            }

                            android.view.MotionEvent.ACTION_UP -> {
                                if (!dragging) {
                                    view.performClick()
                                    currentOnMapTap()
                                }
                            }
                        }
                        false
                    }

                    mapViewInstance = this
                }
            },
            update = { mapView ->
                mapViewInstance = mapView

                // 0. Allinea il rendering dei tile al tema corrente (chiaro/scuro).
                mapView.overlayManager.tilesOverlay.setColorFilter(
                    if (isDark) {
                        ColorMatrixColorFilter(
                            ColorMatrix(
                                floatArrayOf(
                                    -0.80f, 0f, 0f, 0f, 210f,
                                    0f, -0.80f, 0f, 0f, 215f,
                                    0f, 0f, -0.75f, 0f, 225f,
                                    0f, 0f, 0f, 1f, 0f
                                )
                            )
                        )
                    } else {
                        null
                    }
                )

                // 1. Rebuild Places Overlays
                placeOverlays.clear()
                places.forEach { place ->
                    try {
                        if (place.latitude != 0.0 && place.longitude != 0.0 &&
                            !place.latitude.isNaN() && !place.longitude.isNaN() &&
                            place.radiusMeters > 0.0
                        ) {
                            val placeCenter = GeoPoint(place.latitude, place.longitude)
                            val circlePoints = Polygon.pointsAsCircle(placeCenter, place.radiusMeters)
                            val circleOverlay = Polygon(mapView).apply {
                                points = circlePoints
                                fillPaint.color = getCategoryFillColor(place.category)
                                outlinePaint.color = getCategoryStrokeColor(place.category)
                                outlinePaint.strokeWidth = 3f
                            }
                            placeOverlays.add(circleOverlay)

                            val placeMarker = Marker(mapView).apply {
                                position = placeCenter
                                title = place.name
                                snippet = "Zona: ${place.category.label} (${place.radiusMeters.toInt()}m)"
                                icon = createPlaceMarkerDrawable(ctx = mapView.context, place = place)
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                setOnMarkerClickListener { _, _ ->
                                    try {
                                        onPlaceSelected(place)
                                    } catch (_: Throwable) {}
                                    true
                                }
                            }
                            placeOverlays.add(placeMarker)
                        }
                    } catch (pe: Throwable) {
                        Log.w("OsmMapView", "Error building place overlay: ${pe.message}")
                    }
                }

                // 2. Rebuild Snapshots Overlays
                snapshotOverlays.clear()
                snapshotClusters.forEach { cluster ->
                    try {
                        if (cluster.centerLatitude != 0.0 && cluster.centerLongitude != 0.0 &&
                            !cluster.centerLatitude.isNaN() && !cluster.centerLongitude.isNaN()
                        ) {
                            val snapPoint = GeoPoint(cluster.centerLatitude, cluster.centerLongitude)
                            val snapMarker = Marker(mapView).apply {
                                position = snapPoint
                                val latest = cluster.latestSnapshot
                                title = if (cluster.count > 1) "${cluster.count} Istantanee" else (latest?.userName ?: "Istantanea")
                                snippet = latest?.caption?.ifBlank { "Tocca per visualizzare la foto" } ?: "Istantanea del gruppo"
                                icon = createSnapshotMarkerDrawable(mapView.context, cluster)
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                setOnMarkerClickListener { _, _ ->
                                    try {
                                        onSnapshotClusterSelected(cluster)
                                    } catch (_: Throwable) {}
                                    true
                                }
                            }
                            snapshotOverlays.add(snapMarker)
                        }
                    } catch (se: Throwable) {
                        Log.w("OsmMapView", "Error building snapshot marker: ${se.message}")
                    }
                }

                // 3. Rebuild Members Overlays
                memberOverlays.clear()
                locations.forEach { userLoc ->
                    try {
                        if (userLoc.latitude != 0.0 && userLoc.longitude != 0.0 &&
                            !userLoc.latitude.isNaN() && !userLoc.longitude.isNaN()
                        ) {
                            val memberPoint = GeoPoint(userLoc.latitude, userLoc.longitude)
                            val isSelf = userLoc.userId == currentUserId
                            val memberMarker = Marker(mapView).apply {
                                position = memberPoint
                                title = if (isSelf) "Tu (${userLoc.userName})" else userLoc.userName
                                val timeStr = formatRelativeTime(userLoc.timestamp)
                                val speedKmH = (userLoc.speed * 3.6f).toInt()
                                snippet = "Batteria: ${userLoc.batteryLevel}% • $timeStr" + if (speedKmH > 2) " • $speedKmH km/h" else ""
                                val displayName = if (!userLoc.nickname.isNullOrBlank()) "${userLoc.userName} (${userLoc.nickname})" else userLoc.userName
                                icon = createMemberMarkerDrawable(
                                    ctx = mapView.context,
                                    name = displayName,
                                    battery = userLoc.batteryLevel,
                                    isSelf = isSelf,
                                    speedKmH = speedKmH,
                                    photoBase64 = userLoc.photoBase64
                                )
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                setOnMarkerClickListener { _, _ ->
                                    try {
                                        onMemberSelected(userLoc)
                                    } catch (_: Throwable) {}
                                    true
                                }
                            }
                            memberOverlays.add(memberMarker)
                        }
                    } catch (me: Throwable) {
                        Log.w("OsmMapView", "Error building member marker: ${me.message}")
                    }
                }

                // 4. Trip overlays
                tripOverlays.clear()
                val tripColors = listOf(
                    AndroidColor.rgb(99, 102, 241),
                    AndroidColor.rgb(16, 185, 129),
                    AndroidColor.rgb(239, 68, 68),
                    AndroidColor.rgb(245, 158, 11),
                    AndroidColor.rgb(59, 130, 246)
                )
                trips.forEachIndexed { idx, trip ->
                    if (trip.points.size < 2) return@forEachIndexed
                    val color = tripColors[idx % tripColors.size]
                    val polyline = Polyline(mapView).apply {
                        setPoints(trip.points.map { GeoPoint(it.latitude, it.longitude) })
                        outlinePaint.color = if (trip.id == selectedTripId) color
                            else AndroidColor.argb(160, AndroidColor.red(color), AndroidColor.green(color), AndroidColor.blue(color))
                        outlinePaint.strokeWidth = if (trip.id == selectedTripId) 8f else 5f
                        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                        outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                    }
                    tripOverlays.add(polyline)
                }
                if (activeTripPoints.size >= 2) {
                    val activePoly = Polyline(mapView).apply {
                        setPoints(activeTripPoints.map { GeoPoint(it.latitude, it.longitude) })
                        outlinePaint.color = AndroidColor.rgb(239, 68, 68)
                        outlinePaint.strokeWidth = 7f
                        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                        outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                        outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 10f), 0f)
                    }
                    tripOverlays.add(activePoly)
                }

                // Apply active overlays
                refreshMapOverlays(mapView)

                // Initial animation to user
                if (!isMapInitialized && locations.isNotEmpty()) {
                    val myLoc = locations.find { it.userId == currentUserId } ?: locations.first()
                    if (myLoc.latitude != 0.0 && !myLoc.latitude.isNaN()) {
                        mapView.controller.animateTo(GeoPoint(myLoc.latitude, myLoc.longitude))
                        isMapInitialized = true
                    }
                }
            }
        )

        // Right side Floating Action Buttons & Expandable Layer Switcher
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Espansione ORIZZONTALE senza alcun clip.
            //
            // Ogni modificatore che clippa (AnimatedVisibility con expand/shrink,
            // animateContentSize, clipToBounds) crea un hardware layer che sopra
            // l'AndroidView della mappa si compone male, lasciando il rettangolo
            // trasparente. Qui: nessun clip, larghezza della Row SEMPRE costante,
            // e quando il menu e' chiuso i pulsanti sono rimpiazzati da uno Spacer
            // inerte -- che non intercetta i tocchi, quindi la mappa resta usabile.
            val subAlpha by animateFloatAsState(
                targetValue = if (isLayerMenuExpanded) 1f else 0f,
                animationSpec = tween(200),
                label = "layerSubAlpha"
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (subAlpha > 0.01f) {
                    IconButton(
                        onClick = { showMembers = !showMembers },
                        modifier = Modifier
                            .size(40.dp)
                            .graphicsLayer { alpha = subAlpha }
                            .clip(CircleShape)
                            .background(if (showMembers) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), CircleShape)
                            .testTag("layer_toggle_members")
                    ) {
                        Icon(Icons.Default.People, contentDescription = "Mostra Membri", tint = if (showMembers) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                    IconButton(
                        onClick = { showSnapshots = !showSnapshots },
                        modifier = Modifier
                            .size(40.dp)
                            .graphicsLayer { alpha = subAlpha }
                            .clip(CircleShape)
                            .background(if (showSnapshots) Color(0xFFFFEDD5) else MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), CircleShape)
                            .testTag("layer_toggle_snapshots")
                    ) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = "Mostra Istantanee", tint = if (showSnapshots) Color(0xFFEA580C) else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                    IconButton(
                        onClick = { showPlaces = !showPlaces },
                        modifier = Modifier
                            .size(40.dp)
                            .graphicsLayer { alpha = subAlpha }
                            .clip(CircleShape)
                            .background(if (showPlaces) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), CircleShape)
                            .testTag("layer_toggle_places")
                    ) {
                        Icon(Icons.Default.Place, contentDescription = "Mostra Luoghi", tint = if (showPlaces) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                } else {
                    // 3 * 40dp + 2 * 8dp di gap: stessa larghezza dei pulsanti,
                    // cosi' il FAB non si sposta mai. Lo Spacer lascia passare i tocchi.
                    Spacer(Modifier.size(width = 136.dp, height = 40.dp))
                }

                // Layer toggle FAB
                FloatingActionButton(
                    onClick = { isLayerMenuExpanded = !isLayerMenuExpanded },
                    modifier = Modifier
                        .size(48.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), CircleShape)
                        .testTag("expandable_layer_button"),
                    containerColor = if (isLayerMenuExpanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                    contentColor = if (isLayerMenuExpanded) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                    shape = CircleShape
                ) {
                    Icon(Icons.Default.Layers, contentDescription = "Gestione Layer Mappa", modifier = Modifier.size(24.dp))
                }
            }

            // "Centra su di me" vive nella rail di MainRadarScreen: la' oltre a
            // centrare imposta anche il bersaglio del Follow Mode, cosa che qui
            // non sarebbe possibile. Averlo in due posti creava due pulsanti
            // identici con comportamenti diversi.

            // Group Extent View
            FloatingActionButton(
                onClick = {
                    val validLocs = locations.filter { it.latitude != 0.0 && it.longitude != 0.0 && !it.latitude.isNaN() && !it.longitude.isNaN() }
                    if (validLocs.isNotEmpty() && mapViewInstance != null) {
                        if (validLocs.size == 1) {
                            mapViewInstance?.controller?.animateTo(GeoPoint(validLocs[0].latitude, validLocs[0].longitude))
                            mapViewInstance?.controller?.setZoom(16.0)
                        } else {
                            var minLat = validLocs[0].latitude
                            var maxLat = validLocs[0].latitude
                            var minLon = validLocs[0].longitude
                            var maxLon = validLocs[0].longitude
                            validLocs.forEach {
                                minLat = min(minLat, it.latitude)
                                maxLat = max(maxLat, it.latitude)
                                minLon = min(minLon, it.longitude)
                                maxLon = max(maxLon, it.longitude)
                            }
                            val margin = 0.005
                            val boundingBox = BoundingBox(
                                maxLat + margin,
                                maxLon + margin,
                                minLat - margin,
                                minLon - margin
                            )
                            mapViewInstance?.zoomToBoundingBox(boundingBox, true)
                        }
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), CircleShape)
                    .testTag("group_view_button"),
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
                shape = CircleShape
            ) {
                Icon(Icons.Default.Group, contentDescription = "Vista Gruppo")
            }

            // Zoom In
            SmallFloatingActionButton(
                onClick = { mapViewInstance?.controller?.zoomIn() },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                    .testTag("zoom_in_button")
            ) {
                Icon(Icons.Default.Add, contentDescription = "Zoom In")
            }

            // Zoom Out
            SmallFloatingActionButton(
                onClick = { mapViewInstance?.controller?.zoomOut() },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                    .testTag("zoom_out_button")
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Zoom Out")
            }
        }
    }
}

/**
 * Clusters snapshots within 30 meters of each other
 */
fun clusterSnapshots(snapshots: List<PlaceSnapshot>, thresholdMeters: Double = 30.0): List<PlaceSnapshotCluster> {
    val clusters = mutableListOf<MutableList<PlaceSnapshot>>()
    for (snap in snapshots) {
        if (snap.latitude == 0.0 && snap.longitude == 0.0) continue
        var placed = false
        for (cluster in clusters) {
            val rep = cluster.first()
            val dist = FloatArray(1)
            Location.distanceBetween(
                snap.latitude, snap.longitude,
                rep.latitude, rep.longitude,
                dist
            )
            if (dist[0] <= thresholdMeters) {
                cluster.add(snap)
                placed = true
                break
            }
        }
        if (!placed) {
            clusters.add(mutableListOf(snap))
        }
    }
    return clusters.mapIndexed { index, list ->
        val avgLat = list.map { it.latitude }.average()
        val avgLon = list.map { it.longitude }.average()
        PlaceSnapshotCluster(
            id = "cluster_$index",
            centerLatitude = avgLat,
            centerLongitude = avgLon,
            snapshots = list.sortedByDescending { it.timestamp }
        )
    }
}

private fun formatRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 30_000 -> "Adesso"
        diff < 60_000 -> "${diff / 1000}s fa"
        diff < 3600_000 -> "${diff / 60_000}m fa"
        else -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}

// ---------------- Marker Drawing Utilities with Memory Cache ----------------

private fun createMemberMarkerDrawable(
    ctx: Context,
    name: String,
    battery: Int,
    isSelf: Boolean,
    speedKmH: Int,
    photoBase64: String?
): Drawable {
    val cacheKey = "member_${name}_${battery}_${isSelf}_${speedKmH}_${photoBase64?.hashCode() ?: 0}"
    val cached = markerDrawableCache.get(cacheKey)
    if (cached != null) return cached

    val density = ctx.resources.displayMetrics.density
    val size = (52 * density).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = size / 2f
    val radius = (18 * density)

    // Outer glow / pulse ring
    val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isSelf) AndroidColor.argb(70, 79, 70, 229) else AndroidColor.argb(70, 16, 185, 129)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, radius + (5 * density), pulsePaint)

    // Outer solid border
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isSelf) AndroidColor.rgb(79, 70, 229) else AndroidColor.rgb(16, 185, 129)
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
    }
    canvas.drawCircle(center, center, radius, borderPaint)

    // Inner avatar or initial
    var userAvatarBitmap = ImageUtils.base64ToBitmap(photoBase64)
    if (userAvatarBitmap != null) {
        val scaledAvatar = Bitmap.createScaledBitmap(
            userAvatarBitmap,
            (radius * 2).toInt(),
            (radius * 2).toInt(),
            true
        )
        val circleCropBmp = getCircularBitmap(scaledAvatar)
        canvas.drawBitmap(circleCropBmp, center - radius, center - radius, null)
    } else {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isSelf) AndroidColor.rgb(99, 102, 241) else AndroidColor.rgb(52, 211, 153)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, radius - (1.5f * density), bgPaint)

        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            textSize = 14 * density
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val initial = name.firstOrNull()?.uppercaseChar()?.toString() ?: "U"
        val textY = center - ((textPaint.descent() + textPaint.ascent()) / 2)
        canvas.drawText(initial, center, textY, textPaint)
    }

    // Battery badge at bottom-right
    val badgeRadius = (7 * density)
    val badgeX = center + (12 * density)
    val badgeY = center + (12 * density)

    val batteryBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = when {
            battery > 50 -> AndroidColor.rgb(34, 197, 94)
            battery > 20 -> AndroidColor.rgb(234, 179, 8)
            else -> AndroidColor.rgb(239, 68, 68)
        }
        style = Paint.Style.FILL
    }
    val batteryBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    canvas.drawCircle(badgeX, badgeY, badgeRadius, batteryBgPaint)
    canvas.drawCircle(badgeX, badgeY, badgeRadius, batteryBorderPaint)

    val batteryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        textSize = 7 * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    val bTextY = badgeY - ((batteryTextPaint.descent() + batteryTextPaint.ascent()) / 2)
    canvas.drawText("${min(battery, 99)}", badgeX, bTextY, batteryTextPaint)

    val resultDrawable = BitmapDrawable(ctx.resources, bitmap)
    markerDrawableCache.put(cacheKey, resultDrawable)
    return resultDrawable
}

private fun createSnapshotMarkerDrawable(
    ctx: Context,
    cluster: PlaceSnapshotCluster
): Drawable {
    val cacheKey = "snapshot_${cluster.id}_${cluster.count}_${cluster.latestSnapshot?.id}"
    val cached = markerDrawableCache.get(cacheKey)
    if (cached != null) return cached

    val density = ctx.resources.displayMetrics.density
    val size = (52 * density).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = size / 2f
    val radius = (18 * density)

    // Outer glow
    val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.argb(80, 234, 88, 12)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(center, center, radius + (4 * density), glowPaint)

    // Border
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(234, 88, 12)
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
    }
    canvas.drawCircle(center, center, radius, borderPaint)

    val latestSnap = cluster.latestSnapshot
    val thumbBmp = ImageUtils.base64ToBitmap(latestSnap?.photoBase64)
    if (thumbBmp != null) {
        val scaled = Bitmap.createScaledBitmap(thumbBmp, (radius * 2).toInt(), (radius * 2).toInt(), true)
        val circular = getCircularBitmap(scaled)
        canvas.drawBitmap(circular, center - radius, center - radius, null)
    } else {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.rgb(249, 115, 22)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, radius - (1.5f * density), bgPaint)

        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            textSize = 14 * density
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val textY = center - ((iconPaint.descent() + iconPaint.ascent()) / 2)
        canvas.drawText("📷", center, textY, iconPaint)
    }

    // Cluster count badge if multiple
    if (cluster.count > 1) {
        val badgeRadius = (8 * density)
        val badgeX = center + (12 * density)
        val badgeY = center - (12 * density)

        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.rgb(220, 38, 38)
            style = Paint.Style.FILL
        }
        val badgeBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }
        canvas.drawCircle(badgeX, badgeY, badgeRadius, badgePaint)
        canvas.drawCircle(badgeX, badgeY, badgeRadius, badgeBorder)

        val countTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            textSize = 8 * density
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val cTextY = badgeY - ((countTextPaint.descent() + countTextPaint.ascent()) / 2)
        canvas.drawText("${cluster.count}", badgeX, cTextY, countTextPaint)
    }

    val resultDrawable = BitmapDrawable(ctx.resources, bitmap)
    markerDrawableCache.put(cacheKey, resultDrawable)
    return resultDrawable
}

private fun createPlaceMarkerDrawable(ctx: Context, place: SavedPlace): Drawable {
    val cacheKey = "place_${place.id}_${place.category.name}"
    val cached = markerDrawableCache.get(cacheKey)
    if (cached != null) return cached

    val density = ctx.resources.displayMetrics.density
    val width = (36 * density).toInt()
    val height = (44 * density).toInt()
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val mainColor = when (place.category) {
        PlaceCategory.HOME -> AndroidColor.rgb(79, 70, 229)
        PlaceCategory.WORK -> AndroidColor.rgb(13, 148, 136)
        PlaceCategory.SCHOOL -> AndroidColor.rgb(217, 119, 6)
        PlaceCategory.GYM -> AndroidColor.rgb(220, 38, 38)
        PlaceCategory.OTHER -> AndroidColor.rgb(100, 116, 139)
    }

    val pinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = mainColor
        style = Paint.Style.FILL
    }
    val pinBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
    }

    val headRadius = (14 * density)
    val headCenterX = width / 2f
    val headCenterY = headRadius + (2 * density)

    canvas.drawCircle(headCenterX, headCenterY, headRadius, pinPaint)
    canvas.drawCircle(headCenterX, headCenterY, headRadius, pinBorder)

    // Inner icon symbol
    val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        textSize = 12 * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    val symbol = when (place.category) {
        PlaceCategory.HOME -> "🏠"
        PlaceCategory.WORK -> "💼"
        PlaceCategory.SCHOOL -> "🏫"
        PlaceCategory.GYM -> "🏋"
        PlaceCategory.OTHER -> "📍"
    }
    val symY = headCenterY - ((symbolPaint.descent() + symbolPaint.ascent()) / 2)
    canvas.drawText(symbol, headCenterX, symY, symbolPaint)

    val resultDrawable = BitmapDrawable(ctx.resources, bitmap)
    markerDrawableCache.put(cacheKey, resultDrawable)
    return resultDrawable
}

private fun getCircularBitmap(bitmap: Bitmap): Bitmap {
    val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val rect = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
    canvas.drawRoundRect(rect, bitmap.width / 2f, bitmap.height / 2f, paint)
    paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
    canvas.drawBitmap(bitmap, 0f, 0f, paint)
    return output
}

private fun getCategoryFillColor(category: PlaceCategory): Int {
    return when (category) {
        PlaceCategory.HOME -> AndroidColor.argb(45, 79, 70, 229)
        PlaceCategory.WORK -> AndroidColor.argb(45, 13, 148, 136)
        PlaceCategory.SCHOOL -> AndroidColor.argb(45, 217, 119, 6)
        PlaceCategory.GYM -> AndroidColor.argb(45, 220, 38, 38)
        PlaceCategory.OTHER -> AndroidColor.argb(45, 100, 116, 139)
    }
}

private fun getCategoryStrokeColor(category: PlaceCategory): Int {
    return when (category) {
        PlaceCategory.HOME -> AndroidColor.rgb(79, 70, 229)
        PlaceCategory.WORK -> AndroidColor.rgb(13, 148, 136)
        PlaceCategory.SCHOOL -> AndroidColor.rgb(217, 119, 6)
        PlaceCategory.GYM -> AndroidColor.rgb(220, 38, 38)
        PlaceCategory.OTHER -> AndroidColor.rgb(100, 116, 139)
    }
}