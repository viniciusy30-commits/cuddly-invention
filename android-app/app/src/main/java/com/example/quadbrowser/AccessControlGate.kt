package com.example.quadbrowser

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AccessDecision(val allowed: Boolean, val reason: String)

object AccessControlGate {
    fun check(baseUrl: String): AccessDecision {
        if (baseUrl.isBlank()) {
            return AccessDecision(false, "O controle de acesso ainda não foi configurado para este APK.")
        }
        val connection = try {
            (URL(baseUrl.trimEnd('/') + "/access/check").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 8000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write("{}".toByteArray()) }
            }
        } catch (_: Exception) {
            return AccessDecision(false, "Não foi possível validar o acesso. Verifique a conexão e tente novamente.")
        }

        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                AccessDecision(false, "O serviço de controle recusou a validação.")
            } else {
                val json = JSONObject(body)
                val allowed = json.optBoolean("allowed", false)
                AccessDecision(allowed, json.optString("blockedReason", "O acesso foi bloqueado pelo administrador."))
            }
        } catch (_: Exception) {
            AccessDecision(false, "Resposta inválida do serviço de controle.")
        } finally {
            connection.disconnect()
        }
    }
}
