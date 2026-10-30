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
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/** Setup screen: permissions, start/stop, home page, and Brave hand-off status. */
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
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btnPermNotif.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }

        homeUrl.setText(prefs.homeUrl)
        findViewById<Button>(R.id.btn_save_home).setOnClickListener {
            val url = Prefs.toUrl(homeUrl.text.toString()) ?: Prefs.DEFAULT_HOME
            prefs.homeUrl = url
            homeUrl.setText(url)
            Toast.makeText(this, R.string.home_saved, Toast.LENGTH_SHORT).show()
        }

        val opacityValue = findViewById<TextView>(R.id.opacity_value)
        val opacitySeek = findViewById<SeekBar>(R.id.opacity_seek)
        opacitySeek.progress = (prefs.opacity * 100).roundToInt()
        opacityValue.text = getString(R.string.percent, opacitySeek.progress)
        opacitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                opacityValue.text = getString(R.string.percent, progress)
                if (!fromUser) return
                prefs.opacity = progress / 100f
                // The floating window sits on top of this screen, so the change shows as you drag.
                OverlayService.refresh(this@MainActivity)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}

            override fun onStopTrackingTouch(bar: SeekBar) {}
        })

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
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            // Denied twice: Android stops showing the prompt, so send them to the app's notification settings.
            if (Build.VERSION.SDK_INT >= 33 && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
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
        // Step out of the way so the window floats over whatever was underneath.
        moveTaskToBack(true)
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
    }

    private companion object {
        const val REQ_NOTIF = 1
    }
}
