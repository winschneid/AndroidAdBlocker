package com.winschneid.adblocker.core.filter

/**
 * A small, conservative set of well-known advertising and tracking domains that is active even before any
 * remote list has been downloaded (and when the device is offline). Subdomains are covered automatically.
 * The downloaded lists are the real source of coverage; this is only a safety net.
 */
object BuiltinBlocklist {
    val domains: Set<String> = setOf(
        // Google advertising / measurement
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "adservice.google.co.jp", "google-analytics.com", "googletagservices.com", "2mdn.net", "admob.com",
        // Major ad exchanges / SSPs / DSPs
        "adnxs.com", "adsrvr.org", "advertising.com", "adtechus.com", "criteo.com", "criteo.net",
        "rubiconproject.com", "pubmatic.com", "openx.net", "casalemedia.com", "indexww.com",
        "smartadserver.com", "taboola.com", "outbrain.com", "media.net", "amazon-adsystem.com",
        "bidswitch.net", "yieldmo.com", "sharethrough.com", "teads.tv", "triplelift.com", "33across.com",
        "adform.net", "adroll.com", "mathtag.com", "serving-sys.com", "sizmek.com", "flashtalking.com",
        "innovid.com", "spotxchange.com", "spotx.tv", "tremorhub.com", "undertone.com", "unrulymedia.com",
        "an.facebook.com", "an.yandex.ru", "mc.yandex.ru",
        // Pop-ups / aggressive networks
        "popads.net", "popcash.net", "propellerads.com", "adsterra.com", "exoclick.com", "trafficjunky.net",
        "juicyads.com", "zedo.com",
        // Measurement / tracking
        "moatads.com", "scorecardresearch.com", "quantserve.com", "chartbeat.com", "adsafeprotected.com",
        "doubleverify.com", "bluekai.com", "demdex.net", "krxd.net", "rlcdn.com", "agkn.com", "everesttech.net",
        // In-app ad SDKs
        "vungle.com", "applovin.com", "unityads.unity3d.com", "chartboost.com", "inmobi.com", "mopub.com",
        "adcolony.com", "tapjoy.com", "supersonicads.com", "startappservice.com",
        // Japanese ad networks
        "nend.net", "ad-stir.com", "fout.jp", "impact-ad.jp", "adingo.jp", "socdm.com", "genieesspv.jp",
        "gssprt.jp", "spad.i-mobile.co.jp", "spdeliver.i-mobile.co.jp", "send.microad.jp", "yads.c.yimg.jp",
        "l.logly.co.jp", "ladsp.com", "ust-ad.com",
    )
}
