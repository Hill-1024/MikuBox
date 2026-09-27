package com.miku.ray.ui.routing

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textview.MaterialTextView
import com.miku.ray.MikuProfiles
import com.miku.ray.MikuCoreBridge
import com.miku.ray.ui.base.BaseActivity
import com.miku.ray.ui.server.ConfigDocument

/** File switches own policy switches; original YAML and rule order are retained. */
class RoutingSettingActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val toolbar = MaterialToolbar(this)
        root.addView(toolbar)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        setupToolbar(toolbar, showHomeAsUp = true, title = "路由设置")
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        content.addView(MaterialTextView(this).apply {
            text = "在规则模式下，按配置文件、策略控制分流。关闭的规则不会参与匹配；无匹配规则时直连。修改在下次连接时生效，也可立即重新连接。"
        })
        content.addView(MaterialButton(this).apply {
            text = "应用到当前连接"
            setOnClickListener {
                val message = if (!MikuCoreBridge.isRunning()) "已保存，下次连接生效"
                    else if (MikuCoreBridge.restart()) "正在重新连接" else "无法重新连接，请手动重试"
                Toast.makeText(this@RoutingSettingActivity, message, Toast.LENGTH_SHORT).show()
            }
        })
        val store = MikuProfiles.impl ?: return
        lifecycleScope.launch {
            val parsed = withContext(Dispatchers.IO) {
                store.list().map { profile -> profile to runCatching { ConfigDocument.rules(ConfigDocument.parse(profile.config)) } }
            }
            if (parsed.isEmpty()) content.addView(MaterialTextView(this@RoutingSettingActivity).apply { text = "暂无配置，请先导入配置或订阅。" })
            parsed.forEach { (profile, rules) ->
                val section = LinearLayout(this@RoutingSettingActivity).apply { orientation = LinearLayout.VERTICAL }
                val fileSwitch = MaterialSwitch(this@RoutingSettingActivity).apply { text = profile.name; isChecked = store.routingEnabled(profile.id) }
                content.addView(fileSwitch)
                content.addView(section)
                rules.onSuccess { entries ->
                    val disabled = store.disabledPolicies(profile.id)
                    entries.groupBy(ConfigDocument::policy).forEach { (policy, matching) ->
                        section.addView(MaterialSwitch(this@RoutingSettingActivity).apply {
                            text = "$policy · ${matching.size} 条规则"
                            setPadding((24 * resources.displayMetrics.density).toInt(), 0, 0, 0)
                            isChecked = policy !in disabled
                            isEnabled = fileSwitch.isChecked
                            setOnCheckedChangeListener { _, checked -> store.setPolicyEnabled(profile.id, policy, checked) }
                        })
                    }
                    if (entries.isEmpty()) section.addView(MaterialTextView(this@RoutingSettingActivity).apply { text = "此文件没有 rules 分流规则" })
                }.onFailure { error ->
                    fileSwitch.isEnabled = false
                    section.addView(MaterialTextView(this@RoutingSettingActivity).apply { text = "无法解析：${error.message}" })
                }
                fileSwitch.setOnCheckedChangeListener { _, checked ->
                    store.setRoutingEnabled(profile.id, checked)
                    for (index in 0 until section.childCount) section.getChildAt(index).isEnabled = checked
                }
            }
        }
    }
}
