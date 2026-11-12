package com.josh.learn

import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Share a private JSON export of lessons, flashcards, paths and topics the user made. */
object LibraryExport {
    fun share(a: BaseActivity) {
        val json = JSONObject()
            .put("app", "Josh Learn")
            .put("version", BuildConfig.VERSION_NAME)
            .put("lessons", JSONArray().apply { Repo.lessons.forEach { put(it.toJson()) } })
            .put("cards", JSONArray().apply { Repo.cards.forEach { put(it.toJson()) } })
            .put("paths", JSONArray().apply { Repo.paths.forEach { put(it.toJson()) } })
            .put("customTopics", JSONArray().apply {
                Topics.custom(a).forEach { s ->
                    put(JSONObject().put("id", s.customId).put("title", s.title).put("emoji", s.emoji)
                        .put("description", s.description).put("createdAt", s.createdAt).put("emojiPicked", s.emojiPicked))
                }
            })
            .toString(2)
        val dir = File(a.cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, "josh-learn-library.json")
        f.writeText(json)
        val uri = FileProvider.getUriForFile(a, a.packageName + ".fileprovider", f)
        val send = Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        a.startActivity(Intent.createChooser(send, "Export library"))
        Toast.makeText(a, "Exported ${Repo.lessons.size} lessons.", Toast.LENGTH_SHORT).show()
    }
}
