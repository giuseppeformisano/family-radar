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
    targetFocusPoint: Pair<Double, Double>? = null,
    focusToken: Int = 0,
    followedUserId: String? = null,
    onMemberSelected: (UserLocation) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Il click listener viene registrato una volta sola: legge le posizioni correnti
    // tramite questo stato aggiornato invece di catturare la lista al momento.
    val currentLocations by androidx.compose.runtime.rememberUpdatedState(locations)
    val currentFollowed by androidx.compose.runtime.rememberUpdatedState(followedUserId)
    val onSelected by androidx.compose.runtime.rememberUpdatedState(onMemberSelected)

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

    // Tap sul pallino: registrato una volta quando lo style e' pronto. Cerca le feature
    // del layer membri sotto il punto toccato e apre il dettaglio del membro.
    LaunchedEffect(styleReady) {
        if (!styleReady) return@LaunchedEffect
        val map = mapRef ?: return@LaunchedEffect
        map.addOnMapClickListener { latLng ->
            val screen = map.projection.toScreenLocation(latLng)
            val hits = map.queryRenderedFeatures(screen, "members-layer")
            val uid = hits.firstOrNull()?.getStringProperty("userId")
            if (uid != null) {
                currentLocations.find { it.userId == uid }?.let { onSelected(it) }
                true
            } else {
                false
            }
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
