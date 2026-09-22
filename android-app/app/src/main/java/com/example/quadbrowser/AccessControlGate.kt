package com.example.quadbrowser

import android.content.Context
import android.os.Build
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
 *     status: "pending" | "active" | "blocked",
 *     deviceModel: <manufacturer + model string>,
 *     firstSeen: <server timestamp, reset whenever the admin blocks the device>,
 *     lastSeen: <server timestamp, updated every check>,
 *     note: <free-text admin note, only ever written from the admin panel>
 *   }
 *
 * A device with no existing document is treated as brand new: it is created
 * with status "pending" and access is DENIED until the admin approves it
 * from the panel. This is what lets a new install show up in the admin
 * panel for review before it can be used at all. Firestore security rules
 * (deployed separately) make sure a client can only ever write its own
 * document's lastSeen/firstSeen/deviceModel/creation — never its own
 * "status" or "note" fields, and never another device's document at all.
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

            val status = snapshot.getString("status") ?: "pending"

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

            when (status) {
                "active" -> AccessDecision(true, "")
                "blocked" -> AccessDecision(false, "O acesso deste dispositivo foi bloqueado pelo administrador.")
                else -> AccessDecision(false, "Este dispositivo ainda não foi aprovado. Aguarde a liberação do administrador.")
            }
        } catch (_: Exception) {
            AccessDecision(false, "Não foi possível validar o acesso. Tente novamente.")
        }
    }
}
