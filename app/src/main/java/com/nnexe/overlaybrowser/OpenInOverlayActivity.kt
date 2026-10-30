package com.nnexe.overlaybrowser

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Patterns

/** Invisible entry point for "Share → Open in floating browser" and for picking this app to open a link. */
class OpenInOverlayActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.let { text ->
                if (text.none { it.isWhitespace() }) {
                    text
                } else {
                    // Shared text often wraps the link ("Check this out: https://…"); pull the link out.
                    val m = Patterns.WEB_URL.matcher(text)
                    if (m.find()) m.group() else text
                }
            }
            else -> null
        }
        if (Settings.canDrawOverlays(this)) {
            OverlayService.show(this, target)
        } else {
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }
}
