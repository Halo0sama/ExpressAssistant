package com.halo.expressassistant.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.halo.expressassistant.api.XiaomiApi
import com.halo.expressassistant.data.Store
import com.halo.expressassistant.databinding.ActivitySettingsBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.apply(this, binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.aiBase.editText?.setText(Store.aiBase(this))
        binding.aiKey.editText?.setText(Store.aiKey(this))
        binding.aiModel.editText?.setText(Store.aiModel(this))

        refreshButtons()

        binding.rowPhone.setOnClickListener { showPhoneDialog() }
        binding.rowHidden.setOnClickListener { showHiddenDialog() }
        binding.rowReport.setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java).putExtra("open_schedule", true))
        }
        binding.cardMore.setOnClickListener {
            startActivity(Intent(this, MoreConnectionsActivity::class.java))
        }
        binding.btnSave.setOnClickListener {
            Store.saveSettings(
                this,
                binding.aiBase.editText?.text?.toString()?.trim().orEmpty(),
                binding.aiKey.editText?.text?.toString()?.trim().orEmpty(),
                binding.aiModel.editText?.text?.toString()?.trim().orEmpty(),
                Store.kdKey(this),
                Store.kdCustomer(this),
                Store.reportTime(this).first,
                Store.reportTime(this).second,
                Store.kd100Fallback(this),
                Store.accessibilityEnabled(this)
            )
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun refreshButtons() {
        binding.phoneSummary.text = Store.xiaomiPhones(this).joinToString("、")
        binding.hiddenSummary.text = "${Store.xiaomiHidden(this).size} 个已删除"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun onSurfaceVariant(): Int =
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)

    private fun surfaceLow(): Int =
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerLow, 0)

    private fun outlinedInput(hint: String, text: String = "", number: Boolean = false): TextInputLayout {
        val layout = TextInputLayout(this).apply {
            this.hint = hint
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        layout.addView(
            EditText(this).apply {
                setText(text)
                setSingleLine(true)
                if (number) inputType = android.text.InputType.TYPE_CLASS_NUMBER
            }
        )
        return layout
    }

    private fun showPhoneDialog() {
        val sheet = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(24))
        }
        container.addView(
            TextView(this).apply {
                text = "手机号管理"
                textSize = 22f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
        )
        container.addView(
            TextView(this).apply {
                text = "当前绑定：" + Store.xiaomiPhones(this@SettingsActivity).joinToString("、")
                textSize = 13f
                setTextColor(onSurfaceVariant())
                setPadding(0, 2, 0, dp(14))
            }
        )
        val phoneInput = outlinedInput("要绑定的手机号", number = true)
        container.addView(phoneInput)
        val sendBtn = com.google.android.material.button.MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "发送验证码"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        container.addView(sendBtn)
        val codeInput = outlinedInput("短信验证码", number = true)
        container.addView(
            codeInput,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        )
        val bindBtn = com.google.android.material.button.MaterialButton(this).apply {
            text = "确认绑定"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        }
        container.addView(bindBtn)

        sheet.setContentView(container)
        sheet.show()

        fun loggedIn(): Boolean = Store.xiaomiToken(this@SettingsActivity).isNotEmpty()
        fun authParams(): Array<String?> = arrayOf(
            Store.xiaomiToken(this@SettingsActivity),
            Store.xiaomiCUser(this@SettingsActivity),
            Store.xiaomiAccountId(this@SettingsActivity),
            Store.xiaomiOaid(this@SettingsActivity),
            Store.xiaomiVaid(this@SettingsActivity)
        )

        sendBtn.setOnClickListener {
            val phone = phoneInput.editText?.text?.toString()?.trim().orEmpty()
            if (phone.isEmpty() || !loggedIn()) {
                Toast.makeText(this, "请先登录小米并输入手机号", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sendBtn.isEnabled = false
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val a = authParams()
                    val raw = withContext(Dispatchers.IO) {
                        XiaomiApi.sendVerificationCode(
                            this@SettingsActivity, a[0]!!, a[1]!!, a[2], a[3], a[4], phone
                        )
                    }
                    val code = JSONObject(raw).optInt("code")
                    val msg = if (code == 0) "验证码已发送" else JSONObject(raw).optString("message")
                    Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
                } catch (e: Throwable) {
                    Toast.makeText(this@SettingsActivity, "发送失败：$e", Toast.LENGTH_LONG).show()
                } finally {
                    sendBtn.isEnabled = true
                }
            }
        }

        bindBtn.setOnClickListener {
            val phone = phoneInput.editText?.text?.toString()?.trim().orEmpty()
            val code = codeInput.editText?.text?.toString()?.trim().orEmpty()
            if (phone.isEmpty() || code.isEmpty() || !loggedIn()) {
                Toast.makeText(this, "请填写手机号和验证码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            bindBtn.isEnabled = false
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val a = authParams()
                    val checkRaw = withContext(Dispatchers.IO) {
                        XiaomiApi.checkVerificationCode(
                            this@SettingsActivity, a[0]!!, a[1]!!, a[2], a[3], a[4], phone, code
                        )
                    }
                    if (JSONObject(checkRaw).optInt("code") != 0) {
                        Toast.makeText(this@SettingsActivity, "验证码校验失败", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    val phones = (Store.xiaomiPhones(this@SettingsActivity) + phone).distinct()
                    val bindRaw = withContext(Dispatchers.IO) {
                        XiaomiApi.bindPhone(
                            this@SettingsActivity, a[0]!!, a[1]!!, a[2], a[3], a[4], phone, phones, true
                        )
                    }
                    if (JSONObject(bindRaw).optInt("code") == 0) {
                        Store.saveXiaomiPhones(this@SettingsActivity, phones)
                        Toast.makeText(this@SettingsActivity, "绑定成功", Toast.LENGTH_LONG).show()
                        refreshButtons()
                        sheet.dismiss()
                    } else {
                        Toast.makeText(
                            this@SettingsActivity,
                            "绑定失败：" + JSONObject(bindRaw).optString("message"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (e: Throwable) {
                    Toast.makeText(this@SettingsActivity, "绑定异常：$e", Toast.LENGTH_LONG).show()
                } finally {
                    bindBtn.isEnabled = true
                }
            }
        }
    }

    private fun showHiddenDialog() {
        val hidden = Store.xiaomiHidden(this)
        val sheet = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(24))
        }
        container.addView(
            TextView(this).apply {
                text = "删除的快递"
                textSize = 22f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
        )
        container.addView(
            TextView(this).apply {
                text = if (hidden.isEmpty()) "暂无删除记录" else "点击恢复，可全部恢复"
                textSize = 13f
                setTextColor(onSurfaceVariant())
                setPadding(0, 2, 0, dp(8))
            }
        )
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(list)

        fun refresh() {
            list.removeAllViews()
            val items = Store.xiaomiHidden(this@SettingsActivity)
            for (item in items) {
                val row = LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    setBackgroundResource(android.R.attr.selectableItemBackground)
                    isClickable = true
                    setOnClickListener {
                        val current = Store.items(this@SettingsActivity).toMutableList()
                        if (current.none { it.mailNo == item.mailNo }) current.add(item)
                        Store.saveItems(this@SettingsActivity, current)
                        Store.restoreHidden(this@SettingsActivity, item.mailNo)
                        refreshButtons()
                        Toast.makeText(this@SettingsActivity, "已恢复 ${item.companyName}", Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                }
                row.addView(
                    TextView(this@SettingsActivity).apply {
                        text = item.companyName
                        textSize = 16f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                    }
                )
                row.addView(
                    TextView(this@SettingsActivity).apply {
                        text = item.mailNo
                        textSize = 13f
                        setTextColor(onSurfaceVariant())
                    }
                )
                list.addView(row)
            }
        }
        refresh()

        if (hidden.isNotEmpty()) {
            container.addView(
                com.google.android.material.button.MaterialButton(this).apply {
                    text = "全部恢复"
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(10) }
                    setOnClickListener {
                        val current = Store.items(this@SettingsActivity).toMutableList()
                        for (item in Store.xiaomiHidden(this@SettingsActivity)) {
                            if (current.none { it.mailNo == item.mailNo }) current.add(item)
                        }
                        Store.saveItems(this@SettingsActivity, current)
                        Store.clearHidden(this@SettingsActivity)
                        refreshButtons()
                        Toast.makeText(this@SettingsActivity, "已全部恢复", Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                }
            )
        }

        sheet.setContentView(container)
        sheet.show()
    }
}
