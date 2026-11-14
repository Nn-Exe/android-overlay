package com.nnexe.overlaybrowser

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Dialog for choosing the default engine and adding, editing, deleting and reordering engines. */
object SearchEngineDialog {

    fun show(activity: Activity, manager: SearchEngineManager, onChanged: () -> Unit) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.se_manage_title)
            .setView(ScrollView(activity).apply { addView(list) })
            .setPositiveButton(R.string.se_add, null)
            .setNegativeButton(R.string.se_done, null)
            .create()

        var render: () -> Unit = {}

        fun smallButton(label: String, description: String, enabled: Boolean, action: () -> Unit) =
            Button(activity, null, android.R.attr.borderlessButtonStyle).apply {
                text = label
                contentDescription = description
                minWidth = 0
                minimumWidth = dp(40)
                isEnabled = enabled
                setOnClickListener { action() }
            }

        fun editor(existing: SearchEngine?) {
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(8), dp(20), 0)
            }
            val name = EditText(activity).apply {
                hint = activity.getString(R.string.se_name_hint)
                setSingleLine()
                setText(existing?.name.orEmpty())
            }
            val url = EditText(activity).apply {
                hint = activity.getString(R.string.se_url_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setSingleLine()
                setText(existing?.template.orEmpty())
            }
            box.addView(name)
            box.addView(url)
            val editorDialog = AlertDialog.Builder(activity)
                .setTitle(if (existing == null) R.string.se_add_title else R.string.se_edit_title)
                .setView(box)
                .setPositiveButton(R.string.se_save, null)
                .setNegativeButton(R.string.se_cancel, null)
                .create()
            editorDialog.setOnShowListener {
                editorDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val nameText = name.text.toString()
                    val urlText = url.text.toString()
                    val error = if (existing == null) {
                        manager.add(nameText, urlText)
                    } else {
                        manager.update(existing.id, nameText, urlText)
                    }
                    when (error) {
                        null -> {
                            editorDialog.dismiss()
                            onChanged()
                            render()
                        }
                        SearchEngineManager.Error.NAME ->
                            Toast.makeText(activity, R.string.se_name_required, Toast.LENGTH_SHORT).show()
                        SearchEngineManager.Error.URL ->
                            Toast.makeText(activity, R.string.search_template_placeholder, Toast.LENGTH_SHORT).show()
                        SearchEngineManager.Error.NOT_EDITABLE -> editorDialog.dismiss()
                    }
                }
            }
            editorDialog.show()
        }

        fun confirmDelete(engine: SearchEngine) {
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.se_delete_title, engine.name))
                .setPositiveButton(R.string.se_delete) { _, _ ->
                    manager.delete(engine.id)
                    onChanged()
                    render()
                }
                .setNegativeButton(R.string.se_cancel, null)
                .show()
        }

        render = {
            list.removeAllViews()
            list.addView(
                TextView(activity).apply {
                    setText(R.string.se_tap_default)
                    textSize = 13f
                    setPadding(0, 0, 0, dp(8))
                },
            )
            val engines = manager.engines()
            val defaultId = manager.defaultId
            engines.forEachIndexed { index, engine ->
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val label = TextView(activity).apply {
                    text = if (engine.id == defaultId) "\u2605 ${engine.name}" else engine.name
                    textSize = 16f
                    setPadding(0, dp(12), dp(8), dp(12))
                    setOnClickListener {
                        manager.defaultId = engine.id
                        onChanged()
                        render()
                    }
                }
                row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(
                    smallButton("\u2191", activity.getString(R.string.se_move_up), index > 0) {
                        manager.move(engine.id, -1)
                        onChanged()
                        render()
                    },
                )
                row.addView(
                    smallButton("\u2193", activity.getString(R.string.se_move_down), index < engines.lastIndex) {
                        manager.move(engine.id, 1)
                        onChanged()
                        render()
                    },
                )
                if (!engine.builtin) {
                    row.addView(
                        smallButton("\u270E", activity.getString(R.string.se_edit_title), true) { editor(engine) },
                    )
                    row.addView(
                        smallButton("\u2715", activity.getString(R.string.se_delete), true) { confirmDelete(engine) },
                    )
                }
                list.addView(row)
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { editor(null) }
        }
        render()
        dialog.show()
    }
}
