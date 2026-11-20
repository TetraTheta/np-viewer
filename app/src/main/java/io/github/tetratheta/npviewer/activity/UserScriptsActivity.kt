package io.github.tetratheta.npviewer.activity

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.tetratheta.npviewer.R
import io.github.tetratheta.npviewer.userscript.UserScript
import io.github.tetratheta.npviewer.userscript.UserScriptParser
import io.github.tetratheta.npviewer.userscript.UserScriptRepository
import io.github.tetratheta.npviewer.userscript.UserScriptUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UserScriptsActivity : AppCompatActivity() {
  private lateinit var adapter: UserScriptAdapter
  private lateinit var emptyView: View
  private lateinit var repository: UserScriptRepository
  private val fileLauncher =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) readFile(uri)
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_user_scripts)
    repository = UserScriptRepository(this)
    adapter = UserScriptAdapter(mutableListOf(), ::showSource, ::setEnabled)
    emptyView = findViewById(R.id.user_scripts_empty)
    setupInsets()
    setupToolbar()
    setupList()
    refreshList()
  }

  override fun onResume() {
    super.onResume()
    if (::adapter.isInitialized) refreshList()
  }

  private fun setupInsets() {
    ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.user_scripts_root)) { view, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
      view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
      insets
    }
  }

  private fun setupToolbar() {
    findViewById<ImageButton>(R.id.user_scripts_back).apply {
      TooltipCompat.setTooltipText(this, getString(R.string.tooltip_back))
      setOnClickListener { finish() }
    }
    findViewById<ImageButton>(R.id.user_scripts_add_url).apply {
      TooltipCompat.setTooltipText(this, getString(R.string.tooltip_add_user_script_url))
      setOnClickListener { showUrlDialog() }
    }
    findViewById<ImageButton>(R.id.user_scripts_more).apply {
      TooltipCompat.setTooltipText(this, getString(R.string.tooltip_more_user_script))
      setOnClickListener { showMoreMenu(this) }
    }
  }

  private fun showMoreMenu(anchor: View) {
    PopupMenu(this, anchor).apply {
      inflate(R.menu.user_scripts_more)
      setForceShowIcon(true)
      setOnMenuItemClickListener { item ->
        when (item.itemId) {
          R.id.menu_add_user_script_file -> {
            fileLauncher.launch(arrayOf("text/javascript", "application/javascript", "text/plain"))
            true
          }

          R.id.menu_user_script_order -> {
            startActivity(Intent(this@UserScriptsActivity, UserScriptOrderActivity::class.java))
            true
          }

          else -> false
        }
      }
      show()
    }
  }

  private fun setupList() {
    val list = findViewById<RecyclerView>(R.id.user_scripts_list)
    list.layoutManager = LinearLayoutManager(this)
    list.adapter = adapter
  }

  private fun showUrlDialog() {
    val input =
      EditText(this).apply {
        hint = "https://example.com/script.user.js"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
      }
    AlertDialog
      .Builder(this)
      .setTitle(R.string.title_add_user_script_url)
      .setView(input)
      .setPositiveButton(android.R.string.ok) { _, _ -> downloadForInstall(input.text.toString().trim()) }
      .setNegativeButton(R.string.btn_cancel, null)
      .show()
  }

  private fun downloadForInstall(url: String) {
    lifecycleScope.launch {
      runCatching { withContext(Dispatchers.IO) { UserScriptUpdater(this@UserScriptsActivity).download(url) } }
        .onSuccess { previewInstall(it, url) }
        .onFailure { showError(it) }
    }
  }

  private fun readFile(uri: Uri) {
    runCatching {
      contentResolver.openInputStream(uri)?.use { input ->
        UserScriptParser.readUtf8(input)
      } ?: error("Unable to open file")
    }.onSuccess { previewInstall(it, null) }.onFailure { showError(it) }
  }

  private fun previewInstall(
    source: String,
    sourceUrl: String?,
  ) {
    runCatching { UserScriptParser.parse(source) }
      .onSuccess { metadata ->
        val duplicate = repository.findDuplicate(metadata)
        val message =
          buildString {
            appendLine(metadata.name)
            if (metadata.version.isNotBlank()) appendLine(getString(R.string.label_user_script_version, metadata.version))
            appendLine(
              getString(
                R.string.label_user_script_run_at,
                metadata.runAt.name
                  .lowercase()
                  .replace('_', '-'),
              ),
            )
            append(
              getString(
                R.string.label_user_script_targets,
                (metadata.matches + metadata.includes).joinToString().ifBlank { "novelpia.com" },
              ),
            )
            if (duplicate != null) append("\n\n").append(getString(R.string.msg_user_script_replace))
          }
        AlertDialog
          .Builder(this)
          .setTitle(R.string.title_install_user_script)
          .setMessage(message)
          .setPositiveButton(if (duplicate == null) R.string.btn_install else R.string.btn_overwrite) { _, _ ->
            runCatching { repository.install(source, sourceUrl, duplicate?.id) }
              .onSuccess {
                refreshList()
                Toast.makeText(this, R.string.msg_user_script_installed, Toast.LENGTH_SHORT).show()
              }.onFailure { showError(it) }
          }.setNegativeButton(R.string.btn_cancel, null)
          .show()
      }.onFailure { showError(it) }
  }

  private fun showSource(script: UserScript) {
    AlertDialog
      .Builder(this)
      .setTitle(script.metadata.name)
      .setMessage(script.source)
      .setPositiveButton(android.R.string.ok, null)
      .setNegativeButton(R.string.btn_delete) { _, _ -> confirmDelete(script) }
      .show()
  }

  private fun confirmDelete(script: UserScript) {
    AlertDialog
      .Builder(this)
      .setTitle(R.string.title_delete_user_script)
      .setMessage(getString(R.string.msg_delete_user_script, script.metadata.name))
      .setPositiveButton(R.string.btn_delete) { _, _ ->
        repository.delete(script.id)
        refreshList()
      }.setNegativeButton(R.string.btn_cancel, null)
      .show()
  }

  private fun setEnabled(
    script: UserScript,
    enabled: Boolean,
  ) {
    runCatching { repository.setEnabled(script.id, enabled) }.onSuccess { refreshList() }.onFailure { showError(it) }
  }

  private fun refreshList() {
    val scripts =
      runCatching { repository.getAll() }.getOrElse {
        showError(it)
        emptyList()
      }
    adapter.replace(scripts)
    emptyView.visibility = if (scripts.isEmpty()) View.VISIBLE else View.GONE
  }

  private fun showError(error: Throwable) {
    Toast.makeText(this, error.message ?: getString(R.string.msg_user_script_error), Toast.LENGTH_LONG).show()
  }

  private class UserScriptAdapter(
    val items: MutableList<UserScript>,
    private val onClick: (UserScript) -> Unit,
    private val onEnabled: (UserScript, Boolean) -> Unit,
  ) : RecyclerView.Adapter<UserScriptAdapter.ViewHolder>() {
    override fun onCreateViewHolder(
      parent: ViewGroup,
      viewType: Int,
    ): ViewHolder = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_user_script, parent, false))

    override fun onBindViewHolder(
      holder: ViewHolder,
      position: Int,
    ) {
      val item = items[position]
      holder.name.text = item.metadata.name
      holder.details.text =
        listOfNotNull(
          item.metadata.version
            .takeIf(String::isNotBlank)
            ?.let { "v$it" },
          item.sourceUrl ?: holder.itemView.context.getString(R.string.label_user_script_local),
          item.lastUpdateError,
        ).joinToString(" · ")
      holder.enabled.setOnCheckedChangeListener(null)
      holder.enabled.isChecked = item.enabled
      holder.enabled.setOnCheckedChangeListener { _, checked -> onEnabled(item, checked) }
      holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size

    fun replace(next: List<UserScript>) {
      val diff =
        DiffUtil.calculateDiff(
          object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = items.size

            override fun getNewListSize(): Int = next.size

            override fun areItemsTheSame(
              oldItemPosition: Int,
              newItemPosition: Int,
            ): Boolean = items[oldItemPosition].id == next[newItemPosition].id

            override fun areContentsTheSame(
              oldItemPosition: Int,
              newItemPosition: Int,
            ): Boolean = items[oldItemPosition] == next[newItemPosition]
          },
        )
      items.clear()
      items.addAll(next)
      diff.dispatchUpdatesTo(this)
    }

    class ViewHolder(
      view: View,
    ) : RecyclerView.ViewHolder(view) {
      val name: TextView = view.findViewById(R.id.user_script_name)
      val details: TextView = view.findViewById(R.id.user_script_details)
      val enabled: SwitchCompat = view.findViewById(R.id.user_script_enabled)
    }
  }
}
