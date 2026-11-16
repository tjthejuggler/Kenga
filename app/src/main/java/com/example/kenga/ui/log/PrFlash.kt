package com.example.kenga.ui.log

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.kenga.data.weights.PrCheck
import com.example.kenga.data.weights.WeightUnits
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Seconds before the PR flash auto-dismisses (Tail `WEIGHTS_PR_FLASH_SECONDS`). */
const val PR_FLASH_SECONDS = 7

private val PR_FLASH_DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("d/M/yy")

internal fun recordStoodLabel(stoodDays: Long): String =
    if (stoodDays >= 365) "${stoodDays / 365}y ${stoodDays % 365}d"
    else "$stoodDays days"

/**
 * Formats the flash's "old record" story line: the date the record was set
 * and how long it had stood. Null when no date is known.
 */
internal fun prOldRecordLabel(dateStr: String?): String? {
    val oldDate = try {
        dateStr?.let { LocalDate.parse(it) }
    } catch (_: Exception) {
        null
    } ?: return null
    val datePart = "on ${oldDate.format(PR_FLASH_DATE_FMT)}"
    val days = ChronoUnit.DAYS.between(oldDate, LocalDate.now())
    val stoodPart = if (days > 0) "stood ${recordStoodLabel(days)}" else null
    return listOfNotNull(datePart, stoodPart).joinToString(" ")
}

/**
 * A one-shot PR flash event hosted by the Log screen: [version] increments on
 * every new flash so the auto-dismiss timer restarts for the newer one.
 */
data class PrFlashEvent(
    val version: Int,
    val exerciseName: String,
    val check: PrCheck,
)

/**
 * Celebratory bottom flash (Tail `WeightsPrFlash` port, §7.3), shown when a
 * logged set breaks the all-time PB for that exercise. Tells the user WHICH
 * record fell (weight, reps, or both), the new value, and the story of the
 * old record (value + date + how long it stood). Auto-dismisses after
 * [PR_FLASH_SECONDS] — the [version] counter resets the timer when a newer
 * flash arrives — and dismisses immediately on tap.
 */
@Composable
fun PrFlash(
    event: PrFlashEvent?,
    unit: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = event
    LaunchedEffect(current?.version) {
        if (current != null) {
            delay(PR_FLASH_SECONDS * 1000L)
            onDismiss()
        }
    }
    AnimatedVisibility(
        visible = current != null,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        val check = current?.check ?: return@AnimatedVisibility
        Box(
            modifier = Modifier
                .background(Color(0xFF2A2408), RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFFFFD54F), RoundedCornerShape(12.dp))
                .clickable(onClick = onDismiss)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val recordKind = when {
                    check.weightPr && check.repsPr -> "NEW ALL-TIME PB — WEIGHT & REPS!"
                    check.weightPr -> "NEW ALL-TIME PB — WEIGHT!"
                    else -> "NEW ALL-TIME PB — REPS!"
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "🏆 ${current.exerciseName}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFFD700),
                    )
                }
                Text(
                    text = recordKind,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFFFD54F),
                )

                fun gramsLabel(grams: Int): String = WeightUnits.formatGrams(grams, unit)
                if (check.weightPr) {
                    Text(
                        text = "Heaviest ever: ${gramsLabel(check.newWeightGrams)} × ${check.newReps}",
                        fontSize = 12.sp,
                        color = Color(0xFFEEFFEE),
                    )
                }
                if (check.repsPr) {
                    Text(
                        text = "Most reps ever: ${check.newReps} × ${gramsLabel(check.newWeightGrams)}",
                        fontSize = 12.sp,
                        color = Color(0xFFEEFFEE),
                    )
                }

                // Old-record story lines: value + where the record stood
                if (check.weightPr && check.oldWeightGrams > 0) {
                    Text(
                        text = "Previous best ${gramsLabel(check.oldWeightGrams)} — " +
                            (prOldRecordLabel(check.oldWeightDate) ?: "all-time"),
                        fontSize = 11.sp,
                        color = Color(0xFFBBBB88),
                    )
                }
                if (check.repsPr && check.oldReps > 0) {
                    Text(
                        text = "Previous best ${check.oldReps} reps — " +
                            (prOldRecordLabel(check.oldRepsDate) ?: "all-time"),
                        fontSize = 11.sp,
                        color = Color(0xFFBBBB88),
                    )
                }
            }
        }
    }
}
