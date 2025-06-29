package com.github.kr328.clash.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class TunStack {
    @SerialName("gvisor")
    GVisor,

    @SerialName("system")
    System,
}
