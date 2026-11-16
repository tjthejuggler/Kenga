package com.example.kenga.ui.log

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.kenga.data.local.MuscleGroup
import com.example.kenga.data.repository.ExercisePb
import com.example.kenga.data.repository.LogDraft
import com.example.kenga.data.weights.WeightUnits
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Tail-style weights logging dialog (Tail `WeightsInputDialog` port, §7.3):
 * muscle-group selector (16 canonical groups, default = caller-supplied),
 * exercise name with quick-choice chips, kg/lb toggle, weight (one decimal),
 * reps, Machine/Free toggle, and a live PB + last-set readout under the
 * exercise field — recomputed as the name changes.
 *
 * The entered weight is converted to GRAMS (canonical storage unit) before
 * [onConfirm] fires, so logging in lb while the day list shows kg (or vice
 * versa) works with no extra configuration.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeightsLogDialog(
    /** Pre-selected muscle-group id ("" = first mapped group / first group). */
    initialMuscleGroupId: String,
    /** Pre-filled exercise name ("" = most recent quick-choice). */
    initialExerciseName: String,
    /** Initial unit for the dialog's own toggle; defaults from settings. */
    defaultUnit: String,
    /** Previously used exercise names, most recent first. */
    recentExercises: List<String>,
    /** Union PB map (incl. local PENDING) — the readout recomputes as the name changes. */
    pbs: Map<String, ExercisePb>,
    onConfirm: (draft: LogDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    var unit by remember { mutableStateOf(if (defaultUnit == WeightUnits.UNIT_LB) WeightUnits.UNIT_LB else WeightUnits.UNIT_KG) }
    var machine by remember { mutableStateOf(true) }
    var exerciseName by remember { mutableStateOf(initialExerciseName.ifBlank { recentExercises.firstOrNull() ?: "" }) }
    var weightText by remember { mutableStateOf("") }
    var repsText by remember { mutableStateOf("") }

    // Muscle group: preselection → most recently logged → first mapped → first.
    var muscleGroup by remember {
        mutableStateOf(
            MuscleGroup.fromId(initialMuscleGroupId)
                ?: MuscleGroup.entries.first(),
        )
    }
    var groupsOpen by remember { mutableStateOf(false) }

    val weight = weightText.replace(',', '.').toDoubleOrNull() ?: 0.0
    val reps = repsText.toIntOrNull() ?: 0
    val canConfirm = weight > 0.0 || reps > 0

    fun gramsLabel(grams: Int): String = WeightUnits.formatGrams(grams, unit)
    val dateFmt = DateTimeFormatter.ofPattern("d/M/yy")
    fun dateLabel(date: String?): String = try {
        date?.let { LocalDate.parse(it).format(dateFmt) } ?: "—"
    } catch (_: Exception) {
        "—"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "🏋️ Log set", fontSize = 16.sp) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // ── Muscle group selector (16 canonical) ──────────────────
                Text("Muscle group", fontSize = 12.sp, color = Color(0xFFBBBBBB))
                Spacer(modifier = Modifier.height(2.dp))
                ExposedDropdownMenuBox(
                    expanded = groupsOpen,
                    onExpandedChange = { groupsOpen = it },
                ) {
                    OutlinedTextField(
                        value = muscleGroup.displayName,
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = groupsOpen) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                            .height(52.dp),
                        textStyle = TextStyle(fontSize = 14.sp),
                    )
                    ExposedDropdownMenu(expanded = groupsOpen, onDismissRequest = { groupsOpen = false }) {
                        MuscleGroup.entries.forEach { group ->
                            DropdownMenuItem(
                                text = { Text(group.displayName, fontSize = 14.sp) },
                                onClick = {
                                    muscleGroup = group
                                    groupsOpen = false
                                },
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ── Exercise / machine name ───────────────────────────────
                Text("Exercise / Machine", fontSize = 12.sp, color = Color(0xFFBBBBBB))
                Spacer(modifier = Modifier.height(2.dp))
                OutlinedTextField(
                    value = exerciseName,
                    onValueChange = { exerciseName = it.take(40) },
                    singleLine = true,
                    placeholder = { Text("e.g. Bench Press", fontSize = 12.sp, color = Color(0xFF666666)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    textStyle = TextStyle(fontSize = 14.sp),
                )

                // Quick choices — recently used names, most recent first
                if (recentExercises.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        recentExercises.forEach { name ->
                            PillOption(
                                label = name,
                                selected = exerciseName.equals(name, ignoreCase = true),
                                onClick = { exerciseName = name },
                            )
                        }
                    }
                }

                // ── PB + last set for the typed exercise (with dates) ─────
                if (exerciseName.isNotBlank()) {
                    val pb = pbs[exerciseName.trim().lowercase()]
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF142414), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = "PB: ${gramsLabel(pb?.bestWeightGrams ?: 0)} × ${pb?.bestReps ?: 0}  (${dateLabel(pb?.bestWeightDate)})",
                            color = Color(0xFFFFD54F),
                            fontSize = 11.sp,
                        )
                        Text(
                            text = "Last: ${gramsLabel(pb?.lastWeightGrams ?: 0)} × ${pb?.lastReps ?: 0}  (${dateLabel(pb?.lastDate)})",
                            color = Color(0xFFAACCAA),
                            fontSize = 11.sp,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // ── Unit toggle (kg | lb) ─────────────────────────────────
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Unit:", fontSize = 12.sp, color = Color(0xFFBBBBBB))
                    PillOption("kg", unit == WeightUnits.UNIT_KG) { unit = WeightUnits.UNIT_KG }
                    PillOption("lb", unit == WeightUnits.UNIT_LB) { unit = WeightUnits.UNIT_LB }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // ── Machine / Free toggle ─────────────────────────────────
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Type:", fontSize = 12.sp, color = Color(0xFFBBBBBB))
                    PillOption("Machine", machine) { machine = true }
                    PillOption("Free", !machine) { machine = false }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ── Weight amount (decimal, in the selected unit) ────────
                Text(text = "Weight ($unit)", fontSize = 12.sp, color = Color(0xFFBBBBBB))
                Spacer(modifier = Modifier.height(2.dp))
                OutlinedTextField(
                    value = weightText,
                    onValueChange = { raw -> weightText = filterDecimal(raw) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier
                        .width(140.dp)
                        .height(52.dp),
                    textStyle = TextStyle(fontSize = 14.sp),
                )

                Spacer(modifier = Modifier.height(10.dp))

                // ── Reps (integer) ────────────────────────────────────────
                Text("Reps", fontSize = 12.sp, color = Color(0xFFBBBBBB))
                Spacer(modifier = Modifier.height(2.dp))
                OutlinedTextField(
                    value = repsText,
                    onValueChange = { raw -> repsText = raw.filter { it.isDigit() } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .width(140.dp)
                        .height(52.dp),
                    textStyle = TextStyle(fontSize = 14.sp),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val grams = WeightUnits.toGrams(weight, unit)
                    if (grams > 0 || reps > 0) {
                        onConfirm(
                            LogDraft(
                                muscleGroupId = muscleGroup.id,
                                exerciseName = exerciseName.trim(),
                                weightGrams = grams,
                                reps = reps,
                                machine = machine,
                            ),
                        )
                    }
                },
                enabled = canConfirm,
            ) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Small pill-shaped toggle used for the unit, machine/free and quick-choice rows (Tail port). */
@Composable
internal fun PillOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.height(32.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        colors = if (selected) ButtonDefaults.outlinedButtonColors(
            containerColor = Color(0xFF1A3A1A),
            contentColor = Color(0xFF88DD88),
        ) else ButtonDefaults.outlinedButtonColors(),
    ) {
        Text(label, fontSize = 11.sp)
    }
}

/** Keeps digits with at most one decimal separator (',' or '.'). */
internal fun filterDecimal(raw: String): String {
    val filtered = raw.filter { it.isDigit() || it == '.' || it == ',' }
    val firstSep = filtered.indexOfAny(charArrayOf('.', ','))
    return if (firstSep >= 0) {
        filtered.substring(0, firstSep + 1) + filtered.substring(firstSep + 1).filter { it.isDigit() }
    } else filtered
}
