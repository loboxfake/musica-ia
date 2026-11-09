package com.musicaia.seguridad

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

enum class BardoPrincipal {
    USER, JARVIS, BARDO, MODEL, AGENT, SKILL, TOOL
}

enum class BardoPermissionScope {
    READ_OWN_PROJECT,
    STUDY_LOCAL,
    PROPOSE_CHANGE,
    CREATE_LOCAL_CANDIDATE,
    RUN_SANDBOX_CI,
    CREATE_PULL_REQUEST,
    MERGE_MAIN,
    RESEARCH_NETWORK,
    INSTALL_SKILL,
    ACTIVATE_SKILL,
    INSTALL_APK,
    EXPORT_USER_DATA,
    DELETE_USER_DATA,
    MODIFY_PROTECTED_CORE,
    MODIFY_MODO3,
    CHANGE_AUTHORITY_POLICY,
    MANAGE_DELEGATIONS
}

data class BardoAuthorityGrant(
    val grantId: String,
    val issuerFingerprint: String,
    val granteeFingerprint: String,
    val scopes: Set<BardoPermissionScope>,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
    val signatureBase64: String
)

data class BardoSignedAuthorityRequest(
    val grantId: String,
    val granteeFingerprint: String,
    val scope: BardoPermissionScope,
    val requestId: String,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
    val payloadSha256: String,
    val signatureBase64: String
)

fun interface BardoNonceLedger {
    /** Debe consumir el identificador atómicamente; false significa repetido o almacenamiento fallido. */
    fun consumeOnce(requestId: String, expiresAtMillis: Long, nowMillis: Long): Boolean
}

fun interface BardoGrantRevocationStore {
    fun isRevoked(grantId: String): Boolean
}

data class BardoAuthorizationDecision(
    val allowed: Boolean,
    val reason: String
)

/**
 * Política de autoridad independiente de Gemma y de cualquier agente.
 *
 * Las peticiones de JARVIS solo se aceptan cuando se verifican:
 * - una delegación firmada por la clave pública emparejada del usuario;
 * - la firma de la petición de JARVIS;
 * - el alcance, los fingerprints, la caducidad, el hash de la carga y el nonce.
 *
 * La app debe mantener las claves emparejadas y los ledgers en almacenamiento
 * privado y no debe aceptar fingerprints suministrados por el propio modelo.
 */
object BardoAuthorityPolicy {
    private val autonomousSafeScopes = setOf(
        BardoPermissionScope.READ_OWN_PROJECT,
        BardoPermissionScope.STUDY_LOCAL,
        BardoPermissionScope.PROPOSE_CHANGE,
        BardoPermissionScope.CREATE_LOCAL_CANDIDATE,
        BardoPermissionScope.RUN_SANDBOX_CI
    )

    private val idPattern = Regex("^[A-Za-z0-9_-]{16,80}$")
    private val noncePattern = Regex("^[A-Za-z0-9_-]{16,100}$")
    private val hashPattern = Regex("^[0-9a-f]{64}$")
    private const val CLOCK_SKEW_MILLIS = 30_000L
    private const val MAX_REQUEST_LIFETIME_MILLIS = 10 * 60_000L

    fun publicKeyFingerprint(encodedPublicKey: ByteArray): String =
        sha256(encodedPublicKey)

    /** Bytes canónicos que el usuario firma al crear una delegación. */
    fun grantSigningPayload(grant: BardoAuthorityGrant): ByteArray {
        val scopes = grant.scopes.map { it.name }.sorted().joinToString(",")
        return listOf(
            "BARDO-GRANT-V1",
            grant.grantId,
            grant.issuerFingerprint,
            grant.granteeFingerprint,
            scopes,
            grant.issuedAtMillis.toString(),
            grant.expiresAtMillis.toString()
        ).joinToString("\n").toByteArray(Charsets.UTF_8)
    }

    /** Bytes canónicos que JARVIS firma para cada acción concreta. */
    fun requestSigningPayload(request: BardoSignedAuthorityRequest): ByteArray =
        listOf(
            "BARDO-REQUEST-V1",
            request.grantId,
            request.granteeFingerprint,
            request.scope.name,
            request.requestId,
            request.issuedAtMillis.toString(),
            request.expiresAtMillis.toString(),
            request.payloadSha256
        ).joinToString("\n").toByteArray(Charsets.UTF_8)

    fun authorize(
        principal: BardoPrincipal,
        scope: BardoPermissionScope,
        payload: String,
        nowMillis: Long,
        explicitUserConfirmation: Boolean = false,
        grant: BardoAuthorityGrant? = null,
        request: BardoSignedAuthorityRequest? = null,
        pairedUserPublicKeyX509: ByteArray? = null,
        pairedJarvisPublicKeyX509: ByteArray? = null,
        pairedUserFingerprint: String? = null,
        pairedJarvisFingerprint: String? = null,
        nonceLedger: BardoNonceLedger? = null,
        revocationStore: BardoGrantRevocationStore? = null
    ): BardoAuthorizationDecision {
        if (principal == BardoPrincipal.USER) {
            return if (explicitUserConfirmation) {
                BardoAuthorizationDecision(true, "Acción confirmada directamente por el usuario.")
            } else {
                BardoAuthorizationDecision(false, "La acción requiere confirmación explícita del usuario.")
            }
        }

        if (principal == BardoPrincipal.BARDO) {
            return if (scope in autonomousSafeScopes) {
                BardoAuthorizationDecision(true, "Acción autónoma local permitida por la política base.")
            } else {
                BardoAuthorizationDecision(false, "BARDO no puede concederse a sí mismo este permiso.")
            }
        }

        if (principal != BardoPrincipal.JARVIS) {
            return BardoAuthorizationDecision(
                false,
                "Modelos, agentes, skills y herramientas no tienen autoridad para autorizar acciones."
            )
        }

        val g = grant ?: return denied("Falta la delegación firmada por el usuario.")
        val r = request ?: return denied("Falta la petición firmada de JARVIS.")
        val userKey = pairedUserPublicKeyX509 ?: return denied("No hay clave pública de usuario emparejada.")
        val jarvisKey = pairedJarvisPublicKeyX509 ?: return denied("No hay clave pública de JARVIS emparejada.")
        val expectedUser = pairedUserFingerprint ?: return denied("Falta el fingerprint de usuario emparejado.")
        val expectedJarvis = pairedJarvisFingerprint ?: return denied("Falta el fingerprint de JARVIS emparejado.")
        val nonces = nonceLedger ?: return denied("No hay ledger anti-repetición persistente disponible.")
        val revocations = revocationStore ?: return denied("No hay registro de revocaciones disponible.")

        val userFingerprint = publicKeyFingerprint(userKey)
        val jarvisFingerprint = publicKeyFingerprint(jarvisKey)
        if (userFingerprint != expectedUser || g.issuerFingerprint != expectedUser) {
            return denied("La delegación no procede de la clave de usuario emparejada.")
        }
        if (jarvisFingerprint != expectedJarvis || g.granteeFingerprint != expectedJarvis ||
            r.granteeFingerprint != expectedJarvis
        ) {
            return denied("La petición no procede de la identidad JARVIS emparejada.")
        }
        if (!g.grantId.matches(idPattern) || !r.requestId.matches(noncePattern) ||
            r.grantId != g.grantId
        ) {
            return denied("Identificador de delegación o nonce no válido.")
        }
        if (g.scopes.isEmpty() || scope !in g.scopes || r.scope != scope) {
            return denied("La delegación no incluye el alcance solicitado.")
        }
        if (g.issuedAtMillis > nowMillis + CLOCK_SKEW_MILLIS ||
            g.expiresAtMillis <= nowMillis || g.expiresAtMillis <= g.issuedAtMillis
        ) {
            return denied("La delegación está caducada o fuera de su periodo de validez.")
        }
        if (revocations.isRevoked(g.grantId)) {
            return denied("La delegación fue revocada por el usuario.")
        }
        if (!hashPattern.matches(r.payloadSha256) ||
            !constantTimeEquals(r.payloadSha256, sha256(payload.toByteArray(Charsets.UTF_8)))
        ) {
            return denied("El contenido de la acción no coincide con el contenido firmado.")
        }
        if (r.issuedAtMillis < g.issuedAtMillis - CLOCK_SKEW_MILLIS ||
            r.issuedAtMillis > nowMillis + CLOCK_SKEW_MILLIS ||
            r.expiresAtMillis <= nowMillis ||
            r.expiresAtMillis > g.expiresAtMillis ||
            r.expiresAtMillis <= r.issuedAtMillis ||
            r.expiresAtMillis - r.issuedAtMillis > MAX_REQUEST_LIFETIME_MILLIS
        ) {
            return denied("La petición está caducada o excede el periodo autorizado.")
        }
        if (!verifySignature(userKey, grantSigningPayload(g), g.signatureBase64)) {
            return denied("La firma de la delegación del usuario no es válida.")
        }
        if (!verifySignature(jarvisKey, requestSigningPayload(r), r.signatureBase64)) {
            return denied("La firma de la petición de JARVIS no es válida.")
        }
        if (!nonces.consumeOnce(r.requestId, r.expiresAtMillis, nowMillis)) {
            return denied("La petición ya se utilizó o no pudo registrarse contra repetición.")
        }

        return BardoAuthorizationDecision(true, "Acción autorizada por delegación vigente de JARVIS.")
    }

    private fun verifySignature(
        publicKeyBytes: ByteArray,
        content: ByteArray,
        signatureBase64: String
    ): Boolean = runCatching {
        val publicKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(publicKeyBytes))
        val signatureBytes = Base64.getDecoder().decode(signatureBase64)
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(content)
            verify(signatureBytes)
        }
    }.getOrDefault(false)

    private fun denied(reason: String) = BardoAuthorizationDecision(false, reason)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun constantTimeEquals(left: String, right: String): Boolean {
        if (left.length != right.length) return false
        var difference = 0
        for (index in left.indices) difference = difference or (left[index].code xor right[index].code)
        return difference == 0
    }
}
