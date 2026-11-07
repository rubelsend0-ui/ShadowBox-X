package com.shadowbox.x.domain.model

enum class FileKind { IMAGE, VIDEO, AUDIO, DOCUMENT, PDF, ARCHIVE, TEXT, OTHER }
enum class CredentialType { PASSWORD, PIN4, PIN6, CUSTOM_PIN }

data class Folder(
    val id: String, val parentId: String?, val name: String, val color: Int, val icon: String,
    val hidePreviews: Boolean, val hasPin: Boolean, val biometric: Boolean,
    val createdAt: Long, val updatedAt: Long
) { val isProtected: Boolean get() = hasPin || biometric }

data class VaultFile(
    val id: String, val folderId: String?, val name: String, val mime: String, val kind: FileKind,
    val size: Long, val blobSize: Long, val favorite: Boolean, val hasThumb: Boolean,
    val createdAt: Long, val updatedAt: Long
)

data class Credential(
    val id: String, val title: String, val url: String, val username: String, val email: String,
    val password: String, val notes: String, val recoveryCodes: String, val category: String,
    val favorite: Boolean, val createdAt: Long, val updatedAt: Long
)

data class Note(
    val id: String, val title: String, val body: String, val category: String, val pinned: Boolean,
    val locked: Boolean, val attachmentIds: List<String>, val createdAt: Long, val updatedAt: Long
)
