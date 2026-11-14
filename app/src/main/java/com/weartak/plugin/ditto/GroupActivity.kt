// SPDX-License-Identifier: GPL-3.0-or-later
package com.weartak.plugin.ditto

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.weartak.plugin.ditto.group.GroupControls
import com.weartak.plugin.ditto.group.GroupOption
import com.weartak.plugin.ditto.group.PrivateGroups
import com.weartak.plugin.ditto.group.COMMUNITY
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.concurrent.Executors

class GroupActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val controls by lazy { GroupControls(PrivateGroups.forApp(applicationContext)) }
    private lateinit var name: EditText
    private lateinit var status: TextView
    private lateinit var options: LinearLayout
    private lateinit var createButton: Button
    private lateinit var importButton: Button
    private lateinit var exportButton: Button
    private var pendingImportName: String? = null
    private var waitingForExport: String? = null
    private var selectedGroup: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingImportName = savedInstanceState?.getString("pendingImportName")
        waitingForExport = savedInstanceState?.getString("waitingForExport")
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        fun text(value: Int): TextView = TextView(this).apply {
            setText(value)
            setPadding(0, 8, 0, 8)
        }
        content.addView(text(R.string.group_title).apply { textSize = 20f })
        content.addView(text(R.string.group_inactive_notice))
        status = text(R.string.group_loading)
        content.addView(status)
        options = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(options)
        name = EditText(this).apply {
            setSingleLine()
            setHint(R.string.group_name_hint)
            filters = arrayOf(InputFilter.LengthFilter(48))
        }
        content.addView(name)
        createButton = Button(this).apply {
            setText(R.string.group_create)
            setOnClickListener {
                val entered = name.text.toString()
                runOperation { create(entered) }
            }
        }
        content.addView(createButton)
        importButton = Button(this).apply {
            setText(R.string.group_import)
            setOnClickListener { confirmImport() }
        }
        content.addView(importButton)
        exportButton = Button(this).apply {
            setText(R.string.group_export)
            setOnClickListener { confirmExport() }
        }
        content.addView(exportButton)
        setContentView(ScrollView(this).apply { addView(content) })
        runOperation { Unit }
    }

    private fun confirmExport() {
        val group = selectedGroup
        if (group == null || group == COMMUNITY) {
            status.setText(R.string.group_export_select)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.group_export_title)
            .setMessage(R.string.group_export_warning)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.group_choose_destination) { _, _ ->
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_TITLE, "weartak-group-invitation.wtg")
                }
                try {
                    waitingForExport = group
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, REQUEST_EXPORT)
                } catch (_: ActivityNotFoundException) {
                    waitingForExport = null
                    status.setText(R.string.group_export_unavailable)
                } catch (_: SecurityException) {
                    waitingForExport = null
                    status.setText(R.string.group_export_unavailable)
                }
            }
            .show()
    }

    private fun confirmImport() {
        val entered = name.text.toString()
        if (!PrivateGroups.validName(entered)) {
            status.setText(R.string.group_input_error)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.group_import_title)
            .setMessage(R.string.group_import_warning)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.group_choose_file) { _, _ ->
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                try {
                    pendingImportName = entered
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, REQUEST_INVITATION)
                } catch (_: ActivityNotFoundException) {
                    pendingImportName = null
                    status.setText(R.string.group_import_unavailable)
                } catch (_: SecurityException) {
                    pendingImportName = null
                    status.setText(R.string.group_import_unavailable)
                }
            }
            .show()
    }

    @Deprecated("Android activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_EXPORT) {
            val group = waitingForExport ?: return
            waitingForExport = null
            if (resultCode != RESULT_OK) return
            val uri = data?.data
            if (uri == null || uri.scheme != "content") {
                status.setText(R.string.group_export_error)
                return
            }
            runOperation(success = R.string.group_export_success, ioFailure = R.string.group_export_error) {
                val invitation = invitationForExport(group)
                try {
                    val output = contentResolver.openOutputStream(uri, "wt")
                        ?: throw IOException("Invitation destination unavailable")
                    output.use { it.write(invitation); it.flush() }
                } finally {
                    invitation.fill(0)
                }
            }
            return
        }
        if (requestCode != REQUEST_INVITATION) return
        val entered = pendingImportName ?: return
        pendingImportName = null
        if (resultCode != RESULT_OK) return
        val uri = data?.data
        if (uri == null || uri.scheme != "content") {
            status.setText(R.string.group_import_error)
            return
        }
        runOperation(ioFailure = R.string.group_import_error) {
            val input = contentResolver.openInputStream(uri) ?: throw IOException("Invitation unavailable")
            input.use { importInvitation(entered, it) }
        }
    }

    private fun runOperation(
        success: Int = R.string.group_local_only,
        ioFailure: Int = R.string.group_storage_error,
        operation: GroupControls.() -> Unit,
    ) {
        setBusy(true)
        status.setText(R.string.group_loading)
        worker.execute {
            try {
                controls.operation()
                val selected = controls.selected()
                val list = controls.options()
                onUi {
                    renderGroups(selected, list)
                    status.setText(success)
                    setBusy(false)
                }
            } catch (_: IllegalArgumentException) {
                showFailure(R.string.group_input_error)
            } catch (_: GeneralSecurityException) {
                showFailure(R.string.group_storage_error)
            } catch (_: IOException) {
                showFailure(ioFailure)
            } catch (_: SecurityException) {
                showFailure(ioFailure)
            } catch (_: IllegalStateException) {
                showFailure(R.string.group_storage_error)
            } catch (_: RuntimeException) {
                showFailure(ioFailure)
            }
        }
    }

    private fun renderGroups(selected: String, groups: List<GroupOption>) {
        selectedGroup = selected
        options.removeAllViews()
        groups.forEach { group ->
            options.addView(Button(this).apply {
                text = if (group.id == selected) {
                    getString(R.string.group_active, group.label)
                } else {
                    getString(R.string.group_select, group.label)
                }
                isEnabled = group.id != selected
                setOnClickListener { runOperation { select(group.id) } }
            })
        }
    }

    private fun showFailure(message: Int) = onUi {
        status.setText(message)
        setBusy(false)
    }

    private fun setBusy(busy: Boolean) {
        createButton.isEnabled = !busy
        importButton.isEnabled = !busy
        exportButton.isEnabled = !busy
        name.isEnabled = !busy
        options.visibility = if (busy) View.INVISIBLE else View.VISIBLE
    }

    private fun onUi(block: () -> Unit) {
        runOnUiThread { if (!isFinishing && !isDestroyed) block() }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingImportName", pendingImportName)
        outState.putString("waitingForExport", waitingForExport)
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val REQUEST_INVITATION = 100
        private const val REQUEST_EXPORT = 101
    }
}
