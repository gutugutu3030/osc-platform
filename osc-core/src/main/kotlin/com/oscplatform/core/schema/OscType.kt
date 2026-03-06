package com.oscplatform.core.schema

enum class OscType {
    INT,
    FLOAT,
    STRING,
    ;

    companion object {
        fun fromToken(token: String): OscType {
            return when (token.trim().lowercase()) {
                "int", "integer", "i" -> INT
                "float", "f" -> FLOAT
                "string", "str", "s" -> STRING
                else -> error("Unsupported OSC type: $token")
            }
        }
    }
}
