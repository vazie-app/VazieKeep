package app.vazie.keep

// Device tests start from an app that knows no vault. Forgetting only the active one would leave the rest.
fun AppContainer.forgetAllVaults() {
    registry.snapshot.value.vaults.forEach { registry.remove(it.id) }
}
