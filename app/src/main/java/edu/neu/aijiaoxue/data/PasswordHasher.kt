package edu.neu.aijiaoxue.data

import java.security.MessageDigest

/**
 * 密码不得明文存储（F0、NF-06）。Sprint 1 无后端，使用 SHA-256 + 固定盐即可满足演示要求；
 * 接入后端后改为服务端 bcrypt/argon2。
 */
object PasswordHasher {
    private const val SALT = "aijiaoxue-s1"

    fun hash(password: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$SALT:$password".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun verify(password: String, hash: String): Boolean = hash(password) == hash
}
