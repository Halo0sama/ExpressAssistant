package com.halo.expressassistant.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.halo.expressassistant.data.Store
import com.halo.expressassistant.databinding.ActivityMoreConnectionsBinding

class MoreConnectionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMoreConnectionsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMoreConnectionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        EdgeToEdge.apply(this, binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.kdKey.editText?.setText(Store.kdKey(this))
        binding.kdCustomer.editText?.setText(Store.kdCustomer(this))
        binding.swKd.isChecked = Store.kd100Fallback(this)
        binding.swA11y.isChecked = Store.accessibilityEnabled(this)

        binding.btnSave.setOnClickListener {
            Store.saveSettings(
                this,
                Store.aiBase(this),
                Store.aiKey(this),
                Store.aiModel(this),
                binding.kdKey.editText?.text?.toString()?.trim().orEmpty(),
                binding.kdCustomer.editText?.text?.toString()?.trim().orEmpty(),
                Store.reportTime(this).first,
                Store.reportTime(this).second,
                binding.swKd.isChecked,
                binding.swA11y.isChecked
            )
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
