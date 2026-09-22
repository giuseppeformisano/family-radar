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
import androidx.compose.material.icons.filled.Navigation
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
    styleUrl: String,
    places: List<com.example.model.SavedPlace> = emptyList(),
    snapshots: List<com.example.model.PlaceSnapshot> = emptyList(),
    targetFocusPoint: Pair<Double, Double>? = null,
    focusToken: Int = 0,
    followedUserId: String? = null,
    speakingUserId: String? = null,
    heatmapPoints: List<Pair<Double, Double>> = emptyList(),
    heatmapFitToken: Int = 0,
    followCam: Boolean = false,
    onFollowCamChange: (Boolean) -> Unit = {},
    terrainEnabled: Boolean = false,
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
    val currentFollowCam by androidx.compose.runtime.rememberUpdatedState(followCam)
    val currentOnFollowCamChange by androidx.compose.runtime.rememberUpdatedState(onFollowCamChange)
    var speakingOffset by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }

    // Smorzamento bearing per la 3D cam: media circolare sugli ultimi N fix.
    val bearingBuffer = remember { BearingBuffer(size = 6) }

    // Cache snap strade: (lat, lon) grezzo → (lat, lon) agganciato alla strada.
    // Ogni punto viene snappato una sola volta; aggiornamenti successivi riusano
    // il risultato senza fare nuove query alla mappa.
    val snapCache = remember { mutableMapOf<Pair<Double, Double>, Pair<Double, Double>>() }

    var showMembers by remember { mutableStateOf(true) }
    var showSnapshots by remember { mutableStateOf(true) }
    var showPlaces by remember { mutableStateOf(true) }
    var layerMenuOpen by remember { mutableStateOf(false) }

    var mapRef by remember { mutableStateOf<org.maplibre.android.maps.MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var centeredOnce by remember { mutableStateOf(false) }

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
                // Via logo MapLibre, attribuzione e bussola (l'indicatore nord in alto
                // che finiva sotto le altre scritte).
                map.uiSettings.isLogoEnabled = false
                map.uiSettings.isAttributionEnabled = false
                map.uiSettings.isCompassEnabled = false
                map.setStyle(styleUrl) { style ->
                    addRadarLayers(style)
                    styleReady = true
                }
            }
        }
    }

    // Cambio stile mappa: ricarica lo style sulla mappa esistente e ri-aggiunge i
    // layer. Salta la prima esecuzione (lo style e' gia' caricato in getMapAsync).
    var styleInitialized by remember { mutableStateOf(false) }
    LaunchedEffect(styleUrl) {
        if (!styleInitialized) { styleInitialized = true; return@LaunchedEffect }
        val map = mapRef ?: return@LaunchedEffect
        styleReady = false
        map.setStyle(styleUrl) { style ->
            addRadarLayers(style)
            styleReady = true
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

        // Scia con map matching: ogni punto viene snappato alla strada più vicina
        // nei tile già caricati. La cache evita di fare query per punti già visti:
        // in pratica viene chiamato snapToRoad solo per il punto nuovo di ogni secondo.
        val trailFeatures = valid.filter { it.recentPoints.size >= 2 }.map { m ->
            val pts = m.recentPoints.sortedBy { it.t }.map { rp ->
                val key = rp.lat to rp.lon
                val (sLat, sLon) = snapCache.getOrPut(key) {
                    snapToRoad(map, rp.lat, rp.lon)
                }
                org.maplibre.geojson.Point.fromLngLat(sLon, sLat)
            }
            org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.LineString.fromLngLats(pts))
        }
        // Mantieni la cache entro una dimensione ragionevole (max 500 punti totali).
        if (snapCache.size > 500) {
            val toRemove = snapCache.keys.take(snapCache.size - 500)
            toRemove.forEach { snapCache.remove(it) }
        }
        style.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>("trail-src")
            ?.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(trailFeatures))

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

        // Bearing per la 3D cam: aggiorniamo il buffer solo quando il membro seguito
        // si muove abbastanza veloce da avere un bearing GPS affidabile (>5 km/h).
        if (followedUserId != null) {
            val target = valid.find { it.userId == followedUserId }
            if (target != null && target.speed * 3.6f > 5f && target.bearing >= 0f) {
                bearingBuffer.add(target.bearing)
            }
        }

        // Inseguimento 2D: solo se la 3D cam NON è attiva (quella gestisce posizione
        // e bearing nel ticker).
        if (followedUserId != null && !followCam) {
            val target = valid.find { it.userId == followedUserId }
            if (target != null) {
                map.animateCamera(
                    org.maplibre.android.camera.CameraUpdateFactory.newLatLng(
                        org.maplibre.android.geometry.LatLng(target.latitude, target.longitude)
                    )
                )
            }
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

            // 3D follow cam: ogni tick aggiorna posizione + bearing + tilt della camera.
            if (currentFollowCam) {
                val followed = currentFollowed
                if (followed != null) {
                    val pos = memberDisplayed[followed]
                    val bearing = bearingBuffer.smooth()
                    if (pos != null) {
                        mapRef?.animateCamera(
                            org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                                org.maplibre.android.camera.CameraPosition.Builder()
                                    .target(org.maplibre.android.geometry.LatLng(pos.first, pos.second))
                                    .bearing((bearing ?: mapRef?.cameraPosition?.bearing?.toFloat() ?: 0f).toDouble())
                                    .tilt(83.0)
                                    .zoom(19.0)
                                    .build()
                            ), 120, null
                        )
                    }
                }
            }

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

    // Heatmap posizioni storiche: aggiorna sorgente e inquadra la mappa.
    var lastHeatFitToken by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(heatmapPoints, heatmapFitToken, styleReady) {
        if (!styleReady) return@LaunchedEffect
        val style = mapRef?.style ?: return@LaunchedEffect
        val features = heatmapPoints.map { (lat, lon) ->
            org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.Point.fromLngLat(lon, lat))
        }
        style.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>("heatmap-src")
            ?.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(features))
        style.getLayer("heatmap-layer")?.setProperties(
            org.maplibre.android.style.layers.PropertyFactory.visibility(
                if (heatmapPoints.isEmpty()) org.maplibre.android.style.layers.Property.NONE
                else org.maplibre.android.style.layers.Property.VISIBLE
            )
        )
        if (heatmapFitToken != 0 && heatmapFitToken != lastHeatFitToken && heatmapPoints.isNotEmpty()) {
            lastHeatFitToken = heatmapFitToken
            val map = mapRef ?: return@LaunchedEffect
            try {
                val lats = heatmapPoints.map { it.first }
                val lons = heatmapPoints.map { it.second }
                val bounds = org.maplibre.android.geometry.LatLngBounds.Builder()
                    .include(org.maplibre.android.geometry.LatLng(lats.min(), lons.min()))
                    .include(org.maplibre.android.geometry.LatLng(lats.max(), lons.max()))
                    .build()
                map.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(bounds, 80))
            } catch (_: Exception) {}
        }
    }

    // Edifici 3D e tilt camera quando si attiva/disattiva la 3D cam.
    LaunchedEffect(followCam, styleReady) {
        if (!styleReady) return@LaunchedEffect
        val style = mapRef?.style ?: return@LaunchedEffect
        style.getLayer("buildings-3d")?.setProperties(
            org.maplibre.android.style.layers.PropertyFactory.visibility(
                if (followCam) org.maplibre.android.style.layers.Property.VISIBLE
                else org.maplibre.android.style.layers.Property.NONE
            )
        )
        if (followCam) {
            // Se non si sta seguendo nessuno, imposta solo il tilt una volta sola.
            // Se si sta seguendo qualcuno, il ticker gestirà posizione + bearing + tilt.
            if (followedUserId == null) {
                mapRef?.animateCamera(
                    org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                        org.maplibre.android.camera.CameraPosition.Builder()
                            .tilt(83.0)
                            .build()
                    ), 400, null
                )
            }
        } else {
            // Torna a vista piana quando si esce dalla 3D cam.
            mapRef?.animateCamera(
                org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(
                    org.maplibre.android.camera.CameraPosition.Builder()
                        .tilt(0.0)
                        .bearing(0.0)
                        .build()
                ), 400, null
            )
            bearingBuffer.clear()
        }
    }

    // Blocca lo scorrimento (pan) durante l'inseguimento; zoom rimane libero.
    LaunchedEffect(followedUserId, styleReady) {
        if (!styleReady) return@LaunchedEffect
        mapRef?.uiSettings?.isScrollGesturesEnabled = followedUserId == null
    }

    // Hillshading: attiva/disattiva quando cambia la preferenza.
    LaunchedEffect(terrainEnabled, styleReady) {
        if (!styleReady) return@LaunchedEffect
        val style = mapRef?.style ?: return@LaunchedEffect
        style.getLayer("hillshade-layer")?.setProperties(
            org.maplibre.android.style.layers.PropertyFactory.visibility(
                if (terrainEnabled) org.maplibre.android.style.layers.Property.VISIBLE
                else org.maplibre.android.style.layers.Property.NONE
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
            // Pulsante 3D/2D: sempre visibile, indipendente dall'inseguimento.
            CtrlButton(
                Icons.Default.Navigation,
                if (followCam) Color(0xFF6366F1) else Color(0xCC18181B)
            ) { currentOnFollowCamChange(!followCam) }
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

private fun addRadarLayers(style: org.maplibre.android.maps.Style) {
    fun symbol(id: String, srcId: String) = org.maplibre.android.style.layers.SymbolLayer(id, srcId)
        .withProperties(
            org.maplibre.android.style.layers.PropertyFactory.iconImage(
                org.maplibre.android.style.expressions.Expression.get("icon-id")
            ),
            org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap(true),
            org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement(true)
        )

    // Sorgente DEM per hillshading (AWS Terrain, formato terrarium, gratuito).
    // Nascosta di default; attivata dalla preferenza "Rilievo 3D".
    runCatching {
        val demSource = org.maplibre.android.style.sources.RasterDemSource("dem-src",
            "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png")
        style.addSource(demSource)
        style.addLayer(
            org.maplibre.android.style.layers.HillshadeLayer("hillshade-layer", "dem-src")
                .withProperties(
                    org.maplibre.android.style.layers.PropertyFactory.hillshadeExaggeration(0.5f),
                    org.maplibre.android.style.layers.PropertyFactory.hillshadeHighlightColor(
                        android.graphics.Color.rgb(255, 250, 240)),
                    org.maplibre.android.style.layers.PropertyFactory.hillshadeShadowColor(
                        android.graphics.Color.rgb(60, 55, 50)),
                    org.maplibre.android.style.layers.PropertyFactory.hillshadeAccentColor(
                        android.graphics.Color.rgb(100, 90, 80)),
                    org.maplibre.android.style.layers.PropertyFactory.visibility(
                        org.maplibre.android.style.layers.Property.NONE)
                )
        )
    }

    // Heatmap sotto tutto il resto.
    style.addSource(org.maplibre.android.style.sources.GeoJsonSource("heatmap-src"))
    style.addLayer(
        org.maplibre.android.style.layers.HeatmapLayer("heatmap-layer", "heatmap-src").withProperties(
            org.maplibre.android.style.layers.PropertyFactory.heatmapRadius(18f),
            org.maplibre.android.style.layers.PropertyFactory.heatmapOpacity(.72f),
            org.maplibre.android.style.layers.PropertyFactory.heatmapColor(
                org.maplibre.android.style.expressions.Expression.interpolate(
                    org.maplibre.android.style.expressions.Expression.linear(),
                    org.maplibre.android.style.expressions.Expression.heatmapDensity(),
                    org.maplibre.android.style.expressions.Expression.literal(0),   org.maplibre.android.style.expressions.Expression.rgba(0,0,255,0),
                    org.maplibre.android.style.expressions.Expression.literal(0.2), org.maplibre.android.style.expressions.Expression.rgb(0,255,255),
                    org.maplibre.android.style.expressions.Expression.literal(0.5), org.maplibre.android.style.expressions.Expression.rgb(0,255,0),
                    org.maplibre.android.style.expressions.Expression.literal(0.8), org.maplibre.android.style.expressions.Expression.rgb(255,255,0),
                    org.maplibre.android.style.expressions.Expression.literal(1),   org.maplibre.android.style.expressions.Expression.rgb(255,0,0)
                )
            ),
            org.maplibre.android.style.layers.PropertyFactory.visibility(org.maplibre.android.style.layers.Property.NONE)
        )
    )
    // Edifici 3D: usa i dati di altezza già presenti nei tile OpenFreeMap (schema
    // OpenMapTiles). Nascosti di default, si attivano con la 3D cam.
    runCatching {
        style.addLayerAbove(
            org.maplibre.android.style.layers.FillExtrusionLayer("buildings-3d", "openmaptiles")
                .withSourceLayer("building")
                .withProperties(
                    org.maplibre.android.style.layers.PropertyFactory.fillExtrusionColor(
                        android.graphics.Color.rgb(220, 212, 204)
                    ),
                    org.maplibre.android.style.layers.PropertyFactory.fillExtrusionHeight(
                        org.maplibre.android.style.expressions.Expression.get("render_height")
                    ),
                    org.maplibre.android.style.layers.PropertyFactory.fillExtrusionBase(
                        org.maplibre.android.style.expressions.Expression.get("render_min_height")
                    ),
                    org.maplibre.android.style.layers.PropertyFactory.fillExtrusionOpacity(0.85f),
                    org.maplibre.android.style.layers.PropertyFactory.visibility(
                        org.maplibre.android.style.layers.Property.NONE
                    )
                ),
            "heatmap-layer"
        )
    }

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
    style.addSource(org.maplibre.android.style.sources.GeoJsonSource("place-radius-src"))
    style.addLayer(
        org.maplibre.android.style.layers.LineLayer("place-radius-layer", "place-radius-src")
            .withProperties(
                org.maplibre.android.style.layers.PropertyFactory.lineColor(android.graphics.Color.argb(140, 99, 102, 241)),
                org.maplibre.android.style.layers.PropertyFactory.lineWidth(2f)
            )
    )
    style.addSource(org.maplibre.android.style.sources.GeoJsonSource("places-src"))
    style.addLayer(symbol("places-layer", "places-src"))
    style.addSource(org.maplibre.android.style.sources.GeoJsonSource("snapshots-src"))
    style.addLayer(symbol("snapshots-layer", "snapshots-src"))
    style.addSource(org.maplibre.android.style.sources.GeoJsonSource("members-src"))
    style.addLayer(symbol("members-layer", "members-src"))
}

// Ammorbidisce una polilinea GPS con l'algoritmo di Chaikin (nessuna rete stradale richiesta).
private fun chaikinSmooth(pts: List<org.maplibre.geojson.Point>, passes: Int = 3): List<org.maplibre.geojson.Point> {
    var c = pts
    repeat(passes) {
        val out = mutableListOf(c.first())
        for (i in 0 until c.size - 1) {
            val (x0, y0) = c[i].longitude() to c[i].latitude()
            val (x1, y1) = c[i + 1].longitude() to c[i + 1].latitude()
            out += org.maplibre.geojson.Point.fromLngLat(x0 * .75 + x1 * .25, y0 * .75 + y1 * .25)
            out += org.maplibre.geojson.Point.fromLngLat(x0 * .25 + x1 * .75, y0 * .25 + y1 * .75)
        }
        out += c.last()
        c = out
    }
    return c
}

// Snap di un punto GPS alla strada piu' vicina gia' caricata nei tile vettoriali.
// Se nessuna strada e' entro maxDistM metri, restituisce il punto originale (off-road).
// Deve essere chiamata sul main thread (queryRenderedFeatures e' una API UI).
// Layer stradali dello stile OpenFreeMap (schema OpenMapTiles).
// Passare i layer ID specifici è molto più veloce che queryare tutti i layer.
private val ROAD_LAYER_IDS = arrayOf(
    "road_path", "road_minor", "road_secondary_tertiary",
    "road_major", "road_motorway_trunk", "road_link",
    "road_service_track", "transportation_name_road"
)

private fun snapToRoad(
    map: org.maplibre.android.maps.MapLibreMap,
    lat: Double,
    lon: Double,
    maxDistM: Double = 40.0
): Pair<Double, Double> = try {
    val screen = map.projection.toScreenLocation(org.maplibre.android.geometry.LatLng(lat, lon))
    val r = 80f
    val box = android.graphics.RectF(screen.x - r, screen.y - r, screen.x + r, screen.y + r)
    val features = map.queryRenderedFeatures(box, *ROAD_LAYER_IDS)
    var bestLat = lat; var bestLon = lon; var bestDist = maxDistM
    for (feat in features) {
        val geom = feat.geometry() ?: continue
        val lines: List<List<org.maplibre.geojson.Point>> = when (geom) {
            is org.maplibre.geojson.LineString      -> listOf(geom.coordinates())
            is org.maplibre.geojson.MultiLineString -> geom.coordinates()
            else -> emptyList()
        }
        for (line in lines) {
            for (i in 0 until line.size - 1) {
                val (sLat, sLon) = nearestOnSegment(
                    lat, lon,
                    line[i].latitude(), line[i].longitude(),
                    line[i + 1].latitude(), line[i + 1].longitude()
                )
                val d = distanceMeters(lat, lon, sLat, sLon)
                if (d < bestDist) { bestDist = d; bestLat = sLat; bestLon = sLon }
            }
        }
    }
    bestLat to bestLon
} catch (_: Exception) { lat to lon }

// Punto piu' vicino sul segmento A-B al punto P (coordinate geografiche).
private fun nearestOnSegment(
    pLat: Double, pLon: Double,
    aLat: Double, aLon: Double,
    bLat: Double, bLon: Double
): Pair<Double, Double> {
    val dx = bLon - aLon; val dy = bLat - aLat
    val lenSq = dx * dx + dy * dy
    if (lenSq == 0.0) return aLat to aLon
    val t = ((pLon - aLon) * dx + (pLat - aLat) * dy) / lenSq
    val tc = t.coerceIn(0.0, 1.0)
    return (aLat + tc * dy) to (aLon + tc * dx)
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

/**
 * Media circolare degli ultimi N bearing GPS.
 * La media circolare evita l'artefatto 0°/360° (es. media(350°, 10°) = 0°, non 180°).
 */
private class BearingBuffer(private val size: Int = 6) {
    private val values = ArrayDeque<Float>()

    fun add(bearing: Float) {
        values.addLast(bearing)
        if (values.size > size) values.removeFirst()
    }

    fun smooth(): Float? {
        if (values.isEmpty()) return null
        var sinSum = 0.0
        var cosSum = 0.0
        for (b in values) {
            val rad = Math.toRadians(b.toDouble())
            sinSum += kotlin.math.sin(rad)
            cosSum += kotlin.math.cos(rad)
        }
        val deg = Math.toDegrees(kotlin.math.atan2(sinSum, cosSum)).toFloat()
        return if (deg < 0f) deg + 360f else deg
    }

    fun clear() = values.clear()
}
