package com.example.earthquack.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.earthquack.R
import com.example.earthquack.databinding.FragmentProfilesBinding
import com.example.earthquack.databinding.ItemProfileRowBinding
import com.example.earthquack.ssh.ConnectionProfile
import com.example.earthquack.ssh.SshServices
import com.example.earthquack.ssh.KnownHostsStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Lists the saved connection profiles and opens each one for editing.
 *
 * The store is snapshot based, so the list is polled: a profile added or
 * removed in [ProfileEditorActivity] shows up here on the next tick because
 * both screens read from the same [SshServices.profiles] store.
 */
class ProfilesFragment : Fragment() {

    private var _binding: FragmentProfilesBinding? = null
    private val binding get() = _binding!!

    private val profileStore by lazy { SshServices.profiles(requireContext()) }
    private val knownHosts by lazy { SshServices.knownHosts(requireContext()) }

    private var profiles: List<ConnectionProfile> = emptyList()



    private val adapter = ProfilesAdapter(
        onOpen = { profile -> editProfile(profile) },
        onOverflow = { view, profile -> showRowMenu(view, profile) }
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfilesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.listProfiles.layoutManager = LinearLayoutManager(requireContext())
        binding.listProfiles.adapter = adapter
        binding.listProfiles.setHasFixedSize(true)

        binding.btnAdd.setOnClickListener { addProfile() }

        refresh()
    }

    /** Pull the latest list whenever the fragment is on screen. */
    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun refresh() {
        profiles = profileStore.list()
        binding.listProfiles.visibility = if (profiles.isEmpty()) View.GONE else View.VISIBLE
        binding.emptyState.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE

        binding.statusProfiles.statusText.text =
            if (profiles.isEmpty()) {
                ""
            } else {
                getString(R.string.profiles_count, profiles.size)
            }
        binding.statusProfiles.statusSub.text = when {
            profiles.isEmpty() -> getString(R.string.profiles_empty_sub)
            else -> getString(R.string.profiles_subtitle)
        }
        adapter.submitList(profiles)
    }

    private fun addProfile() {
        startActivity(ProfileEditorActivity.intent(requireContext()))
    }

    private fun editProfile(profile: ConnectionProfile) {
        startActivity(ProfileEditorActivity.intent(requireContext(), profile.id))
    }

    /** Not implemented yet: replaced by the SSH terminal screen. */
    private fun openTerminal(profile: ConnectionProfile) {
        toast(R.string.profile_terminal_not_implemented)
    }

    /** Not implemented yet: replaced by the file browser screen. */
    private fun openFiles(profile: ConnectionProfile) {
        toast(R.string.profile_files_not_implemented)
    }

    private fun deleteProfile(profile: ConnectionProfile) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.profiles_delete)
            .setMessage(R.string.profiles_delete_confirm)
            .setPositiveButton(R.string.profiles_delete) { _, _ ->
                profileStore.delete(profile.id, knownHosts)
                toast(R.string.profiles_deleted)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRowMenu(view: View, profile: ConnectionProfile) {
        PopupMenu(requireContext(), view).apply {
            inflate(R.menu.profile_row_menu)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.menu_terminal -> {
                        openTerminal(profile)
                        true
                    }
                    R.id.menu_files -> {
                        openFiles(profile)
                        true
                    }
                    R.id.menu_delete -> {
                        deleteProfile(profile)
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun toast(res: Int) = Toast.makeText(requireContext(), res, Toast.LENGTH_SHORT).show()

    /** One row: the name plus the endpoint (host and, optionally, the user). */

    private class ProfilesAdapter(
        private val onOpen: (ConnectionProfile) -> Unit,
        private val onOverflow: (View, ConnectionProfile) -> Unit
    ) : ListAdapter<ConnectionProfile, ProfilesAdapter.ViewHolder>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_profile_row, parent, false)
            return ViewHolder(ItemProfileRowBinding.bind(view))
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class ViewHolder(private val binding: ItemProfileRowBinding) :
            RecyclerView.ViewHolder(binding.root) {

            fun bind(profile: ConnectionProfile) {
                binding.rowName.text = profile.name
                val suffix = if (profile.username.isBlank()) {
                    ""
                } else {
                    " · " + profile.username
                }
                binding.rowSubtitle.text = profile.host + suffix

                binding.root.setOnClickListener { onOpen(profile) }

                binding.btnOverflow.setOnClickListener { onOverflow(it, profile) }
            }
        }

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<ConnectionProfile>() {
                override fun areItemsTheSame(
                    old: ConnectionProfile,
                    new: ConnectionProfile
                ): Boolean = old.id == new.id

                override fun areContentsTheSame(
                    old: ConnectionProfile,
                    new: ConnectionProfile
                ): Boolean = old == new
            }
        }
    }
}
