package com.musicaia.diagnostico

import android.content.Context
import android.os.Build
import com.musicaia.modelo.BardoCapabilityRegistry
import java.io.File

/**
 * Evidencia local del ciclo real de un modelo, ligada al archivo, la APK y el dispositivo.
 *
 * La presencia del archivo no implica hash verificado; el hash no implica carga;
 * la carga no implica inferencia; y una inferencia correcta no equivale a una prueba
 * física completa. Solo un procedimiento QA explícito puede registrar DEVICE_VERIFIED.
 */
object BardoCapabilityEvidenceStore {
    const val MAX_INFERENCE_AGE_MILLIS = 30L * 24L * 60L * 60L * 1_000L

    data class Snapshot(
        val status: BardoCapabilityRegistry.Status,
        val detail: String,
        val lastInferenceAtEpochMillis: Long? = null,
        val evidenceReference: String? = null
    )

    private data class Identity(
        val expectedSha256: String,
        val fileLength: Long,
        val fileLastModified: Long,
        val appVersionCode: Long,
        val deviceModel: String
    )

    private val modelIdPattern = Regex("^[a-z0-9_]{2,48}$")
    private val shaPattern = Regex("^[a-f0-9]{64}$")
    private val deviceEvidencePattern = Regex("^bardo-device-test://[A-Za-z0-9._/-]{3,180}$")

    fun inspect(
        context: Context,
        modelId: String,
        file: File,
        expectedSha256: String,
        expectedSizeBytes: Long
    ): Snapshot {
        require(modelIdPattern.matches(modelId))
        require(shaPattern.matches(expectedSha256))
        if (!file.isFile || file.length() != expectedSizeBytes) {
            return Snapshot(
                BardoCapabilityRegistry.Status.MODEL_PRESENT,
                "El archivo no coincide con el tamaño esperado; la evidencia anterior no se reutiliza."
            )
        }
        val app = context.applicationContext
        val identity = identity(app, file, expectedSha256)
        val prefs = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val prefix = "$modelId."
        if (!identityMatches(prefs, prefix, identity)) {
            return Snapshot(
                BardoCapabilityRegistry.Status.MODEL_PRESENT,
                "Modelo presente por tamaño; no hay hash verificado vigente para este archivo, APK y dispositivo."
            )
        }

        val verified = prefs.getString(prefix + "verified_sha256", null) == expectedSha256
        if (!verified) {
            return Snapshot(
                BardoCapabilityRegistry.Status.MODEL_PRESENT,
                "Modelo presente por tamaño; falta verificar SHA-256 antes de cargar."
            )
        }
        val loaded = prefs.getString(prefix + "loaded_sha256", null) == expectedSha256 &&
            prefs.getLong(prefix + "loaded_at", 0L) > 0L
        if (!loaded) {
            return Snapshot(
                BardoCapabilityRegistry.Status.VERIFIED,
                "SHA-256 verificado para este archivo, APK y dispositivo; todavía no consta una carga real."
            )
        }

        val inferenceAt = prefs.getLong(prefix + "inference_at", 0L)
        val inferenceFresh = prefs.getString(prefix + "inference_sha256", null) == expectedSha256 &&
            prefs.getBoolean(prefix + "inference_output_validated", false) &&
            inferenceAt > 0L &&
            System.currentTimeMillis() - inferenceAt in 0L..MAX_INFERENCE_AGE_MILLIS
        if (!inferenceFresh) {
            return Snapshot(
                BardoCapabilityRegistry.Status.LOADABLE,
                "La carga real se completó; no hay una inferencia válida reciente para esta versión de BARDO.",
                inferenceAt.takeIf { it > 0L }
            )
        }

        val deviceVerifiedAt = prefs.getLong(prefix + "device_verified_at", 0L)
        val deviceVerified = prefs.getString(prefix + "device_verified_sha256", null) == expectedSha256 &&
            prefs.getString(prefix + "device_verified_model", null) == identity.deviceModel &&
            prefs.getLong(prefix + "device_verified_app_version", -1L) == identity.appVersionCode &&
            deviceVerifiedAt >= inferenceAt &&
            deviceVerifiedAt > 0L &&
            System.currentTimeMillis() - deviceVerifiedAt in 0L..MAX_INFERENCE_AGE_MILLIS &&
            prefs.getString(prefix + "device_verified_ref", null)?.let(deviceEvidencePattern::matches) == true

        return if (deviceVerified) {
            Snapshot(
                BardoCapabilityRegistry.Status.DEVICE_VERIFIED,
                "Prueba física documentada para este modelo, dispositivo y versión de BARDO.",
                inferenceAt,
                prefs.getString(prefix + "device_verified_ref", null)
            )
        } else {
            Snapshot(
                BardoCapabilityRegistry.Status.INFERENCE_PASSED,
                "Inferencia real con salida no vacía validada en este dispositivo y versión; falta la prueba física completa.",
                inferenceAt
            )
        }
    }

    fun recordShaVerified(
        context: Context,
        modelId: String,
        file: File,
        expectedSha256: String,
        actualSha256: String
    ) {
        require(modelIdPattern.matches(modelId))
        require(actualSha256.equals(expectedSha256, ignoreCase = true)) {
            "No se puede registrar un hash distinto al manifiesto."
        }
        val app = context.applicationContext
        require(file.isFile && file.length() > 0L)
        val identity = identity(app, file, expectedSha256)
        val prefs = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val prefix = "$modelId."
        val editor = prefs.edit()
        if (!identityMatches(prefs, prefix, identity)) removeModelEvidence(editor, prefix)
        writeIdentity(editor, prefix, identity)
        editor.putString(prefix + "verified_sha256", expectedSha256)
            .putLong(prefix + "verified_at", System.currentTimeMillis())
            .apply()
    }

    fun recordLoadSucceeded(
        context: Context,
        modelId: String,
        file: File,
        expectedSha256: String
    ) {
        val app = context.applicationContext
        val identity = identity(app, file, expectedSha256)
        val prefs = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val prefix = "$modelId."
        check(identityMatches(prefs, prefix, identity) &&
            prefs.getString(prefix + "verified_sha256", null) == expectedSha256) {
            "No se registra la carga sin una verificación SHA-256 vigente."
        }
        prefs.edit()
            .putString(prefix + "loaded_sha256", expectedSha256)
            .putLong(prefix + "loaded_at", System.currentTimeMillis())
            .apply()
    }

    fun recordInferencePassed(
        context: Context,
        modelId: String,
        file: File,
        expectedSha256: String,
        outputValidated: Boolean
    ) {
        require(outputValidated) { "La salida de inferencia está vacía o no está validada." }
        val app = context.applicationContext
        val identity = identity(app, file, expectedSha256)
        val prefs = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val prefix = "$modelId."
        check(identityMatches(prefs, prefix, identity) &&
            prefs.getString(prefix + "verified_sha256", null) == expectedSha256 &&
            prefs.getString(prefix + "loaded_sha256", null) == expectedSha256) {
            "No se registra inferencia sin hash verificado y carga satisfactoria."
        }
        prefs.edit()
            .putString(prefix + "inference_sha256", expectedSha256)
            .putLong(prefix + "inference_at", System.currentTimeMillis())
            .putBoolean(prefix + "inference_output_validated", true)
            .apply()
    }

    /** QA físico explícito; una inferencia ordinaria nunca llama a este método. */
    fun recordDeviceVerification(
        context: Context,
        modelId: String,
        file: File,
        expectedSha256: String,
        evidenceReference: String,
        passedChecks: Int,
        totalChecks: Int
    ) {
        require(deviceEvidencePattern.matches(evidenceReference))
        require(totalChecks >= 3 && passedChecks == totalChecks) {
            "DEVICE_VERIFIED exige que pasen todos los criterios de aceptación."
        }
        val app = context.applicationContext
        val identity = identity(app, file, expectedSha256)
        val prefs = app.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val prefix = "$modelId."
        val inferenceAt = prefs.getLong(prefix + "inference_at", 0L)
        check(identityMatches(prefs, prefix, identity) &&
            prefs.getString(prefix + "inference_sha256", null) == expectedSha256 &&
            prefs.getBoolean(prefix + "inference_output_validated", false) &&
            inferenceAt > 0L &&
            System.currentTimeMillis() - inferenceAt in 0L..MAX_INFERENCE_AGE_MILLIS) {
            "La prueba física requiere una inferencia válida reciente."
        }
        prefs.edit()
            .putString(prefix + "device_verified_sha256", expectedSha256)
            .putString(prefix + "device_verified_model", identity.deviceModel)
            .putLong(prefix + "device_verified_app_version", identity.appVersionCode)
            .putLong(prefix + "device_verified_at", System.currentTimeMillis())
            .putString(prefix + "device_verified_ref", evidenceReference)
            .apply()
    }

    internal fun resolveStatus(
        modelPresent: Boolean,
        identityMatches: Boolean,
        verified: Boolean,
        loaded: Boolean,
        inferencePassed: Boolean,
        deviceVerified: Boolean
    ): BardoCapabilityRegistry.Status = when {
        !modelPresent -> BardoCapabilityRegistry.Status.NEEDS_MODEL
        !identityMatches || !verified -> BardoCapabilityRegistry.Status.MODEL_PRESENT
        !loaded -> BardoCapabilityRegistry.Status.VERIFIED
        !inferencePassed -> BardoCapabilityRegistry.Status.LOADABLE
        deviceVerified -> BardoCapabilityRegistry.Status.DEVICE_VERIFIED
        else -> BardoCapabilityRegistry.Status.INFERENCE_PASSED
    }

    private fun identity(context: Context, file: File, expectedSha256: String): Identity {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        @Suppress("DEPRECATION")
        val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        return Identity(
            expectedSha256 = expectedSha256.lowercase(),
            fileLength = file.length(),
            fileLastModified = file.lastModified(),
            appVersionCode = versionCode,
            deviceModel = (Build.MANUFACTURER + " " + Build.MODEL).trim()
        )
    }

    private fun identityMatches(
        prefs: android.content.SharedPreferences,
        prefix: String,
        identity: Identity
    ): Boolean =
        prefs.getString(prefix + "identity_expected_sha256", null) == identity.expectedSha256 &&
            prefs.getLong(prefix + "identity_file_length", -1L) == identity.fileLength &&
            prefs.getLong(prefix + "identity_file_modified", -1L) == identity.fileLastModified &&
            prefs.getLong(prefix + "identity_app_version", -1L) == identity.appVersionCode &&
            prefs.getString(prefix + "identity_device", null) == identity.deviceModel

    private fun writeIdentity(
        editor: android.content.SharedPreferences.Editor,
        prefix: String,
        identity: Identity
    ) {
        editor.putString(prefix + "identity_expected_sha256", identity.expectedSha256)
            .putLong(prefix + "identity_file_length", identity.fileLength)
            .putLong(prefix + "identity_file_modified", identity.fileLastModified)
            .putLong(prefix + "identity_app_version", identity.appVersionCode)
            .putString(prefix + "identity_device", identity.deviceModel)
    }

    private fun removeModelEvidence(
        editor: android.content.SharedPreferences.Editor,
        prefix: String
    ) {
        listOf(
            "identity_expected_sha256", "identity_file_length", "identity_file_modified",
            "identity_app_version", "identity_device", "verified_sha256", "verified_at",
            "loaded_sha256", "loaded_at", "inference_sha256", "inference_at",
            "inference_output_validated", "device_verified_sha256",
            "device_verified_model", "device_verified_app_version",
            "device_verified_at", "device_verified_ref"
        ).forEach { editor.remove(prefix + it) }
    }

    private const val PREFERENCES = "bardo_capability_evidence_v1"
}
