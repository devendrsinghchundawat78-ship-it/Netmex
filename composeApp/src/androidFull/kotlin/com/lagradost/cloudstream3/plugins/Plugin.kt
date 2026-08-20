package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.Resources

/**
 * Android host half of Cloudstream's extension ABI.
 *
 * The public BasePlugin API lives in Cloudstream's binary-compatible library.  Provider
 * extensions normally subclass this Android type, so Netmex supplies the same class name and
 * method signatures when a .cs3 dex is loaded by PathClassLoader.
 */
abstract class Plugin : BasePlugin() {
    @Throws(Throwable::class)
    open fun load(context: Context) {
        load()
    }

    var resources: Resources? = null
    var openSettings: ((context: Context) -> Unit)? = null
}
