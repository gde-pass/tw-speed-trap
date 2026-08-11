package io.github.gdepass.twspeedtrap.ui

import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.gdepass.twspeedtrap.R
import io.github.gdepass.twspeedtrap.data.CameraRepository
import io.github.gdepass.twspeedtrap.detection.Camera
import io.github.gdepass.twspeedtrap.detection.CameraType
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

// One color per camera type, mirrored between the map dots and the legend.
private val TYPE_COLORS =
    mapOf(
        CameraType.FIXED to Color(0xFF4FC3F7),
        CameraType.RED_LIGHT to Color(0xFFE53935),
        CameraType.TECH to Color(0xFFFFA726),
        CameraType.SECTION to Color(0xFFAB47BC),
        CameraType.MOBILE to Color(0xFF26A69A),
        CameraType.OTHER to Color(0xFF9E9E9E),
    )

private val LEGEND_LABELS =
    mapOf(
        CameraType.FIXED to R.string.type_fixed,
        CameraType.RED_LIGHT to R.string.type_red_light,
        CameraType.TECH to R.string.type_tech,
        CameraType.SECTION to R.string.type_section,
    )

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
                    buildCameraOverlays(cameras).forEach(map.overlays::add)
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
        Legend(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 24.dp),
        )
    }
}

@Composable
private fun Legend(modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    RoundedCornerShape(8.dp),
                ).padding(8.dp),
    ) {
        LEGEND_LABELS.forEach { (type, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier =
                        Modifier
                            .padding(end = 6.dp)
                            .size(10.dp)
                            .background(TYPE_COLORS.getValue(type), CircleShape),
                )
                Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** One overlay per camera type so each type keeps its own colour; the dense
 * fixed layer is added first and the rarer types render on top of it. */
private fun buildCameraOverlays(cameras: List<Camera>): List<SimpleFastPointOverlay> {
    val drawOrder = listOf(CameraType.FIXED) + (CameraType.entries - CameraType.FIXED)
    return drawOrder.mapNotNull { type ->
        val ofType = cameras.filter { it.type == type }
        if (ofType.isEmpty()) return@mapNotNull null
        val points = ofType.map { LabelledGeoPoint(it.lat, it.lon, it.description) }
        val style =
            Paint().apply {
                style = Paint.Style.FILL
                color =
                    android.graphics.Color.argb(
                        255,
                        (TYPE_COLORS.getValue(type).red * 255).toInt(),
                        (TYPE_COLORS.getValue(type).green * 255).toInt(),
                        (TYPE_COLORS.getValue(type).blue * 255).toInt(),
                    )
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
        SimpleFastPointOverlay(SimplePointTheme(points, false), options)
    }
}
