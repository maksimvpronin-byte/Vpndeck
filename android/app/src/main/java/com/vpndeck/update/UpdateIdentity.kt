package com.vpndeck.update

/** One policy for every downloaded APK, independent of the download source. */
internal fun requireTrustedUpdate(
    packageName: String, installedPackage: String,
    version: Long, installedVersion: Long,
    signers: Set<String>, installedSigners: Set<String>,
) {
    require(packageName == installedPackage) { "Это не VpnDeck" }
    require(version > installedVersion) { "Версия не новее установленной" }
    require(signers.isNotEmpty() && signers == installedSigners) { "Подпись обновления не совпадает — установка отменена" }
}
