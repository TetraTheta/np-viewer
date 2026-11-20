package io.github.tetratheta.npviewer.activity

import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import io.github.tetratheta.npviewer.R
import io.github.tetratheta.npviewer.userscript.UserScript
import io.github.tetratheta.npviewer.userscript.UserScriptRepository
import java.util.Collections

class UserScriptOrderActivity : AppCompatActivity() {
  private lateinit var adapter: OrderAdapter
  private lateinit var repository: UserScriptRepository

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_user_script_order)
    repository = UserScriptRepository(this)
    adapter = OrderAdapter(repository.getAll().toMutableList())
    setupInsets()
    setupToolbar()
    setupList()
  }

  private fun setupInsets() {
    ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.user_script_order_root)) { view, insets ->
      val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
      view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
      insets
    }
  }

  private fun setupToolbar() {
    findViewById<ImageButton>(R.id.user_script_order_back).apply {
      TooltipCompat.setTooltipText(this, getString(R.string.tooltip_back))
      setOnClickListener { finish() }
    }
  }

  private fun setupList() {
    val list = findViewById<RecyclerView>(R.id.user_script_order_list)
    list.adapter = adapter
    ItemTouchHelper(
      object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun onMove(
          recyclerView: RecyclerView,
          viewHolder: RecyclerView.ViewHolder,
          target: RecyclerView.ViewHolder,
        ): Boolean = adapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)

        override fun onSwiped(
          viewHolder: RecyclerView.ViewHolder,
          direction: Int,
        ) = Unit

        override fun onSelectedChanged(
          viewHolder: RecyclerView.ViewHolder?,
          actionState: Int,
        ) {
          super.onSelectedChanged(viewHolder, actionState)
          if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
            viewHolder?.itemView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
          }
        }

        override fun clearView(
          recyclerView: RecyclerView,
          viewHolder: RecyclerView.ViewHolder,
        ) {
          super.clearView(recyclerView, viewHolder)
          repository.saveAll(adapter.items)
        }
      },
    ).attachToRecyclerView(list)
  }

  private class OrderAdapter(
    val items: MutableList<UserScript>,
  ) : RecyclerView.Adapter<OrderAdapter.ViewHolder>() {
    override fun onCreateViewHolder(
      parent: ViewGroup,
      viewType: Int,
    ): ViewHolder = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_user_script_order, parent, false))

    override fun onBindViewHolder(
      holder: ViewHolder,
      position: Int,
    ) {
      val item = items[position]
      holder.name.text = item.metadata.name
      holder.details.text =
        item.metadata.version
          .takeIf(String::isNotBlank)
          ?.let { "v$it" }
          .orEmpty()
    }

    override fun getItemCount(): Int = items.size

    fun move(
      from: Int,
      to: Int,
    ): Boolean {
      if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
      Collections.swap(items, from, to)
      notifyItemMoved(from, to)
      return true
    }

    class ViewHolder(
      view: View,
    ) : RecyclerView.ViewHolder(view) {
      val name: TextView = view.findViewById(R.id.user_script_order_name)
      val details: TextView = view.findViewById(R.id.user_script_order_details)
    }
  }
}
