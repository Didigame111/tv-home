package br.arthur.tvhome

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Dusk: an evening living room. Calm blues so the apps' own art carries the colour,
// and one warm lamp tone that only ever means "this is where you are".
val DuskTop = Color(0xFF232B4A)
val DuskBottom = Color(0xFF2B2342)
val Lamp = Color(0xFFFFC870)
val Paper = Color(0xFFF3EEE4)
val Mist = Color(0xFFA9B0C8)
val Tile = Color(0xFF343D60)
val Panel = Color(0xFF1C2240)
val Ink = Color(0xFF1E2238)

/** TV-safe edge: Android TV keeps content 48 dp in from the sides. */
val EdgePad = 48.dp

val DuskGradient = Brush.verticalGradient(listOf(DuskTop, DuskBottom))

val InstrumentSerif = FontFamily(Font(R.font.instrument_serif))

// Figtree is a variable font; each weight sets its own wght axis.
val Figtree = FontFamily(
    Font(R.font.figtree, FontWeight.Normal),
    Font(R.font.figtree, FontWeight.Medium),
    Font(R.font.figtree, FontWeight.SemiBold),
)

val ClockText = TextStyle(fontFamily = InstrumentSerif, fontSize = 92.sp, lineHeight = 92.sp, color = Paper)
val DateText = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 17.sp, color = Mist)
val CardLabel = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, color = Paper)
val ControlText = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
val TileText = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Paper)
val PanelTitle = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = Paper)
/** Behind words that sit on artwork: separates them from bright or busy pictures. */
val ArtShadow = Shadow(Color.Black.copy(alpha = 0.75f), Offset(0f, 2f), blurRadius = 12f)

val HeroTitle = TextStyle(fontFamily = InstrumentSerif, fontSize = 54.sp, lineHeight = 56.sp, color = Paper, shadow = ArtShadow)
val HeroSource = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, color = Paper, shadow = ArtShadow)
val HeroBody = TextStyle(fontFamily = Figtree, fontSize = 18.sp, lineHeight = 26.sp, color = Paper.copy(alpha = 0.94f), shadow = ArtShadow)
val RatingText = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = Paper)
val DetailsTitle = TextStyle(fontFamily = InstrumentSerif, fontSize = 64.sp, lineHeight = 66.sp, color = Paper, shadow = ArtShadow)
val DetailsBody = TextStyle(fontFamily = Figtree, fontSize = 19.sp, lineHeight = 29.sp, color = Paper.copy(alpha = 0.94f), shadow = ArtShadow)
val HeroMeta = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = Paper.copy(alpha = 0.85f))
val RowTitle = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Paper)
val RowSource = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 15.sp, color = Mist)
val CardDetail = TextStyle(fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 16.sp, color = Mist)
val TopClock = TextStyle(
    fontFamily = Figtree,
    fontWeight = FontWeight.SemiBold,
    fontSize = 21.sp,
    color = Paper,
    shadow = Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 2f), blurRadius = 8f),
)
val PanelBody = TextStyle(fontFamily = Figtree, fontSize = 15.sp, lineHeight = 21.sp, color = Mist)
