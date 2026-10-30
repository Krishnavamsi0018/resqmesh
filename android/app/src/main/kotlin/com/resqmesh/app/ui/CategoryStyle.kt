package com.resqmesh.app.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flood
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.resqmesh.app.R
import com.resqmesh.app.ui.theme.Tokens
import com.resqmesh.mesh.packet.Category

/** Every category is an icon, a colour and a word (design 3.1, 3.2), the same on every screen. */
data class CategoryStyle(val color: Color, val icon: ImageVector, @param:StringRes val label: Int)

fun Category.style(): CategoryStyle = when (this) {
    Category.GENERAL -> CategoryStyle(Tokens.CatGeneral, Icons.Filled.Sos, R.string.cat_general)
    Category.MEDICAL -> CategoryStyle(Tokens.CatMedical, Icons.Filled.MedicalServices, R.string.cat_medical)
    Category.FLOOD -> CategoryStyle(Tokens.CatFlood, Icons.Filled.Flood, R.string.cat_flood)
    Category.FIRE -> CategoryStyle(Tokens.CatFire, Icons.Filled.LocalFireDepartment, R.string.cat_fire)
    Category.TRAPPED -> CategoryStyle(Tokens.CatTrapped, TrappedIcon, R.string.cat_trapped)
    Category.WATER -> CategoryStyle(Tokens.CatWater, Icons.Filled.WaterDrop, R.string.cat_water)
}

/** The home screen's five tiles, in the spec's order (F-10); GENERAL is the big SOS button. */
val TileCategories = listOf(Category.MEDICAL, Category.FLOOD, Category.FIRE, Category.TRAPPED, Category.WATER)

/** Material has no "trapped" symbol, so the spec calls for a custom one: a person under a fallen beam. */
val TrappedIcon: ImageVector = ImageVector.Builder("Trapped", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        // The beam, slanting across the top.
        moveTo(2f, 8.5f)
        lineTo(22f, 3f)
        lineTo(22f, 6.5f)
        lineTo(2f, 12f)
        close()
        // Head: two half circles.
        moveTo(6.5f, 15f)
        arcTo(2.5f, 2.5f, 0f, isMoreThanHalf = false, isPositiveArc = false, 11.5f, 15f)
        arcTo(2.5f, 2.5f, 0f, isMoreThanHalf = false, isPositiveArc = false, 6.5f, 15f)
        close()
        // Crouched body.
        moveTo(4f, 22f)
        lineTo(4f, 21f)
        curveTo(4f, 19f, 6f, 18.5f, 9f, 18.5f)
        curveTo(12f, 18.5f, 14f, 19f, 14f, 21f)
        lineTo(14f, 22f)
        close()
    }
}.build()
