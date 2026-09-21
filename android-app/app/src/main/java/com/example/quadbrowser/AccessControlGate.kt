package com.example.quadbrowser

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

data class AccessDecision(val allowed: Boolean, val reason: String)

/**
 * Checks a device's access status against the "devices" collection in
 * Firestore. Each installation gets its own document, keyed by a random id
 * generated on first launch (see DeviceIdentity).
 *
 * Document shape (collection "devices", doc id = deviceId):
 *   {
 *     status: "active" | "blocked",
 *     firstSeen: <server timestamp, set once>,
 *     lastSeen: <server timestamp, updated every check>
 *   }
 *
 * A device with no existing document is treated as new: it is created with
 * status "active" and allowed through. This is what lets a new install show
 * up automatically in the admin panel for you to review. Firestore security
 * rules (deployed separately) make sure a client can only ever write its own
 * document's lastSeen/firstSeen/creation — never its own "status" field, and
 * never another device's document at all.
 */
object AccessControlGate {

    suspend fun check(context: Context): AccessDecision {
        val deviceId = DeviceIdentity.getOrCreateDeviceId(context)
        val db = FirebaseFirestore.getInstance()
        val docRef = db.collection("devices").document(deviceId)

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
                // First time this install has ever been seen: register it as active.
                val newDevice = hashMapOf(
                    "status" to "active",
                    "firstSeen" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                    "lastSeen" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                )
                try {
                    docRef.set(newDevice).await()
                } catch (_: Exception) {
                    // Even if the registration write fails, don't lock out the user
                    // over a transient issue — treat as allowed and try again next launch.
                }
                return AccessDecision(true, "")
            }

            val status = snapshot.getString("status") ?: "active"

            // Best-effort heartbeat; failure here should never block access.
            try {
                docRef.set(
                    mapOf("lastSeen" to com.google.firebase.firestore.FieldValue.serverTimestamp()),
                    SetOptions.merge()
                ).await()
            } catch (_: Exception) {
                // ignore
            }

            if (status == "blocked") {
                AccessDecision(false, "O acesso deste dispositivo foi bloqueado pelo administrador.")
            } else {
                AccessDecision(true, "")
            }
        } catch (_: Exception) {
            AccessDecision(false, "Não foi possível validar o acesso. Tente novamente.")
        }
    }
}
