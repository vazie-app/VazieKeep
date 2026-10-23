package app.vazie.keep.chooser

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts

// A vault is read and saved, so the picker is asked for a persistable read-write grant.
class OpenVaultDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
}
