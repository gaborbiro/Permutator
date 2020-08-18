package app.gaborbiro.permutator

import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.google.android.material.chip.Chip
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import io.reactivex.subjects.PublishSubject
import io.reactivex.subjects.Subject
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.content_main.*
import kotlinx.android.synthetic.main.flow_actions.view.*
import java.util.*


class MainActivity : AppCompatActivity() {

    private val adapter: PermutatorAdapter = PermutatorAdapter {
        prefs.edit {
            putString(PREF_PERMUTATIONS, gson.toJson(it))
        }
    }

    private var subscription: Disposable? = null
    private var currentThings: List<String>? = null
    private var currentSize: Int? = null
    private var lastProgress: Int? = null

    private val gson = Gson()
    private val prefs: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(
            this
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)

        val thingsStr = prefs.getString(PREF_THINGS, null)
        edit_text_things.setText(thingsStr)
        edit_text_things.addTextChangedListener(object : TextWatcherAdapter() {

            override fun afterTextChanged(editable: Editable?) {
                prefs.edit {
                    putString(
                        PREF_THINGS,
                        editable?.toString()?.trim()?.let { if (it.isNotBlank()) it else null })
                }
                onInputUpdated()
            }
        })

        val sizeStr = prefs.getString(PREF_SIZE, null)
        edit_text_size.setText(sizeStr)
        edit_text_size.addTextChangedListener(object : TextWatcherAdapter() {

            override fun afterTextChanged(editable: Editable?) {
                prefs.edit {
                    putString(
                        PREF_SIZE,
                        editable?.toString()?.trim()?.let { if (it.isNotBlank()) it else null })
                }
                onInputUpdated()
            }
        })

        recycler_view.adapter = adapter
        button_clear.setOnClickListener {
            prefs.edit {
                remove(PREF_PERMUTATIONS)
                remove(PREF_THINGS)
                remove(PREF_SIZE)
            }
            edit_text_things.text = null
            edit_text_size.text = null
            adapter.data = null
            clearActionsContainer()
        }
        prefs.getString(PREF_PERMUTATIONS, null)?.let {
            val checkType = object : TypeToken<List<Permutation>>() {}.type
            adapter.data = gson.fromJson(it, checkType)
        }

        Pair(thingsStr, sizeStr).notNull { thingsStr, sizeStr ->
            val things: List<String> = mapThingsStr(thingsStr)
            val size = sizeStr.toInt()
            setupAdvancedActions(things, size)
        }

        toggle_advanced.setOnCheckedChangeListener { _, isChecked ->
            flow_actions_container.visibility = if (isChecked) View.VISIBLE else View.GONE
        }
        flow_actions_container.visibility = View.GONE
    }

    private fun onInputUpdated() {
        val thingsStr = edit_text_things.text.toString()
        val sizeStr = edit_text_size.text.toString()

        if (thingsStr.isNotEmpty() && sizeStr.isNotEmpty()) {
            val things: List<String> = mapThingsStr(thingsStr)
            val size: Int = sizeStr.toInt()

            if (currentThings?.equals(things) == false || currentSize != size) {
                subscription?.dispose()
                currentThings = things
                currentSize = size
                subscription = generate(things, size)
                    .subscribeOn(Schedulers.computation())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe {
                        if (it.loading) {
                            text_view_progress.show()
                            progress_indicator.visibility = View.VISIBLE
                            scroller_container.hide()
                            text_view_progress.text = "${it.progress}%"
                        } else {
                            text_view_progress.hide()
                            progress_indicator.visibility = View.GONE
                            scroller_container.show()
                            it.content?.toList()?.let {
                                adapter.data = it
                                prefs.edit {
                                    putString(
                                        PREF_PERMUTATIONS,
                                        if (it.isNotEmpty()) gson.toJson(it) else null
                                    )
                                }
                            } ?: run {
                                adapter.data = null
                                prefs.edit {
                                    remove(PREF_PERMUTATIONS)
                                }
                            }
                        }
                    }
                setupAdvancedActions(things, size)
            }
        }
    }

    private fun setupAdvancedActions(things: List<String>, size: Int) {
        clearActionsContainer()
        for (i in 0 until size) {
            things.forEach { thing ->
                addAction("${i + 1} - $thing") {
                    adapter.data?.forEach {
                        if (it.things[i] == thing) {
                            it.checked = true
                        }
                    }
                    adapter.notifyDataSetChanged()
                    prefs.edit {
                        putString(
                            PREF_PERMUTATIONS,
                            if (adapter.data?.isNotEmpty() == true) gson.toJson(adapter.data) else null
                        )
                    }
                }
            }
        }
    }

    private fun mapThingsStr(thingsStr: String): List<String> {
        return thingsStr.split("[,;\\s]+".toRegex()).map { it.toLowerCase().capitalize().trim() }
            .filter { it.isNotBlank() }
    }

    private fun generate(things: List<String>, size: Int): Observable<Lce<Array<Permutation>>> {
        return Observable.create { emitter ->
            emitter.onNext(Lce.loading(0))
            val collector = PublishSubject.create<Candidate>()
            val result = mutableListOf<Permutation>()
            Pair(things, size).notNull { things, size ->
                val target = Math.pow(things.size.toDouble(), size.toDouble())
                collector
                    .doOnComplete {
                        result.sortBy { it.duplicateCount }
                        emitter.onNext(Lce.content(result.toTypedArray()))
                        subscription = null
                    }
                    .subscribe {
                        val data: List<String> =
                            Collections.unmodifiableList(it.data.map { things[it] })
                        result.add(Permutation(things = data, duplicateCount = it.longestRepeat()))

                        ((result.size / target) * 100).toInt().let {
                            if (it != lastProgress) {
                                lastProgress = it
                                emitter.onNext(Lce.loading(it))
                            }
                        }
                    }
                permutate(Candidate(size), things.size, collector)
                collector.onComplete()
            }
        }
    }

    private fun permutate(
        candidate: Candidate,
        count: Int,
        emitter: Subject<Candidate>,
        index: Int = 0
    ) {
        while (candidate.data[index] < count - 1) {
            candidate.inc(index)
            if (index < candidate.data.size - 1) {
                permutate(candidate, count, emitter, index + 1)
            } else {
                emitter.onNext(candidate)
            }
        }
        candidate.clear(index)
    }

    private fun addAction(title: String, onClicked: () -> Unit) {
        getActionsContainer().inflate(R.layout.list_item_action).also { chip ->
            chip.id = View.generateViewId()
            (chip as Chip).text = title
            getActionsContainer().apply {
                addView(chip)
                flow_actions.addView(chip)
                flow_actions.requestLayout()
            }
            chip.setOnClickListener {
                onClicked.invoke()
            }
        }
    }

    private fun getActionsContainer(): ViewGroup {
        if (flow_actions_container.childCount == 0) {
            flow_actions_container.add(R.layout.flow_actions)
        }
        return flow_actions_container
    }

    private fun clearActionsContainer() {
        flow_actions_container.removeAllViews()
    }
}

private const val PREF_THINGS = "PREF_THINGS"
private const val PREF_SIZE = "PREF_SIZE"
private const val PREF_PERMUTATIONS = "PREF_PERMUTATION"
