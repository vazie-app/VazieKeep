package app.vazie.keep.autofillhost

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewStructure
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

// A login form in a native app. `--es claim example.com` makes both fields claim that web domain,
// the way a malicious app could, so Vazie Keep's refusal to believe it can be tested.
class HostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val claim = intent.getStringExtra("claim")
        val username = field(this, claim, "username", InputType.TYPE_CLASS_TEXT)
        val password = field(this, claim, "password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val result = TextView(this).apply { tag = "result" }
        val update = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                result.text = "user=${username.text} password=${password.text}"
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        }
        username.addTextChangedListener(update)
        password.addTextChangedListener(update)
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 48)
                addView(TextView(context).apply { text = "Host login form (test)"; textSize = 20f })
                addView(username)
                addView(password)
                addView(result)
            },
        )
    }

    private fun field(context: Context, claim: String?, hint: String, type: Int): EditText = object : EditText(context) {
        override fun onProvideAutofillStructure(structure: ViewStructure, flags: Int) {
            super.onProvideAutofillStructure(structure, flags)
            if (claim != null) structure.setWebDomain(claim)
        }
    }.apply {
        this.hint = hint
        inputType = type
        setAutofillHints(if (hint == "username") View.AUTOFILL_HINT_USERNAME else View.AUTOFILL_HINT_PASSWORD)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
        tag = hint
    }
}
