package com.videobridge.core.network

import kotlinx.serialization.Serializable

@Serializable
data class DeviceRequestDto(
    val installId: String,
    val type: String,
    val name: String,
    val manufacturer: String? = null,
    val model: String? = null,
    val osVersion: String? = null,
    val appVersion: String? = null,
)

@Serializable
data class RegisterRequestDto(val name: String, val phone: String, val password: String, val device: DeviceRequestDto)

@Serializable
data class LoginRequestDto(val phone: String, val password: String, val device: DeviceRequestDto)

@Serializable
data class RefreshRequestDto(val refreshToken: String)

@Serializable
data class UserDto(val id: String, val phoneMasked: String, val displayName: String)

@Serializable
data class IdDto(val id: String)

@Serializable
data class AuthResponseDto(val accessToken: String, val refreshToken: String, val user: UserDto, val device: IdDto, val session: IdDto)

@Serializable
data class TokenPairDto(val accessToken: String, val refreshToken: String)
