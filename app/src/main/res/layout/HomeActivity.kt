/*
 * Copyright (c) 2020  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.ui.home

import android.app.ActivityOptions
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.Window
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.lifecycleScope
import com.gaurav.avnc.R
import com.gaurav.avnc.databinding.ActivityHomeBinding
import com.gaurav.avnc.model.ServerProfile
import com.gaurav.avnc.ui.about.AboutActivity
import com.gaurav.avnc.ui.prefs.PrefsActivity
import com.gaurav.avnc.ui.vnc.IntentReceiverActivity
import com.gaurav.avnc.ui.vnc.startVncActivity
import com.gaurav.avnc.util.Debugging
import com.gaurav.avnc.util.MsgDialog
import com.gaurav.avnc.viewmodel.HomeViewModel
import com.gaurav.avnc.vnc.VncClient
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Primary activity of the app.
 *
 * It Provides access to saved and discovered servers.
 */
class HomeActivity : AppCompatActivity() {
    val viewModel by viewModels<HomeViewModel>()
    private lateinit var binding: ActivityHomeBinding
    private val tabs = ServerTabs(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.App_Theme)
        super.onCreate(savedInstanceState)
        window.requestFeature(Window.FEATURE_ACTIVITY_TRANSITIONS)

        //View Inflation
        binding = DataBindingUtil.setContentView(this, R.layout.activity_home)
        binding.lifecycleOwner = this

        tabs.create(binding.tabLayout, binding.pager)

        binding.drawerNav.setNavigationItemSelectedListener { onMenuItemSelected(it.itemId) }
        binding.navigationBtn.setOnClickListener { binding.drawerLayout.open() }
        binding.settingsBtn.setOnClickListener { showSettings() }
        binding.urlbar.setOnClickListener { showUrlActivity() }

        // ===== ADDED: Setup PC Discovery Button =====
        binding.btnDiscoverPC.setOnClickListener { onDiscoverPCClick() }
        // ===== END ADDED =====

        //Observers
        viewModel.editProfileEvent.observe(this) { showProfileEditor(it) }
        viewModel.profileSavedEvent.observe(this) { onProfileInserted(it) }
        viewModel.profileDeletedEvent.observe(this) { onProfileDeleted(it) }
        viewModel.newConnectionEvent.observe(this) { startNewConnection(it) }
        viewModel.discovery.servers.observe(this) { updateDiscoveryBadge(it) }
        viewModel.serverProfiles.observe(this) { updateShortcuts(it) }

        setupSplashTheme()
        showWelcomeMsg()
        maybeAutoConnect(savedInstanceState == null)
    }

    override fun onStart() {
        super.onStart()
        viewModel.autoStartDiscovery()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations)
            viewModel.autoStopDiscovery()
    }

    // ===== ADDED: PC Discovery Function =====
    /**
     * Handles PC discovery button click
     */
    private fun onDiscoverPCClick() {
        binding.btnDiscoverPC.isEnabled = false
        binding.progressBarDiscovery.visibility = View.VISIBLE
        binding.tvDiscoveryStatus.text = getString(R.string.discovering_pc)

        // Use the PCDiscovery class to find the magic listener PC
        val discovery = PCDiscovery(this)
        discovery.discoverPC(object : PCDiscovery.DiscoveryListener {
            override fun onPCDiscovered(pcAddress: String) {
                runOnUiThread {
                    binding.progressBarDiscovery.visibility = View.GONE
                    binding.btnDiscoverPC.isEnabled = true
                    binding.tvDiscoveryStatus.text = getString(R.string.pc_found, pcAddress)
                    
                    // Create a server profile from the discovered PC
                    val profile = ServerProfile().apply {
                        name = "Lunar PC"
                        host = pcAddress
                        port = 5900 // Default VNC port
                    }
                    
                    // Start connection to the discovered PC
                    startNewConnection(profile)
                }
            }

            override fun onDiscoveryFailed(error: String) {
                runOnUiThread {
                    binding.progressBarDiscovery.visibility = View.GONE
                    binding.btnDiscoverPC.isEnabled = true
                    binding.tvDiscoveryStatus.text = error
                    Toast.makeText(this@HomeActivity, error, Toast.LENGTH_LONG).show()
                }
            }
        })
    }
    // ===== END ADDED =====

    /**
     * Handle drawer item selection.
     */
    private fun onMenuItemSelected(itemId: Int): Boolean {
        when (itemId) {
            R.id.settings -> showSettings()
            R.id.about -> showAbout()
            R.id.report_bug -> launchBugReport()
            else -> return false
        }
        binding.drawerLayout.close()
        return true
    }

    private fun startNewConnection(profile: ServerProfile) {
        if (checkNativeLib())
            startVncActivity(this, profile)
    }

    /**
     * Launches Settings activity
     */
    private fun showSettings() {
        startActivity(Intent(this, PrefsActivity::class.java))
    }

    private fun showAbout() {
        startActivity(Intent(this, AboutActivity::class.java))
    }

    private fun launchBugReport() {
        val url = AboutActivity.BUG_REPORT_URL + Debugging.bugReportUrlParams()
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    /**
     * Launches VNC Url activity
     */
    private fun showUrlActivity() {
        val anim = ActivityOptions.makeSceneTransitionAnimation(this, binding.urlbar, "urlbar")
        startActivity(Intent(this, UrlBarActivity::class.java), anim.toBundle())
    }

    private fun showProfileEditor(profile: ServerProfile) {
        startProfileEditor(this, profile, viewModel.pref.ui.preferAdvancedEditor)
    }

    private fun onProfileInserted(profile: ServerProfile) {
        tabs.showSavedServers()

        // Show snackbar for new servers
        if (profile.ID == 0L)
            Snackbar.make(binding.root, R.string.msg_server_profile_added, Snackbar.LENGTH_SHORT).show()
    }

    /**
     * Shows delete confirmation snackbar, allowing the user to Undo deletion.
     */
    private fun onProfileDeleted(profile: ServerProfile) {
        Snackbar.make(binding.root, R.string.msg_server_profile_deleted, Snackbar.LENGTH_LONG)
                .setAction(getString(R.string.title_undo)) { viewModel.saveProfile(profile) }
                .show()
    }

    private fun updateDiscoveryBadge(list: List<ServerProfile>) {
        tabs.updateDiscoveryBadge(list.size)
    }

    private fun showWelcomeMsg() {
        /*if (!viewModel.pref.runInfo.hasShownV2WelcomeMsg) {
            viewModel.pref.runInfo.hasShownV2WelcomeMsg = true
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).let {
                if (it.lastUpdateTime > it.firstInstallTime)
                    WelcomeFragment().show(supportFragmentManager, "WelcomeV2")
            }
        }*/
    }

    /**
     * Warns about missing native library.
     * This can happen if AVNC is installed by copying APK from a device with different architecture.
     */
    private fun checkNativeLib(): Boolean {
        return runCatching {
            VncClient.loadLibrary()
        }.onFailure {
            val msg = "You may have installed AVNC using an incorrect APK. " +
                      "Please install correct version from F-Droid or Google Play."
            MsgDialog.show(supportFragmentManager, "Native library is missing!", msg)
        }.isSuccess
    }

    /**
     * Updates splash theme to match with app theme
     */
    private fun setupSplashTheme() {
        if (Build.VERSION.SDK_INT < 31)
            return

        viewModel.pref.ui.theme.observe(this) {
            when (it) {
                "light" -> splashScreen.setSplashScreenTheme(R.style.App_SplashTheme_Light)
                "dark" -> splashScreen.setSplashScreenTheme(R.style.App_SplashTheme_Dark)
                else -> splashScreen.setSplashScreenTheme(R.style.App_SplashTheme)
            }
        }
    }

    private fun maybeAutoConnect(isNewStart: Boolean) {
        if (isNewStart)
            viewModel.maybeConnectOnAppStart()
    }

    /************************************************************************************
     * Shortcuts
     ************************************************************************************/

    private fun createShortcutId(profile: ServerProfile) = "shortcut:pid:${profile.ID}"

    private fun updateShortcuts(profiles: List<ServerProfile>) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val sortedProfiles = profiles.sortedByDescending { it.useCount }
                updateShortcutState(sortedProfiles)
                updateDynamicShortcuts(sortedProfiles)
            }.onFailure {
                Log.e("Shortcuts", "Unable to update shortcuts", it)
            }
        }
    }

    /**
     * Enable/Disable shortcuts based on availability in [profiles]
     */
    private fun updateShortcutState(profiles: List<ServerProfile>) {
        val pinnedShortcuts = ShortcutManagerCompat.getShortcuts(this, ShortcutManagerCompat.FLAG_MATCH_PINNED)
        val disabledMessage = getString(R.string.msg_shortcut_server_deleted)

        val possibleIds = profiles.map { createShortcutId(it) }
        val pinnedIds = pinnedShortcuts.map { it.id }
        val enabledIds = pinnedIds.intersect(possibleIds).toList()
        val enabledShortcuts = pinnedShortcuts.filter { it.id in enabledIds }
        val disabledIds = pinnedIds.subtract(enabledIds).toList()

        ShortcutManagerCompat.enableShortcuts(this, enabledShortcuts)
        ShortcutManagerCompat.disableShortcuts(this, disabledIds, disabledMessage)
    }

    /**
     * Updates dynamic shortcut list
     */
    private fun updateDynamicShortcuts(profiles: List<ServerProfile>) {
        val maxShortcuts = ShortcutManagerCompat.getMaxShortcutCountPerActivity(this)
        val shortcuts = profiles.take(maxShortcuts).mapIndexed { i, p ->
            ShortcutInfoCompat.Builder(this, createShortcutId(p))
                    .setIcon(IconCompat.createWithResource(this, R.drawable.ic_computer_shortcut))
                    .setShortLabel(p.name.ifBlank { p.host })
                    .setLongLabel(p.name.ifBlank { p.host })
                    .setRank(i)
                    .setIntent(IntentReceiverActivity.createShortcutIntent(this, p.ID))
                    .build()
        }
        ShortcutManagerCompat.setDynamicShortcuts(this, shortcuts)
    }
}