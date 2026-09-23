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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class AccessDecision(
    val allowed: Boolean,
    val reason: String,
    val expiresAtMillis: Long? = null,
    // Optional diagnostic message. No longer populated anywhere in this
    // file (kept only so MainActivity's overlay/debug-log code, which checks
    // it, doesn't need to change too) — the on-screen debug log is now off.
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

    /**
     * Callback-based twin of claimHardwareFingerprint above, used by listen()
     * so the whole registration flow can run on Firebase's own Task
     * callbacks instead of a coroutine scope.
     */
    private fun claimHardwareFingerprintAsync(fingerprint: String, deviceId: String, onResult: (String?) -> Unit) {
        val db = FirebaseFirestore.getInstance()
        val fingerprintRef = db.collection("hardwareFingerprints").document(fingerprint)
        fingerprintRef.get()
            .addOnSuccessListener { existing ->
                if (existing.exists()) {
                    onResult(existing.getString("firstDeviceId"))
                } else {
                    fingerprintRef.set(
                        mapOf(
                            "firstDeviceId" to deviceId,
                            "firstSeen" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                        )
                    ).addOnCompleteListener {
                        // Whether this write won the race or lost it (or is
                        // offline), this deviceId is the one claiming right
                        // now, so treat it as "new hardware" this time.
                        onResult(null)
                    }
                }
            }
            .addOnFailureListener { onResult(null) }
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

    // The Firestore project this app talks to (see google-services.json).
    // Only used by the REST fallback below — the normal SDK calls elsewhere
    // in this file already know their project via FirebaseFirestore.getInstance().
    private const val FIRESTORE_PROJECT_ID = "quadbrowser-acesso"
    private const val FIRESTORE_WEB_API_KEY = "AIzaSyCfVTZOKLoa9emMsLuT1c9pVCka4--QSTo"

    // Minimum time between heartbeat writes to Firestore for the same app
    // session. See the comment at the heartbeat call site in listen() for
    // why this throttle exists (Firestore free-tier daily write quota).
    private const val HEARTBEAT_MIN_INTERVAL_MS = 5 * 60 * 1000L

    /**
     * FIX for the "new devices never appear in the admin panel" bug.
     *
     * Root cause: on some devices/networks (confirmed on a Motorola in the
     * field, matches long-documented firebase-android-sdk reports) the
     * Firestore SDK's underlying gRPC connection never connects and never
     * fails either — no exception, no timeout, nothing. docRef.set() from
     * the normal SDK just hangs forever. The Android Firestore SDK has no
     * public setting to switch its transport away from gRPC (that option —
     * setForceLongPolling — only exists on the Web/JS SDK, not Android), so
     * the fix cannot be "configure the SDK differently".
     *
     * Instead, this function writes the initial "pending" device document
     * using Firestore's plain REST API over a normal HTTPS connection
     * (HttpURLConnection, same mechanism DeviceIdentity already uses for the
     * IP lookup), completely bypassing the SDK's gRPC channel. Plain HTTPS
     * gets through on networks/devices where gRPC silently stalls. This is
     * only used as a fallback when the normal SDK write hasn't confirmed
     * within a few seconds — see listen() below. Once this document exists,
     * the realtime listener (which uses a separate, read-only gRPC stream
     * that has not shown this hanging behavior in testing) picks it up
     * normally and the rest of the app is unaffected.
     *
     * Must be called off the main thread.
     */
    private fun registerPendingDeviceViaRest(deviceId: String, modelLabel: String): Pair<Boolean, String?> {
        return try {
            val url = URL(
                "https://firestore.googleapis.com/v1/projects/$FIRESTORE_PROJECT_ID/databases/(default)/documents/devices/$deviceId" +
                    "?key=$FIRESTORE_WEB_API_KEY" +
                    "&updateMask.fieldPaths=status&updateMask.fieldPaths=deviceModel&updateMask.fieldPaths=firstSeen&updateMask.fieldPaths=lastSeen"
            )
            // java.time.Instant needs API 26+; this project's minSdk is 23,
            // so the RFC 3339 timestamp Firestore's REST API expects is
            // built by hand with SimpleDateFormat instead (works since API 1).
            val rfc3339Format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }
            val nowRfc3339 = rfc3339Format.format(java.util.Date())
            val body = """
                {
                  "fields": {
                    "status": { "stringValue": "pending" },
                    "deviceModel": { "stringValue": "${modelLabel.replace("\"", "'")}" },
                    "firstSeen": { "timestampValue": "$nowRfc3339" },
                    "lastSeen": { "timestampValue": "$nowRfc3339" }
                  }
                }
            """.trimIndent()

            val connection = url.openConnection() as HttpURLConnection
            // HttpURLConnection.setRequestMethod("PATCH") throws
            // ProtocolException on some Android versions (PATCH isn't in
            // its allowed method list on every API level). The standard,
            // reflection-free workaround — also the one Firestore's own
            // REST docs endorse — is a POST carrying the override header.
            connection.requestMethod = "POST"
            connection.setRequestProperty("X-HTTP-Method-Override", "PATCH")
            connection.doOutput = true
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("Content-Type", "application/json")
            OutputStreamWriter(connection.outputStream).use { it.write(body) }

            val responseCode = connection.responseCode
            val success = responseCode in 200..299
            val errorDetail = if (!success) {
                val errorText = try {
                    BufferedReader(InputStreamReader(connection.errorStream ?: connection.inputStream)).use { it.readText() }
                } catch (_: Exception) { "" }
                android.util.Log.w("AccessControlGate", "REST fallback registration failed ($responseCode): $errorText")
                "HTTP $responseCode: ${errorText.take(200)}"
            } else null
            connection.disconnect()
            success to errorDetail
        } catch (e: Exception) {
            android.util.Log.w("AccessControlGate", "REST fallback registration threw: ${e.javaClass.simpleName}: ${e.message}")
            false to "${e.javaClass.simpleName}: ${e.message}"
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
        var lastHeartbeatAtMillis = 0L
        var lastKnownIp: String? = null

        return docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                onDecision(
                    AccessDecision(
                        false,
                        "Não foi possível validar o acesso. Verifique sua conexão com a internet."
                    )
                )
                return@addSnapshotListener
            }
            if (snapshot == null || !snapshot.exists()) {
                onDecision(
                    AccessDecision(
                        false,
                        "Este dispositivo ainda não foi aprovado. Aguarde a liberação do administrador."
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
                    //
                    val baseDevice = hashMapOf<String, Any>(
                        "status" to "pending",
                        "deviceModel" to modelLabel,
                        "firstSeen" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                        "lastSeen" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                        "hardwareFingerprint" to fingerprint,
                    )

                    // Races the normal SDK write against a REST fallback: on
                    // networks/devices where the SDK's gRPC channel hangs
                    // silently, the SDK call below never calls either
                    // listener, so a plain timer fires the REST fallback
                    // (registerPendingDeviceViaRest above) if the SDK hasn't
                    // confirmed within 6s. Whichever path wins marks
                    // restFallbackHandled so the other becomes a no-op if it
                    // eventually does resolve too.
                    val registrationMainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                    var restFallbackHandled = false

                    // Best-effort extras, attached only after the base
                    // "pending" document exists — the Firestore create rule
                    // only allows a create whose keys are exactly
                    // status/deviceModel/firstSeen/lastSeen/hardwareFingerprint,
                    // so ipAddress/reusedHardwareFrom must always be a
                    // follow-up merge, never part of the initial create.
                    // Failure here must never block registration or access.
                    fun attachFingerprintAndIpExtras() {
                        claimHardwareFingerprintAsync(fingerprint, deviceId) { reusedFrom ->
                            DeviceIdentity.fetchPublicIpAddressAsync { ip ->
                                val extra = hashMapOf<String, Any>().apply {
                                    if (ip != null) put("ipAddress", ip)
                                    if (reusedFrom != null) put("reusedHardwareFrom", reusedFrom)
                                }
                                if (extra.isNotEmpty()) {
                                    docRef.set(extra, SetOptions.merge())
                                }
                            }
                        }
                    }

                    val restFallbackRunnable = Runnable {
                        if (restFallbackHandled) return@Runnable
                        restFallbackHandled = true
                        Thread {
                            val (restSuccess, _) = registerPendingDeviceViaRest(deviceId, modelLabel)
                            registrationMainHandler.post {
                                if (!restSuccess) {
                                    registered = false
                                } else {
                                    attachFingerprintAndIpExtras()
                                }
                            }
                        }.start()
                    }
                    registrationMainHandler.postDelayed(restFallbackRunnable, 6_000L)

                    docRef.set(baseDevice)
                        .addOnSuccessListener {
                            if (restFallbackHandled) return@addOnSuccessListener
                            restFallbackHandled = true
                            registrationMainHandler.removeCallbacks(restFallbackRunnable)
                            attachFingerprintAndIpExtras()
                        }
                        .addOnFailureListener {
                            if (restFallbackHandled) return@addOnFailureListener
                            restFallbackHandled = true
                            registrationMainHandler.removeCallbacks(restFallbackRunnable)
                            registered = false
                        }
                }
                return@addSnapshotListener
            }

            onDecision(decisionFromSnapshot(snapshot))

            // Best-effort heartbeat; keeps deviceModel/lastSeen current so
            // the admin panel shows when a device was last seen. Failure
            // here should never block access.
            //
            // Throttled to at most once every 5 minutes per app session:
            // addSnapshotListener can fire far more often than once per
            // heartbeat-worthy interval (any change to the document —
            // including this same heartbeat write — re-triggers it), so an
            // unthrottled heartbeat can turn a handful of real devices into
            // thousands of Firestore writes per day, burning through the
            // free-tier daily quota. lastHeartbeatAtMillis is local to this
            // listen() call, so it naturally resets on app restart.
            val nowMillis = System.currentTimeMillis()
            if (nowMillis - lastHeartbeatAtMillis >= HEARTBEAT_MIN_INTERVAL_MS) {
                lastHeartbeatAtMillis = nowMillis
                val ip = lastKnownIp
                val heartbeat = hashMapOf<String, Any>(
                    "lastSeen" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                    "deviceModel" to modelLabel,
                ).apply { if (ip != null) put("ipAddress", ip) }
                docRef.set(heartbeat, SetOptions.merge())

                // Refresh the cached IP in the background for next time,
                // without delaying or duplicating this heartbeat write.
                DeviceIdentity.fetchPublicIpAddressAsync { freshIp ->
                    if (freshIp != null) lastKnownIp = freshIp
                }
            }
        }
    }
}
