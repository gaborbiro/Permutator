package app.gaborbiro.permutator

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.qtalk.recyclerviewfastscroller.RecyclerViewFastScroller
import kotlinx.android.synthetic.main.list_item_candidate.view.*

class PermutatorAdapter(private val onDataChanged: (List<Permutation>) -> Unit) :
    RecyclerView.Adapter<PermutationVH>(),
    RecyclerViewFastScroller.OnPopupTextUpdate {

    var data: List<Permutation>? = null
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onBindViewHolder(holder: PermutationVH, position: Int) {
        data?.let {
            holder.bind(position + 1, it[position]) { _, isChecked ->
                it[position].checked = isChecked
                onDataChanged.invoke(it)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PermutationVH {
        val inflater = LayoutInflater.from(parent.context)
        val view = inflater.inflate(R.layout.list_item_candidate, parent, false)
        return PermutationVH(view)
    }

    override fun getItemCount(): Int {
        return data?.size ?: 0
    }

    override fun onChange(position: Int): CharSequence {
        return data?.let {
            it[position].things.joinToString()
        } ?: ""
    }
}

class PermutationVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
    private val container = itemView as LinearLayout

    fun bind(
        position: Int,
        data: Permutation,
        checkedChangeListener: CompoundButton.OnCheckedChangeListener
    ) {
        itemView.checked.setOnCheckedChangeListener(null)
        itemView.checked.isChecked = data.checked
        itemView.checked.setOnCheckedChangeListener(checkedChangeListener)
        itemView.text_view_index.text = "$position."
        val layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        layoutParams.marginStart =
            container.context.resources.getDimensionPixelSize(R.dimen.margin_normal)

        itemView.checked.text = data.things.joinToString(" ")
    }
}