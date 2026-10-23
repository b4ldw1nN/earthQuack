package io.earthquack.app.ui

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import io.earthquack.app.R
import io.earthquack.app.ssh.ConnectionProfile
import io.earthquack.app.ssh.ProbeResult
import io.earthquack.app.ssh.SshFailure
import io.earthquack.app.ssh.SshProfileProbe
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs a Test-connection attempt and shows its result, from any screen.
 *
 * ## The one rule it enforces
 *
 * A host key that has never been seen is *shown*, not recorded. The dialog
 * states the fingerprint, and only an explicit accept records it
 * ([SshProfileProbe.acceptHostKey]) before the retry connects. A key that
 * changed is reported and refused, with no accept button — which is the
 * whole point of the branch.
 *
 * @param onFinished invoked after a successful test, so a caller can close an
 *   editor that was only validating its form.
 */
fun runProfileTest(
    activity: FragmentActivity,
    profile: ConnectionProfile,
    probe: SshProfileProbe = SshProfileProbe.create(activity),
    onFinished: (Boolean) -> Unit = {}
) {
    activity.lifecycleScope.launch {
        val result = withContext(Dispatchers.IO) { probe.connect(profile) }
        showProbeResult(activity, profile, result, probe, onFinished)
    }
}

private fun showProbeResult(
    activity: FragmentActivity,
    profile: ConnectionProfile,
    result: ProbeResult,
    probe: SshProfileProbe,
    onFinished: (Boolean) -> Unit
) {
    when (result) {
        ProbeResult.Connected -> {
            onFinished(true)
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.profiles_test_success_title)
                .setMessage(
                    activity.getString(
                        R.string.profiles_test_success_body,
                        profile.name,
                        profile.endpoint
                    )
                )
                .setPositiveButton(R.string.action_dismiss, null)
                .show()
        }
        is ProbeResult.Failed -> when (val failure = result.failure) {
            is SshFailure.UnknownHost -> {
                // First sight of this host: the fingerprint is the decision.
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.profiles_test_host_key_unknown_title)
                    .setMessage(
                        activity.getString(
                            R.string.profiles_test_host_key_unknown_body,
                            profile.endpoint,
                            failure.fingerprint
                        )
                    )
                    .setNegativeButton(R.string.profiles_test_decline, null)
                    .setPositiveButton(R.string.profiles_test_accept) { _, _ ->
                        // Recorded only here, on explicit consent.
                        if (probe.acceptHostKey(profile.id)) {
                            runProfileTest(activity, profile, probe, onFinished)
                        }
                    }
                    .show()
            }
            else -> {
                onFinished(false)
                val builder = MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.profiles_test_failed_title)
                    .setMessage(
                        activity.getString(
                            R.string.profiles_test_failed_body,
                            profile.endpoint,
                            failure.message
                        )
                    )
                // A retry is offered only when retrying could plausibly help;
                // a rejected key or missing credential never will.
                if (failure.retryable) {
                    builder.setPositiveButton(R.string.profiles_test_retry) { _, _ ->
                        runProfileTest(activity, profile, probe, onFinished)
                    }
                } else {
                    builder.setPositiveButton(R.string.action_dismiss, null)
                }
                builder.show()
            }
        }
    }
}
