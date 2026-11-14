package com.nnexe.overlaybrowser

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/** Setup screen: permissions, start/stop, home page, appearance, and Brave hand-off status. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var permOverlay: TextView
    private lateinit var btnPermOverlay: Button
    private lateinit var permNotif: TextView
    private lateinit var btnPermNotif: Button
    private lateinit var homeUrl: EditText
    private lateinit var braveStatus: TextView
    private lateinit var freeformStatus: TextView
    private lateinit var btnGetBrave: Button
    private lateinit var btnTestBrave: Button
    private var requestingPermission = false

    // Appearance controls
    private lateinit var switchRounded: Switch
    private lateinit var radiusSeek: SeekBar
    private lateinit var radiusValue: TextView
    private lateinit var borderSeek: SeekBar
    private lateinit var borderValue: TextView
    private lateinit var shadowSeek: SeekBar
    private lateinit var shadowValue: TextView
    private lateinit var opacitySeek: SeekBar
    private lateinit var opacityValue: TextView
    private lateinit var switchGlass: Switch
    private lateinit var switchBlur: Switch
    private lateinit var blurNote: TextView
    private lateinit var switchCompact: Switch
    private lateinit var themeGroup: RadioGroup

    /** True while the controls are being set from Prefs, so their listeners don't write straight back. */
    private var syncing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        permOverlay = findViewById(R.id.perm_overlay)
        btnPermOverlay = findViewById(R.id.btn_perm_overlay)
        permNotif = findViewById(R.id.perm_notif)
        btnPermNotif = findViewById(R.id.btn_perm_notif)
        homeUrl = findViewById(R.id.home_url)
        braveStatus = findViewById(R.id.brave_status)
        freeformStatus = findViewById(R.id.freeform_status)
        btnGetBrave = findViewById(R.id.btn_get_brave)
        btnTestBrave = findViewById(R.id.btn_test_brave)

        findViewById<Button>(R.id.btn_start).setOnClickListener { startOverlay() }
        findViewById<Button>(R.id.btn_stop).setOnClickListener { OverlayService.stop(this) }
        btnPermOverlay.setOnClickListener {
            requestingPermission = true
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btnPermNotif.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) {
                requestingPermission = true
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            }
        }

        homeUrl.setText(prefs.homeUrl)
        findViewById<Button>(R.id.btn_save_home).setOnClickListener {
            val url = Prefs.toUrl(homeUrl.text.toString()) ?: Prefs.DEFAULT_HOME
            prefs.homeUrl = url
            homeUrl.setText(url)
            Toast.makeText(this, R.string.home_saved, Toast.LENGTH_SHORT).show()
        }

        setupAppearance()

        btnGetBrave.setOnClickListener { Brave.openStore(this) }
        btnTestBrave.setOnClickListener {
            // Same spot the floating browser uses, so you can see the two line up.
            val w = prefs.width
            val h = prefs.height
            val bounds = if (w > 0 && h > 0 && prefs.x >= 0 && prefs.y >= 0) {
                Rect(prefs.x, prefs.y, prefs.x + w, prefs.y + h)
            } else {
                val dm = resources.displayMetrics
                Rect(dm.widthPixels / 3, dm.heightPixels / 8, dm.widthPixels - dm.widthPixels / 20, dm.heightPixels * 7 / 8)
            }
            Brave.open(this, prefs.homeUrl, bounds)
        }
    }

    override fun onResume() {
        super.onResume()
        requestingPermission = false
        refresh()
    }

    override fun onStop() {
        super.onStop()
        // Remove the Activity task when leaving while the overlay is running so custom frontends like
        // Cocoon FE don't treat our task as a backgrounded game and suspend/freeze the process, and
        // Chromium doesn't see a stopped Activity in ApplicationStatus.
        if (OverlayService.isRunning && !requestingPermission && !isChangingConfigurations) {
            finishAndRemoveTask()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        requestingPermission = false
        if (requestCode == REQ_NOTIF && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            // Denied twice: Android stops showing the prompt, so send them to the app's notification settings.
            if (Build.VERSION.SDK_INT >= 33 && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                requestingPermission = true
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
                )
            }
        }
        refresh()
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.no_overlay_permission, Toast.LENGTH_LONG).show()
            btnPermOverlay.performClick()
            return
        }
        OverlayService.show(this)
        // Finish and remove our Activity task rather than moving it to the back so launchers like
        // Cocoon FE don't suspend our process as a backgrounded game task.
        finishAndRemoveTask()
    }

    private fun refresh() {
        val overlayOk = Settings.canDrawOverlays(this)
        permOverlay.setText(if (overlayOk) R.string.perm_overlay_ok else R.string.perm_overlay_missing)
        btnPermOverlay.visibility = if (overlayOk) View.GONE else View.VISIBLE

        val notifOk = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        permNotif.setText(if (notifOk) R.string.perm_notif_ok else R.string.perm_notif_missing)
        btnPermNotif.visibility = if (notifOk) View.GONE else View.VISIBLE

        val brave = Brave.installedPackage(this)
        braveStatus.text = if (brave != null) getString(R.string.brave_found, brave) else getString(R.string.brave_missing)
        btnGetBrave.visibility = if (brave == null) View.VISIBLE else View.GONE
        btnTestBrave.visibility = if (brave != null) View.VISIBLE else View.GONE
        freeformStatus.setText(
            when (Brave.freeformEnabled(this)) {
                true -> R.string.freeform_on
                false -> R.string.freeform_off
                null -> R.string.freeform_unknown
            },
        )
        // Window blur can be switched off by power saving, so re-check each time the screen comes back.
        syncAppearance()
    }

    // region Appearance

    private fun setupAppearance() {
        switchRounded = findViewById(R.id.switch_rounded)
        radiusSeek = findViewById(R.id.radius_seek)
        radiusValue = findViewById(R.id.radius_value)
        borderSeek = findViewById(R.id.border_seek)
        borderValue = findViewById(R.id.border_value)
        shadowSeek = findViewById(R.id.shadow_seek)
        shadowValue = findViewById(R.id.shadow_value)
        opacitySeek = findViewById(R.id.opacity_seek)
        opacityValue = findViewById(R.id.opacity_value)
        switchGlass = findViewById(R.id.switch_glass)
        switchBlur = findViewById(R.id.switch_blur)
        blurNote = findViewById(R.id.blur_note)
        switchCompact = findViewById(R.id.switch_compact)
        themeGroup = findViewById(R.id.theme_group)

        findViewById<Button>(R.id.preset_minimal).setOnClickListener { applyPreset(StylePreset.MINIMAL) }
        findViewById<Button>(R.id.preset_rounded).setOnClickListener { applyPreset(StylePreset.ROUNDED) }
        findViewById<Button>(R.id.preset_ultra).setOnClickListener { applyPreset(StylePreset.ULTRA_ROUNDED) }
        findViewById<Button>(R.id.preset_glass).setOnClickListener { applyPreset(StylePreset.LIQUID_GLASS) }

        switchRounded.setOnCheckedChangeListener { _, on ->
            if (syncing) return@setOnCheckedChangeListener
            prefs.roundedCorners = on
            radiusSeek.isEnabled = on
            OverlayService.refresh(this)
        }
        bindSeek(radiusSeek, radiusValue, { getString(R.string.dp_value, it) }) { prefs.cornerRadiusDp = it }
        bindSeek(borderSeek, borderValue, { getString(R.string.dp_value, it) }) { prefs.borderDp = it }
        bindSeek(shadowSeek, shadowValue, { getString(R.string.percent, it) }) { prefs.shadow = it }
        // The floating window sits on top of this screen, so each change shows as you drag.
        bindSeek(opacitySeek, opacityValue, { getString(R.string.percent, it) }) { prefs.opacity = it / 100f }

        switchGlass.setOnCheckedChangeListener { _, on ->
            if (syncing) return@setOnCheckedChangeListener
            prefs.glass = on
            OverlayService.refresh(this)
        }
        switchBlur.setOnCheckedChangeListener { _, on ->
            if (syncing) return@setOnCheckedChangeListener
            prefs.blurBehind = on
            OverlayService.refresh(this)
        }
        switchCompact.setOnCheckedChangeListener { _, on ->
            if (syncing) return@setOnCheckedChangeListener
            prefs.compactBar = on
            OverlayService.refresh(this)
        }
        themeGroup.setOnCheckedChangeListener { _, checkedId ->
            if (syncing) return@setOnCheckedChangeListener
            prefs.amoled = checkedId == R.id.radio_amoled
            OverlayService.refresh(this)
        }
        syncAppearance()
    }

    private fun bindSeek(seek: SeekBar, label: TextView, format: (Int) -> String, onChange: (Int) -> Unit) {
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                label.text = format(progress)
                if (!fromUser || syncing) return
                onChange(progress)
                OverlayService.refresh(this@MainActivity)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}

            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
    }

    private fun applyPreset(preset: StylePreset) {
        preset.applyTo(prefs)
        syncAppearance()
        OverlayService.refresh(this)
    }

    /** Pushes the stored values into every Appearance control. */
    private fun syncAppearance() {
        syncing = true
        switchRounded.isChecked = prefs.roundedCorners
        radiusSeek.isEnabled = prefs.roundedCorners
        radiusSeek.progress = prefs.cornerRadiusDp
        borderSeek.progress = prefs.borderDp
        shadowSeek.progress = prefs.shadow
        opacitySeek.progress = (prefs.opacity * 100).roundToInt()
        switchGlass.isChecked = prefs.glass
        val blurOk = blurSupported()
        switchBlur.isEnabled = blurOk
        switchBlur.isChecked = blurOk && prefs.blurBehind
        blurNote.setText(if (blurOk) R.string.blur_help else R.string.blur_unsupported)
        switchCompact.isChecked = prefs.compactBar
        themeGroup.check(if (prefs.amoled) R.id.radio_amoled else R.id.radio_dark)
        // Setting progress doesn't always fire the listener (same value), so refresh the labels directly.
        radiusValue.text = getString(R.string.dp_value, radiusSeek.progress)
        borderValue.text = getString(R.string.dp_value, borderSeek.progress)
        shadowValue.text = getString(R.string.percent, shadowSeek.progress)
        opacityValue.text = getString(R.string.percent, opacitySeek.progress)
        syncing = false
    }

    private fun blurSupported(): Boolean =
        Build.VERSION.SDK_INT >= 31 && getSystemService(WindowManager::class.java).isCrossWindowBlurEnabled

    // endregion

    private companion object {
        const val REQ_NOTIF = 1
    }
}
