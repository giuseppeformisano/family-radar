package com.example.ui.components

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.model.UserLocation

/**
 * Nuova mappa dell'app basata su MapLibre + OpenFreeMap (vettoriale, mondiale).
 * In migrazione: per ora disegna i pallini dei membri e la scia (agganciata alle
 * strade via [com.example.util.RoadMatcher]). Snapshot, luoghi, "segui", nomi/foto
 * arriveranno negli step successivi. La vecchia mappa (osmdroid) resta disponibile.
 */
@Composable
fun MapLibreMapView(
    locations: List<UserLocation>,
    currentUserId: String,
    dark: Boolean,
    places: List<com.example.model.SavedPlace> = emptyList(),
    snapshots: List<com.example.model.PlaceSnapshot> = emptyList(),
    targetFocusPoint: Pair<Double, Double>? = null,
    focusToken: Int = 0,
    followedUserId: String? = null,
    speakingUserId: String? = null,
    onMemberSelected: (UserLocation) -> Unit = {},
    onPlaceSelected: (com.example.model.SavedPlace) -> Unit = {},
    onSnapshotClusterSelected: (com.example.model.PlaceSnapshotCluster) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Cluster snapshot (stessa logica della vecchia mappa).
    val snapshotClusters = androidx.compose.runtime.remember(snapshots) { clusterSnapshots(snapshots) }
    // Il click listener viene registrato una volta sola: legge i dati correnti tramite
    // questi stati aggiornati invece di catturarli al momento.
    val currentLocations by androidx.compose.runtime.rememberUpdatedState(locations)
    val currentPlaces by androidx.compose.runtime.rememberUpdatedState(places)
    val currentClusters by androidx.compose.runtime.rememberUpdatedState(snapshotClusters)
    val currentFollowed by androidx.compose.runtime.rememberUpdatedState(followedUserId)
    val onSelected by androidx.compose.runtime.rememberUpdatedState(onMemberSelected)
    val onPlaceSel by androidx.compose.runtime.rememberUpdatedState(onPlaceSelected)
    val onClusterSel by androidx.compose.runtime.rememberUpdatedState(onSnapshotClusterSelected)

    val currentSpeaking by androidx.compose.runtime.rememberUpdatedState(speakingUserId)
    var speakingOffset by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }

    var showMembers by remember { mutableStateOf(true) }
    var showSnapshots by remember { mutableStateOf(true) }
    var showPlaces by remember { mutableStateOf(true) }
    var layerMenuOpen by remember { mutableStateOf(false) }

    var mapRef by remember { mutableStateOf<org.maplibre.android.maps.MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var centeredOnce by remember { mutableStateOf(false) }

    val styleUrl = if (dark) "https://tiles.openfreemap.org/styles/dark"
    else "https://tiles.openfreemap.org/styles/bright"

    val mapView = remember {
        org.maplibre.android.MapLibre.getInstance(context)
        // textureMode(true): senza, la mappa usa una SurfaceView che disegna in un
        // layer separato SOPRA i controlli Compose, nascondendo i pulsanti sovrapposti.
        // In modalita' texture si compone normalmente e i pulsanti restano visibili.
        val options = org.maplibre.android.maps.MapLibreMapOptions().textureMode(true)
        org.maplibre.android.maps.MapView(context, options).apply {
            onCreate(null)
            getMapAsync { map ->
                mapRef = map
                map.setStyle(styleUrl) { style ->
                    // Sorgenti vuote + layer: verranno riempite in tempo reale.
                    style.addSource(org.maplibre.android.style.sources.GeoJsonSource("trail-src"))
                    style.addLayer(
                        org.maplibre.android.style.layers.LineLayer("trail-layer", "trail-src")
                            .withProperties(
                                org.maplibre.android.style.layers.PropertyFactory.lineColor(android.graphics.Color.rgb(79, 70, 229)),
                                org.maplibre.android.style.layers.PropertyFactory.lineWidth(4f),
                                org.maplibre.android.style.layers.PropertyFactory.lineOpacity(0.7f),
                                org.maplibre.android.style.layers.PropertyFactory.lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
                                org.maplibre.android.style.layers.PropertyFactory.lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND)
                            )
                    )
                    // Anello del raggio dei luoghi (geofence), sotto tutto.
                    style.addSource(org.maplibre.android.style.sources.GeoJsonSource("place-radius-src"))
                    style.addLayer(
                        org.maplibre.android.style.layers.LineLayer("place-radius-layer", "place-radius-src")
                            .withProperties(
                                org.maplibre.android.style.layers.PropertyFactory.lineColor(android.graphics.Color.argb(140, 99, 102, 241)),
                                org.maplibre.android.style.layers.PropertyFactory.lineWidth(2f)
                            )
                    )

                    // Luoghi.
                    style.addSource(org.maplibre.android.style.sources.GeoJsonSource("places-src"))
                    style.addLayer(
                        org.maplibre.android.style.layers.SymbolLayer("places-layer", "places-src")
                            .withProperties(
                                org.maplibre.android.style.layers.PropertyFactory.iconImage(
                                    org.maplibre.android.style.expressions.Expression.get("icon-id")
                                ),
                                org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap(true),
                                org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement(true)
                            )
                    )

                    // Snapshot (cluster).
                    style.addSource(org.maplibre.android.style.sources.GeoJsonSource("snapshots-src"))
                    style.addLayer(
                        org.maplibre.android.style.layers.SymbolLayer("snapshots-layer", "snapshots-src")
                            .withProperties(
                                org.maplibre.android.style.layers.PropertyFactory.iconImage(
                                    org.maplibre.android.style.expressions.Expression.get("icon-id")
                                ),
                                org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap(true),
                                org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement(true)
                            )
                    )

                    style.addSource(org.maplibre.android.style.sources.GeoJsonSource("members-src"))
                    // SymbolLayer: ogni membro usa la propria icona (nome + foto + stato),
                    // generata come immagine e registrata nello style con id "icon-id".
                    style.addLayer(
                        org.maplibre.android.style.layers.SymbolLayer("members-layer", "members-src")
                            .withProperties(
                                org.maplibre.android.style.layers.PropertyFactory.iconImage(
                                    org.maplibre.android.style.expressions.Expression.get("icon-id")
                                ),
                                org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap(true),
                                org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement(true)
                            )
                    )
                    styleReady = true
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        mapView.onStart()
        mapView.onResume()
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    // Bersaglio (ultima posizione reale) e posizione mostrata per pallino: il ticker
    // fa scivolare il mostrato verso il bersaglio per un movimento morbido.
    val memberTargets = androidx.compose.runtime.remember { mutableMapOf<String, Triple<Double, Double, String>>() }
    val memberDisplayed = androidx.compose.runtime.remember { mutableMapOf<String, Pair<Double, Double>>() }

    // Aggiornamento in tempo reale di scia + bersagli pallini, + centraggio iniziale.
    LaunchedEffect(locations, styleReady) {
        if (!styleReady) return@LaunchedEffect
        val map = mapRef ?: return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect

        val valid = locations.filter { it.latitude != 0.0 || it.longitude != 0.0 }

        valid.forEach { m ->
            val isSelf = m.userId == currentUserId
            val displayName = if (!m.nickname.isNullOrBlank()) "${m.userName} (${m.nickname})" else m.userName
            val speedKmH = (m.speed * 3.6f).toInt()
            val iconId = "member-${m.userId}"
            runCatching {
                val drawable = createMemberMarkerDrawable(
                    ctx = context,
                    name = displayName,
                    battery = m.batteryLevel,
                    isSelf = isSelf,
                    speedKmH = speedKmH,
                    photoBase64 = m.photoBase64,
                    activityType = m.activityType,
                    isFollowed = m.userId == currentFollowed
                )
                val bmp = (drawable as android.graphics.drawable.BitmapDrawable).bitmap
                style.addImage(iconId, bmp)
            }
            memberTargets[m.userId] = Triple(m.latitude, m.longitude, iconId)
        }
        val ids = valid.map { it.userId }.toSet()
        memberTargets.keys.retainAll(ids)
        memberDisplayed.keys.retainAll(ids)

        val rawLineFeatures = valid.filter { it.recentPoints.size >= 2 }.map { m ->
            val pts = m.recentPoints.sortedBy { it.t }
                .map { org.maplibre.geojson.Point.fromLngLat(it.lon, it.lat) }
            org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.LineString.fromLngLats(pts))
        }
        style.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>("trail-src")
            ?.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(rawLineFeatures))

        if (!centeredOnce) {
            val me = valid.find { it.userId == currentUserId } ?: valid.firstOrNull()
            if (me != null) {
                map.cameraPosition = org.maplibre.android.camera.CameraPosition.Builder()
                    .target(org.maplibre.android.geometry.LatLng(me.latitude, me.longitude))
                    .zoom(15.0)
                    .build()
                centeredOnce = true
            }
        }

        // Inseguimento: se stai seguendo un membro, la camera lo tiene al centro.
        if (followedUserId != null) {
            val target = valid.find { it.userId == followedUserId }
            if (target != null) {
                map.animateCamera(
                    org.maplibre.android.camera.CameraUpdateFactory.newLatLng(
                        org.maplibre.android.geometry.LatLng(target.latitude, target.longitude)
                    )
                )
            }
        }

        // Scia agganciata alle strade (in background): sostituisce la scia grezza dove
        // il matching e' affidabile, altrimenti resta il grezzo (regola fuori-strada).
        val withTrails = valid.filter { it.recentPoints.size >= 2 }
        if (withTrails.isNotEmpty()) {
            val snappedFeatures = withTrails.map { m ->
                val raw = m.recentPoints.sortedBy { it.t }.map { it.lat to it.lon }
                val snapped = com.example.util.RoadMatcher.matchToRoads(raw) ?: raw
                val pts = snapped.map { org.maplibre.geojson.Point.fromLngLat(it.second, it.first) }
                org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.LineString.fromLngLats(pts))
            }
            map.style?.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>("trail-src")
                ?.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(snappedFeatures))
        }
    }

    // Ticker: ogni 100ms fa scivolare i pallini dalla posizione mostrata verso il
    // bersaglio (ultima posizione reale), per un movimento morbido tra un fix e l'altro.
    LaunchedEffect(styleReady) {
        if (!styleReady) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(100L)
            val style = mapRef?.style ?: continue
            if (memberTargets.isEmpty()) continue
            val features = memberTargets.map { (userId, target) ->
                val (tLat, tLon, iconId) = target
                val shown = memberDisplayed[userId]
                val (lat, lon) = if (shown == null) {
                    tLat to tLon
                } else {
                    val d = distanceMeters(shown.first, shown.second, tLat, tLon)
                    if (d < 3.0 || d > 120.0) tLat to tLon
                    else (shown.first + (tLat - shown.first) * 0.25) to (shown.second + (tLon - shown.second) * 0.25)
                }
                memberDisplayed[userId] = lat to lon
                org.maplibre.geojson.Feature.fromGeometry(
                    org.maplibre.geojson.Point.fromLngLat(lon, lat)
                ).apply {
                    addStringProperty("icon-id", iconId)
                    addStringProperty("userId", userId)
                }
            }
            style.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>("members-src")
                ?.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(features))

            // Posizione sullo schermo di chi sta mandando un vocale, per l'anello pulsante.
            val sp = currentSpeaking
            speakingOffset = if (sp != null) {
                memberDisplayed[sp]?.let { (lat, lon) ->
                    val p = mapRef?.projection?.toScreenLocation(org.maplibre.android.geometry.LatLng(lat, lon))
                    if (p != null) androidx.compose.ui.geometry.Offset(p.x, p.y) else null
                }
            } else null
        }
    }

    // Luoghi + snapshot: icone e anelli del raggio, ricostruiti quando cambiano.
    LaunchedEffect(places, snapshotClusters, styleReady) {
        if (!styleReady) return@LaunchedEffect
        val map = mapRef ?: return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect

        // Luoghi: icona per luogo.
        val placeFeatures = places.filter { it.latitude != 0.0 || it.longitude != 0.0 }.map { p ->
            val iconId = "place-${p.id}"
            runCatching {
                val bmp = (createPlaceMarkerDrawable(context, p) as android.graphics.drawable.BitmapDrawable).bitmap
                style.addImage(iconId, bmp)
            }
            org.maplibre.geojson.Feature.fromGeometry(
                org.maplibre.geojson.Point.fromLngLat(p.longitude, p.latitude)
            ).apply {
                addStringProperty("icon-id", iconId)
                addStringProperty("placeId", p.id)
            }
        }
        style.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>("places-src")
            ?.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(placeFeatures))

        // Anelli del raggio (solo geofence attivi): poligono-cerchio in coordinate.
        val ringFeatures = places.filter { it.geofenceEnabled && it.radiusMeters > 0 }.map { p ->
            val ring = ArrayList<org.maplibre.geojson.Point>(37)
            val cosLat = kotlin.math.cos(Math.toRadians(p.latitude))
            for (i in 0..36) {
                val a = Math.toRadians((i * 10).toDouble())
                val dLat = p.radiusMeters * kotlin.math.cos(a) / 111320.0
                val dLon = p.radiusMeters * kotlin.math.sin(a) / (111320.0 * cosLat)
                ring.add(org.maplibre.geojson.Point.fromLngLat(p.longitude + dLon, p.latitude + dLat))
            }
            org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.LineString.fromLngLats(ring))
        }
        style.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>("place-radius-src")
            ?.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(ringFeatures))

        // Snapshot: icona per cluster.
        val snapFeatures = snapshotClusters.map { c ->
            val iconId = "snap-${c.id}"
            runCatching {
                val bmp = (createSnapshotMarkerDrawable(context, c) as android.graphics.drawable.BitmapDrawable).bitmap
                style.addImage(iconId, bmp)
            }
            org.maplibre.geojson.Feature.fromGeometry(
                org.maplibre.geojson.Point.fromLngLat(c.centerLongitude, c.centerLatitude)
            ).apply {
                addStringProperty("icon-id", iconId)
                addStringProperty("clusterId", c.id)
            }
        }
        style.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>("snapshots-src")
            ?.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(snapFeatures))
    }

    // Tap sul pallino: registrato una volta quando lo style e' pronto. Cerca le feature
    // del layer membri sotto il punto toccato e apre il dettaglio del membro.
    LaunchedEffect(styleReady) {
        if (!styleReady) return@LaunchedEffect
        val map = mapRef ?: return@LaunchedEffect
        map.addOnMapClickListener { latLng ->
            val screen = map.projection.toScreenLocation(latLng)
            // Ordine di priorita': membri, poi snapshot, poi luoghi.
            val member = map.queryRenderedFeatures(screen, "members-layer")
                .firstOrNull()?.getStringProperty("userId")
            if (member != null) {
                currentLocations.find { it.userId == member }?.let { onSelected(it) }
                return@addOnMapClickListener true
            }
            val cluster = map.queryRenderedFeatures(screen, "snapshots-layer")
                .firstOrNull()?.getStringProperty("clusterId")
            if (cluster != null) {
                currentClusters.find { it.id == cluster }?.let { onClusterSel(it) }
                return@addOnMapClickListener true
            }
            val place = map.queryRenderedFeatures(screen, "places-layer")
                .firstOrNull()?.getStringProperty("placeId")
            if (place != null) {
                currentPlaces.find { it.id == place }?.let { onPlaceSel(it) }
                return@addOnMapClickListener true
            }
            false
        }
    }

    // Centra la mappa quando arriva una richiesta di focus (pulsante "centra qui",
    // apertura di un membro/luogo). Il token forza la reazione anche a coordinate uguali.
    LaunchedEffect(focusToken) {
        if (focusToken == 0) return@LaunchedEffect
        val map = mapRef ?: return@LaunchedEffect
        val point = targetFocusPoint ?: return@LaunchedEffect
        map.animateCamera(
            org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(
                org.maplibre.android.geometry.LatLng(point.first, point.second),
                16.0
            )
        )
    }

    // Mostra/nascondi i layer secondo i toggle.
    LaunchedEffect(showMembers, showSnapshots, showPlaces, styleReady) {
        if (!styleReady) return@LaunchedEffect
        val style = mapRef?.style ?: return@LaunchedEffect
        fun vis(id: String, on: Boolean) {
            style.getLayer(id)?.setProperties(
                org.maplibre.android.style.layers.PropertyFactory.visibility(
                    if (on) org.maplibre.android.style.layers.Property.VISIBLE
                    else org.maplibre.android.style.layers.Property.NONE
                )
            )
        }
        vis("members-layer", showMembers)
        vis("trail-layer", showMembers)
        vis("snapshots-layer", showSnapshots)
        vis("places-layer", showPlaces)
        vis("place-radius-layer", showPlaces)
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // Anello del vocale: alone pulsante sulla posizione di chi sta parlando.
        val so = speakingOffset
        if (so != null) {
            val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "voice")
            val scale by pulse.animateFloat(
                initialValue = 0.6f, targetValue = 1.6f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    androidx.compose.animation.core.tween(1100), androidx.compose.animation.core.RepeatMode.Restart
                ), label = "voiceScale"
            )
            val alpha by pulse.animateFloat(
                initialValue = 0.5f, targetValue = 0f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    androidx.compose.animation.core.tween(1100), androidx.compose.animation.core.RepeatMode.Restart
                ), label = "voiceAlpha"
            )
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .offset { androidx.compose.ui.unit.IntOffset(so.x.toInt(), so.y.toInt()) }
                    .size(1.dp)
            ) {
                drawCircle(
                    color = Color(0xFFDC2626).copy(alpha = alpha),
                    radius = 60f * scale,
                    center = androidx.compose.ui.geometry.Offset(0f, 0f)
                )
            }
        }

        // Colonna controlli a destra: toggle layer + fit gruppo + zoom.
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (layerMenuOpen) {
                    CtrlButton(Icons.Default.People, if (showMembers) Color(0xFF6366F1) else Color(0xCC18181B)) { showMembers = !showMembers }
                    CtrlButton(Icons.Default.PhotoCamera, if (showSnapshots) Color(0xFFEA580C) else Color(0xCC18181B)) { showSnapshots = !showSnapshots }
                    CtrlButton(Icons.Default.Place, if (showPlaces) Color(0xFF10B981) else Color(0xCC18181B)) { showPlaces = !showPlaces }
                }
                CtrlButton(Icons.Default.Layers, if (layerMenuOpen) Color(0xFF6366F1) else Color(0xCC18181B)) { layerMenuOpen = !layerMenuOpen }
            }

            CtrlButton(Icons.Default.Group, Color(0xCC18181B)) {
                val map = mapRef ?: return@CtrlButton
                val pts = currentLocations.filter { it.latitude != 0.0 || it.longitude != 0.0 }
                    .map { org.maplibre.android.geometry.LatLng(it.latitude, it.longitude) }
                if (pts.size == 1) {
                    map.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(pts[0], 16.0))
                } else if (pts.size > 1) {
                    val b = org.maplibre.android.geometry.LatLngBounds.Builder().includes(pts).build()
                    map.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(b, 120))
                }
            }
            CtrlButton(Icons.Default.Add, Color(0xCC18181B)) {
                mapRef?.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.zoomIn())
            }
            CtrlButton(Icons.Default.Remove, Color(0xCC18181B)) {
                mapRef?.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.zoomOut())
            }
        }
    }
}

@Composable
private fun CtrlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = color,
        border = BorderStroke(1.dp, Color(0x1F71717A)),
        modifier = Modifier.size(48.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }
}

private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
        kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
        kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
    return r * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
}
