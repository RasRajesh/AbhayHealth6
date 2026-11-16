package com.example.abhayhealth.feature.trends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.abhayhealth.core.common.DefaultDispatcherProvider
import com.example.abhayhealth.core.common.DispatcherProvider
import com.example.abhayhealth.data.remote.FirestoreHealthRepository
import com.example.abhayhealth.data.remote.FirestoreSyncState
import com.example.abhayhealth.data.remote.MedicalRecordDoc
import com.example.abhayhealth.data.remote.VitalReadingDoc
import com.example.abhayhealth.domain.model.AuthState
import com.example.abhayhealth.domain.repository.AuthRepository
import com.example.abhayhealth.feature.records.ExtractedMedicalInfo
import com.example.abhayhealth.feature.records.MedicalFieldParser
import com.example.abhayhealth.feature.records.MedicalRecordRepository
import com.example.abhayhealth.feature.symptoms.SymptomLogEntity
import com.example.abhayhealth.feature.symptoms.SymptomLogRepository
import com.example.abhayhealth.feature.vitals.VitalReadingEntity
import com.example.abhayhealth.feature.vitals.VitalReadingRepository
import com.example.abhayhealth.feature.vitals.VitalType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

data class BiomarkerPoint(
    val recordDate: String,
    val timestamp: Long,
    val value: String,
    val numericValue: Double?,
    val unit: String
)

data class BiomarkerTrend(
    val biomarkerName: String,
    val latestValue: String,
    val numericValue: Double?,
    val unit: String,
    val referenceRange: String,
    val category: String, // "LABS" or "VITALS"
    val clinicalGuidelineTag: String? = null,
    val deltaFromPrevious: String? = null,
    val points: List<BiomarkerPoint>
)

data class DualMetricPoint(
    val dateLabel: String,
    val timestamp: Long,
    val value1: Double,
    val value2: Double
)

data class DualMetricComparison(
    val metric1Name: String,
    val metric1Unit: String,
    val metric2Name: String,
    val metric2Unit: String,
    val points: List<DualMetricPoint>,
    val correlationInsight: String,
    val isSampleData: Boolean = false
)

enum class DashboardTimeRange(val shortLabel: String, val days: Int?) {
    DAYS_7("7D", 7),
    DAYS_30("30D", 30),
    DAYS_90("90D", 90),
    ALL("All", null)
}

enum class MetricClinicalStatus {
    OPTIMAL,
    ELEVATED,
    HIGH,
    NO_DATA
}

data class BloodPressurePoint(
    val timestamp: Long,
    val dateLabel: String,
    val systolic: Double,
    val diastolic: Double,
    val notes: String,
    val fromFirestore: Boolean
)

data class GlucosePoint(
    val timestamp: Long,
    val dateLabel: String,
    val glucoseMgDl: Double,
    val notes: String,
    val fromFirestore: Boolean
)

data class WeightPoint(
    val timestamp: Long,
    val dateLabel: String,
    val weightKg: Double,
    val notes: String,
    val fromFirestore: Boolean
)

data class HealthDashboardUiState(
    val selectedRange: DashboardTimeRange = DashboardTimeRange.ALL,
    val syncState: FirestoreSyncState = FirestoreSyncState(),
    val bloodPressureSeries: List<BloodPressurePoint> = emptyList(),
    val glucoseSeries: List<GlucosePoint> = emptyList(),
    val weightSeries: List<WeightPoint> = emptyList(),
    val latestBpText: String = "--/--",
    val bpAvgText: String = "--",
    val bpStatus: MetricClinicalStatus = MetricClinicalStatus.NO_DATA,
    val bpStatusLabel: String = "No data",
    val latestGlucoseText: String = "--",
    val glucoseAvgText: String = "--",
    val glucoseStatus: MetricClinicalStatus = MetricClinicalStatus.NO_DATA,
    val glucoseStatusLabel: String = "No data",
    val latestWeightText: String = "--",
    val weightAvgText: String = "--",
    val firestorePointsCount: Int = 0
)

class TrendsViewModel(
    private val medicalRecordRepository: MedicalRecordRepository? = null,
    private val authRepository: AuthRepository? = null,
    private val symptomLogRepository: SymptomLogRepository? = null,
    private val vitalReadingRepository: VitalReadingRepository? = null,
    private val dispatcherProvider: DispatcherProvider = DefaultDispatcherProvider(),
    private val firestoreHealthRepository: FirestoreHealthRepository? = null
) : ViewModel() {

    private val _labTrends = MutableStateFlow<List<BiomarkerTrend>>(emptyList())
    val labTrends: StateFlow<List<BiomarkerTrend>> = _labTrends.asStateFlow()

    private val _vitalsTrends = MutableStateFlow<List<BiomarkerTrend>>(emptyList())
    val vitalsTrends: StateFlow<List<BiomarkerTrend>> = _vitalsTrends.asStateFlow()

    private val _biomarkerTrends = MutableStateFlow<List<BiomarkerTrend>>(emptyList())
    val biomarkerTrends: StateFlow<List<BiomarkerTrend>> = _biomarkerTrends.asStateFlow()

    private val _selectedTimeRange = MutableStateFlow(DashboardTimeRange.ALL)
    val selectedTimeRange: StateFlow<DashboardTimeRange> = _selectedTimeRange.asStateFlow()

    private val _dashboardState = MutableStateFlow(HealthDashboardUiState())
    val dashboardState: StateFlow<HealthDashboardUiState> = _dashboardState.asStateFlow()

    private val _showAddVitalDialog = MutableStateFlow(false)
    val showAddVitalDialog: StateFlow<Boolean> = _showAddVitalDialog.asStateFlow()

    private val _initialVitalMetric = MutableStateFlow(VitalType.BLOOD_PRESSURE)
    val initialVitalMetric: StateFlow<VitalType> = _initialVitalMetric.asStateFlow()

    private val _comparisonMetric1 = MutableStateFlow("Weight")
    val comparisonMetric1: StateFlow<String> = _comparisonMetric1.asStateFlow()

    private val _comparisonMetric2 = MutableStateFlow("Blood Pressure (Systolic)")
    val comparisonMetric2: StateFlow<String> = _comparisonMetric2.asStateFlow()

    private val _availableComparisonMetrics = MutableStateFlow(
        listOf(
            "Weight",
            "Blood Pressure (Systolic)",
            "Blood Pressure (Diastolic)",
            "Blood Glucose",
            "Heart Rate",
            "SpO2"
        )
    )
    val availableComparisonMetrics: StateFlow<List<String>> = _availableComparisonMetrics.asStateFlow()

    private val _dualComparison = MutableStateFlow(
        createDefaultComparison("Weight", "Blood Pressure (Systolic)")
    )
    val dualComparison: StateFlow<DualMetricComparison> = _dualComparison.asStateFlow()

    private val _symptoms = MutableStateFlow<List<SymptomLogEntity>>(emptyList())
    val symptoms: StateFlow<List<SymptomLogEntity>> = _symptoms.asStateFlow()

    private val _showAddSymptomDialog = MutableStateFlow(false)
    val showAddSymptomDialog: StateFlow<Boolean> = _showAddSymptomDialog.asStateFlow()

    init {
        observeBiomarkersAndDashboard()
        observeSymptoms()
    }

    fun setTimeRange(range: DashboardTimeRange) {
        _selectedTimeRange.value = range
    }

    fun openAddVitalDialog(metric: VitalType = VitalType.BLOOD_PRESSURE) {
        _initialVitalMetric.value = metric
        _showAddVitalDialog.value = true
    }

    fun dismissAddVitalDialog() {
        _showAddVitalDialog.value = false
    }

    fun logDashboardVital(
        type: VitalType,
        systolic: Double?,
        diastolic: Double?,
        weightKg: Double?,
        glucoseMgDl: Double?,
        notes: String
    ) {
        val uid = authRepository?.currentUser?.uid?.ifBlank { null } ?: return
        viewModelScope.launch(dispatcherProvider.io) {
            val now = System.currentTimeMillis()
            val baseEntity = VitalReadingEntity(
                accountUid = uid,
                type = type.name,
                systolic = systolic,
                diastolic = diastolic,
                weightKg = weightKg,
                glucoseMgDl = glucoseMgDl,
                timestamp = now,
                notes = notes
            )
            val insertedId = vitalReadingRepository?.insertReading(baseEntity) ?: now
            firestoreHealthRepository?.enqueueVitalReading(baseEntity.copy(id = insertedId))
            withContext(dispatcherProvider.main) {
                _showAddVitalDialog.value = false
            }
        }
    }

    private fun observeBiomarkersAndDashboard() {
        viewModelScope.launch(dispatcherProvider.main) {
            val authRepo = authRepository ?: return@launch
            authRepo.authState.collectLatest { state ->
                val uid = when (state) {
                    is AuthState.Authenticated -> state.user.uid
                    else -> authRepo.currentUser?.uid?.ifBlank { null }
                }
                if (uid == null) {
                    _biomarkerTrends.value = emptyList()
                    _labTrends.value = emptyList()
                    _vitalsTrends.value = emptyList()
                    _dashboardState.value = HealthDashboardUiState()
                    return@collectLatest
                }
                observeForUid(uid)
            }
        }
    }

    private suspend fun observeForUid(uid: String) {
        val localExtractedFlow: Flow<List<ExtractedMedicalInfo>> =
            medicalRecordRepository?.getAllExtractedInfo(uid) ?: flowOf(emptyList())
        val localVitalsFlow: Flow<List<VitalReadingEntity>> =
            vitalReadingRepository?.getAllReadings(uid) ?: flowOf(emptyList())
        val firestoreVitalsFlow: Flow<List<VitalReadingDoc>> =
            firestoreHealthRepository?.observeVitals(uid)?.catch { emit(emptyList()) } ?: flowOf(emptyList())
        val firestoreRecordsFlow: Flow<List<MedicalRecordDoc>> =
            firestoreHealthRepository?.observeMedicalRecords(uid)?.catch { emit(emptyList()) } ?: flowOf(emptyList())
        val syncStateFlow: Flow<FirestoreSyncState> =
            firestoreHealthRepository?.syncState ?: flowOf(FirestoreSyncState())

        combine(
            combine(localExtractedFlow, firestoreRecordsFlow) { localInfo, cloudRecords ->
                mergeExtractedInfo(localInfo, cloudRecords, uid)
            },
            combine(localVitalsFlow, firestoreVitalsFlow) { localVitals, cloudVitals ->
                mergeVitals(localVitals, cloudVitals, uid)
            },
            _selectedTimeRange,
            syncStateFlow
        ) { mergedInfos, mergedVitals, selectedRange, syncState ->
            QuadInput(mergedInfos, mergedVitals, selectedRange, syncState)
        }.collectLatest { (infoList, vitalsWithSource, selectedRange, syncState) ->
            val rawVitals = vitalsWithSource.map { it.entity }
            val firestoreVitalKeys = vitalsWithSource.filter { it.fromFirestore }.map { it.mergeKey }.toSet()

            _dashboardState.value = buildDashboardState(
                vitals = rawVitals,
                extractedInfos = infoList,
                firestoreVitalKeys = firestoreVitalKeys,
                selectedRange = selectedRange,
                syncState = syncState
            )

            updateBiomarkerTrends(infoList, rawVitals)
        }
    }

    private fun updateBiomarkerTrends(
        infoList: List<ExtractedMedicalInfo>,
        vitalsList: List<VitalReadingEntity>
    ) {
        val trendsMap = mutableMapOf<String, MutableList<BiomarkerPoint>>()
        val rangeMap = mutableMapOf<String, String>()
        val sdf = SimpleDateFormat("dd MMM", Locale.getDefault())

        infoList.forEach { info ->
            if (info.structuredResultsJson.isNotBlank() && info.structuredResultsJson != "[]") {
                val results = MedicalFieldParser.deserializeResults(info.structuredResultsJson)
                val dateStr = info.reportDate ?: sdf.format(Date(info.updatedAtTimestamp))
                val timestamp = info.reportDate?.let { MedicalFieldParser.parseDateToTimestamp(it) }
                    ?: info.updatedAtTimestamp

                results.forEach { result ->
                    val testName = result.testName.trim()
                    val valStr = result.value.trim()

                    if (testName.isNotBlank() && valStr.isNotBlank()) {
                        val numValue = extractNumericValue(valStr)
                        val list = trendsMap.getOrPut(testName) { mutableListOf() }
                        list.add(
                            BiomarkerPoint(
                                recordDate = dateStr,
                                timestamp = timestamp,
                                value = valStr,
                                numericValue = numValue,
                                unit = result.unit.trim()
                            )
                        )
                        if (result.referenceRange.isNotBlank()) {
                            rangeMap[testName] = result.referenceRange
                        }
                    }
                }
            }
        }

        vitalsList.forEach { vital ->
            val dateStr = sdf.format(Date(vital.timestamp))
            when (vital.type) {
                VitalType.BLOOD_PRESSURE.name -> {
                    if (vital.systolic != null) {
                        val sList = trendsMap.getOrPut("Blood Pressure (Systolic)") { mutableListOf() }
                        sList.add(
                            BiomarkerPoint(
                                recordDate = dateStr,
                                timestamp = vital.timestamp,
                                value = "${vital.systolic.toInt()}",
                                numericValue = vital.systolic,
                                unit = "mmHg"
                            )
                        )
                        rangeMap["Blood Pressure (Systolic)"] = "< 120 mmHg"
                    }
                    if (vital.diastolic != null) {
                        val dList = trendsMap.getOrPut("Blood Pressure (Diastolic)") { mutableListOf() }
                        dList.add(
                            BiomarkerPoint(
                                recordDate = dateStr,
                                timestamp = vital.timestamp,
                                value = "${vital.diastolic.toInt()}",
                                numericValue = vital.diastolic,
                                unit = "mmHg"
                            )
                        )
                        rangeMap["Blood Pressure (Diastolic)"] = "< 80 mmHg"
                    }
                }
                VitalType.BLOOD_GLUCOSE.name -> {
                    if (vital.glucoseMgDl != null) {
                        val gList = trendsMap.getOrPut("Blood Glucose") { mutableListOf() }
                        gList.add(
                            BiomarkerPoint(
                                recordDate = dateStr,
                                timestamp = vital.timestamp,
                                value = "${vital.glucoseMgDl.roundToInt()}",
                                numericValue = vital.glucoseMgDl,
                                unit = "mg/dL"
                            )
                        )
                        rangeMap["Blood Glucose"] = "70 – 99 mg/dL (Fasting)"
                    }
                }
                VitalType.WEIGHT.name -> {
                    if (vital.weightKg != null) {
                        val wList = trendsMap.getOrPut("Weight") { mutableListOf() }
                        wList.add(
                            BiomarkerPoint(
                                recordDate = dateStr,
                                timestamp = vital.timestamp,
                                value = "${vital.weightKg}",
                                numericValue = vital.weightKg,
                                unit = "kg"
                            )
                        )
                        rangeMap["Weight"] = "Healthy BMI Range"
                    }
                }
            }
        }

        val allTrends = trendsMap.map { (name, points) ->
            val sortedPoints = points.sortedBy { it.timestamp }
            val latest = sortedPoints.lastOrNull()
            val category = categorizeBiomarker(name)
            val latestNum = latest?.numericValue
            val guidelineTag = evaluateClinicalGuideline(name, latestNum)

            val previous = if (sortedPoints.size >= 2) sortedPoints[sortedPoints.size - 2] else null
            val deltaStr = if (latestNum != null && previous?.numericValue != null) {
                val diff = latestNum - previous.numericValue
                val formattedDiff = String.format(Locale.US, "%.1f", abs(diff))
                when {
                    diff > 0.05 -> "↑ +$formattedDiff ${latest.unit}".trim()
                    diff < -0.05 -> "↓ -$formattedDiff ${latest.unit}".trim()
                    else -> "Stable"
                }
            } else null

            BiomarkerTrend(
                biomarkerName = name,
                latestValue = latest?.value ?: "--",
                numericValue = latestNum,
                unit = latest?.unit ?: "",
                referenceRange = rangeMap[name] ?: "",
                category = category,
                clinicalGuidelineTag = guidelineTag,
                deltaFromPrevious = deltaStr,
                points = sortedPoints
            )
        }.sortedByDescending { it.points.size }

        _biomarkerTrends.value = allTrends
        _labTrends.value = allTrends.filter { it.category == "LABS" }
        _vitalsTrends.value = allTrends.filter { it.category == "VITALS" }

        val dynamicMetrics = mutableListOf(
            "Weight",
            "Blood Pressure (Systolic)",
            "Blood Pressure (Diastolic)",
            "Blood Glucose"
        )
        allTrends.forEach { t ->
            if (!dynamicMetrics.contains(t.biomarkerName)) {
                dynamicMetrics.add(t.biomarkerName)
            }
        }
        _availableComparisonMetrics.value = dynamicMetrics
        updateDualComparison()
    }

    fun setComparisonMetric1(metric: String) {
        _comparisonMetric1.value = metric
        updateDualComparison()
    }

    fun setComparisonMetric2(metric: String) {
        _comparisonMetric2.value = metric
        updateDualComparison()
    }

    private fun updateDualComparison() {
        val m1Name = _comparisonMetric1.value
        val m2Name = _comparisonMetric2.value
        val allTrends = _biomarkerTrends.value

        val t1 = allTrends.firstOrNull { it.biomarkerName.equals(m1Name, ignoreCase = true) }
        val t2 = allTrends.firstOrNull { it.biomarkerName.equals(m2Name, ignoreCase = true) }

        val p1 = t1?.points?.filter { it.numericValue != null } ?: emptyList()
        val p2 = t2?.points?.filter { it.numericValue != null } ?: emptyList()

        if (p1.size >= 2 && p2.size >= 2) {
            val mergedPoints = mutableListOf<DualMetricPoint>()
            p1.forEach { pt1 ->
                val matchingPt2 = p2.minByOrNull { abs(it.timestamp - pt1.timestamp) }
                if (matchingPt2 != null && pt1.numericValue != null && matchingPt2.numericValue != null) {
                    mergedPoints.add(
                        DualMetricPoint(
                            dateLabel = pt1.recordDate,
                            timestamp = pt1.timestamp,
                            value1 = pt1.numericValue,
                            value2 = matchingPt2.numericValue
                        )
                    )
                }
            }
            if (mergedPoints.size >= 2) {
                val insight = generateCorrelationInsight(m1Name, m2Name, mergedPoints)
                _dualComparison.value = DualMetricComparison(
                    metric1Name = m1Name,
                    metric1Unit = t1?.unit ?: "",
                    metric2Name = m2Name,
                    metric2Unit = t2?.unit ?: "",
                    points = mergedPoints.sortedBy { it.timestamp },
                    correlationInsight = insight,
                    isSampleData = false
                )
                return
            }
        }

        _dualComparison.value = createDefaultComparison(m1Name, m2Name)
    }

    private fun createDefaultComparison(m1: String, m2: String): DualMetricComparison {
        val (unit1, unit2) = when {
            m1.contains("Weight", true) && m2.contains("Pressure", true) -> Pair("kg", "mmHg")
            m1.contains("Pressure", true) && m2.contains("Weight", true) -> Pair("mmHg", "kg")
            m1.contains("Glucose", true) -> Pair("mg/dL", "mmHg")
            m2.contains("Glucose", true) -> Pair("kg", "mg/dL")
            m1.contains("Heart", true) || m1.contains("Pulse", true) -> Pair("BPM", "mmHg")
            else -> Pair("unit", "unit")
        }

        val samplePoints = listOf(
            DualMetricPoint("01 Oct", 1000L, 72.4, 128.0),
            DualMetricPoint("08 Oct", 2000L, 71.8, 126.0),
            DualMetricPoint("15 Oct", 3000L, 71.2, 123.0),
            DualMetricPoint("22 Oct", 4000L, 70.6, 121.0),
            DualMetricPoint("29 Oct", 5000L, 70.0, 118.0)
        )

        val insight = if ((m1.contains("Weight", true) && m2.contains("Pressure", true)) ||
            (m2.contains("Weight", true) && m1.contains("Pressure", true))) {
            "Clinical Evidence: High physiological correlation. A 2.4 kg reduction in body weight corresponds to a 10 mmHg reduction in systolic blood pressure. Maintaining target weight significantly reduces hypertension risk."
        } else {
            "Comparative Analytics: Tracking $m1 alongside $m2 on the same timeline reveals longitudinal trends and concurrent metric shifts."
        }

        return DualMetricComparison(
            metric1Name = m1,
            metric1Unit = unit1,
            metric2Name = m2,
            metric2Unit = unit2,
            points = samplePoints,
            correlationInsight = insight,
            isSampleData = true
        )
    }

    private fun generateCorrelationInsight(m1: String, m2: String, points: List<DualMetricPoint>): String {
        val first = points.first()
        val last = points.last()
        val delta1 = last.value1 - first.value1
        val delta2 = last.value2 - first.value2

        val dir1 = if (delta1 > 0) "increased" else if (delta1 < 0) "decreased" else "remained steady"
        val dir2 = if (delta2 > 0) "increased" else if (delta2 < 0) "decreased" else "remained steady"

        return if ((delta1 > 0 && delta2 > 0) || (delta1 < 0 && delta2 < 0)) {
            "Positive Correlation: Over this period, $m1 $dir1 while $m2 concurrently $dir2. Monitoring both metrics concurrently assists in comprehensive lifestyle and treatment efficacy assessment."
        } else {
            "Inverse or Independent Pattern: $m1 $dir1 while $m2 $dir2. Continue logging to observe long-term clinical stabilization."
        }
    }

    private fun observeSymptoms() {
        val symptomRepo = symptomLogRepository ?: return
        viewModelScope.launch(dispatcherProvider.main) {
            val authRepo = authRepository ?: return@launch
            authRepo.authState.collectLatest { state ->
                val uid = when (state) {
                    is AuthState.Authenticated -> state.user.uid
                    else -> authRepo.currentUser?.uid?.ifBlank { null }
                }
                if (uid == null) {
                    _symptoms.value = emptyList()
                    return@collectLatest
                }
                symptomRepo.getAllSymptoms(uid).collectLatest { list ->
                    _symptoms.value = list
                }
            }
        }
    }

    fun openAddSymptomDialog() {
        _showAddSymptomDialog.value = true
    }

    fun dismissAddSymptomDialog() {
        _showAddSymptomDialog.value = false
    }

    fun logSymptom(
        symptomName: String,
        severity: String,
        severityScore: Int,
        notes: String,
        triggers: String,
        durationMinutes: Int
    ) {
        val uid = authRepository?.currentUser?.uid?.ifBlank { null } ?: return
        val repo = symptomLogRepository ?: return

        viewModelScope.launch(dispatcherProvider.main) {
            val entity = SymptomLogEntity(
                accountUid = uid,
                symptomName = symptomName,
                severity = severity,
                severityScore = severityScore,
                timestamp = System.currentTimeMillis(),
                notes = notes,
                triggers = triggers,
                durationMinutes = durationMinutes
            )
            val insertedId = repo.logSymptom(entity)
            firestoreHealthRepository?.enqueueSymptomLog(entity.copy(id = insertedId))
            _showAddSymptomDialog.value = false
        }
    }

    fun deleteSymptom(symptom: SymptomLogEntity) {
        val repo = symptomLogRepository ?: return
        viewModelScope.launch(dispatcherProvider.main) {
            repo.deleteSymptom(symptom)
            firestoreHealthRepository?.enqueueDeleteSymptomLog(symptom.accountUid, symptom.id)
        }
    }

    private fun extractNumericValue(valStr: String): Double? {
        val cleaned = valStr.replace("<", "").replace(">", "").replace("≤", "").replace("≥", "").trim()
        val match = Regex("""(\d+(?:\.\d+)?)""").find(cleaned)
        return match?.groupValues?.get(1)?.toDoubleOrNull()
    }

    private fun categorizeBiomarker(name: String): String {
        val lower = name.lowercase()
        return if (lower.contains("bp") || lower.contains("blood pressure") ||
            lower.contains("temp") || lower.contains("systolic") ||
            lower.contains("diastolic") || lower.contains("pulse") ||
            lower.contains("heart rate") || lower.contains("weight") ||
            lower.contains("spo2") || lower.contains("oxygen") ||
            lower.contains("glucose") || lower.contains("blood sugar")
        ) {
            "VITALS"
        } else {
            "LABS"
        }
    }

    private fun evaluateClinicalGuideline(name: String, value: Double?): String? {
        if (value == null) return null
        val lower = name.lowercase()

        return when {
            lower.contains("glucose") || lower.contains("fbs") -> when {
                value < 100.0 -> "Normal FBS"
                value in 100.0..125.0 -> "Impaired Fasting"
                value >= 126.0 -> "Elevated FBS"
                else -> null
            }
            lower.contains("hba1c") -> when {
                value < 5.7 -> "Normal (<5.7%)"
                value in 5.7..6.4 -> "Prediabetes"
                value >= 6.5 -> "Elevated (>=6.5%)"
                else -> null
            }
            lower.contains("hemoglobin") || lower.contains("hb") -> when {
                value in 12.0..16.5 -> "Optimal"
                value < 12.0 -> "Low Hb"
                else -> "Elevated"
            }
            lower.contains("systolic") -> when {
                value < 120.0 -> "Optimal (<120)"
                value in 120.0..129.0 -> "Elevated (120-129)"
                value >= 130.0 -> "Stage 1 HTN"
                else -> null
            }
            else -> null
        }
    }

    private data class QuadInput(
        val infos: List<ExtractedMedicalInfo>,
        val vitals: List<VitalWithSource>,
        val range: DashboardTimeRange,
        val syncState: FirestoreSyncState
    )

    data class VitalWithSource(
        val entity: VitalReadingEntity,
        val fromFirestore: Boolean,
        val mergeKey: String
    )

    companion object {
        internal fun mergeVitals(
            localVitals: List<VitalReadingEntity>,
            cloudVitals: List<VitalReadingDoc>,
            uid: String
        ): List<VitalWithSource> {
            val merged = linkedMapOf<String, VitalWithSource>()

            cloudVitals.forEach { doc ->
                val resolvedId = if (doc.localVitalId > 0L) doc.localVitalId else {
                    doc.id.substringAfterLast('_').toLongOrNull() ?: 0L
                }
                val key = if (resolvedId > 0L) "id_$resolvedId" else "ts_${doc.type}_${doc.timestamp}"
                merged[key] = VitalWithSource(
                    entity = VitalReadingEntity(
                        id = resolvedId,
                        accountUid = uid,
                        type = doc.type,
                        systolic = doc.systolic,
                        diastolic = doc.diastolic,
                        weightKg = doc.weightKg,
                        glucoseMgDl = doc.glucoseMgDl,
                        timestamp = doc.timestamp,
                        notes = doc.notes
                    ),
                    fromFirestore = true,
                    mergeKey = key
                )
            }

            localVitals.forEach { local ->
                val key = if (local.id > 0L) "id_${local.id}" else "ts_${local.type}_${local.timestamp}"
                if (!merged.containsKey(key)) {
                    merged[key] = VitalWithSource(
                        entity = local,
                        fromFirestore = false,
                        mergeKey = key
                    )
                }
            }

            return merged.values.sortedBy { it.entity.timestamp }
        }

        internal fun mergeExtractedInfo(
            localInfos: List<ExtractedMedicalInfo>,
            cloudRecords: List<MedicalRecordDoc>,
            uid: String
        ): List<ExtractedMedicalInfo> {
            val byRecordId = linkedMapOf<Long, ExtractedMedicalInfo>()
            localInfos.forEach { byRecordId[it.recordId] = it }

            cloudRecords.forEach { doc ->
                if (!doc.structuredResultsJson.isNullOrBlank() && doc.structuredResultsJson != "[]") {
                    val recId = if (doc.localRecordId > 0L) doc.localRecordId else {
                        doc.id.substringAfterLast('_').toLongOrNull() ?: (doc.id.hashCode().toLong() and 0x7FFFFFFFL)
                    }
                    val existing = byRecordId[recId]
                    if (existing == null || doc.updatedAtTimestamp >= existing.updatedAtTimestamp) {
                        byRecordId[recId] = ExtractedMedicalInfo(
                            recordId = recId,
                            accountUid = uid,
                            rawText = doc.rawText.orEmpty(),
                            documentTitle = doc.documentTitle ?: doc.title,
                            reportDate = doc.reportDate,
                            structuredResultsJson = doc.structuredResultsJson,
                            reviewStatus = doc.reviewStatus,
                            processedPages = doc.processedPages.coerceAtLeast(1),
                            updatedAtTimestamp = doc.updatedAtTimestamp
                        )
                    }
                }
            }
            return byRecordId.values.toList()
        }

        internal fun buildDashboardState(
            vitals: List<VitalReadingEntity>,
            extractedInfos: List<ExtractedMedicalInfo>,
            firestoreVitalKeys: Set<String>,
            selectedRange: DashboardTimeRange,
            syncState: FirestoreSyncState,
            referenceNowMillis: Long = System.currentTimeMillis()
        ): HealthDashboardUiState {
            val cutoffMillis = selectedRange.days?.let { days ->
                referenceNowMillis - TimeUnit.DAYS.toMillis(days.toLong())
            } ?: Long.MIN_VALUE

            val dateFormatter = SimpleDateFormat("MMM d", Locale.getDefault())

            val bpPoints = mutableListOf<BloodPressurePoint>()
            val glucosePoints = mutableListOf<GlucosePoint>()
            val weightPoints = mutableListOf<WeightPoint>()

            vitals.sortedBy { it.timestamp }.forEach { vital ->
                val mergeKey = if (vital.id > 0L) "id_${vital.id}" else "ts_${vital.type}_${vital.timestamp}"
                val isFromFirestore = mergeKey in firestoreVitalKeys
                val dateLabel = dateFormatter.format(Date(vital.timestamp))

                when (vital.type) {
                    VitalType.BLOOD_PRESSURE.name -> {
                        val sys = vital.systolic
                        val dia = vital.diastolic
                        if (sys != null && dia != null && vital.timestamp >= cutoffMillis) {
                            bpPoints.add(
                                BloodPressurePoint(
                                    timestamp = vital.timestamp,
                                    dateLabel = dateLabel,
                                    systolic = sys,
                                    diastolic = dia,
                                    notes = vital.notes,
                                    fromFirestore = isFromFirestore
                                )
                            )
                        }
                    }
                    VitalType.BLOOD_GLUCOSE.name -> {
                        val glu = vital.glucoseMgDl
                        if (glu != null && vital.timestamp >= cutoffMillis) {
                            glucosePoints.add(
                                GlucosePoint(
                                    timestamp = vital.timestamp,
                                    dateLabel = dateLabel,
                                    glucoseMgDl = glu,
                                    notes = vital.notes,
                                    fromFirestore = isFromFirestore
                                )
                            )
                        }
                    }
                    VitalType.WEIGHT.name -> {
                        val wt = vital.weightKg
                        if (wt != null && vital.timestamp >= cutoffMillis) {
                            weightPoints.add(
                                WeightPoint(
                                    timestamp = vital.timestamp,
                                    dateLabel = dateLabel,
                                    weightKg = wt,
                                    notes = vital.notes,
                                    fromFirestore = isFromFirestore
                                )
                            )
                        }
                    }
                }
            }

            // Merge lab-extracted Blood Glucose & BP points if not already represented at the same timestamp window
            extractedInfos.forEach { info ->
                if (info.structuredResultsJson.isNotBlank() && info.structuredResultsJson != "[]") {
                    val timestamp = info.reportDate?.let { MedicalFieldParser.parseDateToTimestamp(it) }
                        ?: info.updatedAtTimestamp
                    if (timestamp >= cutoffMillis) {
                        val dateLabel = dateFormatter.format(Date(timestamp))
                        val results = MedicalFieldParser.deserializeResults(info.structuredResultsJson)
                        results.forEach { item ->
                            val lower = item.testName.lowercase()
                            val numeric = Regex("""(\d+(?:\.\d+)?)""").find(item.value)?.groupValues?.get(1)?.toDoubleOrNull()
                            if ((lower.contains("glucose") || lower.contains("blood sugar") ||
                                    lower.contains("fbs") || lower.contains("ppbs") || lower.contains("rbs")) &&
                                numeric != null && numeric in 20.0..800.0
                            ) {
                                val hasDuplicate = glucosePoints.any {
                                    abs(it.timestamp - timestamp) < TimeUnit.MINUTES.toMillis(5) &&
                                        abs(it.glucoseMgDl - numeric) < 0.5
                                }
                                if (!hasDuplicate) {
                                    glucosePoints.add(
                                        GlucosePoint(
                                            timestamp = timestamp,
                                            dateLabel = dateLabel,
                                            glucoseMgDl = numeric,
                                            notes = "${item.testName} (${info.documentTitle})",
                                            fromFirestore = true
                                        )
                                    )
                                }
                            } else if ((lower.contains("blood pressure") || lower == "bp")) {
                                val parts = item.value.split(Regex("""[/,\s]+""")).mapNotNull { it.toDoubleOrNull() }
                                if (parts.size >= 2) {
                                    val hasDuplicate = bpPoints.any {
                                        abs(it.timestamp - timestamp) < TimeUnit.MINUTES.toMillis(5) &&
                                            abs(it.systolic - parts[0]) < 0.5
                                    }
                                    if (!hasDuplicate) {
                                        bpPoints.add(
                                            BloodPressurePoint(
                                                timestamp = timestamp,
                                                dateLabel = dateLabel,
                                                systolic = parts[0],
                                                diastolic = parts[1],
                                                notes = "Lab Record: ${info.documentTitle}",
                                                fromFirestore = true
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val sortedBp = bpPoints.sortedBy { it.timestamp }
            val sortedGlucose = glucosePoints.sortedBy { it.timestamp }
            val sortedWeight = weightPoints.sortedBy { it.timestamp }

            val latestBp = sortedBp.lastOrNull()
            val (bpStatus, bpStatusLabel) = if (latestBp == null) {
                MetricClinicalStatus.NO_DATA to "No readings"
            } else when {
                latestBp.systolic < 120.0 && latestBp.diastolic < 80.0 ->
                    MetricClinicalStatus.OPTIMAL to "Normal (<120/80)"
                latestBp.systolic < 130.0 && latestBp.diastolic < 80.0 ->
                    MetricClinicalStatus.ELEVATED to "Elevated"
                else ->
                    MetricClinicalStatus.HIGH to "High (≥130/80)"
            }
            val bpAvgText = if (sortedBp.isNotEmpty()) {
                val avgSys = sortedBp.map { it.systolic }.average().roundToInt()
                val avgDia = sortedBp.map { it.diastolic }.average().roundToInt()
                "$avgSys/$avgDia mmHg"
            } else "--"

            val latestGlu = sortedGlucose.lastOrNull()
            val (gluStatus, gluStatusLabel) = if (latestGlu == null) {
                MetricClinicalStatus.NO_DATA to "No readings"
            } else when {
                latestGlu.glucoseMgDl < 100.0 ->
                    MetricClinicalStatus.OPTIMAL to "Optimal (<100)"
                latestGlu.glucoseMgDl < 140.0 ->
                    MetricClinicalStatus.ELEVATED to "Borderline (100–139)"
                else ->
                    MetricClinicalStatus.HIGH to "High (≥140)"
            }
            val gluAvgText = if (sortedGlucose.isNotEmpty()) {
                "${sortedGlucose.map { it.glucoseMgDl }.average().roundToInt()} mg/dL"
            } else "--"

            val latestWeight = sortedWeight.lastOrNull()
            val weightAvgText = if (sortedWeight.isNotEmpty()) {
                String.format(Locale.US, "%.1f kg", sortedWeight.map { it.weightKg }.average())
            } else "--"

            val firestoreTotal = sortedBp.count { it.fromFirestore } +
                sortedGlucose.count { it.fromFirestore } +
                sortedWeight.count { it.fromFirestore }

            return HealthDashboardUiState(
                selectedRange = selectedRange,
                syncState = syncState,
                bloodPressureSeries = sortedBp,
                glucoseSeries = sortedGlucose,
                weightSeries = sortedWeight,
                latestBpText = latestBp?.let { "${it.systolic.roundToInt()}/${it.diastolic.roundToInt()}" } ?: "--/--",
                bpAvgText = bpAvgText,
                bpStatus = bpStatus,
                bpStatusLabel = bpStatusLabel,
                latestGlucoseText = latestGlu?.let { "${it.glucoseMgDl.roundToInt()}" } ?: "--",
                glucoseAvgText = gluAvgText,
                glucoseStatus = gluStatus,
                glucoseStatusLabel = gluStatusLabel,
                latestWeightText = latestWeight?.let { String.format(Locale.US, "%.1f", it.weightKg) } ?: "--",
                weightAvgText = weightAvgText,
                firestorePointsCount = firestoreTotal
            )
        }
    }
}
