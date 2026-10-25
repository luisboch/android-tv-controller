package dev.atvremote.tv

import android.app.Application

class App : Application() {
    val store by lazy { PairingStore(this) }
}
