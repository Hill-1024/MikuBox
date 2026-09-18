package top.uwu.mikubox.ui

import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import top.uwu.mikubox.R
import top.uwu.mikubox.databinding.DialogUwuConfirmBinding
import top.uwu.mikubox.databinding.DialogUwuInputBinding

/**
 * Dialog bodies shared by every screen: the release build funnels destructive
 * confirmations and text input through the same two card layouts so all
 * prompts line up.
 */
object UwuDialogs {

    /** Confirmation with the error-tinted icon/title and a message card. */
    fun confirm(
        context: Context,
        title: CharSequence,
        message: CharSequence,
        @DrawableRes icon: Int = R.drawable.ic_warning,
        @StringRes positiveRes: Int = R.string.action_delete,
        onConfirm: () -> Unit,
    ) {
        val binding = DialogUwuConfirmBinding.inflate(LayoutInflater.from(context))
        binding.dialogIcon.setImageResource(icon)
        binding.dialogTitle.text = title
        binding.dialogMessage.text = message
        val dialog = MaterialAlertDialogBuilder(context)
            .setView(binding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(positiveRes) { _, _ -> onConfirm() }
            .create()
        dialog.setOnShowListener { UwuWindow.apply(dialog) }
        dialog.show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            ?.setTextColor(MaterialColors.getColor(binding.root, androidx.appcompat.R.attr.colorError))
    }

    /** Single-line text prompt; [validate] returns an error message or null. */
    fun input(
        context: Context,
        title: CharSequence,
        message: CharSequence? = null,
        initial: String = "",
        hint: CharSequence? = null,
        positiveRes: Int = android.R.string.ok,
        validate: ((String) -> Int?)? = null,
        onValue: (String) -> Unit,
    ) {
        val binding = DialogUwuInputBinding.inflate(LayoutInflater.from(context))
        val messageView = binding.root.findViewById<TextView>(android.R.id.message)
        messageView.text = message ?: ""
        messageView.visibility = if (message.isNullOrBlank()) android.view.View.GONE else android.view.View.VISIBLE
        val input = binding.root.findViewById<TextInputEditText>(android.R.id.edit)
        input.setText(initial)
        input.setSelection(initial.length)
        hint?.let { input.hint = it }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(binding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(positiveRes, null)
            .create()
        dialog.setOnShowListener {
            UwuWindow.apply(dialog)
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text?.toString() ?: ""
                val errorRes = validate?.invoke(value)
                if (errorRes != null) {
                    input.error = context.getString(errorRes)
                } else {
                    dialog.dismiss()
                    onValue(value)
                }
            }
        }
        dialog.show()
        input.requestFocus()
    }

    /** Radio list backed by the theme's single-choice rows. */
    fun choose(
        context: Context,
        title: CharSequence,
        items: Array<CharSequence>,
        selected: Int,
        onPick: (Int) -> Unit,
    ) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setSingleChoiceItems(items, selected) { dialog, which ->
                dialog.dismiss()
                onPick(which)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
