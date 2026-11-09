package com.musicaia

import com.musicaia.seguridad.BardoAuthorityGrant
import com.musicaia.seguridad.BardoAuthorityPolicy
import com.musicaia.seguridad.BardoGrantRevocationStore
import com.musicaia.seguridad.BardoNonceLedger
import com.musicaia.seguridad.BardoPermissionScope
import com.musicaia.seguridad.BardoPrincipal
import com.musicaia.seguridad.BardoSignedAuthorityRequest
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BardoAuthorityPolicyTest {
    private data class Pairing(val user: KeyPair, val jarvis: KeyPair)

    private fun keys(): Pairing {
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(256) }
        return Pairing(generator.generateKeyPair(), generator.generateKeyPair())
    }

    private fun sign(privateKey: java.security.PrivateKey, payload: ByteArray): String {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(payload)
        return Base64.getEncoder().encodeToString(signer.sign())
    }

    private fun fingerprint(pair: KeyPair) =
        BardoAuthorityPolicy.publicKeyFingerprint(pair.public.encoded)

    private fun grant(pairing: Pairing, now: Long): BardoAuthorityGrant {
        val unsigned = BardoAuthorityGrant(
            grantId = UUID.randomUUID().toString().replace("-", ""),
            issuerFingerprint = fingerprint(pairing.user),
            granteeFingerprint = fingerprint(pairing.jarvis),
            scopes = setOf(BardoPermissionScope.CREATE_PULL_REQUEST),
            issuedAtMillis = now - 1000,
            expiresAtMillis = now + 60_000,
            signatureBase64 = ""
        )
        return unsigned.copy(signatureBase64 = sign(
            pairing.user.private,
            BardoAuthorityPolicy.grantSigningPayload(unsigned)
        ))
    }

    private fun request(
        pairing: Pairing,
        grant: BardoAuthorityGrant,
        payload: String,
        now: Long
    ): BardoSignedAuthorityRequest {
        val unsigned = BardoSignedAuthorityRequest(
            grantId = grant.grantId,
            granteeFingerprint = fingerprint(pairing.jarvis),
            scope = BardoPermissionScope.CREATE_PULL_REQUEST,
            requestId = UUID.randomUUID().toString(),
            issuedAtMillis = now,
            expiresAtMillis = now + 30_000,
            payloadSha256 = java.security.MessageDigest.getInstance("SHA-256")
                .digest(payload.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) },
            signatureBase64 = ""
        )
        return unsigned.copy(signatureBase64 = sign(
            pairing.jarvis.private,
            BardoAuthorityPolicy.requestSigningPayload(unsigned)
        ))
    }

    private fun authorize(
        pairing: Pairing,
        grant: BardoAuthorityGrant,
        request: BardoSignedAuthorityRequest,
        payload: String,
        now: Long,
        used: MutableSet<String> = mutableSetOf(),
        revoked: Boolean = false
    ) = BardoAuthorityPolicy.authorize(
        principal = BardoPrincipal.JARVIS,
        scope = BardoPermissionScope.CREATE_PULL_REQUEST,
        payload = payload,
        nowMillis = now,
        grant = grant,
        request = request,
        pairedUserPublicKeyX509 = pairing.user.public.encoded,
        pairedJarvisPublicKeyX509 = pairing.jarvis.public.encoded,
        pairedUserFingerprint = fingerprint(pairing.user),
        pairedJarvisFingerprint = fingerprint(pairing.jarvis),
        nonceLedger = BardoNonceLedger { id, _, _ -> used.add(id) },
        revocationStore = BardoGrantRevocationStore { revoked }
    )

    @Test fun userConfirmationOverridesDelegationsAndModelsCannotGrantPermissions() {
        assertTrue(BardoAuthorityPolicy.authorize(
            BardoPrincipal.USER,
            BardoPermissionScope.DELETE_USER_DATA,
            "",
            100L,
            explicitUserConfirmation = true
        ).allowed)
        assertFalse(BardoAuthorityPolicy.authorize(
            BardoPrincipal.MODEL,
            BardoPermissionScope.CREATE_PULL_REQUEST,
            "",
            100L,
            explicitUserConfirmation = true
        ).allowed)
    }

    @Test fun bardoCanOnlyPerformItsSmallAutonomousSafeSet() {
        assertTrue(BardoAuthorityPolicy.authorize(
            BardoPrincipal.BARDO,
            BardoPermissionScope.CREATE_LOCAL_CANDIDATE,
            "",
            100L
        ).allowed)
        assertFalse(BardoAuthorityPolicy.authorize(
            BardoPrincipal.BARDO,
            BardoPermissionScope.MERGE_MAIN,
            "",
            100L
        ).allowed)
    }

    @Test fun bardoCanReadItsOwnInventoryAndLocalStudyData() {
        assertTrue(BardoAuthorityPolicy.authorize(
            BardoPrincipal.BARDO,
            BardoPermissionScope.READ_BARDO_DATA,
            "",
            100L
        ).allowed)
        assertTrue(BardoAuthorityPolicy.authorize(
            BardoPrincipal.BARDO,
            BardoPermissionScope.STUDY_LOCAL,
            "",
            100L
        ).allowed)
    }

    @Test fun acceptsValidScopedSignedJarvisRequestOnce() {
        val pairing = keys()
        val now = System.currentTimeMillis()
        val payload = "candidate=abc123;action=create-draft-pr"
        val grant = grant(pairing, now)
        val request = request(pairing, grant, payload, now)
        val used = mutableSetOf<String>()

        assertTrue(authorize(pairing, grant, request, payload, now, used).allowed)
        assertFalse(authorize(pairing, grant, request, payload, now, used).allowed)
    }

    @Test fun rejectsWrongPayloadExpiredGrantAndRevokedGrant() {
        val pairing = keys()
        val now = System.currentTimeMillis()
        val payload = "candidate=one"
        val grant = grant(pairing, now)
        val request = request(pairing, grant, payload, now)

        assertFalse(authorize(pairing, grant, request, "candidate=other", now).allowed)
        assertFalse(authorize(pairing, grant, request, payload, grant.expiresAtMillis + 1).allowed)
        assertFalse(authorize(pairing, grant, request, payload, now, revoked = true).allowed)
    }

    @Test fun rejectsJarvisRequestOutsideDelegatedScope() {
        val pairing = keys()
        val now = System.currentTimeMillis()
        val payload = "candidate=two"
        val grant = grant(pairing, now)
        val request = request(pairing, grant, payload, now)
        val decision = BardoAuthorityPolicy.authorize(
            principal = BardoPrincipal.JARVIS,
            scope = BardoPermissionScope.MERGE_MAIN,
            payload = payload,
            nowMillis = now,
            grant = grant,
            request = request,
            pairedUserPublicKeyX509 = pairing.user.public.encoded,
            pairedJarvisPublicKeyX509 = pairing.jarvis.public.encoded,
            pairedUserFingerprint = fingerprint(pairing.user),
            pairedJarvisFingerprint = fingerprint(pairing.jarvis),
            nonceLedger = BardoNonceLedger { _, _, _ -> true },
            revocationStore = BardoGrantRevocationStore { false }
        )
        assertFalse(decision.allowed)
    }
}
