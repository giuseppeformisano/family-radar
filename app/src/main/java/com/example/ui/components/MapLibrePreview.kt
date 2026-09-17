package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Anteprima della nuova mappa vettoriale MapLibre alimentata da OpenFreeMap
 * (mondiale, gratis, con POI). Vive a parte dalla mappa attuale (osmdroid): serve
 * a valutare resa grafica e prestazioni prima di migrare l'app vera.
 *
 * Stile: uno degli style pronti di OpenFreeMap. "bright" e' piu' denso di POI
 * (negozi, bar, ecc.) con icone ed etichette; in migrazione lo style e' del tutto
 * personalizzabile (quali POI mostrare, a quale zoom, colori).
 */
@Composable
fun MapLibrePreviewDialog(
    latitude: Double,
    longitude: Double,
    members: List<com.example.model.UserLocation> = emptyList(),
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = true)
    ) {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current

        var mapRef by remember { mutableStateOf<org.maplibre.android.maps.MapLibreMap?>(null) }
        var styleReady by remember { mutableStateOf(false) }

        val mapView = remember {
            org.maplibre.android.MapLibre.getInstance(context)
            val options = org.maplibre.android.maps.MapLibreMapOptions().textureMode(true)
            org.maplibre.android.maps.MapView(context, options).apply {
                onCreate(null)
                getMapAsync { map ->
                    mapRef = map
                    map.cameraPosition = org.maplibre.android.camera.CameraPosition.Builder()
                        .target(org.maplibre.android.geometry.LatLng(latitude, longitude))
                        .zoom(15.0)
                        .build()
                    map.setStyle("https://tiles.openfreemap.org/styles/bright") { style ->
                        if (members.isNotEmpty()) {
                            // Scia: una linea per membro, dagli ultimi ~90s di punti che la
                            // posizione porta con se'. Aggiunta PRIMA dei pallini, cosi' i
                            // pallini restano sopra la linea.
                            val lineFeatures = members
                                .filter { it.recentPoints.size >= 2 }
                                .map { m ->
                                    val coords = m.recentPoints.sortedBy { it.t }
                                        .map { org.maplibre.geojson.Point.fromLngLat(it.lon, it.lat) }
                                    org.maplibre.geojson.Feature.fromGeometry(
                                        org.maplibre.geojson.LineString.fromLngLats(coords)
                                    )
                                }
                            if (lineFeatures.isNotEmpty()) {
                                style.addSource(
                                    org.maplibre.android.style.sources.GeoJsonSource(
                                        "trail-src",
                                        org.maplibre.geojson.FeatureCollection.fromFeatures(lineFeatures)
                                    )
                                )
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
                            }

                            // Pallini dei membri: punto per la posizione attuale.
                            val pointFeatures = members
                                .filter { it.latitude != 0.0 || it.longitude != 0.0 }
                                .map { m ->
                                    org.maplibre.geojson.Feature.fromGeometry(
                                        org.maplibre.geojson.Point.fromLngLat(m.longitude, m.latitude)
                                    )
                                }
                            style.addSource(
                                org.maplibre.android.style.sources.GeoJsonSource(
                                    "members-src",
                                    org.maplibre.geojson.FeatureCollection.fromFeatures(pointFeatures)
                                )
                            )
                            style.addLayer(
                                org.maplibre.android.style.layers.CircleLayer("members-layer", "members-src")
                                    .withProperties(
                                        org.maplibre.android.style.layers.PropertyFactory.circleRadius(9f),
                                        org.maplibre.android.style.layers.PropertyFactory.circleColor(android.graphics.Color.rgb(79, 70, 229)),
                                        org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth(2.5f),
                                        org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE)
                                    )
                            )
                        }
                        styleReady = true
                    }
                }
            }
        }

        // Adattatore strada: aggancia la scia alle strade reali. Se il matching non e'
        // affidabile (fuori strada) tiene la scia grezza. Aggiorna la linea gia' disegnata.
        LaunchedEffect(members, styleReady) {
            if (!styleReady) return@LaunchedEffect
            val map = mapRef ?: return@LaunchedEffect
            val withTrails = members.filter { it.recentPoints.size >= 2 }
            if (withTrails.isEmpty()) return@LaunchedEffect
            val features = withTrails.map { m ->
                val raw = m.recentPoints.sortedBy { it.t }.map { it.lat to it.lon }
                val snapped = com.example.util.RoadMatcher.matchToRoads(raw) ?: raw
                val pts = snapped.map { org.maplibre.geojson.Point.fromLngLat(it.second, it.first) }
                org.maplibre.geojson.Feature.fromGeometry(
                    org.maplibre.geojson.LineString.fromLngLats(pts)
                )
            }
            val fc = org.maplibre.geojson.FeatureCollection.fromFeatures(features)
            map.style?.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>("trail-src")
                ?.setGeoJson(fc)
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

        Box(modifier = Modifier.fillMaxSize().background(Color(0xFF06080D))) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

            Box(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(12.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0x66000000))
                    .clickable { onDismiss() }
                    .align(Alignment.TopStart),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Close, contentDescription = "Chiudi", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
    }
}
