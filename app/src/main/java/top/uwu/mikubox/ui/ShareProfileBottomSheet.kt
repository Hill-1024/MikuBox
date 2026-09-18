package top.uwu.mikubox.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.app.ShareCompat
import androidx.fragment.app.FragmentManager
import top.uwu.mikubox.R
import top.uwu.mikubox.databinding.UwuBottomSheetShareProfileBinding
import top.uwu.mikubox.profile.MihomoProfileStore

/**
 * Share sheet for one profile: the QR code, the YAML config, the subscription
 * URL (when there is one) and a share intent, matching the release build's
 * Share Configuration sheet.
 */
class ShareProfileBottomSheet : BaseUwuSheet() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val binding = UwuBottomSheetShareProfileBinding.inflate(inflater, container, false)
        bindHeader(binding.header)
        val profile = profile ?: run {
            dismiss()
            return binding.root
        }

        UwuRow.bind(
            binding.shareQrcode,
            UwuRow.Slot.TOP,
            R.drawable.ic_action_qr,
            getString(R.string.share_qrcode),
            profile.name,
            arrow = true,
        ) {
            dismiss()
            QrCode.show(requireActivity(), profile.name, profile.subscriptionUrl ?: profile.config)
        }
        UwuRow.bind(
            binding.shareClipboard,
            UwuRow.Slot.MIDDLE,
            R.drawable.ic_copy,
            getString(R.string.export_to_clipboard),
            getString(R.string.desc_copy_config),
            arrow = true,
        ) {
            copy(profile.config)
            dismiss()
        }
        UwuRow.bind(
            binding.shareUrl,
            UwuRow.Slot.MIDDLE,
            R.drawable.ic_promotion_24dp,
            getString(R.string.share_subscription_url),
            profile.subscriptionUrl.orEmpty(),
            arrow = !profile.subscriptionUrl.isNullOrBlank(),
            onClick = profile.subscriptionUrl?.let { url ->
                {
                    copy(url)
                    dismiss()
                }
            },
        )
        UwuRow.bind(
            binding.shareFile,
            UwuRow.Slot.BOTTOM,
            R.drawable.ic_share_24dp,
            getString(R.string.share_as_file),
            getString(R.string.share_configuration),
            arrow = true,
        ) {
            dismiss()
            ShareCompat.IntentBuilder(requireActivity())
                .setType("text/plain")
                .setChooserTitle(profile.name)
                .setText(profile.config)
                .startChooser()
        }
        return binding.root
    }

    private fun copy(text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("MikuBox", text))
        activity?.let { UwuSnackbar.success(it, getString(R.string.toast_exported)) }
    }

    private val profile: MihomoProfileStore.Profile?
        get() = arguments?.getString(ARG_ID)
            ?.let { id -> MihomoProfileStore.profiles(requireContext()).firstOrNull { it.id == id } }

    companion object {
        const val TAG = "ShareProfileBottomSheet"
        private const val ARG_ID = "profile_id"

        fun show(manager: FragmentManager, profile: MihomoProfileStore.Profile) {
            ShareProfileBottomSheet().apply {
                arguments = Bundle().apply { putString(ARG_ID, profile.id) }
            }.show(manager, TAG)
        }
    }
}
