@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.graphicsLayer
import com.example.ui.theme.Spacing
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalFloatingToolbar
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

/** Mappa dell'app basata su MapLibre + OpenFreeMap (vettoriale, mondiale). */
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
    mapMatchingEnabled: Boolean = false,
    onMapCenterChanged: (Pair<Double, Double>) -> Unit = {},
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
    val currentOnMapCenterChanged by androidx.compose.runtime.rememberUpdatedState(onMapCenterChanged)
    var speakingOffset by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }

    // Smorzamento bearing per la 3D cam: media circolare sugli ultimi N fix.
    val bearingBuffer = remember { BearingBuffer(size = 6) }

    // Cache candidati strada per punto grezzo (lat, lon) → lista di punti strada
    // vicini. Ogni punto viene interrogato una sola volta; gli aggiornamenti
    // successivi riusano il risultato senza nuove query alla mappa. Solo il punto
    // nuovo di ogni secondo viene calcolato.
    val candCache = remember { mutableMapOf<Pair<Double, Double>, List<RoadCandidate>>() }

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
                map.addOnCameraIdleListener {
                    val pos = map.cameraPosition.target ?: return@addOnCameraIdleListener
                    currentOnMapCenterChanged(pos.latitude to pos.longitude)
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
    LaunchedEffect(locations, styleReady, mapMatchingEnabled) {
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

        // Scia: con il map matching ATTIVO (interruttore sviluppatore) i punti
        // vengono agganciati alle strade reali tenendo conto del percorso (vedi
        // matchTrailToRoads, calcolo locale sui tile). Spento, si usa la linea
        // grezza del GPS. I nomi dei livelli stradali cambiano da stile a stile,
        // quindi li scopriamo al volo: sono i livelli-linea che pescano dal
        // contenitore dati "transportation", uguale in tutti gli stili OpenFreeMap.
        val roadLayerIds = if (mapMatchingEnabled) {
            style.layers.filterIsInstance<org.maplibre.android.style.layers.LineLayer>()
                .filter { it.sourceLayer == "transportation" }
                .map { it.id }
        } else emptyList()

        val trailFeatures = valid.filter { it.recentPoints.size >= 2 }.map { m ->
            val raw = m.recentPoints.sortedBy { it.t }
            val coords: List<org.maplibre.geojson.Point> = if (mapMatchingEnabled && roadLayerIds.isNotEmpty()) {
                matchTrailToRoads(map, raw, roadLayerIds, candCache)
            } else {
                raw.map { org.maplibre.geojson.Point.fromLngLat(it.lon, it.lat) }
            }
            org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.LineString.fromLngLats(coords))
        }
        // Tetto alla cache candidati, come prima per lo snap.
        if (candCache.size > 500) {
            val toRemove = candCache.keys.take(candCache.size - 500)
            toRemove.forEach { candCache.remove(it) }
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

    // Rilievo (hillshade): gli stili OpenFreeMap sono solo vettoriali e NON hanno
    // dati di elevazione, quindi il rilievo va aggiunto a mano. Usiamo un tiles.json
    // MapLibre che dichiara l'encoding lato server (terrarium), cosi' evitiamo il
    // problema dell'encoding non impostabile dal codice sull'SDK Android.
    // Il layer viene inserito SOTTO le etichette, cosi' membri e luoghi restano sopra.
    LaunchedEffect(terrainEnabled, styleReady) {
        if (!styleReady) return@LaunchedEffect
        val style = mapRef?.style ?: return@LaunchedEffect
        val demId = "radar-dem-src"
        val hillId = "radar-hillshade"
        if (terrainEnabled) {
            runCatching {
                if (style.getSource(demId) == null) {
                    // AWS Terrain Tiles: DEM globale, gratuito, senza chiave. Sono
                    // codificati "terrarium": l'SDK di default decodifica "mapbox",
                    // quindi impostiamo l'encoding sul TileSet o le montagne non si
                    // decodificano (era il motivo per cui non si vedeva nulla).
                    val tileSet = org.maplibre.android.style.sources.TileSet(
                        "2.1.0",
                        "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"
                    ).apply {
                        encoding = "terrarium"
                        minZoom = 0f
                        maxZoom = 15f
                    }
                    style.addSource(
                        org.maplibre.android.style.sources.RasterDemSource(demId, tileSet, 256)
                    )
                }
                if (style.getLayer(hillId) == null) {
                    val layer = org.maplibre.android.style.layers.HillshadeLayer(hillId, demId)
                        .withProperties(
                            org.maplibre.android.style.layers.PropertyFactory.hillshadeShadowColor(
                                android.graphics.Color.argb(150, 40, 45, 55)
                            ),
                            org.maplibre.android.style.layers.PropertyFactory.hillshadeHighlightColor(
                                android.graphics.Color.argb(120, 255, 255, 255)
                            ),
                            org.maplibre.android.style.layers.PropertyFactory.hillshadeExaggeration(0.7f)
                        )
                    val belowId = style.layers.firstOrNull {
                        it is org.maplibre.android.style.layers.SymbolLayer
                    }?.id
                    if (belowId != null) style.addLayerBelow(layer, belowId) else style.addLayer(layer)
                } else {
                    style.getLayer(hillId)?.setProperties(
                        org.maplibre.android.style.layers.PropertyFactory.visibility(
                            org.maplibre.android.style.layers.Property.VISIBLE
                        )
                    )
                }
            }
        } else {
            style.getLayer(hillId)?.setProperties(
                org.maplibre.android.style.layers.PropertyFactory.visibility(
                    org.maplibre.android.style.layers.Property.NONE
                )
            )
        }
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

        // Controlli a destra (Material 3 Expressive): barra flottante squircle con
        // centra-gruppo, vista 3D e zoom. (I livelli sono nel menu a ventaglio in
        // basso a sinistra.)
        VerticalFloatingToolbar(
            expanded = true,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp)
        ) {
            IconButton(onClick = {
                val map = mapRef ?: return@IconButton
                val pts = currentLocations.filter { it.latitude != 0.0 || it.longitude != 0.0 }
                    .map { org.maplibre.android.geometry.LatLng(it.latitude, it.longitude) }
                if (pts.size == 1) {
                    map.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(pts[0], 16.0))
                } else if (pts.size > 1) {
                    val b = org.maplibre.android.geometry.LatLngBounds.Builder().includes(pts).build()
                    map.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngBounds(b, 120))
                }
            }) {
                Icon(Icons.Default.Group, contentDescription = "Centra gruppo")
            }
            IconToggleButton(checked = followCam, onCheckedChange = { currentOnFollowCamChange(it) }) {
                Icon(Icons.Default.Navigation, contentDescription = "Vista 3D")
            }
            IconButton(onClick = { mapRef?.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.zoomIn()) }) {
                Icon(Icons.Default.Add, contentDescription = "Zoom avanti")
            }
            IconButton(onClick = { mapRef?.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.zoomOut()) }) {
                Icon(Icons.Default.Remove, contentDescription = "Zoom indietro")
            }
        }

        // Menu "Livelli" a ventaglio: trigger in basso a sinistra; i tre toggle
        // (membri/foto/luoghi) esplodono verso l'alto-destra con molla. L'animazione
        // vale sia all'apertura sia alla chiusura (fanProgress torna a 0).
        val fanProgress by animateFloatAsState(
            targetValue = if (layerMenuOpen) 1f else 0f,
            animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow),
            label = "layerFan"
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = Spacing.lg, bottom = 80.dp)
        ) {
            if (fanProgress > 0.001f) {
                data class Fan(
                    val icon: androidx.compose.ui.graphics.vector.ImageVector,
                    val checked: Boolean,
                    val angle: Float,
                    val desc: String,
                    val onToggle: (Boolean) -> Unit
                )
                val fans = listOf(
                    Fan(Icons.Default.People, showMembers, 0f, "Membri") { showMembers = it },
                    Fan(Icons.Default.PhotoCamera, showSnapshots, 45f, "Foto") { showSnapshots = it },
                    Fan(Icons.Default.Place, showPlaces, 90f, "Luoghi") { showPlaces = it }
                )
                val radius = 72f
                fans.forEachIndexed { i, f ->
                    val p = ((fanProgress * 1.35f) - i * 0.18f).coerceIn(0f, 1f)
                    val rad = Math.toRadians(f.angle.toDouble())
                    val dx = (kotlin.math.cos(rad) * radius * p).toFloat()
                    val dy = (-kotlin.math.sin(rad) * radius * p).toFloat()
                    Surface(
                        onClick = { f.onToggle(!f.checked) },
                        shape = CircleShape,
                        color = if (f.checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .offset(dx.dp, dy.dp)
                            .size(48.dp)
                            .graphicsLayer { alpha = p; scaleX = p; scaleY = p }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                f.icon,
                                contentDescription = f.desc,
                                tint = if (f.checked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
            Surface(
                onClick = { layerMenuOpen = !layerMenuOpen },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Layers,
                        contentDescription = "Livelli",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .size(24.dp)
                            .graphicsLayer { rotationZ = fanProgress * 45f }
                    )
                }
            }
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
            org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement(true),
            // I pallini sono a goccia: la punta in fondo al bitmap indica il punto esatto.
            org.maplibre.android.style.layers.PropertyFactory.iconAnchor(
                org.maplibre.android.style.layers.Property.ICON_ANCHOR_BOTTOM
            )
        )

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

// ───────────────────────── MAP MATCHING LOCALE (a percorso) ─────────────────────
// Candidato di aggancio: un punto su una strada vicino al punto GPS grezzo.
//  - emitDist: quanto il punto è stato "tirato" verso la strada (metri).
//  - bearing: orientamento della strada in quel punto (0..360, uso non direzionale);
//    null quando è il punto grezzo di ripiego (nessuna strada).
//  - roadId: identità stabile della strada, per la "stickiness" (restare sulla stessa
//    strada tra punti consecutivi); null quando non ricavabile.
data class RoadCandidate(
    val lat: Double,
    val lon: Double,
    val emitDist: Double,
    val bearing: Double? = null,
    val roadId: String? = null
)

private const val MATCH_MAX_DIST_M = 45.0   // oltre questa distanza la strada non è candidata
private const val MATCH_CANDIDATES = 5      // quante strade vicine considerare per punto
private const val MATCH_FALLBACK_COST = 60.0 // "costo" del punto grezzo quando non c'è strada

// Taratura dei due miglioramenti anti "strade parallele":
//  - HEADING: penalizza le strade orientate diversamente dalla direzione di marcia.
//  - STICKINESS: penalizza il cambio di strada tra due punti consecutivi.
private const val MATCH_HEADING_MIN_STEP_M = 4.0  // sotto questo passo la direzione GPS non è affidabile
private const val MATCH_HEADING_MAX_M = 30.0      // penalità massima (strada perpendicolare al moto)
private const val MATCH_SWITCH_PENALTY_M = 25.0   // penalità per saltare da una strada all'altra

// Per un punto GPS, trova fino a [MATCH_CANDIDATES] strade vicine (una per strada) e
// per ognuna il punto più vicino su di essa. Interroga solo i tile già disegnati.
// Deve girare sul main thread (queryRenderedFeatures è una API della UI).
private fun candidatesForPoint(
    map: org.maplibre.android.maps.MapLibreMap,
    lat: Double, lon: Double,
    roadLayerIds: List<String>
): List<RoadCandidate> = try {
    val screen = map.projection.toScreenLocation(org.maplibre.android.geometry.LatLng(lat, lon))
    val r = 90f
    val box = android.graphics.RectF(screen.x - r, screen.y - r, screen.x + r, screen.y + r)
    val features = map.queryRenderedFeatures(box, *roadLayerIds.toTypedArray())
    val out = ArrayList<RoadCandidate>()
    for (feat in features) {
        val geom = feat.geometry() ?: continue
        val lines: List<List<org.maplibre.geojson.Point>> = when (geom) {
            is org.maplibre.geojson.LineString      -> listOf(geom.coordinates())
            is org.maplibre.geojson.MultiLineString -> geom.coordinates()
            else -> emptyList()
        }
        // Punto più vicino su QUESTA strada (un candidato per strada), e gli estremi
        // del segmento vincente per ricavarne l'orientamento.
        var bLat = 0.0; var bLon = 0.0; var bDist = Double.MAX_VALUE
        var saLat = 0.0; var saLon = 0.0; var sbLat = 0.0; var sbLon = 0.0
        for (line in lines) {
            for (i in 0 until line.size - 1) {
                val (sLat, sLon) = nearestOnSegment(
                    lat, lon,
                    line[i].latitude(), line[i].longitude(),
                    line[i + 1].latitude(), line[i + 1].longitude()
                )
                val d = distanceMeters(lat, lon, sLat, sLon)
                if (d < bDist) {
                    bDist = d; bLat = sLat; bLon = sLon
                    saLat = line[i].latitude(); saLon = line[i].longitude()
                    sbLat = line[i + 1].latitude(); sbLon = line[i + 1].longitude()
                }
            }
        }
        if (bDist <= MATCH_MAX_DIST_M) {
            val bearing = segmentBearing(saLat, saLon, sbLat, sbLon)
            out.add(RoadCandidate(bLat, bLon, bDist, bearing, roadIdentity(feat)))
        }
    }
    out.sortedBy { it.emitDist }.take(MATCH_CANDIDATES)
} catch (_: Exception) { emptyList() }

// Identità stabile di una strada per la stickiness. Preferisce nome/numero (stabili
// anche tra tile diversi); in mancanza usa l'id della feature; se non c'è nulla
// restituisce null (nessuna stickiness, meglio che incollare strade diverse).
private fun roadIdentity(feat: org.maplibre.geojson.Feature): String? {
    fun strProp(key: String): String? = try {
        if (feat.hasProperty(key)) feat.getStringProperty(key)?.takeIf { it.isNotBlank() } else null
    } catch (_: Exception) { null }
    val named = listOfNotNull(strProp("name"), strProp("ref")).joinToString("|")
    if (named.isNotBlank()) return named
    val id = try { feat.id() } catch (_: Exception) { null }
    return if (!id.isNullOrBlank()) "id:$id" else null
}

// Orientamento (0..360) del segmento A→B. Usato in modo NON direzionale (vedi
// headingDiffDeg): una strada vale uguale percorsa nei due versi.
private fun segmentBearing(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val dLon = Math.toRadians(bLon - aLon)
    val la1 = Math.toRadians(aLat); val la2 = Math.toRadians(bLat)
    val y = kotlin.math.sin(dLon) * kotlin.math.cos(la2)
    val x = kotlin.math.cos(la1) * kotlin.math.sin(la2) -
        kotlin.math.sin(la1) * kotlin.math.cos(la2) * kotlin.math.cos(dLon)
    val brng = Math.toDegrees(kotlin.math.atan2(y, x))
    return (brng + 360.0) % 360.0
}

// Differenza angolare tra due orientamenti, ripiegata a 0..90: una strada e la sua
// opposta (180°) sono "allineate", così il verso di percorrenza non conta.
private fun headingDiffDeg(a: Double, b: Double): Double {
    var d = kotlin.math.abs(a - b) % 360.0
    if (d > 180.0) d = 360.0 - d   // 0..180
    if (d > 90.0) d = 180.0 - d    // 0..90 (non direzionale)
    return d
}

// Aggancio della scia alle strade tenendo conto del PERCORSO (non punto-per-punto).
// Algoritmo: per ogni punto GPS si raccolgono alcune strade candidate; poi con una
// scansione in avanti (Viterbi) si sceglie la sequenza di candidati che minimizza:
//  - quanto ogni punto viene spostato dalla sua posizione reale (costo "emissione"),
//  - quanto il passo tra due candidati consecutivi si discosta dal passo GPS reale
//    (costo "transizione"): così la scia resta continua e non salta su strade parallele.
private fun matchTrailToRoads(
    map: org.maplibre.android.maps.MapLibreMap,
    raw: List<com.example.model.TrailPoint>,
    roadLayerIds: List<String>,
    candCache: MutableMap<Pair<Double, Double>, List<RoadCandidate>>
): List<org.maplibre.geojson.Point> {
    // Candidati per ogni punto (cache per non riquery-are i punti già visti). Se un
    // punto non ha strade vicine, si usa il punto grezzo con un costo di penalità.
    val cands: List<List<RoadCandidate>> = raw.map { rp ->
        val key = rp.lat to rp.lon
        val list = candCache.getOrPut(key) { candidatesForPoint(map, rp.lat, rp.lon, roadLayerIds) }
        if (list.isEmpty()) listOf(RoadCandidate(rp.lat, rp.lon, MATCH_FALLBACK_COST)) else list
    }
    val n = raw.size
    if (n == 0) return emptyList()

    // Viterbi in avanti.
    val cost = Array(n) { DoubleArray(cands[it].size) { Double.MAX_VALUE } }
    val back = Array(n) { IntArray(cands[it].size) { -1 } }
    for (k in cands[0].indices) cost[0][k] = cands[0][k].emitDist
    for (t in 1 until n) {
        val gpsStep = distanceMeters(raw[t - 1].lat, raw[t - 1].lon, raw[t].lat, raw[t].lon)
        // Direzione di marcia affidabile solo se ci si è spostati abbastanza.
        val gpsHeading = if (gpsStep >= MATCH_HEADING_MIN_STEP_M)
            segmentBearing(raw[t - 1].lat, raw[t - 1].lon, raw[t].lat, raw[t].lon) else null
        for (j in cands[t].indices) {
            val cj = cands[t][j]
            // Costo direzione: dipende solo dal candidato j (strada orientata come il moto?).
            val headCost = if (gpsHeading != null && cj.bearing != null)
                (headingDiffDeg(gpsHeading, cj.bearing) / 90.0) * MATCH_HEADING_MAX_M else 0.0
            var best = Double.MAX_VALUE; var bestI = -1
            for (i in cands[t - 1].indices) {
                if (cost[t - 1][i] == Double.MAX_VALUE) continue
                val ci = cands[t - 1][i]
                val step = distanceMeters(ci.lat, ci.lon, cj.lat, cj.lon)
                val trans = kotlin.math.abs(step - gpsStep)
                // Stickiness: penalizza il cambio di strada (solo se entrambe note).
                val stick = if (ci.roadId != null && cj.roadId != null && ci.roadId != cj.roadId)
                    MATCH_SWITCH_PENALTY_M else 0.0
                val c = cost[t - 1][i] + trans + stick
                if (c < best) { best = c; bestI = i }
            }
            cost[t][j] = (if (best == Double.MAX_VALUE) 0.0 else best) + cj.emitDist + headCost
            back[t][j] = bestI
        }
    }

    // Miglior candidato finale e backtrack.
    var endK = 0; var endCost = Double.MAX_VALUE
    for (k in cands[n - 1].indices) if (cost[n - 1][k] < endCost) { endCost = cost[n - 1][k]; endK = k }
    val chosen = IntArray(n)
    chosen[n - 1] = endK
    for (t in n - 1 downTo 1) chosen[t - 1] = back[t][chosen[t]].coerceAtLeast(0)

    return raw.indices.map { t ->
        val c = cands[t][chosen[t]]
        org.maplibre.geojson.Point.fromLngLat(c.lon, c.lat)
    }
}

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
