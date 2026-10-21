package com.example.earthquack.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.earthquack.tailscale.TaildropManager
import com.example.earthquack.tailscale.TaildropNotifications
import com.example.earthquack.tailscale.TaildropOffer
import com.example.earthquack.tailscale.NativeTaildropControl
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * Minimal UI for incoming Taildrop offers.
 */
class TaildropFragment : Fragment() {

    private val scope = MainScope()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val tv = TextView(requireContext())
        tv.text = "Taildrop"
        tv.setPadding(32, 32, 32, 32)
        return tv
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val manager = TaildropManager(NativeTaildropControl, context = requireContext())
        val notifier = TaildropNotifications(requireContext())
        notifier.ensureChannel()
        scope.launch {
            manager.pendingOffers().forEach { notifier.showOffer(it) }
        }
    }
}