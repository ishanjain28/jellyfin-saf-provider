package me.ishan.jellyfin_saf

import kotlinx.serialization.Serializable

@Serializable
enum class ContentState {
	COMPLETE, PARTIAL
}