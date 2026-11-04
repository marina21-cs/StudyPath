package com.studypath.app.data

/** Client minimum for new passwords. Mirror any stricter Firebase Authentication console policy here. */
object PasswordPolicy {
    const val MIN_LENGTH = 8
    const val REQUIREMENT = "Use at least $MIN_LENGTH characters."

    fun accepts(password: String): Boolean = password.length >= MIN_LENGTH && password.isNotBlank()
}
