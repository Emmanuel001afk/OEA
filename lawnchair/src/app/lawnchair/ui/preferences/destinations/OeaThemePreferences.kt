package app.lawnchair.ui.preferences.destinations

import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.theme.color.ColorOption
import app.lawnchair.ui.preferences.components.layout.PreferenceLayout
import app.lawnchair.ui.preferences.components.layout.PreferenceGroup
import app.lawnchair.ui.preferences.components.layout.PreferenceTemplate
import com.android.launcher3.R
import com.patrykmichalik.opto.core.firstBlocking
import com.patrykmichalik.opto.core.setBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun OeaThemePreferences(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = preferenceManager2()
    val themes by prefs.oeaThemeWallpapers.get()
        .collectAsStateWithLifecycle(initialValue = emptySet())

    fun applyTheme(uriString: String) {
        val uri = android.net.Uri.parse(uriString)
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                WallpaperManager.getInstance(context).setStream(
                    stream,
                    null,
                    true,
                    WallpaperManager.FLAG_SYSTEM,
                )
            }
            prefs.accentColor.setBlocking(ColorOption.WallpaperPrimary)
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val updated = themes + uri.toString()
        prefs.oeaThemeWallpapers.setBlocking(updated)
        applyTheme(uri.toString())
    }

    PreferenceLayout(
        label = stringResource(R.string.oea_themes_label),
        modifier = modifier,
    ) {
        PreferenceGroup(
            heading = stringResource(R.string.oea_themes_gallery),
        ) {
            Item {
                PreferenceTemplate(
                    title = { Text(stringResource(R.string.oea_themes_title)) },
                    description = {
                        Text(stringResource(R.string.oea_themes_description))
                    },
                    startWidget = {
                        Icon(
                            Icons.Rounded.AddPhotoAlternate,
                            contentDescription = null,
                        )
                    },
                )
            }

            Item {
                Button(
                    onClick = {
                        picker.launch(arrayOf("image/*"))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(vertical = 14.dp),
                ) {
                    Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null)
                    Text(
                        text = stringResource(R.string.oea_theme_add),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            if (themes.isEmpty()) {
                Item {
                    Text(
                        text = stringResource(R.string.oea_themes_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                Item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                    ) {
                        itemsIndexed(
                            items = themes.toList(),
                            key = { _, uri -> uri },
                        ) { index, uri ->
                            OeaThemeCard(
                                index = index,
                                uriString = uri,
                                onApply = { applyTheme(uri) },
                                onDelete = {
                                    prefs.oeaThemeWallpapers.setBlocking(
                                        themes - uri,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OeaThemeCard(
    index: Int,
    uriString: String,
    onApply: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = uriString) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(android.net.Uri.parse(uriString))
                    ?.use(BitmapFactory::decodeStream)
            }.getOrNull()
        }
    }

    Card(
        onClick = onApply,
        modifier = Modifier.size(width = 150.dp, height = 220.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = stringResource(
                        R.string.oea_theme_name,
                        index + 1,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.oea_theme_name, index + 1),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                )
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.delete_label),
                    )
                }
            }
        }
    }
}
