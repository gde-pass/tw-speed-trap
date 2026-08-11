package io.github.gdepass.twspeedtrap.ui

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.gdepass.twspeedtrap.R
import io.github.gdepass.twspeedtrap.data.CameraRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val REPO_URL = "https://github.com/gde-pass/tw-speed-trap"
private const val DATA_LICENSE_URL = "https://data.gov.tw/license"

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val appVersion =
        remember {
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "?"
        }
    val metadata by produceState(initialValue = emptyMap()) {
        value =
            withContext(Dispatchers.IO) {
                runCatching { CameraRepository(context.applicationContext).metadata() }
                    .getOrDefault(emptyMap())
            }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
                Text(stringResource(R.string.about_title), style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.about_version, appVersion),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(
                    R.string.data_version_info,
                    metadata["data_version"] ?: "—",
                    metadata["count"] ?: "—",
                ),
                style = MaterialTheme.typography.bodyLarge,
            )

            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.about_attribution_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.about_attribution_body), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            // Attribution required by 政府資料開放授權條款第1版 — keep verbatim.
            Text("資料來源：政府資料開放平臺 (data.gov.tw)", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
            Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, DATA_LICENSE_URL.toUri())) }) {
                Text(stringResource(R.string.about_data_license))
            }

            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.about_code_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.about_license), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.about_osm), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(10.dp))
            Button(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, REPO_URL.toUri())) }) {
                Text(stringResource(R.string.about_repo))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
