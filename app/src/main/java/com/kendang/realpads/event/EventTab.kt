package com.kendang.realpads.event

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kendang.realpads.BuildConfig
import com.kendang.realpads.CtrlPanelColor
import com.kendang.realpads.PadTextColor
import com.kendang.realpads.R
import com.kendang.realpads.TextDim
import com.kendang.realpads.ThemeState
import com.kendang.realpads.skin.SkinManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Isi tab "Event" di SkinPickerDialog (gaya sama: baris CtrlPanelColor rounded 8dp, aksen ThemeState.accentColor).
 * Dipanggil di dalam Column berlebar 300.dp + verticalScroll milik dialog.
 * [onUpgrade]: dipanggil di versi FREE (skin digembok) - mis. openProUpgradeLink(context).
 */
@Composable
fun EventTab(onUpgrade: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snap by EventRepository.snapshot.collectAsState()
    val prog by EventProgressManager.state.collectAsState()
    val backup by EventBackupManager.status.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    var busyEvent by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var selectedSkin by remember { mutableStateOf(SkinManager.selectedId(context)) }
    val configured = remember { EventRepository.eventsUrl(context) != null }

    fun refresh(ignoreThrottle: Boolean) {
        if (refreshing) return
        scope.launch {
            refreshing = true
            EventRepository.refresh(context, force = true, ignoreThrottle = ignoreThrottle)
            refreshing = false
        }
    }
    LaunchedEffect(Unit) { EventBackupManager.loadStatus(context); refresh(ignoreThrottle = false) }   // refresh saat tab dibuka (dibatasi 60 dtk)

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        EventBackupManager.markPermissionAsked(context)
        if (granted) scope.launch { EventBackupManager.backupNow(context, force = true) }
        else EventBackupManager.onPermissionDenied(context)
    }
    // API 26-28: izin diminta SEKALI saat fitur backup pertama dipakai (tab dibuka).
    LaunchedEffect(Unit) {
        if (EventBackupManager.needsLegacyPermission() && !EventBackupManager.hasPermission(context) && !EventBackupManager.permissionAsked(context)) {
            permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            message = when (val r = EventBackupManager.restoreFrom(context, uri)) {
                is EventBackupManager.RestoreResult.Ok ->
                    context.getString(R.string.backup_restore_ok, r.merged) +
                        if (r.skinPending) "\n" + context.getString(R.string.backup_restore_skin_later) else ""
                is EventBackupManager.RestoreResult.Bad ->
                    context.getString(R.string.backup_restore_bad, context.getString(r.resId))
                is EventBackupManager.RestoreResult.Failed ->
                    context.getString(R.string.backup_restore_bad, r.reason)
            }
            selectedSkin = SkinManager.selectedId(context)
        }
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Baris status sumber data + tombol muat ulang
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (snap.source) {
                    DataSource.ONLINE -> stringResource(R.string.event_source_online)
                    DataSource.CACHE -> stringResource(R.string.event_source_cache)
                    DataSource.BUNDLED -> stringResource(R.string.event_source_bundled)
                    DataSource.NONE -> stringResource(R.string.event_source_none)
                },
                color = TextDim, fontSize = 11.sp, modifier = Modifier.weight(1f)
            )
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                if (refreshing) CircularProgressIndicator(color = ThemeState.accentColor, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                else IconButton(onClick = { refresh(ignoreThrottle = true) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Refresh, stringResource(R.string.event_refresh_desc), tint = TextDim, modifier = Modifier.size(18.dp))
                }
            }
        }
        if (!configured) Text(stringResource(R.string.event_not_configured), color = TextDim, fontSize = 11.sp)
        if (snap.events.isEmpty() && !refreshing) Text(stringResource(R.string.event_empty), color = TextDim, fontSize = 12.sp)

        val now = System.currentTimeMillis()
        val df = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
        snap.events.forEach { ev ->
            val rec = prog.events[ev.id]
            val joined = rec?.joined == true
            val status = rec?.status ?: EventStatus.IS_LOCKED
            val doneCount = ev.quests.count { (prog.quests[it.id] ?: 0) >= it.target }
            val banner by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, ev.id, ev.banner, snap.source) {
                value = withContext(Dispatchers.IO) {
                    EventRepository.ensureBanner(context, ev)?.let { EventRepository.decodeBanner(it, 1000) }?.asImageBitmap()
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().background(CtrlPanelColor, RoundedCornerShape(8.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                banner?.let {
                    Image(it, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(90.dp).clip(RoundedCornerShape(6.dp)))
                }
                Text(ev.title, color = PadTextColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                if (ev.description.isNotBlank()) Text(ev.description, color = TextDim, fontSize = 10.sp)
                val period = when {
                    ev.hasEnded(now) -> stringResource(R.string.event_period_ended)
                    !ev.hasStarted(now) -> stringResource(R.string.event_period_not_started, df.format(Date(ev.startAtMs!!)))
                    ev.endAtMs != null -> stringResource(R.string.event_period_until, df.format(Date(ev.endAtMs)))
                    else -> null
                }
                if (period != null) Text(period, color = ThemeState.accentColor, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)

                ev.quests.forEach { q ->
                    val cur = minOf(prog.quests[q.id] ?: 0, q.target)
                    Column {
                        Row {
                            Text(q.title, color = PadTextColor, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 2)
                            Text(
                                if (cur >= q.target) stringResource(R.string.event_quest_done)
                                else stringResource(R.string.event_quest_progress, "$cur", "${q.target}"),
                                color = if (cur >= q.target) ThemeState.accentColor else TextDim, fontSize = 10.sp, fontWeight = FontWeight.Bold
                            )
                        }
                        LinearProgressIndicator(
                            progress = { cur.toFloat() / q.target },
                            color = ThemeState.accentColor, trackColor = PadTextColor.copy(alpha = 0.15f),
                            modifier = Modifier.fillMaxWidth().padding(top = 3.dp)
                        )
                    }
                }

                // Tombol aksi: Ikut / Terkunci(disabled+progres) / Klaim Skin / Pakai
                val claimedSkin = rec?.claimedSkinId ?: ev.skinId
                val busy = busyEvent == ev.id
                val (label, enabled, onClick) = when {
                    status == EventStatus.UNLOCKED && selectedSkin == claimedSkin -> Triple<String, Boolean, () -> Unit>(stringResource(R.string.event_btn_in_use), false, {})
                    status == EventStatus.UNLOCKED && !BuildConfig.IS_PREMIUM -> Triple<String, Boolean, () -> Unit>(stringResource(R.string.event_btn_premium), true, { onUpgrade() })
                    status == EventStatus.UNLOCKED -> Triple<String, Boolean, () -> Unit>(stringResource(R.string.event_btn_use), true, {
                        busyEvent = ev.id; message = null
                        scope.launch {
                            val r = EventClaimer.useUnlocked(context, claimedSkin)
                            message = context.getString(r.resId, r.arg ?: "")
                            selectedSkin = SkinManager.selectedId(context); busyEvent = null
                        }
                    })
                    status == EventStatus.READY_TO_CLAIM && !BuildConfig.IS_PREMIUM -> Triple<String, Boolean, () -> Unit>(stringResource(R.string.event_btn_premium), true, { onUpgrade() })
                    status == EventStatus.READY_TO_CLAIM -> Triple<String, Boolean, () -> Unit>(stringResource(R.string.event_btn_claim), true, {
                        busyEvent = ev.id; message = null
                        scope.launch {
                            val r = EventClaimer.claim(context, ev)
                            message = context.getString(r.resId, r.arg ?: "")
                            selectedSkin = SkinManager.selectedId(context); busyEvent = null
                        }
                    })
                    !joined && ev.hasEnded(now) -> Triple<String, Boolean, () -> Unit>(stringResource(R.string.event_btn_ended), false, {})
                    !joined -> Triple<String, Boolean, () -> Unit>(stringResource(R.string.event_btn_join), true, {
                        if (!EventProgressManager.joinEvent(ev.id)) message = context.getString(R.string.event_join_failed)
                    })
                    else -> Triple<String, Boolean, () -> Unit>(stringResource(R.string.event_btn_locked, doneCount, ev.quests.size), false, {})
                }
                Button(
                    onClick = onClick, enabled = enabled && busyEvent == null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (status == EventStatus.READY_TO_CLAIM || (!joined && enabled)) ThemeState.accentColor else CtrlPanelColor,
                        contentColor = if (status == EventStatus.READY_TO_CLAIM || (!joined && enabled)) CtrlPanelColor else PadTextColor
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    else Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        message?.let { Text(it, color = PadTextColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }

        // ---- Backup & pemulihan ----
        Spacer(Modifier.height(4.dp))
        Column(
            modifier = Modifier.fillMaxWidth().background(CtrlPanelColor, RoundedCornerShape(8.dp)).padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(stringResource(R.string.backup_title), color = PadTextColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(
                if (backup.lastBackupMs > 0) stringResource(R.string.backup_last, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(backup.lastBackupMs)))
                else stringResource(R.string.backup_never),
                color = TextDim, fontSize = 10.sp
            )
            Text(stringResource(R.string.backup_folder, backup.folder), color = TextDim, fontSize = 10.sp)
            if (backup.usedFallbackFolder) Text(stringResource(R.string.backup_folder_fallback), color = TextDim, fontSize = 10.sp)
            if (backup.msgRes != 0) {
                Text(if (backup.msgArg != null) stringResource(backup.msgRes, backup.msgArg!!) else stringResource(backup.msgRes),
                    color = PadTextColor, fontSize = 10.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.backup_auto), color = PadTextColor, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Switch(checked = backup.autoEnabled, onCheckedChange = { EventBackupManager.setAutoEnabled(context, it) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = {
                        if (!EventBackupManager.hasPermission(context)) permLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        else scope.launch { EventBackupManager.backupNow(context, force = true) }
                    },
                    enabled = !backup.busy,
                    colors = ButtonDefaults.buttonColors(containerColor = ThemeState.accentColor, contentColor = CtrlPanelColor),
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.backup_now), fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                Button(
                    onClick = { restoreLauncher.launch(arrayOf("*/*")) },
                    colors = ButtonDefaults.buttonColors(containerColor = CtrlPanelColor, contentColor = PadTextColor),
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.backup_restore), fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}
