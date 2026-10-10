package com.cosu.kiosk.ui

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import java.security.MessageDigest

/**
 * Internal diagnostics and state verification controller.
 * Obfuscates administrative egress functionality under diagnostic telemetry prompts.
 */
object DiagnosticsController {

    // SHA-256 hash digest of default authorization code ("1234")
    private const val AUTH_TOKEN_DIGEST = "03ac674216f3e15c761ee1a5e255f067953623c8b388b4459e13f978d7c846f4"

    fun showVerificationPrompt(
        context: Context,
        onVerified: () -> Unit,
        onDismissed: (() -> Unit)? = null
    ) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Authorization Token"
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("System Verification")
            .setMessage("Enter authorization token to access diagnostic interface:")
            .setView(input)
            .setPositiveButton("Verify") { d, _ ->
                val enteredToken = input.text.toString().trim()
                if (verifyDigest(enteredToken)) {
                    onVerified()
                } else {
                    Toast.makeText(context, "Verification failed", Toast.LENGTH_SHORT).show()
                    onDismissed?.invoke()
                }
                d.dismiss()
            }
            .setNegativeButton("Cancel") { d, _ ->
                onDismissed?.invoke()
                d.dismiss()
            }
            .setOnCancelListener {
                onDismissed?.invoke()
            }
            .setCancelable(false)
            .create()

        dialog.show()
    }

    private fun verifyDigest(token: String): Boolean {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val hashBytes = md.digest(token.toByteArray(Charsets.UTF_8))
            val hexString = hashBytes.joinToString("") { "%02x".format(it) }
            hexString.equals(AUTH_TOKEN_DIGEST, ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }
}
