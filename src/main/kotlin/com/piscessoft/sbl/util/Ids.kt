package com.piscessoft.sbl.util

import java.util.UUID

/** Central identifier factory. UUIDs are stored as TEXT in SQLite. */
object Ids {
    fun newId(): String = UUID.randomUUID().toString()
}
