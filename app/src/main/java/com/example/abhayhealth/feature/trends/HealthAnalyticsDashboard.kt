package com.example.abhayhealth.feature.trends

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bloodtype
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.abhayhealth.R
import com.example.abhayhealth.feature.vitals.VitalType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Interactive Health Analytics Dashboard powered by Cloud Firestore streams
 * and native Compose Canvas visualizations inspired by Recharts.
 */
@Composable
fun HealthAnalyticsDashboard(
    state: HealthDashboardUiState,
    onSelectRange: (DashboardTimeRange) -> Unit,
    onLogVitalClick: (VitalType) -> Unit,
    onSharePdfClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("health_analytics_dashboard"),
        contentPadding = PaddingValues(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Live Firestore Sync Header + Time Range Selector
        item {
            DashboardTelemetryHeaderCard(
                state = state,
                onSelectRange = onSelectRange,
                onLogVitalClick = { onLogVitalClick(VitalType.BLOOD_PRESSURE) },
                onSharePdfClick = onSharePdfClick
            )
        }

        // 2. KPI Summary Row (BP, Glucose, Weight)
        item {
            DashboardKpiSummaryStrip(
                state = state,
                onLogVitalClick = onLogVitalClick
            )
        }

        // 3. Blood Pressure Over Time (Dual Systolic / Diastolic Recharts Curve)
        item {
            BloodPressureRechartsCard(
                points = state.bloodPressureSeries,
                avgText = state.bpAvgText,
                onLogBpClick = { onLogVitalClick(VitalType.BLOOD_PRESSURE) }
            )
        }

        // 4. Blood Glucose Over Time (Area Curve + Target Glycemic Zone Band)
        item {
            BloodGlucoseRechartsCard(
                points = state.glucoseSeries,
                avgText = state.glucoseAvgText,
                onLogGlucoseClick = { onLogVitalClick(VitalType.BLOOD_GLUCOSE) }
            )
        }

        // 5. Weight Over Time (if logged)
        if (state.weightSeries.isNotEmpty()) {
            item {
                WeightRechartsCard(
                    points = state.weightSeries,
                    avgText = state.weightAvgText,
                    onLogWeightClick = { onLogVitalClick(VitalType.WEIGHT) }
                )
            }
        }
    }
}

@Composable
private fun DashboardTelemetryHeaderCard(
    state: HealthDashboardUiState,
    onSelectRange: (DashboardTimeRange) -> Unit,
    onLogVitalClick: () -> Unit,
    onSharePdfClick: (() -> Unit)? = null
) {
    val isPending = state.syncState.hasPendingWrites
    val isFromCache = state.syncState.isFromCache
    val syncLabel = when {
        isPending -> stringResource(id = R.string.dashboard_sync_pending)
        isFromCache -> stringResource(id = R.string.dashboard_sync_cached)
        else -> stringResource(id = R.string.dashboard_sync_live)
    }
    val syncIcon = when {
        isPending -> Icons.Default.CloudSync
        isFromCache -> Icons.Default.CloudOff
        else -> Icons.Default.CloudDone
    }
    val syncBadgeBg = when {
        isPending -> MaterialTheme.colorScheme.tertiaryContainer
        isFromCache -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val syncBadgeFg = when {
        isPending -> MaterialTheme.colorScheme.onTertiaryContainer
        isFromCache -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("dashboard_telemetry_header"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(id = R.string.dashboard_firestore_source_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Surface(
                        color = syncBadgeBg,
                        contentColor = syncBadgeFg,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = syncIcon,
                                contentDescription = syncLabel,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = "$syncLabel • ${state.firestorePointsCount} pts",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                FilledTonalButton(
                    onClick = onLogVitalClick,
                    modifier = Modifier.testTag("dashboard_log_vital_btn"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(id = R.string.vitals_btn_log),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DashboardTimeRange.entries.forEach { range ->
                    FilterChip(
                        selected = state.selectedRange == range,
                        onClick = { onSelectRange(range) },
                        label = { Text(range.shortLabel) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("dashboard_range_${range.shortLabel.lowercase()}")
                    )
                }

                if (onSharePdfClick != null) {
                    Spacer(modifier = Modifier.weight(1f, fill = false))
                    OutlinedButton(
                        onClick = onSharePdfClick,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .height(36.dp)
                            .testTag("dashboard_share_pdf_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureAsPdf,
                            contentDescription = stringResource(id = R.string.dashboard_share_pdf_btn),
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = stringResource(id = R.string.dashboard_share_pdf_btn),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardKpiSummaryStrip(
    state: HealthDashboardUiState,
    onLogVitalClick: (VitalType) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        KpiMetricCard(
            title = stringResource(id = R.string.vitals_metric_bp),
            value = state.latestBpText,
            unit = stringResource(id = R.string.vitals_unit_bp),
            statusLabel = state.bpStatusLabel,
            status = state.bpStatus,
            icon = Icons.Default.Favorite,
            onClick = { onLogVitalClick(VitalType.BLOOD_PRESSURE) },
            modifier = Modifier
                .width(176.dp)
                .testTag("dashboard_kpi_bp")
        )
        KpiMetricCard(
            title = stringResource(id = R.string.vitals_metric_glucose),
            value = state.latestGlucoseText,
            unit = stringResource(id = R.string.vitals_unit_glucose),
            statusLabel = state.glucoseStatusLabel,
            status = state.glucoseStatus,
            icon = Icons.Default.Bloodtype,
            onClick = { onLogVitalClick(VitalType.BLOOD_GLUCOSE) },
            modifier = Modifier
                .width(176.dp)
                .testTag("dashboard_kpi_glucose")
        )
        KpiMetricCard(
            title = stringResource(id = R.string.vitals_metric_weight),
            value = state.latestWeightText,
            unit = stringResource(id = R.string.vitals_unit_weight),
            statusLabel = if (state.weightSeries.isEmpty()) "Tap to log" else "Avg ${state.weightAvgText}",
            status = if (state.weightSeries.isEmpty()) MetricClinicalStatus.NO_DATA else MetricClinicalStatus.OPTIMAL,
            icon = Icons.Default.FitnessCenter,
            onClick = { onLogVitalClick(VitalType.WEIGHT) },
            modifier = Modifier
                .width(164.dp)
                .testTag("dashboard_kpi_weight")
        )
    }
}

@Composable
private fun KpiMetricCard(
    title: String,
    value: String,
    unit: String,
    statusLabel: String,
    status: MetricClinicalStatus,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = when (status) {
        MetricClinicalStatus.OPTIMAL -> MaterialTheme.colorScheme.primary
        MetricClinicalStatus.ELEVATED -> MaterialTheme.colorScheme.tertiary
        MetricClinicalStatus.HIGH -> MaterialTheme.colorScheme.error
        MetricClinicalStatus.NO_DATA -> MaterialTheme.colorScheme.outline
    }

    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f))
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accent,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = unit,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            Surface(
                color = accent.copy(alpha = 0.14f),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun BloodPressureRechartsCard(
    points: List<BloodPressurePoint>,
    avgText: String,
    onLogBpClick: () -> Unit
) {
    var activeIndex by remember(points) { mutableStateOf<Int?>(null) }
    val systolicColor = MaterialTheme.colorScheme.primary
    val diastolicColor = MaterialTheme.colorScheme.tertiary
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    val guideColor = MaterialTheme.colorScheme.error.copy(alpha = 0.65f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("dashboard_bp_chart_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(id = R.string.dashboard_bp_chart_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(id = R.string.dashboard_bp_chart_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (points.isNotEmpty()) {
                    Text(
                        text = "Avg $avgText",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = systolicColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (points.isEmpty()) {
                DashboardEmptyChartState(
                    message = stringResource(id = R.string.dashboard_empty_bp),
                    buttonLabel = "Log Blood Pressure",
                    onClick = onLogBpClick
                )
            } else {
                val tooltipFormatter = remember { SimpleDateFormat("MMM d • h:mm a", Locale.getDefault()) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                ) {
                    Canvas(
                        modifier = Modifier
                            .matchParentSize()
                            .pointerInput(points) {
                                detectTapGestures { offset ->
                                    activeIndex = nearestPointIndex(offset.x, size.width.toFloat(), points.size)
                                }
                            }
                            .pointerInput(points) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        activeIndex = nearestPointIndex(offset.x, size.width.toFloat(), points.size)
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        activeIndex = nearestPointIndex(change.position.x, size.width.toFloat(), points.size)
                                    }
                                )
                            }
                    ) {
                        val padLeft = 38.dp.toPx()
                        val padRight = 18.dp.toPx()
                        val padTop = 26.dp.toPx()
                        val padBottom = 30.dp.toPx()
                        val chartW = size.width - padLeft - padRight
                        val chartH = size.height - padTop - padBottom

                        val allVals = points.flatMap { listOf(it.systolic, it.diastolic) }
                        val minV = ((allVals.minOrNull() ?: 60.0) - 10.0).coerceAtLeast(40.0)
                        val maxV = ((allVals.maxOrNull() ?: 140.0) + 15.0).coerceAtLeast(135.0)
                        val span = (maxV - minV).coerceAtLeast(10.0)

                        fun yFor(v: Double) = padTop + chartH * (1f - ((v - minV) / span).toFloat())
                        fun xFor(i: Int) = if (points.size <= 1) padLeft + chartW / 2f else padLeft + (i.toFloat() / (points.size - 1)) * chartW

                        drawGridAndYAxis(minV, maxV, padLeft, padRight, padTop, chartH, gridColor, labelColor)

                        if (120.0 in minV..maxV) {
                            drawReferenceLine(yFor(120.0), padLeft, size.width - padRight, guideColor, "120 Sys")
                        }
                        if (80.0 in minV..maxV) {
                            drawReferenceLine(yFor(80.0), padLeft, size.width - padRight, diastolicColor.copy(alpha = 0.55f), "80 Dia")
                        }

                        val sysOffsets = points.mapIndexed { i, pt -> Offset(xFor(i), yFor(pt.systolic)) }
                        val diaOffsets = points.mapIndexed { i, pt -> Offset(xFor(i), yFor(pt.diastolic)) }
                        drawBezierSeries(sysOffsets, padTop + chartH, systolicColor)
                        drawBezierSeries(diaOffsets, padTop + chartH, diastolicColor)
                        drawXAxisLabels(points.map { it.dateLabel }, ::xFor, size.height - 6.dp.toPx(), labelColor)

                        val idx = activeIndex
                        if (idx != null && idx in points.indices) {
                            val ax = xFor(idx)
                            drawLine(
                                color = systolicColor.copy(alpha = 0.5f),
                                start = Offset(ax, padTop),
                                end = Offset(ax, padTop + chartH),
                                strokeWidth = 1.5.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                            )
                            drawHighlightDot(sysOffsets[idx], systolicColor)
                            drawHighlightDot(diaOffsets[idx], diastolicColor)
                        }
                    }

                    activeIndex?.let { idx ->
                        if (idx in points.indices) {
                            val pt = points[idx]
                            DashboardTooltipBubble(
                                dateText = tooltipFormatter.format(Date(pt.timestamp)),
                                valueText = "${pt.systolic.roundToInt()} / ${pt.diastolic.roundToInt()} mmHg",
                                subText = if (pt.fromFirestore) "Cloud Firestore" else "Local Cache",
                                accent = systolicColor,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 2.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        ChartLegendDot(systolicColor, stringResource(id = R.string.vitals_legend_systolic))
                        ChartLegendDot(diastolicColor, stringResource(id = R.string.vitals_legend_diastolic))
                    }
                    Text(
                        text = stringResource(id = R.string.vitals_ref_bp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun BloodGlucoseRechartsCard(
    points: List<GlucosePoint>,
    avgText: String,
    onLogGlucoseClick: () -> Unit
) {
    var activeIndex by remember(points) { mutableStateOf<Int?>(null) }
    val glucoseColor = MaterialTheme.colorScheme.tertiary
    val optimalBandColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.09f)
    val fastingLimitColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    val highLimitColor = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("dashboard_glucose_chart_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(id = R.string.dashboard_glucose_chart_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(id = R.string.dashboard_glucose_chart_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (points.isNotEmpty()) {
                    Text(
                        text = "Avg $avgText",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = glucoseColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (points.isEmpty()) {
                DashboardEmptyChartState(
                    message = stringResource(id = R.string.dashboard_empty_glucose),
                    buttonLabel = "Log Blood Glucose",
                    onClick = onLogGlucoseClick
                )
            } else {
                val tooltipFormatter = remember { SimpleDateFormat("MMM d • h:mm a", Locale.getDefault()) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                ) {
                    Canvas(
                        modifier = Modifier
                            .matchParentSize()
                            .pointerInput(points) {
                                detectTapGestures { offset ->
                                    activeIndex = nearestPointIndex(offset.x, size.width.toFloat(), points.size)
                                }
                            }
                            .pointerInput(points) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        activeIndex = nearestPointIndex(offset.x, size.width.toFloat(), points.size)
                                    },
                                    onDrag = { change, _ ->
                                        change.consume()
                                        activeIndex = nearestPointIndex(change.position.x, size.width.toFloat(), points.size)
                                    }
                                )
                            }
                    ) {
                        val padLeft = 38.dp.toPx()
                        val padRight = 18.dp.toPx()
                        val padTop = 26.dp.toPx()
                        val padBottom = 30.dp.toPx()
                        val chartW = size.width - padLeft - padRight
                        val chartH = size.height - padTop - padBottom

                        val allVals = points.map { it.glucoseMgDl }
                        val minV = ((allVals.minOrNull() ?: 70.0) - 15.0).coerceAtLeast(40.0)
                        val maxV = ((allVals.maxOrNull() ?: 140.0) + 20.0).coerceAtLeast(155.0)
                        val span = (maxV - minV).coerceAtLeast(20.0)

                        fun yFor(v: Double) = padTop + chartH * (1f - ((v - minV) / span).toFloat())
                        fun xFor(i: Int) = if (points.size <= 1) padLeft + chartW / 2f else padLeft + (i.toFloat() / (points.size - 1)) * chartW

                        // Shaded normal fasting glucose zone (70 - 99 mg/dL)
                        val topTargetY = yFor(99.0.coerceIn(minV, maxV))
                        val bottomTargetY = yFor(70.0.coerceIn(minV, maxV))
                        if (bottomTargetY > topTargetY) {
                            drawRect(
                                color = optimalBandColor,
                                topLeft = Offset(padLeft, topTargetY),
                                size = Size(chartW, bottomTargetY - topTargetY)
                            )
                        }

                        drawGridAndYAxis(minV, maxV, padLeft, padRight, padTop, chartH, gridColor, labelColor)

                        if (100.0 in minV..maxV) {
                            drawReferenceLine(yFor(100.0), padLeft, size.width - padRight, fastingLimitColor, "100 Fasting")
                        }
                        if (140.0 in minV..maxV) {
                            drawReferenceLine(yFor(140.0), padLeft, size.width - padRight, highLimitColor, "140 Limit")
                        }

                        val gluOffsets = points.mapIndexed { i, pt -> Offset(xFor(i), yFor(pt.glucoseMgDl)) }
                        drawBezierSeries(gluOffsets, padTop + chartH, glucoseColor)
                        drawXAxisLabels(points.map { it.dateLabel }, ::xFor, size.height - 6.dp.toPx(), labelColor)

                        val idx = activeIndex
                        if (idx != null && idx in points.indices) {
                            val ax = xFor(idx)
                            drawLine(
                                color = glucoseColor.copy(alpha = 0.5f),
                                start = Offset(ax, padTop),
                                end = Offset(ax, padTop + chartH),
                                strokeWidth = 1.5.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                            )
                            drawHighlightDot(gluOffsets[idx], glucoseColor)
                        }
                    }

                    activeIndex?.let { idx ->
                        if (idx in points.indices) {
                            val pt = points[idx]
                            DashboardTooltipBubble(
                                dateText = tooltipFormatter.format(Date(pt.timestamp)),
                                valueText = "${pt.glucoseMgDl.roundToInt()} mg/dL",
                                subText = pt.notes.ifBlank { if (pt.fromFirestore) "Cloud Firestore" else "Local Cache" },
                                accent = glucoseColor,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 2.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ChartLegendDot(glucoseColor, stringResource(id = R.string.vitals_legend_glucose))
                    Text(
                        text = stringResource(id = R.string.vitals_ref_glucose),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun WeightRechartsCard(
    points: List<WeightPoint>,
    avgText: String,
    onLogWeightClick: () -> Unit
) {
    var activeIndex by remember(points) { mutableStateOf<Int?>(null) }
    val weightColor = MaterialTheme.colorScheme.secondary
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("dashboard_weight_chart_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(id = R.string.dashboard_weight_chart_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(id = R.string.dashboard_weight_chart_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = onLogWeightClick,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("Avg $avgText", style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            val tooltipFormatter = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(175.dp)
            ) {
                Canvas(
                    modifier = Modifier
                        .matchParentSize()
                        .pointerInput(points) {
                            detectTapGestures { offset ->
                                activeIndex = nearestPointIndex(offset.x, size.width.toFloat(), points.size)
                            }
                        }
                ) {
                    val padLeft = 38.dp.toPx()
                    val padRight = 18.dp.toPx()
                    val padTop = 22.dp.toPx()
                    val padBottom = 28.dp.toPx()
                    val chartW = size.width - padLeft - padRight
                    val chartH = size.height - padTop - padBottom

                    val allVals = points.map { it.weightKg }
                    val minV = (allVals.minOrNull() ?: 50.0) - 2.0
                    val maxV = (allVals.maxOrNull() ?: 90.0) + 2.0
                    val span = (maxV - minV).coerceAtLeast(2.0)

                    fun yFor(v: Double) = padTop + chartH * (1f - ((v - minV) / span).toFloat())
                    fun xFor(i: Int) = if (points.size <= 1) padLeft + chartW / 2f else padLeft + (i.toFloat() / (points.size - 1)) * chartW

                    drawGridAndYAxis(minV, maxV, padLeft, padRight, padTop, chartH, gridColor, labelColor)
                    val offsets = points.mapIndexed { i, pt -> Offset(xFor(i), yFor(pt.weightKg)) }
                    drawBezierSeries(offsets, padTop + chartH, weightColor)
                    drawXAxisLabels(points.map { it.dateLabel }, ::xFor, size.height - 6.dp.toPx(), labelColor)

                    val idx = activeIndex
                    if (idx != null && idx in points.indices) {
                        drawHighlightDot(offsets[idx], weightColor)
                    }
                }

                activeIndex?.let { idx ->
                    if (idx in points.indices) {
                        val pt = points[idx]
                        DashboardTooltipBubble(
                            dateText = tooltipFormatter.format(Date(pt.timestamp)),
                            valueText = String.format(Locale.US, "%.1f kg", pt.weightKg),
                            subText = if (pt.fromFirestore) "Cloud Firestore" else "Local Cache",
                            accent = weightColor,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardEmptyChartState(
    message: String,
    buttonLabel: String,
    onClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FilledTonalButton(
                onClick = onClick,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(buttonLabel, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun DashboardTooltipBubble(
    dateText: String,
    valueText: String,
    subText: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.border(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
            RoundedCornerShape(10.dp)
        ),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(dateText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(valueText, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = accent)
            Text(
                text = subText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ChartLegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun DrawScope.drawGridAndYAxis(
    minV: Double,
    maxV: Double,
    padLeft: Float,
    padRight: Float,
    padTop: Float,
    chartH: Float,
    gridColor: Color,
    labelColor: Color
) {
    val steps = 3
    val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
    val span = (maxV - minV).coerceAtLeast(1.0)
    val paint = android.graphics.Paint().apply {
        color = labelColor.toArgb()
        textSize = 10.sp.toPx()
        textAlign = android.graphics.Paint.Align.RIGHT
        isAntiAlias = true
    }

    for (i in 0..steps) {
        val y = padTop + (chartH / steps) * i
        val v = maxV - (span / steps) * i
        drawLine(
            color = gridColor,
            start = Offset(padLeft, y),
            end = Offset(size.width - padRight, y),
            strokeWidth = 1.dp.toPx(),
            pathEffect = dash
        )
        drawContext.canvas.nativeCanvas.drawText(
            "${v.roundToInt()}",
            padLeft - 8.dp.toPx(),
            y + 3.dp.toPx(),
            paint
        )
    }
}

private fun DrawScope.drawReferenceLine(
    y: Float,
    startX: Float,
    endX: Float,
    color: Color,
    label: String
) {
    drawLine(
        color = color,
        start = Offset(startX, y),
        end = Offset(endX, y),
        strokeWidth = 1.2.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 6f), 0f)
    )
    drawContext.canvas.nativeCanvas.drawText(
        label,
        endX,
        y - 4.dp.toPx(),
        android.graphics.Paint().apply {
            this.color = color.toArgb()
            textSize = 9.sp.toPx()
            textAlign = android.graphics.Paint.Align.RIGHT
            isAntiAlias = true
        }
    )
}

private fun DrawScope.drawBezierSeries(
    points: List<Offset>,
    baseY: Float,
    color: Color
) {
    if (points.isEmpty()) return

    val linePath = Path()
    val areaPath = Path()
    linePath.moveTo(points.first().x, points.first().y)
    areaPath.moveTo(points.first().x, baseY)
    areaPath.lineTo(points.first().x, points.first().y)

    for (i in 0 until points.size - 1) {
        val p0 = points[i]
        val p1 = points[i + 1]
        val cx = p0.x + (p1.x - p0.x) / 2f
        linePath.cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
        areaPath.cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
    }
    areaPath.lineTo(points.last().x, baseY)
    areaPath.close()

    drawPath(
        path = areaPath,
        brush = Brush.verticalGradient(
            colors = listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0.02f)),
            startY = points.minOfOrNull { it.y } ?: 0f,
            endY = baseY
        )
    )
    drawPath(
        path = linePath,
        color = color,
        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
    )

    points.forEach { pt ->
        drawCircle(color = Color.White, radius = 3.2.dp.toPx(), center = pt)
        drawCircle(color = color, radius = 2.2.dp.toPx(), center = pt)
    }
}

private fun DrawScope.drawHighlightDot(center: Offset, color: Color) {
    drawCircle(color = color.copy(alpha = 0.25f), radius = 8.dp.toPx(), center = center)
    drawCircle(color = Color.White, radius = 5.dp.toPx(), center = center)
    drawCircle(color = color, radius = 3.5.dp.toPx(), center = center)
}

private fun DrawScope.drawXAxisLabels(
    labels: List<String>,
    xFor: (Int) -> Float,
    baselineY: Float,
    labelColor: Color
) {
    val paint = android.graphics.Paint().apply {
        color = labelColor.toArgb()
        textSize = 10.sp.toPx()
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
    }
    labels.forEachIndexed { index, label ->
        val show = labels.size <= 5 || index == 0 || index == labels.lastIndex || index == labels.size / 2
        if (show) {
            drawContext.canvas.nativeCanvas.drawText(label, xFor(index), baselineY, paint)
        }
    }
}

private fun nearestPointIndex(touchX: Float, totalWidth: Float, count: Int): Int {
    if (count <= 1) return 0
    val ratio = (touchX / totalWidth.coerceAtLeast(1f)).coerceIn(0f, 1f)
    return (ratio * (count - 1)).roundToInt().coerceIn(0, count - 1)
}
