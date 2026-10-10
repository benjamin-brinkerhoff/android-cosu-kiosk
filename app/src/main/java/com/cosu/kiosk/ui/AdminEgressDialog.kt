package com.cosu.kiosk.ui

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.Toast

/**
 * Handles the administrative egress workflow when the hidden multi-tap sequence is triggered.
 */
object AdminEgressDialog {

    private const val DEFAULT_ADMIN_PIN = "1234"

    fun show(context: Context, onAuthenticated: () -> Unit) {
        val input = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Enter Administrator PIN"
        }

        AlertDialog.Builder(context)
            .setTitle("Administrator Egress")
            .setMessage("Enter authorized PIN to disengage Lock Task Mode and access system configuration:")
            .setView(input)
            .setPositiveButton("Unlock") { dialog, _ ->
                val enteredPin = input.text.toString().trim()
                if (enteredPin == DEFAULT_ADMIN_PIN) {
                    Toast.makeText(context, "Lock Task Mode disengaged", Toast.LENGTH_SHORT).show()
                    onAuthenticated()
                } else {
                    Toast.makeText(context, "Invalid PIN", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }
}
