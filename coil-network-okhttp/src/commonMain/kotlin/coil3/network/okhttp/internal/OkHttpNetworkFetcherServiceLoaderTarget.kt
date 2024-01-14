package coil3.network.okhttp.internal

import coil3.Uri
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.util.FetcherServiceLoaderTarget

class OkHttpNetworkFetcherServiceLoaderTarget : FetcherServiceLoaderTarget<Uri> {
    override fun factory() = OkHttpNetworkFetcherFactory()
    override fun type() = Uri::class
}
