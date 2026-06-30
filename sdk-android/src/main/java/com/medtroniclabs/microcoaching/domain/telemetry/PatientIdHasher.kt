package com.medtroniclabs.microcoaching.domain.telemetry

import java.security.MessageDigest

/**
 * One-way patient identifier hasher.
 *
 * The privacy contract for the SDK (per `docs/UseCases_v2.md` §"Privacy & Data
 * Rules", line 508) is that **raw SPICE patient IDs never leave the device**.
 * Every event row that carries a patient reference stores the SHA-256 hex
 * digest of the raw ID in `patient_id_hash` instead.
 *
 * Hashing is deterministic — the backend can join across event rows for the
 * same patient by comparing hashes — but irreversible.
 *
 * Same hash everywhere: the digest is over the raw UTF-8 bytes of the ID
 * string. SPICE passes whatever shape it uses (`patientTrackId` as a `Long`,
 * a UUID, etc.) — callers stringify before hashing.
 */
internal object PatientIdHasher {
    fun hash(rawSpicePatientId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(rawSpicePatientId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
