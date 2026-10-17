package com.anker.esiotkit.sample.support.platform

/** Android/JVM 实现：System.getenv */
actual fun platformGetenv(name: String): String? = System.getenv(name)
