package com.bookshelf.app.domain

object Isbn {
    fun normalize(raw: String): String? {
        val value = raw.uppercase()
            .replace("-", "")
            .replace(" ", "")
            .trim()

        return when {
            value.length == 13 && value.all(Char::isDigit) && validate13(value) &&
                (value.startsWith("978") || value.startsWith("979")) -> value
            value.length == 10 && validate10(value) -> to13(value)
            else -> null
        }
    }

    fun validate13(value: String): Boolean {
        if (value.length != 13 || !value.all(Char::isDigit)) return false
        val sum = value.take(12).mapIndexed { index, c ->
            c.digitToInt() * if (index % 2 == 0) 1 else 3
        }.sum()
        val check = (10 - (sum % 10)) % 10
        return check == value.last().digitToInt()
    }

    fun validate10(value: String): Boolean {
        if (value.length != 10) return false
        val first = value.take(9)
        if (!first.all(Char::isDigit)) return false
        val last = value.last()
        if (!(last.isDigit() || last == 'X')) return false

        val sum = value.mapIndexed { index, c ->
            val digit = if (index == 9 && c == 'X') 10 else c.digitToInt()
            digit * (10 - index)
        }.sum()
        return sum % 11 == 0
    }

    fun to13(isbn10: String): String {
        val base = "978" + isbn10.take(9)
        val sum = base.mapIndexed { index, c ->
            c.digitToInt() * if (index % 2 == 0) 1 else 3
        }.sum()
        return base + ((10 - sum % 10) % 10)
    }
}
