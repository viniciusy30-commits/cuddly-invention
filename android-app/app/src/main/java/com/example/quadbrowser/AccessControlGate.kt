package com.example.quadbrowser

import android.content.Context
import android.os.Build
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class AccessDecision(
    val allowed: Boolean,
    val reason: String,
    val expiresAtMillis: Long? = null,
    // TEMPORARY diagnostic field: the raw exception message (if any) hit
    // while trying to register/read this device's Firestore document.
    // Surfaced on-screen only to track down why a device isn't showing up
    // in the admin panel. Safe to remove once the root cause is found.
    val debugError: String? = null,
)

/**
 * Checks a device's access status against the "devices" collection in
 * Firestore. Each installation gets its own document, keyed by a random id
 * generated on first launch (see DeviceIdentity).
 *
 * Document shape (collection "devices", doc id = deviceId):
 *   {
 *     status: "pending" | "active" | "blocked",
 *     plan: "trial_24h" | "15_days" | "30_days" | "custom" | null,
 *     expiresAt: <server timestamp when access ends, null/absent = no expiry>,
 *     deviceModel: <manufacturer + model string>,
 *     firstSeen: <server timestamp, reset whenever the admin blocks the device>,
 *     lastSeen: <server timestamp, updated every check>,
 *     note: <free-text admin note, only ever written from the admin panel>,
 *     hardwareFingerprint: <sha-256 hash tied to the physical device, survives reinstall>,
 *     ipAddress: <best-effort public IP address at last heartbeat, may be null/absent>,
 *     reusedHardwareFrom: <deviceId of an earlier install with the same hardwareFingerprint,
 *                          only set once at registration, absent if this hardware is new>
 *   }
 *
 * A separate collection, "hardwareFingerprints/{fingerprintHash}", records the
 * *first* deviceId ever seen for each physical device (see
 * claimHardwareFingerprint below). This is what lets the panel flag "this
 * phone already used the free trial before, under a different install".
 * It is a signal for the admin, not an automatic block — IP and hardware
 * fingerprints are not 100% reliable (see DeviceIdentity's documentation),
 * so the app never denies access on its own based on this; only an admin
 * decision (blocking from the panel) does.
 *
 * A device with no existing document is treated as brand new: it is created
 * with status "pending" and access is DENIED until the admin approves it
 * from the panel (choosing a plan, which sets expiresAt). Firestore security
 * rules (deployed separately) make sure a client can only ever write its own
 * document's lastSeen/firstSeen/deviceModel/hardwareFingerprint/ipAddress/
 * reusedHardwareFrom/creation — never its own "status", "plan", "expiresAt"
 * or "note" fields, and never another device's document at all.
 */
object AccessControlGate {

    private fun deviceModelLabel(): String {
        val manufacturer = Build.MANUFACTURER?.replaceFirstChar { it.uppercase() } ?: ""
        val model = Build.MODEL ?: ""
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model".trim()
        }.ifBlank { "Desconhecido" }
    }

    private fun decisionFromSnapshot(snapshot: com.google.firebase.firestore.DocumentSnapshot): AccessDecision {
        val status = snapshot.getString("status") ?: "pending"
        val expiresAt = snapshot.getTimestamp("expiresAt")?.toDate()?.time
        val expired = expiresAt != null && expiresAt <= System.currentTimeMillis()

        return when {
            status == "blocked" -> AccessDecision(false, "O acesso deste dispositivo foi bloqueado pelo administrador.")
            status != "active" -> AccessDecision(false, "Este dispositivo ainda não foi aprovado. Aguarde a liberação do administrador.")
            expired -> AccessDecision(false, "O seu tempo de acesso acabou. Fale com o administrador para renovar.")
            else -> AccessDecision(true, "", expiresAt)
        }
    }

    /**
     * Tries to claim this hardware fingerprint as "first seen with this
     * deviceId". Firestore rules only allow CREATE (never update/delete) on
     * this collection, so this is a one-shot, race-safe claim: whichever
     * install writes it first wins, and every later install with the same
     * fingerprint will find the doc already there. Returns the deviceId of
     * the install that originally claimed it, or null if this deviceId is
     * the one making the claim right now (i.e. genuinely new hardware).
     */
    private suspend fun claimHardwareFingerprint(fingerprint: String, deviceId: String): String? {
        val db = FirebaseFirestore.getInstance()
        val fingerprintRef = db.collection("hardwareFingerprints").document(fingerprint)
        return try {
            val existing = fingerprintRef.get().await()
            if (existing.exists()) {
                existing.getString("firstDeviceId")
            } else {
                try {
                    fingerprintRef.set(
                        mapOf(
                            "firstDeviceId" to deviceId,
                            "firstSeen" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                        )
                    ).await()
                } catch (_: Exception) {
                    // Lost a race to another launch, or offline — either way,
                    // treat as "new hardware" this time; next launch will see
                    // the doc if it exists.
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** One-off check, used the very first time the app opens. */
    suspend fun check(context: Context): AccessDecision {
        val deviceId = DeviceIdentity.getOrCreateDeviceId(context)
        val db = FirebaseFirestore.getInstance()
        val docRef = db.collection("devices").document(deviceId)
        val modelLabel = deviceModelLabel()
        val fingerprint = DeviceIdentity.getHardwareFingerprint(context)

        return try {
            val snapshot = try {
                docRef.get().await()
            } catch (_: Exception) {
                return AccessDecision(
                    false,
                    "Não foi possível validar o acesso. Verifique sua conexão com a internet e tente novamente."
                )
            }

            if (!snapshot.exists()) {
                // First time this install has ever been seen: register it as
                // pending immediately, on its own — this must never depend
                // on the fingerprint claim or IP lookup succeeding first.
                val newDevice = hashMapOf<String, Any>(
                    "status" to "pending",
                    "deviceModel" to modelLabel,
                    "firstSeen" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                    "lastSeen" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                    "hardwareFingerprint" to fingerprint,
                )
                try {
                    docRef.set(newDevice).await()
                } catch (_: Exception) {
                    // ignore — will retry registering on next launch
                }
                // Best-effort extras, attached after the device already
                // exists; failure here must never affect registration.
                try {
                    val reusedFrom = claimHardwareFingerprint(fingerprint, deviceId)
                    val ip = withContext(Dispatchers.IO) { DeviceIdentity.fetchPublicIpAddress() }
                    val extra = hashMapOf<String, Any>().apply {
                        if (ip != null) put("ipAddress", ip)
                        if (reusedFrom != null) put("reusedHardwareFrom", reusedFrom)
                    }
                    if (extra.isNotEmpty()) docRef.set(extra, SetOptions.merge()).await()
                } catch (_: Exception) {
                    // ignore
                }
                return AccessDecision(
                    false,
                    "Este dispositivo ainda não foi aprovado. Aguarde a liberação do administrador."
                )
            }

            // Best-effort heartbeat; also keeps deviceModel/IP current.
            // Failure here should never block access.
            try {
                val ip = withContext(Dispatchers.IO) { DeviceIdentity.fetchPublicIpAddress() }
                val heartbeat = hashMapOf<String, Any>(
                    "lastSeen" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                    "deviceModel" to modelLabel,
                ).apply { if (ip != null) put("ipAddress", ip) }
                docRef.set(heartbeat, SetOptions.merge()).await()
            } catch (_: Exception) {
                // ignore
            }

            decisionFromSnapshot(snapshot)
        } catch (_: Exception) {
            AccessDecision(false, "Não foi possível validar o acesso. Tente novamente.")
        }
    }

    /**
     * Starts a realtime listener on this device's document so access can be
     * granted, revoked, or restored the instant the admin changes it in the
     * panel — without the user needing to close and reopen the app. This is
     * the only access check the app needs: it also handles first-launch
     * registration (creating the "pending" document) and every subsequent
     * status change, so there is never a state where the app is stuck
     * showing a stale "blocked" screen after the admin has already approved
     * or unblocked the device. Call ListenerRegistration.remove() (e.g. in
     * onDestroy) to stop listening.
     */
    fun listen(context: Context, onDecision: (AccessDecision) -> Unit): ListenerRegistration {
        val deviceId = DeviceIdentity.getOrCreateDeviceId(context)
        val db = FirebaseFirestore.getInstance()
        val docRef = db.collection("devices").document(deviceId)
        val modelLabel = deviceModelLabel()
        val fingerprint = DeviceIdentity.getHardwareFingerprint(context)
        var registered = false

        return docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                onDecision(
                    AccessDecision(
                        false,
                        "Não foi possível validar o acesso. Verifique sua conexão com a internet.",
                        debugError = "listener: ${error.javaClass.simpleName}: ${error.message}"
                    )
                )
                return@addSnapshotListener
            }
            if (snapshot == null || !snapshot.exists()) {
                onDecision(
                    AccessDecision(
                        false,
                        "Este dispositivo ainda não foi aprovado. Aguarde a liberação do administrador.",
                        debugError = if (registered) "aguardando confirmação do registro..." else null
                    )
                )
                // First time this install has ever been seen: register it as
                // pending. The listener will fire again on its own once this
                // write lands, so no extra handling is needed here.
                if (!registered) {
                    registered = true
                    // Register the core "pending" device doc immediately and
                    // on its own — this must never depend on the fingerprint
                    // claim or the IP lookup succeeding. The Firestore create
                    // rule only allows a create whose keys are EXACTLY
                    // status/deviceModel/firstSeen/lastSeen/hardwareFingerprint,
                    // so the follow-up merge (ipAddress/reusedHardwareFrom)
                    // must only run AFTER this create has landed — firing it
                    // in parallel created a race where the merge could reach
                    // Firestore first and get rejected as an invalid create
                    // (extra keys), and if the base write then also failed
                    // (or the app closed first) the device never registered
                    // at all, with no visible error. Now the merge always
                    // waits for the base create to succeed first.
                    val baseDevice = hashMapOf<String, Any>(
                        "status" to "pending",
                        "deviceModel" to modelLabel,
                        "firstSeen" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                        "lastSeen" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                        "hardwareFingerprint" to fingerprint,
                    )
                    MainScope().launch {
                        try {
                            docRef.set(baseDevice).await()
                        } catch (e: Exception) {
                            // Base registration failed (offline, rules, etc).
                            // Do not attempt the merge below — it would race
                            // to create the doc itself and get rejected by
                            // the create rule. The listener will retry
                            // registration on its next fire.
                            registered = false
                            onDecision(
                                AccessDecision(
                                    false,
                                    "Este dispositivo ainda não foi aprovado. Aguarde a liberação do administrador.",
                                    debugError = "registro: ${e.javaClass.simpleName}: ${e.message}"
                                )
                            )
                            return@launch
                        }
                        // TEMPORARY diagnostic: set().await() can resolve
                        // successfully as soon as the write is queued in the
                        // local cache, WITHOUT the server having confirmed it
                        // yet (this is normal Firestore behavior, not a bug
                        // in general — but it means "no exception" here does
                        // NOT prove the panel can see this document). Force a
                        // server-only read right after to confirm it actually
                        // landed, and surface the result on-screen.
                        try {
                            val serverCheck = docRef.get(
                                com.google.firebase.firestore.Source.SERVER
                            ).await()
                            if (!serverCheck.exists()) {
                                onDecision(
                                    AccessDecision(
                                        false,
                                        "Este dispositivo ainda não foi aprovado. Aguarde a liberação do administrador.",
                                        debugError = "set() não gerou erro, mas o servidor não confirmou o documento (Source.SERVER = não existe)."
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            onDecision(
                                AccessDecision(
                                    false,
                                    "Este dispositivo ainda não foi aprovado. Aguarde a liberação do administrador.",
                                    debugError = "checagem servidor: ${e.javaClass.simpleName}: ${e.message}"
                                )
                            )
                        }
                        try {
                            val reusedFrom = claimHardwareFingerprint(fingerprint, deviceId)
                            val ip = withContext(Dispatchers.IO) { DeviceIdentity.fetchPublicIpAddress() }
                            val extra = hashMapOf<String, Any>().apply {
                                if (ip != null) put("ipAddress", ip)
                                if (reusedFrom != null) put("reusedHardwareFrom", reusedFrom)
                            }
                            if (extra.isNotEmpty()) docRef.set(extra, SetOptions.merge()).await()
                        } catch (_: Exception) {
                            // ignore — device is already registered either way
                        }
                    }
                }
                return@addSnapshotListener
            }

            onDecision(decisionFromSnapshot(snapshot))

            // Best-effort heartbeat; also keeps deviceModel/IP current.
            // Failure here should never block access.
            MainScope().launch {
                val ip = withContext(Dispatchers.IO) { DeviceIdentity.fetchPublicIpAddress() }
                val heartbeat = hashMapOf<String, Any>(
                    "lastSeen" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                    "deviceModel" to modelLabel,
                ).apply { if (ip != null) put("ipAddress", ip) }
                docRef.set(heartbeat, SetOptions.merge())
            }
        }
    }
}
