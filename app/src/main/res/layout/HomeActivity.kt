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
import android.view.Window
import android.widget.Button
import android.view.ViewGroup
import android.speech.RecognizerIntent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.lifecycleScope
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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

        // ===== ORIGINAL: Setup Discover Button =====
        binding.btnDiscoverPC.setOnClickListener {
            onDiscoverPCClick()
            binding.tvDiscoveryStatus.text = "AI: Looking for your Lunar PC..."
        }

        // ===== NEW: Add Voice Button to Layout Programmatically =====
        val voiceButton = Button(this).apply {
            text = "🎤 Voice AI"
            id = R.id.ai_voice_btn
            setOnClickListener { 
                if (checkAudioPermission()) {
                    startVoiceRecognition()
                } else {
                    requestAudioPermission()
                }
            }
        }
        
        // Add voice button to your layout (adjust based on your layout structure)
        (binding.root as? ViewGroup)?.addView(voiceButton)

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

    // ===== VOICE RECOGNITION FUNCTIONS =====
    private fun checkAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == 
               PackageManager.PERMISSION_GRANTED
    }

    private fun requestAudioPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(android.Manifest.permission.RECORD_AUDIO),
            123
        )
    }

    private fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Say something like 'Find my PC' or 'Tell me a story'")
        }
        
        try {
            startActivityForResult(intent, 456)
        } catch (e: Exception) {
            Toast.makeText(this, "Voice recognition not supported", Toast.LENGTH_SHORT).show()
        }
    }

    // Handle voice recognition results
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == 456 && resultCode == RESULT_OK) {
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = results?.get(0) ?: ""
            
            val aiResponse = handleAICommand(spokenText)
            binding.tvDiscoveryStatus.text = "Voice: $aiResponse"
            Toast.makeText(this, "Heard: $spokenText", Toast.LENGTH_SHORT).show()
        }
    }

    // Handle permission results
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == 123 && grantResults.isNotEmpty() && 
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startVoiceRecognition()
        }
    }
    // ===== END VOICE FUNCTIONS =====

    // ===== ADDED: AI Functions =====
    /**
     * Handles AI commands and responses
     */
    private fun handleAICommand(command: String): String {
        return when {
            command.contains("find my pc", ignoreCase = true) -> {
                onDiscoverPCClick()
                "Looking for your Lunar PC..."
            }
            command.contains("story", ignoreCase = true) -> 
                "Once upon a time in the world of AR glasses..."
            command.contains("news", ignoreCase = true) -> 
                "Latest news: AI assistants are becoming reality!"
            command.contains("search", ignoreCase = true) -> 
                "I can help you search for information"
            command.contains("problem", ignoreCase = true) -> 
                "Let me think about solving that problem..."
            command.contains("connect", ignoreCase = true) -> 
                "Connecting to your Lunar PC..."
            command.contains("disconnect", ignoreCase = true) -> 
                "Disconnecting from remote session..."
            else -> "I'm your AR assistant! How can I help?"
        }
    }

    /**
     * Handles AI button click or voice command
     */
    fun onAIClick() {
        val userInput = "find my pc"  // This will trigger PC discovery
        val response = handleAICommand(userInput)
        
        binding.tvDiscoveryStatus.text = response
        Toast.makeText(this, response, Toast.LENGTH_SHORT).show()
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