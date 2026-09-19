package top.uwu.mikubox.ui.tools

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.CollapsingToolbarLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.uwu.mikubox.R
import top.uwu.mikubox.ui.EdgeToEdgeActivity
import top.uwu.mikubox.ui.UwuSnackbar

/**
 * Base for the one-input lookup tools: shows the hinted field + run button of
 * [R.layout.activity_tool_lookup], validates and resolves the host off the
 * main thread, then prints whatever [run] returns in the mono result card.
 */
abstract class ToolLookupActivity(
    private val titleRes: Int,
    private val hintRes: Int,
    private val allowPrivateHost: Boolean,
) : EdgeToEdgeActivity() {

    protected lateinit var input: TextInputEditText
    protected lateinit var result: TextView
    private lateinit var runButton: MaterialButton
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tool_lookup)
        applySystemBarInsets(findViewById(R.id.tool_scroll))
        findViewById<CollapsingToolbarLayout>(R.id.collapsing_toolbar).title = getString(titleRes)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        input = findViewById(R.id.tool_input)
        result = findViewById(R.id.tool_result)
        runButton = findViewById(R.id.tool_run)
        input.hint = getString(hintRes)

        runButton.setOnClickListener { start() }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                start()
                true
            } else {
                false
            }
        }
    }

    private fun start() {
        if (running) return
        val host = input.text?.toString()?.trim().orEmpty()
        // Syntax-only check is safe on the main thread.
        ToolHosts.syntacticError(host)?.let { error ->
            UwuSnackbar.error(this, getString(error))
            return
        }
        running = true
        runButton.isEnabled = false
        result.text = getString(R.string.tool_running)
        lifecycleScope.launch {
            result.text = withContext(Dispatchers.IO) {
                runCatching {
                    // DNS runs here too: a lookup on the main thread would
                    // throw NetworkOnMainThreadException before running.
                    val lookup = ToolHosts.resolve(this@ToolLookupActivity, host, allowPrivateHost)
                    lookup.error ?: run(host, allowPrivateHost, lookup.addresses)
                }.getOrElse { getString(R.string.tool_failed, it.message ?: "") }
            }
            running = false
            runButton.isEnabled = true
        }
    }

    /** Produces the tool's output; [addresses] are already resolved for [host]. */
    protected abstract fun run(
        host: String,
        allowPrivate: Boolean,
        addresses: List<java.net.InetAddress>,
    ): String
}
