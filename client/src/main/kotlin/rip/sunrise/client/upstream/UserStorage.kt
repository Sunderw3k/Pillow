package rip.sunrise.client.upstream

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.security.SecureRandom
import java.util.UUID

/**
 * Stores DreamBot account data. Mostly for `sessionId`.
 */
object UserStorage {
    private val cacheFile = File(".user_cache").also {
        // Ensure valid JSON
        if (!it.exists()) it.writeText("[]")
    }
    private val gson = Gson()

    private val userListType = object : TypeToken<MutableList<User>>() {}.type

    fun addUser(user: User) {
        val users = getUsers().apply {
            // Replace if exists
            removeIf { it.name == user.name }
            add(user)
        }
        cacheFile.writeText(gson.toJson(users))
    }

    fun getUser(name: String) = getUsers().firstOrNull { it.name == name }

    fun getUsers(): MutableList<User> = gson.fromJson(cacheFile.readText(), userListType)
}

data class User(val name: String, val hardwareId: String, var sessionId: String)

@OptIn(ExperimentalStdlibApi::class)
fun generateHardwareId(): String = UUID.randomUUID().toString() + SecureRandom.getInstanceStrong().nextInt()