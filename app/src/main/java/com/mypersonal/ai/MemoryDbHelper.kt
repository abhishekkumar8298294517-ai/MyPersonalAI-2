package com.mypersonal.ai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class ChatMessage(
    val id: Long = 0,
    val role: String,
    val text: String
)

data class MemoryItem(
    val id: Long = 0,
    val fact: String
)

class MemoryDbHelper(context: Context) :
    SQLiteOpenHelper(context, "personal_ai_memory.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                role TEXT NOT NULL,
                text TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE memories (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                fact TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS messages")
        db.execSQL("DROP TABLE IF EXISTS memories")
        onCreate(db)
    }

    fun addMessage(role: String, text: String) {
        val values = ContentValues().apply {
            put("role", role)
            put("text", text)
            put("timestamp", System.currentTimeMillis())
        }
        writableDatabase.insert("messages", null, values)
    }

    fun getRecentMessages(limit: Int = 20): List<ChatMessage> {
        val list = mutableListOf<ChatMessage>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, role, text FROM messages ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    ChatMessage(
                        id = it.getLong(0),
                        role = it.getString(1),
                        text = it.getString(2)
                    )
                )
            }
        }
        return list.reversed()
    }

    fun clearMessages() {
        writableDatabase.delete("messages", null, null)
    }

    fun addMemory(fact: String) {
        val cleanFact = fact.trim()
        if (cleanFact.isEmpty()) return
        val values = ContentValues().apply {
            put("fact", cleanFact)
            put("timestamp", System.currentTimeMillis())
        }
        writableDatabase.insert("memories", null, values)
    }

    fun getAllMemories(): List<MemoryItem> {
        val list = mutableListOf<MemoryItem>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, fact FROM memories ORDER BY id DESC",
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    MemoryItem(
                        id = it.getLong(0),
                        fact = it.getString(1)
                    )
                )
            }
        }
        return list
    }

    fun deleteMemory(id: Long) {
        writableDatabase.delete("memories", "id = ?", arrayOf(id.toString()))
    }
    }
    
