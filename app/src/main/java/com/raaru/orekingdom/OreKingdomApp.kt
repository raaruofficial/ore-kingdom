package com.raaru.orekingdom

import android.app.Application

/**
 * Application class. The forum's wallet work happens inside the client via
 * wallet-standard (Mobile Wallet Adapter intents leave through the shell),
 * so there is no native wallet session to own here.
 */
class OreKingdomApp : Application()
