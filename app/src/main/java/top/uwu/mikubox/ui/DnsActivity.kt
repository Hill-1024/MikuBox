package top.uwu.mikubox.ui

import android.os.Bundle
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import top.uwu.mikubox.R
import top.uwu.mikubox.core.MihomoCore
import top.uwu.mikubox.core.MihomoDnsSettings
import top.uwu.mikubox.databinding.ActivityDnsBinding

/**
 * Editor for the app DNS block and for how it combines with a profile's own
 * `dns:` section (profile-first, always-app, or always-profile).
 */
class DnsActivity : EdgeToEdgeActivity() {

    private lateinit var binding: ActivityDnsBinding
    private val sources = MihomoDnsSettings.DnsSource.entries

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDnsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.etDns.setText(MihomoDnsSettings.yaml(this))
        setupSourceSelector()
        applyEnabled(MihomoDnsSettings.source(this))

        binding.btnSave.setOnClickListener {
            val yaml = binding.etDns.text?.toString().orEmpty()
            val error = MihomoCore.validateDns(yaml)
            if (error != null) {
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.dns_invalid_title)
                    .setMessage(error)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@setOnClickListener
            }
            MihomoDnsSettings.setYaml(this, yaml)
            toast(R.string.toast_dns_saved)
        }
        binding.btnReset.setOnClickListener {
            MihomoDnsSettings.resetYaml(this)
            binding.etDns.setText(MihomoDnsSettings.DEFAULT_YAML)
            toast(R.string.toast_dns_reset)
        }
    }

    private fun setupSourceSelector() {
        val labels = resources.getStringArray(R.array.dns_sources)
        binding.dropdownSource.setSimpleItems(labels)
        binding.dropdownSource.setText(labels[sources.indexOf(MihomoDnsSettings.source(this)).coerceAtLeast(0)], false)
        binding.dropdownSource.setOnItemClickListener { _, _, position, _ ->
            MihomoDnsSettings.setSource(this, sources[position])
            applyEnabled(sources[position])
        }
    }

    /** The app block is only editable while a mode can actually use it. */
    private fun applyEnabled(source: MihomoDnsSettings.DnsSource) {
        val enabled = source != MihomoDnsSettings.DnsSource.CONFIG
        binding.tilDns.isEnabled = enabled
        binding.etDns.isEnabled = enabled
        binding.btnSave.isEnabled = enabled
        binding.btnReset.isEnabled = enabled
    }

    private fun toast(messageRes: Int) = Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()
}
