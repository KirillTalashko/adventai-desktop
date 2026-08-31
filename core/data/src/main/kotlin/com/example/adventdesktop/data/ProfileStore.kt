package com.example.adventdesktop.data

import com.example.adventdesktop.domain.UserProfile
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File

/** Профиль предпочтений аккаунта: `profile.json` в каталоге аккаунта. */
class ProfileStore(private val store: FileStore) {
    private val file = File(store.root, "profile.json")

    fun load(): UserProfile? = runCatching {
        store.readText(file).takeIf { it.isNotBlank() }?.let { appJson.decodeFromString<ProfileDto>(it).toDomain() }
    }.getOrNull()

    fun save(profile: UserProfile) {
        store.writeText(file, appJson.encodeToString(profile.toDto()))
    }
}
