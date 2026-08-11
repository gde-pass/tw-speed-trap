package io.github.gdepass.twspeedtrap.ui

import android.graphics.Color
import android.graphics.Paint
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.gdepass.twspeedtrap.R
import io.github.gdepass.twspeedtrap.data.CameraRepository
import io.github.gdepass.twspeedtrap.detection.Camera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.simplefastpoint.LabelledGeoPoint
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlay
import org.osmdroid.views.overlay.simplefastpoint.SimpleFastPointOverlayOptions
import org.osmdroid.views.overlay.simplefastpoint.SimplePointTheme
import java.io.File

/** Read-only coverage map: the camera database rendered on OSM tiles. */
@Composable
fun MapScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val cameras by produceState<List<Camera>>(initialValue = emptyList()) {
        value =
            withContext(Dispatchers.IO) {
                runCatching { CameraRepository(context.applicationContext).loadCameras() }
                    .getOrDefault(emptyList())
            }
    }
    val mapView =
        remember {
            // OSM tile policy: identify ourselves and cache tiles on disk.
            Configuration.getInstance().apply {
                userAgentValue = context.packageName
                osmdroidBasePath = File(context.cacheDir, "osmdroid")
                osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
            }
            MapView(context).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(8.0)
                controller.setCenter(GeoPoint(23.75, 120.95))
                overlays.add(CopyrightOverlay(context))
            }
        }

    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView },
            update = { map ->
                if (cameras.isNotEmpty() && map.overlays.none { it is SimpleFastPointOverlay }) {
                    map.overlays.add(buildCameraOverlay(cameras))
                    map.invalidate()
                }
            },
        )
        FloatingActionButton(
            onClick = onBack,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp),
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
        }
    }
}

private fun buildCameraOverlay(cameras: List<Camera>): SimpleFastPointOverlay {
    val points = cameras.map { LabelledGeoPoint(it.lat, it.lon, it.description) }
    val style =
        Paint().apply {
            style = Paint.Style.FILL
            color = Color.rgb(79, 195, 247)
        }
    val options =
        SimpleFastPointOverlayOptions
            .getDefaultStyle()
            .setAlgorithm(SimpleFastPointOverlayOptions.RenderingAlgorithm.MEDIUM_OPTIMIZATION)
            .setSymbol(SimpleFastPointOverlayOptions.Shape.CIRCLE)
            .setRadius(7f)
            .setIsClickable(false)
            .setCellSize(12)
            .setPointStyle(style)
    return SimpleFastPointOverlay(SimplePointTheme(points, false), options)
}
