/*
 * *************************************************************************
 *  PreferencesFragment.java
 * **************************************************************************
 *  Copyright © 2015 VLC authors and VideoLAN
 *  Author: Geoffrey Métais
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *  ***************************************************************************
 */

package org.videolan.vlc.gui.preferences

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentActivity
import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import org.videolan.libvlc.util.AndroidUtil
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.resources.util.parcelable
import org.videolan.tools.AUDIO_RESUME_PLAYBACK
import org.videolan.tools.KEY_AUDIO_LAST_PLAYLIST
import org.videolan.tools.KEY_CURRENT_AUDIO
import org.videolan.tools.KEY_CURRENT_AUDIO_RESUME_ARTIST
import org.videolan.tools.KEY_CURRENT_AUDIO_RESUME_THUMB
import org.videolan.tools.KEY_CURRENT_AUDIO_RESUME_TITLE
import org.videolan.tools.KEY_CURRENT_MEDIA
import org.videolan.tools.KEY_CURRENT_MEDIA_RESUME
import org.videolan.tools.KEY_MEDIA_LAST_PLAYLIST
import org.videolan.tools.KEY_MEDIA_LAST_PLAYLIST_RESUME
import org.videolan.tools.PLAYBACK_HISTORY
import org.videolan.tools.RESULT_RESTART
import org.videolan.tools.Settings
import org.videolan.tools.Settings.isPinCodeSet
import org.videolan.tools.VIDEO_RESUME_PLAYBACK
import org.videolan.vlc.R
import org.videolan.vlc.gui.EqualizerSettingsActivity
import org.videolan.vlc.gui.PinCodeActivity
import org.videolan.vlc.gui.PinCodeReason
import org.videolan.vlc.gui.SecondaryActivity
import org.videolan.vlc.gui.dialogs.CONFIRM_PREFERENCE_CHANGE_DIALOG_RESULT
import org.videolan.vlc.gui.dialogs.ConfirmPreferenceChangeDialog
import org.videolan.vlc.gui.dialogs.PREFERENCE_KEY
import org.videolan.vlc.gui.dialogs.PermissionListDialog
import org.videolan.vlc.gui.helpers.UiTools
import org.videolan.vlc.gui.preferences.search.PreferenceItem
import org.videolan.vlc.util.Permissions

class PreferencesFragment : BasePreferenceFragment(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var audioResumePref: CheckBoxPreference
    var pinCodeResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            loadFragment(PreferencesParentalControl())
        }
    }

    // Visible, read-only report for this media player's own permissions.
    private val toolboxCredentialResult = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val current = activity ?: return@registerForActivityResult
        if (result.resultCode != Activity.RESULT_OK || !isAdded ||
            Settings.getInstance(current).getBoolean(org.videolan.tools.KEY_RESTRICT_SETTINGS, false)) {
            return@registerForActivityResult
        }
        showToolboxPrivacyReport(current)
    }

    @Suppress("DEPRECATION")
    private fun requestToolboxCredential() {
        val current = activity ?: return
        if (Settings.getInstance(current).getBoolean(org.videolan.tools.KEY_RESTRICT_SETTINGS, false)) {
            android.widget.Toast.makeText(current, R.string.media_toolbox_restricted, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val manager = current.getSystemService(android.content.Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        if (android.os.Build.VERSION.SDK_INT < 23 || manager == null || !manager.isDeviceSecure) {
            android.widget.Toast.makeText(current, "Kunci layar perangkat diperlukan untuk laporan privasi.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val intent = manager.createConfirmDeviceCredentialIntent(
            "Media Toolbox", "Konfirmasi kunci layar untuk melihat laporan izin pemutar media."
        ) ?: return
        try {
            toolboxCredentialResult.launch(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            android.widget.Toast.makeText(current, R.string.media_toolbox_unavailable, android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            android.widget.Toast.makeText(current, R.string.media_toolbox_unavailable, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    @Suppress("DEPRECATION")
    private fun showToolboxPrivacyReport(current: FragmentActivity) {
        val info = try {
            current.packageManager.getPackageInfo(current.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            android.widget.Toast.makeText(current, R.string.media_toolbox_unavailable, android.widget.Toast.LENGTH_SHORT).show()
            return
        } catch (_: SecurityException) {
            android.widget.Toast.makeText(current, R.string.media_toolbox_unavailable, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val permissions = info.requestedPermissions.orEmpty()
        val flags = info.requestedPermissionsFlags
        val report = buildString {
            append("Izin pemutar media ini saja. Laporan hanya-baca.\n\n")
            if (permissions.isEmpty()) append("Tidak ada izin yang dilaporkan.")
            permissions.forEachIndexed { index, permission ->
                val flag = flags?.getOrNull(index)
                val status = when {
                    flag == null -> "Tidak diketahui"
                    flag and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED != 0 -> "Diizinkan"
                    else -> "Tidak diizinkan"
                }
                append(permission).append("\n").append(status).append("\n\n")
            }
        }
        androidx.appcompat.app.AlertDialog.Builder(current)
            .setTitle("Laporan privasi Media Toolbox")
            .setMessage(report)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // Ordinary app shortcuts only: never change launcher visibility or bypass restrictions.
    @Suppress("DEPRECATION")
    private fun showToolboxAppFavorites() {
        val current = activity ?: return
        if (Settings.getInstance(current).getBoolean(org.videolan.tools.KEY_RESTRICT_SETTINGS, false)) {
            android.widget.Toast.makeText(current, R.string.media_toolbox_restricted, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val pm = current.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = try {
            pm.queryIntentActivities(query, 0)
                .filter { it.activityInfo != null && it.activityInfo.packageName != current.packageName }
                .distinctBy { it.activityInfo.packageName }
                .sortedBy { it.loadLabel(pm).toString().lowercase(java.util.Locale.ROOT) }
        } catch (_: SecurityException) {
            android.widget.Toast.makeText(current, R.string.media_toolbox_unavailable, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val prefs = current.getSharedPreferences("media_toolbox_favorites", android.content.Context.MODE_PRIVATE)
        val favorites = prefs.getStringSet("packages", emptySet())?.toMutableSet() ?: mutableSetOf()
        val visiblePackages = apps.map { it.activityInfo.packageName }.toSet()
        favorites.retainAll(visiblePackages)
        prefs.edit().putStringSet("packages", HashSet(favorites)).apply()

        fun dp(value: Int): Int = (value * current.resources.displayMetrics.density + 0.5f).toInt()
        val content = android.widget.LinearLayout(current).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val explanation = android.widget.TextView(current).apply {
            text = "Daftar aplikasi biasa dan favorit. Tombol Buka memakai launcher intent standar Android. Tidak mengubah visibilitas atau izin aplikasi."
        }
        val search = android.widget.EditText(current).apply {
            hint = "Cari nama aplikasi"
            isSingleLine = true
        }
        val favoritesOnly = android.widget.CheckBox(current).apply {
            text = "Hanya favorit"
        }
        val counter = android.widget.TextView(current)
        val rows = android.widget.LinearLayout(current).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        val scroll = android.widget.ScrollView(current).apply {
            addView(rows)
        }
        content.addView(explanation)
        content.addView(search)
        content.addView(favoritesOnly)
        content.addView(counter)
        content.addView(scroll, android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(280)
        ))

        fun render() {
            rows.removeAllViews()
            val term = search.text.toString().trim()
            val filtered = apps.filter {
                (!favoritesOnly.isChecked || it.activityInfo.packageName in favorites) &&
                    (it.loadLabel(pm).toString().contains(term, ignoreCase = true) ||
                        it.activityInfo.packageName.contains(term, ignoreCase = true))
            }
            counter.text = "${filtered.size} aplikasi • ${favorites.size} favorit"
            if (filtered.isEmpty()) rows.addView(android.widget.TextView(current).apply {
                text = "Tidak ada aplikasi yang cocok."
                setPadding(0, dp(12), 0, dp(12))
            })
            filtered.forEach { app ->
                val packageName = app.activityInfo.packageName
                val row = android.widget.LinearLayout(current).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4), 0, dp(4))
                }
                val icon = android.widget.ImageView(current).apply {
                    setImageDrawable(app.loadIcon(pm))
                    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                val check = android.widget.CheckBox(current).apply {
                    text = app.loadLabel(pm).toString()
                    isChecked = packageName in favorites
                    setOnCheckedChangeListener { _, checked ->
                        if (Settings.getInstance(current).getBoolean(org.videolan.tools.KEY_RESTRICT_SETTINGS, false)) return@setOnCheckedChangeListener
                        if (checked) favorites.add(packageName) else favorites.remove(packageName)
                        prefs.edit().putStringSet("packages", HashSet(favorites)).apply()
                        if (favoritesOnly.isChecked) render()
                        else counter.text = "${filtered.size} aplikasi • ${favorites.size} favorit"
                    }
                }
                row.addView(icon, android.widget.LinearLayout.LayoutParams(dp(36), dp(36)))
                row.addView(check, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val open = android.widget.Button(current).apply {
                    text = "Buka"
                    contentDescription = "Buka " + app.loadLabel(pm).toString()
                    isEnabled = !current.isPinCodeSet() &&
                        !Settings.getInstance(current).getBoolean(org.videolan.tools.KEY_RESTRICT_SETTINGS, false)
                    setOnClickListener {
                        if (current.isPinCodeSet() ||
                            Settings.getInstance(current).getBoolean(org.videolan.tools.KEY_RESTRICT_SETTINGS, false)) {
                            android.widget.Toast.makeText(current, R.string.media_toolbox_restricted, android.widget.Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        try {
                            val ordinary = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                                .setPackage(packageName)
                            if (pm.queryIntentActivities(ordinary, 0).none { it.activityInfo?.enabled == true && it.activityInfo?.exported == true }) {
                                android.widget.Toast.makeText(current, "Aplikasi tidak tersedia.", android.widget.Toast.LENGTH_SHORT).show()
                                return@setOnClickListener
                            }
                            current.startActivity(ordinary)
                        } catch (_: android.content.ActivityNotFoundException) {
                            android.widget.Toast.makeText(current, "Aplikasi tidak tersedia.", android.widget.Toast.LENGTH_SHORT).show()
                        } catch (_: SecurityException) {
                            android.widget.Toast.makeText(current, R.string.media_toolbox_restricted, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                row.addView(open, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
                rows.addView(row)
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render() }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        favoritesOnly.setOnCheckedChangeListener { _, _ -> render() }
        render()
        androidx.appcompat.app.AlertDialog.Builder(current)
            .setTitle("Daftar aplikasi & favorit")
            .setView(content)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun getXml() = R.xml.preferences

    override fun getTitleId() = R.string.preferences

    override fun onStart() {
        super.onStart()
        preferenceScreen.sharedPreferences!!.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onStop() {
        super.onStop()
        preferenceScreen.sharedPreferences!!.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        audioResumePref = findPreference(AUDIO_RESUME_PLAYBACK)!!
        findPreference<Preference>("media_toolbox_app_permissions")?.parent?.addPreference(
            Preference(requireContext()).apply {
                key = "media_toolbox_app_favorites"
                title = "Daftar aplikasi & favorit"
                summary = "Cari dan tandai aplikasi biasa; tidak mengubah launcher"
                isPersistent = false
                setOnPreferenceClickListener {
                    showToolboxAppFavorites()
                    true
                }
            }
        )
        findPreference<Preference>("media_toolbox_app_permissions")?.parent?.addPreference(
            Preference(requireContext()).apply {
                key = "media_toolbox_privacy_report"
                title = "Laporan privasi"
                summary = "Periksa izin pemutar media dengan autentikasi kunci layar"
                isPersistent = false
                setOnPreferenceClickListener {
                    requestToolboxCredential()
                    true
                }
            }
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        arguments?.parcelable<PreferenceItem>(EXTRA_PREF_END_POINT)?.let { endPoint ->
            when (endPoint.parentScreen) {
                R.xml.preferences_ui -> loadFragment(PreferencesUi().apply {
                    arguments = bundleOf(EXTRA_PREF_END_POINT to endPoint)
                })
                R.xml.preferences_video -> loadFragment(PreferencesVideo().apply {
                    arguments = bundleOf(EXTRA_PREF_END_POINT to endPoint)
                })
                R.xml.preferences_subtitles -> loadFragment(PreferencesSubtitles().apply {
                    arguments = bundleOf(EXTRA_PREF_END_POINT to endPoint)
                })
                R.xml.preferences_audio -> loadFragment(PreferencesAudio().apply {
                    arguments = bundleOf(EXTRA_PREF_END_POINT to endPoint)
                })
                R.xml.preferences_adv -> loadFragment(PreferencesAdvanced().apply {
                    arguments = bundleOf(EXTRA_PREF_END_POINT to endPoint)
                })
                R.xml.preferences_casting -> loadFragment(PreferencesCasting().apply {
                    arguments = bundleOf(EXTRA_PREF_END_POINT to endPoint)
                })
                R.xml.preferences_remote_access -> loadFragment(PreferencesRemoteAccess().apply {
                    arguments = bundleOf(EXTRA_PREF_END_POINT to endPoint)
                })
                R.xml.preferences_android_auto -> loadFragment(PreferencesAndroidAuto().apply {
                    arguments = bundleOf(EXTRA_PREF_END_POINT to endPoint)
                })
            }
            arguments = null
        }
        requireActivity().supportFragmentManager.setFragmentResultListener(CONFIRM_PREFERENCE_CHANGE_DIALOG_RESULT, viewLifecycleOwner) { requestKey, bundle ->
            when (bundle.getString(PREFERENCE_KEY, "")) {
                AUDIO_RESUME_PLAYBACK -> {
                    Settings.getInstance(requireActivity()).edit()
                        .remove(KEY_AUDIO_LAST_PLAYLIST)
                        .remove(KEY_MEDIA_LAST_PLAYLIST_RESUME)
                        .remove(KEY_CURRENT_AUDIO_RESUME_TITLE)
                        .remove(KEY_CURRENT_AUDIO_RESUME_ARTIST)
                        .remove(KEY_CURRENT_AUDIO_RESUME_THUMB)
                        .remove(KEY_CURRENT_AUDIO)
                        .remove(KEY_CURRENT_MEDIA)
                        .remove(KEY_CURRENT_MEDIA_RESUME)
                        .apply()
                    val activity = activity
                    activity?.setResult(RESULT_RESTART)
                    audioResumePref.isChecked = false
                }
                PLAYBACK_HISTORY -> findPreference<CheckBoxPreference>(PLAYBACK_HISTORY)?.isChecked = false
            }
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            "media_toolbox_app_permissions" -> {
                val current = requireActivity()
                // Keep this shortcut unavailable whenever VLC settings are restricted.
                if (Settings.getInstance(current).getBoolean(org.videolan.tools.KEY_RESTRICT_SETTINGS, false)) {
                    android.widget.Toast.makeText(current, R.string.media_toolbox_restricted, android.widget.Toast.LENGTH_SHORT).show()
                    return true
                }
                val intent = Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", current.packageName, null)
                )
                try {
                    startActivity(intent)
                } catch (_: android.content.ActivityNotFoundException) {
                    android.widget.Toast.makeText(current, R.string.media_toolbox_unavailable, android.widget.Toast.LENGTH_SHORT).show()
                } catch (_: SecurityException) {
                    android.widget.Toast.makeText(current, R.string.media_toolbox_unavailable, android.widget.Toast.LENGTH_SHORT).show()
                }
                return true
            }
            "directories" -> {
                if (Medialibrary.getInstance().isWorking) {
                    UiTools.snacker(requireActivity(), getString(R.string.settings_ml_block_scan))
                } else {
                    val activity = requireActivity()
                    val intent = Intent(activity.applicationContext, SecondaryActivity::class.java)
                    intent.putExtra("fragment", SecondaryActivity.STORAGE_BROWSER)
                    startActivity(intent)
                    activity.setResult(RESULT_RESTART)
                }
                return true
            }
            "ui_category" -> loadFragment(PreferencesUi())
            "video_category" -> loadFragment(PreferencesVideo())
            "subtitles_category" -> loadFragment(PreferencesSubtitles())
            "audio_category" -> loadFragment(PreferencesAudio())
            "equalizer" -> startActivity(Intent(requireActivity().applicationContext, EqualizerSettingsActivity::class.java))
            "adv_category" -> loadFragment(PreferencesAdvanced())
            "casting_category" -> loadFragment(PreferencesCasting())
            "parental_control" -> {
                if (requireActivity().isPinCodeSet())
                    loadFragment(PreferencesParentalControl())
                else {
                    val intent = PinCodeActivity.getIntent(requireActivity(), PinCodeReason.FIRST_CREATION)
                    pinCodeResult.launch(intent)
                }
            }
            "permissions" -> {
                PermissionListDialog.newInstance().show(requireActivity().supportFragmentManager, "PermissionListDialog")

            }
            "remote_access_category" -> loadFragment(PreferencesRemoteAccess())
            "android_auto_category" -> loadFragment(PreferencesAndroidAuto())
            PLAYBACK_HISTORY -> {
                val activity = activity
                activity?.setResult(RESULT_RESTART)
                if (!(preference as CheckBoxPreference).isChecked) {
                    val dialog = ConfirmPreferenceChangeDialog.newInstance(PLAYBACK_HISTORY,getString(R.string.playback_history_title),getString(R.string.playback_history_warning))
                    dialog.show((activity as FragmentActivity).supportFragmentManager, ConfirmPreferenceChangeDialog::class.simpleName)
                    preference.isChecked = true
                }
                return true
            }
            AUDIO_RESUME_PLAYBACK -> {
                if (!audioResumePref.isChecked) {
                    val dialog = ConfirmPreferenceChangeDialog.newInstance(AUDIO_RESUME_PLAYBACK,getString(R.string.audio_resume_playback_title),getString(R.string.audio_resume_playback_warning))
                    dialog.show((activity as FragmentActivity).supportFragmentManager, ConfirmPreferenceChangeDialog::class.simpleName)
                    audioResumePref.isChecked = true
                }
                return true
            }
            VIDEO_RESUME_PLAYBACK -> {
                Settings.getInstance(requireActivity()).edit()
                        .remove(KEY_MEDIA_LAST_PLAYLIST)
                        .remove(KEY_MEDIA_LAST_PLAYLIST_RESUME)
                        .remove(KEY_CURRENT_MEDIA_RESUME)
                        .remove(KEY_CURRENT_MEDIA)
                        .apply()
                val activity = activity
                activity?.setResult(RESULT_RESTART)
                return true
            }
            else -> return super.onPreferenceTreeClick(preference)
        }
        return true
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        val activity = activity ?: return
        if (sharedPreferences == null || key == null) return

        when (key) {
            "video_action_switch" -> if (!AndroidUtil.isOOrLater && findPreference<ListPreference>(key)?.value == "2"
                    && !Permissions.canDrawOverlays(activity))
                Permissions.checkDrawOverlaysPermission(activity)
            PLAYBACK_HISTORY -> {
                if (sharedPreferences.getBoolean(key, true)) {
                    findPreference<CheckBoxPreference>(AUDIO_RESUME_PLAYBACK)?.isChecked = true
                    findPreference<CheckBoxPreference>(VIDEO_RESUME_PLAYBACK)?.isChecked = true
                }
            }
        }
    }
}
