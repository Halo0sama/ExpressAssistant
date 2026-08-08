package com.halo.expressassistant.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.location.Geocoder
import android.location.LocationManager
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.halo.expressassistant.R
import com.halo.expressassistant.ApiServer
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputLayout
import com.halo.expressassistant.api.XiaomiApi
import com.halo.expressassistant.data.Store
import com.halo.expressassistant.databinding.ActivitySettingsBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var addressInput: EditText? = null

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
        binding.rowLocalApi.setOnClickListener { showLocalApiSheet() }
        binding.btnTaobao.setOnClickListener { showTaobaoDialog() }
        binding.rowAddress.setOnClickListener { showAddressSheet() }
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
        binding.localApiSummary.text = if (ApiServer.isRunning()) {
            "运行中 · 127.0.0.1:${ApiServer.PORT}"
        } else {
            "已关闭"
        }
        binding.addressSummary.text = Store.homeAddress(this).ifBlank { "未设置 · 用于 AI 计算进度" }
    }

    private fun showAddressSheet() {
        val sheet = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(24))
        }
        container.addView(
            TextView(this).apply {
                text = "我的地址"
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
            }
        )
        container.addView(
            TextView(this).apply {
                text = "用于 AI 计算运输进度与预计送达时间，也可以手动填写"
                textSize = 13f
                setTextColor(onSurfaceVariant())
                setPadding(0, 2, 0, dp(14))
            }
        )
        val inputLayout = layoutInflater.inflate(R.layout.view_input_outlined, null) as TextInputLayout
        inputLayout.hint = "收件地址"
        inputLayout.editText?.setText(Store.homeAddress(this))
        addressInput = inputLayout.editText
        container.addView(inputLayout)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, 0)
        }
        val locate = com.google.android.material.button.MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = "自动定位"
            setIconResource(R.drawable.ic_location)
            setOnClickListener {
                locateAddress(inputLayout.editText!!) {
                    Toast.makeText(this@SettingsActivity, "定位失败，请检查定位权限或手动填写", Toast.LENGTH_LONG).show()
                }
            }
        }
        buttons.addView(
            locate,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(10)
            }
        )
        buttons.addView(
            com.google.android.material.button.MaterialButton(this).apply {
                text = "保存"
                setOnClickListener {
                    val addr = inputLayout.editText?.text?.toString()?.trim().orEmpty()
                    Store.saveHomeAddress(this@SettingsActivity, addr)
                    val items = Store.items(this@SettingsActivity).map {
                        it.copy(aiProgress = -1, aiEta = "", aiProgressAt = "")
                    }
                    Store.saveItems(this@SettingsActivity, items)
                    refreshButtons()
                    Toast.makeText(this@SettingsActivity, "已保存", Toast.LENGTH_SHORT).show()
                    sheet.dismiss()
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        container.addView(buttons)
        sheet.setContentView(container)
        sheet.show()
    }

    private fun locateAddress(input: EditText, onFail: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQUEST_LOCATION
            )
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            val address = withContext(Dispatchers.IO) {
                try {
                    val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                        ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                        ?: return@withContext ""
                    val geocoder = Geocoder(this@SettingsActivity, Locale.CHINA)
                    val list = geocoder.getFromLocation(loc.latitude, loc.longitude, 1)
                    val a = list?.firstOrNull()
                    listOf(
                        a?.countryName,
                        a?.adminArea,
                        a?.locality,
                        a?.subLocality,
                        a?.thoroughfare,
                        a?.subThoroughfare
                    ).filterNotNull().joinToString("").ifBlank {
                        "${loc.latitude},${loc.longitude}"
                    }
                } catch (e: Throwable) {
                    ""
                }
            }
            if (address.isNotEmpty()) {
                input.setText(address)
            } else {
                onFail()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION) {
            val input = addressInput ?: return
            locateAddress(input) {
                Toast.makeText(this, "定位失败，请检查定位权限或手动填写", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showLocalApiSheet() {
        val sheet = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(24))
        }
        container.addView(
            TextView(this).apply {
                text = "本地接口（CLI / MCP）"
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
            }
        )
        container.addView(
            TextView(this).apply {
                text = "仅监听手机本机 127.0.0.1:${ApiServer.PORT}，通过 adb 端口转发后，可由命令行或 AI 调用。不会暴露到局域网。"
                textSize = 13f
                setTextColor(onSurfaceVariant())
                setPadding(0, 4, 0, dp(14))
                setLineSpacing(0f, 1.2f)
            }
        )

        container.addView(
            TextView(this).apply {
                text = "使用方式"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(6), 0, dp(6))
            }
        )
        val usage = "adb forward tcp:${ApiServer.PORT} tcp:${ApiServer.PORT}\n\n" +
            "python3 tools/express-cli.py list\n" +
            "python3 tools/express-cli.py mcp summarize '{\"question\":\"汇总我的快递\"}'"
        container.addView(
            TextView(this).apply {
                text = usage
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(MaterialColors.getColor(this@SettingsActivity, com.google.android.material.R.attr.colorOnSurfaceVariant, 0))
                setBackgroundColor(MaterialColors.getColor(this@SettingsActivity, com.google.android.material.R.attr.colorSurfaceContainerLow, 0))
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setLineSpacing(0f, 1.2f)
            }
        )

        val switchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        switchRow.addView(
            TextView(this).apply {
                text = "启用本地接口"
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        val sw = SwitchMaterial(this).apply {
            isChecked = Store.localApiEnabled(this@SettingsActivity)
        }
        switchRow.addView(sw)
        container.addView(switchRow)

        val status = TextView(this).apply {
            text = if (ApiServer.isRunning()) "状态：运行中" else "状态：已关闭"
            textSize = 13f
            setTextColor(onSurfaceVariant())
            setPadding(0, dp(4), 0, dp(10))
        }
        container.addView(status)

        sw.setOnCheckedChangeListener { _, checked ->
            Store.saveLocalApiEnabled(this@SettingsActivity, checked)
            if (checked) ApiServer.start(this@SettingsActivity) else ApiServer.stop()
            status.text = if (ApiServer.isRunning()) "状态：运行中" else "状态：已关闭"
            refreshButtons()
        }

        val scroll = ScrollView(this)
        scroll.addView(container)
        sheet.setContentView(scroll)
        sheet.show()
    }

    private fun showTaobaoDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("淘宝快递同步")
            .setMessage("若要同步淘宝的快递，需要在淘宝设置的隐私设置里关闭“订单号码保护”。")
            .setPositiveButton("好的", null)
            .show()
    }

    companion object {
        private const val REQUEST_LOCATION = 4001
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun onSurfaceVariant(): Int =
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)

    private fun surfaceLow(): Int =
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerLow, 0)

    private fun selectableBackground(): Int {
        val typed = android.util.TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, typed, true)
        return typed.resourceId
    }

    private fun outlinedInput(hint: String, text: String = "", number: Boolean = false): TextInputLayout {
        val layout = layoutInflater.inflate(R.layout.view_input_outlined, null) as TextInputLayout
        layout.hint = hint
        layout.editText?.apply {
            setText(text)
            setSingleLine(true)
            if (number) inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
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
                    setBackgroundResource(selectableBackground())
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
