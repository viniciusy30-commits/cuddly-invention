package com.example.quadbrowser

import android.content.Context
import android.os.Build
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

data class AccessDecision(val allowed: Boolean, val reason: String, val expiresAtMillis: Long? = null)

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
 *     note: <free-text admin note, only ever written from the admin panel>
 *   }
 *
 * A device with no existing document is treated as brand new: it is created
 * with status "pending" and access is DENIED until the admin approves it
 * from the panel (choosing a plan, which sets expiresAt). Firestore security
 * rules (deployed separately) make sure a client can only ever write its own
 * document's lastSeen/firstSeen/deviceModel/creation — never its own
 * "status", "plan", "expiresAt" or "note" fields, and never another
 * device's document at all.
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

    /** One-off check, used the very first time the app opens. */
    suspend fun check(context: Context): AccessDecision {
        val deviceId = DeviceIdentity.getOrCreateDeviceId(context)
        val db = FirebaseFirestore.getInstance()
        val docRef = db.collection("devices").document(deviceId)
        val modelLabel = deviceModelLabel()

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
                // pending and deny access until the admin approves it.
                val newDevice = hashMapOf(
                    "status" to "pending",
                    "deviceModel" to modelLabel,
                    "firstSeen" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                    "lastSeen" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                )
                try {
                    docRef.set(newDevice).await()
                } catch (_: Exception) {
                    // ignore — will retry registering on next launch
                }
                return AccessDecision(
                    false,
                    "Este dispositivo ainda não foi aprovado. Aguarde a liberação do administrador."
                )
            }

            // Best-effort heartbeat; also keeps deviceModel current if the app
            // moved to different hardware. Failure here should never block access.
            try {
                docRef.set(
                    mapOf(
                        "lastSeen" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                        "deviceModel" to modelLabel
                    ),
                    SetOptions.merge()
                ).await()
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
     * revoked (or restored) the instant the admin changes it in the panel —
     * without the user needing to close and reopen the app. Call
     * ListenerRegistration.remove() (e.g. in onDestroy) to stop listening.
     */
    fun listen(context: Context, onDecision: (AccessDecision) -> Unit): ListenerRegistration {
        val deviceId = DeviceIdentity.getOrCreateDeviceId(context)
        val db = FirebaseFirestore.getInstance()
        val docRef = db.collection("devices").document(deviceId)
        return docRef.addSnapshotListener { snapshot, error ->
            if (error != null || snapshot == null || !snapshot.exists()) return@addSnapshotListener
            onDecision(decisionFromSnapshot(snapshot))
        }
    }
}
