package com.resqmesh.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.PortableWifiOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resqmesh.app.R
import com.resqmesh.app.ui.theme.Tokens
import com.resqmesh.mesh.packet.Category
import com.resqmesh.mesh.sos.MySos

/** Screen A4: the mesh status bar, the big SOS button and five category tiles. One tap starts the countdown. */
@Composable
fun HomeScreen(
    running: Boolean,
    linked: Int,
    locationOn: Boolean,
    mySos: MySos?,
    onPick: (Category) -> Unit,
    onOpenStatus: () -> Unit,
    onFixLocation: () -> Unit,
    onChangeLanguage: () -> Unit,
) {
    val active = mySos?.isOpen == true
    // While an SOS is open every tap leads to its status: one SOS at a time keeps relays under the per-sender limit.
    val pick: (Category) -> Unit = if (active) { _ -> onOpenStatus() } else onPick
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MeshStatusBar(running, linked, onChangeLanguage)
        if (!locationOn) {
            Banner(Icons.Filled.LocationOff, stringResource(R.string.home_location_off), Tokens.StatusWaiting) {
                TextButton(onClick = onFixLocation) { Text(stringResource(R.string.perm_settings)) }
            }
        }
        if (active) {
            Banner(Icons.Filled.Share, stringResource(R.string.home_active), Tokens.StatusRelayed, onClick = onOpenStatus)
        }
        SosButton(pulsing = active, onClick = { pick(Category.GENERAL) })
        TileCategories.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { category -> CategoryTile(category, Modifier.weight(1f)) { pick(category) } }
            }
        }
    }
}

/** "3 phones nearby" / "Looking for nearby phones" / "Relay is off": icon and words, never colour alone. */
@Composable
private fun MeshStatusBar(running: Boolean, linked: Int, onChangeLanguage: () -> Unit) {
    val (icon, text) = when {
        !running -> Icons.Filled.PortableWifiOff to stringResource(R.string.mesh_off)
        linked == 0 -> Icons.Filled.BluetoothSearching to stringResource(R.string.mesh_searching)
        else -> Icons.Filled.Share to pluralStringResource(R.plurals.mesh_nearby, linked, linked)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
        IconButton(onClick = onChangeLanguage, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Filled.Translate, contentDescription = stringResource(R.string.action_language))
        }
    }
}

@Composable
private fun Banner(
    icon: ImageVector,
    text: String,
    color: Color,
    onClick: (() -> Unit)? = null,
    action: @Composable (() -> Unit)? = null,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            Icon(icon, contentDescription = null, tint = color)
            Text(text, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
            action?.invoke()
        }
    }
}

/** 200 dp red circle (design 3.5). An open SOS shows a slow 1.6 s pulse ring around it. */
@Composable
private fun SosButton(pulsing: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.cat_general)
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .padding(vertical = 8.dp)
            .size(232.dp)
            .drawBehind {
                if (pulsing) {
                    val base = 100.dp.toPx()
                    drawCircle(Tokens.CatGeneral.copy(alpha = 0.35f * (1 - pulse)), radius = base + 16.dp.toPx() * pulse)
                }
            },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .size(200.dp)
                .clip(CircleShape)
                .background(Tokens.CatGeneral)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label },
        ) {
            Text("SOS", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.home_tap_for_help), color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center)
        }
    }
}

/** Solid category colour, white 48 dp icon, label up to two lines (design 3.5); about 100 × 100 dp. */
@Composable
private fun CategoryTile(category: Category, modifier: Modifier, onClick: () -> Unit) {
    val style = category.style()
    val label = stringResource(style.label)
    val spoken = stringResource(R.string.tile_spoken, label)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .heightIn(min = 104.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(style.color)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .padding(12.dp),
    ) {
        Icon(style.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
        Text(label, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
    }
}
