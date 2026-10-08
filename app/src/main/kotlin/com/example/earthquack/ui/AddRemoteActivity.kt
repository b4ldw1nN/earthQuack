package com.example.earthquack.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.earthquack.R
import com.example.earthquack.databinding.ActivityAddRemoteBinding
import com.example.earthquack.databinding.ItemProviderRowBinding
import com.example.earthquack.storage.ProviderInfo
import com.example.earthquack.storage.RcloneConfigManager
import com.example.earthquack.storage.RcloneEngine
import com.example.earthquack.storage.RcloneRemoteManager
import kotlinx.coroutines.launch

/**
 * Step 1 of adding an rclone remote: choose a backend.
 *
 * ## The list is rclone's, not ours
 *
 * Every entry comes from `config/providers`, which reports the backends actually
 * linked into this binary and the settings each accepts. Hard-coding provider
 * names would offer backends the app cannot configure — the set linked into a
 * gomobile AAR is a subset of upstream's ~40, and it changes whenever
 * `rclone-android/rclone.go` gains an import.
 *
 * ## Empty is a real state
 *
 * A build with no backends linked shows "No backends are linked into this build"
 * rather than an empty list, because an empty list would look like a loading
 * failure.
 */
class AddRemoteActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddRemoteBinding

    private val adapter = ProviderAdapter { provider ->
        startActivity(RemoteFormActivity.intent(this, provider.name))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddRemoteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.appbar.title.setText(R.string.add_remote_title)
        binding.appbar.subtitle.setText(R.string.add_remote_subtitle)
        binding.appbar.subtitle.visibility = View.VISIBLE
        binding.appbar.btnBack.visibility = View.VISIBLE
        binding.appbar.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.listProviders.layoutManager = LinearLayoutManager(this)
        binding.listProviders.adapter = adapter

        binding.btnProviderRetry.setOnClickListener { loadProviders() }
        loadProviders()
    }

    private fun loadProviders() {
        binding.providerMessage.visibility = View.VISIBLE
        binding.btnProviderRetry.visibility = View.GONE
        binding.textProviderMessage.setText(R.string.add_remote_loading)
        binding.listProviders.visibility = View.GONE

        // config/providers crosses the JNI boundary into rclone, so it must not
        // run on the main thread.
        lifecycleScope.launch {
            val result = runCatching {
                val cfg = RcloneConfigManager(applicationContext)
                cfg.ensureReady()
                val engine = RcloneEngine().also { it.initialize(cfg.configPath) }
                RcloneRemoteManager(engine).providers()
            }

            result.onSuccess { providers ->
                renderProviders(providers)
            }.onFailure { error ->
                binding.providerMessage.visibility = View.VISIBLE
                binding.listProviders.visibility = View.GONE
                binding.textProviderMessage.text =
                    getString(R.string.add_remote_error, error.message ?: "unknown error")
                binding.btnProviderRetry.visibility = View.VISIBLE
            }
        }
    }

    private fun renderProviders(providers: List<ProviderInfo>) {
        if (providers.isEmpty()) {
            binding.providerMessage.visibility = View.VISIBLE
            binding.listProviders.visibility = View.GONE
            binding.textProviderMessage.setText(R.string.add_remote_empty)
            binding.btnProviderRetry.visibility = View.GONE
            return
        }

        binding.providerMessage.visibility = View.GONE
        binding.listProviders.visibility = View.VISIBLE
        // Alphabetical, because there is no meaningful "most likely" order and
        // the list is long enough that order is the only way to find anything.
        adapter.submit(providers.sortedBy { it.name })
    }

    companion object {
        fun intent(context: Context): Intent =
            Intent(context, AddRemoteActivity::class.java)
    }
}

/** Provider rows. Small enough that a plain adapter beats DiffUtil plumbing. */
private class ProviderAdapter(
    private val onClick: (ProviderInfo) -> Unit
) : RecyclerView.Adapter<ProviderAdapter.Holder>() {

    private var items: List<ProviderInfo> = emptyList()

    fun submit(newItems: List<ProviderInfo>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemProviderRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val row: ItemProviderRowBinding) :
        RecyclerView.ViewHolder(row.root) {

        fun bind(provider: ProviderInfo) {
            row.providerName.text = provider.name
            row.providerDescription.text = provider.description
            row.providerDescription.visibility =
                if (provider.description.isBlank()) View.GONE else View.VISIBLE
            row.root.setOnClickListener { onClick(provider) }
        }
    }
}
