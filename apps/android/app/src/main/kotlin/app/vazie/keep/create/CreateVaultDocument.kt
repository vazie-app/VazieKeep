package app.vazie.keep.create

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts

// The new file is read back and saved to later, so the grant is asked to be persistable and read-write.
class CreateVaultDocument : ActivityResultContracts.CreateDocument("application/octet-stream") {
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
}
