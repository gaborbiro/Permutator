package app.gaborbiro.permutator

import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.preference.PreferenceManager
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

        edit_text_things.setText(prefs.getString(PREF_THINGS, ""))
        edit_text_things.addTextChangedListener(object : TextWatcherAdapter() {

            override fun afterTextChanged(editable: Editable?) {
                prefs.edit {
                    putString(PREF_THINGS, editable?.toString())
                }
                onInputUpdated()
            }
        })

        edit_text_size.setText(prefs.getString(PREF_SIZE, ""))
        edit_text_size.addTextChangedListener(object : TextWatcherAdapter() {

            override fun afterTextChanged(editable: Editable?) {
                prefs.edit {
                    putString(PREF_SIZE, editable?.toString())
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
        }
        prefs.getString(PREF_PERMUTATIONS, null)?.let {
            val checkType = object : TypeToken<List<Permutation>>() {}.type
            val things: List<Permutation> = gson.fromJson(it, checkType)
            adapter.data = things
        }
    }

    private fun onInputUpdated() {
        val things = edit_text_things.text.toString()
        val size = edit_text_size.text.toString()

        if (things.isNotEmpty() && size.isNotEmpty()) {
            val thingsList: List<String> =
                things.split("[,;\\s]+".toRegex()).map { it.toLowerCase().capitalize().trim() }
                    .filter { it.isNotBlank() }
            val sizeInt = size.toInt()

            if (currentThings?.equals(thingsList) == false || currentSize != sizeInt) {
                subscription?.dispose()
                currentThings = thingsList
                currentSize = sizeInt
                subscription = generate(thingsList, sizeInt)
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
                                    putString(PREF_PERMUTATIONS, gson.toJson(it))
                                }
                            } ?: run {
                                adapter.data = null
                                prefs.edit {
                                    remove(PREF_PERMUTATIONS)
                                }
                            }
                        }
                    }
            }
        }
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
}

private const val PREF_THINGS = "PREF_THINGS"
private const val PREF_SIZE = "PREF_SIZE"
private const val PREF_PERMUTATIONS = "PREF_PERMUTATION"
