package com.example.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

    var mapRef by remember { mutableStateOf<org.maplibre.android.maps.MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var centeredOnce by remember { mutableStateOf(false) }

    // Per ora sempre "bright" (POI ricchi). Lo style scuro dedicato arrivera' dopo,
    // una volta verificato quale style scuro offre OpenFreeMap.
    val styleUrl = "https://tiles.openfreemap.org/styles/bright"

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

    // Aggiornamento in tempo reale di pallini e scia (grezza), + centraggio iniziale.
    LaunchedEffect(locations, styleReady) {
        if (!styleReady) return@LaunchedEffect
        val map = mapRef ?: return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect

        val valid = locations.filter { it.latitude != 0.0 || it.longitude != 0.0 }

        val pointFeatures = valid.map { m ->
            val isSelf = m.userId == currentUserId
            val displayName = if (!m.nickname.isNullOrBlank()) "${m.userName} (${m.nickname})" else m.userName
            val speedKmH = (m.speed * 3.6f).toInt()
            // Stessa icona della vecchia mappa (nome + foto + stato), generata come
            // immagine e registrata nello style. addImage sovrascrive se gia' presente.
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
            org.maplibre.geojson.Feature.fromGeometry(
                org.maplibre.geojson.Point.fromLngLat(m.longitude, m.latitude)
            ).apply {
                addStringProperty("icon-id", iconId)
                addStringProperty("userId", m.userId)
            }
        }
        style.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>("members-src")
            ?.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(pointFeatures))

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

    AndroidView(factory = { mapView }, modifier = modifier)
}
