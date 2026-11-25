package com.jhaiian.clint.comictranslate

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jhaiian.clint.R
import com.jhaiian.clint.translation.ClintTranslations
import com.jhaiian.clint.ui.theme.LocalClintColors
import com.jhaiian.clint.util.formatFileSize
import java.util.Locale

private const val MIN_CHIP_FONT_SP = 7f
private const val MAX_CHIP_FONT_SP = 16f

@Composable
fun ComicTranslationOverlay(
    state: ComicOverlayState,
    showOriginal: Boolean,
    targetOptions: List<String>,
    onToggleOriginal: () -> Unit,
    onTargetSelected: (String) -> Unit,
    onToggleAutoSite: (Boolean) -> Unit,
    onDownloadModel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalClintColors.current
    BackHandler { onDismiss() }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                    }
                }
            }
    ) {
        when (state) {
            is ComicOverlayState.Analyzing -> {
                state.bitmap?.let { FrozenPage(it) }
                CenteredCard {
                    CircularProgressIndicator(color = colors.primary, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
                    Text(
                        stringResource(R.string.comic_translate_analyzing),
                        color = colors.popupText,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
                ComicCloseButton(onDismiss)
            }
            is ComicOverlayState.Downloading -> {
                FrozenPage(state.bitmap)
                CenteredCard {
                    CircularProgressIndicator(color = colors.primary, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
                    Text(
                        stringResource(
                            R.string.comic_translate_downloading,
                            ClintTranslations.displayName(state.language, Locale.getDefault()),
                            formatFileSize(state.bytes)
                        ),
                        color = colors.popupText,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
                ComicCloseButton(onDismiss)
            }
            is ComicOverlayState.Failed -> {
                state.bitmap?.let { FrozenPage(it) }
                CenteredCard {
                    Text(
                        failureText(state),
                        color = colors.popupText,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    state.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                        Text(
                            detail,
                            color = colors.popupText.copy(alpha = 0.6f),
                            fontSize = 10.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    when (state.failure) {
                        ComicFailure.MODEL_MISSING -> Button(
                            onClick = onDownloadModel,
                            colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
                            shape = RoundedCornerShape(20.dp),
                            modifier = Modifier.padding(top = 14.dp)
                        ) {
                            Text(stringResource(R.string.comic_translate_download_model), fontSize = 13.sp)
                        }
                        else -> TextButton(onClick = onRetry, modifier = Modifier.padding(top = 6.dp)) {
                            Text(stringResource(R.string.comic_translate_retry), color = colors.primary, fontSize = 13.sp)
                        }
                    }
                }
                ComicCloseButton(onDismiss)
            }
            is ComicOverlayState.Ready -> {
                TranslatedPage(state.page, showOriginal)
                ComicTopBar(
                    page = state.page,
                    showOriginal = showOriginal,
                    targetOptions = targetOptions,
                    onToggleOriginal = onToggleOriginal,
                    onTargetSelected = onTargetSelected,
                    onToggleAutoSite = onToggleAutoSite,
                    onDismiss = onDismiss
                )
            }
        }
    }
}

@Composable
private fun FrozenPage(bitmap: Bitmap) {
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun TranslatedPage(page: ComicTranslationPage, showOriginal: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val scaleX = constraints.maxWidth.toFloat() / page.bitmap.width
        val scaleY = constraints.maxHeight.toFloat() / page.bitmap.height
        FrozenPage(page.bitmap)
        val chipColor = Color.White.copy(alpha = 0.94f)
        val textColor = Color(0xFF121212)
        page.blocks.forEach { block ->
            val text = if (showOriginal) block.original else block.translation ?: block.original
            val widthDp = with(density) { (block.bounds.width() * scaleX).toDp() }
            val heightDp = with(density) { (block.bounds.height() * scaleY).toDp() }
            val fontSize = with(density) {
                val byHeight = (block.bounds.height() * scaleY * 0.42f).toSp().value
                val byLength = (block.bounds.width() * scaleX * block.bounds.height() * scaleY).let { area ->
                    kotlin.math.sqrt(area / (text.length.coerceAtLeast(1) * 1.35f))
                }.toSp().value
                minOf(byHeight, byLength).coerceIn(MIN_CHIP_FONT_SP, MAX_CHIP_FONT_SP)
            }
            Box(
                modifier = Modifier
                    .offset(x = with(density) { (block.bounds.left * scaleX).toDp() }, y = with(density) { (block.bounds.top * scaleY).toDp() })
                    .size(width = widthDp.coerceAtLeast(12.dp), height = heightDp.coerceAtLeast(10.dp))
                    .clip(RoundedCornerShape(3.dp))
                    .background(chipColor)
                    .padding(horizontal = 1.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = text,
                    color = textColor,
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize * 1.08f).sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 6,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ComicTopBar(
    page: ComicTranslationPage,
    showOriginal: Boolean,
    targetOptions: List<String>,
    onToggleOriginal: () -> Unit,
    onTargetSelected: (String) -> Unit,
    onToggleAutoSite: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalClintColors.current
    val locale = Locale.getDefault()
    var languageMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.popupBackground.copy(alpha = 0.95f))
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Translate, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
        Column(Modifier.padding(start = 8.dp).weight(1f)) {
            Text(
                stringResource(R.string.comic_translate_title),
                color = colors.popupText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "${ClintTranslations.displayName(page.sourceLanguage, locale)} → " +
                    ClintTranslations.displayName(page.targetLanguage, locale) + " · " +
                    stringResource(
                        if (page.provider == ComicTranslationProvider.ON_DEVICE) R.string.comic_translate_via_device
                        else R.string.comic_translate_via_online
                    ),
                color = colors.secondaryText,
                fontSize = 11.sp
            )
        }
        Box {
            TextButton(onClick = { languageMenu = true }) {
                Text(
                    ClintTranslations.displayName(page.targetLanguage, locale),
                    color = colors.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            DropdownMenu(
                expanded = languageMenu,
                onDismissRequest = { languageMenu = false },
                containerColor = colors.popupBackground
            ) {
                targetOptions.forEach { code ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                ClintTranslations.displayName(code, locale),
                                color = colors.popupText,
                                fontSize = 13.sp
                            )
                        },
                        trailingIcon = if (code == page.targetLanguage) {
                            { Icon(Icons.Filled.Check, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp)) }
                        } else null,
                        onClick = {
                            languageMenu = false
                            if (code != page.targetLanguage) onTargetSelected(code)
                        }
                    )
                }
            }
        }
        IconButton(onClick = onToggleOriginal, modifier = Modifier.size(38.dp)) {
            Text(
                if (showOriginal) stringResource(R.string.comic_translate_translated) else stringResource(R.string.comic_translate_original),
                color = if (showOriginal) colors.primary else colors.iconTint,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
        }
        IconButton(onClick = { onToggleAutoSite(!page.autoTranslateSite) }, modifier = Modifier.size(38.dp)) {
            Text(
                stringResource(R.string.comic_translate_auto_short),
                color = if (page.autoTranslateSite) colors.primary else colors.iconTint,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium
            )
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(38.dp)) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.comic_translate_close), tint = colors.iconTint)
        }
    }
}

@Composable
private fun ComicCloseButton(onDismiss: () -> Unit) {
    val colors = LocalClintColors.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        IconButton(onClick = onDismiss, modifier = Modifier.padding(4.dp).size(38.dp)) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.comic_translate_close), tint = colors.popupText)
        }
    }
}

@Composable
private fun CenteredCard(content: @Composable () -> Unit) {
    val colors = LocalClintColors.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.popupBackground.copy(alpha = 0.96f))
                .clickable(enabled = false) {}
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            content()
        }
    }
}

@Composable
private fun failureText(state: ComicOverlayState.Failed): String {
    val locale = Locale.getDefault()
    return when (state.failure) {
        ComicFailure.NO_TEXT -> stringResource(R.string.comic_translate_no_text)
        ComicFailure.NO_SOURCE_LANGUAGE -> stringResource(R.string.comic_translate_no_source)
        ComicFailure.SAME_LANGUAGE -> stringResource(
            R.string.comic_translate_same_language,
            state.sourceLanguage?.let { ClintTranslations.displayName(it, locale) } ?: ""
        )
        ComicFailure.CAPTURE_FAILED -> stringResource(R.string.comic_translate_capture_failed)
        ComicFailure.MODEL_MISSING -> stringResource(
            R.string.comic_translate_model_missing,
            formatFileSize(state.downloadBytes)
        )
        ComicFailure.TRANSLATION_FAILED -> stringResource(R.string.comic_translate_failed)
    }
}
