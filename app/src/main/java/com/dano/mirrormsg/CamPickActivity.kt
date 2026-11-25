package com.dano.mirrormsg

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * MirrorMap Dash's chat picker for Dash Cam (action [CamRules.ACTION_PICK], started for
 * a result). With `slot` it picks the one chat that person's link goes to; with
 * `multi=true` the standing list of chats for the public link; `clear=true` forgets
 * the slot's chat (or the list) without showing anything. The choice stays here
 * ([CamChats]); the result tells Dash only `count` and, for a single chat, `network`.
 *
 * Shown only to MirrorMap Dash with a pinned certificate ([CamCallers]); anyone else
 * gets RESULT_CANCELED before anything is drawn.
 */
class CamPickActivity : Activity() {

    private class Row(val chat: CamChats.Chat, val view: View, val mark: CompoundButton)
    private class Section(val header: View, val rows: MutableList<Row> = ArrayList())

    private var multi = false
    private var slot: String? = null
    private val selected = LinkedHashMap<String, CamChats.Chat>()
    private val marks = HashMap<String, CompoundButton>()
    private val sections = ArrayList<Section>()
    private var query = ""

    private lateinit var list: LinearLayout
    private lateinit var done: Button

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val accent = Color.parseColor("#7C4DFF")
    private fun night() = (resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val bg by lazy { if (night()) Color.parseColor("#0E0E10") else Color.parseColor("#F5F4F8") }
    private val card by lazy { if (night()) Color.parseColor("#1C1C1F") else Color.WHITE }
    private val border by lazy { if (night()) Color.parseColor("#2A2A2E") else Color.parseColor("#E2E0E8") }
    private val textColor by lazy { if (night()) Color.parseColor("#F2F2F5") else Color.parseColor("#1A1A1E") }
    private val sub by lazy { if (night()) Color.parseColor("#9A9AA2") else Color.parseColor("#6B6B73") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The caller first: nothing is shown or changed for anyone but Dash.
        if (!CamCallers.isDash(this, callingPackage)) {
            LogStore.w(TAG, "Picker refused for another caller")
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        multi = intent.getBooleanExtra(CamRules.EXTRA_MULTI, false)
        if (!multi) {
            slot = intent.getStringExtra(CamRules.EXTRA_SLOT)
            if (!CamRules.validSlot(slot)) {
                LogStore.w(TAG, "Picker: bad slot")
                setResult(RESULT_CANCELED)
                finish()
                return
            }
        }
        if (intent.getBooleanExtra(CamRules.EXTRA_CLEAR, false)) {
            CamChats.save(this, slot, emptyList())
            LogStore.i(TAG, "Picker: forgot the ${if (multi) "list" else "chat"}")
            finishWith(emptyList())
            return
        }
        CamChats.boot(this)
        for (c in CamChats.load(this, slot)) selected[c.key] = c
        setContentView(buildUi())
        loadChats()
    }

    @Suppress("DEPRECATION")   // the pre-Android 11 insets
    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(accent)
            setPadding(dp(20), dp(12), dp(20), dp(16))
            addView(TextView(this@CamPickActivity).apply {
                text = "Dash Cam"; textSize = 22f; setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@CamPickActivity).apply {
                text = if (multi) "Pick the chats MirrorMap Dash sends your public Dash Cam link to."
                       else "Pick the chat MirrorMap Dash sends this person's Dash Cam link to."
                textSize = 13f; setTextColor(Color.WHITE); setPadding(0, dp(4), 0, 0)
            })
        }
        root.addView(header)

        root.addView(EditText(this).apply {
            hint = "Search chats"
            setHintTextColor(sub); setTextColor(textColor); textSize = 16f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            background = rounded(card, border, dp(10))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim() ?: ""; applyFilter() }
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(dp(16), dp(12), dp(16), 0) })

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }
        root.addView(ScrollView(this).apply { addView(list) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        done = Button(this).apply {
            isAllCaps = false; textSize = 16f
            setTextColor(Color.WHITE)
            background = rounded(accent, accent, dp(12))
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnClickListener { save() }
        }
        root.addView(done, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(dp(16), dp(8), dp(16), dp(16)) })
        updateDone()

        // Edge to edge: the header under the status bar, Done above the navigation bar
        // and the keyboard.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                top = i.top; bottom = i.bottom
            } else {
                top = insets.systemWindowInsetTop
                bottom = insets.systemWindowInsetBottom
            }
            header.setPadding(dp(20), top + dp(12), dp(20), dp(16))
            root.setPadding(0, 0, 0, bottom)
            insets
        }

        // What's picked now, at the top (it may be older than the lists show).
        if (selected.isNotEmpty()) {
            val s = addSection("Picked")
            for (c in selected.values) addRow(s, c, networkName(c.serviceId))
        }
        return root
    }

    private fun loadChats() {
        val app = applicationContext
        thread(name = "cam-pick") {
            val services = try { ServiceRegistry.loggedIn(app) } catch (e: Throwable) { emptyList() }
            val pending = ArrayList<Pair<MessagingService, Section>>()
            ui {
                if (services.isEmpty()) {
                    list.addView(note("No networks connected. Connect one in MirrorMsg first."))
                }
                for (svc in services) {
                    val s = addSection(label(svc))
                    synchronized(pending) { pending.add(svc to s) }
                    list.addView(note("Loading…").also { it.tag = svc.id })
                }
            }
            for (svc in services) {
                var chats = CamChats.chatsOf(svc)
                if (chats.isEmpty()) {
                    Thread.sleep(3000)   // just started: give the network a moment
                    chats = CamChats.chatsOf(svc)
                }
                ui {
                    val s = synchronized(pending) { pending.firstOrNull { it.first.id == svc.id }?.second } ?: return@ui
                    val loading = list.findViewWithTag<View>(svc.id)
                    var at = if (loading != null) list.indexOfChild(loading) else list.indexOfChild(s.header) + 1
                    if (loading != null) list.removeView(loading)
                    var shown = 0
                    for (c in chats) {
                        if (c.key in marks) {                       // already under "Picked": refresh it
                            selected[c.key]?.let { selected[c.key] = c }
                            continue
                        }
                        addRow(s, c, null, at++)
                        shown++
                    }
                    if (shown == 0) list.addView(note("No chats to show."), at)
                    applyFilter()
                }
            }
        }
    }

    private fun ui(block: () -> Unit) = runOnUiThread { if (!isFinishing && !isDestroyed) block() }

    private fun label(svc: MessagingService): String =
        if (svc is BeeperServiceBase) "${svc.displayName} · Beeper" else svc.displayName

    private fun networkName(serviceId: String): String? =
        try { ServiceRegistry.byId(this, serviceId)?.displayName } catch (e: Throwable) { null }

    private fun addSection(title: String): Section {
        val h = TextView(this).apply {
            text = title; textSize = 13f; setTextColor(sub)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(16), dp(4), dp(8))
        }
        list.addView(h)
        return Section(h).also { sections.add(it) }
    }

    private fun note(t: String) = TextView(this).apply {
        text = t; textSize = 14f; setTextColor(sub); setPadding(dp(4), dp(4), dp(4), dp(8))
    }

    private fun addRow(s: Section, chat: CamChats.Chat, network: String?, at: Int = -1) {
        val mark: CompoundButton = (if (multi) CheckBox(this) else RadioButton(this)).apply {
            isClickable = false; isFocusable = false
            buttonTintList = android.content.res.ColorStateList.valueOf(accent)
            isChecked = chat.key in selected
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(card, border, dp(10))
            setPadding(dp(14), dp(6), dp(6), dp(6))
            minimumHeight = dp(52)
            val texts = LinearLayout(this@CamPickActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@CamPickActivity).apply {
                    text = chat.name.ifEmpty { "(no name)" }; textSize = 16f; setTextColor(textColor)
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                if (network != null) addView(TextView(this@CamPickActivity).apply {
                    text = network; textSize = 12f; setTextColor(sub)
                })
            }
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(mark)
            setOnClickListener { toggle(chat) }
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(8)
        if (at >= 0) list.addView(row, at, lp) else list.addView(row, lp)
        marks[chat.key] = mark
        s.rows.add(Row(chat, row, mark))
    }

    private fun toggle(chat: CamChats.Chat) {
        if (chat.key in selected) {
            selected.remove(chat.key)
        } else {
            if (!multi) selected.clear()
            if (multi && selected.size >= CamRules.MAX_LIST_CHATS) {
                Toast.makeText(this, "Up to ${CamRules.MAX_LIST_CHATS} chats", Toast.LENGTH_SHORT).show()
                return
            }
            selected[chat.key] = chat
        }
        for ((k, m) in marks) m.isChecked = k in selected
        updateDone()
    }

    private fun updateDone() {
        done.text = when {
            selected.isEmpty() -> "Done (none picked)"
            multi -> "Done (${selected.size} picked)"
            else -> "Done"
        }
    }

    private fun applyFilter() {
        val q = query.lowercase()
        for (s in sections) {
            var any = false
            for (r in s.rows) {
                val show = q.isEmpty() || r.chat.name.lowercase().contains(q)
                r.view.visibility = if (show) View.VISIBLE else View.GONE
                any = any || show
            }
            s.header.visibility = if (any || q.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun save() {
        val chosen = selected.values.toList()
        CamChats.save(this, slot, chosen)
        LogStore.i(TAG, "Picker: ${chosen.size} ${if (multi) "chats in the list" else "chat"} picked")
        finishWith(chosen)
    }

    private fun finishWith(chosen: List<CamChats.Chat>) {
        val network = if (chosen.size == 1) networkName(chosen[0].serviceId) else null
        setResult(RESULT_OK, Intent()
            .putExtra(CamRules.EXTRA_COUNT, chosen.size)
            .putExtra(CamRules.EXTRA_NETWORK, network))
        finish()
    }

    private fun rounded(fill: Int, stroke: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill); cornerRadius = radius.toFloat(); setStroke(dp(1), stroke)
    }

    companion object {
        private const val TAG = "CamPick"
    }
}
