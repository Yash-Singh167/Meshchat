package com.meshchat.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues

class MeshMessageStore(context: Context) :
    SQLiteOpenHelper(context, "meshchat.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "sender TEXT NOT NULL," +
                "body TEXT NOT NULL," +
                "timestamp INTEGER NOT NULL," +
                "relayed INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun add(sender: String, body: String, relayed: Boolean) {
        writableDatabase.insert(
            "messages", null,
            ContentValues().apply {
                put("sender", sender)
                put("body", body)
                put("timestamp", System.currentTimeMillis())
                put("relayed", if (relayed) 1 else 0)
            }
        )
    }

    fun recent(limit: Int = 100): List<String> =
        readableDatabase.query(
            "messages", arrayOf("sender", "body"),
            null, null, null, null, "timestamp DESC", limit.toString()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add("[" + cursor.getString(0) + "] " + cursor.getString(1))
                }
            }.asReversed()
        }
}
