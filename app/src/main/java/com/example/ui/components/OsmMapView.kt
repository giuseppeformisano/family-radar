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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.model.ActivityKind
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
    /** Chi si sta inseguendo: il suo marker riceve un anello di richiamo. */
    followedUserId: String? = null,
    trips: List<Trip> = emptyList(),
    activeTripPoints: List<TripPoint> = emptyList(),
    selectedTripId: String? = null,
    /**
     * Incrementalo per inquadrare l'intera traccia del viaggio selezionato.
     * Serve un token come per [focusToken]: riaprendo lo stesso viaggio la
     * lista dei punti e' identica e da sola non farebbe riscattare l'effetto.
     */
    fitSelectedTripToken: Int = 0,
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

    // Inquadra tutta la traccia, non solo il punto di partenza: centrare
    // sull'inizio a zoom fisso mostrava un pezzetto di percorso e lasciava il
    // resto fuori schermo, che e' l'opposto di "mostra sulla mappa".
    // La chiave comprende anche `trips`: quando il token scatta la traccia puo'
    // non essere ancora arrivata nella lista, e con la sola chiave del token
    // l'effetto non riscatterebbe piu'. Il token gia' consumato viene ricordato,
    // cosi' gli aggiornamenti successivi dei viaggi (per esempio il riversamento
    // di una diretta) non riportano la mappa indietro mentre la si sta usando.
    var lastFittedToken by remember { mutableIntStateOf(0) }
    LaunchedEffect(fitSelectedTripToken, trips, selectedTripId) {
        if (fitSelectedTripToken == 0 || fitSelectedTripToken == lastFittedToken) return@LaunchedEffect
        val points = trips.find { it.id == selectedTripId }?.points ?: return@LaunchedEffect
        if (points.size < 2) return@LaunchedEffect
        lastFittedToken = fitSelectedTripToken
        try {
            var minLat = points[0].latitude
            var maxLat = points[0].latitude
            var minLon = points[0].longitude
            var maxLon = points[0].longitude
            points.forEach {
                minLat = min(minLat, it.latitude)
                maxLat = max(maxLat, it.latitude)
                minLon = min(minLon, it.longitude)
                maxLon = max(maxLon, it.longitude)
            }
            // Margine proporzionale all'estensione: su un tragitto di due isolati
            // un margine fisso inquadrerebbe mezza citta', su uno lungo sarebbe
            // invisibile. Il minimo evita che una traccia quasi puntiforme dia
            // un riquadro degenere.
            val marginLat = ((maxLat - minLat) * 0.20).coerceAtLeast(0.0015)
            val marginLon = ((maxLon - minLon) * 0.20).coerceAtLeast(0.0015)
            mapViewInstance?.zoomToBoundingBox(
                BoundingBox(
                    maxLat + marginLat,
                    maxLon + marginLon,
                    minLat - marginLat,
                    minLon - marginLon
                ),
                true
            )
        } catch (t: Throwable) {
            Log.w("OsmMapView", "Inquadratura traccia fallita: ${t.message}")
        }
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
                                    photoBase64 = userLoc.photoBase64,
                                    activityType = userLoc.activityType,
                                    isFollowed = userLoc.userId == followedUserId
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
                // Solo il viaggio scelto e quelli in corso. Disegnarli tutti
                // riempiva la mappa di polilinee sovrapposte che nessuno stava
                // guardando, rendendo illeggibili anche membri e luoghi.
                trips.forEachIndexed { idx, trip ->
                    val isSelected = trip.id == selectedTripId
                    if (!isSelected && !trip.isLive) return@forEachIndexed
                    if (trip.points.size < 2) return@forEachIndexed

                    val color = if (trip.isLive) AndroidColor.rgb(239, 68, 68)
                        else tripColors[idx % tripColors.size]
                    val geo = trip.points.map { GeoPoint(it.latitude, it.longitude) }
                    val polyline = Polyline(mapView).apply {
                        setPoints(geo)
                        outlinePaint.color = color
                        outlinePaint.strokeWidth = if (isSelected) 8f else 6f
                        outlinePaint.strokeCap = android.graphics.Paint.Cap.ROUND
                        outlinePaint.strokeJoin = android.graphics.Paint.Join.ROUND
                        if (trip.isLive) {
                            outlinePaint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 10f), 0f)
                        }
                    }
                    tripOverlays.add(polyline)

                    // Verso di marcia: frecce lungo il percorso piu' partenza e
                    // arrivo. Senza, una traccia e' ambigua — non si capisce da
                    // che capo e' cominciata, e su un anello nemmeno dove finisce.
                    tripOverlays.addAll(buildDirectionArrows(mapView, geo, color))

                    tripOverlays.add(
                        Marker(mapView).apply {
                            position = geo.first()
                            title = "Partenza"
                            icon = createTripEndpointDrawable(mapView.context, isStart = true)
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                            setOnMarkerClickListener { _, _ -> true }
                        }
                    )
                    if (!trip.isLive) {
                        tripOverlays.add(
                            Marker(mapView).apply {
                                position = geo.last()
                                title = "Arrivo"
                                icon = createTripEndpointDrawable(mapView.context, isStart = false)
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                                setOnMarkerClickListener { _, _ -> true }
                            }
                        )
                    }
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
            // Espansione ORIZZONTALE, senza NESSUNA animazione. Volutamente.
            //
            // I pixel della mappa li disegna il view system di Android, non
            // Compose: l'AndroidView non finisce nel graphics layer di Compose.
            // Percio' qualunque modificatore che allochi un RenderNode o un clip
            // sopra quella regione -- graphicsLayer/alpha, fadeIn/fadeOut,
            // AnimatedVisibility con expand/shrink, animateContentSize (che
            // chiama clipToBounds al suo interno) -- ritaglia un rettangolo che
            // la mappa non riempie, e si vede come un rettangolo trasparente che
            // taglia i pulsanti. Tutte queste strade sono gia' state provate e
            // falliscono per lo stesso motivo.
            //
            // Qui non c'e' niente da comporre: i pulsanti compaiono e spariscono
            // e basta. Da chiusi lasciano il posto a uno Spacer della stessa
            // larghezza, cosi' il FAB non si sposta mai e i tocchi passano alla
            // mappa (lo Spacer non intercetta gli eventi).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isLayerMenuExpanded) {
                    IconButton(
                        onClick = { showMembers = !showMembers },
                        modifier = Modifier
                            .size(40.dp)
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
                            val latSpan = maxLat - minLat
                            val lonSpan = maxLon - minLon
                            // Margine proporzionale: 20% del lato maggiore, minimo 0.003°
                            // (~330 m) per non incollare i marker agli angoli anche su
                            // brevi distanze. Il secondo parametro di zoomToBoundingBox
                            // aggiunge 80 px di bordo schermo sopra il bounding box calcolato.
                            val margin = maxOf(0.003, maxOf(latSpan, lonSpan) * 0.20)
                            val boundingBox = BoundingBox(
                                maxLat + margin,
                                maxLon + margin,
                                minLat - margin,
                                minLon - margin
                            )
                            mapViewInstance?.zoomToBoundingBox(boundingBox, true, 80)
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
 * Frecce di direzione lungo la traccia.
 *
 * Sono distribuite a intervalli regolari sui punti, non su ogni segmento: una
 * freccia per ogni coppia di punti sarebbe illeggibile su un percorso lungo e
 * costerebbe centinaia di marker. L'angolo viene disegnato dentro il bitmap
 * invece di usare Marker.rotation, cosi' non si dipende dalla convenzione di
 * rotazione di osmdroid.
 */
private fun buildDirectionArrows(
    mapView: MapView,
    points: List<GeoPoint>,
    color: Int
): List<Overlay> {
    if (points.size < 2) return emptyList()

    val arrows = mutableListOf<Overlay>()
    // Circa una decina di frecce sul percorso, mai piu' fitte di un punto ogni due.
    val step = max(2, points.size / 10)

    var i = step
    while (i < points.size) {
        val from = points[i - 1]
        val to = points[i]
        val result = FloatArray(2)
        Location.distanceBetween(
            from.latitude, from.longitude,
            to.latitude, to.longitude,
            result
        )
        // Segmenti troppo corti danno un rilevamento instabile: si salta.
        if (result[0] >= 5f) {
            val bearing = result[1]
            arrows.add(
                Marker(mapView).apply {
                    position = to
                    icon = createTripArrowDrawable(mapView.context, bearing, color)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    // Le frecce sono decorazione: non devono rubare il tocco
                    // alla mappa ne' aprire fumetti.
                    setOnMarkerClickListener { _, _ -> true }
                }
            )
        }
        i += step
    }
    return arrows
}

/** Triangolo gia' ruotato verso il rilevamento indicato. */
private fun createTripArrowDrawable(ctx: Context, bearingDeg: Float, color: Int): Drawable {
    // Si arrotonda a 5 gradi: la differenza non si vede e la cache resta piccola.
    val rounded = (Math.round(bearingDeg / 5f) * 5)
    val cacheKey = "triparrow_${rounded}_$color"
    markerDrawableCache.get(cacheKey)?.let { return it }

    val density = ctx.resources.displayMetrics.density
    val size = (20 * density).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = size / 2f

    canvas.save()
    canvas.rotate(rounded.toFloat(), center, center)

    val path = android.graphics.Path().apply {
        moveTo(center, center - 6 * density)
        lineTo(center + 4.5f * density, center + 5 * density)
        lineTo(center, center + 2.5f * density)
        lineTo(center - 4.5f * density, center + 5 * density)
        close()
    }

    // Bordo chiaro sotto: sopra una mappa scura o una strada scura il triangolo
    // pieno sparirebbe.
    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = AndroidColor.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeJoin = Paint.Join.ROUND
    })
    canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    })
    canvas.restore()

    val drawable = BitmapDrawable(ctx.resources, bitmap)
    markerDrawableCache.put(cacheKey, drawable)
    return drawable
}

/** Pallino di partenza (verde) o di arrivo (scuro con centro chiaro). */
private fun createTripEndpointDrawable(ctx: Context, isStart: Boolean): Drawable {
    val cacheKey = "tripend_$isStart"
    markerDrawableCache.get(cacheKey)?.let { return it }

    val density = ctx.resources.displayMetrics.density
    val size = (24 * density).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = size / 2f
    val radius = 8 * density

    val fill = if (isStart) AndroidColor.rgb(34, 197, 94) else AndroidColor.rgb(30, 41, 59)

    canvas.drawCircle(center, center, radius + 2 * density, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.WHITE
        style = Paint.Style.FILL
    })
    canvas.drawCircle(center, center, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fill
        style = Paint.Style.FILL
    })
    // L'arrivo ha un anello interno chiaro, cosi' i due capi si distinguono
    // anche da chi non percepisce bene la differenza di colore.
    if (!isStart) {
        canvas.drawCircle(center, center, radius * 0.45f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            style = Paint.Style.FILL
        })
    }

    val drawable = BitmapDrawable(ctx.resources, bitmap)
    markerDrawableCache.put(cacheKey, drawable)
    return drawable
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
    photoBase64: String?,
    activityType: String,
    isFollowed: Boolean = false
): Drawable {
    val cacheKey = "member_${name}_${battery}_${isSelf}_${speedKmH}_${photoBase64?.hashCode() ?: 0}_${activityType}_$isFollowed"
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

    // Anello di richiamo su chi si sta inseguendo. Prima l'unico segnale era
    // un'etichetta piccola nella barra laterale: diceva CHE stai seguendo
    // qualcuno, ma sulla mappa non si capiva CHI fra i marker fosse.
    if (isFollowed) {
        canvas.drawCircle(center, center, radius + (7 * density), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.rgb(99, 102, 241)
            style = Paint.Style.STROKE
            strokeWidth = 3.5f * density
        })
        canvas.drawCircle(center, center, radius + (7 * density), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1f * density
        })
    }

    // Outer solid border
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isSelf) AndroidColor.rgb(79, 70, 229) else AndroidColor.rgb(16, 185, 129)
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
    }
    canvas.drawCircle(center, center, radius, borderPaint)

    // Inner avatar or initial
    val userAvatarBitmap = ImageUtils.base64ToBitmap(photoBase64)
    if (userAvatarBitmap != null) {
        // Il marker e' tondo, quindi la destinazione e' quadrata: scalare
        // direttamente a lato x lato deformava le foto non quadrate (una verticale
        // usciva schiacciata). Si ritaglia prima il quadrato centrale, come fa
        // ContentScale.Crop altrove nell'app, e solo dopo si scala.
        val squared = centerCropSquare(userAvatarBitmap)
        val side = (radius * 2).toInt().coerceAtLeast(1)
        val scaledAvatar = Bitmap.createScaledBitmap(squared, side, side, true)
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

    // Modo di spostarsi, in alto a sinistra: sta all'opposto del badge batteria
    // per non coprirlo, e compare solo quando si e' in movimento — un pallino
    // "fermo" su ogni marker sarebbe rumore, dato che da fermi sono quasi tutti.
    val activityGlyph = activityGlyphFor(activityType)
    if (activityGlyph != null) {
        // Fondo CHIARO e glifo grande. Le emoji sono a colori e ignorano il
        // colore del Paint: su fondo blu scuro, e a 9dp, la bicicletta diventava
        // una macchia illeggibile. Su bianco i colori propri del glifo si
        // staccano, e il bordo scuro tiene il badge distinto dalla mappa.
        val aRadius = (10 * density)
        val aX = center - (12 * density)
        val aY = center - (12 * density)

        val aBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            style = Paint.Style.FILL
        }
        val aBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.rgb(30, 41, 59)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }
        canvas.drawCircle(aX, aY, aRadius, aBgPaint)
        canvas.drawCircle(aX, aY, aRadius, aBorderPaint)

        val aTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 13 * density
            textAlign = Paint.Align.CENTER
        }
        val aTextY = aY - ((aTextPaint.descent() + aTextPaint.ascent()) / 2)
        canvas.drawText(activityGlyph, aX, aTextY, aTextPaint)
    }

    val resultDrawable = BitmapDrawable(ctx.resources, bitmap)
    markerDrawableCache.put(cacheKey, resultDrawable)
    return resultDrawable
}

/**
 * Glifo del modo di spostarsi, o null se non va mostrato niente.
 *
 * Sono caratteri e non vettoriali di Material perche' il marker e' disegnato su
 * Canvas: caricare e ridimensionare un drawable per ogni membro a ogni refresh
 * costerebbe piu' di quanto rende, e questi si leggono anche a 16dp.
 */
private fun activityGlyphFor(activityType: String): String? = when (activityType) {
    ActivityKind.VEHICLE -> "🚗"  // automobile
    ActivityKind.BICYCLE -> "🚲"  // bicicletta
    ActivityKind.RUNNING -> "🏃"  // corsa
    ActivityKind.WALKING -> "🚶"  // camminata
    else -> null
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

/**
 * Ritaglia il quadrato centrale di un'immagine, lasciando fuori le bande in
 * eccesso sul lato lungo. Serve prima di scalare a una destinazione quadrata:
 * senza, l'immagine viene deformata invece che ritagliata.
 */
private fun centerCropSquare(source: Bitmap): Bitmap {
    val side = min(source.width, source.height)
    if (source.width == source.height) return source
    val left = (source.width - side) / 2
    val top = (source.height - side) / 2
    return try {
        Bitmap.createBitmap(source, left, top, side, side)
    } catch (_: Throwable) {
        source
    }
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